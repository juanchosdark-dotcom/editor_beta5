package com.example.core.audio

import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.SilenceDetectorEngine
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.ClipKind
import com.example.core.model.SilenceSegment
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.subtitles.SubtitleBatchEdit
import com.example.core.subtitles.SubtitleBatchEngine
import com.example.core.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAndSubtitlesTest {

  @Test
  fun trackMixer_resolvesSoloAndMuteCorrectly() {
    val tracks =
      listOf(
        Track("t_vid", TrackType.VIDEO, 0, "Video", isMuted = false, isSolo = false),
        Track("t_aud", TrackType.AUDIO, 1, "Audio", isMuted = false, isSolo = true),
        Track("t_aud2", TrackType.AUDIO, 2, "Audio 2", isMuted = true, isSolo = true),
      )

    val audibility = AudioTrackMixer.resolveTrackAudibility(tracks)
    assertFalse(audibility.getValue("t_vid")) // suppressed because t_aud is Solo
    assertTrue(audibility.getValue("t_aud"))
    assertFalse(audibility.getValue("t_aud2")) // mute overrides solo
  }

  @Test
  fun duckingAndEnvelopeGain_computeFadesAndAttenuation() {
    val state =
      TimelineState(
        tracks =
          listOf(
            Track("t_vid", TrackType.VIDEO, 0, "Voice"),
            Track("t_aud", TrackType.AUDIO, 1, "Music"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v1",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "Voice",
              timelineStartMs = 2_000L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
            ),
            TimelineClip(
              clipId = "a1",
              trackId = "t_aud",
              kind = ClipKind.AUDIO,
              label = "Music",
              timelineStartMs = 0L,
              timelineEndMs = 8_000L,
              sourceInMs = 0L,
              sourceOutMs = 8_000L,
              audioConfig = AudioClipConfig(volume = 1.0f, fadeInMs = 500L, fadeOutMs = 500L),
            ),
          ),
        duckingConfig =
          AudioDuckingConfig(
            enabled = true,
            triggerTrackId = "t_vid",
            targetTrackId = "t_aud",
            attenuationFactor = 0.25f,
            rampMs = 200L,
          ),
      )

    val windows = AudioTrackMixer.computeDuckingWindows(state)
    assertEquals(1, windows.size)

    // Halfway through fade-in (250ms of 500ms) outside ducking window -> 0.5f
    val gainAtFadeIn =
      AudioTrackMixer.computeClipEnvelopeGainAt(
        clipStartOnTimelineMs = 0L,
        clipOffsetMs = 250L,
        clipDurationMs = 8_000L,
        baseVolume = 1.0f,
        fadeInMs = 500L,
        fadeOutMs = 500L,
        duckingWindows = windows,
      )
    assertEquals(0.5f, gainAtFadeIn, 0.01f)

    // Inside ducking window (3000ms) -> 0.25f
    val gainDuringVoice =
      AudioTrackMixer.computeClipEnvelopeGainAt(
        clipStartOnTimelineMs = 0L,
        clipOffsetMs = 3_000L,
        clipDurationMs = 8_000L,
        baseVolume = 1.0f,
        fadeInMs = 500L,
        fadeOutMs = 500L,
        duckingWindows = windows,
      )
    assertEquals(0.25f, gainDuringVoice, 0.01f)
  }

  @Test
  fun silenceDetector_clustersSilentWindowsAndRemovesPausesWithRipple() {
    val dbWindows =
      listOf(
        0L to -12f,
        200L to -42f,
        400L to -45f,
        600L to -44f,
        800L to -14f,
      )
    val silences =
      SilenceDetectorEngine.detectSilencesFromDbWindows(
        windowDbValues = dbWindows,
        windowDurationMs = 200L,
        thresholdDb = -36f,
        minSilenceDurationMs = 500L,
      )
    assertEquals(1, silences.size)
    assertEquals(SilenceSegment(200L, 800L, -43.666f).startMs, silences.first().startMs)
    assertEquals(800L, silences.first().endMs)

    val engine = TimelineEngine()
    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "Talk",
              timelineStartMs = 0L,
              timelineEndMs = 2_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
            ),
          ),
      )

    val cleaned =
      SilenceDetectorEngine.removeSilenceSegmentsFromClip(
        engine = engine,
        state = state,
        clipId = "c1",
        silenceSegments = silences,
        ripple = true,
      )
    assertEquals(2, cleaned.clips.size)
    // 600ms pause removed with ripple => total duration shrinks from 2000ms to 1400ms
    assertEquals(1_400L, cleaned.totalDurationMs)
  }

  @Test
  fun subtitleBatchEngine_parsesSrtAppliesStyleAndExportsSrt() {
    val srt =
      """
      1
      00:00:01,000 --> 00:00:02,500
      Primera línea

      2
      00:00:03,000 --> 00:00:05,000
      Segunda línea
      """.trimIndent()

    val parsed =
      SubtitleBatchEngine.parseScriptOrSrt(
        rawInput = srt,
        trackId = "t_sub",
        style = BuiltInSubtitleStyles.CLASSIC_CINEMA,
      )
    assertEquals(2, parsed.size)
    assertEquals(1_000L, parsed[0].timelineStartMs)
    assertEquals(2_500L, parsed[0].timelineEndMs)

    var state =
      TimelineState(
        tracks = listOf(Track("t_sub", TrackType.SUBTITLE, 0, "Subs")),
        clips = parsed,
      )

    state = SubtitleBatchEngine.applyStylePreset(state, BuiltInSubtitleStyles.HIGH_CONTRAST_AMBER)
    assertTrue(state.clips.all { it.subtitleConfig?.textColorHex == "#FBBF24" })

    state =
      SubtitleBatchEngine.applyBatchEdits(
        state,
        listOf(SubtitleBatchEdit(parsed[0].clipId, "Editada", 1_000L, 2_500L, isEnabled = true)),
      )
    val exported = SubtitleBatchEngine.exportToSrt(state, "t_sub")
    assertTrue(exported.contains("Editada"))
    assertTrue(exported.contains("00:00:01,000 --> 00:00:02,500"))
  }
}
