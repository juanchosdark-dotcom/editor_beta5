package com.example.core.ergonomics

import com.example.core.model.TimelineState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pure ergonomic math helpers for magnetic snapping, stylus fine-scrubbing sensitivity,
 * focal-anchored pinch-to-zoom, and fit-to-timeline zoom calculation.
 */
object TimelineErgonomicsHelper {

  /**
   * Snaps [rawPositionMs] to the closest marker or clip boundary within [thresholdMs]
   * when [snapEnabled] is true.
   */
  fun applySnapIfEnabled(
    rawPositionMs: Long,
    state: TimelineState,
    thresholdMs: Long,
    snapEnabled: Boolean,
  ): Long {
    val safeRaw = rawPositionMs.coerceAtLeast(0L)
    if (!snapEnabled || thresholdMs <= 0L) return safeRaw

    val snapTargets = buildList {
      add(0L)
      state.markers.forEach { add(it.positionMs) }
      state.clips.forEach {
        add(it.timelineStartMs)
        add(it.timelineEndMs)
      }
    }

    var closestTarget = safeRaw
    var closestDistance = Long.MAX_VALUE
    for (target in snapTargets) {
      val distance = abs(target - safeRaw)
      if (distance <= thresholdMs && distance < closestDistance) {
        closestDistance = distance
        closestTarget = target
      }
    }
    return closestTarget
  }

  /**
   * Converts horizontal pointer drag distance in pixels into timeline milliseconds,
   * scaling down automatically when using a Stylus for frame-accurate positioning.
   */
  fun pointerDeltaPxToTimelineMs(
    deltaPx: Float,
    pxPerSecond: Float,
    inputMode: InputDeviceMode,
  ): Long {
    if (pxPerSecond <= 0f) return 0L
    val rawMs = (deltaPx / pxPerSecond) * 1000f
    return (rawMs * inputMode.scrubSensitivity).roundToLong()
  }

  fun clampZoom(currentScale: Float, factor: Float): Float {
    val safeCurrent = if (currentScale.isFinite() && currentScale > 0f) currentScale else 1.0f
    val safeFactor = if (factor.isFinite() && factor > 0f) factor else 1.0f
    return (safeCurrent * safeFactor).coerceIn(
      TimelineViewportErgonomics.MIN_ZOOM,
      TimelineViewportErgonomics.MAX_ZOOM,
    )
  }

  /**
   * Computes the horizontal scroll offset (px) that keeps the timeline moment under
   * [focalViewportX] anchored during a pinch-to-zoom transition from [oldZoom] to [newZoom].
   */
  fun calculateFocalAnchoredScrollPx(
    currentScrollPx: Float,
    focalViewportX: Float,
    oldZoom: Float,
    newZoom: Float,
  ): Int {
    if (!oldZoom.isFinite() || !newZoom.isFinite() || oldZoom <= 0f || newZoom <= 0f) {
      return currentScrollPx.coerceAtLeast(0f).roundToInt()
    }
    val ratio = newZoom / oldZoom
    val safeFocalX = focalViewportX.coerceAtLeast(0f)
    val safeScroll = currentScrollPx.coerceAtLeast(0f)
    return ((safeScroll + safeFocalX) * ratio - safeFocalX)
      .roundToInt()
      .coerceAtLeast(0)
  }

  /**
   * Computes a zoom scale so the active timeline duration ([totalDurationMs]) fits cleanly
   * inside [viewportWidthDp] without horizontal overflow, clamped to valid zoom bounds.
   */
  fun calculateFitZoomScale(
    viewportWidthDp: Float,
    totalDurationMs: Long,
    baseDpPerSecond: Float = 56f,
    paddingFraction: Float = 0.94f,
  ): Float {
    if (viewportWidthDp <= 0f || totalDurationMs <= 0L || baseDpPerSecond <= 0f) return 1.0f
    val durationSec = (totalDurationMs / 1000f).coerceAtLeast(1f)
    val targetDpPerSecond = (viewportWidthDp * paddingFraction.coerceIn(0.5f, 1f)) / durationSec
    return (targetDpPerSecond / baseDpPerSecond).coerceIn(
      TimelineViewportErgonomics.MIN_ZOOM,
      TimelineViewportErgonomics.MAX_ZOOM,
    )
  }

  data class ViewerPanSnapResult(
    val panNormalizedX: Float,
    val panNormalizedY: Float,
    val snappedToCenterX: Boolean,
    val snappedToCenterY: Boolean,
  )

  fun clampViewerZoom(currentScale: Float, factor: Float): Float {
    val safeCurrent =
      if (currentScale.isFinite() && currentScale > 0f) currentScale else ViewerViewportState.MIN_ZOOM
    val safeFactor = if (factor.isFinite() && factor > 0f) factor else 1.0f
    return (safeCurrent * safeFactor).coerceIn(
      ViewerViewportState.MIN_ZOOM,
      ViewerViewportState.MAX_ZOOM,
    )
  }

  fun applyViewerCenterSnap(
    rawPanNormalizedX: Float,
    rawPanNormalizedY: Float,
    zoomScale: Float,
    magneticCenterSnap: Boolean,
    snapThreshold: Float = ViewerViewportState.CENTER_SNAP_THRESHOLD,
  ): ViewerPanSnapResult {
    val safeZoom =
      if (zoomScale.isFinite()) {
        zoomScale.coerceIn(ViewerViewportState.MIN_ZOOM, ViewerViewportState.MAX_ZOOM)
      } else {
        ViewerViewportState.MIN_ZOOM
      }
    val maxPan = ((safeZoom - 1f) * 0.5f + 0.25f).coerceIn(0.25f, 1.75f)
    val clampedX =
      if (rawPanNormalizedX.isFinite()) rawPanNormalizedX.coerceIn(-maxPan, maxPan) else 0f
    val clampedY =
      if (rawPanNormalizedY.isFinite()) rawPanNormalizedY.coerceIn(-maxPan, maxPan) else 0f

    val safeThreshold = snapThreshold.coerceAtLeast(0f)
    val snapX = magneticCenterSnap && abs(clampedX) <= safeThreshold
    val snapY = magneticCenterSnap && abs(clampedY) <= safeThreshold
    return ViewerPanSnapResult(
      panNormalizedX = if (snapX) 0f else clampedX,
      panNormalizedY = if (snapY) 0f else clampedY,
      snappedToCenterX = snapX,
      snappedToCenterY = snapY,
    )
  }
}
