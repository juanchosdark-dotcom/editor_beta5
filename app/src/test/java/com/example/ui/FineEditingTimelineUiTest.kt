package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.SubtitleAnimation
import com.example.core.model.TransitionType
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
@Config(sdk = [36], qualifiers = "w1080dp-h900dp")
class FineEditingTimelineUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `cut transition badge opens editor and applies transition to adjacent clips`() {
    val tempRoot = Files.createTempDirectory("frameline_transition_ui_").toFile()
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
      composeRule.onNodeWithTag("cut_transition_badge_clip_v1_clip_v2").performClick()
      composeRule.waitForIdle()

      composeRule.onNodeWithTag("transition_editor_sheet").assertIsDisplayed()
      composeRule.onNodeWithTag("transition_type_WIPE_LEFT").performClick()
      composeRule.onNodeWithTag("apply_transition_button").performClick()
      composeRule.waitForIdle()

      val clips = vm.uiState.value.timeline.clips
      assertEquals(TransitionType.WIPE_LEFT, clips.first { it.clipId == "clip_v1" }.videoConfig?.transitionOut?.type)
      assertEquals(TransitionType.WIPE_LEFT, clips.first { it.clipId == "clip_v2" }.videoConfig?.transitionIn?.type)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `subtitle inspector edits text timing styles split merge and auto-subtitles`() {
    val tempRoot = Files.createTempDirectory("frameline_subtitle_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_sub1")
      vm.seekPlayhead(1_500L)
      vm.openInspectorOrToggleTab(InspectorTab.SPEED_SUBTITLES)

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
      composeRule.onNodeWithTag("inspector_subtitle_text_field").performScrollTo().performTextReplacement("Hola mundo edición fina")
      composeRule.onNodeWithTag("subtitle_anim_POP").performScrollTo().performClick()
      composeRule.onNodeWithTag("subtitle_end_plus_btn").performScrollTo().performClick()
      composeRule.waitForIdle()

      val editedSub = vm.uiState.value.timeline.clips.first { it.clipId == "clip_sub1" }
      assertEquals("Hola mundo edición fina", editedSub.subtitleConfig?.text)
      assertEquals(SubtitleAnimation.POP, editedSub.subtitleConfig?.animation)
      assertEquals(4_400L, editedSub.timelineEndMs)

      composeRule.onNodeWithTag("inspector_split_subtitle_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      assertEquals(2, vm.uiState.value.timeline.clips.count { it.kind == com.example.core.model.ClipKind.SUBTITLE })

      composeRule.onNodeWithTag("inspector_merge_subtitle_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      assertEquals(1, vm.uiState.value.timeline.clips.count { it.kind == com.example.core.model.ClipKind.SUBTITLE })

      composeRule.onNodeWithTag("inspector_auto_subtitles_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      assertTrue(vm.uiState.value.timeline.clips.count { it.kind == com.example.core.model.ClipKind.SUBTITLE } >= 2)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `speed curve inspector applies ramp preset recalculates duration and toggles audio pitch`() {
    val tempRoot = Files.createTempDirectory("frameline_speed_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      vm.toggleSubPanel(EditorToolSubPanel.ADJUSTMENTS)
      vm.selectInspectorTab(InspectorTab.SPEED_SUBTITLES)

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
      val originalDur = vm.uiState.value.timeline.clips.first { it.clipId == "clip_v1" }.durationMs
      composeRule.onNodeWithTag("speed_curve_canvas").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("speed_recalculated_duration_badge").performScrollTo().assertIsDisplayed()

      composeRule.onNodeWithTag("speed_ramp_preset_bullet").performScrollTo().performClick()
      composeRule.onNodeWithTag("preserve_pitch_switch").performScrollTo().performClick()
      composeRule.waitForIdle()

      val ramped = vm.uiState.value.timeline.clips.first { it.clipId == "clip_v1" }
      assertTrue(ramped.hasVariableSpeed)
      assertTrue(ramped.durationMs != originalDur)
      assertFalse(ramped.videoConfig?.preserveAudioPitch ?: true)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
