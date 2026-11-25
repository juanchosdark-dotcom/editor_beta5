package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.DepthBlurMaskMode
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorToolSubPanel
import com.example.ui.editor.InspectorTab
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
class ColorDepthAndComparatorUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `viewer AB bypass hold, draggable split comparator, histogram scopes, and depth reticle work`() {
    val tempRoot = Files.createTempDirectory("frameline_ab_scopes_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      vm.updateSelectedClipColorParams(
        exposure = 0.45f,
        contrast = 1.25f,
        depthBlur = 0.65f,
        depthFocusX = 0.4f,
        depthFocusY = 0.5f,
        fromSliderDrag = false,
      )

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(460.dp, 860.dp)) {
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

      // Depth reticle is visible while depth blur > 0 and bypass is off
      composeRule.onNodeWithTag("viewer_depth_focus_reticle").assertIsDisplayed()

      // Hold-to-Bypass button activates bypass while pressed and restores on release
      composeRule.onNodeWithTag("viewer_ab_bypass_button").performTouchInput {
        down(center)
      }
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.viewerAbBypassHeld)
      composeRule.onNodeWithTag("viewer_ab_bypass_badge").assertIsDisplayed()

      composeRule.onNodeWithTag("viewer_ab_bypass_button").performTouchInput {
        up()
      }
      composeRule.waitForIdle()
      assertFalse(vm.uiState.value.viewerAbBypassHeld)

      // Toggle Split A/B view and drag vertical divider
      composeRule.onNodeWithTag("viewer_ab_split_toggle").performClick()
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.viewerAbSplitEnabled)
      composeRule.onNodeWithTag("viewer_ab_split_divider").assertIsDisplayed()

      val beforeSplit = vm.uiState.value.viewerAbSplitFraction
      composeRule.onNodeWithTag("viewer_ab_split_divider").performTouchInput {
        swipeRight(startX = centerX, endX = centerX + 80f)
      }
      composeRule.waitForIdle()
      assertTrue(
        "Expected split divider drag to move fraction rightward (before=$beforeSplit, after=${vm.uiState.value.viewerAbSplitFraction})",
        vm.uiState.value.viewerAbSplitFraction > beforeSplit,
      )

      // Toggle Luminance Histogram & Tonal Balance HUD
      composeRule.onNodeWithTag("viewer_histogram_toggle").performClick()
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.viewerHistogramEnabled)
      composeRule.onNodeWithTag("viewer_histogram_overlay").assertIsDisplayed()
      composeRule.onNodeWithTag("viewer_scope_balance_text").assertIsDisplayed()
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `color inspector exposes wheels, custom LUT saving, depth controls, and rack focus keyframes`() {
    val tempRoot = Files.createTempDirectory("frameline_inspector_color_depth_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      vm.openInspectorOrToggleTab(InspectorTab.COLOR_LUT)
      vm.updateSelectedClipColorParams(
        whites = 0.2f,
        blacks = -0.15f,
        vibrance = 0.3f,
        liftX = 0.25f,
        liftY = -0.1f,
        depthBlur = 0.55f,
        fromSliderDrag = false,
      )

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(960.dp, 680.dp)) {
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
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()

      // Save custom LUT preset via UI dialog
      composeRule.onNodeWithTag("save_lut_preset_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("lut_save_name_input").performTextReplacement("Cine Nocturno")
      composeRule.onNodeWithTag("lut_save_confirm_button").performClick()
      composeRule.waitForIdle()
      assertEquals(1, vm.uiState.value.savedLutPresets.size)
      assertEquals("Cine Nocturno", vm.uiState.value.savedLutPresets.first().name)

      // Extended sliders & Lift/Gamma/Gain wheels + curve
      composeRule.onNodeWithTag("adjust_vibrance_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("adjust_whites_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("adjust_blacks_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("color_wheels_section").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("lift_wheel_control").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("gamma_wheel_control").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("gain_wheel_control").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("tonal_curve_preview").performScrollTo().assertIsDisplayed()

      // Depth / Lens Blur section & mode selector + rack focus keyframe button
      composeRule.onNodeWithTag("depth_blur_section").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("adjust_depth_blur_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("depth_mode_LINEAR").performScrollTo().performClick()
      composeRule.waitForIdle()
      assertEquals(DepthBlurMaskMode.LINEAR, vm.uiState.value.selectedClip!!.videoConfig!!.depthBlurMode)

      composeRule.onNodeWithTag("depth_add_rack_focus_kf_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      val kfs = vm.uiState.value.selectedClip!!.keyframes()
      assertEquals(1, kfs.size)
      assertEquals(0.55f, kfs.first().depthBlur ?: -1f, 0.001f)

      // Switch to Keyframes subpanel and verify Rack Focus sliders are exposed
      composeRule.runOnUiThread {
        vm.toggleSubPanel(EditorToolSubPanel.KEYFRAMES)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("keyframes_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("keyframe_depth_blur_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("keyframe_focus_range_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("keyframe_focus_x_slider").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("keyframe_focus_y_slider").performScrollTo().assertIsDisplayed()

      // Delete custom LUT preset through ViewModel removes it cleanly
      val savedId = vm.uiState.value.savedLutPresets.first().id
      composeRule.runOnUiThread {
        vm.applyLutPresetToSelected(savedId)
        vm.deleteCustomLutPreset(savedId)
      }
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.savedLutPresets.isEmpty())
      assertEquals(null, vm.uiState.value.selectedClip!!.videoConfig!!.lutPresetId)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
