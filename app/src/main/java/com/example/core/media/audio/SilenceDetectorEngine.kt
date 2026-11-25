package com.example.core.media.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.core.model.SilenceSegment
import com.example.core.model.TimelineState
import com.example.core.timeline.TimelineEngine
import com.example.core.timeline.TimelineOperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Local PCM silence & pause analyzer powered by Android's native [MediaExtractor] + [MediaCodec].
 * Also provides pure timeline helpers to mark or ripple-remove detected silent pauses.
 */
class SilenceDetectorEngine {

  /**
   * Decodes the first audio track of [mediaFile] into 16-bit PCM frames and detects continuous
   * silent intervals below [thresholdDb] lasting at least [minSilenceDurationMs].
   */
  suspend fun analyzeAudioFile(
    mediaFile: File,
    thresholdDb: Float = DEFAULT_SILENCE_THRESHOLD_DB,
    minSilenceDurationMs: Long = DEFAULT_MIN_SILENCE_MS,
  ): Result<List<SilenceSegment>> =
    withContext(Dispatchers.Default) {
      runCatching {
        if (!mediaFile.exists()) error("Archivo de audio/video no encontrado")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
          extractor.setDataSource(mediaFile.absolutePath)
          val audioTrackIndex = findAudioTrackIndex(extractor)
            ?: error("El archivo no contiene pista de audio")

          extractor.selectTrack(audioTrackIndex)
          val format = extractor.getTrackFormat(audioTrackIndex)
          val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Formato de audio desconocido")

          codec = MediaCodec.createDecoderByType(mime)
          codec.configure(format, null, null, 0)
          codec.start()

          val dbWindows = decodePcmWindowsDb(extractor, codec, format)
          detectSilencesFromDbWindows(
            windowDbValues = dbWindows,
            windowDurationMs = ANALYSIS_WINDOW_MS,
            thresholdDb = thresholdDb,
            minSilenceDurationMs = minSilenceDurationMs,
          )
        } finally {
          runCatching { codec?.stop() }
          runCatching { codec?.release() }
          runCatching { extractor.release() }
        }
      }
    }

  private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
    for (i in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
      if (mime.startsWith("audio/")) return i
    }
    return null
  }

  private fun decodePcmWindowsDb(
    extractor: MediaExtractor,
    codec: MediaCodec,
    initialFormat: MediaFormat,
  ): List<Pair<Long, Float>> {
    val bufferInfo = MediaCodec.BufferInfo()
    val windows = mutableListOf<Pair<Long, Float>>()

    var sampleRate = initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
    var channelCount = initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
    var samplesPerWindow = ((sampleRate * ANALYSIS_WINDOW_MS) / 1000L).toInt() * channelCount

    var sumSquares = 0.0
    var sampleCounter = 0
    var currentWindowIndex = 0L

    var inputDone = false
    var outputDone = false

    while (!outputDone) {
      if (!inputDone) {
        val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inIndex >= 0) {
          val inBuffer = codec.getInputBuffer(inIndex)!!
          val sampleSize = extractor.readSampleData(inBuffer, 0)
          if (sampleSize < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }

      val outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
      when {
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val outFormat = codec.outputFormat
          if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
          }
          if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
          }
          samplesPerWindow = ((sampleRate * ANALYSIS_WINDOW_MS) / 1000L).toInt() * channelCount
        }
        outIndex >= 0 -> {
          val outBuffer = codec.getOutputBuffer(outIndex)
          if (outBuffer != null && bufferInfo.size > 0) {
            outBuffer.position(bufferInfo.offset)
            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
            val shortBuf = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (shortBuf.hasRemaining()) {
              val normalized = shortBuf.get().toDouble() / 32768.0
              sumSquares += normalized * normalized
              sampleCounter++
              if (sampleCounter >= samplesPerWindow) {
                val rms = sqrt(sumSquares / sampleCounter).coerceAtLeast(1e-6)
                val db = (20.0 * log10(rms)).toFloat()
                windows.add((currentWindowIndex * ANALYSIS_WINDOW_MS) to db)
                currentWindowIndex++
                sumSquares = 0.0
                sampleCounter = 0
              }
            }
          }
          codec.releaseOutputBuffer(outIndex, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true
          }
        }
      }
    }

    if (sampleCounter > 0) {
      val rms = sqrt(sumSquares / sampleCounter).coerceAtLeast(1e-6)
      val db = (20.0 * log10(rms)).toFloat()
      windows.add((currentWindowIndex * ANALYSIS_WINDOW_MS) to db)
    }

    return windows
  }

  companion object {
    const val ANALYSIS_WINDOW_MS = 20L
    const val DEFAULT_SILENCE_THRESHOLD_DB = -36f
    const val DEFAULT_MIN_SILENCE_MS = 450L
    private const val CODEC_TIMEOUT_US = 10_000L

    /**
     * Pure deterministic silence clustering over `(timestampMs, rmsDb)` analysis windows.
     */
    fun detectSilencesFromDbWindows(
      windowDbValues: List<Pair<Long, Float>>,
      windowDurationMs: Long = ANALYSIS_WINDOW_MS,
      thresholdDb: Float = DEFAULT_SILENCE_THRESHOLD_DB,
      minSilenceDurationMs: Long = DEFAULT_MIN_SILENCE_MS,
    ): List<SilenceSegment> {
      if (windowDbValues.isEmpty()) return emptyList()

      val segments = mutableListOf<SilenceSegment>()
      var activeStartMs: Long? = null
      var dbSum = 0f
      var dbCount = 0

      for ((timestampMs, db) in windowDbValues) {
        if (db <= thresholdDb) {
          if (activeStartMs == null) {
            activeStartMs = timestampMs
            dbSum = 0f
            dbCount = 0
          }
          dbSum += db
          dbCount++
        } else if (activeStartMs != null) {
          val endMs = timestampMs
          if (endMs - activeStartMs >= minSilenceDurationMs && dbCount > 0) {
            segments.add(
              SilenceSegment(
                startMs = activeStartMs,
                endMs = endMs,
                averageDb = dbSum / dbCount,
              ),
            )
          }
          activeStartMs = null
        }
      }

      if (activeStartMs != null && dbCount > 0) {
        val lastEndMs = windowDbValues.last().first + windowDurationMs
        if (lastEndMs - activeStartMs >= minSilenceDurationMs) {
          segments.add(
            SilenceSegment(
              startMs = activeStartMs,
              endMs = lastEndMs,
              averageDb = dbSum / dbCount,
            ),
          )
        }
      }

      return segments
    }

    /**
     * Automatically splits around [silenceSegments] within [clipId] and removes the silent gaps,
     * optionally closing gaps with ripple mode.
     *
     * Note: [silenceSegments] are expressed in source-media milliseconds (`0..assetDuration`).
     */
    fun removeSilenceSegmentsFromClip(
      engine: TimelineEngine,
      state: TimelineState,
      clipId: String,
      silenceSegments: List<SilenceSegment>,
      ripple: Boolean = true,
    ): TimelineState {
      var workingState = state
      // Process from right to left so earlier timeline timestamps remain stable while cutting
      val sortedSilences = silenceSegments.sortedByDescending { it.startMs }

      for (silence in sortedSilences) {
        val currentClip = workingState.clips.find { it.clipId == clipId }
          ?: workingState.clips.find {
            // If clip was previously split, locate the segment overlapping this source interval
            it.sourceInMs <= silence.startMs && it.sourceOutMs >= silence.endMs
          } ?: continue

        if (silence.endMs <= currentClip.sourceInMs || silence.startMs >= currentClip.sourceOutMs) {
          continue
        }

        val clampedStartSrc = silence.startMs.coerceIn(currentClip.sourceInMs, currentClip.sourceOutMs)
        val clampedEndSrc = silence.endMs.coerceIn(currentClip.sourceInMs, currentClip.sourceOutMs)
        if (clampedEndSrc <= clampedStartSrc) continue

        val startTimelineMs =
          currentClip.timelineStartMs +
            ((clampedStartSrc - currentClip.sourceInMs) / currentClip.speed).toDouble().roundToLong()
        val endTimelineMs =
          currentClip.timelineStartMs +
            ((clampedEndSrc - currentClip.sourceInMs) / currentClip.speed).toDouble().roundToLong()

        var targetIdToDelete: String? = null
        if (startTimelineMs > currentClip.timelineStartMs && endTimelineMs < currentClip.timelineEndMs) {
          // Cut at end first, then start, and delete the middle silent piece
          val splitEnd =
            engine.splitAtPlayhead(
              engine.seekPlayhead(workingState, endTimelineMs),
              targetClipId = currentClip.clipId,
            )
          if (splitEnd is TimelineOperationResult.Success) {
            workingState = splitEnd.newState
            val splitStart =
              engine.splitAtPlayhead(
                engine.seekPlayhead(workingState, startTimelineMs),
                targetClipId = currentClip.clipId,
              )
            if (splitStart is TimelineOperationResult.Success) {
              workingState = splitStart.newState
              targetIdToDelete =
                splitStart.affectedClipIds.firstOrNull { id ->
                  workingState.clips.find { it.clipId == id }?.timelineStartMs == startTimelineMs
                }
            }
          }
        } else if (startTimelineMs <= currentClip.timelineStartMs && endTimelineMs < currentClip.timelineEndMs) {
          val trimmed =
            engine.trimClip(
              state = workingState,
              clipId = currentClip.clipId,
              newStartMs = endTimelineMs,
              ripple = ripple,
            )
          if (trimmed is TimelineOperationResult.Success) workingState = trimmed.newState
        } else if (startTimelineMs > currentClip.timelineStartMs && endTimelineMs >= currentClip.timelineEndMs) {
          val trimmed =
            engine.trimClip(
              state = workingState,
              clipId = currentClip.clipId,
              newEndMs = startTimelineMs,
              ripple = ripple,
            )
          if (trimmed is TimelineOperationResult.Success) workingState = trimmed.newState
        }

        if (targetIdToDelete != null) {
          val deleted = engine.deleteClip(workingState, targetIdToDelete, ripple = ripple)
          if (deleted is TimelineOperationResult.Success) {
            workingState = deleted.newState
          }
        }
      }

      return workingState.copy(playheadMs = state.playheadMs.coerceAtMost(workingState.totalDurationMs))
    }
  }
}
