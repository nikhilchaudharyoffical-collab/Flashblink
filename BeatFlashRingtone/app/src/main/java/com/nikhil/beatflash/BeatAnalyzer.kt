package com.nikhil.beatflash

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

object BeatAnalyzer {

    private const val PREFS_NAME = "beat_flash_prefs"
    private const val KEY_BEATS = "cached_beats_ms"
    private const val KEY_URI = "cached_uri"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_PULSE_MS = "pulse_ms"
    private const val KEY_ENABLED = "service_enabled"

    fun isFeatureEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSensitivity(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SENSITIVITY, 1.45f)

    fun setSensitivity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SENSITIVITY, value).apply()
    }

    fun getPulseDurationMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PULSE_MS, 75L)

    fun setPulseDurationMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_PULSE_MS, ms).apply()
    }

    fun saveBeats(context: Context, uri: Uri, beatsMs: List<Long>) {
        val serialized = beatsMs.joinToString(",")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_BEATS, serialized)
            .apply()
    }

    fun getCachedBeats(context: Context): List<Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BEATS, "") ?: ""
        if (raw.isBlank()) return defaultFallbackBeats()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.ifEmpty { defaultFallbackBeats() }
    }

    fun defaultFallbackBeats(): List<Long> {
        // Fallback rhythmic pattern every 450ms up to 30 seconds
        return (0L..30000L step 450L).toList()
    }

    /**
     * Decodes up to 35 seconds of any ringtone URI into raw PCM and extracts beat onset timestamps (ms).
     */
    fun analyzeRingtoneBeats(context: Context, uri: Uri, sensitivity: Float = 1.45f): List<Long> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) return defaultFallbackBeats()

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val hopMs = 25
            val samplesPerHop = (sampleRate * hopMs) / 1000
            val rmsList = ArrayList<Float>()
            var currentSumSq = 0.0
            var currentCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputDone = false
            var isOutputDone = false
            val maxSamplesToRead = sampleRate * 35 // Analyze max 35s of ringtone
            var totalSamplesRead = 0

            while (!isOutputDone && totalSamplesRead < maxSamplesToRead) {
                if (!isInputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                        while (shortBuffer.hasRemaining()) {
                            var monoSample = 0f
                            for (c in 0 until channelCount) {
                                if (shortBuffer.hasRemaining()) {
                                    monoSample += shortBuffer.get() / 32768f
                                }
                            }
                            monoSample /= channelCount.coerceAtLeast(1)
                            currentSumSq += (monoSample * monoSample)
                            currentCount++
                            totalSamplesRead++

                            if (currentCount >= samplesPerHop) {
                                rmsList.add(sqrt(currentSumSq / currentCount).toFloat())
                                currentSumSq = 0.0
                                currentCount = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputDone = true
                    }
                }
            }

            return extractPeaksFromRms(rmsList, hopMs, sensitivity)
        } catch (e: Exception) {
            e.printStackTrace()
            return defaultFallbackBeats()
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) { }
            try {
                extractor.release()
            } catch (_: Exception) { }
        }
    }

    private fun extractPeaksFromRms(
        rms: List<Float>,
        hopMs: Int,
        thresholdMult: Float
    ): List<Long> {
        if (rms.size < 10) return defaultFallbackBeats()

        val window = 14
        val minGapFrames = (130 / hopMs).coerceAtLeast(1) // Max ~7.5 blinks/sec
        val beatsMs = ArrayList<Long>()
        var lastBeatIdx = -minGapFrames

        for (i in 1 until rms.size - 1) {
            val start = (i - window / 2).coerceAtLeast(0)
            val end = (i + window / 2).coerceAtMost(rms.size - 1)
            var sum = 0f
            for (j in start..end) sum += rms[j]
            val localAvg = (sum / (end - start + 1)) + 0.005f

            val ratio = rms[i] / localAvg
            if (rms[i] > 0.04f &&
                ratio >= thresholdMult &&
                rms[i] >= rms[i - 1] &&
                rms[i] >= rms[i + 1] &&
                (i - lastBeatIdx) >= minGapFrames
            ) {
                beatsMs.add((i * hopMs).toLong())
                lastBeatIdx = i
            }
        }

        return beatsMs.ifEmpty { defaultFallbackBeats() }
    }
}
