package io.github.kesemb2.calibrate.patterns

import android.media.MediaCodec
import android.os.Build
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the first frame of a pattern with the box's own hardware decoder (the one the player uses) into memory and
 * compares the luma of every probe region with the exact code value from the manifest. This proves the decoder
 * reproduces the lossless files bit for bit; what the HDMI output and the TV do afterwards is the human's part.
 */
object DecoderCheck {

    data class Result(
        val decoder: String?,
        val checked: Int,
        val mismatches: List<String>,
        val error: String? = null,
        /** 10 when the decoder gave us its real 10-bit output (P010); 8 when only an 8-bit conversion was available. */
        val bits: Int = 0,
    ) {
        val ok: Boolean get() = error == null && mismatches.isEmpty() && checked > 0
    }

    /** Null until tried; a decoder that refused P010 once is not asked again (each refusal costs the deadline). */
    @Volatile
    private var p010Works: Boolean? = null

    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 8_000L

    /**
     * Asks for P010 (the decoder's real 10-bit output, API 33+) first. Codec2 decoders asked for "flexible YUV"
     * hand out an 8-bit conversion (seen on the Homatics), which can only be compared to within one code.
     */
    fun check(file: File, probes: List<Probe>): Result {
        if (Build.VERSION.SDK_INT >= 33 && p010Works != false) {
            val p010 = run(file, probes, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            p010Works = p010.bits == 10
            if (p010.bits == 10) return p010
        }
        return run(file, probes, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
    }

    private fun run(file: File, probes: List<Probe>, colorFormat: Int): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var name: String? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: return Result(null, 0, emptyList(), "no decoder for this format")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            codec = MediaCodec.createByCodecName(name).apply {
                configure(format, null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val result = compare(codec, outIndex, probes, name)
                        codec.releaseOutputBuffer(outIndex, false)
                        return result
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Result(name, 0, emptyList(), "no frame decoded in time")
        } catch (e: Exception) {
            return Result(name, 0, emptyList(), e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun compare(codec: MediaCodec, index: Int, probes: List<Probe>, name: String): Result {
        val image = codec.getOutputImage(index) ?: return Result(name, 0, emptyList(), "decoder gives no CPU image")
        image.use {
            val plane = it.planes[0]
            val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val tenBit = pixelStride == 2
            // P010 keeps the 10 bits in the top of each 16-bit word; some decoders keep them at the bottom.
            fun luma(x: Int, y: Int): Int = if (tenBit) {
                val raw = buf.getShort(y * rowStride + x * 2).toInt() and 0xFFFF
                if (raw > 1023) raw ushr 6 else raw
            } else {
                buf.get(y * rowStride + x * pixelStride).toInt() and 0xFF
            }
            // In 8 bits the expected value is the 10-bit code / 4, and the decoder's own rounding may differ by one.
            fun matches(v: Int, y10: Int) = if (tenBit) v == y10 else kotlin.math.abs(v * 4 - y10) <= 4
            val mismatches = mutableListOf<String>()
            var checked = 0
            for (p in probes) {
                var bad: Int? = null
                val stepX = maxOf(1, p.w / 16)
                val stepY = maxOf(1, p.h / 16)
                var y = p.y
                while (y < p.y + p.h && y < it.height) {
                    var x = p.x
                    while (x < p.x + p.w && x < it.width) {
                        val v = luma(x, y)
                        checked++
                        if (!matches(v, p.y10) && bad == null) bad = if (tenBit) v else v * 4
                        x += stepX
                    }
                    y += stepY
                }
                if (bad != null) mismatches += "${p.name}: expected ${p.y10}, got $bad"
            }
            return Result(name, checked, mismatches, bits = if (tenBit) 10 else 8)
        }
    }
}
