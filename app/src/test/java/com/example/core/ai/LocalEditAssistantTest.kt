package com.example.core.ai

import com.example.core.ai.api.ClipLumaMetrics
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.ai.api.EditFixCategory
import com.example.core.ai.api.EditFixStatus
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.LocalEditAnalysisReport
import com.example.core.ai.api.LocalEditAssistEngine
import com.example.core.ai.api.LocalModelProfile
import com.example.core.ai.api.ResourceGuard
import com.example.core.ai.api.ResourceSnapshot
import com.example.core.ai.api.ThermalTier
import com.example.core.ai.local.DeviceResourceGuard
import com.example.core.ai.local.LocalEditAssistant
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.ui.editor.TimelineSkeletonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalEditAssistantTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `device profile scales sampling budget down for low ram and low memory conditions`() {
    val lowRamProfile =
      DeviceResourceGuard.buildDeviceProfile(
        snapshot =
          ResourceSnapshot(
            thermalTier = ThermalTier.NORMAL,
            availableRamMb = 650L,
            isLowMemory = false,
            canRunInference = true,
          ),
        totalRamMb = 2_048L,
        isLowRamDevice = true,
        settings = LocalAiSettings(enabled = true, profile = LocalModelProfile.LIGHTWEIGHT_DSP),
      )
    assertEquals(DeviceMemoryTier.CONSERVATIVE, lowRamProfile.tier)
    assertEquals(3, lowRamProfile.maxSampledFrames)
    assertEquals(240, lowRamProfile.maxAnalysisWidthPx)
    assertTrue(lowRamProfile.allowFrameSampling)

    val blockedSamplingProfile =
      DeviceResourceGuard.buildDeviceProfile(
        snapshot =
          ResourceSnapshot(
            thermalTier = ThermalTier.NORMAL,
            availableRamMb = 250L,
            isLowMemory = true,
            canRunInference = false,
            blockReason = "Memoria RAM insuficiente",
          ),
        totalRamMb = 2_048L,
        isLowRamDevice = true,
        settings = LocalAiSettings(enabled = true),
      )
    assertFalse(blockedSamplingProfile.allowFrameSampling)
    assertEquals(0, blockedSamplingProfile.maxSampledFrames)

    val highTierProfile =
      DeviceResourceGuard.buildDeviceProfile(
        snapshot =
          ResourceSnapshot(
            thermalTier = ThermalTier.NORMAL,
            availableRamMb = 3_200L,
            isLowMemory = false,
            canRunInference = true,
          ),
        totalRamMb = 8_192L,
        isLowRamDevice = false,
        settings = LocalAiSettings(enabled = true, profile = LocalModelProfile.BALANCED_LOCAL),
      )
    assertEquals(DeviceMemoryTier.PERFORMANCE, highTierProfile.tier)
    assertEquals(8, highTierProfile.maxSampledFrames)
    assertEquals(480, highTierProfile.maxAnalysisWidthPx)
  }

  @Test
  fun `assistant generates actionable color cut and keyframe suggestions for dark ungraded clip`() {
    val timeline = TimelineSkeletonViewModel.createInitialSampleTimeline()
    val clipV2 = timeline.clips.first { it.clipId == "clip_v2" }
    val projectMetrics = LocalEditAssistant.collectProjectMetrics(timeline, clipV2)
    val darkMetrics =
      ClipLumaMetrics(
        sampledFrames = 5,
        averageLuma = 0.25f,
        contrastSpread = 0.14f,
        darkFrameRatio = 0.80f,
        highlightsClippedRatio = 0.0f,
      )
    val profile =
      DeviceProfile(
        tier = DeviceMemoryTier.BALANCED,
        maxSampledFrames = 5,
        maxAnalysisWidthPx = 360,
        maxSuggestedFixes = 4,
      )

    val suggestions =
      LocalEditAssistant.generateSuggestions(
        timeline = timeline,
        targetClip = clipV2,
        projectMetrics = projectMetrics,
        clipMetrics = darkMetrics,
        deviceProfile = profile,
      )

    assertTrue(suggestions.any { it.category == EditFixCategory.COLOR_LIGHT })
    assertTrue(suggestions.any { it.category == EditFixCategory.CUT_MARKERS })
    assertTrue(suggestions.any { it.category == EditFixCategory.KEYFRAME_MOTION })
  }

  @Test
  fun `viewmodel applies and dismisses local edit suggestions onto shared editor state`() =
    runTest {
      val fakeGuard = FakeResourceGuard()
      val assistant = LocalEditAssistant(fakeGuard, workDispatcher = testDispatcher)
      val vm =
        TimelineSkeletonViewModel(
          resourceGuard = fakeGuard,
          localEditAssistant = assistant,
        )

      vm.setLocalAiEnabled(true)
      vm.selectClip("clip_v2")
      val initialMarkerCount = vm.uiState.value.timeline.markers.size

      vm.analyzeCurrentClipWithAssistant()
      advanceUntilIdle()

      val report = vm.uiState.value.editAnalysisReport
      assertNotNull(report)
      val suggestions = report!!.suggestions
      assertTrue(suggestions.isNotEmpty())

      val keyframeFix = suggestions.first { it.category == EditFixCategory.KEYFRAME_MOTION }
      val cutFix = suggestions.first { it.category == EditFixCategory.CUT_MARKERS }

      vm.applyEditSuggestion(keyframeFix.suggestionId)
      val updatedClipV2 = vm.uiState.value.timeline.clips.first { it.clipId == "clip_v2" }
      assertEquals(2, updatedClipV2.videoConfig?.keyframes?.size)
      assertEquals(
        EditFixStatus.APPLIED,
        vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == keyframeFix.suggestionId }?.status,
      )

      vm.dismissEditSuggestion(cutFix.suggestionId)
      assertEquals(
        EditFixStatus.DISMISSED,
        vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == cutFix.suggestionId }?.status,
      )
      assertEquals(initialMarkerCount, vm.uiState.value.timeline.markers.size)

      // Applying undo reverts the applied fix cleanly
      vm.undo()
      val revertedClipV2 = vm.uiState.value.timeline.clips.first { it.clipId == "clip_v2" }
      assertTrue(revertedClipV2.videoConfig?.keyframes.isNullOrEmpty())

      // Re-apply and revert directly via revertEditSuggestion (recorded in Undo/Redo)
      vm.redo()
      assertEquals(2, vm.uiState.value.timeline.clips.first { it.clipId == "clip_v2" }.videoConfig?.keyframes?.size)
      vm.revertEditSuggestion(keyframeFix.suggestionId)
      assertTrue(vm.uiState.value.timeline.clips.first { it.clipId == "clip_v2" }.videoConfig?.keyframes.isNullOrEmpty())
      assertEquals(
        EditFixStatus.PENDING,
        vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == keyframeFix.suggestionId }?.status,
      )
      assertTrue(vm.uiState.value.appliedAiAdjustments.isEmpty())

      // Undo on direct revert restores the applied keyframes again
      vm.undo()
      assertEquals(2, vm.uiState.value.timeline.clips.first { it.clipId == "clip_v2" }.videoConfig?.keyframes?.size)
      assertEquals(1, vm.uiState.value.appliedAiAdjustments.size)
    }

  @Test
  fun `assistant skips raw HDR frame sampling in conservative profile without proxy while generating suggestions`() =
    runTest {
      val lowRamGuard =
        object : ResourceGuard {
          private val snap =
            ResourceSnapshot(
              thermalTier = ThermalTier.NORMAL,
              availableRamMb = 650L,
              isLowMemory = false,
              batteryPercent = 80,
              isPowerSaveMode = false,
              canRunInference = true,
            )
          override val snapshot: StateFlow<ResourceSnapshot> = MutableStateFlow(snap)
          private val profile =
            DeviceResourceGuard.buildDeviceProfile(
              snapshot = snap,
              totalRamMb = 2_048L,
              isLowRamDevice = true,
              settings = LocalAiSettings(enabled = true),
            )
          override val deviceProfile: StateFlow<DeviceProfile> = MutableStateFlow(profile)
          override fun refreshNow(settings: LocalAiSettings) = snap
          override fun resolveDeviceProfile(settings: LocalAiSettings) = profile
          override fun startMonitoring(settings: LocalAiSettings) = Unit
          override fun stopMonitoring() = Unit
        }

      val assistant = LocalEditAssistant(lowRamGuard, workDispatcher = testDispatcher)
      val timeline = TimelineSkeletonViewModel.createInitialSampleTimeline()
      val clipV2 = timeline.clips.first { it.clipId == "clip_v2" }
      val hdrAsset =
        MediaAsset(
          assetId = "asset_hdr",
          type = com.example.core.model.MediaType.VIDEO,
          displayName = "hdr.mp4",
          relativePath = "temp_media/hdr.mp4",
          proxyRelativePath = null,
          fingerprint =
            com.example.core.model.MediaFingerprint(
              byteSize = 10_000L,
              durationMs = 4_500L,
              partialSha256 = "hdr",
              mimeType = "video/mp4",
              isHdr = true,
            ),
        )

      val report =
        assistant.analyzeForEdits(
          timeline = timeline,
          selectedClip = clipV2,
          asset = hdrAsset,
          projectRootDir = File("."),
          settings = LocalAiSettings(enabled = true),
        ).getOrThrow()

      assertEquals(DeviceMemoryTier.CONSERVATIVE, report.deviceProfile.tier)
      assertEquals(null, report.clipMetrics)
      assertTrue(report.memoryGuardNote.contains("HDR", ignoreCase = true))
      assertTrue(report.suggestions.isNotEmpty())
    }

  private class FakeResourceGuard : ResourceGuard {
    private val _snapshot =
      MutableStateFlow(
        ResourceSnapshot(
          thermalTier = ThermalTier.NORMAL,
          availableRamMb = 1_800L,
          isLowMemory = false,
          batteryPercent = 85,
          isPowerSaveMode = false,
          canRunInference = true,
          blockReason = null,
        ),
      )
    override val snapshot: StateFlow<ResourceSnapshot> = _snapshot

    private val _deviceProfile = MutableStateFlow(DeviceProfile())
    override val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile

    override fun refreshNow(settings: LocalAiSettings): ResourceSnapshot {
      val reason =
        DeviceResourceGuard.evaluatePolicy(
          settings = settings,
          thermalTier = ThermalTier.NORMAL,
          isLowMemory = false,
          isPowerSave = false,
          batteryPercent = 85,
        )
      val nextSnap = _snapshot.value.copy(canRunInference = reason == null, blockReason = reason)
      _snapshot.value = nextSnap
      _deviceProfile.value =
        DeviceResourceGuard.buildDeviceProfile(
          snapshot = nextSnap,
          totalRamMb = 4_096L,
          isLowRamDevice = false,
          settings = settings,
        )
      return nextSnap
    }

    override fun resolveDeviceProfile(settings: LocalAiSettings): DeviceProfile {
      refreshNow(settings)
      return _deviceProfile.value
    }

    override fun startMonitoring(settings: LocalAiSettings) {
      refreshNow(settings)
    }

    override fun stopMonitoring() = Unit
  }
}
