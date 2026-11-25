package com.example.core.media.model

import com.example.core.model.AudioCodec
import com.example.core.model.ExportConfig
import com.example.core.model.VideoCodec

/**
 * Preview playback quality profiles intentionally decoupled from final export settings.
 * Lowering preview quality keeps scrubbing fluid on mid-range hardware without altering export.
 */
enum class PreviewQualityProfile(
  val label: String,
  val targetHeight: Int,
  val maxFps: Int,
  val proxyBitrateBps: Int,
) {
  DRAFT_360P(label = "360p Rápida", targetHeight = 360, maxFps = 24, proxyBitrateBps = 1_500_000),
  BALANCED_540P(label = "540p Fluida", targetHeight = 540, maxFps = 30, proxyBitrateBps = 3_000_000),
  HIGH_720P(label = "720p Nítida", targetHeight = 720, maxFps = 30, proxyBitrateBps = 5_500_000),
}

/**
 * User-configurable preview rendering preferences.
 */
data class RenderSettings(
  val previewQuality: PreviewQualityProfile = PreviewQualityProfile.BALANCED_540P,
  val useProxiesForPreview: Boolean = true,
  val proxyTargetHeight: Int = 540,
)

/**
 * Distinguishes whether a Timeline is being compiled for real-time preview or final local export.
 * Export ALWAYS uses original full-resolution assets, even if proxies are enabled for preview.
 */
sealed interface MediaRenderTarget {
  data class Preview(
    val profile: PreviewQualityProfile,
    val useProxies: Boolean,
  ) : MediaRenderTarget

  data class Export(
    val config: ExportConfig,
  ) : MediaRenderTarget
}

/**
 * Curated local export presets independent from [PreviewQualityProfile].
 */
enum class ExportPreset(
  val displayName: String,
  val config: ExportConfig,
) {
  SOCIAL_720P_H264(
    displayName = "720p H.264 (Ligero)",
    config =
      ExportConfig(
        presetName = "720p_30fps_h264",
        width = 720,
        height = 1280,
        fps = 30,
        videoCodec = VideoCodec.H264,
        videoBitrateKbps = 6_000,
        audioCodec = AudioCodec.AAC,
        audioBitrateKbps = 160,
      ),
  ),
  HD_1080P_H264(
    displayName = "1080p H.264 (Estándar)",
    config =
      ExportConfig(
        presetName = "1080p_30fps_h264",
        width = 1080,
        height = 1920,
        fps = 30,
        videoCodec = VideoCodec.H264,
        videoBitrateKbps = 12_000,
        audioCodec = AudioCodec.AAC,
        audioBitrateKbps = 192,
      ),
  ),
  HD_1080P_HEVC(
    displayName = "1080p HEVC (Alta eficiencia)",
    config =
      ExportConfig(
        presetName = "1080p_60fps_hevc",
        width = 1080,
        height = 1920,
        fps = 60,
        videoCodec = VideoCodec.HEVC,
        videoBitrateKbps = 16_000,
        audioCodec = AudioCodec.AAC,
        audioBitrateKbps = 256,
      ),
  ),
}
