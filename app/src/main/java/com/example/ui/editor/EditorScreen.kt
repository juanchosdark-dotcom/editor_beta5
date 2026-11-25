package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ergonomics.KeyboardShortcutMapper
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.ClipKind

@Composable
fun EditorScreen(
  uiState: TimelineSkeletonUiState,
  previewState: PreviewPlayerState,
  viewModel: TimelineSkeletonViewModel,
  onOpenMediaPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val markerDefaultPrefix = stringResource(R.string.label_marker_default)

  val visibleTrackIds =
    uiState.timeline.tracks.filter { it.isVisible }.mapTo(mutableSetOf()) { it.trackId }
  val overlayTrackIds =
    uiState.timeline.tracks.filter { it.isOverlay && it.isVisible }.mapTo(mutableSetOf()) { it.trackId }
  val activeAtPlayhead = uiState.timeline.activeClipsAt(uiState.timeline.playheadMs)
  val activeOverlayClips =
    activeAtPlayhead.filter {
      it.kind == ClipKind.VIDEO &&
        (it.trackId in overlayTrackIds || (it.videoConfig?.isOverlayContent == true && it.trackId in visibleTrackIds))
    }
  val activeVideoClip =
    uiState.selectedClip?.takeIf {
      it.kind == ClipKind.VIDEO &&
        it.trackId in visibleTrackIds &&
        it.trackId !in overlayTrackIds &&
        it.videoConfig?.isOverlayContent != true
    } ?: activeAtPlayhead.firstOrNull {
      it.kind == ClipKind.VIDEO &&
        it.trackId in visibleTrackIds &&
        it.trackId !in overlayTrackIds &&
        it.videoConfig?.isOverlayContent != true
    }
  val activeSubtitleClip =
    activeAtPlayhead.firstOrNull { it.kind == ClipKind.SUBTITLE && it.trackId in visibleTrackIds }
  val activeSubtitleText = activeSubtitleClip?.subtitleConfig?.text
  val activeAdjustmentClip =
    uiState.selectedClip?.takeIf { it.kind == ClipKind.ADJUSTMENT && it.trackId in visibleTrackIds }
      ?: activeAtPlayhead.firstOrNull { it.kind == ClipKind.ADJUSTMENT && it.trackId in visibleTrackIds }
  val keyframeableClip =
    uiState.selectedClip?.takeIf { it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO }
  val hasActiveSubPanel =
    uiState.activeSubPanel != EditorToolSubPanel.NONE &&
      (uiState.activeSubPanel != EditorToolSubPanel.KEYFRAMES || keyframeableClip != null)

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .focusable()
        .onPreviewKeyEvent { keyEvent ->
          if (keyEvent.type == KeyEventType.KeyDown) {
            val action =
              KeyboardShortcutMapper.mapKey(
                key = keyEvent.key,
                isCtrlPressed = keyEvent.isCtrlPressed,
                isShiftPressed = keyEvent.isShiftPressed,
              )
            if (action != null) {
              viewModel.handleShortcutAction(action)
              true
            } else {
              false
            }
          } else {
            false
          }
        },
  ) {
    val isLandscapeLayout = maxWidth > maxHeight && maxWidth >= 440.dp
    val isWideThreePane = isLandscapeLayout && maxWidth >= 840.dp && maxHeight >= 380.dp

    Column(modifier = Modifier.fillMaxSize()) {
      StudioHeaderBar(
        projectName = uiState.projectName,
        playheadMs = uiState.timeline.playheadMs,
        totalDurationMs = uiState.timeline.totalDurationMs,
        inputMode = uiState.ergonomics.inputMode,
        renderSettings = uiState.renderSettings,
        isLoopEnabled = uiState.isPreviewLoopEnabled || previewState.isLoopEnabled,
        canUndo = uiState.canUndo,
        canRedo = uiState.canRedo,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSetPreviewQuality = viewModel::setPreviewQuality,
        onToggleProxies = viewModel::toggleUseProxiesForPreview,
        onToggleLoop = viewModel::togglePreviewLoop,
        onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
        compactSingleRow = isLandscapeLayout,
      )

      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val bodyWidth = maxWidth
        val bodyHeight = maxHeight
        val timelineOuterPadding = if (isLandscapeLayout) 6.dp else 12.dp
        val estimatedDeckWidth = (bodyWidth - timelineOuterPadding * 2).coerceAtLeast(160.dp)
        val estimatedHeaderWidth =
          when {
            estimatedDeckWidth >= 680.dp -> 136.dp
            estimatedDeckWidth >= 420.dp -> 124.dp
            else -> 110.dp
          }
        val estimatedTimelineViewportDp = (estimatedDeckWidth - estimatedHeaderWidth).coerceAtLeast(160.dp)
        val fitTimelineZoom = { viewModel.fitTimelineZoom(estimatedTimelineViewportDp.value) }

        if (isLandscapeLayout) {
          val upperRowWeight =
            when {
              isWideThreePane -> 0.50f
              hasActiveSubPanel -> 0.54f
              else -> 0.48f
            }
          val timelineWeight = 1f - upperRowWeight

          Column(
            modifier = Modifier.fillMaxSize().testTag("editor_landscape_layout"),
          ) {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .weight(upperRowWeight)
                  .padding(horizontal = 4.dp, vertical = 2.dp),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (isWideThreePane && hasActiveSubPanel) {
                Media3PreviewPlayerCard(
                  previewController = viewModel.previewController,
                  previewState = previewState,
                  deviceProfile = uiState.deviceProfile,
                  activeVideoClip = activeVideoClip,
                  activeAdjustmentClip = activeAdjustmentClip,
                  activeSubtitleText = activeSubtitleText,
                  activeSubtitleClip = activeSubtitleClip,
                  playheadMs = uiState.timeline.playheadMs,
                  onRetryPreview = viewModel::retryPreviewPlayback,
                  onDismissError = viewModel::dismissPreviewError,
                  onOpenMediaPicker = onOpenMediaPicker,
                  viewerViewport = uiState.viewerViewport,
                  onToggleViewerZoomPan = viewModel::toggleViewerZoomPanMode,
                  onToggleViewerRuleOfThirds = viewModel::toggleViewerRuleOfThirds,
                  onToggleViewerSafeMargins = viewModel::toggleViewerSafeMargins,
                  onToggleViewerCenterSnap = viewModel::toggleViewerMagneticCenterSnap,
                  onViewerTransformChange = viewModel::setViewerTransform,
                  onResetViewerViewport = viewModel::resetViewerViewport,
                  customLutPresets = uiState.savedLutPresets,
                  abBypassHeld = uiState.viewerAbBypassHeld,
                  abSplitEnabled = uiState.viewerAbSplitEnabled,
                  abSplitFraction = uiState.viewerAbSplitFraction,
                  histogramEnabled = uiState.viewerHistogramEnabled,
                  onAbBypassHeldChange = viewModel::setViewerAbBypassHeld,
                  onToggleAbSplit = viewModel::toggleViewerAbSplit,
                  onAbSplitFractionChange = viewModel::setViewerAbSplitFraction,
                  onToggleHistogram = viewModel::toggleViewerHistogram,
                  overlayClips = activeOverlayClips,
                  selectedOverlayClipId = uiState.selectedClipId,
                  projectRootDir = viewModel.projectRootDir,
                  assets = uiState.assets,
                  onSelectOverlayClip = viewModel::selectClip,
                  onBeginOverlayTransform = viewModel::beginClipEditTransaction,
                  onUpdateOverlayTransform = viewModel::commitViewerOverlayTransform,
                  onEndOverlayTransform = viewModel::endClipEditTransaction,
                  fillAvailableHeight = true,
                  compactDiagnostics = true,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.32f),
                )

                ActivePreviewSummaryCard(
                  uiState = uiState,
                  previewState = previewState,
                  onBeginScrub = viewModel::beginTimelineScrub,
                  onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                  onEndScrub = viewModel::endTimelineScrub,
                  onSeekToStart = viewModel::seekToStart,
                  onStepBackFrame = viewModel::stepFrameBackward,
                  onTogglePlayPause = viewModel::togglePlayPausePreview,
                  onStepForwardFrame = viewModel::stepFrameForward,
                  onToggleLoop = viewModel::togglePreviewLoop,
                  onPreviousMarker = viewModel::jumpToPreviousMarker,
                  onNextMarker = viewModel::jumpToNextMarker,
                  onCyclePreviewQuality = viewModel::cyclePreviewQuality,
                  onToggleProxies = viewModel::toggleUseProxiesForPreview,
                  onToggleSnap = viewModel::toggleSnapEnabled,
                  onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
                  onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
                  onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                  onFitZoom = fitTimelineZoom,
                  onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
                  onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
                  compact = true,
                  fillAvailableHeight = true,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.32f),
                )

                ActiveEditorSubPanel(
                  uiState = uiState,
                  viewModel = viewModel,
                  fillAvailableHeight = true,
                  maxContentHeight = bodyHeight,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.36f),
                )
              } else {
                Column(
                  modifier =
                    Modifier
                      .weight(if (hasActiveSubPanel) 0.44f else 0.48f)
                      .fillMaxHeight(),
                ) {
                  Media3PreviewPlayerCard(
                    previewController = viewModel.previewController,
                    previewState = previewState,
                    deviceProfile = uiState.deviceProfile,
                    activeVideoClip = activeVideoClip,
                    activeAdjustmentClip = activeAdjustmentClip,
                    activeSubtitleText = activeSubtitleText,
                    activeSubtitleClip = activeSubtitleClip,
                    playheadMs = uiState.timeline.playheadMs,
                    onRetryPreview = viewModel::retryPreviewPlayback,
                    onDismissError = viewModel::dismissPreviewError,
                    onOpenMediaPicker = onOpenMediaPicker,
                    viewerViewport = uiState.viewerViewport,
                    onToggleViewerZoomPan = viewModel::toggleViewerZoomPanMode,
                    onToggleViewerRuleOfThirds = viewModel::toggleViewerRuleOfThirds,
                    onToggleViewerSafeMargins = viewModel::toggleViewerSafeMargins,
                    onToggleViewerCenterSnap = viewModel::toggleViewerMagneticCenterSnap,
                    onViewerTransformChange = viewModel::setViewerTransform,
                    onResetViewerViewport = viewModel::resetViewerViewport,
                    customLutPresets = uiState.savedLutPresets,
                    abBypassHeld = uiState.viewerAbBypassHeld,
                    abSplitEnabled = uiState.viewerAbSplitEnabled,
                    abSplitFraction = uiState.viewerAbSplitFraction,
                    histogramEnabled = uiState.viewerHistogramEnabled,
                    onAbBypassHeldChange = viewModel::setViewerAbBypassHeld,
                    onToggleAbSplit = viewModel::toggleViewerAbSplit,
                    onAbSplitFractionChange = viewModel::setViewerAbSplitFraction,
                    onToggleHistogram = viewModel::toggleViewerHistogram,
                    overlayClips = activeOverlayClips,
                    selectedOverlayClipId = uiState.selectedClipId,
                    projectRootDir = viewModel.projectRootDir,
                    assets = uiState.assets,
                    onSelectOverlayClip = viewModel::selectClip,
                    onBeginOverlayTransform = viewModel::beginClipEditTransaction,
                    onUpdateOverlayTransform = viewModel::commitViewerOverlayTransform,
                    onEndOverlayTransform = viewModel::endClipEditTransaction,
                    fillAvailableHeight = true,
                    compactDiagnostics = true,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(1f),
                  )

                  if (hasActiveSubPanel) {
                    CompactLandscapeTransportStrip(
                      uiState = uiState,
                      previewState = previewState,
                      onBeginScrub = viewModel::beginTimelineScrub,
                      onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                      onEndScrub = viewModel::endTimelineScrub,
                      onSeekToStart = viewModel::seekToStart,
                      onStepBackFrame = viewModel::stepFrameBackward,
                      onTogglePlayPause = viewModel::togglePlayPausePreview,
                      onStepForwardFrame = viewModel::stepFrameForward,
                      onToggleLoop = viewModel::togglePreviewLoop,
                      onToggleSnap = viewModel::toggleSnapEnabled,
                      onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                      onFitZoom = fitTimelineZoom,
                      outerHorizontalPadding = 2.dp,
                    )
                  }
                }

                if (hasActiveSubPanel) {
                  ActiveEditorSubPanel(
                    uiState = uiState,
                    viewModel = viewModel,
                    fillAvailableHeight = true,
                    maxContentHeight = bodyHeight,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(0.56f),
                  )
                } else {
                  ActivePreviewSummaryCard(
                    uiState = uiState,
                    previewState = previewState,
                    onBeginScrub = viewModel::beginTimelineScrub,
                    onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                    onEndScrub = viewModel::endTimelineScrub,
                    onSeekToStart = viewModel::seekToStart,
                    onStepBackFrame = viewModel::stepFrameBackward,
                    onTogglePlayPause = viewModel::togglePlayPausePreview,
                    onStepForwardFrame = viewModel::stepFrameForward,
                    onToggleLoop = viewModel::togglePreviewLoop,
                    onPreviousMarker = viewModel::jumpToPreviousMarker,
                    onNextMarker = viewModel::jumpToNextMarker,
                    onCyclePreviewQuality = viewModel::cyclePreviewQuality,
                    onToggleProxies = viewModel::toggleUseProxiesForPreview,
                    onToggleSnap = viewModel::toggleSnapEnabled,
                    onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
                    onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
                    onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                    onFitZoom = fitTimelineZoom,
                    onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
                    onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
                    compact = true,
                    fillAvailableHeight = true,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(0.52f),
                  )
                }
              }
            }

            MultitrackTimelineDeck(
              uiState = uiState,
              onSelectClip = viewModel::selectClip,
              onSelectMarker = viewModel::seekPlayhead,
              onBeginScrub = viewModel::beginTimelineScrub,
              onScrubPlayhead = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onToggleTrackMute = viewModel::toggleTrackMute,
              onToggleTrackSolo = viewModel::toggleTrackSolo,
              onToggleTrackLocked = viewModel::toggleTrackLocked,
              onToggleTrackVisible = viewModel::toggleTrackVisible,
              onBeginClipTrim = viewModel::beginClipEditTransaction,
              onTrimClipEdge = { clipId, isStartEdge, targetMs ->
                viewModel.trimSelectedClipEdge(
                  clipId = clipId,
                  isStartEdge = isStartEdge,
                  rawTargetMs = targetMs,
                  fromDrag = true,
                )
              },
              onEndClipTrim = viewModel::endClipEditTransaction,
              onPointerInputDetected = viewModel::updateInputMode,
              onHoverTimelineMsChanged = viewModel::updateHoverTimelineMs,
              onZoomBy = viewModel::zoomTimelineBy,
              onSetZoom = viewModel::setTimelineZoom,
              onFitZoom = { widthDp -> viewModel.fitTimelineZoom(widthDp) },
              onEraserTapClip = viewModel::eraserTapClip,
              loadClipThumbnail = viewModel::loadClipThumbnail,
              loadClipWaveform = viewModel::loadClipWaveform,
              onSelectTransitionCut = viewModel::openTransitionCutEditor,
              onAddToTrack = viewModel::openInsertForTrack,
              onAddNewTrack = { type, isOverlay -> viewModel.addTrack(type, isOverlay) },
              outerHorizontalPadding = timelineOuterPadding,
              modifier = Modifier.weight(timelineWeight),
            )
          }
        } else {
          Column(
            modifier = Modifier.fillMaxSize().testTag("editor_portrait_layout"),
          ) {
            Media3PreviewPlayerCard(
              previewController = viewModel.previewController,
              previewState = previewState,
              deviceProfile = uiState.deviceProfile,
              activeVideoClip = activeVideoClip,
              activeAdjustmentClip = activeAdjustmentClip,
              activeSubtitleText = activeSubtitleText,
              activeSubtitleClip = activeSubtitleClip,
              playheadMs = uiState.timeline.playheadMs,
              onRetryPreview = viewModel::retryPreviewPlayback,
              onDismissError = viewModel::dismissPreviewError,
              onOpenMediaPicker = onOpenMediaPicker,
              viewerViewport = uiState.viewerViewport,
              onToggleViewerZoomPan = viewModel::toggleViewerZoomPanMode,
              onToggleViewerRuleOfThirds = viewModel::toggleViewerRuleOfThirds,
              onToggleViewerSafeMargins = viewModel::toggleViewerSafeMargins,
              onToggleViewerCenterSnap = viewModel::toggleViewerMagneticCenterSnap,
              onViewerTransformChange = viewModel::setViewerTransform,
              onResetViewerViewport = viewModel::resetViewerViewport,
              customLutPresets = uiState.savedLutPresets,
              abBypassHeld = uiState.viewerAbBypassHeld,
              abSplitEnabled = uiState.viewerAbSplitEnabled,
              abSplitFraction = uiState.viewerAbSplitFraction,
              histogramEnabled = uiState.viewerHistogramEnabled,
              onAbBypassHeldChange = viewModel::setViewerAbBypassHeld,
              onToggleAbSplit = viewModel::toggleViewerAbSplit,
              onAbSplitFractionChange = viewModel::setViewerAbSplitFraction,
              onToggleHistogram = viewModel::toggleViewerHistogram,
              overlayClips = activeOverlayClips,
              selectedOverlayClipId = uiState.selectedClipId,
              projectRootDir = viewModel.projectRootDir,
              assets = uiState.assets,
              onSelectOverlayClip = viewModel::selectClip,
              onBeginOverlayTransform = viewModel::beginClipEditTransaction,
              onUpdateOverlayTransform = viewModel::commitViewerOverlayTransform,
              onEndOverlayTransform = viewModel::endClipEditTransaction,
              fillAvailableHeight = true,
              compactDiagnostics = hasActiveSubPanel,
              modifier = Modifier.weight(if (hasActiveSubPanel) 0.28f else 0.42f),
            )

            ActivePreviewSummaryCard(
              uiState = uiState,
              previewState = previewState,
              onBeginScrub = viewModel::beginTimelineScrub,
              onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onSeekToStart = viewModel::seekToStart,
              onStepBackFrame = viewModel::stepFrameBackward,
              onTogglePlayPause = viewModel::togglePlayPausePreview,
              onStepForwardFrame = viewModel::stepFrameForward,
              onToggleLoop = viewModel::togglePreviewLoop,
              onPreviousMarker = viewModel::jumpToPreviousMarker,
              onNextMarker = viewModel::jumpToNextMarker,
              onCyclePreviewQuality = viewModel::cyclePreviewQuality,
              onToggleProxies = viewModel::toggleUseProxiesForPreview,
              onToggleSnap = viewModel::toggleSnapEnabled,
              onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
              onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
              onResetZoom = { viewModel.setTimelineZoom(1.0f) },
              onFitZoom = fitTimelineZoom,
              onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
              onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
              compact = bodyHeight < 640.dp || hasActiveSubPanel,
            )

            MultitrackTimelineDeck(
              uiState = uiState,
              onSelectClip = viewModel::selectClip,
              onSelectMarker = viewModel::seekPlayhead,
              onBeginScrub = viewModel::beginTimelineScrub,
              onScrubPlayhead = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onToggleTrackMute = viewModel::toggleTrackMute,
              onToggleTrackSolo = viewModel::toggleTrackSolo,
              onToggleTrackLocked = viewModel::toggleTrackLocked,
              onToggleTrackVisible = viewModel::toggleTrackVisible,
              onBeginClipTrim = viewModel::beginClipEditTransaction,
              onTrimClipEdge = { clipId, isStartEdge, targetMs ->
                viewModel.trimSelectedClipEdge(
                  clipId = clipId,
                  isStartEdge = isStartEdge,
                  rawTargetMs = targetMs,
                  fromDrag = true,
                )
              },
              onEndClipTrim = viewModel::endClipEditTransaction,
              onPointerInputDetected = viewModel::updateInputMode,
              onHoverTimelineMsChanged = viewModel::updateHoverTimelineMs,
              onZoomBy = viewModel::zoomTimelineBy,
              onSetZoom = viewModel::setTimelineZoom,
              onFitZoom = { widthDp -> viewModel.fitTimelineZoom(widthDp) },
              onEraserTapClip = viewModel::eraserTapClip,
              loadClipThumbnail = viewModel::loadClipThumbnail,
              loadClipWaveform = viewModel::loadClipWaveform,
              onSelectTransitionCut = viewModel::openTransitionCutEditor,
              onAddToTrack = viewModel::openInsertForTrack,
              onAddNewTrack = { type, isOverlay -> viewModel.addTrack(type, isOverlay) },
              outerHorizontalPadding = timelineOuterPadding,
              modifier = Modifier.weight(if (hasActiveSubPanel) 0.36f else 0.58f),
            )

            if (hasActiveSubPanel) {
              ActiveEditorSubPanel(
                uiState = uiState,
                viewModel = viewModel,
                fillAvailableHeight = true,
                maxContentHeight = bodyHeight,
                outerHorizontalPadding = 12.dp,
                outerVerticalPadding = 4.dp,
                modifier = Modifier.weight(0.36f),
              )
            }
          }
        }
      }

      EditActionDeck(
        uiState = uiState,
        onSplitAtPlayhead = viewModel::splitAtPlayhead,
        onToggleTrimPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.TRIM) },
        onToggleAdjustmentsPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.ADJUSTMENTS) },
        onQuickColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
        onToggleKeyframesPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.KEYFRAMES) },
        onDuplicateClip = viewModel::duplicateSelectedClip,
        onToggleRipple = viewModel::toggleRippleEnabled,
        onCycleRippleScope = viewModel::cycleRippleScope,
        onToggleClipEnabled = viewModel::toggleSelectedClipEnabled,
        onDeleteClip = viewModel::deleteSelectedClip,
        onAddMarker = { viewModel.addMarkerAtPlayhead(markerDefaultPrefix) },
        onOpenAudioTools = { viewModel.openSheet(EditorToolSheet.AUDIO) },
        onOpenSubtitlesBatch = { viewModel.openSheet(EditorToolSheet.SUBTITLES) },
        onOpenAttributesSnapshots = { viewModel.openSheet(EditorToolSheet.ATTRIBUTES_SNAPSHOTS) },
        onQuickAddAudio = { viewModel.openInsertSheet(TimelineInsertKind.AUDIO) },
        onQuickAddOverlay = { viewModel.openInsertSheet(TimelineInsertKind.OVERLAY) },
        onQuickAddEffects = { viewModel.openInsertSheet(TimelineInsertKind.EFFECTS) },
        compact = isLandscapeLayout,
      )
    }
  }

  when (uiState.activeSheet) {
    EditorToolSheet.AUDIO ->
      AudioToolsSheet(
        selectedClip = uiState.selectedClip,
        duckingConfig = uiState.timeline.duckingConfig,
        detectedSilences = uiState.detectedSilences,
        isAnalyzingSilence = uiState.isAnalyzingSilence,
        onUpdateClipAudio = viewModel::updateClipAudioMix,
        onUpdateDucking = viewModel::updateDuckingConfig,
        onAnalyzeSilences = viewModel::analyzeSilencesForClip,
        onRemoveDetectedSilences = viewModel::removeDetectedSilences,
        onDismiss = viewModel::closeSheet,
        tracks = uiState.timeline.tracksSorted(),
        onUpdateTrackVolume = viewModel::updateTrackVolume,
        onToggleTrackMute = viewModel::toggleTrackMute,
        onToggleTrackSolo = viewModel::toggleTrackSolo,
        onBeginTrackEdit = viewModel::beginClipEditTransaction,
        onEndTrackEdit = viewModel::endClipEditTransaction,
      )

    EditorToolSheet.SUBTITLES ->
      BatchSubtitlesSheet(
        subtitleClips = uiState.timeline.clips.filter { it.kind == ClipKind.SUBTITLE },
        savedStyles = uiState.savedSubtitleStyles,
        selectedClip = uiState.selectedClip,
        onApplyStyleToAll = viewModel::applySubtitleStyleToAll,
        onSaveStyleFromSelected = viewModel::saveCustomSubtitleStyleFromSelected,
        onGenerateBatchFromScript = viewModel::generateBatchSubtitles,
        onSaveBatchEdits = viewModel::saveBatchSubtitleEdits,
        onAutoGenerateSubtitles = { viewModel.autoGenerateSubtitlesForTimeline() },
        onDismiss = viewModel::closeSheet,
      )

    EditorToolSheet.ATTRIBUTES_SNAPSHOTS ->
      AttributesAndSnapshotsSheet(
        selectedClip = uiState.selectedClip,
        availableCategories = viewModel.availableAttributesForSelected(),
        clipboard = uiState.attributeClipboard,
        snapshots = uiState.snapshots,
        sequences = uiState.sequences,
        activeSequenceId = uiState.activeSequenceId,
        onCopyAttributes = viewModel::copySelectedClipAttributes,
        onPasteToCompatibleClips = viewModel::pasteAttributesToCompatibleClips,
        onCreateSnapshot = viewModel::createTimelineSnapshot,
        onRestoreSnapshot = viewModel::restoreTimelineSnapshot,
        onDuplicateSequence = viewModel::duplicateTimelineAsSequence,
        onSwitchSequence = viewModel::switchTimelineSequence,
        onDismiss = viewModel::closeSheet,
      )

    EditorToolSheet.NONE -> Unit
  }

  uiState.selectedTransitionCut?.let { transitionCut ->
    TransitionEditorSheet(
      cut = transitionCut,
      onApplyTransition = viewModel::applyTransitionAtCut,
      onDismiss = viewModel::dismissTransitionCutEditor,
    )
  }

  if (uiState.showShortcutsDialog) {
    KeyboardShortcutsDialog(
      onDismiss = { viewModel.setShortcutsDialogOpen(false) },
    )
  }

  uiState.activeInsertRequest?.let { insertReq ->
    val targetTrack = insertReq.targetTrackId?.let { id -> uiState.timeline.tracks.find { it.trackId == id } }
    TimelineInsertSheet(
      request = insertReq,
      targetTrack = targetTrack,
      assets = uiState.assets,
      savedLutPresets = uiState.savedLutPresets,
      mediaDirectory = viewModel.resolveMediaDirectory(),
      onImportAudioUri = { uri ->
        viewModel.importAudioAndInsertAtPlayhead(
          uri = uri,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onExtractAudioFromVideoAsset = { assetId ->
        viewModel.extractAudioFromVideoAssetAtPlayhead(
          assetId = assetId,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onExtractAudioFromVideoUri = { uri ->
        viewModel.extractAudioFromVideoUriAtPlayhead(
          context = context,
          uri = uri,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onInsertRecordedVoiceover = { outFile ->
        viewModel.insertRecordedVoiceoverAtPlayhead(
          audioFile = outFile,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onImportOverlayImageUri = { uri ->
        viewModel.importOverlayImageAtPlayhead(
          uri = uri,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onInsertStickerOrLogo = { preset ->
        viewModel.insertStickerOrLogoAtPlayhead(
          stickerId = preset.id,
          title = preset.title,
          isLogo = preset.isLogo,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onImportPipVideoUri = { uri ->
        viewModel.importPipVideoAtPlayhead(
          uri = uri,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onInsertAssetAsPip = { assetId ->
        viewModel.insertAssetAsPipAtPlayhead(
          assetId = assetId,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onInsertAdjustmentEffect = { label, config, durationMs ->
        viewModel.insertAdjustmentEffectAtPlayhead(
          label = label,
          config = config,
          durationMs = durationMs,
          targetTrackId = insertReq.targetTrackId,
          anchorMs = insertReq.anchorMs,
        )
      },
      onDismiss = viewModel::dismissInsertSheet,
    )
  }
}

@Composable
private fun ActiveEditorSubPanel(
  uiState: TimelineSkeletonUiState,
  viewModel: TimelineSkeletonViewModel,
  fillAvailableHeight: Boolean,
  maxContentHeight: Dp,
  outerHorizontalPadding: Dp,
  outerVerticalPadding: Dp,
  modifier: Modifier = Modifier,
) {
  when (uiState.activeSubPanel) {
    EditorToolSubPanel.TRIM -> {
      val maxBound = uiState.selectedClip?.let { viewModel.maxSourceDurationForClip(it) } ?: 10_000L
      TrimRangeSubPanel(
        selectedClip = uiState.selectedClip,
        maxSourceDurationMs = maxBound,
        onBeginEdit = viewModel::beginClipEditTransaction,
        onTrimRangeChange = viewModel::updateSelectedClipTrimRange,
        onEndEdit = viewModel::endClipEditTransaction,
        onTrimStartToPlayhead = viewModel::trimSelectedStartToPlayhead,
        onTrimEndToPlayhead = viewModel::trimSelectedEndToPlayhead,
        onClose = viewModel::closeSubPanel,
        modifier = modifier,
        fillAvailableHeight = fillAvailableHeight,
        maxContentHeight = maxContentHeight,
        outerHorizontalPadding = outerHorizontalPadding,
        outerVerticalPadding = outerVerticalPadding,
      )
    }

    EditorToolSubPanel.ADJUSTMENTS ->
      ClipInspectorPanel(
        selectedClip = uiState.selectedClip,
        activeTab = uiState.activeInspectorTab,
        onSelectTab = viewModel::selectInspectorTab,
        onBeginEdit = viewModel::beginClipEditTransaction,
        onEndEdit = viewModel::endClipEditTransaction,
        onUpdateTransform = viewModel::updateSelectedClipTransformParams,
        onRotate90 = viewModel::rotateSelectedClipBy90,
        onUpdateColor = viewModel::updateSelectedClipColorParams,
        onSelectLutPreset = { presetId -> viewModel.applyLutPresetToSelected(presetId) },
        savedLutPresets = uiState.savedLutPresets,
        onSaveLutPreset = viewModel::saveCustomLutPreset,
        onDeleteLutPreset = viewModel::deleteCustomLutPreset,
        onAddRackFocusKeyframe = viewModel::addRackFocusKeyframeAtPlayhead,
        onUpdateAudio = viewModel::updateSelectedClipAudioParams,
        onUpdateSpeed = viewModel::updateSelectedClipSpeed,
        onUpdateSpeedCurve = viewModel::updateSelectedClipSpeedCurve,
        onApplySpeedRampPreset = viewModel::applySelectedSpeedRampPreset,
        onTogglePreservePitch = viewModel::setSelectedClipPreserveAudioPitch,
        onUpdateSubtitle = viewModel::updateSelectedSubtitleInline,
        onUpdateSubtitleExtended = viewModel::updateSelectedSubtitleInline,
        onUpdateSubtitleTiming = viewModel::updateSelectedSubtitleTiming,
        onSplitSubtitle = viewModel::splitSelectedSubtitle,
        onMergeSubtitleWithNext = viewModel::mergeSelectedSubtitleWithNext,
        onAutoGenerateSubtitles = { viewModel.autoGenerateSubtitlesForTimeline() },
        onResetTab = viewModel::resetInspectorCategory,
        onResetAll = viewModel::resetSelectedClipAdjustments,
        onOpenAudioSheet = { viewModel.openSheet(EditorToolSheet.AUDIO) },
        onOpenSubtitlesSheet = { viewModel.openSheet(EditorToolSheet.SUBTITLES) },
        onClose = viewModel::closeSubPanel,
        modifier = modifier,
        fillAvailableHeight = fillAvailableHeight,
        maxContentHeight = maxContentHeight,
        outerHorizontalPadding = outerHorizontalPadding,
        outerVerticalPadding = outerVerticalPadding,
      )

    EditorToolSubPanel.KEYFRAMES ->
      uiState.selectedClip
        ?.takeIf { it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO }
        ?.let { keyframeableClip ->
          KeyframeInspectorStrip(
            selectedClip = keyframeableClip,
            playheadMs = uiState.timeline.playheadMs,
            onAddKeyframe = viewModel::addOrUpdateKeyframeAtPlayhead,
            onSeekToMs = viewModel::seekPlayhead,
            onJumpPreviousKeyframe = viewModel::jumpToPreviousKeyframe,
            onJumpNextKeyframe = viewModel::jumpToNextKeyframe,
            onMoveKeyframe = viewModel::moveSelectedClipKeyframe,
            onUpdateInterpolation = viewModel::setSelectedKeyframeInterpolation,
            onRemoveKeyframe = viewModel::removeKeyframeFromSelected,
            onClearKeyframes = viewModel::clearSelectedClipKeyframes,
            onBeginEdit = viewModel::beginClipEditTransaction,
            onUpdateKeyframeProperties = {
                scale,
                opacity,
                offsetX,
                offsetY,
                rotationDegrees,
                volume,
                depthBlur,
                depthFocusX,
                depthFocusY,
                depthFocusRange,
                fromSliderDrag,
              ->
              viewModel.updateSelectedClipKeyframeProperties(
                scale = scale,
                opacity = opacity,
                offsetX = offsetX,
                offsetY = offsetY,
                rotationDegrees = rotationDegrees,
                volume = volume,
                depthBlur = depthBlur,
                depthFocusX = depthFocusX,
                depthFocusY = depthFocusY,
                depthFocusRange = depthFocusRange,
                fromSliderDrag = fromSliderDrag,
              )
            },
            onEndEdit = viewModel::endClipEditTransaction,
            onClose = viewModel::closeSubPanel,
            modifier = modifier,
            fillAvailableHeight = fillAvailableHeight,
            maxContentHeight = maxContentHeight,
            outerHorizontalPadding = outerHorizontalPadding,
            outerVerticalPadding = outerVerticalPadding,
          )
        }

    EditorToolSubPanel.NONE -> Unit
  }
}
