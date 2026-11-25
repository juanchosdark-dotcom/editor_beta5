package com.example.core.media.model

/**
 * Categorized media & decoder errors surfaced visibly in the UI without crashing.
 */
enum class MediaUiErrorKind {
  FILE_UNAVAILABLE,
  UNSUPPORTED_FORMAT_OR_DECODER,
  MEMORY_PRESSURE_ADJUSTED,
  HDR_COMPATIBILITY_FALLBACK,
  PLAYBACK_GENERAL,
}

data class MediaUiError(
  val kind: MediaUiErrorKind,
  val title: String,
  val detail: String,
  val isRecoverable: Boolean = true,
)

data class PlaybackDiagnosticNotice(
  val message: String,
  val isMemoryAdjusted: Boolean = false,
  val isHdrProtected: Boolean = false,
)
