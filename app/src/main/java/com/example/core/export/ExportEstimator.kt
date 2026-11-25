package com.example.core.export

import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.model.ExportConfig
import com.example.core.model.VideoCodec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure technical estimate for a planned local MP4 export given preset, span duration,
 * device capability profile, and active effects complexity.
 */
data class ExportEstimate(
  val rangeDurationMs: Long,
  val estimatedSizeBytes: Long,
  val estimatedRenderTimeSec: Int,
)

object ExportEstimator {

  fun estimate(
    config: ExportConfig,
    rangeDurationMs: Long,
    deviceProfile: DeviceProfile,
    activeEffectsCount: Int = 0,
  ): ExportEstimate {
    if (rangeDurationMs <= 0L) {
      return ExportEstimate(
        rangeDurationMs = 0L,
        estimatedSizeBytes = 0L,
        estimatedRenderTimeSec = 0,
      )
    }

    val durationSec = rangeDurationMs / 1000.0
    val totalBitrateBps = (config.videoBitrateKbps + config.audioBitrateKbps).coerceAtLeast(256) * 1_000.0
    // Add ~1.5% MP4 container & muxing overhead
    val estimatedBytes = ((totalBitrateBps / 8.0) * durationSec * 1.015).toLong().coerceAtLeast(1_024L)

    val pixelFactor = (config.width.toDouble() * config.height.toDouble()) / (1080.0 * 1920.0)
    val fpsFactor = config.fps.toDouble() / 30.0
    val codecFactor =
      when (config.videoCodec) {
        VideoCodec.H264 -> 1.0
        VideoCodec.HEVC -> 1.35
      }
    val deviceFactor =
      when (deviceProfile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 1.55
        DeviceMemoryTier.BALANCED -> 1.05
        DeviceMemoryTier.PERFORMANCE -> 0.72
      }
    val effectsFactor = 1.0 + activeEffectsCount.coerceIn(0, 8) * 0.08

    val rawSeconds =
      durationSec *
        max(0.45, pixelFactor) *
        max(0.8, fpsFactor) *
        codecFactor *
        deviceFactor *
        effectsFactor *
        0.85

    val roundedSeconds = ceil(rawSeconds).roundToInt().coerceAtLeast(1)

    return ExportEstimate(
      rangeDurationMs = rangeDurationMs,
      estimatedSizeBytes = estimatedBytes,
      estimatedRenderTimeSec = roundedSeconds,
    )
  }

  fun formatEtaSeconds(seconds: Int): String {
    if (seconds <= 0) return "—"
    if (seconds < 60) return "~$seconds s"
    val mins = seconds / 60
    val rem = seconds % 60
    return if (rem == 0) "~${mins}m" else "~${mins}m ${rem}s"
  }
}
