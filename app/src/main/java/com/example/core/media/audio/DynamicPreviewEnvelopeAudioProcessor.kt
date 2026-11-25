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

data class PreviewAudioSegmentContext(
  val isTrackAudible: Boolean,
  val clipStartOnTimelineMs: Long,
  val clipDurationMs: Long,
  val clipSpeed: Float,
  val baseVolume: Float,
  val trackVolume: Float,
  val fadeInMs: Long,
  val fadeOutMs: Long,
  val volumeKeyframes: List<ClipKeyframe> = emptyList(),
  val duckingWindows: List<DuckingWindow> = emptyList(),
  val anchorClipOffsetMs: Long = 0L,
  val pan: Float = 0.0f,
  val fadeCurve: AudioFadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
  val sourceSpanMs: Long = 0L,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
  val anchorSourceOffsetMs: Long = 0L,
)

/**
 * Seek-aware live preview 16-bit PCM [BaseAudioProcessor] sharing [AudioTrackMixer.computeClipEnvelopeGainAt]
 * with export [EnvelopeAudioProcessor] so keyframed volume, pan, smooth fades, and ducking match
 * between preview and render.
 */
@OptIn(UnstableApi::class)
class DynamicPreviewEnvelopeAudioProcessor : BaseAudioProcessor() {

  @Volatile
  private var segmentContext: PreviewAudioSegmentContext? = null

  @Volatile
  private var processedFramesSinceFlush: Long = 0L

  fun updateContext(context: PreviewAudioSegmentContext?) {
    segmentContext = context
    processedFramesSinceFlush = 0L
  }

  fun updateAnchorOffsetMs(clipOffsetMs: Long) {
    segmentContext =
      segmentContext?.let { ctx ->
        val safeClipOffset = clipOffsetMs.coerceAtLeast(0L)
        val anchorSrc =
          if (ctx.speedCurve.size >= 2 && ctx.sourceSpanMs > 0L) {
            SpeedCurveEngine.timelineOffsetMsToSourceMs(
              timelineOffsetMs = safeClipOffset,
              sourceSpanMs = ctx.sourceSpanMs,
              baseSpeed = ctx.clipSpeed,
              speedCurve = ctx.speedCurve,
            )
          } else {
            (safeClipOffset * ctx.clipSpeed.coerceIn(0.25f, 4.0f)).roundToLong()
          }
        ctx.copy(anchorClipOffsetMs = safeClipOffset, anchorSourceOffsetMs = anchorSrc)
      }
    processedFramesSinceFlush = 0L
  }

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return

    val ctx = segmentContext
    val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
    val bytesPerFrame = channelCount * 2
    val remainingBytes = inputBuffer.remaining()
    val outputBuffer = replaceOutputBuffer(remainingBytes)
    val frameCount = remainingBytes / bytesPerFrame

    if (ctx == null) {
      outputBuffer.put(inputBuffer)
      processedFramesSinceFlush += frameCount
      outputBuffer.flip()
      return
    }

    val safeSpeed = ctx.clipSpeed.coerceIn(0.25f, 4.0f)
    for (frame in 0 until frameCount) {
      val currentFrameIndex = processedFramesSinceFlush + frame
      val elapsedSourceMs = (currentFrameIndex * 1000.0) / sampleRate.toDouble()
      val clipOffsetMs =
        if (ctx.speedCurve.size >= 2 && ctx.sourceSpanMs > 0L) {
          val absSourceMs = ctx.anchorSourceOffsetMs.toDouble() + elapsedSourceMs
          SpeedCurveEngine.sourceOffsetMsToTimelineMsDouble(
            sourceOffsetMs = absSourceMs,
            sourceSpanMs = ctx.sourceSpanMs,
            baseSpeed = safeSpeed,
            speedCurve = ctx.speedCurve,
          ).roundToLong().coerceIn(0L, ctx.clipDurationMs)
        } else {
          val elapsedTimelineMs = (elapsedSourceMs / safeSpeed.toDouble()).roundToLong()
          (ctx.anchorClipOffsetMs + elapsedTimelineMs).coerceIn(0L, ctx.clipDurationMs)
        }

      val gain =
        if (!ctx.isTrackAudible) {
          0f
        } else {
          AudioTrackMixer.computeClipEnvelopeGainAt(
            clipStartOnTimelineMs = ctx.clipStartOnTimelineMs,
            clipOffsetMs = clipOffsetMs,
            clipDurationMs = ctx.clipDurationMs,
            baseVolume = ctx.baseVolume,
            fadeInMs = ctx.fadeInMs,
            fadeOutMs = ctx.fadeOutMs,
            duckingWindows = ctx.duckingWindows,
            volumeKeyframes = ctx.volumeKeyframes,
            trackVolume = ctx.trackVolume,
            fadeCurve = ctx.fadeCurve,
          )
        }

      val (leftGain, rightGain) =
        if (channelCount == 2) {
          AudioTrackMixer.computeStereoChannelGains(gain, ctx.pan)
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

    while (inputBuffer.hasRemaining()) {
      outputBuffer.put(inputBuffer.get())
    }

    processedFramesSinceFlush += frameCount
    outputBuffer.flip()
  }

  override fun onFlush() {
    processedFramesSinceFlush = 0L
  }

  override fun onReset() {
    processedFramesSinceFlush = 0L
  }
}
