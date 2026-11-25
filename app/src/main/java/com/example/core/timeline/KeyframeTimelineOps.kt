package com.example.core.timeline

import com.example.core.model.AudioClipConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.ClipKind
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.KeyframeValidator
import com.example.core.model.TimelineClip
import com.example.core.model.VideoClipConfig
import kotlin.math.abs

/**
 * Pure clip-level keyframe CRUD, navigation, and remap helpers invoked by [TimelineEngine].
 */
internal object KeyframeTimelineOps {

  fun upsertKeyframe(
    clip: TimelineClip,
    offsetMs: Long,
    scale: Float? = null,
    opacity: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    rotationDegrees: Float? = null,
    volume: Float? = null,
    interpolation: KeyframeInterpolation? = null,
    mergeToleranceMs: Long = 40L,
    idGenerator: () -> String,
    depthBlur: Float? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
  ): TimelineClip? {
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return null
    if (offsetMs !in 0L..clip.durationMs) return null

    val numericInputs =
      listOfNotNull(
        scale,
        opacity,
        offsetX,
        offsetY,
        rotationDegrees,
        volume,
        depthBlur,
        depthFocusX,
        depthFocusY,
        depthFocusRange,
      )
    if (numericInputs.any { !it.isFinite() }) return null

    val existingKeyframes = clip.keyframes()
    val evaluated = clip.evaluatePropertiesAt(offsetMs)
    val trackDepthOnClip =
      depthBlur != null ||
        depthFocusX != null ||
        depthFocusY != null ||
        depthFocusRange != null ||
        existingKeyframes.any {
          it.depthBlur != null ||
            it.depthFocusX != null ||
            it.depthFocusY != null ||
            it.depthFocusRange != null
        }
    val matchIdx =
      if (mergeToleranceMs >= 0L) {
        existingKeyframes.indexOfFirst { abs(it.offsetMs - offsetMs) <= mergeToleranceMs }
      } else {
        existingKeyframes.indexOfFirst { it.offsetMs == offsetMs }
      }

    val candidate =
      if (matchIdx >= 0) {
        val current = existingKeyframes[matchIdx]
        current.copy(
          offsetMs = offsetMs,
          scale = scale ?: current.scale,
          opacity = opacity ?: current.opacity,
          offsetX = offsetX ?: current.offsetX,
          offsetY = offsetY ?: current.offsetY,
          rotationDegrees = rotationDegrees ?: current.rotationDegrees,
          volume = volume ?: current.volume ?: evaluated.volume,
          interpolation = interpolation ?: current.interpolation,
          depthBlur = depthBlur ?: current.depthBlur,
          depthFocusX = depthFocusX ?: current.depthFocusX,
          depthFocusY = depthFocusY ?: current.depthFocusY,
          depthFocusRange = depthFocusRange ?: current.depthFocusRange,
        )
      } else {
        ClipKeyframe(
          keyframeId = idGenerator(),
          offsetMs = offsetMs,
          scale = scale ?: evaluated.scale,
          opacity = opacity ?: evaluated.opacity,
          offsetX = offsetX ?: evaluated.offsetX,
          offsetY = offsetY ?: evaluated.offsetY,
          rotationDegrees = rotationDegrees ?: evaluated.rotationDegrees,
          volume = volume ?: evaluated.volume,
          interpolation = interpolation ?: evaluated.interpolation,
          depthBlur = if (trackDepthOnClip) depthBlur ?: evaluated.depthBlur else null,
          depthFocusX = if (trackDepthOnClip) depthFocusX ?: evaluated.depthFocusX else null,
          depthFocusY = if (trackDepthOnClip) depthFocusY ?: evaluated.depthFocusY else null,
          depthFocusRange = if (trackDepthOnClip) depthFocusRange ?: evaluated.depthFocusRange else null,
        )
      }

    val cleanKf = KeyframeValidator.sanitizeKeyframe(candidate, clip.durationMs)
    val nextKeyframes =
      if (matchIdx >= 0) {
        existingKeyframes
          .mapIndexed { idx, kf -> if (idx == matchIdx) cleanKf else kf }
          .sortedBy { it.offsetMs }
      } else {
        (existingKeyframes + cleanKf).sortedBy { it.offsetMs }
      }

    return withKeyframes(clip, nextKeyframes)
  }

  fun moveKeyframe(
    clip: TimelineClip,
    keyframeId: String,
    newOffsetMs: Long,
  ): TimelineClip? {
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return null
    if (newOffsetMs !in 0L..clip.durationMs) return null

    val keyframes = clip.keyframes()
    val target = keyframes.find { it.keyframeId == keyframeId } ?: return null
    val moved = target.copy(offsetMs = newOffsetMs)
    val nextKeyframes =
      keyframes
        .filterNot { it.keyframeId == keyframeId || it.offsetMs == newOffsetMs }
        .plus(moved)
        .sortedBy { it.offsetMs }
    return withKeyframes(clip, nextKeyframes)
  }

  fun removeKeyframe(
    clip: TimelineClip,
    keyframeId: String,
  ): TimelineClip? {
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return null
    val keyframes = clip.keyframes()
    if (keyframes.none { it.keyframeId == keyframeId }) return null
    return withKeyframes(clip, keyframes.filterNot { it.keyframeId == keyframeId })
  }

  fun updateKeyframeInterpolation(
    clip: TimelineClip,
    keyframeId: String,
    interpolation: KeyframeInterpolation,
  ): TimelineClip? {
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return null
    val keyframes = clip.keyframes()
    if (keyframes.none { it.keyframeId == keyframeId }) return null
    val nextKeyframes =
      keyframes.map { kf ->
        if (kf.keyframeId == keyframeId) kf.copy(interpolation = interpolation) else kf
      }
    return withKeyframes(clip, nextKeyframes)
  }

  fun clearKeyframes(clip: TimelineClip): TimelineClip? {
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return null
    return withKeyframes(clip, emptyList())
  }

  fun sliceClipKeyframes(
    clip: TimelineClip,
    rangeOffsetStartMs: Long,
    rangeOffsetEndMs: Long,
    idPrefix: String,
  ): TimelineClip {
    val slicedVideo =
      clip.videoConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes =
              ClipKeyframeEvaluator.sliceKeyframesForRange(
                keyframes = cfg.keyframes,
                rangeOffsetStartMs = rangeOffsetStartMs,
                rangeOffsetEndMs = rangeOffsetEndMs,
                baseScale = cfg.scale,
                baseOpacity = cfg.opacity,
                baseOffsetX = cfg.offsetX,
                baseOffsetY = cfg.offsetY,
                baseRotationDegrees = cfg.rotationDegrees,
                baseVolume = cfg.volume,
                idPrefix = idPrefix,
                baseDepthBlur = cfg.depthBlur,
                baseDepthFocusX = cfg.depthFocusX,
                baseDepthFocusY = cfg.depthFocusY,
                baseDepthFocusRange = cfg.depthFocusRange,
              ),
          )
        }
      }

    val slicedAudio =
      clip.audioConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes =
              ClipKeyframeEvaluator.sliceKeyframesForRange(
                keyframes = cfg.keyframes,
                rangeOffsetStartMs = rangeOffsetStartMs,
                rangeOffsetEndMs = rangeOffsetEndMs,
                baseVolume = cfg.volume,
                idPrefix = idPrefix,
              ),
          )
        }
      }

    return clip.copy(videoConfig = slicedVideo, audioConfig = slicedAudio)
  }

  fun remapClipKeyframesOnTrim(
    clip: TimelineClip,
    oldStartMs: Long,
    oldEndMs: Long,
    newStartMs: Long,
    newEndMs: Long,
  ): TimelineClip {
    val startShiftMs = oldStartMs - newStartMs
    val newDurationMs = (newEndMs - newStartMs).coerceAtLeast(1L)
    val oldDurationMs = (oldEndMs - oldStartMs).coerceAtLeast(1L)

    fun remapList(
      keyframes: List<ClipKeyframe>,
      baseScale: Float = 1.0f,
      baseOpacity: Float = 1.0f,
      baseOffsetX: Float = 0.0f,
      baseOffsetY: Float = 0.0f,
      baseRotationDegrees: Float = 0.0f,
      baseVolume: Float = 1.0f,
      baseDepthBlur: Float = 0.0f,
      baseDepthFocusX: Float = 0.5f,
      baseDepthFocusY: Float = 0.5f,
      baseDepthFocusRange: Float = 0.28f,
    ): List<ClipKeyframe> {
      if (keyframes.isEmpty()) return emptyList()
      return if (newStartMs >= oldStartMs && newEndMs <= oldEndMs) {
        val relStart = (newStartMs - oldStartMs).coerceIn(0L, oldDurationMs)
        val relEnd = (newEndMs - oldStartMs).coerceIn(relStart, oldDurationMs)
        ClipKeyframeEvaluator.sliceKeyframesForRange(
          keyframes = keyframes,
          rangeOffsetStartMs = relStart,
          rangeOffsetEndMs = relEnd,
          baseScale = baseScale,
          baseOpacity = baseOpacity,
          baseOffsetX = baseOffsetX,
          baseOffsetY = baseOffsetY,
          baseRotationDegrees = baseRotationDegrees,
          baseVolume = baseVolume,
          idPrefix = "${clip.clipId}_trim",
          baseDepthBlur = baseDepthBlur,
          baseDepthFocusX = baseDepthFocusX,
          baseDepthFocusY = baseDepthFocusY,
          baseDepthFocusRange = baseDepthFocusRange,
        )
      } else {
        // Outward extension on one or both ends: preserve relative timeline anchor by shifting offsets.
        val shifted =
          keyframes.mapNotNull { kf ->
            val shiftedOffset = kf.offsetMs + startShiftMs
            if (shiftedOffset in 0L..newDurationMs) kf.copy(offsetMs = shiftedOffset) else null
          }
        shifted.sortedBy { it.offsetMs }
      }
    }

    val updatedVideo =
      clip.videoConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes =
              remapList(
                keyframes = cfg.keyframes,
                baseScale = cfg.scale,
                baseOpacity = cfg.opacity,
                baseOffsetX = cfg.offsetX,
                baseOffsetY = cfg.offsetY,
                baseRotationDegrees = cfg.rotationDegrees,
                baseVolume = cfg.volume,
                baseDepthBlur = cfg.depthBlur,
                baseDepthFocusX = cfg.depthFocusX,
                baseDepthFocusY = cfg.depthFocusY,
                baseDepthFocusRange = cfg.depthFocusRange,
              ),
          )
        }
      }
    val updatedAudio =
      clip.audioConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes = remapList(keyframes = cfg.keyframes, baseVolume = cfg.volume),
          )
        }
      }

    return clip.copy(videoConfig = updatedVideo, audioConfig = updatedAudio)
  }

  fun rescaleClipKeyframesForDuration(
    clip: TimelineClip,
    oldDurationMs: Long,
    newDurationMs: Long,
  ): TimelineClip {
    val updatedVideo =
      clip.videoConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) cfg
        else cfg.copy(keyframes = ClipKeyframeEvaluator.rescaleKeyframesForDuration(cfg.keyframes, oldDurationMs, newDurationMs))
      }
    val updatedAudio =
      clip.audioConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) cfg
        else cfg.copy(keyframes = ClipKeyframeEvaluator.rescaleKeyframesForDuration(cfg.keyframes, oldDurationMs, newDurationMs))
      }
    return clip.copy(videoConfig = updatedVideo, audioConfig = updatedAudio)
  }

  private fun withKeyframes(clip: TimelineClip, keyframes: List<ClipKeyframe>): TimelineClip =
    when (clip.kind) {
      ClipKind.VIDEO ->
        clip.copy(videoConfig = (clip.videoConfig ?: VideoClipConfig()).copy(keyframes = keyframes))
      ClipKind.AUDIO ->
        clip.copy(audioConfig = (clip.audioConfig ?: AudioClipConfig()).copy(keyframes = keyframes))
      else -> clip
    }
}
