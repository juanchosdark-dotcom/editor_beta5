package com.example.core.subtitles

import com.example.core.model.ClipKind
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.SubtitleStylePreset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import java.util.Locale
import java.util.UUID

data class SubtitleBatchEdit(
  val clipId: String,
  val text: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val isEnabled: Boolean = true,
)

/**
 * Batch subtitle parser, bulk editor, SRT exporter, and style-preset applicator.
 */
object SubtitleBatchEngine {

  private val srtTimecodeRegex =
    Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

  /**
   * Parses either `.srt` timecoded blocks OR plain multi-line script text (one subtitle per
   * non-empty line, distributed sequentially from [startAtMs] using [defaultBlockDurationMs]).
   */
  fun parseScriptOrSrt(
    rawInput: String,
    trackId: String,
    style: SubtitleStylePreset,
    startAtMs: Long = 0L,
    defaultBlockDurationMs: Long = 2_500L,
    idGenerator: () -> String = { "sub_${UUID.randomUUID().toString().take(8)}" },
  ): List<TimelineClip> {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return emptyList()

    if (srtTimecodeRegex.containsMatchIn(trimmed)) {
      return parseSrtBlocks(trimmed, trackId, style, idGenerator)
    }

    var cursorMs = startAtMs.coerceAtLeast(0L)
    return trimmed
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { line ->
        val start = cursorMs
        val end = start + defaultBlockDurationMs.coerceAtLeast(300L)
        cursorMs = end + 100L
        TimelineClip(
          clipId = idGenerator(),
          trackId = trackId,
          kind = ClipKind.SUBTITLE,
          label = line.take(28),
          timelineStartMs = start,
          timelineEndMs = end,
          subtitleConfig = style.toSubtitleConfig(text = line),
        )
      }
  }

  private fun parseSrtBlocks(
    rawSrt: String,
    trackId: String,
    style: SubtitleStylePreset,
    idGenerator: () -> String,
  ): List<TimelineClip> {
    val blocks = rawSrt.split(Regex("""\r?\n\r?\n+"""))
    return blocks.mapNotNull { block ->
      val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
      val timeLineIndex = lines.indexOfFirst { srtTimecodeRegex.containsMatchIn(it) }
      if (timeLineIndex < 0) return@mapNotNull null

      val match = srtTimecodeRegex.find(lines[timeLineIndex]) ?: return@mapNotNull null
      val startMs = parseSrtTimestamp(match.groupValues, 1)
      val endMs = parseSrtTimestamp(match.groupValues, 5)
      val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
      if (text.isEmpty() || endMs <= startMs) return@mapNotNull null

      TimelineClip(
        clipId = idGenerator(),
        trackId = trackId,
        kind = ClipKind.SUBTITLE,
        label = text.lines().first().take(28),
        timelineStartMs = startMs,
        timelineEndMs = endMs,
        subtitleConfig = style.toSubtitleConfig(text = text),
      )
    }
  }

  private fun parseSrtTimestamp(groups: List<String>, offset: Int): Long {
    val h = groups[offset].toLong()
    val m = groups[offset + 1].toLong()
    val s = groups[offset + 2].toLong()
    val ms = groups[offset + 3].toLong()
    return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
  }

  /**
   * Replaces all subtitle clips on [trackId] with newly parsed/edited blocks in one atomic step.
   */
  fun replaceTrackSubtitles(
    state: TimelineState,
    trackId: String,
    newSubtitleClips: List<TimelineClip>,
  ): TimelineState {
    val nonTrackSubtitles =
      state.clips.filterNot { it.trackId == trackId && it.kind == ClipKind.SUBTITLE }
    return state.copy(
      clips = (nonTrackSubtitles + newSubtitleClips).sortedBy { it.timelineStartMs },
    )
  }

  /**
   * Applies [edits] across multiple existing subtitle clips in a single batch operation.
   */
  fun applyBatchEdits(
    state: TimelineState,
    edits: List<SubtitleBatchEdit>,
  ): TimelineState {
    val editById = edits.associateBy { it.clipId }
    val updated =
      state.clips.map { clip ->
        val edit = editById[clip.clipId]
        if (edit != null && clip.kind == ClipKind.SUBTITLE && edit.timelineEndMs > edit.timelineStartMs) {
          val currentCfg = clip.subtitleConfig ?: SubtitleClipConfig(text = edit.text)
          clip.copy(
            label = edit.text.lines().firstOrNull()?.take(28) ?: clip.label,
            timelineStartMs = edit.timelineStartMs.coerceAtLeast(0L),
            timelineEndMs = edit.timelineEndMs,
            isEnabled = edit.isEnabled,
            subtitleConfig = currentCfg.copy(text = edit.text),
          )
        } else {
          clip
        }
      }
    return state.copy(clips = updated.sortedBy { it.timelineStartMs })
  }

  /**
   * Applies a saved [SubtitleStylePreset] to every subtitle clip on [trackId] (or all subtitle
   * clips if [trackId] is null) while preserving each clip's text content.
   */
  fun applyStylePreset(
    state: TimelineState,
    preset: SubtitleStylePreset,
    trackId: String? = null,
  ): TimelineState {
    val updated =
      state.clips.map { clip ->
        val matchesTrack = trackId == null || clip.trackId == trackId
        if (clip.kind == ClipKind.SUBTITLE && matchesTrack) {
          val text = clip.subtitleConfig?.text ?: clip.label
          clip.copy(subtitleConfig = preset.toSubtitleConfig(text = text))
        } else {
          clip
        }
      }
    return state.copy(clips = updated)
  }

  /**
   * Exports enabled subtitle clips on [trackId] into standard `.srt` format.
   */
  fun exportToSrt(state: TimelineState, trackId: String): String {
    val subtitles =
      state.clips
        .filter { it.trackId == trackId && it.kind == ClipKind.SUBTITLE && it.isEnabled }
        .sortedBy { it.timelineStartMs }

    return subtitles.mapIndexed { index, clip ->
      val startStr = formatSrtTimestamp(clip.timelineStartMs)
      val endStr = formatSrtTimestamp(clip.timelineEndMs)
      val text = clip.subtitleConfig?.text ?: clip.label
      "${index + 1}\n$startStr --> $endStr\n$text"
    }.joinToString("\n\n")
  }

  private fun formatSrtTimestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe % 3_600_000L) / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
  }

  /**
   * Splits [text] along word boundaries according to [ratio] (`0.0..1.0`) when multiple words
   * exist; preserves single-word lines intact on both halves.
   */
  fun splitTextProportionally(text: String, ratio: Float): Pair<String, String> {
    val normalized = text.trim()
    if (normalized.isEmpty()) return "" to ""
    val words = normalized.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.size <= 1) return normalized to normalized
    val splitIdx = (words.size * ratio.coerceIn(0.15f, 0.85f)).toInt().coerceIn(1, words.size - 1)
    val left = words.subList(0, splitIdx).joinToString(" ")
    val right = words.subList(splitIdx, words.size).joinToString(" ")
    return left to right
  }

  /**
   * Automatically generates timed editable subtitle blocks from spoken/active timeline intervals
   * (minus detected silence windows) over [spanStartMs..spanEndMs] using [baseText] sentences or
   * smart voice cue labels when no transcript is supplied yet.
   */
  fun generateAutoSubtitlesForIntervals(
    trackId: String,
    intervals: List<Pair<Long, Long>>,
    style: SubtitleStylePreset,
    seedTranscript: String = "",
    idGenerator: () -> String = { "sub_${UUID.randomUUID().toString().take(8)}" },
  ): List<TimelineClip> {
    val cleanIntervals = intervals.filter { it.second - it.first >= 250L }
    if (cleanIntervals.isEmpty()) return emptyList()
    val sentences =
      seedTranscript
        .split(Regex("""(?<=[.!?])\s+|\r?\n+"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return cleanIntervals.mapIndexed { index, (startMs, endMs) ->
      val text =
        sentences.getOrNull(index)
          ?: if (cleanIntervals.size == 1) {
            "Subtítulo automático 1"
          } else {
            "Diálogo ${index + 1}"
          }
      TimelineClip(
        clipId = idGenerator(),
        trackId = trackId,
        kind = ClipKind.SUBTITLE,
        label = text.take(28),
        timelineStartMs = startMs,
        timelineEndMs = endMs,
        subtitleConfig = style.toSubtitleConfig(text),
      )
    }
  }
}
