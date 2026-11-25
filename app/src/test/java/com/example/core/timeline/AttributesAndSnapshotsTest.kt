package com.example.core.timeline

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ClipKind
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.ProjectDocument
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AttributesAndSnapshotsTest {

  @Test
  fun selectiveAttributeTransfer_copiesOnlyScalePositionWithoutOverwritingAudio() {
    val engine = AttributeTransferEngine()
    val source =
      TimelineClip(
        clipId = "v1",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Source",
        timelineStartMs = 0L,
        timelineEndMs = 2_000L,
        sourceInMs = 0L,
        sourceOutMs = 2_000L,
        videoConfig = VideoClipConfig(volume = 0.2f, scale = 1.5f, offsetX = 40f, rotationDegrees = 15f),
      )
    val target =
      TimelineClip(
        clipId = "v2",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Target",
        timelineStartMs = 2_000L,
        timelineEndMs = 4_000L,
        sourceInMs = 0L,
        sourceOutMs = 2_000L,
        videoConfig = VideoClipConfig(volume = 0.9f, scale = 1.0f, offsetX = 0f, rotationDegrees = 0f),
      )

    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips = listOf(source, target),
      )

    val clipboard = engine.copyFromClip(source, setOf(AttributeCategory.SCALE_POSITION))
    val result =
      engine.pasteOntoClips(state, listOf("v2"), clipboard, setOf(AttributeCategory.SCALE_POSITION))
        as TimelineOperationResult.Success

    val updatedTarget = result.newState.clips.first { it.clipId == "v2" }
    assertEquals(1.5f, updatedTarget.videoConfig?.scale)
    assertEquals(40f, updatedTarget.videoConfig?.offsetX)
    assertEquals(15f, updatedTarget.videoConfig?.rotationDegrees)
    // Volume must remain untouched when only SCALE_POSITION was copied/pasted
    assertEquals(0.9f, updatedTarget.videoConfig?.volume)
  }

  @Test
  fun selectiveAttributeTransfer_copiesOnlyTextStyleWhilePreservingTargetText() {
    val engine = AttributeTransferEngine()
    val subSource =
      TimelineClip(
        clipId = "s1",
        trackId = "t_sub",
        kind = ClipKind.SUBTITLE,
        label = "S1",
        timelineStartMs = 0L,
        timelineEndMs = 1_500L,
        subtitleConfig =
          SubtitleClipConfig(
            text = "Texto origen",
            fontSizeSp = 28f,
            textColorHex = "#FBBF24",
            alignment = SubtitleAlignment.TOP_CENTER,
          ),
      )
    val subTarget =
      TimelineClip(
        clipId = "s2",
        trackId = "t_sub",
        kind = ClipKind.SUBTITLE,
        label = "S2",
        timelineStartMs = 1_500L,
        timelineEndMs = 3_000L,
        subtitleConfig = SubtitleClipConfig(text = "Texto destino único"),
      )
    val adjSource =
      TimelineClip(
        clipId = "adj1",
        trackId = "t_adj",
        kind = ClipKind.ADJUSTMENT,
        label = "LUT 1",
        timelineStartMs = 0L,
        timelineEndMs = 3_000L,
        adjustmentConfig = AdjustmentClipConfig(contrast = 1.3f, lutRelativePath = "luts/teal.cube"),
      )

    val state =
      TimelineState(
        tracks =
          listOf(
            Track("t_sub", TrackType.SUBTITLE, 0, "Subs"),
            Track("t_adj", TrackType.ADJUSTMENT, 1, "Adj"),
          ),
        clips = listOf(subSource, subTarget, adjSource),
      )

    val clipboard = engine.copyFromClip(subSource, setOf(AttributeCategory.TEXT_STYLE))
    val pasted =
      engine.pasteOntoClips(state, listOf("s2", "adj1"), clipboard, setOf(AttributeCategory.TEXT_STYLE))
        as TimelineOperationResult.Success

    val updatedTarget = pasted.newState.clips.first { it.clipId == "s2" }
    assertEquals("Texto destino único", updatedTarget.subtitleConfig?.text)
    assertEquals(28f, updatedTarget.subtitleConfig?.fontSizeSp)
    assertEquals("#FBBF24", updatedTarget.subtitleConfig?.textColorHex)
    assertEquals(SubtitleAlignment.TOP_CENTER, updatedTarget.subtitleConfig?.alignment)
  }

  @Test
  fun snapshotManager_createsRestoresAndDuplicatesSequences() {
    var seqCounter = 1
    val manager = TimelineSnapshotManager(idGenerator = { "id_${seqCounter++}" })
    val initialTimeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("c1", "v1", ClipKind.VIDEO, "Intro", 0L, 3_000L, 0L, 3_000L),
          ),
      )
    var project =
      ProjectDocument(
        projectId = "p1",
        name = "Test",
        createdAtIso = "2026-11-23T00:00:00Z",
        updatedAtIso = "2026-11-23T00:00:00Z",
        timeline = initialTimeline,
      )

    // 1. Save snapshot before modifying timeline
    project = manager.createSnapshot(project, initialTimeline, "Antes de recorte")
    assertEquals(1, project.snapshots.size)

    // 2. Duplicate timeline into a second independent sequence
    val (withDuplicate, dupSeq) = manager.duplicateAsSequence(project, initialTimeline, "Versión B")
    project = withDuplicate
    assertEquals(2, project.sequences.size)
    assertEquals(dupSeq.sequenceId, project.activeSequenceId)

    // 3. Modify duplicated sequence, then switch back to main sequence
    val modifiedDupTimeline = dupSeq.timeline.copy(clips = emptyList())
    val switchedBack =
      manager.switchSequence(project, modifiedDupTimeline, "seq_main")!!
    project = switchedBack.first
    assertEquals(1, switchedBack.second.clips.size)
    assertNotEquals(dupSeq.sequenceId, project.activeSequenceId)

    // 4. Restore snapshot
    val restored = manager.restoreSnapshot(project, project.snapshots.first().snapshotId)!!
    assertEquals(1, restored.second.clips.size)
  }

  @Test
  fun selectiveAttributeTransfer_copiesExtendedColorWheelsWhitesBlacksVibranceAndDepthBlur() {
    val engine = AttributeTransferEngine()
    val source =
      TimelineClip(
        clipId = "v1",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Source",
        timelineStartMs = 0L,
        timelineEndMs = 3_000L,
        sourceInMs = 0L,
        sourceOutMs = 3_000L,
        videoConfig =
          VideoClipConfig(
            scale = 1.8f,
            whites = 0.35f,
            blacks = -0.25f,
            vibrance = 0.45f,
            liftX = 0.2f,
            liftY = -0.15f,
            gammaX = 0.1f,
            gammaY = 0.25f,
            gainX = -0.3f,
            gainY = 0.4f,
            depthBlur = 0.7f,
            depthBlurRadius = 20f,
            depthBlurFeather = 0.55f,
            depthBlurMode = DepthBlurMaskMode.LINEAR,
            depthFocusX = 0.3f,
            depthFocusY = 0.7f,
            depthFocusRange = 0.42f,
          ),
      )
    val target =
      TimelineClip(
        clipId = "v2",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Target",
        timelineStartMs = 3_000L,
        timelineEndMs = 6_000L,
        sourceInMs = 0L,
        sourceOutMs = 3_000L,
        videoConfig = VideoClipConfig(scale = 1.0f),
      )
    val state =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips = listOf(source, target),
      )

    val clipboard = engine.copyFromClip(source, setOf(AttributeCategory.COLOR_LUT))
    val pasted =
      engine.pasteOntoClips(state, listOf("v2"), clipboard, setOf(AttributeCategory.COLOR_LUT))
        as TimelineOperationResult.Success
    val updated = pasted.newState.clips.first { it.clipId == "v2" }.videoConfig!!

    assertEquals(1.0f, updated.scale, 0.001f)
    assertEquals(0.35f, updated.whites, 0.001f)
    assertEquals(-0.25f, updated.blacks, 0.001f)
    assertEquals(0.45f, updated.vibrance, 0.001f)
    assertEquals(0.2f, updated.liftX, 0.001f)
    assertEquals(-0.15f, updated.liftY, 0.001f)
    assertEquals(0.4f, updated.gainY, 0.001f)
    assertEquals(0.7f, updated.depthBlur, 0.001f)
    assertEquals(20f, updated.depthBlurRadius, 0.001f)
    assertEquals(0.55f, updated.depthBlurFeather, 0.001f)
    assertEquals(DepthBlurMaskMode.LINEAR, updated.depthBlurMode)
    assertEquals(0.3f, updated.depthFocusX, 0.001f)
    assertEquals(0.7f, updated.depthFocusY, 0.001f)
    assertEquals(0.42f, updated.depthFocusRange, 0.001f)
  }
}
