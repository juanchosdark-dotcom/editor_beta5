package com.example.ui.editor

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.core.ai.api.DeviceProfile
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.ViewerViewportState
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.example.core.media.engine.TransitionVisualMath
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.ClipKind
import com.example.core.model.ColorGradeBounds
import com.example.core.model.ColorGradingEngine
import com.example.core.model.ColorScopeSnapshot
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.KeyframeValidator
import com.example.core.model.LutPreset
import com.example.core.model.MediaAsset
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimationMath
import com.example.core.model.TimelineClip
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
internal fun Media3PreviewPlayerCard(
  previewController: PreviewPlayerController?,
  previewState: PreviewPlayerState,
  deviceProfile: DeviceProfile,
  activeVideoClip: TimelineClip?,
  activeAdjustmentClip: TimelineClip?,
  activeSubtitleText: String?,
  playheadMs: Long,
  onRetryPreview: () -> Unit,
  onDismissError: () -> Unit,
  onOpenMediaPicker: () -> Unit,
  modifier: Modifier = Modifier,
  activeSubtitleClip: TimelineClip? = null,
  viewerViewport: ViewerViewportState = ViewerViewportState(),
  onToggleViewerZoomPan: () -> Unit = {},
  onToggleViewerRuleOfThirds: () -> Unit = {},
  onToggleViewerSafeMargins: () -> Unit = {},
  onToggleViewerCenterSnap: () -> Unit = {},
  onViewerTransformChange: (zoomScale: Float, panNormalizedX: Float, panNormalizedY: Float) -> Unit = { _, _, _ -> },
  onResetViewerViewport: () -> Unit = {},
  customLutPresets: List<LutPreset> = emptyList(),
  abBypassHeld: Boolean = false,
  abSplitEnabled: Boolean = false,
  abSplitFraction: Float = 0.5f,
  histogramEnabled: Boolean = false,
  onAbBypassHeldChange: (Boolean) -> Unit = {},
  onToggleAbSplit: () -> Unit = {},
  onAbSplitFractionChange: (Float) -> Unit = {},
  onToggleHistogram: () -> Unit = {},
  overlayClips: List<TimelineClip> = emptyList(),
  selectedOverlayClipId: String? = null,
  projectRootDir: File = File("."),
  assets: List<MediaAsset> = emptyList(),
  onSelectOverlayClip: (String) -> Unit = {},
  onBeginOverlayTransform: () -> Unit = {},
  onUpdateOverlayTransform: (clipId: String, offsetX: Float?, offsetY: Float?, scale: Float?, fromDrag: Boolean) -> Unit = { _, _, _, _, _ -> },
  onEndOverlayTransform: () -> Unit = {},
  fillAvailableHeight: Boolean = false,
  previewHeight: Dp = 180.dp,
  compactDiagnostics: Boolean = false,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  val isInspection = LocalInspectionMode.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val latestViewerViewport by rememberUpdatedState(viewerViewport)
  val latestOnViewerTransformChange by rememberUpdatedState(onViewerTransformChange)
  val latestOnResetViewerViewport by rememberUpdatedState(onResetViewerViewport)
  val latestOnAbBypassHeldChange by rememberUpdatedState(onAbBypassHeldChange)
  val latestAbSplitFraction by rememberUpdatedState(abSplitFraction)
  val latestOnAbSplitFractionChange by rememberUpdatedState(onAbSplitFractionChange)
  var monitorSizePx by remember { mutableStateOf(IntSize.Zero) }
  var isViewerInteracting by remember { mutableStateOf(false) }
  var prevSnapX by remember { mutableStateOf(false) }
  var prevSnapY by remember { mutableStateOf(false) }

  LaunchedEffect(viewerViewport.isSnappedToCenterX, viewerViewport.isSnappedToCenterY, isViewerInteracting) {
    if (isViewerInteracting && viewerViewport.isZoomPanEnabled && viewerViewport.magneticCenterSnap) {
      val newlySnappedX = viewerViewport.isSnappedToCenterX && !prevSnapX
      val newlySnappedY = viewerViewport.isSnappedToCenterY && !prevSnapY
      if (newlySnappedX || newlySnappedY) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
      }
    }
    prevSnapX = viewerViewport.isSnappedToCenterX
    prevSnapY = viewerViewport.isSnappedToCenterY
  }

  // Pause immediately on route change or ON_PAUSE/ON_STOP and restore cleanly after rotation.
  DisposableEffect(lifecycleOwner, previewController) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_PAUSE,
          Lifecycle.Event.ON_STOP, -> previewController?.pausePlayback()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    previewController?.restoreIfNeeded(playheadMs)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      previewController?.pausePlayback()
    }
  }

  val videoCfg = activeVideoClip?.videoConfig
  val adjCfg = activeAdjustmentClip?.adjustmentConfig
  val offsetInClip =
    if (activeVideoClip != null) {
      (playheadMs - activeVideoClip.timelineStartMs).coerceIn(0L, activeVideoClip.durationMs)
    } else {
      0L
    }
  val evaluated = activeVideoClip?.evaluatePropertiesAt(offsetInClip)
  val colorPipeline =
    ColorGradingEngine.buildPipeline(
      videoCfg = videoCfg,
      adjustmentCfg = adjCfg,
      evaluatedClipOpacity = evaluated?.opacity,
      customPresets = customLutPresets,
    )
  val shadowSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.14f, 0.16f, 0.20f, colorPipeline)
  val midtoneSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.32f, 0.38f, 0.48f, colorPipeline)
  val highlightSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.68f, 0.72f, 0.78f, colorPipeline)
  val activeLutTint = colorPipeline.combinedGrade.activeLut
  val scopeSnapshot =
    remember(colorPipeline, abBypassHeld) {
      ColorGradingEngine.computeColorScopes(colorPipeline, bypass = abBypassHeld)
    }
  val adjDepthStage = adjCfg?.let { ColorGradingEngine.extractAdjustmentBaseStage(it) }
  val useAdjDepth = (adjDepthStage?.depthBlur ?: 0f) > 0.005f
  val effectiveDepthBlur =
    if (useAdjDepth) {
      ((evaluated?.depthBlur ?: 0f) + (adjDepthStage?.depthBlur ?: 0f)).coerceIn(0f, 1f)
    } else {
      evaluated?.depthBlur ?: colorPipeline.combinedGrade.depthBlur
    }
  val effectiveFocusX =
    if (useAdjDepth) {
      adjDepthStage?.depthFocusX ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD
    } else {
      evaluated?.depthFocusX ?: colorPipeline.combinedGrade.depthFocusX
    }
  val effectiveFocusY =
    if (useAdjDepth) {
      adjDepthStage?.depthFocusY ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD
    } else {
      evaluated?.depthFocusY ?: colorPipeline.combinedGrade.depthFocusY
    }
  val effectiveFocusRange =
    if (useAdjDepth) {
      adjDepthStage?.depthFocusRange ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE
    } else {
      evaluated?.depthFocusRange ?: colorPipeline.combinedGrade.depthFocusRange
    }
  val effectiveDepthMode =
    if (useAdjDepth) {
      adjDepthStage?.depthBlurMode ?: DepthBlurMaskMode.RADIAL
    } else {
      colorPipeline.combinedGrade.depthBlurMode
    }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = RoundedCornerShape(10.dp),
      border =
        BorderStroke(
          width = 1.dp,
          color =
            if (previewState.uiError != null) {
              MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            } else if (viewerViewport.isZoomPanEnabled) {
              MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
            } else {
              MaterialTheme.colorScheme.outlineVariant
            },
        ),
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.weight(1f) else it },
    ) {
      val viewerGestureModifier =
        if (viewerViewport.isZoomPanEnabled) {
          Modifier
            .pointerInput(viewerViewport.isZoomPanEnabled) {
              detectTapGestures(
                onDoubleTap = { latestOnResetViewerViewport() },
              )
            }
            .pointerInput(viewerViewport.isZoomPanEnabled) {
              awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var virtualZoom = latestViewerViewport.zoomScale
                var virtualRawPanX = latestViewerViewport.panNormalizedX
                var virtualRawPanY = latestViewerViewport.panNormalizedY
                var accumulatedPan = Offset.Zero
                var gestureActive = false
                try {
                  do {
                    val event = awaitPointerEvent()
                    val pressedCount = event.changes.count { it.pressed }
                    if (pressedCount == 0) break
                    val zoomChange = event.calculateZoom()
                    val panDelta = event.calculatePan()
                    val hasZoomDelta =
                      pressedCount >= 2 && zoomChange.isFinite() && zoomChange > 0f && abs(zoomChange - 1f) > 0.002f
                    val hasPanMove =
                      panDelta.x.isFinite() && panDelta.y.isFinite() &&
                        (abs(panDelta.x) > 0.5f || abs(panDelta.y) > 0.5f)
                    if (!gestureActive) {
                      if (pressedCount >= 2) {
                        gestureActive = true
                      } else if (hasPanMove && event.changes.none { it.isConsumed }) {
                        accumulatedPan += panDelta
                        if (accumulatedPan.getDistance() > viewConfiguration.touchSlop) {
                          gestureActive = true
                        }
                      }
                    }
                    if (gestureActive && (hasZoomDelta || hasPanMove)) {
                      isViewerInteracting = true
                      val nextZoom =
                        if (hasZoomDelta) {
                          TimelineErgonomicsHelper.clampViewerZoom(
                            virtualZoom,
                            zoomChange.coerceIn(0.75f, 1.35f),
                          )
                        } else {
                          virtualZoom
                        }
                      val widthPx = monitorSizePx.width.coerceAtLeast(1).toFloat()
                      val heightPx = monitorSizePx.height.coerceAtLeast(1).toFloat()
                      virtualZoom = nextZoom
                      virtualRawPanX += panDelta.x / widthPx
                      virtualRawPanY += panDelta.y / heightPx
                      val maxPan = ((nextZoom - 1f) * 0.5f + 0.25f).coerceIn(0.25f, 1.75f)
                      virtualRawPanX = virtualRawPanX.coerceIn(-maxPan, maxPan)
                      virtualRawPanY = virtualRawPanY.coerceIn(-maxPan, maxPan)
                      latestOnViewerTransformChange(nextZoom, virtualRawPanX, virtualRawPanY)
                      event.changes.forEach { it.consume() }
                    }
                  } while (event.changes.any { it.pressed })
                } finally {
                  isViewerInteracting = false
                }
              }
            }
        } else {
          Modifier
        }

      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .let { if (fillAvailableHeight) it.fillMaxHeight() else it.height(previewHeight) }
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged { monitorSizePx = it }
            .then(viewerGestureModifier)
            .testTag("viewer_monitor_surface"),
      ) {
        // Layer 1: Zoomable / pannable media surface
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .graphicsLayer {
                scaleX = viewerViewport.zoomScale
                scaleY = viewerViewport.zoomScale
                translationX = viewerViewport.panNormalizedX * size.width
                translationY = viewerViewport.panNormalizedY * size.height
              }
              .testTag("viewer_transform_layer"),
        ) {
          if (!isInspection && previewController != null && previewState.hasPlayableMedia) {
            AndroidView(
              factory = { context ->
                PlayerView(context).apply {
                  useController = false
                  resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                  setKeepContentOnPlayerReset(true)
                  player = previewController.getOrCreatePlayer(deviceProfile)
                }
              },
              update = { view ->
                val targetPlayer = previewController.getOrCreatePlayer(deviceProfile)
                if (view.player !== targetPlayer) {
                  view.player = targetPlayer
                }
              },
              onRelease = { view -> runCatching { view.player = null } },
              modifier = Modifier.fillMaxSize().testTag("media3_player_view"),
            )
          } else {
            // Precision offline/standby viewer monitor preserving transform (X/Y, scale, rotation, opacity) & color/A-B preview
            val demoScale = (evaluated?.scale ?: 1f).coerceIn(KeyframeValidator.MIN_SCALE, KeyframeValidator.MAX_SCALE)
            val demoRotation = evaluated?.rotationDegrees ?: 0f
            val demoAlpha = (evaluated?.opacity ?: 1f).coerceIn(0.05f, 1.0f)
            val demoOffsetX = (evaluated?.offsetX ?: 0f).coerceIn(-1.5f, 1.5f)
            val demoOffsetY = (evaluated?.offsetY ?: 0f).coerceIn(-1.5f, 1.5f)
            val ungradedBrush =
              Brush.linearGradient(
                listOf(
                  Color(0.14f, 0.16f, 0.20f, 0.92f),
                  Color(0.32f, 0.38f, 0.48f, 0.85f),
                  Color(0.68f, 0.72f, 0.78f, 0.78f),
                ),
              )
            val shadowStopColor =
              Color(
                red = shadowSample[0].coerceIn(0f, 1f),
                green = shadowSample[1].coerceIn(0f, 1f),
                blue = shadowSample[2].coerceIn(0f, 1f),
                alpha = 0.92f,
              )
            val midtoneStopColor =
              if (activeLutTint != null) {
                Color(
                  red = ((midtoneSample[0] + Color(activeLutTint.midtoneColorArgb).red) * 0.5f).coerceIn(0f, 1f),
                  green = ((midtoneSample[1] + Color(activeLutTint.midtoneColorArgb).green) * 0.5f).coerceIn(0f, 1f),
                  blue = ((midtoneSample[2] + Color(activeLutTint.midtoneColorArgb).blue) * 0.5f).coerceIn(0f, 1f),
                  alpha = 0.85f,
                )
              } else {
                Color(
                  red = midtoneSample[0].coerceIn(0f, 1f),
                  green = midtoneSample[1].coerceIn(0f, 1f),
                  blue = midtoneSample[2].coerceIn(0f, 1f),
                  alpha = 0.85f,
                )
              }
            val highlightStopColor =
              Color(
                red = highlightSample[0].coerceIn(0f, 1f),
                green = highlightSample[1].coerceIn(0f, 1f),
                blue = highlightSample[2].coerceIn(0f, 1f),
                alpha = 0.78f,
              )
            val gradedBrush =
              Brush.linearGradient(
                listOf(
                  shadowStopColor,
                  midtoneStopColor,
                  highlightStopColor,
                ),
              )

            val transitionState =
              if (activeVideoClip != null) {
                TransitionVisualMath.evaluate(
                  transitionIn = videoCfg?.transitionIn,
                  transitionOut = videoCfg?.transitionOut,
                  offsetMs = offsetInClip,
                  clipDurationMs = activeVideoClip.durationMs,
                )
              } else {
                com.example.core.media.engine.ClipTransitionVisualState()
              }
            val wipeVis = transitionState.wipeVisibleFraction.coerceIn(0f, 1f)
            val transitionFlashAlpha = (1f - transitionState.alpha).coerceIn(0f, 1f)
            val transitionFlashColor =
              if (transitionState.flashColorWhiteFraction >= 0.5f) {
                Color.White
              } else {
                Color.Black
              }

            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .padding(8.dp)
                  .graphicsLayer {
                    translationX =
                      demoOffsetX * (size.width * 0.5f) + transitionState.slideOffsetXFraction * size.width
                    translationY = demoOffsetY * (size.height * 0.5f)
                    scaleX = demoScale
                    scaleY = demoScale
                    rotationZ = demoRotation
                    alpha = (demoAlpha * transitionState.alpha).coerceIn(0.02f, 1.0f)
                  }
                  .drawWithContent {
                    if (wipeVis < 0.999f) {
                      if (transitionState.wipeFromRight) {
                        clipRect(left = size.width * (1f - wipeVis)) {
                          this@drawWithContent.drawContent()
                        }
                      } else {
                        clipRect(right = size.width * wipeVis) {
                          this@drawWithContent.drawContent()
                        }
                      }
                    } else {
                      drawContent()
                    }
                  }
                  .clip(RoundedCornerShape(6.dp))
                  .background(if (abBypassHeld) ungradedBrush else gradedBrush),
            ) {
              if (abSplitEnabled && !abBypassHeld) {
                Box(
                  modifier =
                    Modifier
                      .fillMaxSize()
                      .drawWithContent {
                        clipRect(right = size.width * abSplitFraction.coerceIn(0.05f, 0.95f)) {
                          this@drawWithContent.drawContent()
                        }
                      }
                      .background(ungradedBrush),
                )
              }
              if (transitionState.isActive &&
                (transitionState.type == com.example.core.model.TransitionType.FADE_BLACK ||
                  transitionState.type == com.example.core.model.TransitionType.FADE_WHITE) &&
                transitionFlashAlpha > 0.01f
              ) {
                Box(
                  modifier =
                    Modifier
                      .fillMaxSize()
                      .background(transitionFlashColor.copy(alpha = transitionFlashAlpha)),
                )
              }
              Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxSize(),
              ) {
                Box(modifier = Modifier.fillMaxSize())
              }
            }
          }

          // Interactive Viewer Overlays (PNG/JPG with alpha, Stickers, Logos, PiP clips)
          if (overlayClips.isNotEmpty()) {
            val latestOnBeginOverlayTransform by rememberUpdatedState(onBeginOverlayTransform)
            val latestOnUpdateOverlayTransform by rememberUpdatedState(onUpdateOverlayTransform)
            val latestOnEndOverlayTransform by rememberUpdatedState(onEndOverlayTransform)
            val latestOnSelectOverlayClip by rememberUpdatedState(onSelectOverlayClip)
            val resizeHandleCd = stringResource(R.string.cd_viewer_overlay_resize)

            overlayClips.forEach { ovlClip ->
              val ovlCfg = ovlClip.videoConfig ?: return@forEach
              val ovlOffsetMs = (playheadMs - ovlClip.timelineStartMs).coerceIn(0L, ovlClip.durationMs)
              val ovlEval = ovlClip.evaluatePropertiesAt(ovlOffsetMs)
              val ovlScale = ovlEval.scale.coerceIn(0.15f, 3.0f)
              val ovlOffsetX = ovlEval.offsetX.coerceIn(-0.95f, 0.95f)
              val ovlOffsetY = ovlEval.offsetY.coerceIn(-0.95f, 0.95f)
              val isOvlSelected = ovlClip.clipId == selectedOverlayClipId

              val monitorW = monitorSizePx.width.coerceAtLeast(1).toFloat()
              val monitorH = monitorSizePx.height.coerceAtLeast(1).toFloat()
              val baseFraction = 0.56f
              val boxWidthDp = with(density) { (monitorW * baseFraction * ovlScale).coerceAtLeast(64f).toDp() }
              val boxHeightDp = with(density) { (monitorH * baseFraction * ovlScale).coerceAtLeast(48f).toDp() }
              val transXPx = ovlOffsetX * (monitorW * 0.5f)
              val transYPx = ovlOffsetY * (monitorH * 0.5f)

              Box(
                modifier =
                  Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(transXPx.roundToInt(), transYPx.roundToInt()) }
                    .size(width = boxWidthDp, height = boxHeightDp)
                    .graphicsLayer {
                      rotationZ = ovlEval.rotationDegrees
                      alpha = ovlEval.opacity.coerceIn(0.05f, 1.0f)
                    }
                    .then(
                      if (isOvlSelected) {
                        Modifier.border(
                          width = 1.5.dp,
                          color = MaterialTheme.colorScheme.primary,
                          shape = RoundedCornerShape(8.dp),
                        )
                      } else {
                        Modifier
                      },
                    )
                    .pointerInput(ovlClip.clipId, monitorSizePx) {
                      detectTapGestures {
                        latestOnSelectOverlayClip(ovlClip.clipId)
                      }
                    }
                    .pointerInput(ovlClip.clipId, monitorSizePx) {
                      var dragX = ovlOffsetX
                      var dragY = ovlOffsetY
                      detectDragGestures(
                        onDragStart = {
                          latestOnSelectOverlayClip(ovlClip.clipId)
                          latestOnBeginOverlayTransform()
                          dragX = ovlOffsetX
                          dragY = ovlOffsetY
                        },
                        onDragEnd = { latestOnEndOverlayTransform() },
                        onDragCancel = { latestOnEndOverlayTransform() },
                        onDrag = { change, dragAmount ->
                          change.consume()
                          val halfW = (monitorSizePx.width.coerceAtLeast(1) * 0.5f)
                          val halfH = (monitorSizePx.height.coerceAtLeast(1) * 0.5f)
                          dragX = (dragX + dragAmount.x / halfW).coerceIn(-0.95f, 0.95f)
                          dragY = (dragY + dragAmount.y / halfH).coerceIn(-0.95f, 0.95f)
                          latestOnUpdateOverlayTransform(ovlClip.clipId, dragX, dragY, null, true)
                        },
                      )
                    }
                    .testTag("viewer_overlay_item_${ovlClip.clipId}"),
              ) {
                OverlayClipVisual(
                  clip = ovlClip,
                  projectRootDir = projectRootDir,
                  assets = assets,
                  playheadMs = playheadMs,
                  modifier = Modifier.fillMaxSize(),
                )

                if (isOvlSelected) {
                  Box(
                    modifier =
                      Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = resizeHandleCd }
                        .pointerInput(ovlClip.clipId, monitorSizePx) {
                          var curScale = ovlScale
                          detectDragGestures(
                            onDragStart = {
                              latestOnBeginOverlayTransform()
                              curScale = ovlScale
                            },
                            onDragEnd = { latestOnEndOverlayTransform() },
                            onDragCancel = { latestOnEndOverlayTransform() },
                            onDrag = { change, dragAmount ->
                              change.consume()
                              val scaleBasePx = (monitorSizePx.width.coerceAtLeast(1) * baseFraction * 0.5f)
                              val delta = (dragAmount.x + dragAmount.y) / scaleBasePx
                              curScale = (curScale + delta).coerceIn(0.15f, 3.0f)
                              latestOnUpdateOverlayTransform(ovlClip.clipId, null, null, curScale, true)
                            },
                          )
                        }
                        .testTag("viewer_overlay_resize_handle_${ovlClip.clipId}"),
                    contentAlignment = Alignment.Center,
                  ) {
                    Surface(
                      color = MaterialTheme.colorScheme.primary,
                      shape = CircleShape,
                      border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.background),
                      shadowElevation = 4.dp,
                      modifier = Modifier.size(22.dp),
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(
                          imageVector = Icons.Default.OpenInFull,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.onPrimary,
                          modifier = Modifier.size(12.dp),
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          val subCfg = activeSubtitleClip?.subtitleConfig
          val displaySubText = subCfg?.text?.takeIf { it.isNotBlank() } ?: activeSubtitleText?.takeIf { it.isNotBlank() }
          if (displaySubText != null) {
            val animFrame =
              if (activeSubtitleClip != null && subCfg != null) {
                SubtitleAnimationMath.evaluate(
                  animation = subCfg.animation,
                  elapsedMs = playheadMs - activeSubtitleClip.timelineStartMs,
                  totalDurationMs = activeSubtitleClip.durationMs,
                )
              } else {
                com.example.core.model.SubtitleAnimationFrame(alpha = 1f, scale = 1f, translationYFraction = 0f)
              }
            val alignBox =
              when (subCfg?.alignment) {
                SubtitleAlignment.TOP_CENTER -> Alignment.TopCenter
                SubtitleAlignment.MIDDLE_CENTER -> Alignment.Center
                SubtitleAlignment.BOTTOM_CENTER, null -> Alignment.BottomCenter
              }
            val previewFontFamily =
              when (subCfg?.fontFamily) {
                "serif" -> FontFamily.Serif
                "monospace" -> FontFamily.Monospace
                else -> FontFamily.SansSerif
              }
            val previewTextColor =
              subCfg?.textColorHex?.let { hex ->
                runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
              } ?: MaterialTheme.colorScheme.onSurface
            val previewBgColor =
              subCfg?.backgroundColorHex?.let { hex ->
                runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
              } ?: MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.84f)
            val previewOutlineColor =
              subCfg?.outlineColorHex?.let { hex ->
                runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
              } ?: Color.Black
            val outlinePx = with(density) { (subCfg?.outlineWidthDp ?: 0f).coerceIn(0f, 8f).dp.toPx() }
            val verticalBiasShiftPx =
              with(density) { (subCfg?.verticalOffsetNormalized ?: 0f) * monitorSizePx.height.toFloat() }
            val scaledSp = ((subCfg?.fontSizeSp ?: 14f) * 0.65f).coerceIn(10f, 28f).sp

            Surface(
              color = previewBgColor,
              shape = RoundedCornerShape(4.dp),
              modifier =
                Modifier
                  .align(alignBox)
                  .padding(vertical = 10.dp, horizontal = 16.dp)
                  .graphicsLayer {
                    alpha = animFrame.alpha
                    scaleX = animFrame.scale
                    scaleY = animFrame.scale
                    translationY = verticalBiasShiftPx + animFrame.translationYFraction * size.height
                  }
                  .testTag("viewer_subtitle_overlay"),
            ) {
              Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                if (outlinePx > 0.5f && previewOutlineColor.alpha > 0.05f) {
                  Text(
                    text = displaySubText,
                    style =
                      TextStyle(
                        fontSize = scaledSp,
                        fontFamily = previewFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        drawStyle = ComposeStroke(width = outlinePx),
                      ),
                    color = previewOutlineColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                Text(
                  text = displaySubText,
                  style =
                    TextStyle(
                      fontSize = scaledSp,
                      fontFamily = previewFontFamily,
                      fontWeight = FontWeight.SemiBold,
                    ),
                  color = previewTextColor,
                  textAlign = TextAlign.Center,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        // Layer 2: Standby media prompt (kept unscaled & tappable outside clip/viewer zoom transform)
        if (isInspection || previewController == null || !previewState.hasPlayableMedia) {
          BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isVeryShort = maxHeight < 132.dp
            if (!isVeryShort) {
              Text(
                text = stringResource(R.string.label_viewer_no_signal),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 12.dp),
              )
            }

            if (isVeryShort) {
              Row(
                modifier =
                  Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                AssistChip(
                  onClick = onOpenMediaPicker,
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.AddPhotoAlternate,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = { Text(stringResource(R.string.nav_media)) },
                  modifier = Modifier.testTag("preview_open_media_chip"),
                )
              }
            } else {
              Column(
                modifier =
                  Modifier
                    .align(Alignment.Center)
                    .verticalScroll(rememberScrollState(), enabled = !viewerViewport.isZoomPanEnabled)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Videocam,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = previewState.statusNote ?: stringResource(R.string.label_no_preview_media),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                AssistChip(
                  onClick = onOpenMediaPicker,
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.AddPhotoAlternate,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = { Text(stringResource(R.string.nav_media)) },
                  modifier = Modifier.testTag("preview_open_media_chip"),
                )
              }
            }
          }
        }

        // Layer 3: Precision framing guides (Rule of Thirds, Safe Margins, Magnetic Center Snap) & Depth Reticle
        val showCenterGuides =
          viewerViewport.isZoomPanEnabled &&
            viewerViewport.magneticCenterSnap &&
            (isViewerInteracting || viewerViewport.isSnappedToCenterX || viewerViewport.isSnappedToCenterY || viewerViewport.isTransformed)
        if (viewerViewport.showRuleOfThirds || viewerViewport.showSafeMargins || showCenterGuides) {
          ViewerGuidesOverlay(
            viewerViewport = viewerViewport,
            showCenterCrosshair = showCenterGuides,
            modifier = Modifier.fillMaxSize().testTag("viewer_guides_canvas"),
          )
        }

        if (!abBypassHeld && effectiveDepthBlur > 0.01f) {
          DepthFocusReticleOverlay(
            focusX = effectiveFocusX,
            focusY = effectiveFocusY,
            focusRange = effectiveFocusRange,
            mode = effectiveDepthMode,
            modifier = Modifier.fillMaxSize().testTag("viewer_depth_focus_reticle"),
          )
        }

        // Layer 3.5: A/B Split View divider & Bypass indicator
        if (abSplitEnabled && !abBypassHeld) {
          val safeFraction = abSplitFraction.coerceIn(0.05f, 0.95f)
          val widthPx = monitorSizePx.width.coerceAtLeast(1).toFloat()
          val dividerX = (widthPx * safeFraction).roundToInt()
          val splitDividerCd = stringResource(R.string.cd_ab_split_view)

          Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
              val x = size.width * safeFraction
              drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
              )
            }
            Surface(
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
            ) {
              Text(
                text = stringResource(R.string.label_ab_before),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.align(Alignment.TopStart).padding(start = 62.dp, top = 8.dp),
            ) {
              Text(
                text = stringResource(R.string.label_ab_after),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }

            val halfHandlePx = with(density) { 24.dp.roundToPx() }
            Box(
              modifier =
                Modifier
                  .align(Alignment.CenterStart)
                  .offset { IntOffset((dividerX - halfHandlePx).coerceIn(0, (monitorSizePx.width - halfHandlePx * 2).coerceAtLeast(0)), 0) }
                  .minimumInteractiveComponentSize()
                  .semantics { contentDescription = splitDividerCd }
                  .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                      change.consume()
                      val currentWidth = monitorSizePx.width.coerceAtLeast(1).toFloat()
                      latestOnAbSplitFractionChange(
                        (latestAbSplitFraction + dragAmount / currentWidth).coerceIn(0.05f, 0.95f),
                      )
                    }
                  }
                  .testTag("viewer_ab_split_divider"),
              contentAlignment = Alignment.Center,
            ) {
              Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = CircleShape,
                border = BorderStroke(1.5.dp, Color.White),
                modifier = Modifier.size(22.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Compare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(13.dp),
                  )
                }
              }
            }
          }
        }

        if (abBypassHeld) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
            modifier =
              Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp)
                .testTag("viewer_ab_bypass_badge"),
          ) {
            Text(
              text = stringResource(R.string.label_ab_bypass_active),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }

        if (histogramEnabled) {
          ViewerLuminanceScopeHud(
            scopeSnapshot = scopeSnapshot,
            abBypassHeld = abBypassHeld,
            modifier =
              Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 6.dp),
          )
        }

        // Layer 4: Viewer Precision HUD (Top-End toggles & Bottom-End reset pill)
        Row(
          modifier =
            Modifier
              .align(Alignment.TopEnd)
              .padding(top = 4.dp, end = 4.dp)
              .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val bypassCd = stringResource(R.string.cd_ab_bypass_hold)
          Box(
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .semantics { contentDescription = bypassCd }
                .pointerInput(Unit) {
                  awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    latestOnAbBypassHeldChange(true)
                    try {
                      waitForUpOrCancellation()
                    } finally {
                      latestOnAbBypassHeldChange(false)
                    }
                  }
                }
                .testTag("viewer_ab_bypass_button"),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              color =
                if (abBypassHeld) {
                  MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
                } else {
                  MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                },
              shape = RoundedCornerShape(8.dp),
              border =
                BorderStroke(
                  width = 1.dp,
                  color =
                    if (abBypassHeld) {
                      MaterialTheme.colorScheme.tertiary
                    } else {
                      MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
              ) {
                Icon(
                  imageVector = if (abBypassHeld) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                  contentDescription = null,
                  tint =
                    if (abBypassHeld) {
                      MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = stringResource(R.string.action_ab_bypass_hold),
                  style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                  fontWeight = FontWeight.SemiBold,
                  color =
                    if (abBypassHeld) {
                      MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                  maxLines = 1,
                )
              }
            }
          }

          ViewerHudIconToggle(
            icon = Icons.Default.Compare,
            contentDescription = stringResource(R.string.cd_ab_split_view),
            active = abSplitEnabled,
            onClick = onToggleAbSplit,
            testTag = "viewer_ab_split_toggle",
          )
          ViewerHudIconToggle(
            icon = Icons.Default.Equalizer,
            contentDescription = stringResource(R.string.cd_viewer_histogram),
            active = histogramEnabled,
            onClick = onToggleHistogram,
            testTag = "viewer_histogram_toggle",
          )
          ViewerHudIconToggle(
            icon = Icons.Default.ZoomIn,
            contentDescription = stringResource(R.string.cd_viewer_zoom_pan),
            active = viewerViewport.isZoomPanEnabled,
            onClick = onToggleViewerZoomPan,
            testTag = "viewer_zoom_pan_toggle",
          )
          ViewerHudIconToggle(
            icon = Icons.Default.Grid3x3,
            contentDescription = stringResource(R.string.cd_viewer_rule_of_thirds),
            active = viewerViewport.showRuleOfThirds,
            onClick = onToggleViewerRuleOfThirds,
            testTag = "viewer_thirds_toggle",
          )
          ViewerHudIconToggle(
            icon = Icons.Default.CropFree,
            contentDescription = stringResource(R.string.cd_viewer_safe_margins),
            active = viewerViewport.showSafeMargins,
            onClick = onToggleViewerSafeMargins,
            testTag = "viewer_safe_margins_toggle",
          )
          ViewerHudIconToggle(
            icon = Icons.Default.FilterCenterFocus,
            contentDescription = stringResource(R.string.cd_viewer_center_snap),
            active = viewerViewport.magneticCenterSnap,
            onClick = onToggleViewerCenterSnap,
            testTag = "viewer_center_snap_toggle",
          )
        }

        if (viewerViewport.isTransformed) {
          val resetCd = stringResource(R.string.cd_viewer_reset_viewport)
          Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)),
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 6.dp)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(999.dp))
                .semantics { contentDescription = resetCd }
                .clickable(onClick = onResetViewerViewport)
                .testTag("viewer_reset_zoom_button"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = "${(viewerViewport.zoomScale * 100f).roundToInt()}% · ${stringResource(R.string.label_viewer_reset_short)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }

    if (!compactDiagnostics) {
      previewState.diagnosticNotice?.let { notice ->
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().testTag("preview_diagnostic_notice"),
        ) {
          Text(
            text = notice.message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }
    }

    previewState.uiError?.let { uiError ->
      Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth().testTag("preview_error_banner"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = uiError.title,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              text = uiError.detail,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (uiError.isRecoverable) {
            TextButton(onClick = onRetryPreview) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(2.dp))
              Text("Reintentar")
            }
          }
          IconButton(onClick = onDismissError, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Ocultar error", modifier = Modifier.size(16.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun ViewerHudIconToggle(
  icon: ImageVector,
  contentDescription: String,
  active: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  val activeColor = MaterialTheme.colorScheme.secondary
  Box(
    modifier =
      Modifier
        .minimumInteractiveComponentSize()
        .clip(RoundedCornerShape(10.dp))
        .semantics { this.contentDescription = contentDescription }
        .clickable(onClick = onClick)
        .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color =
        if (active) {
          activeColor.copy(alpha = 0.24f)
        } else {
          MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
        },
      shape = RoundedCornerShape(8.dp),
      border =
        BorderStroke(
          width = 1.dp,
          color = if (active) activeColor else MaterialTheme.colorScheme.outlineVariant,
        ),
      modifier = Modifier.size(32.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

@Composable
private fun ViewerGuidesOverlay(
  viewerViewport: ViewerViewportState,
  showCenterCrosshair: Boolean,
  modifier: Modifier = Modifier,
) {
  val thirdsColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
  val powerPointColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
  val actionSafeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
  val titleSafeColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.70f)
  val centerSnapColor = MaterialTheme.colorScheme.secondary
  val centerIdleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
  val textMeasurer = rememberTextMeasurer()
  val actionLabel = stringResource(R.string.label_action_safe)
  val titleLabel = stringResource(R.string.label_title_safe)
  val badgeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    if (w <= 4f || h <= 4f) return@Canvas

    if (viewerViewport.showRuleOfThirds) {
      val x1 = w / 3f
      val x2 = 2f * w / 3f
      val y1 = h / 3f
      val y2 = 2f * h / 3f
      val strokePx = 1.dp.toPx()

      drawLine(thirdsColor, Offset(x1, 0f), Offset(x1, h), strokeWidth = strokePx)
      drawLine(thirdsColor, Offset(x2, 0f), Offset(x2, h), strokeWidth = strokePx)
      drawLine(thirdsColor, Offset(0f, y1), Offset(w, y1), strokeWidth = strokePx)
      drawLine(thirdsColor, Offset(0f, y2), Offset(w, y2), strokeWidth = strokePx)

      val pointRadius = 2.5.dp.toPx()
      listOf(
        Offset(x1, y1),
        Offset(x2, y1),
        Offset(x1, y2),
        Offset(x2, y2),
      ).forEach { pt ->
        drawCircle(color = powerPointColor, radius = pointRadius, center = pt)
      }
    }

    if (viewerViewport.showSafeMargins) {
      val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
      // 90% Action Safe (5% inset)
      val actionInsetX = w * 0.05f
      val actionInsetY = h * 0.05f
      val actionW = w - actionInsetX * 2f
      val actionH = h - actionInsetY * 2f
      drawRect(
        color = actionSafeColor,
        topLeft = Offset(actionInsetX, actionInsetY),
        size = Size(actionW, actionH),
        style = Stroke(width = 1.dp.toPx(), pathEffect = dashEffect),
      )

      // 80% Title Safe (10% inset)
      val titleInsetX = w * 0.10f
      val titleInsetY = h * 0.10f
      val titleW = w - titleInsetX * 2f
      val titleH = h - titleInsetY * 2f
      drawRect(
        color = titleSafeColor,
        topLeft = Offset(titleInsetX, titleInsetY),
        size = Size(titleW, titleH),
        style = Stroke(width = 1.2.dp.toPx()),
      )

      // Corner ticks on Title Safe
      val tickLen = (minOf(w, h) * 0.06f).coerceIn(6.dp.toPx(), 14.dp.toPx())
      val tickStroke = 2.dp.toPx()
      // Top-left
      drawLine(titleSafeColor, Offset(titleInsetX, titleInsetY), Offset(titleInsetX + tickLen, titleInsetY), tickStroke)
      drawLine(titleSafeColor, Offset(titleInsetX, titleInsetY), Offset(titleInsetX, titleInsetY + tickLen), tickStroke)
      // Top-right
      drawLine(titleSafeColor, Offset(titleInsetX + titleW, titleInsetY), Offset(titleInsetX + titleW - tickLen, titleInsetY), tickStroke)
      drawLine(titleSafeColor, Offset(titleInsetX + titleW, titleInsetY), Offset(titleInsetX + titleW, titleInsetY + tickLen), tickStroke)
      // Bottom-left
      drawLine(titleSafeColor, Offset(titleInsetX, titleInsetY + titleH), Offset(titleInsetX + tickLen, titleInsetY + titleH), tickStroke)
      drawLine(titleSafeColor, Offset(titleInsetX, titleInsetY + titleH), Offset(titleInsetX, titleInsetY + titleH - tickLen), tickStroke)
      // Bottom-right
      drawLine(titleSafeColor, Offset(titleInsetX + titleW, titleInsetY + titleH), Offset(titleInsetX + titleW - tickLen, titleInsetY + titleH), tickStroke)
      drawLine(titleSafeColor, Offset(titleInsetX + titleW, titleInsetY + titleH), Offset(titleInsetX + titleW, titleInsetY + titleH - tickLen), tickStroke)

      if (h >= 96.dp.toPx()) {
        val actionMeasured = textMeasurer.measure(actionLabel, style = badgeStyle.copy(color = actionSafeColor))
        drawText(actionMeasured, topLeft = Offset(actionInsetX + 4.dp.toPx(), actionInsetY + 2.dp.toPx()))
        val titleMeasured = textMeasurer.measure(titleLabel, style = badgeStyle.copy(color = titleSafeColor))
        drawText(titleMeasured, topLeft = Offset(titleInsetX + 4.dp.toPx(), titleInsetY + 2.dp.toPx()))
      }
    }

    if (showCenterCrosshair) {
      val cx = w * 0.5f
      val cy = h * 0.5f
      val snapXActive = viewerViewport.isSnappedToCenterX
      val snapYActive = viewerViewport.isSnappedToCenterY
      val guideDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)

      if (snapXActive) {
        drawLine(
          color = centerSnapColor.copy(alpha = 0.88f),
          start = Offset(cx, 0f),
          end = Offset(cx, h),
          strokeWidth = 1.5.dp.toPx(),
        )
      } else {
        drawLine(
          color = centerIdleColor,
          start = Offset(cx, cy - 14.dp.toPx()),
          end = Offset(cx, cy + 14.dp.toPx()),
          strokeWidth = 1.dp.toPx(),
          pathEffect = guideDash,
        )
      }

      if (snapYActive) {
        drawLine(
          color = centerSnapColor.copy(alpha = 0.88f),
          start = Offset(0f, cy),
          end = Offset(w, cy),
          strokeWidth = 1.5.dp.toPx(),
        )
      } else {
        drawLine(
          color = centerIdleColor,
          start = Offset(cx - 14.dp.toPx(), cy),
          end = Offset(cx + 14.dp.toPx(), cy),
          strokeWidth = 1.dp.toPx(),
          pathEffect = guideDash,
        )
      }

      if (snapXActive && snapYActive) {
        drawCircle(
          color = centerSnapColor,
          radius = 5.dp.toPx(),
          center = Offset(cx, cy),
          style = Stroke(width = 1.5.dp.toPx()),
        )
      }
    }
  }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyframeInspectorStrip(
  selectedClip: TimelineClip,
  playheadMs: Long,
  onAddKeyframe: () -> Unit,
  onSeekToMs: (Long) -> Unit,
  onJumpPreviousKeyframe: () -> Unit,
  onJumpNextKeyframe: () -> Unit,
  onMoveKeyframe: (keyframeId: String, newOffsetMs: Long) -> Unit,
  onUpdateInterpolation: (keyframeId: String, interpolation: KeyframeInterpolation) -> Unit,
  onRemoveKeyframe: (String) -> Unit,
  onClearKeyframes: () -> Unit,
  onBeginEdit: () -> Unit,
  onUpdateKeyframeProperties: (
    scale: Float?,
    opacity: Float?,
    offsetX: Float?,
    offsetY: Float?,
    rotationDegrees: Float?,
    volume: Float?,
    depthBlur: Float?,
    depthFocusX: Float?,
    depthFocusY: Float?,
    depthFocusRange: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onEndEdit: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  fillAvailableHeight: Boolean = false,
  maxContentHeight: Dp = 280.dp,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  if (selectedClip.kind != ClipKind.VIDEO && selectedClip.kind != ClipKind.AUDIO) return
  val isVideo = selectedClip.kind == ClipKind.VIDEO
  val keyframes = selectedClip.keyframes()
  val offsetMs = (playheadMs - selectedClip.timelineStartMs).coerceIn(0L, selectedClip.durationMs)
  val evaluated = selectedClip.evaluatePropertiesAt(offsetMs)
  val activeKf = keyframes.firstOrNull { abs(it.offsetMs - offsetMs) <= 40L }
  val headerTitle =
    if (isVideo) {
      stringResource(R.string.editor_keyframes_title)
    } else {
      stringResource(R.string.label_audio_keyframes_title)
    }
  val readoutSummary =
    if (isVideo) {
      "${"%.2f".format(evaluated.scale)}x · ${evaluated.rotationDegrees.roundToInt()}° · ${(evaluated.opacity * 100f).roundToInt()}% · Vol ${(evaluated.volume * 100f).roundToInt()}%"
    } else {
      "Vol ${(evaluated.volume * 100f).roundToInt()}% · ${keyframes.size} kf"
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
        .testTag("keyframes_subpanel"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.fillMaxHeight() else it.heightIn(max = maxContentHeight) }
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = headerTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${formatTimecode(offsetMs)} · $readoutSummary",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onJumpPreviousKeyframe,
          enabled = keyframes.isNotEmpty(),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("prev_keyframe_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.cd_prev_keyframe),
          )
        }
        FilledTonalButton(
          onClick = onAddKeyframe,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("add_keyframe_button"),
        ) {
          Icon(imageVector = Icons.Default.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.action_add_keyframe))
        }
        IconButton(
          onClick = onJumpNextKeyframe,
          enabled = keyframes.isNotEmpty(),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("next_keyframe_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.cd_next_keyframe),
          )
        }
        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_subpanel))
        }
      }

      if (keyframes.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          keyframes.forEach { kf ->
            val chipSummary =
              if (isVideo) {
                "${formatTimecode(kf.offsetMs)} (${"%.1f".format(kf.scale)}x)"
              } else {
                "${formatTimecode(kf.offsetMs)} (${((kf.volume ?: evaluated.volume) * 100f).roundToInt()}%)"
              }
            InputChip(
              selected = activeKf?.keyframeId == kf.keyframeId,
              onClick = { onSeekToMs(selectedClip.timelineStartMs + kf.offsetMs) },
              label = { Text(chipSummary) },
              trailingIcon = {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = stringResource(R.string.cd_remove_keyframe),
                  modifier =
                    Modifier
                      .size(18.dp)
                      .clickable { onRemoveKeyframe(kf.keyframeId) }
                      .testTag("remove_kf_${kf.keyframeId}"),
                )
              },
              modifier = Modifier.testTag("keyframe_chip_${kf.keyframeId}"),
            )
          }
        }

        activeKf?.let { selectedKf ->
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.label_interpolation),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyframeInterpolation.entries.forEach { curve ->
              FilterChip(
                selected = selectedKf.interpolation == curve,
                onClick = { onUpdateInterpolation(selectedKf.keyframeId, curve) },
                label = { Text(curve.displayLabel) },
                modifier = Modifier.testTag("kf_interp_${curve.name.lowercase()}"),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(
              onClick = {
                val target = (selectedKf.offsetMs - 100L).coerceAtLeast(0L)
                onMoveKeyframe(selectedKf.keyframeId, target)
                onSeekToMs(selectedClip.timelineStartMs + target)
              },
              enabled = selectedKf.offsetMs > 0L,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("nudge_keyframe_back_button"),
            ) {
              Text(stringResource(R.string.action_nudge_back_100ms))
            }
            OutlinedButton(
              onClick = {
                val target = (selectedKf.offsetMs + 100L).coerceAtMost(selectedClip.durationMs)
                onMoveKeyframe(selectedKf.keyframeId, target)
                onSeekToMs(selectedClip.timelineStartMs + target)
              },
              enabled = selectedKf.offsetMs < selectedClip.durationMs,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("nudge_keyframe_fwd_button"),
            ) {
              Text(stringResource(R.string.action_nudge_fwd_100ms))
            }
            AssistChip(
              onClick = { onRemoveKeyframe(selectedKf.keyframeId) },
              label = { Text(stringResource(R.string.action_delete_keyframe_at, formatTimecode(selectedKf.offsetMs))) },
              modifier = Modifier.testTag("delete_active_keyframe_chip"),
            )
            AssistChip(
              onClick = onClearKeyframes,
              leadingIcon = {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
              },
              label = { Text(stringResource(R.string.action_clear_keyframes)) },
              modifier = Modifier.testTag("clear_all_keyframes_chip"),
            )
          }
        }
      }

      if (isVideo) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_scale),
            valueText = "${"%.2f".format(evaluated.scale)}x",
            value = evaluated.scale.coerceIn(0.25f, 3.0f),
            valueRange = 0.25f..3.0f,
            defaultValue = 1.0f,
            onValueChange = { nextScale ->
              onBeginEdit()
              onUpdateKeyframeProperties(nextScale, null, null, null, null, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(1.0f, null, null, null, null, null, null, null, null, null, false)
            },
            testTag = "keyframe_scale_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_opacity),
            valueText = "${(evaluated.opacity * 100f).roundToInt()}%",
            value = evaluated.opacity.coerceIn(0.0f, 1.0f),
            valueRange = 0.0f..1.0f,
            defaultValue = 1.0f,
            onValueChange = { nextOpacity ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, nextOpacity, null, null, null, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, 1.0f, null, null, null, null, null, null, null, null, false)
            },
            testTag = "keyframe_opacity_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_position_x),
            valueText = "%+.2f".format(evaluated.offsetX),
            value = evaluated.offsetX.coerceIn(-1.5f, 1.5f),
            valueRange = -1.5f..1.5f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextX ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, nextX, null, null, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, 0.0f, null, null, null, null, null, null, null, false)
            },
            testTag = "keyframe_pos_x_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_position_y),
            valueText = "%+.2f".format(evaluated.offsetY),
            value = evaluated.offsetY.coerceIn(-1.5f, 1.5f),
            valueRange = -1.5f..1.5f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextY ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, nextY, null, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, 0.0f, null, null, null, null, null, null, false)
            },
            testTag = "keyframe_pos_y_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_rotation),
            valueText = "${evaluated.rotationDegrees.roundToInt()}°",
            value = evaluated.rotationDegrees.coerceIn(-180f, 180f),
            valueRange = -180f..180f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextRot ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, nextRot, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, null, 0.0f, null, null, null, null, null, false)
            },
            testTag = "keyframe_rotation_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_audio_volume),
            valueText = "${(evaluated.volume * 100f).roundToInt()}%",
            value = evaluated.volume.coerceIn(0.0f, 2.0f),
            valueRange = 0.0f..2.0f,
            defaultValue = 1.0f,
            onValueChange = { nextVol ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, nextVol, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, null, null, 1.0f, null, null, null, null, false)
            },
            testTag = "keyframe_volume_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_depth_blur_intensity),
            valueText = "${(evaluated.depthBlur * 100f).roundToInt()}%",
            value = evaluated.depthBlur.coerceIn(ColorGradeBounds.MIN_DEPTH_BLUR, ColorGradeBounds.MAX_DEPTH_BLUR),
            valueRange = ColorGradeBounds.MIN_DEPTH_BLUR..ColorGradeBounds.MAX_DEPTH_BLUR,
            defaultValue = ColorGradeBounds.DEFAULT_DEPTH_BLUR,
            onValueChange = { nextBlur ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, null, nextBlur, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, null, null, null, 0.0f, null, null, null, false)
            },
            testTag = "keyframe_depth_blur_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_depth_focus_range),
            valueText = "${(evaluated.depthFocusRange * 100f).roundToInt()}%",
            value = evaluated.depthFocusRange.coerceIn(
              ColorGradeBounds.MIN_DEPTH_FOCUS_RANGE,
              ColorGradeBounds.MAX_DEPTH_FOCUS_RANGE,
            ),
            valueRange = ColorGradeBounds.MIN_DEPTH_FOCUS_RANGE..ColorGradeBounds.MAX_DEPTH_FOCUS_RANGE,
            defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
            onValueChange = { nextRange ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, null, null, null, null, nextRange, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
                false,
              )
            },
            testTag = "keyframe_focus_range_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_depth_focus_x),
            valueText = "${(evaluated.depthFocusX * 100f).roundToInt()}%",
            value = evaluated.depthFocusX.coerceIn(
              ColorGradeBounds.MIN_DEPTH_FOCUS_COORD,
              ColorGradeBounds.MAX_DEPTH_FOCUS_COORD,
            ),
            valueRange = ColorGradeBounds.MIN_DEPTH_FOCUS_COORD..ColorGradeBounds.MAX_DEPTH_FOCUS_COORD,
            defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
            onValueChange = { nextFx ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, null, null, nextFx, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
                null,
                null,
                false,
              )
            },
            testTag = "keyframe_focus_x_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_depth_focus_y),
            valueText = "${(evaluated.depthFocusY * 100f).roundToInt()}%",
            value = evaluated.depthFocusY.coerceIn(
              ColorGradeBounds.MIN_DEPTH_FOCUS_COORD,
              ColorGradeBounds.MAX_DEPTH_FOCUS_COORD,
            ),
            valueRange = ColorGradeBounds.MIN_DEPTH_FOCUS_COORD..ColorGradeBounds.MAX_DEPTH_FOCUS_COORD,
            defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
            onValueChange = { nextFy ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, null, null, null, nextFy, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
                null,
                false,
              )
            },
            testTag = "keyframe_focus_y_slider",
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        PrecisionConsoleSlider(
          label = stringResource(R.string.label_audio_volume),
          valueText = "${(evaluated.volume * 100f).roundToInt()}%",
          value = evaluated.volume.coerceIn(0.0f, 2.0f),
          valueRange = 0.0f..2.0f,
          defaultValue = 1.0f,
          onValueChange = { nextVol ->
            onBeginEdit()
            onUpdateKeyframeProperties(null, null, null, null, null, nextVol, null, null, null, null, true)
          },
          onValueChangeFinished = onEndEdit,
          onReset = {
            onUpdateKeyframeProperties(null, null, null, null, null, 1.0f, null, null, null, null, false)
          },
          testTag = "keyframe_volume_slider",
        )
      }
    }
  }
}

@Composable
private fun DepthFocusReticleOverlay(
  focusX: Float,
  focusY: Float,
  focusRange: Float,
  mode: DepthBlurMaskMode,
  modifier: Modifier = Modifier,
) {
  val ringColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
  val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    if (w <= 4f || h <= 4f) return@Canvas
    val cx = w * focusX.coerceIn(0f, 1f)
    val cy = h * focusY.coerceIn(0f, 1f)
    val radiusPx = (minOf(w, h) * focusRange.coerceIn(0.05f, 0.85f)).coerceAtLeast(10.dp.toPx())

    when (mode) {
      DepthBlurMaskMode.LINEAR -> {
        val halfBand = (h * focusRange * 0.5f).coerceAtLeast(8.dp.toPx())
        val topY = (cy - halfBand).coerceIn(0f, h)
        val botY = (cy + halfBand).coerceIn(0f, h)
        drawLine(ringColor, Offset(0f, topY), Offset(w, topY), strokeWidth = 1.4.dp.toPx(), pathEffect = dash)
        drawLine(ringColor, Offset(0f, botY), Offset(w, botY), strokeWidth = 1.4.dp.toPx(), pathEffect = dash)
        drawCircle(color = ringColor, radius = 4.dp.toPx(), center = Offset(cx, cy))
      }
      DepthBlurMaskMode.RADIAL,
      DepthBlurMaskMode.FOREGROUND_SUBJECT, -> {
        drawCircle(
          color = ringColor,
          radius = radiusPx,
          center = Offset(cx, cy),
          style = Stroke(width = 1.4.dp.toPx(), pathEffect = dash),
        )
        drawCircle(color = ringColor, radius = 4.dp.toPx(), center = Offset(cx, cy))
      }
    }
  }
}

@Composable
private fun ViewerLuminanceScopeHud(
  scopeSnapshot: ColorScopeSnapshot,
  abBypassHeld: Boolean,
  modifier: Modifier = Modifier,
) {
  val barColor =
    if (abBypassHeld) {
      MaterialTheme.colorScheme.tertiary
    } else {
      MaterialTheme.colorScheme.secondary
    }
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
  val balanceText =
    "${stringResource(R.string.label_scope_shadows)} ${scopeSnapshot.shadowsPct}% · " +
      "${stringResource(R.string.label_scope_midtones)} ${scopeSnapshot.midtonesPct}% · " +
      "${stringResource(R.string.label_scope_highlights)} ${scopeSnapshot.highlightsPct}%"

  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.testTag("viewer_histogram_overlay"),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(
        text = stringResource(R.string.label_scope_histogram),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Canvas(
        modifier =
          Modifier
            .width(128.dp)
            .height(34.dp),
      ) {
        val bins = scopeSnapshot.lumaBins
        if (bins.isEmpty()) return@Canvas
        drawLine(
          color = gridColor,
          start = Offset(0f, size.height * 0.5f),
          end = Offset(size.width, size.height * 0.5f),
          strokeWidth = 0.8.dp.toPx(),
        )
        val stepW = size.width / bins.size.toFloat()
        val barW = (stepW * 0.78f).coerceAtLeast(1.5f)
        bins.forEachIndexed { idx, norm ->
          val barH = (size.height * norm.coerceIn(0f, 1f)).coerceAtLeast(1.dp.toPx())
          drawRect(
            color = barColor.copy(alpha = 0.88f),
            topLeft = Offset(idx * stepW, size.height - barH),
            size = Size(barW, barH),
          )
        }
      }
      Text(
        text = balanceText,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("viewer_scope_balance_text"),
      )
    }
  }
}
