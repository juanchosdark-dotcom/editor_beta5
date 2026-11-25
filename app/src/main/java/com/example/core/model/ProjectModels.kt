package com.example.core.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppliedAiAdjustment(
  val adjustmentId: String,
  val suggestionId: String,
  val category: String,
  val title: String,
  val rationale: String,
  val targetClipId: String? = null,
  val parametersSummary: String = "",
  val appliedAtIso: String,
)

/**
 * Root portable project document persisted as `<projectId>.json` inside `filesDir/projects/`.
 */
@JsonClass(generateAdapter = true)
data class ProjectDocument(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val projectId: String,
  val name: String,
  val createdAtIso: String,
  val updatedAtIso: String,
  val portability: PortabilityConfig = PortabilityConfig(),
  val canvas: CanvasSpec = CanvasSpec(),
  val assets: List<MediaAsset> = emptyList(),
  val timeline: TimelineState = TimelineState(),
  val activeSequenceId: String = "seq_main",
  val sequences: List<TimelineSequence> = emptyList(),
  val snapshots: List<TimelineSnapshot> = emptyList(),
  val savedSubtitleStyles: List<SubtitleStylePreset> = emptyList(),
  val savedLutPresets: List<LutPreset> = emptyList(),
  val exportConfig: ExportConfig = ExportConfig(),
  val appliedAiAdjustments: List<AppliedAiAdjustment> = emptyList(),
  val selectedClipId: String? = null,
  val selectedExportPresetName: String = "HD_1080P_H264",
) {
  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}

/**
 * Named alternate timeline sequence produced by duplicating an existing timeline or snapshot.
 */
@JsonClass(generateAdapter = true)
data class TimelineSequence(
  val sequenceId: String,
  val name: String,
  val createdAtIso: String,
  val timeline: TimelineState,
)

/**
 * Lightweight immutable checkpoint of a [TimelineState] for instant A/B restore or branching.
 */
@JsonClass(generateAdapter = true)
data class TimelineSnapshot(
  val snapshotId: String,
  val name: String,
  val createdAtIso: String,
  val note: String = "",
  val timeline: TimelineState,
)

@JsonClass(generateAdapter = true)
data class PortabilityConfig(
  val rootMode: PortabilityRootMode = PortabilityRootMode.BUNDLED_RELATIVE,
  val mediaDir: String = "media",
  val proxyDir: String = "proxies",
  val cacheDir: String = ".cache",
)

enum class PortabilityRootMode {
  /** Media files are copied inside `<projectRoot>/media/` and referenced via relative paths. */
  BUNDLED_RELATIVE,

  /** Media files are linked externally via SAF URIs while retaining relative-path fallback. */
  EXTERNAL_LINKED,
}

@JsonClass(generateAdapter = true)
data class CanvasSpec(
  val width: Int = 1080,
  val height: Int = 1920,
  val fps: Int = 30,
  val aspectRatioLabel: String = "9:16",
  val audioSampleRateHz: Int = 48_000,
  val backgroundColorHex: String = "#000000",
)

@JsonClass(generateAdapter = true)
data class MediaAsset(
  val assetId: String,
  val type: MediaType,
  val displayName: String,
  val relativePath: String,
  val externalSourceUri: String? = null,
  val proxyRelativePath: String? = null,
  val fingerprint: MediaFingerprint,
  val relinkState: RelinkState = RelinkState(),
)

enum class MediaType {
  VIDEO,
  AUDIO,
  IMAGE,
}

@JsonClass(generateAdapter = true)
data class MediaFingerprint(
  val byteSize: Long,
  val durationMs: Long,
  val partialSha256: String,
  val mimeType: String,
  val width: Int? = null,
  val height: Int? = null,
  val fps: Float? = null,
  val rotationDegrees: Int = 0,
  val hasAudio: Boolean = false,
  val colorStandard: Int? = null,
  val colorTransfer: Int? = null,
  val isHdr: Boolean = false,
  val isHighResolution: Boolean = false,
) {
  fun orientedResolution(): Pair<Int?, Int?> {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) {
      height to width
    } else {
      width to height
    }
  }
}

@JsonClass(generateAdapter = true)
data class RelinkState(
  val status: RelinkStatus = RelinkStatus.ONLINE,
  val lastVerifiedAtIso: String? = null,
)

enum class RelinkStatus {
  ONLINE,
  PROXY_ONLY,
  MISSING,
}

@JsonClass(generateAdapter = true)
data class ExportConfig(
  val presetName: String = "1080p_30fps",
  val width: Int = 1080,
  val height: Int = 1920,
  val fps: Int = 30,
  val videoCodec: VideoCodec = VideoCodec.H264,
  val videoBitrateKbps: Int = 12_000,
  val audioCodec: AudioCodec = AudioCodec.AAC,
  val audioBitrateKbps: Int = 192,
)

enum class VideoCodec {
  H264,
  HEVC,
}

enum class AudioCodec {
  AAC,
  OPUS,
}
