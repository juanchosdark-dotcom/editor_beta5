package com.example.core.ai.api

import com.example.core.model.ClipKeyframe
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import java.io.File

/**
 * Hardware memory and thermal budget profile used to bound local AI analysis workloads.
 */
enum class DeviceMemoryTier(val label: String) {
  CONSERVATIVE("Conservador (RAM limitada)"),
  BALANCED("Balanceado (Gama media)"),
  PERFORMANCE("Alto rendimiento"),
}

data class DeviceProfile(
  val tier: DeviceMemoryTier = DeviceMemoryTier.BALANCED,
  val totalRamMb: Long = 4_096L,
  val availableRamMb: Long = 1_536L,
  val isLowRamDevice: Boolean = false,
  val thermalTier: ThermalTier = ThermalTier.NORMAL,
  val maxSampledFrames: Int = 5,
  val maxAnalysisWidthPx: Int = 360,
  val maxSuggestedFixes: Int = 4,
  val allowFrameSampling: Boolean = true,
  val summaryLabel: String = "Balanceado · 5 frames @ 360px",
)

enum class EditFixCategory(val label: String) {
  COLOR_LIGHT("Luz y Color"),
  CUT_MARKERS("Marcas de corte"),
  KEYFRAME_MOTION("Keyframes de escala/pos"),
  AUDIO_FADE("Suavizado de audio"),
}

enum class EditFixStatus {
  PENDING,
  APPLIED,
  DISMISSED,
}

sealed interface EditFixPayload {
  data class ColorLightFix(
    val targetClipId: String,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val spanStartMs: Long,
    val spanEndMs: Long,
  ) : EditFixPayload

  data class CutMarkersFix(
    val positionsMs: List<Long>,
    val labels: List<String>,
  ) : EditFixPayload

  data class InitialKeyframesFix(
    val targetClipId: String,
    val keyframes: List<ClipKeyframe>,
  ) : EditFixPayload

  data class AudioFadeFix(
    val targetClipId: String,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val volume: Float,
  ) : EditFixPayload
}

data class LocalEditSuggestion(
  val suggestionId: String,
  val category: EditFixCategory,
  val title: String,
  val rationale: String,
  val metricsEvidence: String,
  val targetClipId: String?,
  val status: EditFixStatus = EditFixStatus.PENDING,
  val payload: EditFixPayload,
)

data class ClipLumaMetrics(
  val sampledFrames: Int,
  val averageLuma: Float,
  val contrastSpread: Float,
  val darkFrameRatio: Float,
  val highlightsClippedRatio: Float,
)

data class ProjectEditMetrics(
  val totalClips: Int,
  val videoClipCount: Int,
  val timelineDurationMs: Long,
  val markerCount: Int,
  val hasAdjustmentLayer: Boolean,
  val selectedClipLabel: String,
  val selectedClipDurationMs: Long,
  val selectedClipKeyframeCount: Int,
  val selectedClipHasFades: Boolean,
  val selectedClipHasAudio: Boolean,
)

data class LocalEditAnalysisReport(
  val deviceProfile: DeviceProfile,
  val clipMetrics: ClipLumaMetrics?,
  val projectMetrics: ProjectEditMetrics,
  val suggestions: List<LocalEditSuggestion>,
  val memoryGuardNote: String,
)

interface LocalEditAssistEngine {
  suspend fun analyzeForEdits(
    timeline: TimelineState,
    selectedClip: TimelineClip?,
    asset: MediaAsset?,
    projectRootDir: File,
    settings: LocalAiSettings,
  ): Result<LocalEditAnalysisReport>
}
