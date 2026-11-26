package com.nikhil.beatflash

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

data class BeatEvent(val timeMs: Long, val intensity: Float)

object BeatAnalyzer {

    private const val PREFS_NAME = "beat_flash_prefs"
    private const val KEY_BEATS = "cached_beats_ms"
    private const val KEY_URI = "cached_uri"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_PULSE_MS = "pulse_ms"
    private const val KEY_ENABLED = "service_enabled"

    fun isFeatureEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSensitivity(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SENSITIVITY, 1.45f)

    fun setSensitivity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SENSITIVITY, value).apply()
    }

    fun getPulseDurationMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_PULSE_MS, 75L)

    fun setPulseDurationMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_PULSE_MS, ms).apply()
    }

    fun saveBeats(context: Context, uri: Uri, beats: List<BeatEvent>) {
        val serialized = beats.sortedBy { it.timeMs }.distinctBy { it.timeMs }
            .joinToString(",") { "${it.timeMs}:${it.intensity.coerceIn(0f, 1f)}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_BEATS, serialized)
            .apply()
    }

    /** Reads the cached beat/intensity pairs, retaining compatibility with older timestamp-only caches. */
    fun getCachedBeats(context: Context, uri: Uri? = null): List<BeatEvent> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (uri != null && prefs.getString(KEY_URI, null) != uri.toString()) return emptyList()
        return prefs.getString(KEY_BEATS, "")?.split(",")?.mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            if (time < 0L) return@mapNotNull null
            val intensity = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.65f
            BeatEvent(time, intensity)
        }?.sortedBy { it.timeMs }.orEmpty()
    }

    /** Decodes ringtone audio and returns cached onset timestamps with normalized relative strengths. */
    fun analyzeRingtoneBeats(context: Context, uri: Uri, sensitivity: Float = 1.45f): List<BeatEvent> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return emptyList()

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val hopMs = 10
            val samplesPerHop = (sampleRate * hopMs / 1000).coerceAtLeast(1)
            val rms = ArrayList<Float>()
            var sumSq = 0.0
            var sampleCount = 0
            var decodedSamples = 0
            val maxSamples = sampleRate * 35
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && decodedSamples < maxSamples) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: return emptyList()
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (pcm.hasRemaining() && decodedSamples < maxSamples) {
                            var mono = 0f
                            var channelsRead = 0
                            repeat(channels) {
                                if (pcm.hasRemaining()) {
                                    mono += pcm.get() / 32768f
                                    channelsRead++
                                }
                            }
                            if (channelsRead > 0) {
                                mono /= channelsRead
                                sumSq += mono * mono
                                sampleCount++
                                decodedSamples++
                                if (sampleCount >= samplesPerHop) {
                                    rms.add(sqrt(sumSq / sampleCount).toFloat())
                                    sumSq = 0.0
                                    sampleCount = 0
                                }
                            }
                        }
                    }
                    val ended = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (ended) outputDone = true
                }
            }
            return extractPeaksFromRms(rms, hopMs, sensitivity)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun extractPeaksFromRms(rms: List<Float>, hopMs: Int, threshold: Float): List<BeatEvent> {
        if (rms.size < 10) return emptyList()
        val window = 20
        val minGapFrames = (130 / hopMs).coerceAtLeast(1)
        val maxEnergy = rms.maxOrNull()?.coerceAtLeast(0.001f) ?: return emptyList()
        val beats = ArrayList<BeatEvent>()
        var lastBeat = -minGapFrames

        for (i in 1 until rms.lastIndex) {
            val start = (i - window / 2).coerceAtLeast(0)
            val end = (i + window / 2).coerceAtMost(rms.lastIndex)
            var sum = 0f
            for (j in start..end) sum += rms[j]
            val localAverage = sum / (end - start + 1) + 0.005f
            val ratio = rms[i] / localAverage
            val isPeak = rms[i] >= rms[i - 1] && rms[i] > rms[i + 1]
            if (rms[i] > 0.04f && ratio >= threshold && isPeak && i - lastBeat >= minGapFrames) {
                val relativeEnergy = (rms[i] / maxEnergy).coerceIn(0f, 1f)
                val onsetProminence = ((ratio - threshold) / threshold.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
                val intensity = (relativeEnergy * 0.7f + onsetProminence * 0.3f).coerceIn(0f, 1f)
                beats.add(BeatEvent(i.toLong() * hopMs, intensity))
                lastBeat = i
            }
        }
        return beats
    }
}
