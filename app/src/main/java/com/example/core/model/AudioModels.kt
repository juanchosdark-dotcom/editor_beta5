package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Automatic side-chain ducking configuration: when active audio/dialogue clips play on
 * [triggerTrackId], volume on [targetTrackId] is attenuated smoothly by [attenuationFactor].
 */
@JsonClass(generateAdapter = true)
data class AudioDuckingConfig(
  val enabled: Boolean = false,
  val triggerTrackId: String = "t_vid",
  val targetTrackId: String = "t_aud",
  val attenuationFactor: Float = 0.25f,
  val rampMs: Long = 200L,
)

/**
 * Computed timeline interval during which an audio clip should be ducked.
 */
data class DuckingWindow(
  val startMs: Long,
  val endMs: Long,
  val attenuationFactor: Float,
  val rampMs: Long,
)

/**
 * Detected silent pause inside an audio stream or media file.
 */
data class SilenceSegment(
  val startMs: Long,
  val endMs: Long,
  val averageDb: Float,
) {
  val durationMs: Long
    get() = (endMs - startMs).coerceAtLeast(0L)
}
