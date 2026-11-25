package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.OverlayContentKind
import com.example.core.model.TrackType
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.TimelineInsertKind
import com.example.ui.editor.TimelineSkeletonViewModel
import com.example.ui.theme.FrameLineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1080dp-h900dp")
class MultitrackInsertAndTrackCreationTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `addTrack creates additional audio overlay and adjustment tracks with undo support`() {
    val tempRoot = Files.createTempDirectory("frameline_tracks_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      val initialCount = vm.uiState.value.timeline.tracks.size

      val aud2 = vm.addTrack(TrackType.AUDIO)
      val ovl1 = vm.addTrack(TrackType.VIDEO, isOverlay = true)
      val adj2 = vm.addTrack(TrackType.ADJUSTMENT)

      val tracks = vm.uiState.value.timeline.tracks
      assertEquals(initialCount + 3, tracks.size)
      assertEquals(2, tracks.count { it.type == TrackType.AUDIO })
      assertEquals(1, tracks.count { it.type == TrackType.VIDEO && it.isOverlay })
      assertEquals(2, tracks.count { it.type == TrackType.ADJUSTMENT })
      assertTrue(tracks.any { it.trackId == aud2.trackId })
      assertTrue(tracks.any { it.trackId == ovl1.trackId && it.isOverlay })
      assertTrue(tracks.any { it.trackId == adj2.trackId })

      vm.undo()
      assertEquals(initialCount + 2, vm.uiState.value.timeline.tracks.size)
      vm.redo()
      assertEquals(initialCount + 3, vm.uiState.value.timeline.tracks.size)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `playhead anchored insert spawns parallel tracks on overlap and supports draggable trim and viewer overlay transform`() {
    val tempRoot = Files.createTempDirectory("frameline_insert_vm_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.seekPlayhead(2_500L)

      // 1) Voiceover / audio inserted at playhead overlaps existing A1 clip (0..9500) -> auto creates A2
      val voiceFile = File(vm.resolveMediaDirectory(), "voice_test.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
      vm.insertRecordedVoiceoverAtPlayhead(audioFile = voiceFile, label = "Narración Test")
      val stateAfterAudio = vm.uiState.value
      val voiceClip = stateAfterAudio.timeline.clips.firstOrNull { it.label == "Narración Test" }
      assertNotNull(voiceClip)
      assertEquals(2_500L, voiceClip!!.timelineStartMs)
      assertEquals(2, stateAfterAudio.timeline.tracks.count { it.type == TrackType.AUDIO })

      // 2) Adjustment effect inserted at playhead overlaps clip_adj1 (1000..8000) -> auto creates second adjustment track & trims freely
      vm.insertAdjustmentEffectAtPlayhead(
        label = "Aberración cromática",
        config = AdjustmentClipConfig(chromaticAberration = 0.65f),
        durationMs = 2_000L,
      )
      val fxClip = vm.uiState.value.timeline.clips.firstOrNull { it.label == "Aberración cromática" }
      assertNotNull(fxClip)
      assertEquals(2_500L, fxClip!!.timelineStartMs)
      assertEquals(4_500L, fxClip.timelineEndMs)
      assertEquals(2, vm.uiState.value.timeline.tracks.count { it.type == TrackType.ADJUSTMENT })

      // Draggable duration extension on adjustment clip
      vm.trimSelectedClipEdge(clipId = fxClip.clipId, isStartEdge = false, rawTargetMs = 6_000L, fromDrag = false)
      val trimmedFx = vm.uiState.value.timeline.clips.first { it.clipId == fxClip.clipId }
      assertEquals(6_000L, trimmedFx.timelineEndMs)

      // 3) Sticker / Logo overlay inserted at playhead creates overlay track and allows viewer drag/resize + undo
      vm.insertStickerOrLogoAtPlayhead(
        stickerId = "frameline_logo",
        title = "FrameLine",
        isLogo = true,
        durationMs = 3_500L,
      )
      val ovlClip = vm.uiState.value.timeline.clips.firstOrNull { it.videoConfig?.stickerId == "frameline_logo" }
      assertNotNull(ovlClip)
      assertEquals(OverlayContentKind.LOGO, ovlClip!!.videoConfig?.overlayKind)
      assertEquals(2_500L, ovlClip.timelineStartMs)
      assertEquals(6_000L, ovlClip.timelineEndMs)

      // Extend image/logo overlay beyond initial duration
      vm.trimSelectedClipEdge(clipId = ovlClip.clipId, isStartEdge = false, rawTargetMs = 8_200L, fromDrag = false)
      assertEquals(8_200L, vm.uiState.value.timeline.clips.first { it.clipId == ovlClip.clipId }.timelineEndMs)

      // Direct viewer transform (position + resize)
      vm.commitViewerOverlayTransform(
        clipId = ovlClip.clipId,
        offsetX = 0.42f,
        offsetY = -0.35f,
        scale = 1.45f,
        fromDrag = false,
      )
      val movedOvl = vm.uiState.value.timeline.clips.first { it.clipId == ovlClip.clipId }
      assertEquals(0.42f, movedOvl.videoConfig?.offsetX ?: 0f, 0.001f)
      assertEquals(-0.35f, movedOvl.videoConfig?.offsetY ?: 0f, 0.001f)
      assertEquals(1.45f, movedOvl.videoConfig?.scale ?: 0f, 0.001f)

      vm.undo()
      val revertedOvl = vm.uiState.value.timeline.clips.first { it.clipId == ovlClip.clipId }
      assertEquals(0.0f, revertedOvl.videoConfig?.offsetX ?: -1f, 0.001f)

      // 4) Insert project media asset as PiP via persisted ProjectRepository document & verify RenderPlan
      val pipAsset =
        MediaAsset(
          assetId = "asset_pip_demo",
          type = MediaType.VIDEO,
          displayName = "Camara_B.mp4",
          relativePath = "media/Camara_B.mp4",
          fingerprint = MediaFingerprint(byteSize = 1024L, durationMs = 4_000L, partialSha256 = "abc", mimeType = "video/mp4"),
        )
      val repo = com.example.core.project.ProjectRepository(File(tempRoot, "projects"))
      val savedDoc =
        TimelineSkeletonViewModel.createInitialProjectDocument().copy(
          projectId = "proj_pip",
          assets = listOf(pipAsset),
        )
      kotlinx.coroutines.runBlocking { repo.saveProject(savedDoc) }
      val vm2 = TimelineSkeletonViewModel(projectRepository = repo, projectRootDir = tempRoot)
      vm2.openRecentProject("proj_pip")
      val deadline = System.currentTimeMillis() + 5_000L
      while (vm2.uiState.value.assets.none { it.assetId == pipAsset.assetId } && System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(20L)
      }
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      vm2.seekPlayhead(1_500L)
      vm2.insertAssetAsPipAtPlayhead(pipAsset.assetId)
      val pipClip = vm2.uiState.value.timeline.clips.firstOrNull { it.videoConfig?.overlayKind == OverlayContentKind.VIDEO_PIP }
      assertNotNull(pipClip)
      assertEquals(1_500L, pipClip!!.timelineStartMs)
      File(tempRoot, "media").mkdirs()
      File(tempRoot, "media/Camara_B.mp4").writeBytes(byteArrayOf(1, 2, 3, 4))
      val plan =
        com.example.core.media.engine.TimelineRenderPlanner.buildPlan(
          timeline = vm2.uiState.value.timeline,
          assets = vm2.uiState.value.assets,
          projectRootDir = tempRoot,
          useProxies = false,
        )
      assertTrue(plan.overlaySegments.any { it.clip.clipId == pipClip.clipId && it.overlayKind == OverlayContentKind.VIDEO_PIP })
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `editor screen displays track and dock plus shortcuts inserts overlay with resize handle and adds tracks`() {
    val tempRoot = Files.createTempDirectory("frameline_insert_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(1000.dp, 850.dp)) {
            EditorScreen(
              uiState = uiState,
              previewState = previewState,
              viewModel = vm,
              onOpenMediaPicker = {},
            )
          }
        }
      }

      composeRule.waitForIdle()

      // Track header + and end-of-clips + plus quick dock buttons and + Pista button
      composeRule.onNodeWithTag("track_add_button_t_aud").assertIsDisplayed()
      composeRule.onNodeWithTag("track_end_add_button_t_aud").assertIsDisplayed()
      composeRule.onNodeWithTag("track_add_button_t_adj").assertIsDisplayed()
      composeRule.onNodeWithTag("quick_add_audio_button").assertIsDisplayed()
      composeRule.onNodeWithTag("quick_add_overlay_button").assertIsDisplayed()
      composeRule.onNodeWithTag("quick_add_effects_button").assertIsDisplayed()
      composeRule.onNodeWithTag("add_track_button").assertIsDisplayed()

      // Create a new Overlay track from the + Pista menu
      val beforeTracks = vm.uiState.value.timeline.tracks.size
      composeRule.onNodeWithTag("add_track_button").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("add_track_option_OVERLAY").performClick()
      composeRule.waitForIdle()
      assertEquals(beforeTracks + 1, vm.uiState.value.timeline.tracks.size)
      assertTrue(vm.uiState.value.timeline.tracks.any { it.isOverlay })

      // Quick Audio dock button opens Audio Insert Sheet anchored at playhead
      composeRule.onNodeWithTag("quick_add_audio_button").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("timeline_insert_sheet").assertIsDisplayed()
      composeRule.onNodeWithTag("audio_import_device_button").assertIsDisplayed()
      composeRule.onNodeWithTag("audio_extract_video_picker_button").assertIsDisplayed()
      composeRule.onNodeWithTag("audio_record_voiceover_button").assertIsDisplayed()
      composeRule.onNodeWithTag("close_insert_sheet_button").performClick()
      composeRule.waitForIdle()

      // Quick Overlay dock button opens Overlay sheet -> insert sticker -> visible on viewer with resize handle
      composeRule.onNodeWithTag("quick_add_overlay_button").performClick()
      composeRule.waitForIdle()
      assertEquals(TimelineInsertKind.OVERLAY, vm.uiState.value.activeInsertRequest?.kind)
      composeRule.onNodeWithTag("overlay_import_image_button").assertIsDisplayed()
      composeRule.onNodeWithTag("overlay_import_pip_button").assertIsDisplayed()
      composeRule.onNodeWithTag("overlay_sticker_frameline_logo").performClick()
      composeRule.waitForIdle()

      val insertedOverlay =
        vm.uiState.value.timeline.clips.first { it.videoConfig?.stickerId == "frameline_logo" }
      composeRule.onNodeWithTag("viewer_overlay_item_${insertedOverlay.clipId}").assertIsDisplayed()
      composeRule.onNodeWithTag("viewer_overlay_resize_handle_${insertedOverlay.clipId}").assertIsDisplayed()

      // Quick Effects dock button inserts chromatic aberration adjustment clip
      composeRule.onNodeWithTag("quick_add_effects_button").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("effect_preset_fx_chromatic").performClick()
      composeRule.waitForIdle()

      val insertedFx =
        vm.uiState.value.timeline.clips.firstOrNull {
          it.kind == ClipKind.ADJUSTMENT && (it.adjustmentConfig?.chromaticAberration ?: 0f) > 0.1f
        }
      assertNotNull(insertedFx)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
