package com.example.core.ai.local

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.core.ai.api.BeatMarkerCandidate
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.LocalAudioAssistEngine
import com.example.core.ai.api.LocalAudioAssistResult
import com.example.core.ai.api.ResourceGuard
import com.example.core.media.audio.SilenceDetectorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight, 100% offline DSP engine that analyzes PCM audio frames under [ResourceGuard]
 * constraints to propose both silence/pause cuts and rhythmic beat marker candidates.
 */
class LocalRhythmAndSilenceAssistant(
  private val resourceGuard: ResourceGuard,
) : LocalAudioAssistEngine {

  override suspend fun analyzeMediaFile(
    mediaFile: File,
    settings: LocalAiSettings,
    silenceThresholdDb: Float,
    minSilenceMs: Long,
    beatSensitivity: Float,
  ): Result<LocalAudioAssistResult> =
    withContext(Dispatchers.Default) {
      runCatching {
        val guardState = resourceGuard.refreshNow(settings)
        if (!guardState.canRunInference) {
          error(guardState.blockReason ?: "Análisis local bloqueado por recursos")
        }
        if (!mediaFile.exists()) error("Archivo multimedia no encontrado")

        val windows = extractRmsEnergyWindows(mediaFile)
        val dbWindows =
          windows.map { (tsMs, rms) ->
            tsMs to (20.0 * log10(rms.toDouble().coerceAtLeast(1e-6))).toFloat()
          }

        val silences =
          SilenceDetectorEngine.detectSilencesFromDbWindows(
            windowDbValues = dbWindows,
            windowDurationMs = WINDOW_MS,
            thresholdDb = silenceThresholdDb,
            minSilenceDurationMs = minSilenceMs,
          )
        val beats =
          detectBeatOnsetsFromRmsWindows(
            rmsWindows = windows,
            sensitivityMultiplier = beatSensitivity,
          )

        LocalAudioAssistResult(silences = silences, beats = beats)
      }
    }

  private fun extractRmsEnergyWindows(file: File): List<Pair<Long, Float>> {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      extractor.setDataSource(file.absolutePath)
      var audioTrack = -1
      for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          audioTrack = i
          break
        }
      }
      if (audioTrack < 0) error("El archivo no tiene pista de audio")

      extractor.selectTrack(audioTrack)
      val format = extractor.getTrackFormat(audioTrack)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Formato desconocido")

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(format, null, null, 0)
      codec.start()

      val info = MediaCodec.BufferInfo()
      val results = mutableListOf<Pair<Long, Float>>()

      var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
      var samplesPerWindow = ((sampleRate * WINDOW_MS) / 1000L).toInt() * channels
      var sumSquares = 0.0
      var count = 0
      var windowIdx = 0L
      var inputDone = false
      var outputDone = false

      while (!outputDone) {
        if (!inputDone) {
          val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
          if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          val outFmt = codec.outputFormat
          if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
          }
          if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
          }
          samplesPerWindow = ((sampleRate * WINDOW_MS) / 1000L).toInt() * channels
        } else if (outIdx >= 0) {
          val outBuf = codec.getOutputBuffer(outIdx)
          if (outBuf != null && info.size > 0) {
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (shorts.hasRemaining()) {
              val norm = shorts.get().toDouble() / 32768.0
              sumSquares += norm * norm
              count++
              if (count >= samplesPerWindow) {
                val rms = sqrt(sumSquares / count).toFloat()
                results.add((windowIdx * WINDOW_MS) to rms)
                windowIdx++
                sumSquares = 0.0
                count = 0
              }
            }
          }
          codec.releaseOutputBuffer(outIdx, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true
          }
        }
      }

      return results
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  companion object {
    const val WINDOW_MS = 20L
    private const val TIMEOUT_US = 10_000L

    /**
     * Pure beat-onset detector using local moving-average energy flux and minimum inter-beat
     * cooldown so markers stay musically spaced.
     */
    fun detectBeatOnsetsFromRmsWindows(
      rmsWindows: List<Pair<Long, Float>>,
      sensitivityMultiplier: Float = 1.65f,
      minBeatSpacingMs: Long = 350L,
      minRmsFloor: Float = 0.04f,
    ): List<BeatMarkerCandidate> {
      if (rmsWindows.size < 6) return emptyList()

      val beats = mutableListOf<BeatMarkerCandidate>()
      var lastBeatMs = -minBeatSpacingMs
      val historySize = 10

      for (i in historySize until rmsWindows.size) {
        val (tsMs, currentRms) = rmsWindows[i]
        if (currentRms < minRmsFloor) continue
        if (tsMs - lastBeatMs < minBeatSpacingMs) continue

        var avg = 0f
        for (j in (i - historySize) until i) {
          avg += rmsWindows[j].second
        }
        avg /= historySize.toFloat()

        val prevRms = rmsWindows[i - 1].second
        if (currentRms > avg * sensitivityMultiplier && currentRms > prevRms * 1.2f) {
          val score = (currentRms / avg.coerceAtLeast(1e-4f)).coerceAtMost(5f)
          beats.add(BeatMarkerCandidate(positionMs = tsMs, strength = score))
          lastBeatMs = tsMs
        }
      }

      return beats
    }
  }
}
