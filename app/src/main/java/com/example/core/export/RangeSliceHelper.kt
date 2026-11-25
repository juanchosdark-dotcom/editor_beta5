package com.example.core.export

import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.ClipKind
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class ExportRangeBounds(
  val startMs: Long,
  val endMs: Long,
) {
  val durationMs: Long
    get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Pure domain helpers for slicing a multitrack [TimelineState] and computing exact export
 * range bounds/durations before compiling for local export.
 */
object RangeSliceHelper {

  fun resolveExportRangeBounds(
    range: ExportRange,
    totalDurationMs: Long,
  ): ExportRangeBounds {
    val safeTotal = totalDurationMs.coerceAtLeast(0L)
    return when (range) {
      is ExportRange.FullTimeline -> ExportRangeBounds(0L, safeTotal)
      is ExportRange.TimeRange -> {
        val start = range.startMs.coerceIn(0L, safeTotal)
        val end = range.endMs.coerceIn(start, safeTotal)
        ExportRangeBounds(start, end)
      }
      is ExportRange.MarkerSpan -> {
        val start = range.startMs.coerceIn(0L, safeTotal)
        val end = range.endMs.coerceIn(start, safeTotal)
        ExportRangeBounds(start, end)
      }
    }
  }

  fun resolveMarkerSelectionBounds(
    sortedMarkers: List<TimelineMarker>,
    startBoundaryIndex: Int,
    endBoundaryIndex: Int,
    totalDurationMs: Long,
  ): ExportRangeBounds {
    val safeTotal = totalDurationMs.coerceAtLeast(0L)
    val startMs =
      if (startBoundaryIndex < 0) {
        0L
      } else {
        sortedMarkers.getOrNull(startBoundaryIndex)?.positionMs?.coerceIn(0L, safeTotal) ?: 0L
      }
    val rawEndMs =
      if (endBoundaryIndex >= sortedMarkers.size) {
        safeTotal
      } else {
        sortedMarkers.getOrNull(endBoundaryIndex)?.positionMs?.coerceIn(0L, safeTotal) ?: safeTotal
      }
    return ExportRangeBounds(startMs = startMs, endMs = rawEndMs.coerceAtLeast(startMs))
  }

  fun calculateExportDurationMs(
    range: ExportRange,
    totalDurationMs: Long,
  ): Long = resolveExportRangeBounds(range, totalDurationMs).durationMs

  fun calculateTotalBatchDurationMs(
    ranges: List<ExportRange>,
    totalDurationMs: Long,
  ): Long = ranges.sumOf { calculateExportDurationMs(it, totalDurationMs) }

  /**
   * Returns a normalized [TimelineState] containing only the segments overlapping
   * `[rangeStartMs, rangeEndMs]`, shifted so `rangeStartMs` maps to `0L`.
   */
  fun sliceTimelineForRange(
    timeline: TimelineState,
    rangeStartMs: Long,
    rangeEndMs: Long,
  ): TimelineState {
    require(rangeStartMs >= 0L) { "rangeStartMs must be >= 0" }
    require(rangeEndMs > rangeStartMs) { "rangeEndMs must be > rangeStartMs" }

    val slicedClips =
      timeline.clips.mapNotNull { clip ->
        sliceClip(clip, rangeStartMs, rangeEndMs)
      }

    val slicedMarkers =
      timeline.markers
        .filter { it.positionMs in rangeStartMs..rangeEndMs }
        .map { it.copy(positionMs = it.positionMs - rangeStartMs) }

    return timeline.copy(
      playheadMs = 0L,
      clips = slicedClips.sortedBy { it.timelineStartMs },
      markers = slicedMarkers,
    )
  }

  private fun sliceClip(
    clip: TimelineClip,
    rangeStartMs: Long,
    rangeEndMs: Long,
  ): TimelineClip? {
    if (!clip.isEnabled) return null
    val overlapStart = max(clip.timelineStartMs, rangeStartMs)
    val overlapEnd = min(clip.timelineEndMs, rangeEndMs)
    if (overlapEnd <= overlapStart) return null

    val hasMediaSource = clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO
    val trimLeftTimelineMs = overlapStart - clip.timelineStartMs
    val trimRightTimelineMs = clip.timelineEndMs - overlapEnd
    val slicedDurationMs = (overlapEnd - overlapStart).coerceAtLeast(1L)
    val oldSourceSpan = clip.sourceSpanMs

    val (nextSourceIn, nextSourceOut, nextCurve, nextSpeed) =
      if (hasMediaSource && clip.hasVariableSpeed && oldSourceSpan > 0L) {
        val sliceStartTimeline = trimLeftTimelineMs.coerceIn(0L, clip.durationMs)
        val sliceEndTimeline = (clip.durationMs - trimRightTimelineMs).coerceIn(sliceStartTimeline, clip.durationMs)
        val srcStartOffset =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = sliceStartTimeline,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = clip.speed,
            speedCurve = clip.speedCurve,
          )
        val srcEndOffset =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = sliceEndTimeline,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = clip.speed,
            speedCurve = clip.speedCurve,
          )
        val computedIn = clip.sourceInMs + srcStartOffset
        val computedOut = (clip.sourceInMs + srcEndOffset).coerceAtLeast(computedIn + 1L)
        val subCurve =
          SpeedCurveEngine.sliceCurveForSourceRange(
            speedCurve = clip.speedCurve,
            baseSpeed = clip.speed,
            oldSourceSpanMs = oldSourceSpan,
            subSourceStartMs = srcStartOffset,
            subSourceEndMs = (computedOut - clip.sourceInMs).coerceAtLeast(srcStartOffset + 1L),
          )
        val avgSpeed =
          SpeedCurveEngine.averageEffectiveSpeed(
            sourceSpanMs = (computedOut - computedIn).coerceAtLeast(1L),
            baseSpeed = clip.speed,
            speedCurve = subCurve,
          )
        listOf(computedIn, computedOut, subCurve, avgSpeed)
      } else {
        val computedIn =
          if (hasMediaSource) {
            clip.sourceInMs + (trimLeftTimelineMs * clip.speed).roundToLong()
          } else {
            clip.sourceInMs
          }
        val computedOut =
          if (hasMediaSource) {
            (clip.sourceOutMs - (trimRightTimelineMs * clip.speed).roundToLong())
              .coerceAtLeast(computedIn + 1L)
          } else {
            clip.sourceOutMs
          }
        listOf(computedIn, computedOut, clip.speedCurve, clip.speed)
      }

    val typedSourceIn = nextSourceIn as Long
    val typedSourceOut = nextSourceOut as Long
    @Suppress("UNCHECKED_CAST")
    val typedCurve = nextCurve as List<com.example.core.model.SpeedCurvePoint>
    val typedSpeed = nextSpeed as Float

    val sliceRelStartMs = (overlapStart - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val sliceRelEndMs = (overlapEnd - clip.timelineStartMs).coerceIn(sliceRelStartMs, clip.durationMs)

    val slicedVideoConfig =
      clip.videoConfig?.let { cfg ->
        val nextKeyframes =
          if (cfg.keyframes.isEmpty()) {
            emptyList()
          } else {
            ClipKeyframeEvaluator.sliceKeyframesForRange(
              keyframes = cfg.keyframes,
              rangeOffsetStartMs = sliceRelStartMs,
              rangeOffsetEndMs = sliceRelEndMs,
              baseScale = cfg.scale,
              baseOpacity = cfg.opacity,
              baseOffsetX = cfg.offsetX,
              baseOffsetY = cfg.offsetY,
              baseRotationDegrees = cfg.rotationDegrees,
              baseVolume = cfg.volume,
              idPrefix = "${clip.clipId}_range",
              baseDepthBlur = cfg.depthBlur,
              baseDepthFocusX = cfg.depthFocusX,
              baseDepthFocusY = cfg.depthFocusY,
              baseDepthFocusRange = cfg.depthFocusRange,
            )
          }
        cfg.copy(
          fadeInMs = (cfg.fadeInMs - trimLeftTimelineMs).coerceIn(0L, slicedDurationMs),
          fadeOutMs = (cfg.fadeOutMs - trimRightTimelineMs).coerceIn(0L, slicedDurationMs),
          transitionIn =
            if (trimLeftTimelineMs > 0L) {
              null
            } else {
              cfg.transitionIn?.let { it.copy(durationMs = it.durationMs.coerceAtMost(slicedDurationMs)) }
            },
          transitionOut =
            if (trimRightTimelineMs > 0L) {
              null
            } else {
              cfg.transitionOut?.let { it.copy(durationMs = it.durationMs.coerceAtMost(slicedDurationMs)) }
            },
          keyframes = nextKeyframes,
        )
      }

    val slicedAudioConfig =
      clip.audioConfig?.let { cfg ->
        val nextKeyframes =
          if (cfg.keyframes.isEmpty()) {
            emptyList()
          } else {
            ClipKeyframeEvaluator.sliceKeyframesForRange(
              keyframes = cfg.keyframes,
              rangeOffsetStartMs = sliceRelStartMs,
              rangeOffsetEndMs = sliceRelEndMs,
              baseVolume = cfg.volume,
              idPrefix = "${clip.clipId}_arange",
            )
          }
        cfg.copy(
          fadeInMs = (cfg.fadeInMs - trimLeftTimelineMs).coerceIn(0L, slicedDurationMs),
          fadeOutMs = (cfg.fadeOutMs - trimRightTimelineMs).coerceIn(0L, slicedDurationMs),
          keyframes = nextKeyframes,
        )
      }

    return clip.copy(
      timelineStartMs = overlapStart - rangeStartMs,
      timelineEndMs = overlapEnd - rangeStartMs,
      sourceInMs = typedSourceIn,
      sourceOutMs = typedSourceOut,
      speed = typedSpeed,
      speedCurve = typedCurve,
      videoConfig = slicedVideoConfig,
      audioConfig = slicedAudioConfig,
    )
  }

  /**
   * Builds consecutive chapter spans delimited by sorted [markers] across `[0, totalDurationMs]`.
   * Example: markers at 2.5s and 5.0s on a 9.5s timeline produce:
   * - `0.0s .. 2.5s` (Inicio -> Golpe de ritmo)
   * - `2.5s .. 5.0s` (Golpe de ritmo -> Cambio de escena)
   * - `5.0s .. 9.5s` (Cambio de escena -> Final)
   */
  fun buildMarkerChapterSpans(
    markers: List<TimelineMarker>,
    totalDurationMs: Long,
  ): List<ExportRange.MarkerSpan> {
    if (totalDurationMs <= 0L) return emptyList()
    val sorted =
      markers
        .filter { it.positionMs in 1L until totalDurationMs }
        .sortedBy { it.positionMs }
        .distinctBy { it.positionMs }

    if (sorted.isEmpty()) {
      return listOf(
        ExportRange.MarkerSpan(
          startMarkerId = null,
          endMarkerId = null,
          startMs = 0L,
          endMs = totalDurationMs,
          label = "Clip completo",
        ),
      )
    }

    val spans = mutableListOf<ExportRange.MarkerSpan>()
    var prevMs = 0L
    var prevMarker: TimelineMarker? = null

    for (marker in sorted) {
      if (marker.positionMs > prevMs) {
        spans.add(
          ExportRange.MarkerSpan(
            startMarkerId = prevMarker?.markerId,
            endMarkerId = marker.markerId,
            startMs = prevMs,
            endMs = marker.positionMs,
            label = "${prevMarker?.label ?: "Inicio"} → ${marker.label}",
          ),
        )
      }
      prevMs = marker.positionMs
      prevMarker = marker
    }

    if (totalDurationMs > prevMs && prevMarker != null) {
      spans.add(
        ExportRange.MarkerSpan(
          startMarkerId = prevMarker.markerId,
          endMarkerId = null,
          startMs = prevMs,
          endMs = totalDurationMs,
          label = "${prevMarker.label} → Final",
        ),
      )
    }

    return spans
  }
}
