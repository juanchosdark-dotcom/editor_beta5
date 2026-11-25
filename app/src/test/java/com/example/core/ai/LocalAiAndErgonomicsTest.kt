package com.example.core.ai

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.ThermalTier
import com.example.core.ai.local.DeviceResourceGuard
import com.example.core.ai.local.LocalRhythmAndSilenceAssistant
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.ergonomics.KeyboardShortcutMapper
import com.example.core.ergonomics.ShortcutAction
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.core.ergonomics.ViewerViewportState
import com.example.core.model.ClipKind
import com.example.core.model.MarkerColor
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiAndErgonomicsTest {

  @Test
  fun `resource guard blocks when module disabled or thermal severe or low memory`() {
    val disabledReason =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = false),
        thermalTier = ThermalTier.NORMAL,
        isLowMemory = false,
        isPowerSave = false,
        batteryPercent = 90,
      )
    assertNotNull(disabledReason)

    val thermalBlocked =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, maxAllowedThermalTier = ThermalTier.MODERATE),
        thermalTier = ThermalTier.SEVERE,
        isLowMemory = false,
        isPowerSave = false,
        batteryPercent = 85,
      )
    assertNotNull(thermalBlocked)

    val lowMemBlocked =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true),
        thermalTier = ThermalTier.NORMAL,
        isLowMemory = true,
        isPowerSave = false,
        batteryPercent = 85,
      )
    assertNotNull(lowMemBlocked)
  }

  @Test
  fun `resource guard respects battery saver override and allows normal conditions`() {
    val blockedOnSaver =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, allowOnBatterySaver = false),
        thermalTier = ThermalTier.MODERATE,
        isLowMemory = false,
        isPowerSave = true,
        batteryPercent = 60,
      )
    assertNotNull(blockedOnSaver)

    val allowedWithOverride =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, allowOnBatterySaver = true),
        thermalTier = ThermalTier.MODERATE,
        isLowMemory = false,
        isPowerSave = true,
        batteryPercent = 10,
      )
    assertNull(allowedWithOverride)
  }

  @Test
  fun `beat onset detector finds strong peaks and enforces cooldown spacing`() {
    // 40 windows of 20ms = 800ms; baseline RMS = 0.05f with spikes at 300ms, 340ms (within cooldown), and 700ms
    val windows =
      (0 until 40).map { idx ->
        val ts = idx * 20L
        val rms =
          when (ts) {
            300L -> 0.35f
            340L -> 0.40f // skipped because minBeatSpacingMs = 350ms
            700L -> 0.38f
            else -> 0.05f
          }
        ts to rms
      }

    val beats =
      LocalRhythmAndSilenceAssistant.detectBeatOnsetsFromRmsWindows(
        rmsWindows = windows,
        sensitivityMultiplier = 1.65f,
        minBeatSpacingMs = 350L,
      )

    assertEquals(2, beats.size)
    assertEquals(300L, beats[0].positionMs)
    assertEquals(700L, beats[1].positionMs)
    assertTrue(beats[0].strength > 1.65f)
  }

  @Test
  fun `keyboard shortcut mapper resolves timeline actions accurately`() {
    assertEquals(
      ShortcutAction.PLAY_PAUSE,
      KeyboardShortcutMapper.mapKey(Key.Spacebar, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.SPLIT_AT_PLAYHEAD,
      KeyboardShortcutMapper.mapKey(Key.B, isCtrlPressed = true, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.SPLIT_AT_PLAYHEAD,
      KeyboardShortcutMapper.mapKey(Key.S, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.UNDO,
      KeyboardShortcutMapper.mapKey(Key.Z, isCtrlPressed = true, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.STEP_BACK_FRAME,
      KeyboardShortcutMapper.mapKey(Key.DirectionLeft, isCtrlPressed = false, isShiftPressed = true),
    )
    assertEquals(
      ShortcutAction.STEP_FORWARD_COARSE,
      KeyboardShortcutMapper.mapKey(Key.L, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.DELETE_CLIP,
      KeyboardShortcutMapper.mapKey(Key.Delete, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.ZOOM_IN,
      KeyboardShortcutMapper.mapKey(Key.Plus, isCtrlPressed = false, isShiftPressed = false),
    )
  }

  @Test
  fun `ergonomics helper scales stylus scrubbing and snaps to markers and clip boundaries`() {
    val state =
      TimelineState(
        tracks = listOf(Track(trackId = "t1", type = TrackType.VIDEO, order = 0, name = "V1")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "t1",
              kind = ClipKind.VIDEO,
              label = "Clip 1",
              timelineStartMs = 1_000L,
              timelineEndMs = 4_000L,
            ),
          ),
        markers =
          listOf(
            TimelineMarker(
              markerId = "m1",
              positionMs = 2_500L,
              label = "Beat",
              color = MarkerColor.AMBER,
            ),
          ),
      )

    // Within 160ms touch threshold -> snaps to marker 2500ms
    val snappedTouch =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = 2_420L,
        state = state,
        thresholdMs = InputDeviceMode.TOUCH.snapThresholdMs,
        snapEnabled = true,
      )
    assertEquals(2_500L, snappedTouch)

    // Outside 45ms stylus threshold -> stays precise at 2420ms
    val preciseStylus =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = 2_420L,
        state = state,
        thresholdMs = InputDeviceMode.STYLUS.snapThresholdMs,
        snapEnabled = true,
      )
    assertEquals(2_420L, preciseStylus)

    // Stylus scales drag delta by 0.25x compared to touch 1.0x
    val touchDeltaMs =
      TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
        deltaPx = 100f,
        pxPerSecond = 100f,
        inputMode = InputDeviceMode.TOUCH,
      )
    val stylusDeltaMs =
      TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
        deltaPx = 100f,
        pxPerSecond = 100f,
        inputMode = InputDeviceMode.STYLUS,
      )
    assertEquals(1_000L, touchDeltaMs)
    assertEquals(250L, stylusDeltaMs)

    assertEquals(
      InputDeviceMode.STYLUS,
      InputDeviceMode.fromPointerType(PointerType.Stylus),
    )
    assertEquals(
      InputDeviceMode.STYLUS,
      InputDeviceMode.fromPointerType(PointerType.Eraser),
    )
    assertEquals(
      TimelineViewportErgonomics.MAX_ZOOM,
      TimelineErgonomicsHelper.clampZoom(3.5f, 2.0f),
      0.001f,
    )
    assertEquals(
      TimelineViewportErgonomics.MIN_ZOOM,
      TimelineErgonomicsHelper.clampZoom(0.3f, 0.2f),
      0.001f,
    )
    assertEquals(
      1.0f,
      TimelineErgonomicsHelper.clampZoom(Float.NaN, Float.POSITIVE_INFINITY),
      0.001f,
    )
  }

  @Test
  fun `ergonomics helper preserves focal point scroll offset and computes fit zoom scale`() {
    // At zoom=1.0, scroll=200px, focal point=150px -> timeline px = 350px.
    // Zooming to 2.0x should place that same timeline moment at scroll=500px (500 + 150 = 650? Wait: (200+150)*2 - 150 = 550px!).
    val zoomedInScroll =
      TimelineErgonomicsHelper.calculateFocalAnchoredScrollPx(
        currentScrollPx = 200f,
        focalViewportX = 150f,
        oldZoom = 1.0f,
        newZoom = 2.0f,
      )
    assertEquals(550, zoomedInScroll)

    // Zooming back out 2.0 -> 1.0 returns to 200px
    val zoomedOutScroll =
      TimelineErgonomicsHelper.calculateFocalAnchoredScrollPx(
        currentScrollPx = zoomedInScroll.toFloat(),
        focalViewportX = 150f,
        oldZoom = 2.0f,
        newZoom = 1.0f,
      )
    assertEquals(200, zoomedOutScroll)

    // Fit zoom for a 20s timeline inside 300dp viewport: 300 * 0.94 / 20 = 14.1 dp/s -> 14.1 / 56 = ~0.2518f
    val fitLong =
      TimelineErgonomicsHelper.calculateFitZoomScale(
        viewportWidthDp = 300f,
        totalDurationMs = 20_000L,
      )
    assertEquals((300f * 0.94f / 20f) / 56f, fitLong, 0.005f)

    // Very short timeline clamps cleanly at MAX_ZOOM (5.0f)
    val fitShort =
      TimelineErgonomicsHelper.calculateFitZoomScale(
        viewportWidthDp = 800f,
        totalDurationMs = 1_000L,
      )
    assertEquals(TimelineViewportErgonomics.MAX_ZOOM, fitShort, 0.001f)
  }

  @Test
  fun `viewmodel fitTimelineZoom adapts scale cleanly within ergonomic bounds`() {
    val tempRoot = java.nio.file.Files.createTempDirectory("frameline_fit_zoom_").toFile()
    try {
      val vm = com.example.ui.editor.TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.fitTimelineZoom(viewportWidthDp = 240f)
      val scale = vm.uiState.value.ergonomics.zoomScale
      assertTrue(scale in TimelineViewportErgonomics.MIN_ZOOM..TimelineViewportErgonomics.MAX_ZOOM)
      assertTrue(scale < 1.0f)

      vm.setTimelineZoom(1.0f)
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `viewer viewport helper clamps zoom and applies magnetic center snap accurately`() {
    assertEquals(
      ViewerViewportState.MAX_ZOOM,
      TimelineErgonomicsHelper.clampViewerZoom(3.2f, 2.0f),
      0.001f,
    )
    assertEquals(
      ViewerViewportState.MIN_ZOOM,
      TimelineErgonomicsHelper.clampViewerZoom(1.1f, 0.4f),
      0.001f,
    )
    assertEquals(
      ViewerViewportState.MIN_ZOOM,
      TimelineErgonomicsHelper.clampViewerZoom(Float.NaN, Float.POSITIVE_INFINITY),
      0.001f,
    )

    // Within center threshold -> snaps X and Y to 0f
    val snappedBoth =
      TimelineErgonomicsHelper.applyViewerCenterSnap(
        rawPanNormalizedX = 0.02f,
        rawPanNormalizedY = -0.03f,
        zoomScale = 2.0f,
        magneticCenterSnap = true,
      )
    assertEquals(0f, snappedBoth.panNormalizedX, 0.0001f)
    assertEquals(0f, snappedBoth.panNormalizedY, 0.0001f)
    assertTrue(snappedBoth.snappedToCenterX)
    assertTrue(snappedBoth.snappedToCenterY)

    // Outside threshold on X, inside on Y -> snaps Y only
    val snappedYOnly =
      TimelineErgonomicsHelper.applyViewerCenterSnap(
        rawPanNormalizedX = 0.25f,
        rawPanNormalizedY = 0.01f,
        zoomScale = 2.0f,
        magneticCenterSnap = true,
      )
    assertEquals(0.25f, snappedYOnly.panNormalizedX, 0.0001f)
    assertEquals(0f, snappedYOnly.panNormalizedY, 0.0001f)
    assertFalse(snappedYOnly.snappedToCenterX)
    assertTrue(snappedYOnly.snappedToCenterY)

    // With magnetic snap disabled -> preserves exact offset and clamps to maxPan
    val unsnappedClamped =
      TimelineErgonomicsHelper.applyViewerCenterSnap(
        rawPanNormalizedX = 0.02f,
        rawPanNormalizedY = 5.0f,
        zoomScale = 2.0f,
        magneticCenterSnap = false,
      )
    assertEquals(0.02f, unsnappedClamped.panNormalizedX, 0.0001f)
    assertEquals(0.75f, unsnappedClamped.panNormalizedY, 0.0001f)
    assertFalse(unsnappedClamped.snappedToCenterX)
    assertFalse(unsnappedClamped.snappedToCenterY)
  }

  @Test
  fun `viewmodel toggles viewer guides, zoom pan transform, and reset cleanly`() {
    val tempRoot = java.nio.file.Files.createTempDirectory("frameline_viewer_vp_").toFile()
    try {
      val vm = com.example.ui.editor.TimelineSkeletonViewModel(projectRootDir = tempRoot)
      assertFalse(vm.uiState.value.viewerViewport.isZoomPanEnabled)
      assertFalse(vm.uiState.value.viewerViewport.showRuleOfThirds)
      assertFalse(vm.uiState.value.viewerViewport.showSafeMargins)
      assertTrue(vm.uiState.value.viewerViewport.magneticCenterSnap)

      vm.toggleViewerZoomPanMode()
      vm.toggleViewerRuleOfThirds()
      vm.toggleViewerSafeMargins()
      assertTrue(vm.uiState.value.viewerViewport.isZoomPanEnabled)
      assertTrue(vm.uiState.value.viewerViewport.showRuleOfThirds)
      assertTrue(vm.uiState.value.viewerViewport.showSafeMargins)

      vm.setViewerTransform(zoomScale = 2.2f, panNormalizedX = 0.01f, panNormalizedY = 0.18f)
      val transformed = vm.uiState.value.viewerViewport
      assertEquals(2.2f, transformed.zoomScale, 0.001f)
      assertEquals(0f, transformed.panNormalizedX, 0.0001f)
      assertEquals(0.18f, transformed.panNormalizedY, 0.0001f)
      assertTrue(transformed.isSnappedToCenterX)
      assertFalse(transformed.isSnappedToCenterY)
      assertTrue(transformed.isTransformed)

      vm.toggleViewerMagneticCenterSnap()
      assertFalse(vm.uiState.value.viewerViewport.magneticCenterSnap)

      vm.resetViewerViewport()
      val reset = vm.uiState.value.viewerViewport
      assertEquals(1.0f, reset.zoomScale, 0.001f)
      assertEquals(0f, reset.panNormalizedX, 0.0001f)
      assertEquals(0f, reset.panNormalizedY, 0.0001f)
      assertFalse(reset.isTransformed)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
