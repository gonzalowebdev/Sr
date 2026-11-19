package com.gonzalowebdev.sr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var st: Settings
    private var after: (() -> Unit)? = null
    private val resList = listOf(480, 720, 1080)

    companion object {
        private const val REQ_RECORD = 1
        private const val REQ_SHOT = 2
        private const val REQ_PERM = 10
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        st = Settings.of(this)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<Switch>(R.id.swAudio).setOnCheckedChangeListener { _, on ->
            if (!RecorderService.recording && st.audio != on) {
                st.audio = on
                render()
            }
        }
        findViewById<View>(R.id.btnRec).setOnClickListener { onRecClick() }
        findViewById<View>(R.id.btnShot).setOnClickListener { onShotClick() }
        findViewById<View>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.onChange = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        RecorderService.onChange = null
    }

    private fun segment(
        box: LinearLayout,
        labels: List<String>,
        sel: Int,
        enabled: List<Boolean>? = null,
        pick: (Int) -> Unit
    ) {
        box.removeAllViews()
        val locked = RecorderService.recording
        labels.forEachIndexed { i, label ->
            val on = i == sel
            val ok = (enabled?.get(i) ?: true) && !locked
            val tv = TextView(this)
            tv.text = label
            tv.gravity = Gravity.CENTER
            tv.textSize = 14f
            tv.setTypeface(tv.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(getColor(if (on) R.color.white else if (ok) R.color.text2 else R.color.disabled))
            if (on) {
                tv.setBackgroundResource(R.drawable.seg_on)
                tv.elevation = dp(3).toFloat()
                tv.setShadowLayer(2f, 0f, 1f, 0x66000000)
            }
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (ok) tv.setOnClickListener { pick(i) }
            box.addView(tv)
        }
    }

    private fun render() {
        val hevcOk = Encoders.hasHevc()
        val useHevc = st.hevc && hevcOk
        segment(findViewById(R.id.segCodec), listOf("H.264", "H.265"), if (useHevc) 1 else 0, listOf(true, hevcOk)) {
            st.hevc = it == 1
            render()
        }
        segment(findViewById(R.id.segRes), listOf("480p", "720p", "1080p"), resList.indexOf(st.shortSide)) {
            st.shortSide = resList[it]
            render()
        }
        segment(findViewById(R.id.segFps), listOf("30 fps", "60 fps"), if (st.fps == 60) 1 else 0) {
            st.fps = if (it == 1) 60 else 30
            render()
        }
        segment(findViewById(R.id.segQ), listOf("Ahorro", "Balance", "Máxima"), st.quality) {
            st.quality = it
            render()
        }
        val sw = findViewById<Switch>(R.id.swAudio)
        if (sw.isChecked != st.audio) sw.isChecked = st.audio

        val plan = Encoders.plan(realSize(this), st.shortSide, st.fps, useHevc, st.quality)
        val mbMin = (plan.bitrate + if (st.audio) 96_000 else 0) / 8.0 * 60 / 1_000_000
        findViewById<TextView>(R.id.tvInfo).text =
            "${plan.w}×${plan.h} · %.1f Mb/s · hasta ≈ ${mbMin.roundToInt()} MB/min".format(plan.bitrate / 1e6)
        renderState()
    }

    private fun renderState() {
        val rec = RecorderService.recording
        findViewById<View>(R.id.btnRec).setBackgroundResource(if (rec) R.drawable.btn_rec_active else R.drawable.btn_rec_idle)
        findViewById<TextView>(R.id.tvStatus).text = if (rec) "Grabando · toca para detener" else "Toca para grabar"
        val ch = findViewById<Chronometer>(R.id.chrono)
        if (rec) {
            ch.base = if (RecorderService.startedAt > 0) RecorderService.startedAt else SystemClock.elapsedRealtime()
            ch.visibility = View.VISIBLE
            ch.start()
        } else {
            ch.stop()
            ch.visibility = View.INVISIBLE
        }
        findViewById<View>(R.id.btnShot).alpha = if (rec) 0.4f else 1f
        findViewById<View>(R.id.settings).alpha = if (rec) 0.5f else 1f
        findViewById<Switch>(R.id.swAudio).isEnabled = !rec
    }

    private fun onRecClick() {
        if (RecorderService.recording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            withPermissions(st.audio) { askProjection(REQ_RECORD) }
        }
    }

    private fun onShotClick() {
        if (RecorderService.recording) {
            Toast.makeText(this, "Detén la grabación para tomar una captura", Toast.LENGTH_SHORT).show()
        } else {
            withPermissions(false) { askProjection(REQ_SHOT) }
        }
    }

    private fun withPermissions(audio: Boolean, next: () -> Unit) {
        val need = ArrayList<String>()
        if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            next()
        } else {
            after = next
            requestPermissions(need.toTypedArray(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (st.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            st.audio = false
            Toast.makeText(this, "Sin permiso de audio: se grabará sin audio interno", Toast.LENGTH_LONG).show()
            render()
        }
        val next = after
        after = null
        next?.invoke()
    }

    @Suppress("DEPRECATION")
    private fun askProjection(req: Int) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(i, req)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if ((req == REQ_RECORD || req == REQ_SHOT) && res == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java)
                .setAction(if (req == REQ_RECORD) RecorderService.ACTION_RECORD else RecorderService.ACTION_SHOT)
                .putExtra(RecorderService.EX_CODE, res)
                .putExtra(RecorderService.EX_DATA, data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
