package com.gonzalowebdev.sr

import android.graphics.Point
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.roundToInt

data class Plan(
    val w: Int,
    val h: Int,
    val bitrate: Int,
    val fps: Int,
    val hevc: Boolean,
    val info: MediaCodecInfo?
)

object Encoders {
    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    fun mime(hevc: Boolean) = if (hevc) HEVC else AVC

    private fun encoders(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    /** H.265 solo se ofrece si hay encoder por hardware (el de software es lento). */
    fun hasHevc(): Boolean = try {
        encoders(HEVC).any { it.isHardwareAccelerated }
    } catch (e: Exception) {
        false
    }

    private class Cand(val info: MediaCodecInfo, val w: Int, val h: Int, val rate: Boolean)

    /** Elige encoder, tamaño alineado y bitrate VBR según códec, fps y nivel de calidad. */
    fun plan(phys: Point, shortSide: Int, fps: Int, hevc: Boolean, quality: Int): Plan {
        val mime = mime(hevc)
        val scale = minOf(1.0, shortSide.toDouble() / minOf(phys.x, phys.y))
        val w0 = (phys.x * scale).roundToInt()
        val h0 = (phys.y * scale).roundToInt()

        val cands = try {
            encoders(mime).mapNotNull { ci ->
                try {
                    val vc = ci.getCapabilitiesForType(mime).videoCapabilities
                    val wa = maxOf(vc.widthAlignment, 2)
                    val ha = maxOf(vc.heightAlignment, 2)
                    var w = w0 / wa * wa
                    var h = h0 / ha * ha
                    if (!vc.isSizeSupported(w, h)) {
                        w = w0 / 16 * 16
                        h = h0 / 16 * 16
                    }
                    if (!vc.isSizeSupported(w, h)) null
                    else Cand(ci, w, h, vc.areSizeAndRateSupported(w, h, fps.toDouble()))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        val best = cands.firstOrNull { it.info.isHardwareAccelerated && it.rate }
            ?: cands.firstOrNull { it.info.isHardwareAccelerated }
            ?: cands.firstOrNull()

        val w = best?.w ?: (w0 / 2 * 2)
        val h = best?.h ?: (h0 / 2 * 2)
        // bits por píxel: H.265 necesita ~40% menos que H.264 para la misma calidad
        val bpp = (if (hevc) 0.040 else 0.065) * doubleArrayOf(0.7, 1.0, 1.5)[quality.coerceIn(0, 2)]
        val br = (w.toDouble() * h * 30.0 * bpp * (if (fps >= 60) 1.6 else 1.0))
            .coerceIn(800_000.0, 24_000_000.0).toInt()
        return Plan(w, h, br, fps, hevc, best?.info)
    }

    fun createVideo(p: Plan): MediaCodec {
        val info = p.info ?: throw IllegalStateException("No hay encoder de video")
        val mime = mime(p.hevc)
        val caps = info.getCapabilitiesForType(mime)
        val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val fmt = MediaFormat.createVideoFormat(mime, p.w, p.h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
        val ec = caps.encoderCapabilities
        if (ec.isBitrateModeSupported(vbr)) fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, vbr)
        else if (ec.isBitrateModeSupported(cbr)) fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, cbr)
        val prof = if (p.hevc) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        if (caps.profileLevels.any { it.profile == prof }) fmt.setInteger(MediaFormat.KEY_PROFILE, prof)
        // limita los frames al fps pedido y repite el último si la pantalla está quieta
        fmt.setFloat("max-fps-to-encoder", p.fps.toFloat())
        fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)

        val drops = listOf(
            emptyList<String>(),
            listOf(MediaFormat.KEY_PROFILE),
            listOf(MediaFormat.KEY_PROFILE, "max-fps-to-encoder", MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER),
            listOf(MediaFormat.KEY_PROFILE, "max-fps-to-encoder", MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, MediaFormat.KEY_BITRATE_MODE)
        )
        var last: Exception? = null
        for (d in drops) {
            d.forEach { fmt.removeKey(it) }
            val c = MediaCodec.createByCodecName(info.name)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return c
            } catch (e: Exception) {
                last = e
                safe { c.release() }
            }
        }
        throw last ?: IllegalStateException("No se pudo configurar el encoder")
    }
}
