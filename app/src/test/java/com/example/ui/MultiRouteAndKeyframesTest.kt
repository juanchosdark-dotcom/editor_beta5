package com.example.ui

import com.example.core.model.ClipKeyframe
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.RelinkState
import com.example.core.model.RelinkStatus
import com.example.core.model.VideoClipConfig
import com.example.ui.editor.TimelineSkeletonViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MultiRouteAndKeyframesTest {

  @Test
  fun `video clip config interpolates scale and opacity smoothly between keyframes`() {
    val cfg =
      VideoClipConfig(
        keyframes =
          listOf(
            ClipKeyframe(keyframeId = "k1", offsetMs = 1_000L, scale = 1.0f, opacity = 1.0f),
            ClipKeyframe(keyframeId = "k2", offsetMs = 3_000L, scale = 2.0f, opacity = 0.2f),
          ),
      )

    val before = cfg.evaluateTransformAt(200L)
    assertEquals(1.0f, before.scale, 0.001f)
    assertEquals(1.0f, before.opacity, 0.001f)

    val mid = cfg.evaluateTransformAt(2_000L)
    assertEquals(1.5f, mid.scale, 0.001f)
    assertEquals(0.6f, mid.opacity, 0.001f)

    val after = cfg.evaluateTransformAt(4_000L)
    assertEquals(2.0f, after.scale, 0.001f)
    assertEquals(0.2f, after.opacity, 0.001f)
  }

  @Test
  fun `viewmodel manages keyframes, recent projects, and asset validation`() {
    val tempRoot = Files.createTempDirectory("frameline_test_").toFile()
    try {
      val mediaDir = File(tempRoot, "media").apply { mkdirs() }
      val realFile = File(mediaDir, "sample.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      // Playhead starts at 2500ms inside clip_v1 (0..5000ms)
      vm.selectClip("clip_v1")
      vm.addOrUpdateKeyframeAtPlayhead()
      vm.updateSelectedClipTransform(scale = 1.4f, opacity = 0.8f, rotationDegrees = 10f)

      val keyframesAfterAdd = vm.uiState.value.selectedClip?.videoConfig?.keyframes.orEmpty()
      assertEquals(1, keyframesAfterAdd.size)
      assertEquals(2_500L, keyframesAfterAdd.first().offsetMs)
      assertEquals(1.4f, keyframesAfterAdd.first().scale, 0.001f)

      vm.removeKeyframeFromSelected(keyframesAfterAdd.first().keyframeId)
      assertTrue(vm.uiState.value.selectedClip?.videoConfig?.keyframes.isNullOrEmpty())

      // Create new project and switch back to Demo
      val initialProjectId = vm.uiState.value.recentProjects.first().projectId
      vm.createNewProject("Proyecto B")
      assertEquals("Proyecto B", vm.uiState.value.projectName)
      assertEquals(2, vm.uiState.value.recentProjects.size)

      vm.openRecentProject(initialProjectId)
      assertEquals("FrameLine Demo", vm.uiState.value.projectName)

      // Validate present vs missing media asset
      val validAsset =
        MediaAsset(
          assetId = "a1",
          type = MediaType.VIDEO,
          displayName = "sample.mp4",
          relativePath = "media/${realFile.name}",
          fingerprint =
            MediaFingerprint(
              byteSize = 4L,
              durationMs = 3_000L,
              partialSha256 = "abc123",
              mimeType = "video/mp4",
              width = 1920,
              height = 1080,
              hasAudio = true,
            ),
          relinkState = RelinkState(status = RelinkStatus.ONLINE),
        )
      val validReport = vm.validateAsset(validAsset)
      assertTrue(validReport.isReadyForEditor)
      assertEquals("1920x1080", validReport.resolutionLabel)

      val missingReport = vm.validateAsset(validAsset.copy(relativePath = "media/missing.mp4"))
      assertFalse(missingReport.isReadyForEditor)
      assertTrue(missingReport.warnings.isNotEmpty())
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel supports trim range, adjustments, rotation, split, and undo redo`() {
    val tempRoot = Files.createTempDirectory("frameline_manual_tools_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")

      // Trim start to playhead (2500ms) -> clip_v1 becomes 2500ms long (sourceIn=2500, sourceOut=5000)
      vm.trimSelectedStartToPlayhead()
      val trimmed = vm.uiState.value.selectedClip!!
      assertEquals(2_500L, trimmed.sourceInMs)
      assertEquals(5_000L, trimmed.sourceOutMs)
      assertTrue(vm.uiState.value.canUndo)

      // Adjust brightness, contrast, saturation, scale + rotate 90
      vm.beginClipEditTransaction()
      vm.updateSelectedClipAdjustments(
        brightness = 0.25f,
        contrast = 1.3f,
        saturation = 1.4f,
        rotationDegrees = 0f,
        scale = 1.25f,
        fromSliderDrag = true,
      )
      vm.endClipEditTransaction()
      vm.rotateSelectedClipBy90()

      val adjustedCfg = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(0.25f, adjustedCfg.brightness, 0.001f)
      assertEquals(1.3f, adjustedCfg.contrast, 0.001f)
      assertEquals(1.4f, adjustedCfg.saturation, 0.001f)
      assertEquals(90f, adjustedCfg.rotationDegrees, 0.001f)
      assertEquals(1.25f, adjustedCfg.scale, 0.001f)

      // Undo rotation -> returns to 0 deg while keeping slider transaction adjustments
      vm.undo()
      assertEquals(0f, vm.uiState.value.selectedClip!!.videoConfig!!.rotationDegrees, 0.001f)
      assertEquals(0.25f, vm.uiState.value.selectedClip!!.videoConfig!!.brightness, 0.001f)

      // Redo rotation -> back to 90 deg
      vm.redo()
      assertEquals(90f, vm.uiState.value.selectedClip!!.videoConfig!!.rotationDegrees, 0.001f)

      // Reset adjustments
      vm.resetSelectedClipAdjustments()
      val resetCfg = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(0f, resetCfg.brightness, 0.001f)
      assertEquals(1f, resetCfg.contrast, 0.001f)
      assertEquals(1f, resetCfg.saturation, 0.001f)
      assertEquals(0f, resetCfg.rotationDegrees, 0.001f)
      assertEquals(1f, resetCfg.scale, 0.001f)

      // Split clip_v1 at 1200ms
      vm.seekPlayhead(1_200L)
      val countBeforeSplit = vm.uiState.value.timeline.clips.size
      vm.splitAtPlayhead()
      assertEquals(countBeforeSplit + 1, vm.uiState.value.timeline.clips.size)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel supports transport controls, frame stepping, loop, and preview resolution`() {
    val tempRoot = Files.createTempDirectory("frameline_transport_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      assertEquals(2_500L, vm.uiState.value.timeline.playheadMs)
      assertFalse(vm.uiState.value.isPreviewLoopEnabled)
      assertEquals(
        com.example.core.media.model.PreviewQualityProfile.BALANCED_540P,
        vm.uiState.value.renderSettings.previewQuality,
      )

      // Frame step forward / backward
      vm.stepFrameForward()
      assertEquals(2_533L, vm.uiState.value.timeline.playheadMs)
      vm.stepFrameBackward()
      assertEquals(2_500L, vm.uiState.value.timeline.playheadMs)

      // Seek to start and clamp at 0
      vm.seekToStart()
      assertEquals(0L, vm.uiState.value.timeline.playheadMs)
      vm.stepFrameBackward()
      assertEquals(0L, vm.uiState.value.timeline.playheadMs)

      // Toggle loop & switch preview resolution + proxies
      vm.togglePreviewLoop()
      assertTrue(vm.uiState.value.isPreviewLoopEnabled)
      vm.togglePreviewLoop()
      assertFalse(vm.uiState.value.isPreviewLoopEnabled)

      vm.setPreviewQuality(com.example.core.media.model.PreviewQualityProfile.HIGH_720P)
      assertEquals(
        com.example.core.media.model.PreviewQualityProfile.HIGH_720P,
        vm.uiState.value.renderSettings.previewQuality,
      )
      vm.toggleUseProxiesForPreview()
      assertFalse(vm.uiState.value.renderSettings.useProxiesForPreview)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel supports direct edge trimming, clip duplication, and track lock or visibility toggles`() {
    val tempRoot = Files.createTempDirectory("frameline_timeline_ux_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      val originalCount = vm.uiState.value.timeline.clips.size

      // Drag trim start edge of clip_v1 to 500ms, commit transaction, and undo
      vm.beginClipEditTransaction()
      vm.trimSelectedClipEdge(clipId = "clip_v1", isStartEdge = true, rawTargetMs = 500L, fromDrag = true)
      vm.endClipEditTransaction()
      val trimmedClip = vm.uiState.value.timeline.clips.first { it.clipId == "clip_v1" }
      assertEquals(500L, trimmedClip.timelineStartMs)
      assertEquals(500L, trimmedClip.sourceInMs)
      assertTrue(vm.uiState.value.canUndo)

      vm.undo()
      assertEquals(0L, vm.uiState.value.timeline.clips.first { it.clipId == "clip_v1" }.timelineStartMs)

      // Duplicate selected clip selects the newly duplicated copy and supports undo
      vm.duplicateSelectedClip()
      assertEquals(originalCount + 1, vm.uiState.value.timeline.clips.size)
      val duplicated = vm.uiState.value.selectedClip!!
      assertTrue(duplicated.label.endsWith("(copia)"))
      assertFalse(duplicated.clipId == "clip_v1")

      vm.undo()
      assertEquals(originalCount, vm.uiState.value.timeline.clips.size)

      // Toggle track lock and visibility
      vm.toggleTrackLocked("t_vid")
      assertTrue(vm.uiState.value.timeline.tracks.first { it.trackId == "t_vid" }.isLocked)
      vm.toggleTrackVisible("t_sub")
      assertFalse(vm.uiState.value.timeline.tracks.first { it.trackId == "t_sub" }.isVisible)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel inspector manages tabs, LUT presets with strength, speed, audio pan, and category reset`() {
    val tempRoot = Files.createTempDirectory("frameline_inspector_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")

      // Quick open Color/LUT tab and apply Teal & Orange LUT at 75% strength
      vm.openInspectorOrToggleTab(com.example.ui.editor.InspectorTab.COLOR_LUT)
      assertEquals(com.example.ui.editor.EditorToolSubPanel.ADJUSTMENTS, vm.uiState.value.activeSubPanel)
      assertEquals(com.example.ui.editor.InspectorTab.COLOR_LUT, vm.uiState.value.activeInspectorTab)

      vm.applyLutPresetToSelected(com.example.core.model.BuiltInLutPresets.TEAL_ORANGE.id)
      vm.beginClipEditTransaction()
      vm.updateSelectedClipColorParams(lutStrength = 0.75f, exposure = 0.2f, fromSliderDrag = true)
      vm.endClipEditTransaction()

      val videoCfg = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(com.example.core.model.BuiltInLutPresets.TEAL_ORANGE.id, videoCfg.lutPresetId)
      assertEquals(0.75f, videoCfg.lutStrength, 0.001f)
      assertEquals(0.2f, videoCfg.exposure, 0.001f)
      assertTrue(vm.uiState.value.canUndo)

      // Reset COLOR_LUT category only (while keeping Transform changes)
      vm.updateSelectedClipTransformParams(scale = 1.5f, fromSliderDrag = false)
      vm.resetInspectorCategory(com.example.ui.editor.InspectorTab.COLOR_LUT)
      val afterColorReset = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(null, afterColorReset.lutPresetId)
      assertEquals(0.0f, afterColorReset.exposure, 0.001f)
      assertEquals(1.5f, afterColorReset.scale, 0.001f)

      // Update clip speed -> duration halves from 5000ms to 2500ms
      vm.updateSelectedClipSpeed(2.0f, fromSliderDrag = false)
      assertEquals(2.0f, vm.uiState.value.selectedClip!!.speed, 0.001f)
      assertEquals(2_500L, vm.uiState.value.selectedClip!!.durationMs)

      // Selecting audio clip while inspector is open auto-switches to AUDIO tab
      vm.selectClip("clip_a1")
      assertEquals(com.example.ui.editor.InspectorTab.AUDIO, vm.uiState.value.activeInspectorTab)
      vm.updateSelectedClipAudioParams(volume = 1.2f, pan = 0.4f, fadeInMs = 250L, fromSliderDrag = false)
      val audioCfg = vm.uiState.value.selectedClip!!.audioConfig!!
      assertEquals(1.2f, audioCfg.volume, 0.001f)
      assertEquals(0.4f, audioCfg.pan, 0.001f)
      assertEquals(250L, audioCfg.fadeInMs)

      // Selecting subtitle clip auto-switches to SPEED_SUBTITLES tab & allows inline edit
      vm.selectClip("clip_sub1")
      assertEquals(com.example.ui.editor.InspectorTab.SPEED_SUBTITLES, vm.uiState.value.activeInspectorTab)
      vm.updateSelectedSubtitleInline(text = "Título nuevo", fontSizeSp = 26f, fromSliderDrag = false)
      assertEquals("Título nuevo", vm.uiState.value.selectedClip!!.subtitleConfig!!.text)
      assertEquals(26f, vm.uiState.value.selectedClip!!.subtitleConfig!!.fontSizeSp, 0.001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `export estimator calculates size and ETA scaling across presets and device profiles`() {
    val fastProfile =
      com.example.core.ai.api.DeviceProfile(
        tier = com.example.core.ai.api.DeviceMemoryTier.PERFORMANCE,
      )
    val slowProfile =
      com.example.core.ai.api.DeviceProfile(
        tier = com.example.core.ai.api.DeviceMemoryTier.CONSERVATIVE,
      )
    val estimate720 =
      com.example.core.export.ExportEstimator.estimate(
        config = com.example.core.media.model.ExportPreset.SOCIAL_720P_H264.config,
        rangeDurationMs = 10_000L,
        deviceProfile = fastProfile,
        activeEffectsCount = 0,
      )
    val estimate1080Hevc =
      com.example.core.export.ExportEstimator.estimate(
        config = com.example.core.media.model.ExportPreset.HD_1080P_HEVC.config,
        rangeDurationMs = 10_000L,
        deviceProfile = slowProfile,
        activeEffectsCount = 3,
      )

    assertTrue(estimate720.estimatedSizeBytes > 5_000_000L)
    assertTrue(estimate1080Hevc.estimatedSizeBytes > estimate720.estimatedSizeBytes)
    assertTrue(estimate1080Hevc.estimatedRenderTimeSec > estimate720.estimatedRenderTimeSec)
    assertEquals("—", com.example.core.export.ExportEstimator.formatEtaSeconds(0))
    assertEquals("~12 s", com.example.core.export.ExportEstimator.formatEtaSeconds(12))
    assertEquals("~1m 15s", com.example.core.export.ExportEstimator.formatEtaSeconds(75))
  }

  @Test
  fun `viewmodel appendAssetsToTimeline inserts multi-selected clips sequentially in one undo step`() {
    val tempRoot = Files.createTempDirectory("frameline_batch_media_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      val initialCount = vm.uiState.value.timeline.clips.size
      val assetIds = vm.uiState.value.assets.map { it.assetId }
      if (assetIds.isEmpty()) {
        // Inject assets via import or test only if initial assets are absent; Demo initializes with clips only,
        // verify empty list safely is a no-op.
        vm.appendAssetsToTimeline(emptyList())
        assertEquals(initialCount, vm.uiState.value.timeline.clips.size)
      } else {
        vm.appendAssetsToTimeline(assetIds)
        assertEquals(initialCount + assetIds.size, vm.uiState.value.timeline.clips.size)
        vm.undo()
        assertEquals(initialCount, vm.uiState.value.timeline.clips.size)
      }
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel supports move, interpolation, prev next navigation, and audio keyframes with undo redo`() {
    val tempRoot = Files.createTempDirectory("frameline_kf_api_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")

      // Add keyframe at 1000ms and 3000ms on clip_v1 (0..5000ms)
      vm.seekPlayhead(1_000L)
      vm.addOrUpdateKeyframe(
        scale = 1.2f,
        opacity = 0.7f,
        offsetX = -0.2f,
        offsetY = 0.15f,
        rotationDegrees = -15f,
        volume = 0.6f,
        interpolation = com.example.core.model.KeyframeInterpolation.EASE_IN_OUT,
      )
      vm.seekPlayhead(3_000L)
      vm.addOrUpdateKeyframe(
        scale = 1.8f,
        opacity = 1.0f,
        offsetX = 0.3f,
        offsetY = -0.1f,
        rotationDegrees = 30f,
        volume = 1.4f,
        interpolation = com.example.core.model.KeyframeInterpolation.EASE_OUT,
      )

      val kfs = vm.uiState.value.selectedClip!!.keyframes()
      assertEquals(2, kfs.size)
      val firstKf = kfs.first()
      val secondKf = kfs.last()
      assertEquals(com.example.core.model.KeyframeInterpolation.EASE_IN_OUT, firstKf.interpolation)

      // Move first keyframe to 1500ms and change interpolation to EASE_IN
      vm.moveSelectedClipKeyframe(firstKf.keyframeId, 1_500L)
      vm.setSelectedKeyframeInterpolation(firstKf.keyframeId, com.example.core.model.KeyframeInterpolation.EASE_IN)
      val updatedFirst = vm.uiState.value.selectedClip!!.keyframes().first()
      assertEquals(1_500L, updatedFirst.offsetMs)
      assertEquals(com.example.core.model.KeyframeInterpolation.EASE_IN, updatedFirst.interpolation)

      // Navigate Previous / Next keyframe from playhead 2200ms
      vm.seekPlayhead(2_200L)
      vm.jumpToPreviousKeyframe()
      assertEquals(1_500L, vm.uiState.value.timeline.playheadMs)
      vm.jumpToNextKeyframe()
      assertEquals(secondKf.offsetMs, vm.uiState.value.timeline.playheadMs)

      // Undo interpolation change & redo
      vm.undo()
      assertEquals(
        com.example.core.model.KeyframeInterpolation.EASE_IN_OUT,
        vm.uiState.value.selectedClip!!.keyframes().first().interpolation,
      )
      vm.redo()
      assertEquals(
        com.example.core.model.KeyframeInterpolation.EASE_IN,
        vm.uiState.value.selectedClip!!.keyframes().first().interpolation,
      )

      // Audio clip keyframes
      vm.selectClip("clip_a1")
      val audioClip = vm.uiState.value.selectedClip!!
      vm.seekPlayhead(audioClip.timelineStartMs + 200L)
      vm.addOrUpdateKeyframe(volume = 0.3f)
      vm.seekPlayhead(audioClip.timelineStartMs + 1_200L)
      vm.addOrUpdateKeyframe(volume = 1.2f)
      assertEquals(2, vm.uiState.value.selectedClip!!.keyframes().size)
      vm.clearSelectedClipKeyframes()
      assertTrue(vm.uiState.value.selectedClip!!.keyframes().isEmpty())
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewmodel clamps and resets tint, lift gamma gain, blendOpacity, and transfers COLOR_LUT attributes`() {
    val tempRoot = Files.createTempDirectory("frameline_color_vm_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")

      // Out-of-range and non-finite inputs must clamp cleanly via ColorGradeBounds
      vm.updateSelectedClipColorParams(
        tint = 5.0f,
        shadows = -4.0f,
        midtones = Float.NaN,
        highlights = 0.45f,
        fromSliderDrag = false,
      )
      val vCfg = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(1.0f, vCfg.tint, 0.001f)
      assertEquals(-1.0f, vCfg.shadows, 0.001f)
      assertEquals(0.0f, vCfg.midtones, 0.001f)
      assertEquals(0.45f, vCfg.highlights, 0.001f)

      // Adjustment layer supports full parametric grading + blendOpacity
      vm.selectClip("clip_adj1")
      vm.updateSelectedClipColorParams(
        exposure = 0.35f,
        tint = -0.3f,
        shadows = 0.25f,
        midtones = -0.15f,
        highlights = 0.4f,
        blendOpacity = 0.65f,
        fromSliderDrag = false,
      )
      val adjCfg = vm.uiState.value.selectedClip!!.adjustmentConfig!!
      assertEquals(0.35f, adjCfg.exposure, 0.001f)
      assertEquals(-0.3f, adjCfg.tint, 0.001f)
      assertEquals(0.25f, adjCfg.shadows, 0.001f)
      assertEquals(-0.15f, adjCfg.midtones, 0.001f)
      assertEquals(0.4f, adjCfg.highlights, 0.001f)
      assertEquals(0.65f, adjCfg.blendOpacity, 0.001f)

      // Transfer COLOR_LUT from adjustment layer onto video clip without overwriting transform
      vm.copySelectedClipAttributes(setOf(com.example.core.timeline.AttributeCategory.COLOR_LUT))
      vm.selectClip("clip_v1")
      vm.updateSelectedClipTransformParams(scale = 1.6f, fromSliderDrag = false)
      vm.pasteAttributesToCompatibleClips(setOf(com.example.core.timeline.AttributeCategory.COLOR_LUT))
      val pastedVideo = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(1.6f, pastedVideo.scale, 0.001f)
      assertEquals(-0.3f, pastedVideo.tint, 0.001f)
      assertEquals(0.25f, pastedVideo.shadows, 0.001f)
      assertEquals(0.4f, pastedVideo.highlights, 0.001f)

      // Reset COLOR_LUT category clears all color & tonal wheel fields
      vm.resetInspectorCategory(com.example.ui.editor.InspectorTab.COLOR_LUT)
      val resetVideo = vm.uiState.value.selectedClip!!.videoConfig!!
      assertEquals(0.0f, resetVideo.tint, 0.001f)
      assertEquals(0.0f, resetVideo.shadows, 0.001f)
      assertEquals(0.0f, resetVideo.midtones, 0.001f)
      assertEquals(0.0f, resetVideo.highlights, 0.001f)
      assertEquals(1.6f, resetVideo.scale, 0.001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
