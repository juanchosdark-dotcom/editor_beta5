package com.example.core.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Pure analytical engine for piecewise-linear variable speed curves ([SpeedCurvePoint]).
 *
 * Maps clip source timestamps `[0..sourceSpanMs]` to rendered timeline offsets and back
 * using closed-form integration of `dt = ds / v(s)`, guaranteeing exact A/V sync across
 * render planning, Media3 [androidx.media3.common.audio.SpeedProvider], audio envelope
 * processors, keyframe slicing, and live preview.
 */
object SpeedCurveEngine {

  const val MIN_SPEED = 0.25f
  const val MAX_SPEED = 4.0f

  fun clampSpeed(speed: Float): Float =
    (if (speed.isFinite()) speed else 1.0f).coerceIn(MIN_SPEED, MAX_SPEED)

  fun hasVariableCurve(speedCurve: List<SpeedCurvePoint>): Boolean = speedCurve.size >= 2

  /**
   * Produces a monotonic, clamped curve spanning `u in [0f, 1f]` with at least 2 anchors.
   */
  fun normalizeCurve(
    speedCurve: List<SpeedCurvePoint>,
    baseSpeed: Float = 1.0f,
  ): List<SpeedCurvePoint> {
    val safeBase = clampSpeed(baseSpeed)
    if (speedCurve.size < 2) {
      return listOf(
        SpeedCurvePoint(normalizedTime = 0.0f, speed = safeBase),
        SpeedCurvePoint(normalizedTime = 1.0f, speed = safeBase),
      )
    }

    val cleaned =
      speedCurve
        .map { pt ->
          SpeedCurvePoint(
            normalizedTime = (if (pt.normalizedTime.isFinite()) pt.normalizedTime else 0f).coerceIn(0f, 1f),
            speed = clampSpeed(pt.speed),
          )
        }
        .sortedBy { it.normalizedTime }

    val deduped = mutableListOf<SpeedCurvePoint>()
    for (pt in cleaned) {
      val last = deduped.lastOrNull()
      if (last != null && abs(pt.normalizedTime - last.normalizedTime) < 1e-4f) {
        deduped[deduped.lastIndex] = pt
      } else {
        deduped += pt
      }
    }

    if (deduped.isEmpty()) {
      return listOf(
        SpeedCurvePoint(0f, safeBase),
        SpeedCurvePoint(1f, safeBase),
      )
    }
    if (deduped.first().normalizedTime > 1e-4f) {
      deduped.add(0, SpeedCurvePoint(0f, deduped.first().speed))
    } else {
      deduped[0] = deduped[0].copy(normalizedTime = 0f)
    }

    if (deduped.last().normalizedTime < 1f - 1e-4f) {
      deduped.add(SpeedCurvePoint(1f, deduped.last().speed))
    } else {
      deduped[deduped.lastIndex] = deduped.last().copy(normalizedTime = 1f)
    }

    if (deduped.size < 2) {
      val s = deduped.first().speed
      return listOf(SpeedCurvePoint(0f, s), SpeedCurvePoint(1f, s))
    }
    return deduped
  }

  private fun pieceTimelineMs(
    pieceSourceMs: Double,
    v0: Double,
    v1: Double,
  ): Double {
    if (pieceSourceMs <= 0.0) return 0.0
    return if (abs(v1 - v0) < 1e-4) {
      pieceSourceMs / v0
    } else {
      pieceSourceMs * ln(v1 / v0) / (v1 - v0)
    }
  }

  /**
   * Computes rendered timeline duration (in ms) for [sourceSpanMs] under [baseSpeed] / [speedCurve].
   */
  fun renderedDurationMs(
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Long {
    val safeSource = sourceSpanMs.coerceAtLeast(0L)
    if (safeSource == 0L) return 0L
    if (!hasVariableCurve(speedCurve)) {
      val safeSpeed = clampSpeed(baseSpeed)
      return (safeSource.toDouble() / safeSpeed.toDouble()).roundToLong()
    }
    return sourceOffsetMsToTimelineMsDouble(safeSource.toDouble(), safeSource, baseSpeed, speedCurve).roundToLong()
  }

  fun sourceOffsetMsToTimelineMs(
    sourceOffsetMs: Long,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Long =
    sourceOffsetMsToTimelineMsDouble(
      sourceOffsetMs = sourceOffsetMs.toDouble(),
      sourceSpanMs = sourceSpanMs,
      baseSpeed = baseSpeed,
      speedCurve = speedCurve,
    ).roundToLong()

  fun sourceOffsetMsToTimelineMsDouble(
    sourceOffsetMs: Double,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Double {
    val safeSpan = sourceSpanMs.coerceAtLeast(1L).toDouble()
    val clampedSource = sourceOffsetMs.coerceIn(0.0, safeSpan)
    if (!hasVariableCurve(speedCurve)) {
      return clampedSource / clampSpeed(baseSpeed).toDouble()
    }
    val targetU = (clampedSource / safeSpan).coerceIn(0.0, 1.0)
    val curve = normalizeCurve(speedCurve, baseSpeed)
    var totalTimelineMs = 0.0
    for (i in 0 until curve.lastIndex) {
      val p0 = curve[i]
      val p1 = curve[i + 1]
      val u0 = p0.normalizedTime.toDouble()
      val u1 = p1.normalizedTime.toDouble()
      if (u1 <= u0) continue
      if (targetU <= u0) break

      val v0 = p0.speed.toDouble()
      val v1 = p1.speed.toDouble()
      val clampedEndU = minOf(targetU, u1)
      val frac = ((clampedEndU - u0) / (u1 - u0)).coerceIn(0.0, 1.0)
      val vAtEnd = v0 + (v1 - v0) * frac
      val subSourceMs = safeSpan * (clampedEndU - u0)
      totalTimelineMs += pieceTimelineMs(subSourceMs, v0, vAtEnd)
      if (targetU <= u1) break
    }
    return totalTimelineMs
  }

  /**
   * Inverse mapping from a clip-relative rendered timeline offset `[0..renderedDuration]`
   * back onto a source offset in `[0..sourceSpanMs]`.
   */
  fun timelineOffsetMsToSourceMs(
    timelineOffsetMs: Long,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Long =
    timelineOffsetMsToSourceMsDouble(
      timelineOffsetMs = timelineOffsetMs.toDouble(),
      sourceSpanMs = sourceSpanMs,
      baseSpeed = baseSpeed,
      speedCurve = speedCurve,
    ).roundToLong().coerceIn(0L, sourceSpanMs.coerceAtLeast(0L))

  fun timelineOffsetMsToSourceMsDouble(
    timelineOffsetMs: Double,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Double {
    val safeSpan = sourceSpanMs.coerceAtLeast(0L).toDouble()
    if (safeSpan <= 0.0 || timelineOffsetMs <= 0.0) return 0.0
    if (!hasVariableCurve(speedCurve)) {
      return (timelineOffsetMs * clampSpeed(baseSpeed).toDouble()).coerceIn(0.0, safeSpan)
    }

    val curve = normalizeCurve(speedCurve, baseSpeed)
    var remainingTimelineMs = timelineOffsetMs
    for (i in 0 until curve.lastIndex) {
      val p0 = curve[i]
      val p1 = curve[i + 1]
      val u0 = p0.normalizedTime.toDouble()
      val u1 = p1.normalizedTime.toDouble()
      val du = u1 - u0
      if (du <= 0.0) continue

      val pieceSourceMs = safeSpan * du
      val v0 = p0.speed.toDouble()
      val v1 = p1.speed.toDouble()
      val pieceDurationMs = pieceTimelineMs(pieceSourceMs, v0, v1)
      if (remainingTimelineMs <= pieceDurationMs || i == curve.lastIndex - 1) {
        val clampedElapsed = remainingTimelineMs.coerceIn(0.0, pieceDurationMs)
        val frac =
          if (abs(v1 - v0) < 1e-4) {
            (clampedElapsed * v0 / pieceSourceMs).coerceIn(0.0, 1.0)
          } else {
            val expTerm = exp((clampedElapsed * (v1 - v0)) / pieceSourceMs)
            ((v0 * (expTerm - 1.0)) / (v1 - v0)).coerceIn(0.0, 1.0)
          }
        return (safeSpan * (u0 + du * frac)).coerceIn(0.0, safeSpan)
      }
      remainingTimelineMs -= pieceDurationMs
    }
    return safeSpan
  }

  /**
   * Returns instantaneous playback speed at normalized source position `u in [0..1]`.
   */
  fun instantaneousSpeedAtNormalizedSource(
    normalizedSourceTime: Float,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Float {
    if (!hasVariableCurve(speedCurve)) return clampSpeed(baseSpeed)
    val u = normalizedSourceTime.coerceIn(0f, 1f)
    val curve = normalizeCurve(speedCurve, baseSpeed)
    for (i in 0 until curve.lastIndex) {
      val p0 = curve[i]
      val p1 = curve[i + 1]
      if (u <= p1.normalizedTime || i == curve.lastIndex - 1) {
        val du = (p1.normalizedTime - p0.normalizedTime).coerceAtLeast(1e-5f)
        val frac = ((u - p0.normalizedTime) / du).coerceIn(0f, 1f)
        return clampSpeed(p0.speed + (p1.speed - p0.speed) * frac)
      }
    }
    return clampSpeed(curve.last().speed)
  }

  fun instantaneousSpeedAtSourceMs(
    sourceOffsetMs: Long,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Float {
    if (!hasVariableCurve(speedCurve) || sourceSpanMs <= 0L) return clampSpeed(baseSpeed)
    val u = (sourceOffsetMs.toDouble() / sourceSpanMs.toDouble()).toFloat().coerceIn(0f, 1f)
    return instantaneousSpeedAtNormalizedSource(u, baseSpeed, speedCurve)
  }

  fun instantaneousSpeedAtTimelineMs(
    timelineOffsetMs: Long,
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Float {
    if (!hasVariableCurve(speedCurve) || sourceSpanMs <= 0L) return clampSpeed(baseSpeed)
    val sourceOffset = timelineOffsetMsToSourceMsDouble(timelineOffsetMs.toDouble(), sourceSpanMs, baseSpeed, speedCurve)
    val u = (sourceOffset / sourceSpanMs.toDouble()).toFloat().coerceIn(0f, 1f)
    return instantaneousSpeedAtNormalizedSource(u, baseSpeed, speedCurve)
  }

  fun averageEffectiveSpeed(
    sourceSpanMs: Long,
    baseSpeed: Float = 1.0f,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Float {
    if (!hasVariableCurve(speedCurve) || sourceSpanMs <= 0L) return clampSpeed(baseSpeed)
    val rendered = sourceOffsetMsToTimelineMsDouble(sourceSpanMs.toDouble(), sourceSpanMs, baseSpeed, speedCurve)
    if (rendered <= 0.0) return clampSpeed(baseSpeed)
    return (sourceSpanMs.toDouble() / rendered).toFloat().coerceIn(MIN_SPEED, MAX_SPEED)
  }

  /**
   * Slices a [speedCurve] to a sub-range `[subSourceStartMs, subSourceEndMs]` of `[0..oldSourceSpanMs]`,
   * preserving an empty list when no variable curve is active.
   */
  fun sliceCurveForSourceRange(
    speedCurve: List<SpeedCurvePoint>,
    baseSpeed: Float,
    oldSourceSpanMs: Long,
    subSourceStartMs: Long,
    subSourceEndMs: Long,
  ): List<SpeedCurvePoint> {
    if (!hasVariableCurve(speedCurve)) return emptyList()
    if (oldSourceSpanMs <= 0L || subSourceEndMs <= subSourceStartMs) return emptyList()
    val uStart = (subSourceStartMs.toDouble() / oldSourceSpanMs.toDouble()).toFloat().coerceIn(0f, 1f)
    val uEnd = (subSourceEndMs.toDouble() / oldSourceSpanMs.toDouble()).toFloat().coerceIn(uStart, 1f)
    val span = uEnd - uStart
    if (span <= 1e-5f) {
      val s = instantaneousSpeedAtNormalizedSource(uStart, baseSpeed, speedCurve)
      return listOf(SpeedCurvePoint(0f, s), SpeedCurvePoint(1f, s))
    }

    val normalized = normalizeCurve(speedCurve, baseSpeed)
    val out = mutableListOf<SpeedCurvePoint>()
    out += SpeedCurvePoint(0f, instantaneousSpeedAtNormalizedSource(uStart, baseSpeed, normalized))
    for (pt in normalized) {
      if (pt.normalizedTime > uStart + 1e-4f && pt.normalizedTime < uEnd - 1e-4f) {
        val remappedU = ((pt.normalizedTime - uStart) / span).coerceIn(0f, 1f)
        out += SpeedCurvePoint(remappedU, pt.speed)
      }
    }
    out += SpeedCurvePoint(1f, instantaneousSpeedAtNormalizedSource(uEnd, baseSpeed, normalized))
    return normalizeCurve(out, baseSpeed)
  }

  /**
   * Builds a smooth cinematic ramp curve preset (e.g. "Pulse", "Slow-Fast-Slow").
   */
  fun presetRampFastMiddle(peakSpeed: Float = 2.0f, edgeSpeed: Float = 0.75f): List<SpeedCurvePoint> {
    val edge = clampSpeed(edgeSpeed)
    val peak = clampSpeed(peakSpeed)
    return listOf(
      SpeedCurvePoint(0.0f, edge),
      SpeedCurvePoint(0.35f, peak),
      SpeedCurvePoint(0.65f, peak),
      SpeedCurvePoint(1.0f, edge),
    )
  }
}
