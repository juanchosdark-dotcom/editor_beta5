package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorToolSubPanel
import com.example.ui.editor.TimelineSkeletonViewModel
import com.example.ui.theme.FrameLineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h1280dp")
class EditorAdaptiveLayoutAndPinchTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `editor adapts across portrait landscape tablet and wide layouts while keeping state and expanding dynamically`() {
    val tempRoot = Files.createTempDirectory("frameline_adaptive_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      vm.seekPlayhead(1_800L)
      vm.updateSelectedClipColorParams(brightness = 0.22f, fromSliderDrag = false)
      vm.setTimelineZoom(1.45f)
      vm.toggleSubPanel(EditorToolSubPanel.ADJUSTMENTS)
      vm.toggleViewerZoomPanMode()
      vm.toggleViewerRuleOfThirds()
      vm.setViewerTransform(zoomScale = 1.8f, panNormalizedX = 0.14f, panNormalizedY = 0f)

      var viewportSize by mutableStateOf(DpSize(411.dp, 820.dp))

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(viewportSize.width, viewportSize.height)) {
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
      // Portrait with Inspector open: viewer, timeline, inspector & action deck share 100% area
      composeRule.onNodeWithTag("editor_portrait_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("viewer_monitor_surface").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()
      composeRule.onNodeWithTag("split_button").assertIsDisplayed()
      val phonePreviewBounds =
        composeRule.onNodeWithTag("viewer_monitor_surface").fetchSemanticsNode().boundsInRoot
      val phoneTimelineBounds =
        composeRule.onNodeWithTag("multitrack_timeline_deck").fetchSemanticsNode().boundsInRoot
      val phoneSubPanelBounds =
        composeRule.onNodeWithTag("adjustments_subpanel").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Portrait timeline height should not be crushed (was ${phoneTimelineBounds.height}px)",
        phoneTimelineBounds.height >= 120f,
      )

      // Expand to large Tablet Portrait (800 x 1200 dp): verify containers expand dynamically beyond old fixed caps
      composeRule.runOnUiThread {
        viewportSize = DpSize(800.dp, 1200.dp)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("editor_portrait_layout").assertIsDisplayed()
      val tabletPreviewBounds =
        composeRule.onNodeWithTag("viewer_monitor_surface").fetchSemanticsNode().boundsInRoot
      val tabletTimelineBounds =
        composeRule.onNodeWithTag("multitrack_timeline_deck").fetchSemanticsNode().boundsInRoot
      val tabletSubPanelBounds =
        composeRule.onNodeWithTag("adjustments_subpanel").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Tablet portrait viewer should expand dynamically (phone=${phonePreviewBounds.height}, tablet=${tabletPreviewBounds.height})",
        tabletPreviewBounds.height > phonePreviewBounds.height + 40f,
      )
      assertTrue(
        "Tablet portrait timeline should expand dynamically (phone=${phoneTimelineBounds.height}, tablet=${tabletTimelineBounds.height})",
        tabletTimelineBounds.height > phoneTimelineBounds.height + 40f,
      )
      assertTrue(
        "Tablet portrait inspector should expand dynamically (phone=${phoneSubPanelBounds.height}, tablet=${tabletSubPanelBounds.height})",
        tabletSubPanelBounds.height > phoneSubPanelBounds.height + 40f,
      )

      // Rotate / resize to phone Landscape (760x390dp)
      composeRule.runOnUiThread {
        viewportSize = DpSize(760.dp, 390.dp)
      }
      composeRule.waitForIdle()

      composeRule.onNodeWithTag("editor_landscape_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("compact_landscape_transport_strip").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()
      composeRule.onNodeWithTag("preview_play_pause_button").assertIsDisplayed()
      composeRule.onNodeWithTag("zoom_fit_button").assertIsDisplayed()
      composeRule.onNodeWithTag("split_button").assertIsDisplayed()

      val landscapeTimelineBounds =
        composeRule.onNodeWithTag("multitrack_timeline_deck").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Landscape timeline height must remain usable (was ${landscapeTimelineBounds.height}px)",
        landscapeTimelineBounds.height >= 100f,
      )
      assertTrue(
        "Landscape timeline width should expand to use extra width (was ${landscapeTimelineBounds.width}px)",
        landscapeTimelineBounds.width > phoneTimelineBounds.width,
      )

      // State preserved across portrait -> tablet -> landscape transitions
      assertEquals(1_800L, vm.uiState.value.timeline.playheadMs)
      assertEquals("clip_v1", vm.uiState.value.selectedClipId)
      assertEquals(0.22f, vm.uiState.value.selectedClip!!.videoConfig!!.brightness, 0.001f)
      assertEquals(1.45f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertEquals(EditorToolSubPanel.ADJUSTMENTS, vm.uiState.value.activeSubPanel)
      assertEquals(1.8f, vm.uiState.value.viewerViewport.zoomScale, 0.001f)
      assertTrue(vm.uiState.value.viewerViewport.showRuleOfThirds)

      // Expand to Wide Tablet Landscape (960 x 620 dp): all 3 upper panes + timeline visible
      composeRule.runOnUiThread {
        viewportSize = DpSize(960.dp, 620.dp)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("editor_landscape_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("zoom_fit_button").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()

      // Return to Portrait: all editor and viewer state remains intact
      composeRule.runOnUiThread {
        viewportSize = DpSize(411.dp, 820.dp)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("editor_portrait_layout").assertIsDisplayed()
      assertEquals(1_800L, vm.uiState.value.timeline.playheadMs)
      assertEquals("clip_v1", vm.uiState.value.selectedClipId)
      assertEquals(1.45f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertEquals(1.8f, vm.uiState.value.viewerViewport.zoomScale, 0.001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `two finger pinch zooms timeline smoothly without interfering with trim or playhead scrub`() {
    val tempRoot = Files.createTempDirectory("frameline_pinch_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      val initialPlayheadMs = vm.uiState.value.timeline.playheadMs
      val initialClipStartMs = vm.uiState.value.selectedClip!!.timelineStartMs
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(440.dp, 840.dp)) {
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

      // 1. Two-finger pinch out on the timeline deck -> zooms in without moving playhead or trimming clip
      composeRule.onNodeWithTag("multitrack_timeline_deck").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(50f, 0f),
          end0 = c - Offset(160f, 0f),
          start1 = c + Offset(50f, 0f),
          end1 = c + Offset(160f, 0f),
        )
      }
      composeRule.waitForIdle()

      val zoomedInScale = vm.uiState.value.ergonomics.zoomScale
      assertTrue("Expected pinch-out to increase zoomScale above 1.0 (was $zoomedInScale)", zoomedInScale > 1.2f)
      assertEquals(initialPlayheadMs, vm.uiState.value.timeline.playheadMs)
      assertEquals(initialClipStartMs, vm.uiState.value.selectedClip!!.timelineStartMs)

      // 1b. Two-finger pinch centered directly over the playhead handle zooms without scrubbing playhead
      composeRule.onNodeWithTag("timeline_playhead_handle").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(24f, 0f),
          end0 = c - Offset(90f, 0f),
          start1 = c + Offset(24f, 0f),
          end1 = c + Offset(90f, 0f),
        )
      }
      composeRule.waitForIdle()
      assertEquals(initialPlayheadMs, vm.uiState.value.timeline.playheadMs)
      assertEquals(initialClipStartMs, vm.uiState.value.selectedClip!!.timelineStartMs)

      // 1c. Two-finger pinch centered over a clip trim handle never triggers clip trimming
      composeRule.onNodeWithTag("trim_handle_start_clip_v1").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(90f, 0f),
          end0 = c - Offset(24f, 0f),
          start1 = c + Offset(90f, 0f),
          end1 = c + Offset(24f, 0f),
        )
      }
      composeRule.waitForIdle()
      assertEquals(initialClipStartMs, vm.uiState.value.selectedClip!!.timelineStartMs)
      assertEquals(initialPlayheadMs, vm.uiState.value.timeline.playheadMs)

      // Reset to 100% via quick reset pill
      if (kotlin.math.abs(vm.uiState.value.ergonomics.zoomScale - 1.0f) > 0.02f) {
        composeRule.onNodeWithTag("timeline_zoom_reset_pill").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
      }
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)

      // 2. Single-finger drag on trim_handle_start_clip_v1 trims clip without changing zoom
      composeRule.onNodeWithTag("trim_handle_start_clip_v1").performTouchInput {
        swipeRight(startX = centerX, endX = centerX + 80f)
      }
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertTrue(
        "Single-finger drag on trim handle must still trim clip",
        vm.uiState.value.selectedClip!!.timelineStartMs > initialClipStartMs,
      )

      // 3. Single-finger drag on timeline_playhead_handle scrubs playhead without changing zoom
      val playheadBeforeScrub = vm.uiState.value.timeline.playheadMs
      composeRule.onNodeWithTag("timeline_playhead_handle").performTouchInput {
        swipeRight(startX = centerX, endX = centerX + 90f)
      }
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertTrue(
        "Single-finger drag on playhead handle must scrub playhead forward",
        vm.uiState.value.timeline.playheadMs > playheadBeforeScrub,
      )

      // 4. Quick Fit Zoom pill ("Ajustar todo") adjusts zoom within valid bounds
      composeRule.onNodeWithTag("timeline_zoom_fit_pill").performClick()
      composeRule.waitForIdle()
      val fitScale = vm.uiState.value.ergonomics.zoomScale
      assertTrue(fitScale in TimelineViewportErgonomics.MIN_ZOOM..TimelineViewportErgonomics.MAX_ZOOM)
      assertTrue("Fit zoom should adjust scale from 1.0f (was $fitScale)", kotlin.math.abs(fitScale - 1.0f) > 0.05f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewer optional zoom pan, rule of thirds, safe margins, magnetic center snap and reset work`() {
    val tempRoot = Files.createTempDirectory("frameline_viewer_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(420.dp, 820.dp)) {
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

      // When zoom/pan mode is OFF, pinching the viewer monitor keeps zoomScale at 1.0
      composeRule.onNodeWithTag("viewer_monitor_surface").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(30f, 0f),
          end0 = c - Offset(110f, 0f),
          start1 = c + Offset(30f, 0f),
          end1 = c + Offset(110f, 0f),
        )
      }
      composeRule.waitForIdle()
      assertFalse(vm.uiState.value.viewerViewport.isZoomPanEnabled)
      assertEquals(1.0f, vm.uiState.value.viewerViewport.zoomScale, 0.001f)

      // Toggle Rule of Thirds & Safe Margins -> guides overlay appears
      composeRule.onNodeWithTag("viewer_thirds_toggle").assertIsDisplayed().performClick()
      composeRule.onNodeWithTag("viewer_safe_margins_toggle").assertIsDisplayed().performClick()
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.viewerViewport.showRuleOfThirds)
      assertTrue(vm.uiState.value.viewerViewport.showSafeMargins)
      composeRule.onNodeWithTag("viewer_guides_canvas").assertIsDisplayed()

      // Enable viewer zoom/pan and pinch-out -> viewer zooms and shows reset pill
      composeRule.onNodeWithTag("viewer_zoom_pan_toggle").assertIsDisplayed().performClick()
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.viewerViewport.isZoomPanEnabled)

      composeRule.onNodeWithTag("viewer_monitor_surface").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(35f, 0f),
          end0 = c - Offset(120f, 0f),
          start1 = c + Offset(35f, 0f),
          end1 = c + Offset(120f, 0f),
        )
      }
      composeRule.waitForIdle()
      assertTrue(
        "Expected viewer zoom above 1.0f after pinch-out (was ${vm.uiState.value.viewerViewport.zoomScale})",
        vm.uiState.value.viewerViewport.zoomScale > 1.2f,
      )
      composeRule.onNodeWithTag("viewer_reset_zoom_button").assertIsDisplayed()

      // Pan away from center then verify magnetic center snap toggle & reset button work
      composeRule.onNodeWithTag("viewer_monitor_surface").performTouchInput {
        swipeRight(startX = centerX - 60f, endX = centerX + 80f)
      }
      composeRule.waitForIdle()
      assertTrue(kotlin.math.abs(vm.uiState.value.viewerViewport.panNormalizedX) > 0.04f)

      composeRule.onNodeWithTag("viewer_center_snap_toggle").assertIsDisplayed().performClick()
      composeRule.waitForIdle()
      assertFalse(vm.uiState.value.viewerViewport.magneticCenterSnap)

      composeRule.onNodeWithTag("viewer_reset_zoom_button").performClick()
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.viewerViewport.zoomScale, 0.001f)
      assertEquals(0f, vm.uiState.value.viewerViewport.panNormalizedX, 0.0001f)
      assertEquals(0f, vm.uiState.value.viewerViewport.panNormalizedY, 0.0001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
