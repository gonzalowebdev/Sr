package com.gonzalowebdev.sr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_RECORD = "com.gonzalowebdev.sr.RECORD"
        const val ACTION_SHOT = "com.gonzalowebdev.sr.SHOT"
        const val ACTION_STOP = "com.gonzalowebdev.sr.STOP"
        const val EX_CODE = "code"
        const val EX_DATA = "data"
        private const val CH_REC = "rec"
        private const val CH_DONE = "done"
        private const val ID_FG = 1
        private const val ID_DONE = 2
        private const val SR = 44100

        @Volatile
        var recording = false

        @Volatile
        var startedAt = 0L

        @Volatile
        var onChange: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var bg: HandlerThread? = null
    private var mode = 0 // 0 libre, 1 grabando, 2 captura
    private var stopping = false

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var vSurface: Surface? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var arec: AudioRecord? = null
    private var sink: MuxerSink? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var originUs = 0L

    @Volatile
    private var stopFlag = false

    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var shotTries = 0

    private val projCb = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post {
                if (mode == 1) stopRecording() else if (mode == 2) endAll()
            }
        }
    }

    private val startRec = Runnable {
        try {
            beginRecording()
        } catch (e: Exception) {
            fail("No se pudo grabar: ${e.message}")
        }
    }

    private val startShot = Runnable {
        try {
            beginShot()
        } catch (e: Exception) {
            fail("No se pudo capturar: ${e.message}")
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun dataExtra(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EX_DATA, Intent::class.java)
        else i.getParcelableExtra(EX_DATA)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (intent == null || (action != ACTION_RECORD && action != ACTION_SHOT && action != ACTION_STOP)) {
            if (mode == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            if (mode == 0) stopSelf() else stopRecording()
            return START_NOT_STICKY
        }
        if (mode != 0) return START_NOT_STICKY
        mode = if (action == ACTION_RECORD) 1 else 2
        channels()
        startForeground(
            ID_FG,
            notif(if (mode == 1) "Empezando…" else "Capturando…", mode == 1, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        try {
            val data = dataExtra(intent) ?: throw IllegalStateException("Permiso de captura inválido")
            val p = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EX_CODE, 0), data)
            p.registerCallback(projCb, main)
            proj = p
        } catch (e: Exception) {
            fail(e.message)
            return START_NOT_STICKY
        }
        if (mode == 1) {
            recording = true
            startedAt = 0
            onChange?.invoke()
            main.postDelayed(startRec, 1200) // tiempo para salir de la app
        } else {
            main.postDelayed(startShot, 700)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ grabación

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private var curLandscape = false
    private var rotating = false
    private var dlRegistered = false

    @Suppress("DEPRECATION")
    private fun displaySize(): android.graphics.Point {
        val d = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val pt = android.graphics.Point()
        d.getRealSize(pt)
        return pt
    }

    private val dl = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != 0 || mode != 1 || stopping || rotating || vd == null || venc == null) return
            val sz = displaySize()
            if ((sz.x > sz.y) != curLandscape) rotateSegment()
        }
    }

    /** Al rotar la pantalla se cierra el archivo actual y se sigue en uno nuevo con la orientación correcta. */
    private fun rotateSegment() {
        rotating = true
        Thread {
            closeRecording(true)
            main.post {
                try {
                    if (mode == 1 && !stopping && vd != null) beginRecording(true)
                } catch (e: Exception) {
                    fail(e.message)
                }
                rotating = false
            }
        }.start()
    }

    private fun beginRecording(reuse: Boolean = false) {
        val p = proj ?: return
        val st = Settings.of(this)
        val phys = displaySize()
        curLandscape = phys.x > phys.y
        if (!dlRegistered) {
            getSystemService(DisplayManager::class.java).registerDisplayListener(dl, main)
            dlRegistered = true
        }
        var hevc = st.hevc && Encoders.hasHevc()
        var plan = Encoders.plan(phys, st.shortSide, st.fps, hevc, st.quality)
        if (plan.info == null && hevc) {
            hevc = false
            plan = Encoders.plan(phys, st.shortSide, st.fps, false, st.quality)
        }
        if (plan.info == null) throw IllegalStateException("Este dispositivo no tiene encoder de video compatible")

        originUs = System.nanoTime() / 1000
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Sr_" + stamp() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Sr")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
            ?: throw IllegalStateException("No se pudo crear el archivo")
        outUri = uri
        val fd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("No se pudo abrir el archivo")
        pfd = fd
        val muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val withAudio = st.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val audio = if (withAudio) makeAudio(p) else null
        val s = MuxerSink(muxer, if (audio != null) 2 else 1, originUs)
        sink = s

        val enc = Encoders.createVideo(plan)
        venc = enc
        val surface = enc.createInputSurface()
        vSurface = surface
        enc.start()
        stopFlag = false
        vThread = Thread({ videoLoop(enc, s) }, "sr-video").also { it.start() }
        if (audio != null) {
            arec = audio.first
            aenc = audio.second
            audio.second.start()
            aThread = Thread({ audioLoop(audio.first, audio.second, s) }, "sr-audio").also { it.start() }
        }
        val old = vd
        if (reuse && old != null) {
            old.resize(plan.w, plan.h, resources.displayMetrics.densityDpi)
            old.surface = surface
        } else {
            vd = p.createVirtualDisplay(
                "sr", plan.w, plan.h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
            startedAt = SystemClock.elapsedRealtime()
        }
        updateFg("${plan.w}×${plan.h} · ${if (hevc) "H.265" else "H.264"} · ${plan.fps} fps", true, true)
        onChange?.invoke()
    }

    private fun makeAudio(p: MediaProjection): Pair<AudioRecord, MediaCodec>? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 2, 16384))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            null
        } else {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Pair(rec, enc)
        }
    } catch (e: Exception) {
        null
    }

    private fun videoLoop(enc: MediaCodec, s: MuxerSink) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = s.addTrack(enc.outputFormat)
                } else if (i >= 0) {
                    val buf = enc.getOutputBuffer(i)
                    if (buf != null && track >= 0 && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) s.write(track, buf, info)
                    enc.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (e: Exception) {
            if (!stopFlag) main.post { stopRecording() }
        }
    }

    private fun audioLoop(rec: AudioRecord, enc: MediaCodec, s: MuxerSink) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        var track = -1
        var frames = 0L
        var inDone = false
        var outDone = false
        var failed = false
        try {
            rec.startRecording()
            val t0 = System.nanoTime() / 1000
            while (!outDone) {
                if (!inDone) {
                    if (stopFlag || failed) {
                        val ii = enc.dequeueInputBuffer(20_000)
                        if (ii >= 0) {
                            enc.queueInputBuffer(ii, 0, 0, t0 + frames * 1_000_000L / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        }
                    } else {
                        val n = rec.read(pcm, 0, pcm.size)
                        if (n > 0) {
                            val pts = t0 + frames * 1_000_000L / SR
                            frames += n / 4
                            val ii = enc.dequeueInputBuffer(20_000)
                            if (ii >= 0) {
                                val b = enc.getInputBuffer(ii)!!
                                b.clear()
                                b.put(pcm, 0, n)
                                enc.queueInputBuffer(ii, 0, n, pts, 0)
                            }
                        } else if (n < 0) {
                            failed = true
                        }
                    }
                }
                var oi = enc.dequeueOutputBuffer(info, if (inDone) 10_000L else 0L)
                while (oi != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = s.addTrack(enc.outputFormat)
                    } else if (oi >= 0) {
                        val buf = enc.getOutputBuffer(oi)
                        if (buf != null && track >= 0 && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) s.write(track, buf, info)
                        enc.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outDone = true
                            break
                        }
                    }
                    oi = enc.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            if (track < 0) s.trackFailed()
        } finally {
            safe { rec.stop() }
        }
    }

    private fun stopRecording() {
        if (mode != 1 || stopping) return
        stopping = true
        main.removeCallbacks(startRec)
        if (vd == null) {
            closeRecording()
            endAll()
            return
        }
        updateFg("Guardando…", false, false)
        Thread {
            val uri = closeRecording()
            main.post {
                if (uri != null) {
                    notifyDone("Grabación guardada", uri, "video/mp4", true)
                    Toast.makeText(this, "Grabación guardada", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No se pudo guardar la grabación", Toast.LENGTH_LONG).show()
                }
                endAll()
            }
        }.start()
    }

    /** Cierra encoders, muxer y archivo. Devuelve el Uri si el video quedó válido. */
    private fun closeRecording(keepVd: Boolean = false): Uri? {
        stopFlag = true
        if (keepVd) {
            safe { vd?.surface = null }
        } else {
            safe { vd?.release() }
            vd = null
        }
        safe { venc?.signalEndOfInputStream() }
        safe { vThread?.join(4000) }
        safe { aThread?.join(4000) }
        vThread = null
        aThread = null
        val ok = try {
            sink?.finish() ?: false
        } catch (e: Exception) {
            false
        }
        sink = null
        safe { venc?.stop() }
        safe { venc?.release() }
        safe { aenc?.stop() }
        safe { aenc?.release() }
        venc = null
        aenc = null
        safe { vSurface?.release() }
        vSurface = null
        safe { arec?.release() }
        arec = null
        safe { pfd?.close() }
        pfd = null
        val uri = outUri
        outUri = null
        if (uri == null) return null
        return try {
            if (ok) {
                val cv = ContentValues()
                cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
                uri
            } else {
                contentResolver.delete(uri, null, null)
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ captura

    private fun beginShot() {
        val p = proj ?: return
        val size = realSize(this)
        val r = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 3)
        reader = r
        val t = HandlerThread("sr-bg")
        t.start()
        bg = t
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(this) {
                    safe { latest?.close() }
                    latest = img
                }
            }
        }, Handler(t.looper))
        vd = p.createVirtualDisplay(
            "sr-shot", size.x, size.y, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        shotTries = 0
        Handler(t.looper).postDelayed({ saveShot() }, 900)
    }

    private fun saveShot() {
        val img = synchronized(this) {
            val i = latest
            latest = null
            i
        }
        if (img == null) {
            if (++shotTries > 15) {
                main.post { fail("No se pudo capturar la pantalla") }
                return
            }
            bg?.looper?.let { Handler(it).postDelayed({ saveShot() }, 250) }
            return
        }
        var bmp: Bitmap? = null
        try {
            val plane = img.planes[0]
            val raw = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            bmp = if (raw.width != img.width) Bitmap.createBitmap(raw, 0, 0, img.width, img.height) else raw
        } catch (e: Exception) {
            main.post { fail("No se pudo capturar la pantalla") }
            return
        } finally {
            safe { img.close() }
        }
        val out = bmp ?: return
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Sr_" + stamp() + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sr")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                ?: throw IllegalStateException("MediaStore")
            contentResolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val done = ContentValues()
            done.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, done, null, null)
            main.post {
                notifyDone("Captura guardada", uri, "image/png", false)
                Toast.makeText(this, "Captura guardada", Toast.LENGTH_SHORT).show()
                endAll()
            }
        } catch (e: Exception) {
            main.post { fail("No se pudo guardar la captura") }
        }
    }

    // ------------------------------------------------------------------ cierre / avisos

    private fun fail(msg: String?) {
        Toast.makeText(this, msg ?: "Error", Toast.LENGTH_LONG).show()
        stopping = true
        closeRecording()
        endAll()
    }

    private fun endAll() {
        main.removeCallbacksAndMessages(null)
        if (dlRegistered) {
            safe { getSystemService(DisplayManager::class.java).unregisterDisplayListener(dl) }
            dlRegistered = false
        }
        safe { vd?.release() }
        vd = null
        safe { reader?.close() }
        reader = null
        synchronized(this) {
            safe { latest?.close() }
            latest = null
        }
        proj?.let {
            safe { it.unregisterCallback(projCb) }
            safe { it.stop() }
        }
        proj = null
        bg?.quitSafely()
        bg = null
        recording = false
        startedAt = 0
        mode = 0
        stopping = false
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (mode == 1 && vd != null) safe { closeRecording() }
        super.onDestroy()
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REC, "Grabación", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Archivos guardados", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notif(text: String, stopBtn: Boolean, chrono: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CH_REC)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Sr")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (chrono) b.setUsesChronometer(true).setWhen(System.currentTimeMillis())
        if (stopBtn) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Detener", stop).build())
        }
        return b.build()
    }

    private fun updateFg(text: String, stopBtn: Boolean, chrono: Boolean) {
        getSystemService(NotificationManager::class.java).notify(ID_FG, notif(text, stopBtn, chrono))
    }

    private fun notifyDone(title: String, uri: Uri, mime: String, video: Boolean) {
        val view = if (video) Intent(this, PlayerActivity::class.java).setData(uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("Toca para abrir")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
    }
}
