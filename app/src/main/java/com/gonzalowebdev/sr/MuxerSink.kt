package com.gonzalowebdev.sr

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Muxer compartido por las hebras de video y audio. Arranca cuando están todas las pistas
 * y guarda en memoria las muestras que llegan antes. Normaliza los timestamps al origen.
 */
class MuxerSink(
    private val muxer: MediaMuxer,
    private var expected: Int,
    private val originUs: Long
) {
    private class Pending(val track: Int, val data: ByteArray, val flags: Int, val pts: Long)

    private var added = 0
    private val pending = ArrayList<Pending>()
    private val bi = MediaCodec.BufferInfo()

    @Volatile
    var started = false
        private set

    @Synchronized
    fun addTrack(f: MediaFormat): Int {
        val i = muxer.addTrack(f)
        added++
        tryStart()
        return i
    }

    /** Una pista (p. ej. audio) no pudo iniciarse: se graba sin ella. */
    @Synchronized
    fun trackFailed() {
        expected--
        tryStart()
    }

    private fun tryStart() {
        if (started || expected <= 0 || added < expected) return
        muxer.start()
        started = true
        for (p in pending) {
            bi.set(0, p.data.size, p.pts, p.flags)
            muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), bi)
        }
        pending.clear()
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        val pts = maxOf(0L, info.presentationTimeUs - originUs)
        val flags = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (started) {
            bi.set(info.offset, info.size, pts, flags)
            muxer.writeSampleData(track, buf, bi)
        } else {
            buf.limit(info.offset + info.size)
            buf.position(info.offset)
            val b = ByteArray(info.size)
            buf.get(b)
            pending.add(Pending(track, b, flags, pts))
        }
    }

    /** @return true si el archivo quedó válido */
    @Synchronized
    fun finish(): Boolean {
        var ok = started
        if (started) {
            try {
                muxer.stop()
            } catch (e: Exception) {
                ok = false
            }
        }
        safe { muxer.release() }
        return ok
    }
}
