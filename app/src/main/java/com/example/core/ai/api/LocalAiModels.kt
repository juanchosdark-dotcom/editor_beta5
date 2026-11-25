package com.example.core.ai.api

import com.example.core.model.SilenceSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Thermal severity tiers mapped from Android [android.os.PowerManager] thermal status.
 */
enum class ThermalTier {
  NORMAL,
  MODERATE,
  SEVERE,
  CRITICAL,
}

/**
 * Lightweight vs. higher-accuracy local inference profiles.
 */
enum class LocalModelProfile(val label: String, val maxConcurrentJobs: Int) {
  LIGHTWEIGHT_DSP("Ligero (DSP + On-Device)", maxConcurrentJobs = 1),
  BALANCED_LOCAL("Balanceado (Whisper Tiny / On-Device)", maxConcurrentJobs = 1),
}

/**
 * User settings for the optional, fully disableable Local AI module.
 * Disabled by default (`enabled = false`) so zero background resources are consumed unless opted-in.
 */
data class LocalAiSettings(
  val enabled: Boolean = false,
  val allowOnBatterySaver: Boolean = false,
  val maxAllowedThermalTier: ThermalTier = ThermalTier.MODERATE,
  val profile: LocalModelProfile = LocalModelProfile.LIGHTWEIGHT_DSP,
)

/**
 * Real-time device telemetry snapshot used to throttle or pause local AI workloads safely.
 */
data class ResourceSnapshot(
  val thermalTier: ThermalTier = ThermalTier.NORMAL,
  val availableRamMb: Long = 0L,
  val isLowMemory: Boolean = false,
  val batteryPercent: Int = 100,
  val isPowerSaveMode: Boolean = false,
  val canRunInference: Boolean = true,
  val blockReason: String? = null,
)

/**
 * Rhythmic onset peak detected in local audio for automatic marker placement.
 */
data class BeatMarkerCandidate(
  val positionMs: Long,
  val strength: Float,
)

data class LocalAudioAssistResult(
  val silences: List<SilenceSegment>,
  val beats: List<BeatMarkerCandidate>,
)

data class LocalTranscriptSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
  val confidence: Float,
)

sealed interface LocalTranscriptionEvent {
  data object Listening : LocalTranscriptionEvent
  data class Partial(val text: String) : LocalTranscriptionEvent
  data class Completed(val segment: LocalTranscriptSegment) : LocalTranscriptionEvent
  data class UnavailableOrError(val reason: String) : LocalTranscriptionEvent
}

/**
 * Contract for thermal-aware device resource monitoring.
 */
interface ResourceGuard {
  val snapshot: StateFlow<ResourceSnapshot>
  val deviceProfile: StateFlow<DeviceProfile>
  fun refreshNow(settings: LocalAiSettings): ResourceSnapshot
  fun resolveDeviceProfile(settings: LocalAiSettings): DeviceProfile
  fun startMonitoring(settings: LocalAiSettings)
  fun stopMonitoring()
}

/**
 * Contract for local, offline audio rhythm & silence assistance.
 */
interface LocalAudioAssistEngine {
  suspend fun analyzeMediaFile(
    mediaFile: File,
    settings: LocalAiSettings,
    silenceThresholdDb: Float = -36f,
    minSilenceMs: Long = 450L,
    beatSensitivity: Float = 1.65f,
  ): Result<LocalAudioAssistResult>
}

/**
 * Contract for optional on-device speech-to-subtitle transcription.
 */
interface LocalTranscriptionBackend {
  fun isOfflineRecognitionSupported(): Boolean
  fun startOfflineDictation(languageTag: String = "es-ES"): Flow<LocalTranscriptionEvent>
}
