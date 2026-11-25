package com.example.core.timeline

import com.example.core.media.engine.CompiledCompositionResult
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.engine.TransitionVisualMath
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.ClipKind
import com.example.core.model.ClipTransition
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.ProjectDocument
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.SubtitleAnimationMath
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.TransitionType
import com.example.core.model.VideoClipConfig
import com.example.core.project.ProjectJsonSerializer
import com.example.core.subtitles.SubtitleBatchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FineEditingEngineAndCompilerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun subtitleEngine_splitMergeAndTimingWorkCleanly() {
    val engine = TimelineEngine()
    val state =
      TimelineState(
        tracks = listOf(Track("t_sub", TrackType.SUBTITLE, 0, "Subtítulos")),
        clips =
          listOf(
            TimelineClip(
              clipId = "s1",
              trackId = "t_sub",
              kind = ClipKind.SUBTITLE,
              label = "Hola mundo FrameLine demo",
              timelineStartMs = 1_000L,
              timelineEndMs = 5_000L,
              subtitleConfig =
                SubtitleClipConfig(
                  text = "Hola mundo FrameLine demo",
                  outlineWidthDp = 2.5f,
                  animation = SubtitleAnimation.POP,
                ),
            ),
          ),
      )

    val split = engine.splitSubtitleClip(state, "s1") as TimelineOperationResult.Success
    val splitClips = split.newState.clips.filter { it.kind == ClipKind.SUBTITLE }.sortedBy { it.timelineStartMs }
    assertEquals(2, splitClips.size)
    assertEquals(1_000L, splitClips[0].timelineStartMs)
    assertEquals(3_000L, splitClips[0].timelineEndMs)
    assertEquals(3_000L, splitClips[1].timelineStartMs)
    assertEquals(5_000L, splitClips[1].timelineEndMs)
    assertEquals("Hola mundo", splitClips[0].subtitleConfig?.text)
    assertEquals("FrameLine demo", splitClips[1].subtitleConfig?.text)

    val timingUpdated =
      engine.updateSubtitleTiming(split.newState, splitClips[0].clipId, 800L, 2_800L)
        as TimelineOperationResult.Success
    val adjustedFirst = timingUpdated.newState.clips.first { it.clipId == splitClips[0].clipId }
    assertEquals(800L, adjustedFirst.timelineStartMs)
    assertEquals(2_800L, adjustedFirst.timelineEndMs)

    val merged =
      engine.mergeAdjacentSubtitles(timingUpdated.newState, splitClips[0].clipId)
        as TimelineOperationResult.Success
    val mergedSubs = merged.newState.clips.filter { it.kind == ClipKind.SUBTITLE }
    assertEquals(1, mergedSubs.size)
    assertEquals(800L, mergedSubs[0].timelineStartMs)
    assertEquals(5_000L, mergedSubs[0].timelineEndMs)
    assertEquals("Hola mundo FrameLine demo", mergedSubs[0].subtitleConfig?.text)
  }

  @Test
  fun transitionCut_setsValidatesAndRemovesAdjacentClipTransitions() {
    val engine = TimelineEngine()
    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("v1", "t_vid", ClipKind.VIDEO, "Intro", 0L, 4_000L, 0L, 4_000L, videoConfig = VideoClipConfig()),
            TimelineClip("v2", "t_vid", ClipKind.VIDEO, "Main", 4_000L, 5_000L, 0L, 1_000L, videoConfig = VideoClipConfig()),
          ),
      )

    // Requested 1800ms should be clamped to shorter clip's duration (1000ms)
    val applied =
      engine.setTransitionAtCut(
        state = state,
        outgoingClipId = "v1",
        incomingClipId = "v2",
        transition = ClipTransition(type = TransitionType.WIPE_LEFT, durationMs = 1_800L),
      ) as TimelineOperationResult.Success

    val v1 = applied.newState.clips.first { it.clipId == "v1" }
    val v2 = applied.newState.clips.first { it.clipId == "v2" }
    assertEquals(TransitionType.WIPE_LEFT, v1.videoConfig?.transitionOut?.type)
    assertEquals(1_000L, v1.videoConfig?.transitionOut?.durationMs)
    assertEquals(TransitionType.WIPE_LEFT, v2.videoConfig?.transitionIn?.type)
    assertEquals(1_000L, v2.videoConfig?.transitionIn?.durationMs)

    val removed =
      engine.setTransitionAtCut(applied.newState, "v1", "v2", null) as TimelineOperationResult.Success
    assertNull(removed.newState.clips.first { it.clipId == "v1" }.videoConfig?.transitionOut)
    assertNull(removed.newState.clips.first { it.clipId == "v2" }.videoConfig?.transitionIn)
  }

  @Test
  fun speedCurveAndPitchPreservation_recalculateClipAndDownstreamTrackDurations() {
    val engine = TimelineEngine()
    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("v1", "t_vid", ClipKind.VIDEO, "Intro", 0L, 4_000L, 0L, 4_000L, videoConfig = VideoClipConfig()),
            TimelineClip("v2", "t_vid", ClipKind.VIDEO, "Next", 4_000L, 8_000L, 0L, 4_000L, videoConfig = VideoClipConfig()),
          ),
      )

    val ramp =
      listOf(
        SpeedCurvePoint(0.0f, 0.5f),
        SpeedCurvePoint(0.5f, 2.5f),
        SpeedCurvePoint(1.0f, 0.5f),
      )
    val expectedDur = SpeedCurveEngine.renderedDurationMs(4_000L, 1.0f, ramp)
    val ramped =
      engine.updateClipSpeedCurve(
        state = state,
        clipId = "v1",
        speedCurve = ramp,
        ripple = true,
        preserveAudioPitch = false,
      ) as TimelineOperationResult.Success

    val v1After = ramped.newState.clips.first { it.clipId == "v1" }
    val v2After = ramped.newState.clips.first { it.clipId == "v2" }
    assertEquals(expectedDur, v1After.durationMs)
    assertEquals(expectedDur, v2After.timelineStartMs)
    assertFalse(v1After.videoConfig?.preserveAudioPitch ?: true)

    val pitchRestored =
      engine.setClipPreserveAudioPitch(ramped.newState, "v1", true) as TimelineOperationResult.Success
    assertTrue(pitchRestored.newState.clips.first { it.clipId == "v1" }.videoConfig?.preserveAudioPitch == true)
  }

  @Test
  fun subtitleAnimationAndTransitionVisualMath_evaluateDeterministically() {
    val popStart = SubtitleAnimationMath.evaluate(SubtitleAnimation.POP, elapsedMs = 0L, totalDurationMs = 2_000L)
    val popMid = SubtitleAnimationMath.evaluate(SubtitleAnimation.POP, elapsedMs = 1_000L, totalDurationMs = 2_000L)
    assertEquals(0f, popStart.alpha, 0.01f)
    assertEquals(1f, popMid.alpha, 0.01f)
    assertEquals(1f, popMid.scale, 0.01f)

    val fadeWhiteIn =
      TransitionVisualMath.evaluate(
        transitionIn = ClipTransition(TransitionType.FADE_WHITE, 500L),
        transitionOut = null,
        offsetMs = 0L,
        clipDurationMs = 4_000L,
      )
    assertEquals(TransitionType.FADE_WHITE, fadeWhiteIn.type)
    assertEquals(1.0f, fadeWhiteIn.flashColorWhiteFraction, 0.01f)
    assertEquals(0.0f, fadeWhiteIn.alpha, 0.01f)
  }

  @Test
  fun serializer_roundTripsNewFieldsAndDecodesLegacyJson() {
    val serializer = ProjectJsonSerializer()
    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video"), Track("t_sub", TrackType.SUBTITLE, 1, "Subs")),
        clips =
          listOf(
            TimelineClip(
              clipId = "v1",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 3_000L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
              videoConfig =
                VideoClipConfig(
                  transitionOut = ClipTransition(TransitionType.FADE_WHITE, 450L),
                  preserveAudioPitch = false,
                ),
            ),
            TimelineClip(
              clipId = "s1",
              trackId = "t_sub",
              kind = ClipKind.SUBTITLE,
              label = "Sub",
              timelineStartMs = 200L,
              timelineEndMs = 2_000L,
              subtitleConfig =
                SubtitleClipConfig(
                  text = "Sub animado",
                  outlineColorHex = "#FFF59E0B",
                  outlineWidthDp = 3.5f,
                  verticalOffsetNormalized = -0.15f,
                  animation = SubtitleAnimation.SLIDE_UP,
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_fine",
        name = "Fine Edit",
        createdAtIso = "2026-11-25T10:00:00Z",
        updatedAtIso = "2026-11-25T10:00:00Z",
        timeline = state,
        savedSubtitleStyles = BuiltInSubtitleStyles.ALL,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val decodedVid = decoded.timeline.clips.first { it.clipId == "v1" }
    val decodedSub = decoded.timeline.clips.first { it.clipId == "s1" }
    assertFalse(decodedVid.videoConfig!!.preserveAudioPitch)
    assertEquals(TransitionType.FADE_WHITE, decodedVid.videoConfig?.transitionOut?.type)
    assertEquals(SubtitleAnimation.SLIDE_UP, decodedSub.subtitleConfig?.animation)
    assertEquals(3.5f, decodedSub.subtitleConfig!!.outlineWidthDp, 0.01f)
    assertEquals(-0.15f, decodedSub.subtitleConfig!!.verticalOffsetNormalized, 0.01f)

    // Backward-compatible legacy JSON without preserveAudioPitch or subtitle outline/animation fields
    val legacyJson =
      """
      {
        "schemaVersion": 1,
        "projectId": "legacy_sub",
        "name": "Legacy",
        "createdAtIso": "2025-01-01T00:00:00Z",
        "updatedAtIso": "2025-01-01T00:00:00Z",
        "timeline": {
          "playheadMs": 0,
          "tracks": [{"trackId": "v", "type": "VIDEO", "order": 0, "name": "V"}, {"trackId": "s", "type": "SUBTITLE", "order": 1, "name": "S"}],
          "clips": [
            {"clipId": "vc", "trackId": "v", "kind": "VIDEO", "label": "V", "timelineStartMs": 0, "timelineEndMs": 2000, "sourceInMs": 0, "sourceOutMs": 2000, "videoConfig": {"volume": 1.0}},
            {"clipId": "sc", "trackId": "s", "kind": "SUBTITLE", "label": "S", "timelineStartMs": 0, "timelineEndMs": 2000, "subtitleConfig": {"text": "Old", "alignment": "BOTTOM_CENTER"}}
          ]
        }
      }
      """.trimIndent()
    val legacyDecoded = serializer.decode(legacyJson).getOrThrow()
    assertTrue(legacyDecoded.timeline.clips.first { it.clipId == "vc" }.videoConfig!!.preserveAudioPitch)
    assertEquals(SubtitleAnimation.NONE, legacyDecoded.timeline.clips.first { it.clipId == "sc" }.subtitleConfig!!.animation)
  }

  @Test
  fun compositionCompiler_handlesTransitionsVariableSpeedCurvesPitchAndAnimatedSubtitles() {
    val rootDir = tempFolder.newFolder("fine_export_proj")
    val mediaFile = File(rootDir, "media/clip.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip.mp4",
        relativePath = "media/${mediaFile.name}",
        fingerprint = MediaFingerprint(4L, 5_000L, "hash", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video"),
            Track("sub", TrackType.SUBTITLE, 1, "Subtítulos"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Ramped Cut",
              timelineStartMs = 0L,
              timelineEndMs = 3_000L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
              assetId = "a1",
              speedCurve = listOf(SpeedCurvePoint(0f, 0.75f), SpeedCurvePoint(0.5f, 2.0f), SpeedCurvePoint(1f, 0.8f)),
              videoConfig =
                VideoClipConfig(
                  transitionIn = ClipTransition(TransitionType.FADE_WHITE, 400L),
                  transitionOut = ClipTransition(TransitionType.WIPE_LEFT, 400L),
                  preserveAudioPitch = false,
                ),
            ),
            TimelineClip(
              clipId = "s1",
              trackId = "sub",
              kind = ClipKind.SUBTITLE,
              label = "Hola",
              timelineStartMs = 200L,
              timelineEndMs = 2_400L,
              subtitleConfig =
                SubtitleClipConfig(
                  text = "Subtítulo animado",
                  alignment = SubtitleAlignment.MIDDLE_CENTER,
                  outlineWidthDp = 2.0f,
                  animation = SubtitleAnimation.POP,
                ),
            ),
          ),
      )

    val result =
      CompositionCompiler().compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      )

    assertTrue(result is CompiledCompositionResult.Ready)
    val ready = result as CompiledCompositionResult.Ready
    assertEquals(1, ready.totalVideoItems)
    assertNotNull(ready.composition)

    val autoSubs =
      SubtitleBatchEngine.generateAutoSubtitlesForIntervals(
        trackId = "sub",
        intervals = listOf(0L to 1_200L, 1_500L to 3_000L),
        style = BuiltInSubtitleStyles.HIGH_CONTRAST_AMBER,
        seedTranscript = "Primera frase auto generada para probar el motor",
      )
    assertEquals(2, autoSubs.size)
  }
}
