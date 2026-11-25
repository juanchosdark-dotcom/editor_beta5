package com.example.core.media.audio

import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.DuckingWindow
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class AudioFadeCurve {
  /** Smooth raised-cosine S-curve (`0.5 - 0.5 * cos(pi * t)`), avoiding slope clicks at 0/1. */
  SMOOTH_S_CURVE,

  /** Classic linear amplitude ramp `t`. */
  LINEAR,
}

/**
 * Pure audio mixing rules for track Mute/Solo resolution, basic side-chain ducking,
 * stereo pan balance, and sample-accurate gain + smooth fade-in/fade-out + keyframed volume
 * envelope calculation shared by live preview and export.
 */
object AudioTrackMixer {

  /**
   * Resolves effective audibility per track ID.
   * When any audio-bearing track has `isSolo == true`, non-solo tracks are silenced.
   * Explicit `isMuted == true` always silences the track.
   */
  fun resolveTrackAudibility(tracks: List<Track>): Map<String, Boolean> {
    val audioBearingTracks =
      tracks.filter { it.type == TrackType.VIDEO || it.type == TrackType.AUDIO }
    val anySoloActive = audioBearingTracks.any { it.isSolo }

    return tracks.associate { track ->
      val audible =
        when {
          track.type != TrackType.VIDEO && track.type != TrackType.AUDIO -> false
          track.isMuted -> false
          anySoloActive -> track.isSolo
          else -> true
        }
      track.trackId to audible
    }
  }

  /**
   * Resolves effective track gain multiplier in `[0f..2f]`, returning `0f` if muted or
   * suppressed by another solo track.
   */
  fun resolveEffectiveTrackGain(
    track: Track,
    audibility: Map<String, Boolean>,
  ): Float {
    if (audibility[track.trackId] != true) return 0f
    return track.volume.coerceIn(0f, 2f)
  }

  /**
   * Computes ducking intervals on `state.duckingConfig.targetTrackId` wherever active,
   * audible clips are playing on `state.duckingConfig.triggerTrackId`.
   */
  fun computeDuckingWindows(state: TimelineState): List<DuckingWindow> {
    val config = state.duckingConfig
    if (!config.enabled) return emptyList()

    val audibility = resolveTrackAudibility(state.tracks)
    if (audibility[config.triggerTrackId] != true) return emptyList()
    if (audibility[config.targetTrackId] != true) return emptyList()

    val triggerClips =
      state.clips
        .filter { it.trackId == config.triggerTrackId && it.isEnabled }
        .sortedBy { it.timelineStartMs }

    if (triggerClips.isEmpty()) return emptyList()

    // Merge overlapping/adjacent trigger clips into clean continuous ducking spans
    val merged = mutableListOf<Pair<Long, Long>>()
    for (clip in triggerClips) {
      val last = merged.lastOrNull()
      if (last != null && clip.timelineStartMs <= last.second + config.rampMs) {
        merged[merged.lastIndex] = last.first to max(last.second, clip.timelineEndMs)
      } else {
        merged.add(clip.timelineStartMs to clip.timelineEndMs)
      }
    }

    val clampedAttenuation = config.attenuationFactor.coerceIn(0.05f, 1.0f)
    return merged.map { (start, end) ->
      DuckingWindow(
        startMs = start,
        endMs = end,
        attenuationFactor = clampedAttenuation,
        rampMs = config.rampMs.coerceAtLeast(0L),
      )
    }
  }

  /**
   * Evaluates a normalized fade progress `t in 0f..1f` using [curve].
   */
  fun evaluateFadeCurve(
    progress: Float,
    curve: AudioFadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
  ): Float {
    val t = progress.coerceIn(0f, 1f)
    return when (curve) {
      AudioFadeCurve.LINEAR -> t
      AudioFadeCurve.SMOOTH_S_CURVE -> (0.5 - 0.5 * cos(PI * t.toDouble())).toFloat().coerceIn(0f, 1f)
    }
  }

  /**
   * Splits a scalar [monoGain] into `(leftGain, rightGain)` according to stereo [pan] in `-1f..1f`.
   * Center `0f` yields `(monoGain, monoGain)` exact unity.
   */
  fun computeStereoChannelGains(
    monoGain: Float,
    pan: Float,
  ): Pair<Float, Float> {
    val safePan = pan.coerceIn(-1f, 1f)
    val left = monoGain * (1f - safePan.coerceAtLeast(0f))
    val right = monoGain * (1f + safePan.coerceAtMost(0f))
    return left to right
  }

  /**
   * Returns the linear volume multiplier at [clipOffsetMs] inside a clip of [clipDurationMs],
   * combining base volume, smooth fade-in/fade-out ramps, and any overlapping [duckingWindows].
   */
  fun computeClipEnvelopeGainAt(
    clipStartOnTimelineMs: Long,
    clipOffsetMs: Long,
    clipDurationMs: Long,
    baseVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    duckingWindows: List<DuckingWindow>,
  ): Float =
    computeClipEnvelopeGainAt(
      clipStartOnTimelineMs = clipStartOnTimelineMs,
      clipOffsetMs = clipOffsetMs,
      clipDurationMs = clipDurationMs,
      baseVolume = baseVolume,
      fadeInMs = fadeInMs,
      fadeOutMs = fadeOutMs,
      duckingWindows = duckingWindows,
      volumeKeyframes = emptyList(),
      trackVolume = 1.0f,
      fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
    )

  /**
   * Returns the deterministic linear volume multiplier at [clipOffsetMs] inside a clip of
   * [clipDurationMs], combining animated [volumeKeyframes], [trackVolume], smooth
   * fade-in/fade-out ramps, and any overlapping [duckingWindows].
   */
  fun computeClipEnvelopeGainAt(
    clipStartOnTimelineMs: Long,
    clipOffsetMs: Long,
    clipDurationMs: Long,
    baseVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    duckingWindows: List<DuckingWindow>,
    volumeKeyframes: List<ClipKeyframe> = emptyList(),
    trackVolume: Float = 1.0f,
    fadeCurve: AudioFadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
  ): Float {
    val safeTrackVol = trackVolume.coerceIn(0f, 2f)
    if (clipDurationMs <= 0L || safeTrackVol <= 0f) return 0f

    val safeOffset = clipOffsetMs.coerceIn(0L, clipDurationMs)
    val animatedVolume =
      if (volumeKeyframes.any { it.volume != null }) {
        ClipKeyframeEvaluator.evaluateAt(
          offsetMs = safeOffset,
          keyframes = volumeKeyframes,
          baseVolume = baseVolume,
        ).volume
      } else {
        baseVolume
      }
    val effectiveBaseVolume = (animatedVolume * safeTrackVol).coerceIn(0f, 2f)
    if (effectiveBaseVolume <= 0f) return 0f

    val effectiveFadeInMs = fadeInMs.coerceIn(0L, clipDurationMs)
    val fadeInFactor =
      if (effectiveFadeInMs > 0L && safeOffset < effectiveFadeInMs) {
        evaluateFadeCurve(safeOffset.toFloat() / effectiveFadeInMs.toFloat(), fadeCurve)
      } else {
        1f
      }

    val remainingMs = (clipDurationMs - safeOffset).coerceAtLeast(0L)
    val effectiveFadeOutMs = fadeOutMs.coerceIn(0L, clipDurationMs)
    val fadeOutFactor =
      if (effectiveFadeOutMs > 0L && remainingMs < effectiveFadeOutMs) {
        evaluateFadeCurve(remainingMs.toFloat() / effectiveFadeOutMs.toFloat(), fadeCurve)
      } else {
        1f
      }

    val timelinePosMs = clipStartOnTimelineMs + safeOffset
    var duckFactor = 1f
    for (window in duckingWindows) {
      val ramp = window.rampMs
      val expandedStart = (window.startMs - ramp).coerceAtLeast(0L)
      val expandedEnd = window.endMs + ramp
      if (timelinePosMs in expandedStart..expandedEnd) {
        val factor =
          when {
            timelinePosMs < window.startMs && ramp > 0L -> {
              val t = evaluateFadeCurve((timelinePosMs - expandedStart).toFloat() / ramp.toFloat(), fadeCurve)
              1f - (1f - window.attenuationFactor) * t
            }
            timelinePosMs > window.endMs && ramp > 0L -> {
              val t = evaluateFadeCurve((timelinePosMs - window.endMs).toFloat() / ramp.toFloat(), fadeCurve)
              window.attenuationFactor + (1f - window.attenuationFactor) * t
            }
            else -> window.attenuationFactor
          }
        duckFactor = min(duckFactor, factor)
      }
    }

    return (effectiveBaseVolume * fadeInFactor * fadeOutFactor * duckFactor).coerceIn(0f, 2f)
  }

  /**
   * Filters ducking windows relevant to a specific [clip].
   */
  fun duckingWindowsForClip(
    clip: TimelineClip,
    allTargetWindows: List<DuckingWindow>,
  ): List<DuckingWindow> {
    return allTargetWindows.filter { window ->
      (window.startMs - window.rampMs) < clip.timelineEndMs &&
        (window.endMs + window.rampMs) > clip.timelineStartMs
    }
  }
}
