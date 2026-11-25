package com.example.core.timeline

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ClipKind
import com.example.core.model.ClipTransition
import com.example.core.model.EditModeOptions
import com.example.core.model.MarkerColor
import com.example.core.model.RippleScope
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.TransitionType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {

  private var nextId = 1
  private fun createEngine() = TimelineEngine(idGenerator = { "gen_${nextId++}" })

  private fun baseState(
    rippleEnabled: Boolean = false,
    rippleScope: RippleScope = RippleScope.SAME_TRACK,
  ): TimelineState {
    return TimelineState(
      playheadMs = 2_000L,
      tracks =
        listOf(
          Track("t_vid", TrackType.VIDEO, 0, "Video 1"),
          Track("t_aud", TrackType.AUDIO, 1, "Audio 1"),
          Track("t_sub", TrackType.SUBTITLE, 2, "Subtitles"),
          Track("t_adj", TrackType.ADJUSTMENT, 3, "Adjustment"),
        ),
      clips =
        listOf(
          TimelineClip(
            clipId = "v1",
            trackId = "t_vid",
            kind = ClipKind.VIDEO,
            label = "Clip 1",
            timelineStartMs = 0L,
            timelineEndMs = 4_000L,
            sourceInMs = 1_000L,
            sourceOutMs = 9_000L,
            speed = 2.0f,
            videoConfig =
              VideoClipConfig(
                transitionIn = ClipTransition(TransitionType.FADE_BLACK, 300L),
                transitionOut = ClipTransition(TransitionType.CROSS_DISSOLVE, 300L),
              ),
          ),
          TimelineClip(
            clipId = "v2",
            trackId = "t_vid",
            kind = ClipKind.VIDEO,
            label = "Clip 2",
            timelineStartMs = 4_000L,
            timelineEndMs = 7_000L,
            sourceInMs = 0L,
            sourceOutMs = 3_000L,
          ),
          TimelineClip(
            clipId = "a1",
            trackId = "t_aud",
            kind = ClipKind.AUDIO,
            label = "Music",
            timelineStartMs = 4_500L,
            timelineEndMs = 8_500L,
            sourceInMs = 0L,
            sourceOutMs = 4_000L,
          ),
          TimelineClip(
            clipId = "s1",
            trackId = "t_sub",
            kind = ClipKind.SUBTITLE,
            label = "Sub",
            timelineStartMs = 1_000L,
            timelineEndMs = 3_000L,
            subtitleConfig = SubtitleClipConfig(text = "Hello"),
          ),
          TimelineClip(
            clipId = "adj1",
            trackId = "t_adj",
            kind = ClipKind.ADJUSTMENT,
            label = "Warmth",
            timelineStartMs = 0L,
            timelineEndMs = 5_000L,
            adjustmentConfig = AdjustmentClipConfig(temperature = 0.25f),
          ),
        ),
      markers =
        listOf(
          TimelineMarker("m1", 1_500L, "Beat 1", MarkerColor.AMBER),
          TimelineMarker("m2", 5_000L, "Beat 2", MarkerColor.CYAN),
        ),
      editOptions = EditModeOptions(rippleEnabled = rippleEnabled, rippleScope = rippleScope),
    )
  }

  @Test
  fun splitAtPlayhead_scalesSourceInOutBySpeedAndSplitsAllTrackKinds() {
    val engine = createEngine()
    val state = baseState()

    val result = engine.splitAtPlayhead(state, targetClipId = "v1")
    assertTrue(result is TimelineOperationResult.Success)
    val next = (result as TimelineOperationResult.Success).newState

    val left = next.clips.first { it.clipId == "v1" }
    val right = next.clips.first { it.clipId == "gen_1" }

    // 2000ms timeline split at speed 2.0 => +4000ms source offset from sourceIn=1000 => 5000ms
    assertEquals(0L, left.timelineStartMs)
    assertEquals(2_000L, left.timelineEndMs)
    assertEquals(1_000L, left.sourceInMs)
    assertEquals(5_000L, left.sourceOutMs)
    assertNull(left.videoConfig?.transitionOut)

    assertEquals(2_000L, right.timelineStartMs)
    assertEquals(4_000L, right.timelineEndMs)
    assertEquals(5_000L, right.sourceInMs)
    assertEquals(9_000L, right.sourceOutMs)
    assertNull(right.videoConfig?.transitionIn)

    // Subtitle split when targeting all tracks at playhead
    val subResult = engine.splitAtPlayhead(next, targetClipId = "s1")
    assertTrue(subResult is TimelineOperationResult.Success)
  }

  @Test
  fun deleteClip_withRippleSameTrack_shiftsOnlySameTrackClips() {
    val engine = createEngine()
    val state = baseState(rippleEnabled = true, rippleScope = RippleScope.SAME_TRACK)

    val result = engine.deleteClip(state, "v1") as TimelineOperationResult.Success
    val v2 = result.newState.clips.first { it.clipId == "v2" }
    val a1 = result.newState.clips.first { it.clipId == "a1" }

    // v1 had 4000ms duration -> v2 shifts from 4000..7000 to 0..3000
    assertEquals(0L, v2.timelineStartMs)
    assertEquals(3_000L, v2.timelineEndMs)
    // a1 is on audio track -> untouched
    assertEquals(4_500L, a1.timelineStartMs)
  }

  @Test
  fun deleteClip_withRippleAllTracks_shiftsUnlockedTracksAndMarkers() {
    val engine = createEngine()
    val state = baseState(rippleEnabled = true, rippleScope = RippleScope.ALL_TRACKS)

    val result = engine.deleteClip(state, "v1") as TimelineOperationResult.Success
    val a1 = result.newState.clips.first { it.clipId == "a1" }
    val m2 = result.newState.markers.first { it.markerId == "m2" }

    // v1 ended at 4000ms -> a1 (4500..8500) shifts left by 4000ms to 500..4500
    assertEquals(500L, a1.timelineStartMs)
    assertEquals(4_500L, a1.timelineEndMs)
    // m2 (5000ms) shifts left by 4000ms to 1000ms
    assertEquals(1_000L, m2.positionMs)
  }

  @Test
  fun toggleClipEnabled_preservesClipAndExcludesFromActiveLookup() {
    val engine = createEngine()
    val state = baseState()

    val disabled =
      (engine.setClipEnabled(state, "v1", enabled = false) as TimelineOperationResult.Success)
        .newState
    val clip = disabled.clips.first { it.clipId == "v1" }
    assertFalse(clip.isEnabled)
    assertTrue(disabled.activeClipsAt(2_000L).none { it.clipId == "v1" })
    assertTrue(disabled.activeClipsAt(2_000L).any { it.clipId == "adj1" })
  }

  @Test
  fun markers_addAndNavigatePreviousNext() {
    val engine = createEngine()
    val state = baseState() // playhead at 2000ms; markers at 1500ms, 5000ms

    assertEquals("m1", engine.findPreviousMarker(state)?.markerId)
    assertEquals("m2", engine.findNextMarker(state)?.markerId)

    val withNewMarker =
      engine.addMarker(state, positionMs = 3_200L, label = "Cue", color = MarkerColor.EMERALD)
    assertEquals(3, withNewMarker.markers.size)
    assertEquals("gen_1", engine.findNextMarker(withNewMarker)?.markerId)
  }

  @Test
  fun trimClipSourceRange_updatesMediaBoundsAndRipplesDownstream() {
    val engine = createEngine()
    val state = baseState(rippleEnabled = true, rippleScope = RippleScope.SAME_TRACK)

    // v1 has speed 2.0, timeline 0..4000, sourceIn=1000, sourceOut=9000 -> trim source to 2000..6000 (duration 2000ms)
    val result =
      engine.trimClipSourceRange(
        state = state,
        clipId = "v1",
        newSourceInMs = 2_000L,
        newSourceOutMs = 6_000L,
        maxSourceDurationMs = 9_000L,
      )
    assertTrue(result is TimelineOperationResult.Success)
    val next = (result as TimelineOperationResult.Success).newState
    val v1 = next.clips.first { it.clipId == "v1" }
    val v2 = next.clips.first { it.clipId == "v2" }

    assertEquals(0L, v1.timelineStartMs)
    assertEquals(2_000L, v1.timelineEndMs)
    assertEquals(2_000L, v1.sourceInMs)
    assertEquals(6_000L, v1.sourceOutMs)
    // v2 shifted left by 2000ms
    assertEquals(2_000L, v2.timelineStartMs)
    assertEquals(5_000L, v2.timelineEndMs)
  }

  @Test
  fun duplicateClip_avoidsOverlapWithoutRipple_shiftsDownstreamWithRipple_andRespectsTrackLock() {
    val engine = createEngine()
    // Without ripple: v1 (0..4000) followed by v2 (4000..7000) -> copy lands after v2 (7000..11000)
    val noRippleState = baseState(rippleEnabled = false)
    val noRippleResult = engine.duplicateClip(noRippleState, "v1") as TimelineOperationResult.Success
    val copyNoRipple = noRippleResult.newState.clips.first { it.clipId == "gen_1" }
    assertEquals(7_000L, copyNoRipple.timelineStartMs)
    assertEquals(11_000L, copyNoRipple.timelineEndMs)
    assertEquals("Clip 1 (copia)", copyNoRipple.label)
    assertFalse(copyNoRipple.isLocked)

    // With ripple: copy lands at 4000..8000 and pushes v2 to 8000..11000
    val rippleState = baseState(rippleEnabled = true, rippleScope = RippleScope.SAME_TRACK)
    val rippleResult = engine.duplicateClip(rippleState, "v1") as TimelineOperationResult.Success
    val copyRipple = rippleResult.newState.clips.first { it.clipId == "gen_2" }
    val shiftedV2 = rippleResult.newState.clips.first { it.clipId == "v2" }
    assertEquals(4_000L, copyRipple.timelineStartMs)
    assertEquals(8_000L, copyRipple.timelineEndMs)
    assertEquals(8_000L, shiftedV2.timelineStartMs)
    assertEquals(11_000L, shiftedV2.timelineEndMs)

    // Track lock blocks duplication
    val lockedState = engine.toggleTrackLocked(noRippleState, "t_vid")
    assertTrue(lockedState.tracks.first { it.trackId == "t_vid" }.isLocked)
    val rejected = engine.duplicateClip(lockedState, "v1")
    assertTrue(rejected is TimelineOperationResult.Rejected)
    assertEquals(RejectionReason.TRACK_LOCKED, (rejected as TimelineOperationResult.Rejected).reason)

    // Track visibility toggle flips isVisible flag
    val hiddenState = engine.toggleTrackVisible(noRippleState, "t_sub")
    assertFalse(hiddenState.tracks.first { it.trackId == "t_sub" }.isVisible)
  }

  @Test
  fun updateClipSpeed_scalesTimelineDurationAndRipplesDownstream() {
    val engine = createEngine()
    val state = baseState(rippleEnabled = true, rippleScope = RippleScope.SAME_TRACK)
    // v1 has timeline 0..4000ms, sourceSpan=8000ms, speed=2.0 -> change speed to 4.0 => duration 2000ms
    val result = engine.updateClipSpeed(state, "v1", newSpeed = 4.0f) as TimelineOperationResult.Success
    val v1 = result.newState.clips.first { it.clipId == "v1" }
    val v2 = result.newState.clips.first { it.clipId == "v2" }
    assertEquals(4.0f, v1.speed, 0.001f)
    assertEquals(2_000L, v1.durationMs)
    assertEquals(2_000L, v2.timelineStartMs)
    assertEquals(5_000L, v2.timelineEndMs)
  }

  @Test
  fun updateAudioPanAndSubtitleAndLutStrength_applyCorrectly() {
    val engine = createEngine()
    val state = baseState()

    val audioRes =
      engine.updateClipAudioPanAndMix(
        state = state,
        clipId = "a1",
        volume = 1.35f,
        pan = -0.6f,
        fadeInMs = 300L,
        fadeOutMs = 450L,
      ) as TimelineOperationResult.Success
    val a1Cfg = audioRes.newState.clips.first { it.clipId == "a1" }.audioConfig!!
    assertEquals(1.35f, a1Cfg.volume, 0.001f)
    assertEquals(-0.6f, a1Cfg.pan, 0.001f)
    assertEquals(300L, a1Cfg.fadeInMs)
    assertEquals(450L, a1Cfg.fadeOutMs)

    val subRes =
      engine.updateSubtitleClip(audioRes.newState, "s1") { cfg ->
        cfg.copy(text = "Escena 1", fontSizeSp = 28f)
      } as TimelineOperationResult.Success
    val s1 = subRes.newState.clips.first { it.clipId == "s1" }
    assertEquals("Escena 1", s1.label)
    assertEquals(28f, s1.subtitleConfig!!.fontSizeSp, 0.001f)

    // LUT strength blending check
    val halfStrength =
      com.example.core.model.BuiltInLutPresets.resolveEffectiveColor(
        brightness = 0f,
        contrast = 1f,
        saturation = 1f,
        exposure = 0f,
        temperature = 0f,
        vignette = 0f,
        lutPresetId = com.example.core.model.BuiltInLutPresets.TEAL_ORANGE.id,
        lutStrength = 0.5f,
      )
    assertEquals(1.10f, halfStrength.contrast, 0.001f)
    assertEquals(0.12f, halfStrength.temperature, 0.001f)
    assertTrue(halfStrength.hasColorModifications)
  }
}
