package com.example.core.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.core.model.ClipKeyframe
import com.example.core.model.DuckingWindow
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sample-accurate 16-bit PCM [BaseAudioProcessor] applying direct/keyframed clip gain, linear
 * fade-in/out, and automatic ducking envelopes inside Media3 preview and `Transformer` export.
 */
@OptIn(UnstableApi::class)
class EnvelopeAudioProcessor(
  private val baseVolume: Float,
  private val fadeInMs: Long,
  private val fadeOutMs: Long,
  private val clipStartOnTimelineMs: Long,
  private val clipDurationMs: Long,
  private val duckingWindows: List<DuckingWindow> = emptyList(),
  private val volumeKeyframes: List<ClipKeyframe> = emptyList(),
  private val trackVolume: Float = 1.0f,
  private val clipSpeed: Float = 1.0f,
  private val pan: Float = 0.0f,
  private val fadeCurve: AudioFadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
  private val sourceSpanMs: Long = 0L,
  private val speedCurve: List<SpeedCurvePoint> = emptyList(),
) : BaseAudioProcessor() {

  private var processedFrames: Long = 0L

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return

    val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
    val safeSpeed = clipSpeed.coerceIn(0.25f, 4.0f)
    val bytesPerFrame = channelCount * 2
    val remainingBytes = inputBuffer.remaining()
    val outputBuffer = replaceOutputBuffer(remainingBytes)

    val frameCount = remainingBytes / bytesPerFrame
    for (frame in 0 until frameCount) {
      val currentFrameIndex = processedFrames + frame
      val sourceMs = (currentFrameIndex * 1000.0) / sampleRate.toDouble()
      val clipOffsetMs =
        if (speedCurve.size >= 2 && sourceSpanMs > 0L) {
          SpeedCurveEngine.sourceOffsetMsToTimelineMsDouble(
            sourceOffsetMs = sourceMs,
            sourceSpanMs = sourceSpanMs,
            baseSpeed = safeSpeed,
            speedCurve = speedCurve,
          ).roundToLong().coerceIn(0L, clipDurationMs)
        } else {
          (sourceMs / safeSpeed.toDouble()).roundToLong().coerceIn(0L, clipDurationMs)
        }
      val gain =
        AudioTrackMixer.computeClipEnvelopeGainAt(
          clipStartOnTimelineMs = clipStartOnTimelineMs,
          clipOffsetMs = clipOffsetMs,
          clipDurationMs = clipDurationMs,
          baseVolume = baseVolume,
          fadeInMs = fadeInMs,
          fadeOutMs = fadeOutMs,
          duckingWindows = duckingWindows,
          volumeKeyframes = volumeKeyframes,
          trackVolume = trackVolume,
          fadeCurve = fadeCurve,
        )

      val (leftGain, rightGain) =
        if (channelCount == 2) {
          AudioTrackMixer.computeStereoChannelGains(gain, pan)
        } else {
          gain to gain
        }

      for (ch in 0 until channelCount) {
        val sample = inputBuffer.short
        val chGain =
          when {
            channelCount == 2 && ch == 0 -> leftGain
            channelCount == 2 && ch == 1 -> rightGain
            else -> gain
          }
        val scaled = (sample * chGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        outputBuffer.putShort(scaled.toShort())
      }
    }

    // Copy any trailing partial bytes safely
    while (inputBuffer.hasRemaining()) {
      outputBuffer.put(inputBuffer.get())
    }

    processedFrames += frameCount
    outputBuffer.flip()
  }

  override fun onFlush() {
    processedFrames = 0L
  }

  override fun onReset() {
    processedFrames = 0L
  }
}
