package com.example.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.ai.api.BeatMarkerCandidate
import com.example.core.ai.api.DeviceProfile
import com.example.core.ai.api.EditFixPayload
import com.example.core.ai.api.EditFixStatus
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.LocalAudioAssistEngine
import com.example.core.ai.api.LocalEditAnalysisReport
import com.example.core.ai.api.LocalEditAssistEngine
import com.example.core.ai.api.LocalModelProfile
import com.example.core.ai.api.LocalTranscriptionBackend
import com.example.core.ai.api.LocalTranscriptionEvent
import com.example.core.ai.api.ResourceGuard
import com.example.core.ai.api.ResourceSnapshot
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.ergonomics.ShortcutAction
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.core.ergonomics.ViewerViewportState
import com.example.core.export.ExportJobStatus
import com.example.core.export.ExportQueueItem
import com.example.core.export.ExportQueueManager
import com.example.core.export.ExportRange
import com.example.core.media.audio.AudioWaveformCache
import com.example.core.media.audio.SilenceDetectorEngine
import com.example.core.media.audio.StudioSoundGenerator
import com.example.core.media.audio.VideoAudioExtractor
import com.example.core.media.audio.WaveformPeaksResult
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.PreviewQualityProfile
import com.example.core.media.model.RenderSettings
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.media.playback.ThumbnailLoadResult
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.media.probe.MediaProbe
import com.example.core.media.proxy.ProxyBatchState
import com.example.core.media.proxy.ProxyManager
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AppliedAiAdjustment
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.StudioSoundPreset
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.ClipTransition
import com.example.core.model.ColorGradeBounds
import com.example.core.model.ColorGradingEngine
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.EditModeOptions
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.KeyframeValidator
import com.example.core.model.LutPreset
import com.example.core.model.MarkerColor
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import com.example.core.model.OverlayContentKind
import com.example.core.model.ProjectDocument
import com.example.core.model.RelinkStatus
import com.example.core.model.RippleScope
import com.example.core.model.SilenceSegment
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.SubtitleStylePreset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.TransitionType
import com.example.core.model.VideoClipConfig
import com.example.core.project.ProjectRepository
import com.example.core.storage.CacheStorageStats
import com.example.core.storage.TempCacheStorageManager
import com.example.core.subtitles.SubtitleBatchEdit
import com.example.core.subtitles.SubtitleBatchEngine
import com.example.core.subtitles.SubtitleStyleRepository
import com.example.core.timeline.AttributeCategory
import com.example.core.timeline.AttributeTransferEngine
import com.example.core.timeline.CopiedClipAttributes
import com.example.core.timeline.EditorHistoryManager
import com.example.core.timeline.EditorHistorySnapshot
import com.example.core.timeline.RejectionReason
import com.example.core.timeline.TimelineEngine
import com.example.core.timeline.TimelineOperationResult
import com.example.core.timeline.TimelineSnapshotManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

enum class EditorToolSheet {
  NONE,
  AUDIO,
  SUBTITLES,
  ATTRIBUTES_SNAPSHOTS,
}

enum class EditorToolSubPanel {
  NONE,
  TRIM,
  ADJUSTMENTS,
  KEYFRAMES,
}

enum class InspectorTab {
  TRANSFORM,
  COLOR_LUT,
  AUDIO,
  SPEED_SUBTITLES,
}

enum class TimelineInsertKind {
  AUDIO,
  OVERLAY,
  EFFECTS,
  TEXT,
}

data class TimelineInsertRequest(
  val kind: TimelineInsertKind,
  val targetTrackId: String? = null,
  val anchorMs: Long = 0L,
)

data class RecentProjectSummary(
  val projectId: String,
  val name: String,
  val updatedAtIso: String,
  val clipCount: Int,
  val durationMs: Long,
  val isActive: Boolean,
  val aiAdjustmentCount: Int = 0,
  val isPersistedJson: Boolean = false,
)

data class MediaValidationReport(
  val isFilePresent: Boolean,
  val isPlayableDuration: Boolean,
  val hasAudioStream: Boolean,
  val resolutionLabel: String,
  val warnings: List<String>,
) {
  val isReadyForEditor: Boolean
    get() = isFilePresent && isPlayableDuration
}

data class SelectedTransitionCut(
  val leftClipId: String,
  val rightClipId: String,
  val currentTransition: ClipTransition?,
  val maxDurationMs: Long,
)

data class TimelineSkeletonUiState(
  val projectName: String = "FrameLine Demo",
  val recentProjects: List<RecentProjectSummary> = emptyList(),
  val timeline: TimelineState,
  val assets: List<MediaAsset> = emptyList(),
  val isImportingMedia: Boolean = false,
  val lastImportedAssetId: String? = null,
  val selectedClipId: String? = null,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val undoStepsCount: Int = 0,
  val redoStepsCount: Int = 0,
  val appliedAiAdjustments: List<AppliedAiAdjustment> = emptyList(),
  val cacheStorageStats: CacheStorageStats = CacheStorageStats(),
  val isClearingCache: Boolean = false,
  val lastStatusMessage: String? = null,
  val renderSettings: RenderSettings = RenderSettings(),
  val isPreviewLoopEnabled: Boolean = false,
  val selectedExportPreset: ExportPreset = ExportPreset.HD_1080P_H264,
  val proxyProgress: ProxyBatchState? = null,
  val activeSheet: EditorToolSheet = EditorToolSheet.NONE,
  val activeSubPanel: EditorToolSubPanel = EditorToolSubPanel.NONE,
  val activeInspectorTab: InspectorTab = InspectorTab.COLOR_LUT,
  val detectedSilences: List<SilenceSegment> = emptyList(),
  val isAnalyzingSilence: Boolean = false,
  val savedSubtitleStyles: List<SubtitleStylePreset> = BuiltInSubtitleStyles.ALL,
  val attributeClipboard: CopiedClipAttributes? = null,
  val snapshots: List<TimelineSnapshot> = emptyList(),
  val sequences: List<TimelineSequence> = emptyList(),
  val activeSequenceId: String = "seq_main",
  val localAiSettings: LocalAiSettings = LocalAiSettings(),
  val resourceSnapshot: ResourceSnapshot = ResourceSnapshot(),
  val deviceProfile: DeviceProfile = DeviceProfile(),
  val editAnalysisReport: LocalEditAnalysisReport? = null,
  val isAnalyzingEdits: Boolean = false,
  val beatCandidates: List<BeatMarkerCandidate> = emptyList(),
  val isAnalyzingRhythm: Boolean = false,
  val isDictatingSubtitle: Boolean = false,
  val partialTranscript: String? = null,
  val ergonomics: TimelineViewportErgonomics = TimelineViewportErgonomics(),
  val viewerViewport: ViewerViewportState = ViewerViewportState(),
  val showShortcutsDialog: Boolean = false,
  val savedLutPresets: List<LutPreset> = emptyList(),
  val viewerAbBypassHeld: Boolean = false,
  val viewerAbSplitEnabled: Boolean = false,
  val viewerAbSplitFraction: Float = 0.5f,
  val viewerHistogramEnabled: Boolean = false,
  val selectedTransitionCut: SelectedTransitionCut? = null,
  val activeInsertRequest: TimelineInsertRequest? = null,
) {
  val selectedClip: TimelineClip?
    get() = timeline.clips.find { it.clipId == selectedClipId }
}

class TimelineSkeletonViewModel(
  private val engine: TimelineEngine = TimelineEngine(),
  private val attributeEngine: AttributeTransferEngine = AttributeTransferEngine(),
  private val snapshotManager: TimelineSnapshotManager = TimelineSnapshotManager(),
  private val silenceDetector: SilenceDetectorEngine = SilenceDetectorEngine(),
  private val subtitleStyleRepo: SubtitleStyleRepository? = null,
  private val mediaProbe: MediaProbe? = null,
  private val proxyManager: ProxyManager? = null,
  private val exportQueueManager: ExportQueueManager? = null,
  val previewController: PreviewPlayerController? = null,
  val thumbnailCache: VideoThumbnailCache? = null,
  val waveformCache: AudioWaveformCache? = null,
  private val resourceGuard: ResourceGuard? = null,
  private val localAudioAssistEngine: LocalAudioAssistEngine? = null,
  private val localEditAssistant: LocalEditAssistEngine? = null,
  private val localTranscriptionBackend: LocalTranscriptionBackend? = null,
  private val projectRepository: ProjectRepository? = null,
  private val tempCacheStorageManager: TempCacheStorageManager? = null,
  private val historyManager: EditorHistoryManager = EditorHistoryManager(MAX_UNDO_HISTORY),
  val projectRootDir: File = File("."),
) : ViewModel() {

  private var projectDoc: ProjectDocument = createInitialProjectDocument()
  private val projectStore = linkedMapOf(projectDoc.projectId to projectDoc)
  private var dictationJob: Job? = null
  private var playbackTickerJob: Job? = null
  private var pendingEditHistorySnapshot: EditorHistorySnapshot? = null
  private val aiSuggestionRollbackMap = mutableMapOf<String, AiSuggestionRollback>()

  private sealed interface AiSuggestionRollback {
    data class CreatedClip(val clipId: String) : AiSuggestionRollback
    data class ModifiedAdjustment(
      val clipId: String,
      val previousConfig: AdjustmentClipConfig?,
    ) : AiSuggestionRollback
    data class AddedMarkers(val markerIds: List<String>) : AiSuggestionRollback
    data class AddedKeyframes(
      val targetClipId: String,
      val previousKeyframes: List<ClipKeyframe>,
    ) : AiSuggestionRollback
    data class ModifiedAudioMix(
      val targetClipId: String,
      val previousVideoConfig: VideoClipConfig?,
      val previousAudioConfig: AudioClipConfig?,
    ) : AiSuggestionRollback
  }

  private val _uiState =
    MutableStateFlow(
      TimelineSkeletonUiState(
        projectName = projectDoc.name,
        recentProjects = buildRecentSummaries(projectDoc.projectId),
        timeline = projectDoc.timeline,
        selectedClipId = "clip_v1",
        snapshots = projectDoc.snapshots,
        sequences = projectDoc.sequences,
        activeSequenceId = projectDoc.activeSequenceId,
        appliedAiAdjustments = projectDoc.appliedAiAdjustments,
        savedLutPresets = projectDoc.savedLutPresets,
      ),
    )
  val uiState: StateFlow<TimelineSkeletonUiState> = _uiState.asStateFlow()

  init {
    BuiltInLutPresets.syncCustomPresets(projectDoc.savedLutPresets)
    subtitleStyleRepo?.let { repo ->
      viewModelScope.launch {
        _uiState.update { it.copy(savedSubtitleStyles = repo.loadAllStyles()) }
      }
    }
    if (projectRepository != null) {
      viewModelScope.launch {
        initializePersistentProjects()
      }
    }
    if (tempCacheStorageManager != null) {
      viewModelScope.launch {
        refreshCacheStorageStatsInternal()
      }
    }
    resourceGuard?.let { guard ->
      viewModelScope.launch {
        guard.snapshot.collect { snap ->
          _uiState.update { it.copy(resourceSnapshot = snap) }
        }
      }
      viewModelScope.launch {
        guard.deviceProfile.collect { profile ->
          thumbnailCache?.trimToProfile(profile)
          waveformCache?.trimToProfile(profile)
          _uiState.update { it.copy(deviceProfile = profile) }
        }
      }
    }
    previewController?.let { controller ->
      viewModelScope.launch {
        controller.state.collect { playerState ->
          if (playerState.isPlaying) {
            if (playbackTickerJob == null || playbackTickerJob?.isActive != true) {
              playbackTickerJob =
                viewModelScope.launch {
                  while (true) {
                    val posMs = controller.currentPositionMs()
                    if (!controller.state.value.isScrubbing) {
                      _uiState.update { state ->
                        if (state.timeline.playheadMs != posMs) {
                          state.copy(timeline = engine.seekPlayhead(state.timeline, posMs))
                        } else {
                          state
                        }
                      }
                    }
                    kotlinx.coroutines.delay(60L)
                  }
                }
            }
          } else {
            playbackTickerJob?.cancel()
            playbackTickerJob = null
          }
        }
      }
    }
    exportQueueManager?.let { manager ->
      viewModelScope.launch {
        manager.storageUpdateVersion.collect { version ->
          if (version > 0L) {
            refreshCacheStorageStatsInternal()
          }
        }
      }
      viewModelScope.launch {
        var prevTerminalSnapshot: List<Pair<String, ExportJobStatus>> = emptyList()
        manager.queue.collect { list ->
          val terminalSnapshot =
            list
              .filter {
                it.status == ExportJobStatus.COMPLETED ||
                  it.status == ExportJobStatus.CANCELLED ||
                  it.status == ExportJobStatus.FAILED
              }
              .map { it.jobId to it.status }
          if (terminalSnapshot != prevTerminalSnapshot) {
            prevTerminalSnapshot = terminalSnapshot
            refreshCacheStorageStatsInternal()
          }
        }
      }
    }
  }

  val exportQueue: StateFlow<List<ExportQueueItem>> =
    exportQueueManager?.queue
      ?: MutableStateFlow<List<ExportQueueItem>>(emptyList()).asStateFlow()

  val previewState: StateFlow<PreviewPlayerState> =
    previewController?.state
      ?: MutableStateFlow(PreviewPlayerState()).asStateFlow()

  val activeExportCount: StateFlow<Int> =
    MutableStateFlow(0).also { flow ->
      viewModelScope.launch {
        exportQueue.collect { list ->
          flow.value =
            list.count {
              it.status == ExportJobStatus.QUEUED || it.status == ExportJobStatus.RUNNING
            }
        }
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

  fun refreshResourceSnapshot() {
    val settings = _uiState.value.localAiSettings
    val snap = resourceGuard?.refreshNow(settings) ?: return
    val profile = resourceGuard.resolveDeviceProfile(settings)
    thumbnailCache?.trimToProfile(profile)
    _uiState.update { it.copy(resourceSnapshot = snap, deviceProfile = profile) }
  }

  fun openSheet(sheet: EditorToolSheet) {
    _uiState.update { it.copy(activeSheet = sheet, lastStatusMessage = null) }
  }

  fun closeSheet() {
    _uiState.update { it.copy(activeSheet = EditorToolSheet.NONE) }
  }

  fun toggleSubPanel(panel: EditorToolSubPanel) {
    _uiState.update { state ->
      val next = if (state.activeSubPanel == panel) EditorToolSubPanel.NONE else panel
      val nextTab =
        if (next == EditorToolSubPanel.ADJUSTMENTS) {
          defaultInspectorTabFor(state.selectedClip, state.activeInspectorTab)
        } else {
          state.activeInspectorTab
        }
      state.copy(
        activeSubPanel = next,
        activeInspectorTab = nextTab,
        lastStatusMessage = null,
      )
    }
  }

  fun selectInspectorTab(tab: InspectorTab) {
    _uiState.update {
      it.copy(
        activeSubPanel = EditorToolSubPanel.ADJUSTMENTS,
        activeInspectorTab = tab,
        lastStatusMessage = null,
      )
    }
  }

  fun openInspectorOrToggleTab(tab: InspectorTab) {
    _uiState.update { state ->
      val shouldClose =
        state.activeSubPanel == EditorToolSubPanel.ADJUSTMENTS && state.activeInspectorTab == tab
      state.copy(
        activeSubPanel = if (shouldClose) EditorToolSubPanel.NONE else EditorToolSubPanel.ADJUSTMENTS,
        activeInspectorTab = tab,
        lastStatusMessage = null,
      )
    }
  }

  fun closeSubPanel() {
    _uiState.update { it.copy(activeSubPanel = EditorToolSubPanel.NONE) }
  }

  private fun defaultInspectorTabFor(
    clip: TimelineClip?,
    currentTab: InspectorTab,
  ): InspectorTab {
    if (clip == null) return currentTab
    return when (clip.kind) {
      ClipKind.VIDEO -> currentTab
      ClipKind.ADJUSTMENT -> InspectorTab.COLOR_LUT
      ClipKind.AUDIO ->
        if (currentTab == InspectorTab.AUDIO || currentTab == InspectorTab.SPEED_SUBTITLES) {
          currentTab
        } else {
          InspectorTab.AUDIO
        }
      ClipKind.SUBTITLE -> InspectorTab.SPEED_SUBTITLES
    }
  }

  fun seekPlayhead(positionMs: Long) {
    _uiState.update { state ->
      state.copy(
        timeline = engine.seekPlayhead(state.timeline, positionMs),
        lastStatusMessage = null,
      )
    }
    previewController?.seekTo(positionMs)
  }

  fun beginTimelineScrub() {
    previewController?.beginScrubbing()
  }

  fun endTimelineScrub() {
    val pos = _uiState.value.timeline.playheadMs
    previewController?.endScrubbing(pos)
  }

  fun retryPreviewPlayback() {
    previewController?.retryLastPreview()
  }

  fun dismissPreviewError() {
    previewController?.clearError()
  }

  suspend fun loadClipThumbnail(assetId: String?, sourceTimeMs: Long): ThumbnailLoadResult? {
    if (assetId == null) return null
    val cache = thumbnailCache ?: return null
    val current = _uiState.value
    val asset = current.assets.find { it.assetId == assetId } ?: return null
    return cache.loadThumbnail(
      asset = asset,
      projectRootDir = projectRootDir,
      sourceTimeMs = sourceTimeMs,
      profile = current.deviceProfile,
    )
  }

  suspend fun loadClipWaveform(
    assetId: String?,
    sourceInMs: Long,
    sourceOutMs: Long,
    targetBuckets: Int = AudioWaveformCache.DEFAULT_RENDER_BUCKETS,
  ): FloatArray? {
    if (assetId == null) return null
    val cache = waveformCache ?: return null
    val asset = _uiState.value.assets.find { it.assetId == assetId } ?: return null
    return when (
      val res =
        cache.loadWaveformSlice(
          asset = asset,
          projectRootDir = projectRootDir,
          sourceInMs = sourceInMs,
          sourceOutMs = sourceOutMs,
          targetBuckets = targetBuckets,
        )
    ) {
      is WaveformPeaksResult.Ready -> res.peaks
      is WaveformPeaksResult.Unavailable -> null
    }
  }

  fun selectClip(clipId: String?) {
    _uiState.update { state ->
      val targetClip = clipId?.let { id -> state.timeline.clips.find { it.clipId == id } }
      val nextTab =
        if (state.activeSubPanel == EditorToolSubPanel.ADJUSTMENTS) {
          defaultInspectorTabFor(targetClip, state.activeInspectorTab)
        } else {
          state.activeInspectorTab
        }
      state.copy(
        selectedClipId = clipId,
        activeInspectorTab = nextTab,
        detectedSilences = emptyList(),
        lastStatusMessage = null,
      )
    }
  }

  fun splitAtPlayhead() {
    applyOperation { current ->
      val playhead = current.timeline.playheadMs
      val selected = current.selectedClip
      val targetId =
        when {
          selected != null &&
            playhead > selected.timelineStartMs &&
            playhead < selected.timelineEndMs -> selected.clipId
          else ->
            current.timeline.clips
              .firstOrNull { clip ->
                clip.trackId == "t_vid" &&
                  !clip.isLocked &&
                  playhead > clip.timelineStartMs &&
                  playhead < clip.timelineEndMs
              }?.clipId
              ?: current.timeline.clips
                .firstOrNull { clip ->
                  !clip.isLocked &&
                    playhead > clip.timelineStartMs &&
                    playhead < clip.timelineEndMs
                }?.clipId
              ?: current.selectedClipId
        }
      engine.splitAtPlayhead(state = current.timeline, targetClipId = targetId)
    }
  }

  fun beginClipEditTransaction() {
    if (pendingEditHistorySnapshot == null) {
      pendingEditHistorySnapshot = captureHistorySnapshot(_uiState.value)
    }
  }

  fun endClipEditTransaction() {
    val pending = pendingEditHistorySnapshot
    pendingEditHistorySnapshot = null
    if (pending != null && pending.timeline != _uiState.value.timeline) {
      historyManager.record(pending)
      _uiState.update {
        it.copy(
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    }
  }

  private fun isFreeDurationOverlayClip(clip: TimelineClip): Boolean {
    val cfg = clip.videoConfig
    val isNonVideoOverlay =
      cfg != null &&
        (cfg.overlayKind == OverlayContentKind.IMAGE ||
          cfg.overlayKind == OverlayContentKind.STICKER ||
          cfg.overlayKind == OverlayContentKind.LOGO)
    if (isNonVideoOverlay) return true
    val assetType = clip.assetId?.let { id -> _uiState.value.assets.find { it.assetId == id }?.type }
    return assetType == MediaType.IMAGE
  }

  fun maxSourceDurationForClip(clip: TimelineClip): Long {
    val hasBoundedMedia =
      (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) && !isFreeDurationOverlayClip(clip)
    if (!hasBoundedMedia) {
      return maxOf(clip.timelineEndMs + 10_000L, _uiState.value.timeline.totalDurationMs + 10_000L, 15_000L)
    }
    val assetDuration =
      clip.assetId?.let { id -> _uiState.value.assets.find { it.assetId == id } }?.fingerprint?.durationMs
    return maxOf(assetDuration ?: 0L, clip.sourceOutMs, clip.durationMs, 100L)
  }

  fun trimSelectedStartToPlayhead() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val playhead = current.timeline.playheadMs
    val minEnd = clip.timelineEndMs - TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS
    val minMediaStart =
      if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) {
        (clip.timelineStartMs - (clip.sourceInMs / clip.speed).toLong()).coerceAtLeast(0L)
      } else {
        0L
      }
    if (playhead !in minMediaStart..minEnd) {
      _uiState.update {
        it.copy(lastStatusMessage = "Coloca el cabezal dentro del clip para fijar el inicio")
      }
      return
    }
    val maxSource = maxSourceDurationForClip(clip)
    applyOperation { state ->
      engine.trimClip(
        state = state.timeline,
        clipId = clip.clipId,
        newStartMs = playhead,
        maxSourceDurationMs = if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) maxSource else null,
      )
    }
  }

  fun trimSelectedEndToPlayhead() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val playhead = current.timeline.playheadMs
    val minStart = clip.timelineStartMs + TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS
    val maxSource = maxSourceDurationForClip(clip)
    val maxEnd =
      if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) {
        clip.timelineStartMs + (((maxSource - clip.sourceInMs).coerceAtLeast(100L)) / clip.speed).toLong()
      } else {
        Long.MAX_VALUE
      }
    if (playhead !in minStart..maxEnd) {
      _uiState.update {
        it.copy(lastStatusMessage = "Coloca el cabezal después del inicio del clip para fijar el fin")
      }
      return
    }
    applyOperation { state ->
      engine.trimClip(
        state = state.timeline,
        clipId = clip.clipId,
        newEndMs = playhead,
        maxSourceDurationMs = if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) maxSource else null,
      )
    }
  }

  fun updateSelectedClipTrimRange(
    startMs: Long,
    endMs: Long,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val maxBound = maxSourceDurationForClip(clip)
    val safeStart = startMs.coerceIn(0L, (maxBound - TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS).coerceAtLeast(0L))
    val safeEnd = endMs.coerceIn(safeStart + TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS, maxBound)

    val opBlock: (TimelineState) -> TimelineOperationResult = { timeline ->
      if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) {
        engine.trimClipSourceRange(
          state = timeline,
          clipId = clip.clipId,
          newSourceInMs = safeStart,
          newSourceOutMs = safeEnd,
          maxSourceDurationMs = maxBound,
        )
      } else {
        engine.trimClip(
          state = timeline,
          clipId = clip.clipId,
          newStartMs = safeStart,
          newEndMs = safeEnd,
        )
      }
    }

    if (!fromSliderDrag) {
      applyOperation { state -> opBlock(state.timeline) }
      return
    }

    if (pendingEditHistorySnapshot == null) {
      pendingEditHistorySnapshot = captureHistorySnapshot(current)
    }
    when (val result = opBlock(current.timeline)) {
      is TimelineOperationResult.Success -> {
        _uiState.update {
          it.copy(timeline = result.newState, lastStatusMessage = null)
        }
      }
      is TimelineOperationResult.Rejected -> {
        _uiState.update { it.copy(lastStatusMessage = mapRejectionReason(result.reason)) }
      }
    }
  }

  fun trimSelectedClipEdge(
    clipId: String,
    isStartEdge: Boolean,
    rawTargetMs: Long,
    fromDrag: Boolean = true,
  ) {
    val current = _uiState.value
    val clip = current.timeline.clips.find { it.clipId == clipId } ?: return
    val track = current.timeline.tracks.find { it.trackId == clip.trackId }
    if (track?.isLocked == true) {
      _uiState.update { it.copy(lastStatusMessage = mapRejectionReason(RejectionReason.TRACK_LOCKED)) }
      return
    }
    if (clip.isLocked) {
      _uiState.update { it.copy(lastStatusMessage = mapRejectionReason(RejectionReason.CLIP_LOCKED)) }
      return
    }

    val snappedTarget =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = rawTargetMs.coerceAtLeast(0L),
        state = current.timeline,
        thresholdMs = current.ergonomics.inputMode.snapThresholdMs,
        snapEnabled = current.ergonomics.snapEnabled,
      )

    val hasMedia =
      (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO) && !isFreeDurationOverlayClip(clip)
    val maxSource = maxSourceDurationForClip(clip)
    val minAllowedStart =
      if (hasMedia) {
        (clip.timelineStartMs - (clip.sourceInMs / clip.speed).toLong()).coerceAtLeast(0L)
      } else {
        0L
      }
    val maxAllowedStart =
      (clip.timelineEndMs - TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS).coerceAtLeast(minAllowedStart)
    val minAllowedEnd = clip.timelineStartMs + TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS
    val maxAllowedEnd =
      if (hasMedia) {
        clip.timelineStartMs + (((maxSource - clip.sourceInMs).coerceAtLeast(100L)) / clip.speed).toLong()
      } else {
        maxOf(clip.timelineEndMs + 30_000L, current.timeline.totalDurationMs + 30_000L)
      }

    val opBlock: (TimelineState) -> TimelineOperationResult = { timeline ->
      if (isStartEdge) {
        val clampedStart = snappedTarget.coerceIn(minAllowedStart, maxAllowedStart)
        if (!hasMedia && clip.kind == ClipKind.VIDEO) {
          val nextDur = (clip.timelineEndMs - clampedStart).coerceAtLeast(TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS)
          val updated =
            timeline.clips.map { c ->
              if (c.clipId == clip.clipId) c.copy(timelineStartMs = clampedStart, sourceInMs = 0L, sourceOutMs = nextDur) else c
            }
          TimelineOperationResult.Success(timeline.copy(clips = updated.sortedBy { it.timelineStartMs }), listOf(clip.clipId))
        } else {
          engine.trimClip(
            state = timeline,
            clipId = clip.clipId,
            newStartMs = clampedStart,
            maxSourceDurationMs = if (hasMedia) maxSource else null,
            ripple = false,
          )
        }
      } else {
        val clampedEnd = snappedTarget.coerceIn(minAllowedEnd, maxAllowedEnd.coerceAtLeast(minAllowedEnd))
        if (!hasMedia && clip.kind == ClipKind.VIDEO) {
          val nextDur = (clampedEnd - clip.timelineStartMs).coerceAtLeast(TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS)
          val updated =
            timeline.clips.map { c ->
              if (c.clipId == clip.clipId) c.copy(timelineEndMs = clampedEnd, sourceInMs = 0L, sourceOutMs = nextDur) else c
            }
          TimelineOperationResult.Success(timeline.copy(clips = updated.sortedBy { it.timelineStartMs }), listOf(clip.clipId))
        } else {
          val shouldRipple = if (clip.kind == ClipKind.ADJUSTMENT || isFreeDurationOverlayClip(clip)) false else timeline.editOptions.rippleEnabled
          engine.trimClip(
            state = timeline,
            clipId = clip.clipId,
            newEndMs = clampedEnd,
            maxSourceDurationMs = if (hasMedia) maxSource else null,
            ripple = shouldRipple,
          )
        }
      }
    }

    if (!fromDrag) {
      applyOperation { state -> opBlock(state.timeline) }
      return
    }

    if (pendingEditHistorySnapshot == null) {
      pendingEditHistorySnapshot = captureHistorySnapshot(current)
    }
    when (val result = opBlock(current.timeline)) {
      is TimelineOperationResult.Success -> {
        _uiState.update {
          it.copy(timeline = result.newState, lastStatusMessage = null)
        }
      }
      is TimelineOperationResult.Rejected -> {
        _uiState.update { it.copy(lastStatusMessage = mapRejectionReason(result.reason)) }
      }
    }
  }

  fun updateSelectedClipAdjustments(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    rotationDegrees: Float,
    scale: Float,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val clampedBrightness = brightness.coerceIn(-0.5f, 0.5f)
    val clampedContrast = contrast.coerceIn(0.5f, 1.8f)
    val clampedSaturation = saturation.coerceIn(0.0f, 2.0f)
    val clampedRotation = rotationDegrees.coerceIn(-180f, 180f)
    val clampedScale = scale.coerceIn(0.5f, 2.0f)

    val result =
      when (clip.kind) {
        ClipKind.VIDEO ->
          engine.updateVideoTransform(current.timeline, clip.clipId) { cfg ->
            cfg.copy(
              brightness = clampedBrightness,
              contrast = clampedContrast,
              saturation = clampedSaturation,
              rotationDegrees = clampedRotation,
              scale = clampedScale,
            )
          }
        ClipKind.ADJUSTMENT ->
          engine.updateAdjustmentConfig(current.timeline, clip.clipId) { cfg ->
            cfg.copy(
              brightness = clampedBrightness,
              contrast = clampedContrast,
              saturation = clampedSaturation,
            )
          }
        else -> return
      }

    if (result !is TimelineOperationResult.Success) return

    if (fromSliderDrag) {
      if (pendingEditHistorySnapshot == null) {
        pendingEditHistorySnapshot = captureHistorySnapshot(current)
      }
      _uiState.update { it.copy(timeline = result.newState, lastStatusMessage = null) }
    } else {
      pushHistory(current)
      _uiState.update {
        it.copy(
          timeline = result.newState,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage = null,
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    }
  }

  fun rotateSelectedClipBy90() {
    val clip = _uiState.value.selectedClip ?: return
    val cfg = clip.videoConfig ?: return
    val rawNext = ((cfg.rotationDegrees + 90f) % 360f + 360f) % 360f
    val normalized = if (rawNext > 180f) rawNext - 360f else rawNext
    updateSelectedClipAdjustments(
      brightness = cfg.brightness,
      contrast = cfg.contrast,
      saturation = cfg.saturation,
      rotationDegrees = normalized,
      scale = cfg.scale,
      fromSliderDrag = false,
    )
  }

  fun resetSelectedClipAdjustments() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    when (clip.kind) {
      ClipKind.VIDEO ->
        applyOperation { state ->
          engine.updateVideoTransform(state.timeline, clip.clipId) { cfg ->
            cfg.copy(
              scale = 1.0f,
              offsetX = 0.0f,
              offsetY = 0.0f,
              rotationDegrees = 0.0f,
              opacity = 1.0f,
              brightness = 0.0f,
              contrast = 1.0f,
              saturation = 1.0f,
              exposure = 0.0f,
              temperature = 0.0f,
              vignette = 0.0f,
              lutPresetId = null,
              lutStrength = 1.0f,
              tint = 0.0f,
              shadows = 0.0f,
              midtones = 0.0f,
              highlights = 0.0f,
              whites = 0.0f,
              blacks = 0.0f,
              vibrance = 0.0f,
              liftX = 0.0f,
              liftY = 0.0f,
              gammaX = 0.0f,
              gammaY = 0.0f,
              gainX = 0.0f,
              gainY = 0.0f,
              depthBlur = 0.0f,
              depthBlurRadius = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
              depthBlurFeather = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
              depthBlurMode = DepthBlurMaskMode.RADIAL,
              depthFocusX = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
              depthFocusY = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
              depthFocusRange = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
              chromaticAberration = 0.0f,
              glitchIntensity = 0.0f,
            )
          }
        }
      ClipKind.ADJUSTMENT ->
        applyOperation { state ->
          engine.updateAdjustmentConfig(state.timeline, clip.clipId) { cfg ->
            cfg.copy(
              brightness = 0.0f,
              contrast = 1.0f,
              saturation = 1.0f,
              exposure = 0.0f,
              temperature = 0.0f,
              vignette = 0.0f,
              lutPresetId = null,
              lutStrength = 1.0f,
              tint = 0.0f,
              shadows = 0.0f,
              midtones = 0.0f,
              highlights = 0.0f,
              blendOpacity = 1.0f,
              whites = 0.0f,
              blacks = 0.0f,
              vibrance = 0.0f,
              liftX = 0.0f,
              liftY = 0.0f,
              gammaX = 0.0f,
              gammaY = 0.0f,
              gainX = 0.0f,
              gainY = 0.0f,
              depthBlur = 0.0f,
              depthBlurRadius = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
              depthBlurFeather = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
              depthBlurMode = DepthBlurMaskMode.RADIAL,
              depthFocusX = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
              depthFocusY = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
              depthFocusRange = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
              chromaticAberration = 0.0f,
              glitchIntensity = 0.0f,
            )
          }
        }
      else -> Unit
    }
  }

  fun updateSelectedClipTransformParams(
    scale: Float? = null,
    rotationDegrees: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    opacity: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO) return
    val isOverlayTrack = current.timeline.tracks.find { it.trackId == clip.trackId }?.isOverlay == true
    val isOverlayClip = isOverlayTrack || (clip.videoConfig?.isOverlayContent == true)
    val minScale = if (isOverlayClip) 0.15f else 0.5f
    val maxScale = if (isOverlayClip) 3.0f else 2.0f
    val maxOffset = if (isOverlayClip) 0.95f else 0.5f
    val result =
      engine.updateVideoTransform(current.timeline, clip.clipId) { cfg ->
        cfg.copy(
          scale = (scale ?: cfg.scale).coerceIn(minScale, maxScale),
          rotationDegrees = (rotationDegrees ?: cfg.rotationDegrees).coerceIn(-180f, 180f),
          offsetX = (offsetX ?: cfg.offsetX).coerceIn(-maxOffset, maxOffset),
          offsetY = (offsetY ?: cfg.offsetY).coerceIn(-maxOffset, maxOffset),
          opacity = (opacity ?: cfg.opacity).coerceIn(0.1f, 1.0f),
        )
      }
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun updateSelectedClipCropParams(
    cropLeft: Float? = null,
    cropTop: Float? = null,
    cropRight: Float? = null,
    cropBottom: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO) return
    val result =
      engine.updateVideoTransform(current.timeline, clip.clipId) { cfg ->
        val nextLeft = ColorGradeBounds.clampCropLeft(cropLeft ?: cfg.cropLeft)
        val nextTop = ColorGradeBounds.clampCropTop(cropTop ?: cfg.cropTop)
        val nextRight = ColorGradeBounds.clampCropRight(cropRight ?: cfg.cropRight, nextLeft)
        val nextBottom = ColorGradeBounds.clampCropBottom(cropBottom ?: cfg.cropBottom, nextTop)
        cfg.copy(
          cropLeft = nextLeft,
          cropTop = nextTop,
          cropRight = nextRight,
          cropBottom = nextBottom,
        )
      }
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun commitViewerOverlayTransform(
    clipId: String,
    offsetX: Float? = null,
    offsetY: Float? = null,
    scale: Float? = null,
    fromDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.timeline.clips.find { it.clipId == clipId } ?: return
    if (clip.kind != ClipKind.VIDEO) return
    if (current.selectedClipId != clipId) {
      _uiState.update { it.copy(selectedClipId = clipId) }
    }
    val updatedCurrent = _uiState.value
    val result =
      engine.updateVideoTransform(updatedCurrent.timeline, clipId) { cfg ->
        cfg.copy(
          scale = (scale ?: cfg.scale).coerceIn(0.15f, 3.0f),
          offsetX = (offsetX ?: cfg.offsetX).coerceIn(-0.95f, 0.95f),
          offsetY = (offsetY ?: cfg.offsetY).coerceIn(-0.95f, 0.95f),
        )
      }
    commitInspectorMutation(updatedCurrent, result, fromSliderDrag = fromDrag)
  }

  fun updateSelectedClipColorParams(
    brightness: Float? = null,
    contrast: Float? = null,
    saturation: Float? = null,
    exposure: Float? = null,
    temperature: Float? = null,
    vignette: Float? = null,
    lutPresetId: String? = null,
    clearLutPreset: Boolean = false,
    lutStrength: Float? = null,
    tint: Float? = null,
    shadows: Float? = null,
    midtones: Float? = null,
    highlights: Float? = null,
    blendOpacity: Float? = null,
    whites: Float? = null,
    blacks: Float? = null,
    vibrance: Float? = null,
    liftX: Float? = null,
    liftY: Float? = null,
    gammaX: Float? = null,
    gammaY: Float? = null,
    gainX: Float? = null,
    gainY: Float? = null,
    depthBlur: Float? = null,
    depthBlurRadius: Float? = null,
    depthBlurFeather: Float? = null,
    depthBlurMode: DepthBlurMaskMode? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
    chromaticAberration: Float? = null,
    glitchIntensity: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result =
      when (clip.kind) {
        ClipKind.VIDEO ->
          engine.updateVideoTransform(current.timeline, clip.clipId) { cfg ->
            val nextLutId =
              when {
                clearLutPreset -> null
                lutPresetId != null -> lutPresetId.takeUnless { it == BuiltInLutPresets.ID_NONE }
                else -> cfg.lutPresetId
              }
            ColorGradeBounds.clampVideoColor(
              cfg.copy(
                brightness = brightness ?: cfg.brightness,
                contrast = contrast ?: cfg.contrast,
                saturation = saturation ?: cfg.saturation,
                exposure = exposure ?: cfg.exposure,
                temperature = temperature ?: cfg.temperature,
                vignette = vignette ?: cfg.vignette,
                lutPresetId = nextLutId,
                lutStrength = lutStrength ?: cfg.lutStrength,
                tint = tint ?: cfg.tint,
                shadows = shadows ?: cfg.shadows,
                midtones = midtones ?: cfg.midtones,
                highlights = highlights ?: cfg.highlights,
                whites = whites ?: cfg.whites,
                blacks = blacks ?: cfg.blacks,
                vibrance = vibrance ?: cfg.vibrance,
                liftX = liftX ?: cfg.liftX,
                liftY = liftY ?: cfg.liftY,
                gammaX = gammaX ?: cfg.gammaX,
                gammaY = gammaY ?: cfg.gammaY,
                gainX = gainX ?: cfg.gainX,
                gainY = gainY ?: cfg.gainY,
                depthBlur = depthBlur ?: cfg.depthBlur,
                depthBlurRadius = depthBlurRadius ?: cfg.depthBlurRadius,
                depthBlurFeather = depthBlurFeather ?: cfg.depthBlurFeather,
                depthBlurMode = depthBlurMode ?: cfg.depthBlurMode,
                depthFocusX = depthFocusX ?: cfg.depthFocusX,
                depthFocusY = depthFocusY ?: cfg.depthFocusY,
                depthFocusRange = depthFocusRange ?: cfg.depthFocusRange,
                chromaticAberration = chromaticAberration ?: cfg.chromaticAberration,
                glitchIntensity = glitchIntensity ?: cfg.glitchIntensity,
              ),
            )
          }
        ClipKind.ADJUSTMENT ->
          engine.updateAdjustmentConfig(current.timeline, clip.clipId) { cfg ->
            val nextLutId =
              when {
                clearLutPreset -> null
                lutPresetId != null -> lutPresetId.takeUnless { it == BuiltInLutPresets.ID_NONE }
                else -> cfg.lutPresetId
              }
            ColorGradeBounds.clampAdjustmentColor(
              cfg.copy(
                brightness = brightness ?: cfg.brightness,
                contrast = contrast ?: cfg.contrast,
                saturation = saturation ?: cfg.saturation,
                exposure = exposure ?: cfg.exposure,
                temperature = temperature ?: cfg.temperature,
                vignette = vignette ?: cfg.vignette,
                lutPresetId = nextLutId,
                lutStrength = lutStrength ?: cfg.lutStrength,
                tint = tint ?: cfg.tint,
                shadows = shadows ?: cfg.shadows,
                midtones = midtones ?: cfg.midtones,
                highlights = highlights ?: cfg.highlights,
                blendOpacity = blendOpacity ?: cfg.blendOpacity,
                whites = whites ?: cfg.whites,
                blacks = blacks ?: cfg.blacks,
                vibrance = vibrance ?: cfg.vibrance,
                liftX = liftX ?: cfg.liftX,
                liftY = liftY ?: cfg.liftY,
                gammaX = gammaX ?: cfg.gammaX,
                gammaY = gammaY ?: cfg.gammaY,
                gainX = gainX ?: cfg.gainX,
                gainY = gainY ?: cfg.gainY,
                depthBlur = depthBlur ?: cfg.depthBlur,
                depthBlurRadius = depthBlurRadius ?: cfg.depthBlurRadius,
                depthBlurFeather = depthBlurFeather ?: cfg.depthBlurFeather,
                depthBlurMode = depthBlurMode ?: cfg.depthBlurMode,
                depthFocusX = depthFocusX ?: cfg.depthFocusX,
                depthFocusY = depthFocusY ?: cfg.depthFocusY,
                depthFocusRange = depthFocusRange ?: cfg.depthFocusRange,
                chromaticAberration = chromaticAberration ?: cfg.chromaticAberration,
                glitchIntensity = glitchIntensity ?: cfg.glitchIntensity,
              ),
            )
          }
        else -> return
      }
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun applyLutPresetToSelected(presetId: String, strength: Float? = null) {
    val isNone = presetId == BuiltInLutPresets.ID_NONE
    val clip = _uiState.value.selectedClip ?: return
    val existingStrength =
      when (clip.kind) {
        ClipKind.VIDEO -> clip.videoConfig?.lutStrength ?: 1.0f
        ClipKind.ADJUSTMENT -> clip.adjustmentConfig?.lutStrength ?: 1.0f
        else -> 1.0f
      }
    val nextStrength = strength ?: if (existingStrength <= 0.02f) 1.0f else existingStrength
    updateSelectedClipColorParams(
      lutPresetId = if (isNone) null else presetId,
      clearLutPreset = isNone,
      lutStrength = if (isNone) 1.0f else nextStrength,
      fromSliderDrag = false,
    )
  }

  fun saveCustomLutPreset(name: String) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val effective =
      when (clip.kind) {
        ClipKind.VIDEO ->
          clip.videoConfig?.let { BuiltInLutPresets.resolveEffectiveColor(it, current.savedLutPresets) }
        ClipKind.ADJUSTMENT ->
          clip.adjustmentConfig?.let { BuiltInLutPresets.resolveEffectiveColor(it, current.savedLutPresets) }
        else -> null
      } ?: return

    val cleanName = name.trim().ifBlank { "Mi LUT ${current.savedLutPresets.size + 1}" }
    val shortCode =
      cleanName
        .uppercase()
        .filter { it.isLetterOrDigit() }
        .take(4)
        .ifBlank { "USER" }

    val stage =
      com.example.core.model.ColorGradeStageParams(
        brightness = effective.brightness,
        exposure = effective.exposure,
        contrast = effective.contrast,
        saturation = effective.saturation,
        temperature = effective.temperature,
        tint = effective.tint,
        shadows = effective.shadows,
        midtones = effective.midtones,
        highlights = effective.highlights,
        whites = effective.whites,
        blacks = effective.blacks,
        vibrance = effective.vibrance,
        liftX = effective.liftX,
        liftY = effective.liftY,
        gammaX = effective.gammaX,
        gammaY = effective.gammaY,
        gainX = effective.gainX,
        gainY = effective.gainY,
      )

    fun swatchArgb(r0: Double, g0: Double, b0: Double): Long {
      val (rr, gg, bb) = ColorGradingEngine.applyBaseAdjustmentsToRgb(r0, g0, b0, stage)
      val ri = (rr * 255.0).toInt().coerceIn(0, 255)
      val gi = (gg * 255.0).toInt().coerceIn(0, 255)
      val bi = (bb * 255.0).toInt().coerceIn(0, 255)
      return (0xFFL shl 24) or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()
    }

    val newPreset =
      LutPreset(
        id = "custom_lut_${UUID.randomUUID().toString().take(8)}",
        name = cleanName,
        shortCode = shortCode,
        category = "Personal",
        brightness = effective.brightness,
        contrast = effective.contrast,
        saturation = effective.saturation,
        exposure = effective.exposure,
        temperature = effective.temperature,
        vignette = effective.vignette,
        shadowColorArgb = swatchArgb(0.14, 0.16, 0.22),
        midtoneColorArgb = swatchArgb(0.45, 0.48, 0.52),
        highlightColorArgb = swatchArgb(0.84, 0.86, 0.90),
        tint = effective.tint,
        shadows = effective.shadows,
        midtones = effective.midtones,
        highlights = effective.highlights,
        whites = effective.whites,
        blacks = effective.blacks,
        vibrance = effective.vibrance,
        liftX = effective.liftX,
        liftY = effective.liftY,
        gammaX = effective.gammaX,
        gammaY = effective.gammaY,
        gainX = effective.gainX,
        gainY = effective.gainY,
        isCustom = true,
      )
    val nextSaved = current.savedLutPresets + newPreset
    BuiltInLutPresets.syncCustomPresets(nextSaved)
    _uiState.update {
      it.copy(
        savedLutPresets = nextSaved,
        lastStatusMessage = "Preset LUT guardado: $cleanName",
      )
    }
    persistCurrentProjectAsync()
  }

  fun deleteCustomLutPreset(presetId: String) {
    val current = _uiState.value
    if (current.savedLutPresets.none { it.id == presetId }) return
    val nextSaved = current.savedLutPresets.filterNot { it.id == presetId }
    BuiltInLutPresets.syncCustomPresets(nextSaved)
    val cleanedClips =
      current.timeline.clips.map { clip ->
        when (clip.kind) {
          ClipKind.VIDEO ->
            if (clip.videoConfig?.lutPresetId == presetId) {
              clip.copy(videoConfig = clip.videoConfig.copy(lutPresetId = null))
            } else {
              clip
            }
          ClipKind.ADJUSTMENT ->
            if (clip.adjustmentConfig?.lutPresetId == presetId) {
              clip.copy(adjustmentConfig = clip.adjustmentConfig.copy(lutPresetId = null))
            } else {
              clip
            }
          else -> clip
        }
      }
    _uiState.update {
      it.copy(
        savedLutPresets = nextSaved,
        timeline = it.timeline.copy(clips = cleanedClips),
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun updateSelectedClipAudioParams(
    volume: Float? = null,
    pan: Float? = null,
    fadeInMs: Long? = null,
    fadeOutMs: Long? = null,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val baseVol =
      when (clip.kind) {
        ClipKind.VIDEO -> clip.videoConfig?.volume ?: 1.0f
        ClipKind.AUDIO -> clip.audioConfig?.volume ?: 1.0f
        else -> return
      }
    val basePan =
      when (clip.kind) {
        ClipKind.AUDIO -> clip.audioConfig?.pan ?: 0.0f
        else -> 0.0f
      }
    val baseFadeIn =
      when (clip.kind) {
        ClipKind.VIDEO -> clip.videoConfig?.fadeInMs ?: 0L
        ClipKind.AUDIO -> clip.audioConfig?.fadeInMs ?: 0L
      }
    val baseFadeOut =
      when (clip.kind) {
        ClipKind.VIDEO -> clip.videoConfig?.fadeOutMs ?: 0L
        ClipKind.AUDIO -> clip.audioConfig?.fadeOutMs ?: 0L
      }
    val result =
      engine.updateClipAudioPanAndMix(
        state = current.timeline,
        clipId = clip.clipId,
        volume = volume ?: baseVol,
        pan = pan ?: basePan,
        fadeInMs = fadeInMs ?: baseFadeIn,
        fadeOutMs = fadeOutMs ?: baseFadeOut,
      )
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun updateSelectedClipSpeed(speed: Float, fromSliderDrag: Boolean = false) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.updateClipSpeed(current.timeline, clip.clipId, speed)
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun updateSelectedClipSpeedCurve(
    speedCurve: List<SpeedCurvePoint>,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.updateClipSpeedCurve(current.timeline, clip.clipId, speedCurve)
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun applySelectedSpeedRampPreset(presetId: String) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return
    val result =
      when (presetId) {
        "normal" -> engine.updateClipSpeed(current.timeline, clip.clipId, 1.0f)
        "pulse" ->
          engine.updateClipSpeedCurve(
            current.timeline,
            clip.clipId,
            SpeedCurveEngine.presetRampFastMiddle(peakSpeed = 2.4f, edgeSpeed = 0.65f),
          )
        "hero" ->
          engine.updateClipSpeedCurve(
            current.timeline,
            clip.clipId,
            listOf(
              SpeedCurvePoint(0.0f, 2.0f),
              SpeedCurvePoint(0.45f, 0.55f),
              SpeedCurvePoint(1.0f, 1.6f),
            ),
          )
        "bullet" ->
          engine.updateClipSpeedCurve(
            current.timeline,
            clip.clipId,
            listOf(
              SpeedCurvePoint(0.0f, 0.5f),
              SpeedCurvePoint(0.5f, 3.0f),
              SpeedCurvePoint(1.0f, 0.5f),
            ),
          )
        else -> return
      }
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun setSelectedClipPreserveAudioPitch(preservePitch: Boolean) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.setClipPreserveAudioPitch(current.timeline, clip.clipId, preservePitch)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun openTransitionCutEditor(leftClipId: String, rightClipId: String) {
    val current = _uiState.value
    val left = current.timeline.clips.find { it.clipId == leftClipId } ?: return
    val right = current.timeline.clips.find { it.clipId == rightClipId } ?: return
    if (left.kind != ClipKind.VIDEO || right.kind != ClipKind.VIDEO) return
    val maxDur = minOf(left.durationMs, right.durationMs, 2_500L).coerceAtLeast(100L)
    val existing = left.videoConfig?.transitionOut ?: right.videoConfig?.transitionIn
    _uiState.update {
      it.copy(
        selectedTransitionCut =
          SelectedTransitionCut(
            leftClipId = leftClipId,
            rightClipId = rightClipId,
            currentTransition = existing,
            maxDurationMs = maxDur,
          ),
      )
    }
  }

  fun dismissTransitionCutEditor() {
    _uiState.update { it.copy(selectedTransitionCut = null) }
  }

  fun applyTransitionAtCut(type: TransitionType?, durationMs: Long) {
    val current = _uiState.value
    val cut = current.selectedTransitionCut ?: return
    val transition =
      type?.let {
        ClipTransition(type = it, durationMs = durationMs.coerceIn(100L, cut.maxDurationMs))
      }
    val result = engine.setTransitionAtCut(current.timeline, cut.leftClipId, cut.rightClipId, transition)
    commitInspectorMutation(current, result, fromSliderDrag = false)
    _uiState.update { it.copy(selectedTransitionCut = null) }
  }

  fun updateSelectedSubtitleInline(
    text: String? = null,
    fontSizeSp: Float? = null,
    alignment: SubtitleAlignment? = null,
    fromSliderDrag: Boolean = false,
    fontFamily: String? = null,
    textColorHex: String? = null,
    backgroundColorHex: String? = null,
    outlineColorHex: String? = null,
    outlineWidthDp: Float? = null,
    verticalOffsetNormalized: Float? = null,
    animation: SubtitleAnimation? = null,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.SUBTITLE) return
    val result =
      engine.updateSubtitleClip(current.timeline, clip.clipId) { cfg ->
        cfg.copy(
          text = text ?: cfg.text,
          fontFamily = fontFamily ?: cfg.fontFamily,
          fontSizeSp = (fontSizeSp ?: cfg.fontSizeSp).coerceIn(12f, 48f),
          textColorHex = textColorHex ?: cfg.textColorHex,
          backgroundColorHex = backgroundColorHex ?: cfg.backgroundColorHex,
          outlineColorHex = outlineColorHex ?: cfg.outlineColorHex,
          outlineWidthDp = (outlineWidthDp ?: cfg.outlineWidthDp).coerceIn(0f, 8f),
          alignment = alignment ?: cfg.alignment,
          verticalOffsetNormalized =
            (verticalOffsetNormalized ?: cfg.verticalOffsetNormalized).coerceIn(-0.45f, 0.45f),
          animation = animation ?: cfg.animation,
        )
      }
    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun updateSelectedSubtitleTiming(newStartMs: Long, newEndMs: Long) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.SUBTITLE) return
    val result = engine.updateSubtitleTiming(current.timeline, clip.clipId, newStartMs, newEndMs)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun splitSelectedSubtitle() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.SUBTITLE) return
    val result = engine.splitSubtitleClip(current.timeline, clip.clipId)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun mergeSelectedSubtitleWithNext() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.SUBTITLE) return
    val result = engine.mergeAdjacentSubtitles(current.timeline, clip.clipId)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun autoGenerateSubtitlesForTimeline(seedTranscript: String = "") {
    val current = _uiState.value
    val subTrack =
      current.timeline.tracks.firstOrNull { it.type == TrackType.SUBTITLE }
        ?: Track(trackId = "t_sub", type = TrackType.SUBTITLE, order = 2, name = "Subtítulos")
    val ensuredTimeline =
      if (current.timeline.tracks.any { it.trackId == subTrack.trackId }) {
        current.timeline
      } else {
        current.timeline.copy(tracks = current.timeline.tracks + subTrack)
      }

    val videoBounds =
      ensuredTimeline.clips
        .filter { it.isEnabled && (it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO) }
        .sortedBy { it.timelineStartMs }
        .map { it.timelineStartMs to it.timelineEndMs }

    val baseSpans =
      if (videoBounds.isNotEmpty()) {
        videoBounds
      } else {
        listOf(0L to maxOf(4_500L, ensuredTimeline.totalDurationMs))
      }

    val silences = current.detectedSilences
    val spokenIntervals = mutableListOf<Pair<Long, Long>>()
    for ((spanStart, spanEnd) in baseSpans) {
      var cursor = spanStart
      val overlappingSilences =
        silences.filter { it.endMs > spanStart && it.startMs < spanEnd }.sortedBy { it.startMs }
      for (silence in overlappingSilences) {
        val segEnd = silence.startMs.coerceIn(cursor, spanEnd)
        if (segEnd - cursor >= 400L) {
          splitIntoReadableCaptionWindows(cursor, segEnd, spokenIntervals)
        }
        cursor = maxOf(cursor, silence.endMs)
      }
      if (spanEnd - cursor >= 400L) {
        splitIntoReadableCaptionWindows(cursor, spanEnd, spokenIntervals)
      }
    }

    if (spokenIntervals.isEmpty()) return

    val style = current.savedSubtitleStyles.firstOrNull() ?: BuiltInSubtitleStyles.CLASSIC_CINEMA
    val generated =
      SubtitleBatchEngine.generateAutoSubtitlesForIntervals(
        trackId = subTrack.trackId,
        intervals = spokenIntervals,
        style = style,
        seedTranscript = seedTranscript,
      )
    if (generated.isEmpty()) return

    val updatedTimeline = SubtitleBatchEngine.replaceTrackSubtitles(ensuredTimeline, subTrack.trackId, generated)
    pushHistory(current)
    _uiState.update {
      it.copy(
        timeline = updatedTimeline,
        selectedClipId = generated.first().clipId,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = "Generados ${generated.size} bloques de subtítulo editables",
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  private fun splitIntoReadableCaptionWindows(
    startMs: Long,
    endMs: Long,
    out: MutableList<Pair<Long, Long>>,
  ) {
    val maxWindowMs = 2_600L
    var cursor = startMs
    while (cursor < endMs) {
      val nextEnd = minOf(endMs, cursor + maxWindowMs)
      if (nextEnd - cursor >= 400L) {
        out += cursor to nextEnd
      }
      cursor = nextEnd + 80L
    }
  }

  fun resetInspectorCategory(tab: InspectorTab) {
    val clip = _uiState.value.selectedClip ?: return
    when (tab) {
      InspectorTab.TRANSFORM ->
        updateSelectedClipTransformParams(
          scale = 1.0f,
          rotationDegrees = 0.0f,
          offsetX = 0.0f,
          offsetY = 0.0f,
          opacity = 1.0f,
          fromSliderDrag = false,
        )
      InspectorTab.COLOR_LUT ->
        updateSelectedClipColorParams(
          brightness = 0.0f,
          contrast = 1.0f,
          saturation = 1.0f,
          exposure = 0.0f,
          temperature = 0.0f,
          vignette = 0.0f,
          clearLutPreset = true,
          lutStrength = 1.0f,
          tint = 0.0f,
          shadows = 0.0f,
          midtones = 0.0f,
          highlights = 0.0f,
          blendOpacity = 1.0f,
          whites = 0.0f,
          blacks = 0.0f,
          vibrance = 0.0f,
          liftX = 0.0f,
          liftY = 0.0f,
          gammaX = 0.0f,
          gammaY = 0.0f,
          gainX = 0.0f,
          gainY = 0.0f,
          depthBlur = 0.0f,
          depthBlurRadius = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
          depthBlurFeather = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
          depthBlurMode = DepthBlurMaskMode.RADIAL,
          depthFocusX = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
          depthFocusY = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
          depthFocusRange = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
          chromaticAberration = 0.0f,
          glitchIntensity = 0.0f,
          fromSliderDrag = false,
        )
      InspectorTab.AUDIO ->
        updateSelectedClipAudioParams(
          volume = 1.0f,
          pan = 0.0f,
          fadeInMs = 0L,
          fadeOutMs = 0L,
          fromSliderDrag = false,
        )
      InspectorTab.SPEED_SUBTITLES ->
        when (clip.kind) {
          ClipKind.VIDEO, ClipKind.AUDIO -> {
            val current = _uiState.value
            val result = engine.updateClipSpeed(current.timeline, clip.clipId, 1.0f, preserveAudioPitch = true)
            commitInspectorMutation(current, result, fromSliderDrag = false)
          }
          ClipKind.SUBTITLE ->
            updateSelectedSubtitleInline(
              fontSizeSp = 20f,
              alignment = SubtitleAlignment.BOTTOM_CENTER,
              fromSliderDrag = false,
              fontFamily = "sans-serif",
              textColorHex = "#FFFFFF",
              backgroundColorHex = "#80000000",
              outlineColorHex = "#CC000000",
              outlineWidthDp = 2.0f,
              verticalOffsetNormalized = 0.0f,
              animation = SubtitleAnimation.NONE,
            )
          ClipKind.ADJUSTMENT -> Unit
        }
    }
  }

  private fun commitInspectorMutation(
    beforeState: TimelineSkeletonUiState,
    result: TimelineOperationResult,
    fromSliderDrag: Boolean,
  ) {
    when (result) {
      is TimelineOperationResult.Success -> {
        if (fromSliderDrag) {
          if (pendingEditHistorySnapshot == null) {
            pendingEditHistorySnapshot = captureHistorySnapshot(beforeState)
          }
          _uiState.update { it.copy(timeline = result.newState, lastStatusMessage = null) }
        } else {
          pushHistory(beforeState)
          _uiState.update {
            it.copy(
              timeline = result.newState,
              canUndo = historyManager.canUndo,
              canRedo = historyManager.canRedo,
              undoStepsCount = historyManager.undoCount,
              redoStepsCount = historyManager.redoCount,
              lastStatusMessage = null,
            )
          }
          syncPreviewComposition()
          persistCurrentProjectAsync()
        }
      }
      is TimelineOperationResult.Rejected -> {
        _uiState.update { it.copy(lastStatusMessage = mapRejectionReason(result.reason)) }
      }
    }
  }

  fun toggleRippleEnabled() {
    _uiState.update { state ->
      val nextEnabled = !state.timeline.editOptions.rippleEnabled
      state.copy(timeline = engine.setRippleEnabled(state.timeline, nextEnabled))
    }
  }

  fun cycleRippleScope() {
    _uiState.update { state ->
      val nextScope =
        when (state.timeline.editOptions.rippleScope) {
          RippleScope.SAME_TRACK -> RippleScope.ALL_TRACKS
          RippleScope.ALL_TRACKS -> RippleScope.SAME_TRACK
        }
      state.copy(timeline = engine.setRippleScope(state.timeline, nextScope))
    }
  }

  fun toggleSelectedClipEnabled() {
    val clipId = _uiState.value.selectedClipId ?: return
    applyOperation { current -> engine.toggleClipEnabled(current.timeline, clipId) }
  }

  fun deleteSelectedClip() {
    val clipId = _uiState.value.selectedClipId ?: return
    applyOperation { current -> engine.deleteClip(current.timeline, clipId) }
  }

  fun duplicateSelectedClip() {
    val current = _uiState.value
    val clipId = current.selectedClipId ?: return
    when (val result = engine.duplicateClip(current.timeline, clipId)) {
      is TimelineOperationResult.Success -> {
        pushHistory(current)
        val duplicatedId = result.affectedClipIds.firstOrNull() ?: current.selectedClipId
        _uiState.value =
          current.copy(
            timeline = result.newState,
            selectedClipId = duplicatedId,
            canUndo = historyManager.canUndo,
            canRedo = historyManager.canRedo,
            undoStepsCount = historyManager.undoCount,
            redoStepsCount = historyManager.redoCount,
            lastStatusMessage = null,
          )
        syncPreviewComposition()
        persistCurrentProjectAsync()
      }
      is TimelineOperationResult.Rejected -> {
        _uiState.update {
          it.copy(lastStatusMessage = mapRejectionReason(result.reason))
        }
      }
    }
  }

  fun addMarkerAtPlayhead(defaultLabelPrefix: String) {
    val current = _uiState.value
    pushHistory(current)
    val markerIndex = current.timeline.markers.size + 1
    val colors = MarkerColor.entries
    val nextColor = colors[(markerIndex - 1) % colors.size]
    val nextTimeline =
      engine.addMarker(
        state = current.timeline,
        positionMs = current.timeline.playheadMs,
        label = "$defaultLabelPrefix $markerIndex",
        color = nextColor,
      )
    _uiState.update { state ->
      state.copy(
        timeline = nextTimeline,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = null,
      )
    }
    persistCurrentProjectAsync()
  }

  fun jumpToPreviousMarker() {
    val marker = engine.findPreviousMarker(_uiState.value.timeline) ?: return
    seekPlayhead(marker.positionMs)
  }

  fun jumpToNextMarker() {
    val marker = engine.findNextMarker(_uiState.value.timeline) ?: return
    seekPlayhead(marker.positionMs)
  }

  fun undo() {
    val current = _uiState.value
    val previous = historyManager.undo(captureHistorySnapshot(current)) ?: return
    applyRestoredHistorySnapshot(previous)
  }

  fun redo() {
    val current = _uiState.value
    val next = historyManager.redo(captureHistorySnapshot(current)) ?: return
    applyRestoredHistorySnapshot(next)
  }

  private fun applyRestoredHistorySnapshot(snapshot: EditorHistorySnapshot) {
    _uiState.update { state ->
      val validSelected =
        snapshot.selectedClipId?.takeIf { id -> snapshot.timeline.clips.any { it.clipId == id } }
          ?: snapshot.timeline.clips.firstOrNull()?.clipId
      state.copy(
        timeline = snapshot.timeline,
        selectedClipId = validSelected,
        appliedAiAdjustments = snapshot.appliedAiAdjustments,
        editAnalysisReport = snapshot.editAnalysisReport,
        snapshots = snapshot.snapshots,
        sequences = snapshot.sequences,
        activeSequenceId = snapshot.activeSequenceId,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = null,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  // --- Paso 4: Audio (Gain/Fades, Mute/Solo, Ducking, Silence Detection) ---

  fun toggleTrackMute(trackId: String) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = engine.toggleTrackMute(state.timeline, trackId),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun toggleTrackSolo(trackId: String) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = engine.toggleTrackSolo(state.timeline, trackId),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun toggleTrackLocked(trackId: String) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = engine.toggleTrackLocked(state.timeline, trackId),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = null,
      )
    }
    persistCurrentProjectAsync()
  }

  fun toggleTrackVisible(trackId: String) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = engine.toggleTrackVisible(state.timeline, trackId),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = null,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun updateTrackVolume(
    trackId: String,
    volume: Float,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    if (!fromSliderDrag && pendingEditHistorySnapshot == null) {
      pushHistory(current)
    }
    _uiState.update { state ->
      state.copy(
        timeline = engine.updateTrackVolume(state.timeline, trackId, volume),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    syncPreviewComposition()
    if (!fromSliderDrag) {
      persistCurrentProjectAsync()
    }
  }

  fun updateClipAudioMix(clipId: String, volume: Float, fadeInMs: Long, fadeOutMs: Long) {
    val current = _uiState.value
    val result = engine.updateClipAudioMix(current.timeline, clipId, volume, fadeInMs, fadeOutMs)
    if (result is TimelineOperationResult.Success) {
      _uiState.update { it.copy(timeline = result.newState) }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    }
  }

  fun updateDuckingConfig(config: AudioDuckingConfig) {
    _uiState.update { state ->
      state.copy(timeline = engine.setDuckingConfig(state.timeline, config))
    }
    syncPreviewComposition()
  }

  fun analyzeSilencesForClip(clipId: String) {
    val current = _uiState.value
    val clip = current.timeline.clips.find { it.clipId == clipId } ?: return
    val asset = clip.assetId?.let { id -> current.assets.find { it.assetId == id } }
    if (asset == null) {
      _uiState.update {
        it.copy(
          lastStatusMessage = "Importa un clip multimedia local para analizar su audio PCM real",
        )
      }
      return
    }
    val mediaFile = File(projectRootDir, asset.relativePath)
    _uiState.update { it.copy(isAnalyzingSilence = true, lastStatusMessage = null) }

    viewModelScope.launch {
      silenceDetector
        .analyzeAudioFile(mediaFile)
        .onSuccess { silences ->
          _uiState.update {
            it.copy(
              detectedSilences = silences,
              isAnalyzingSilence = false,
              lastStatusMessage =
                if (silences.isEmpty()) "No se detectaron pausas largas en el audio" else null,
            )
          }
        }
        .onFailure { error ->
          _uiState.update {
            it.copy(
              isAnalyzingSilence = false,
              lastStatusMessage = error.localizedMessage ?: "Error al analizar audio",
            )
          }
        }
    }
  }

  fun removeDetectedSilences(clipId: String) {
    val current = _uiState.value
    if (current.detectedSilences.isEmpty()) return
    pushHistory(current)
    val nextTimeline =
      SilenceDetectorEngine.removeSilenceSegmentsFromClip(
        engine = engine,
        state = current.timeline,
        clipId = clipId,
        silenceSegments = current.detectedSilences,
        ripple = true,
      )
    _uiState.update {
      it.copy(
        timeline = nextTimeline,
        detectedSilences = emptyList(),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  // --- Paso 4: Subtítulos editables en bloque y estilos guardables ---

  fun applySubtitleStyleToAll(preset: SubtitleStylePreset) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = SubtitleBatchEngine.applyStylePreset(state.timeline, preset),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    persistCurrentProjectAsync()
  }

  fun saveCustomSubtitleStyleFromSelected(name: String) {
    val config = _uiState.value.selectedClip?.subtitleConfig ?: return
    val repo = subtitleStyleRepo ?: return
    viewModelScope.launch {
      repo.saveStyleFromConfig(name, config)
      val refreshed = repo.loadAllStyles()
      _uiState.update { it.copy(savedSubtitleStyles = refreshed) }
    }
  }

  fun generateBatchSubtitles(rawScriptOrSrt: String, style: SubtitleStylePreset) {
    val current = _uiState.value
    pushHistory(current)
    val parsedClips =
      SubtitleBatchEngine.parseScriptOrSrt(
        rawInput = rawScriptOrSrt,
        trackId = "t_sub",
        style = style,
        startAtMs = current.timeline.playheadMs,
      )
    val nextTimeline =
      SubtitleBatchEngine.replaceTrackSubtitles(current.timeline, "t_sub", parsedClips)
    _uiState.update { state ->
      state.copy(
        timeline = nextTimeline,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        selectedClipId = parsedClips.firstOrNull()?.clipId ?: state.selectedClipId,
      )
    }
    persistCurrentProjectAsync()
  }

  fun saveBatchSubtitleEdits(edits: List<SubtitleBatchEdit>) {
    val current = _uiState.value
    pushHistory(current)
    _uiState.update { state ->
      state.copy(
        timeline = SubtitleBatchEngine.applyBatchEdits(state.timeline, edits),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    persistCurrentProjectAsync()
  }

  // --- Paso 4: Copiado selectivo de atributos e Instantáneas / Duplicado ---

  fun availableAttributesForSelected(): Set<AttributeCategory> =
    _uiState.value.selectedClip?.let { attributeEngine.availableCategoriesFor(it) }.orEmpty()

  fun copySelectedClipAttributes(categories: Set<AttributeCategory>) {
    val clip = _uiState.value.selectedClip ?: return
    val copied = attributeEngine.copyFromClip(clip, categories)
    _uiState.update { it.copy(attributeClipboard = copied) }
  }

  fun pasteAttributesToCompatibleClips(categories: Set<AttributeCategory>) {
    val current = _uiState.value
    val clipboard = current.attributeClipboard ?: return
    val targetIds =
      current.timeline.clips
        .filter { it.clipId != clipboard.sourceClipId }
        .map { it.clipId }
    if (targetIds.isEmpty()) return

    applyOperation {
      attributeEngine.pasteOntoClips(
        state = current.timeline,
        targetClipIds = targetIds,
        clipboard = clipboard,
        categoriesToApply = categories,
      )
    }
  }

  fun createTimelineSnapshot() {
    val current = _uiState.value
    pushHistory(current)
    val snapName = "Instantánea ${current.snapshots.size + 1}"
    projectDoc =
      snapshotManager.createSnapshot(
        project = projectDoc,
        currentTimeline = current.timeline,
        name = snapName,
      )
    _uiState.update {
      it.copy(
        snapshots = projectDoc.snapshots,
        sequences = projectDoc.sequences,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    persistCurrentProjectAsync()
  }

  fun restoreTimelineSnapshot(snapshotId: String) {
    val current = _uiState.value
    val restored = snapshotManager.restoreSnapshot(projectDoc, snapshotId) ?: return
    pushHistory(current)
    projectDoc = restored.first
    _uiState.update {
      it.copy(
        timeline = restored.second,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        selectedClipId = restored.second.clips.firstOrNull()?.clipId,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun duplicateTimelineAsSequence() {
    val current = _uiState.value
    pushHistory(current)
    val nextName = "Versión ${current.sequences.size + 1}"
    val (updatedDoc, newSeq) =
      snapshotManager.duplicateAsSequence(projectDoc, current.timeline, nextName)
    projectDoc = updatedDoc
    _uiState.update {
      it.copy(
        timeline = newSeq.timeline,
        sequences = updatedDoc.sequences,
        activeSequenceId = updatedDoc.activeSequenceId,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    persistCurrentProjectAsync()
  }

  fun switchTimelineSequence(sequenceId: String) {
    val current = _uiState.value
    val switched =
      snapshotManager.switchSequence(projectDoc, current.timeline, sequenceId) ?: return
    pushHistory(current)
    projectDoc = switched.first
    _uiState.update {
      it.copy(
        timeline = switched.second,
        sequences = projectDoc.sequences,
        activeSequenceId = projectDoc.activeSequenceId,
        selectedClipId = switched.second.clips.firstOrNull()?.clipId,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  // --- Paso 3: Preview Quality, Proxies, Import & Export ---

  fun cyclePreviewQuality() {
    _uiState.update { state ->
      val profiles = PreviewQualityProfile.entries
      val nextIndex = (profiles.indexOf(state.renderSettings.previewQuality) + 1) % profiles.size
      state.copy(
        renderSettings = state.renderSettings.copy(previewQuality = profiles[nextIndex]),
      )
    }
    syncPreviewComposition()
  }

  fun setPreviewQuality(profile: PreviewQualityProfile) {
    if (_uiState.value.renderSettings.previewQuality == profile) return
    _uiState.update { state ->
      state.copy(
        renderSettings = state.renderSettings.copy(previewQuality = profile),
      )
    }
    syncPreviewComposition()
  }

  fun toggleUseProxiesForPreview() {
    _uiState.update { state ->
      state.copy(
        renderSettings =
          state.renderSettings.copy(
            useProxiesForPreview = !state.renderSettings.useProxiesForPreview,
          ),
      )
    }
    syncPreviewComposition()
  }

  fun openRecentProject(projectId: String) {
    syncCurrentDocIntoStore()
    val previousDoc = projectDoc
    val cachedTarget = projectStore[projectId]
    if (cachedTarget != null) {
      applyLoadedProjectDocument(cachedTarget)
    }
    val repo = projectRepository
    if (repo != null) {
      viewModelScope.launch {
        repo.saveProject(previousDoc)
        val diskDoc = repo.loadProject(projectId).getOrNull()
        if (diskDoc != null && (cachedTarget == null || diskDoc.updatedAtIso > cachedTarget.updatedAtIso)) {
          applyLoadedProjectDocument(diskDoc)
        }
        refreshRecentProjectsFromDiskOrStore(projectDoc.projectId)
        refreshCacheStorageStatsInternal()
      }
    } else if (tempCacheStorageManager != null) {
      viewModelScope.launch {
        refreshCacheStorageStatsInternal()
      }
    }
  }

  fun createNewProject(name: String) {
    syncCurrentDocIntoStore()
    val previousDoc = projectDoc
    val now = Instant.now().toString()
    val cleanName = name.trim().ifBlank { "Proyecto ${projectStore.size + 1}" }
    val newTimeline =
      TimelineState(
        tracks =
          listOf(
            Track(trackId = "t_adj", type = TrackType.ADJUSTMENT, order = 0, name = "Ajuste LUT"),
            Track(trackId = "t_sub", type = TrackType.SUBTITLE, order = 1, name = "Subtítulos"),
            Track(trackId = "t_vid", type = TrackType.VIDEO, order = 2, name = "Video V1"),
            Track(trackId = "t_aud", type = TrackType.AUDIO, order = 3, name = "Audio A1"),
          ),
      )
    val newDoc =
      ProjectDocument(
        projectId = "proj_${UUID.randomUUID().toString().take(6)}",
        name = cleanName,
        createdAtIso = now,
        updatedAtIso = now,
        timeline = newTimeline,
        activeSequenceId = "seq_main",
        sequences =
          listOf(
            TimelineSequence(
              sequenceId = "seq_main",
              name = "Principal",
              createdAtIso = now,
              timeline = newTimeline,
            ),
          ),
      )
    projectStore[newDoc.projectId] = newDoc
    projectDoc = newDoc
    BuiltInLutPresets.syncCustomPresets(newDoc.savedLutPresets)
    historyManager.clear()
    _uiState.update {
      it.copy(
        projectName = newDoc.name,
        recentProjects = buildRecentSummaries(newDoc.projectId),
        timeline = newDoc.timeline,
        assets = emptyList(),
        selectedClipId = null,
        canUndo = false,
        canRedo = false,
        undoStepsCount = 0,
        redoStepsCount = 0,
        appliedAiAdjustments = emptyList(),
        editAnalysisReport = null,
        snapshots = emptyList(),
        sequences = newDoc.sequences,
        activeSequenceId = newDoc.activeSequenceId,
        savedLutPresets = newDoc.savedLutPresets,
        lastStatusMessage = null,
      )
    }
    syncPreviewComposition()
    if (projectRepository != null || tempCacheStorageManager != null) {
      viewModelScope.launch {
        projectRepository?.saveProject(previousDoc)
        projectRepository?.saveProject(newDoc)
        refreshRecentProjectsFromDiskOrStore(newDoc.projectId)
        refreshCacheStorageStatsInternal()
      }
    }
  }

  fun saveProjectNow() {
    syncCurrentDocIntoStore()
    val docSnapshot = projectDoc
    viewModelScope.launch {
      projectRepository?.saveProject(docSnapshot)
      refreshRecentProjectsFromDiskOrStore(docSnapshot.projectId)
      refreshCacheStorageStatsInternal()
    }
  }

  fun togglePlayPausePreview() {
    previewController?.togglePlayPause()
  }

  fun seekToStart() {
    seekPlayhead(0L)
  }

  fun stepFrameBackward() {
    val currentMs = _uiState.value.timeline.playheadMs
    seekPlayhead((currentMs - TimelineViewportErgonomics.FRAME_STEP_30FPS_MS).coerceAtLeast(0L))
  }

  fun stepFrameForward() {
    val timeline = _uiState.value.timeline
    val maxDurationMs = timeline.totalDurationMs.coerceAtLeast(0L)
    val nextMs =
      if (maxDurationMs > 0L) {
        (timeline.playheadMs + TimelineViewportErgonomics.FRAME_STEP_30FPS_MS).coerceAtMost(maxDurationMs)
      } else {
        timeline.playheadMs + TimelineViewportErgonomics.FRAME_STEP_30FPS_MS
      }
    seekPlayhead(nextMs)
  }

  fun togglePreviewLoop() {
    val nextLoop = !_uiState.value.isPreviewLoopEnabled
    previewController?.setLoopEnabled(nextLoop)
    _uiState.update { it.copy(isPreviewLoopEnabled = nextLoop) }
  }

  fun addOrUpdateKeyframeAtPlayhead() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return
    val playheadMs = current.timeline.playheadMs
    if (playheadMs !in clip.timelineStartMs..clip.timelineEndMs) {
      _uiState.update {
        it.copy(lastStatusMessage = "Coloca el cabezal dentro del clip para fijar un keyframe")
      }
      return
    }
    val offsetMs = (playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    addOrUpdateKeyframe(offsetMs = offsetMs)
  }

  fun addOrUpdateKeyframe(
    offsetMs: Long? = null,
    scale: Float? = null,
    opacity: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    rotationDegrees: Float? = null,
    volume: Float? = null,
    interpolation: KeyframeInterpolation? = null,
    depthBlur: Float? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return
    val targetOffsetMs =
      offsetMs ?: (current.timeline.playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val result =
      engine.upsertKeyframe(
        state = current.timeline,
        clipId = clip.clipId,
        offsetMs = targetOffsetMs,
        scale = scale,
        opacity = opacity,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDegrees = rotationDegrees,
        volume = volume,
        interpolation = interpolation,
        depthBlur = depthBlur,
        depthFocusX = depthFocusX,
        depthFocusY = depthFocusY,
        depthFocusRange = depthFocusRange,
        mergeToleranceMs = 40L,
      )
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun addRackFocusKeyframeAtPlayhead() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO) {
      _uiState.update {
        it.copy(lastStatusMessage = "Los keyframes de rack focus se animan en clips de vídeo")
      }
      return
    }
    val playheadMs = current.timeline.playheadMs
    if (playheadMs !in clip.timelineStartMs..clip.timelineEndMs) {
      _uiState.update {
        it.copy(lastStatusMessage = "Coloca el cabezal dentro del clip para fijar el rack focus")
      }
      return
    }
    val cfg = clip.videoConfig ?: return
    val offsetMs = (playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val evaluated = clip.evaluatePropertiesAt(offsetMs)
    addOrUpdateKeyframe(
      offsetMs = offsetMs,
      depthBlur = if (evaluated.depthBlur > 0.005f) evaluated.depthBlur else cfg.depthBlur,
      depthFocusX = evaluated.depthFocusX,
      depthFocusY = evaluated.depthFocusY,
      depthFocusRange = evaluated.depthFocusRange,
    )
  }

  fun moveSelectedClipKeyframe(keyframeId: String, newOffsetMs: Long) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.moveKeyframe(current.timeline, clip.clipId, keyframeId, newOffsetMs)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun setSelectedKeyframeInterpolation(keyframeId: String, interpolation: KeyframeInterpolation) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.updateKeyframeInterpolation(current.timeline, clip.clipId, keyframeId, interpolation)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun removeKeyframeFromSelected(keyframeId: String) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.removeKeyframe(current.timeline, clip.clipId, keyframeId)
    if (result is TimelineOperationResult.Success) {
      commitInspectorMutation(current, result, fromSliderDrag = false)
    }
  }

  fun clearSelectedClipKeyframes() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val result = engine.clearKeyframes(current.timeline, clip.clipId)
    commitInspectorMutation(current, result, fromSliderDrag = false)
  }

  fun jumpToKeyframe(keyframeId: String) {
    val clip = _uiState.value.selectedClip ?: return
    val target = clip.keyframes().find { it.keyframeId == keyframeId } ?: return
    seekPlayhead(clip.timelineStartMs + target.offsetMs)
  }

  fun jumpToPreviousKeyframe() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val prev = engine.findPreviousKeyframe(current.timeline, clip.clipId)
    if (prev == null) {
      _uiState.update { it.copy(lastStatusMessage = "No hay keyframe anterior en este clip") }
      return
    }
    seekPlayhead(clip.timelineStartMs + prev.offsetMs)
  }

  fun jumpToNextKeyframe() {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    val next = engine.findNextKeyframe(current.timeline, clip.clipId)
    if (next == null) {
      _uiState.update { it.copy(lastStatusMessage = "No hay keyframe siguiente en este clip") }
      return
    }
    seekPlayhead(clip.timelineStartMs + next.offsetMs)
  }

  fun updateSelectedClipTransform(
    scale: Float,
    opacity: Float,
    rotationDegrees: Float,
    fromSliderDrag: Boolean = false,
  ) {
    updateSelectedClipKeyframeProperties(
      scale = scale,
      opacity = opacity,
      rotationDegrees = rotationDegrees,
      fromSliderDrag = fromSliderDrag,
    )
  }

  fun updateSelectedClipKeyframeProperties(
    scale: Float? = null,
    opacity: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    rotationDegrees: Float? = null,
    volume: Float? = null,
    interpolation: KeyframeInterpolation? = null,
    depthBlur: Float? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    val current = _uiState.value
    val clip = current.selectedClip ?: return
    if (clip.kind != ClipKind.VIDEO && clip.kind != ClipKind.AUDIO) return
    val playheadOffset = (current.timeline.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
    val keyframes = clip.keyframes()
    val targetKf =
      if (current.timeline.playheadMs in clip.timelineStartMs..clip.timelineEndMs) {
        keyframes.firstOrNull { abs(it.offsetMs - playheadOffset) <= 40L }
      } else {
        null
      }

    val safeScale = scale?.coerceIn(KeyframeValidator.MIN_SCALE, KeyframeValidator.MAX_SCALE)
    val safeOpacity = opacity?.coerceIn(KeyframeValidator.MIN_OPACITY, KeyframeValidator.MAX_OPACITY)
    val safeOffsetX = offsetX?.coerceIn(KeyframeValidator.MIN_OFFSET, KeyframeValidator.MAX_OFFSET)
    val safeOffsetY = offsetY?.coerceIn(KeyframeValidator.MIN_OFFSET, KeyframeValidator.MAX_OFFSET)
    val safeRotation =
      rotationDegrees?.coerceIn(KeyframeValidator.MIN_ROTATION_DEGREES, KeyframeValidator.MAX_ROTATION_DEGREES)
    val safeVolume = volume?.coerceIn(KeyframeValidator.MIN_VOLUME, KeyframeValidator.MAX_VOLUME)
    val safeDepthBlur = depthBlur?.let(ColorGradeBounds::clampDepthBlur)
    val safeFocusX = depthFocusX?.let(ColorGradeBounds::clampDepthFocusCoord)
    val safeFocusY = depthFocusY?.let(ColorGradeBounds::clampDepthFocusCoord)
    val safeFocusRange = depthFocusRange?.let(ColorGradeBounds::clampDepthFocusRange)

    val result: TimelineOperationResult =
      when (clip.kind) {
        ClipKind.VIDEO ->
          engine.updateVideoTransform(current.timeline, clip.clipId) { videoCfg ->
            val updatedKfs =
              if (targetKf != null) {
                videoCfg.keyframes.map { kf ->
                  if (kf.keyframeId == targetKf.keyframeId) {
                    kf.copy(
                      scale = safeScale ?: kf.scale,
                      opacity = safeOpacity ?: kf.opacity,
                      offsetX = safeOffsetX ?: kf.offsetX,
                      offsetY = safeOffsetY ?: kf.offsetY,
                      rotationDegrees = safeRotation ?: kf.rotationDegrees,
                      volume = safeVolume ?: kf.volume,
                      interpolation = interpolation ?: kf.interpolation,
                      depthBlur = safeDepthBlur ?: kf.depthBlur,
                      depthFocusX = safeFocusX ?: kf.depthFocusX,
                      depthFocusY = safeFocusY ?: kf.depthFocusY,
                      depthFocusRange = safeFocusRange ?: kf.depthFocusRange,
                    )
                  } else {
                    kf
                  }
                }
              } else {
                videoCfg.keyframes
              }
            videoCfg.copy(
              scale = safeScale ?: videoCfg.scale,
              opacity = safeOpacity ?: videoCfg.opacity,
              offsetX = safeOffsetX ?: videoCfg.offsetX,
              offsetY = safeOffsetY ?: videoCfg.offsetY,
              rotationDegrees = safeRotation ?: videoCfg.rotationDegrees,
              volume = safeVolume ?: videoCfg.volume,
              depthBlur = safeDepthBlur ?: videoCfg.depthBlur,
              depthFocusX = safeFocusX ?: videoCfg.depthFocusX,
              depthFocusY = safeFocusY ?: videoCfg.depthFocusY,
              depthFocusRange = safeFocusRange ?: videoCfg.depthFocusRange,
              keyframes = updatedKfs,
            )
          }

        ClipKind.AUDIO -> {
          val cfg = clip.audioConfig ?: AudioClipConfig()
          val updatedKfs =
            if (targetKf != null) {
              cfg.keyframes.map { kf ->
                if (kf.keyframeId == targetKf.keyframeId) {
                  kf.copy(
                    volume = safeVolume ?: kf.volume ?: cfg.volume,
                    interpolation = interpolation ?: kf.interpolation,
                  )
                } else {
                  kf
                }
              }
            } else {
              cfg.keyframes
            }
          val updatedClip =
            clip.copy(
              audioConfig =
                cfg.copy(
                  volume = safeVolume ?: cfg.volume,
                  keyframes = updatedKfs,
                ),
            )
          TimelineOperationResult.Success(
            newState =
              current.timeline.copy(
                clips = current.timeline.clips.map { if (it.clipId == clip.clipId) updatedClip else it },
              ),
            affectedClipIds = listOf(clip.clipId),
          )
        }

        else -> return
      }

    commitInspectorMutation(current, result, fromSliderDrag)
  }

  fun validateAsset(asset: MediaAsset): MediaValidationReport {
    val file = File(projectRootDir, asset.relativePath)
    val exists = file.exists() && file.length() > 0L
    val playableDuration = asset.fingerprint.durationMs >= 200L
    val width = asset.fingerprint.width ?: 0
    val height = asset.fingerprint.height ?: 0
    val resLabel = if (width > 0 && height > 0) "${width}x${height}" else "Resolución desconocida"
    val warnings = buildList {
      if (!exists || asset.relinkState.status != RelinkStatus.ONLINE) {
        add("Archivo ausente en la carpeta portable")
      }
      if (!playableDuration) {
        add("Duración inferior a 200 ms")
      }
      if (!asset.fingerprint.hasAudio) {
        add("Pista sin audio detectado (solo imagen)")
      }
      if (asset.fingerprint.rotationDegrees != 0) {
        add("Rotación metadato: ${asset.fingerprint.rotationDegrees}°")
      }
    }
    return MediaValidationReport(
      isFilePresent = exists,
      isPlayableDuration = playableDuration,
      hasAudioStream = asset.fingerprint.hasAudio,
      resolutionLabel = resLabel,
      warnings = warnings,
    )
  }

  fun importLocalVideo(
    uri: Uri,
    addToTimelineImmediately: Boolean = true,
  ) {
    val probe = mediaProbe ?: return
    val mediaDirName =
      if (tempCacheStorageManager != null) {
        TempCacheStorageManager.DIR_TEMP_MEDIA
      } else {
        "media"
      }
    _uiState.update { it.copy(isImportingMedia = true, lastStatusMessage = null) }
    viewModelScope.launch {
      probe.importIntoPortableProject(uri, projectRootDir, mediaDirName = mediaDirName)
        .onSuccess { asset ->
          _uiState.update {
            it.copy(
              assets = it.assets + asset,
              isImportingMedia = false,
              lastImportedAssetId = asset.assetId,
              lastStatusMessage = null,
            )
          }
          if (addToTimelineImmediately) {
            appendAssetToTimeline(asset.assetId)
          } else {
            persistCurrentProjectAsync()
          }
          refreshCacheStorageStatsInternal()
        }
        .onFailure { error ->
          val safeMessage =
            when (error) {
              is SecurityException -> "Sin permiso para leer el video seleccionado"
              else -> {
                val msg = error.message.orEmpty()
                if (msg.contains("permiso") || msg.contains("abrir") || msg.contains("vacío")) {
                  msg
                } else {
                  "No se pudo importar el video seleccionado"
                }
              }
            }
          _uiState.update {
            it.copy(
              isImportingMedia = false,
              lastStatusMessage = safeMessage,
            )
          }
        }
    }
  }

  fun appendAssetToTimeline(assetId: String) {
    appendAssetsToTimeline(listOf(assetId))
  }

  fun appendAssetsToTimeline(assetIds: List<String>) {
    if (assetIds.isEmpty()) return
    val current = _uiState.value
    val selectedAssets =
      assetIds.distinct().mapNotNull { id ->
        current.assets.find { it.assetId == id }
      }
    if (selectedAssets.isEmpty()) return

    var workingTimeline = current.timeline
    var appendStartMs = workingTimeline.clipsForTrack("t_vid").maxOfOrNull { it.timelineEndMs } ?: 0L
    var lastInsertedClipId: String? = null

    for (asset in selectedAssets) {
      val durationMs = asset.fingerprint.durationMs.coerceAtLeast(1_000L)
      val newClip =
        TimelineClip(
          clipId = "clip_${UUID.randomUUID().toString().take(6)}",
          trackId = "t_vid",
          kind = ClipKind.VIDEO,
          label = asset.displayName,
          timelineStartMs = appendStartMs,
          timelineEndMs = appendStartMs + durationMs,
          sourceInMs = 0L,
          sourceOutMs = durationMs,
          assetId = asset.assetId,
          videoConfig = VideoClipConfig(),
        )
      val inserted = engine.insertClip(workingTimeline, newClip, ripple = false)
      if (inserted is TimelineOperationResult.Success) {
        workingTimeline = inserted.newState
        appendStartMs = newClip.timelineEndMs
        lastInsertedClipId = newClip.clipId
      }
    }

    if (lastInsertedClipId != null) {
      pushHistory(current)
      _uiState.update {
        it.copy(
          timeline = workingTimeline,
          selectedClipId = lastInsertedClipId,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage =
            if (selectedAssets.size > 1) {
              "${selectedAssets.size} clips añadidos a la línea de tiempo"
            } else {
              null
            },
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    }
  }

  // --- Inserción directa en Playhead y Gestión Dinámica de Pistas ---

  fun resolveMediaDirectory(): File {
    val mediaDirName =
      if (tempCacheStorageManager != null) {
        TempCacheStorageManager.DIR_TEMP_MEDIA
      } else {
        "media"
      }
    return File(projectRootDir, mediaDirName).apply { mkdirs() }
  }

  private fun resolveMediaDirName(): String =
    if (tempCacheStorageManager != null) TempCacheStorageManager.DIR_TEMP_MEDIA else "media"

  fun addTrack(
    type: TrackType,
    isOverlay: Boolean = false,
    customName: String? = null,
  ): Track {
    val current = _uiState.value
    pushHistory(current)
    val (nextTimeline, createdTrack) =
      engine.addTrack(
        state = current.timeline,
        type = type,
        isOverlay = isOverlay,
        customName = customName,
      )
    _uiState.update {
      it.copy(
        timeline = nextTimeline,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = "Pista creada: ${createdTrack.name}",
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
    return createdTrack
  }

  fun openInsertSheet(
    kind: TimelineInsertKind,
    targetTrackId: String? = null,
    anchorMs: Long? = null,
  ) {
    val current = _uiState.value
    val effectiveAnchor = (anchorMs ?: current.timeline.playheadMs).coerceAtLeast(0L)
    val resolvedTrackId = targetTrackId ?: defaultTrackIdForInsertKind(current.timeline, kind)
    _uiState.update {
      it.copy(
        activeInsertRequest =
          TimelineInsertRequest(
            kind = kind,
            targetTrackId = resolvedTrackId,
            anchorMs = effectiveAnchor,
          ),
      )
    }
  }

  fun switchInsertTab(kind: TimelineInsertKind) {
    val current = _uiState.value
    val active = current.activeInsertRequest ?: return
    val currentTrack = active.targetTrackId?.let { id -> current.timeline.tracks.find { it.trackId == id } }
    val keepsTrack =
      when (kind) {
        TimelineInsertKind.AUDIO -> currentTrack?.type == TrackType.AUDIO
        TimelineInsertKind.OVERLAY -> currentTrack?.isOverlay == true
        TimelineInsertKind.EFFECTS -> currentTrack?.type == TrackType.ADJUSTMENT
        TimelineInsertKind.TEXT -> currentTrack?.type == TrackType.SUBTITLE
      }
    val nextTrackId =
      if (keepsTrack) active.targetTrackId else defaultTrackIdForInsertKind(current.timeline, kind)
    _uiState.update {
      it.copy(
        activeInsertRequest =
          active.copy(
            kind = kind,
            targetTrackId = nextTrackId,
          ),
      )
    }
  }

  private fun defaultTrackIdForInsertKind(
    timeline: TimelineState,
    kind: TimelineInsertKind,
  ): String? {
    val sorted = timeline.tracksSorted()
    return when (kind) {
      TimelineInsertKind.AUDIO -> sorted.firstOrNull { !it.isLocked && it.type == TrackType.AUDIO }?.trackId
      TimelineInsertKind.OVERLAY -> sorted.firstOrNull { !it.isLocked && it.isOverlay }?.trackId
      TimelineInsertKind.EFFECTS -> sorted.firstOrNull { !it.isLocked && it.type == TrackType.ADJUSTMENT }?.trackId
      TimelineInsertKind.TEXT -> sorted.firstOrNull { !it.isLocked && it.type == TrackType.SUBTITLE }?.trackId
    }
  }

  fun openInsertForTrack(
    track: Track,
    useTrackEnd: Boolean = false,
  ) {
    val state = _uiState.value.timeline
    val kind =
      when {
        track.type == TrackType.AUDIO -> TimelineInsertKind.AUDIO
        track.type == TrackType.ADJUSTMENT -> TimelineInsertKind.EFFECTS
        track.type == TrackType.SUBTITLE -> TimelineInsertKind.TEXT
        track.type == TrackType.VIDEO -> TimelineInsertKind.OVERLAY
        else -> TimelineInsertKind.OVERLAY
      }
    val anchor =
      if (useTrackEnd) {
        state.clipsForTrack(track.trackId).maxOfOrNull { it.timelineEndMs } ?: state.playheadMs
      } else {
        state.playheadMs
      }
    openInsertSheet(kind = kind, targetTrackId = track.trackId, anchorMs = anchor)
  }

  fun dismissInsertSheet() {
    _uiState.update { it.copy(activeInsertRequest = null) }
  }

  private fun resolveTargetTrackAndState(
    initialState: TimelineState,
    preferredTrackId: String?,
    type: TrackType,
    isOverlay: Boolean,
    startMs: Long,
    durationMs: Long,
  ): Pair<TimelineState, String> {
    val endMs = startMs + durationMs
    val candidates =
      buildList {
        preferredTrackId
          ?.let { id -> initialState.tracks.find { it.trackId == id } }
          ?.takeIf { !it.isLocked }
          ?.let { add(it) }
        initialState.tracksSorted()
          .filter { track ->
            !track.isLocked &&
              track.type == type &&
              (!isOverlay || track.isOverlay) &&
              track.trackId != preferredTrackId
          }
          .forEach { add(it) }
      }

    val nonOverlapping =
      candidates.firstOrNull { track ->
        !engine.hasClipOverlapOnTrack(initialState, track.trackId, startMs, endMs)
      }
    if (nonOverlapping != null) {
      return initialState to nonOverlapping.trackId
    }

    val (expandedState, newTrack) =
      engine.addTrack(
        state = initialState,
        type = type,
        isOverlay = isOverlay,
      )
    return expandedState to newTrack.trackId
  }

  fun importAudioAndInsertAtPlayhead(
    uri: Uri,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val probe = mediaProbe ?: return
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    _uiState.update { it.copy(isImportingMedia = true, activeInsertRequest = null, lastStatusMessage = null) }
    viewModelScope.launch {
      probe.importIntoPortableProject(
        sourceUri = uri,
        projectRootDir = projectRootDir,
        mediaDirName = resolveMediaDirName(),
        preferredMediaType = MediaType.AUDIO,
      ).onSuccess { asset ->
        insertAudioAssetAtAnchor(asset = asset, targetTrackId = targetTrackId, anchorMs = insertAnchor)
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isImportingMedia = false,
            lastStatusMessage = err.message ?: "No se pudo importar el audio seleccionado",
          )
        }
      }
    }
  }

  fun extractAudioFromVideoAssetAtPlayhead(
    assetId: String,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val current = _uiState.value
    val sourceAsset = current.assets.find { it.assetId == assetId } ?: return
    val probe = mediaProbe ?: return
    val videoFile = File(projectRootDir, sourceAsset.relativePath)
    val insertAnchor = (anchorMs ?: current.timeline.playheadMs).coerceAtLeast(0L)
    _uiState.update { it.copy(isImportingMedia = true, activeInsertRequest = null, lastStatusMessage = null) }
    viewModelScope.launch {
      VideoAudioExtractor.extractFromFile(
        sourceVideoFile = videoFile,
        mediaDir = resolveMediaDirectory(),
      ).onSuccess { extracted ->
        val cleanName = "${sourceAsset.displayName.substringBeforeLast('.')} (Audio).m4a"
        val asset =
          probe.probeAndBuildAsset(
            file = extracted.outputFile,
            projectRootDir = projectRootDir,
            mediaType = MediaType.AUDIO,
            displayName = cleanName,
          ).let { built ->
            built.copy(
              fingerprint =
                built.fingerprint.copy(
                  durationMs = maxOf(built.fingerprint.durationMs, extracted.durationMs),
                ),
            )
          }
        insertAudioAssetAtAnchor(asset = asset, targetTrackId = targetTrackId, anchorMs = insertAnchor)
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isImportingMedia = false,
            lastStatusMessage = err.message ?: "El video no tiene pista de audio extraíble",
          )
        }
      }
    }
  }

  fun extractAudioFromVideoUriAtPlayhead(
    context: Context,
    uri: Uri,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val probe = mediaProbe ?: return
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    _uiState.update { it.copy(isImportingMedia = true, activeInsertRequest = null, lastStatusMessage = null) }
    viewModelScope.launch {
      VideoAudioExtractor.extractFromUri(
        context = context,
        uri = uri,
        mediaDir = resolveMediaDirectory(),
      ).onSuccess { extracted ->
        val cleanName = "Audio extraído (${extracted.outputFile.nameWithoutExtension.takeLast(4)}).m4a"
        val built =
          probe.probeAndBuildAsset(
            file = extracted.outputFile,
            projectRootDir = projectRootDir,
            mediaType = MediaType.AUDIO,
            displayName = cleanName,
          )
        val asset =
          built.copy(
            fingerprint =
              built.fingerprint.copy(
                durationMs = maxOf(built.fingerprint.durationMs, extracted.durationMs),
              ),
          )
        insertAudioAssetAtAnchor(asset = asset, targetTrackId = targetTrackId, anchorMs = insertAnchor)
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isImportingMedia = false,
            lastStatusMessage = err.message ?: "No se pudo extraer audio del video",
          )
        }
      }
    }
  }

  fun insertRecordedVoiceoverAtPlayhead(
    audioFile: File,
    label: String = "Voz en off",
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    val probe = mediaProbe
    val relPath =
      runCatching { audioFile.relativeTo(projectRootDir).invariantSeparatorsPath }
        .getOrDefault("media/${audioFile.name}")
    val fp =
      probe?.probeFile(audioFile, fallbackMimeType = "audio/mp4")
        ?: com.example.core.model.MediaFingerprint(
          byteSize = audioFile.length(),
          durationMs = 3_000L,
          partialSha256 = "voice_${audioFile.name}",
          mimeType = "audio/mp4",
          hasAudio = true,
        )
    val asset =
      MediaAsset(
        assetId = "asset_${UUID.randomUUID().toString().take(8)}",
        displayName = label,
        type = MediaType.AUDIO,
        relativePath = relPath,
        fingerprint = fp,
      )
    _uiState.update { it.copy(activeInsertRequest = null) }
    insertAudioAssetAtAnchor(asset = asset, targetTrackId = targetTrackId, anchorMs = insertAnchor)
  }

  fun insertStudioSoundAtPlayhead(
    preset: StudioSoundPreset,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    val mediaDir = resolveMediaDirectory()
    val wavFile = StudioSoundGenerator.ensureSoundFile(preset = preset, mediaDir = mediaDir)
    val relPath = "${resolveMediaDirName()}/${wavFile.name}"
    val fingerprint =
      mediaProbe?.probeFile(wavFile, fallbackMimeType = "audio/wav")?.let { probed ->
        probed.copy(durationMs = maxOf(probed.durationMs, preset.durationMs))
      } ?: com.example.core.model.MediaFingerprint(
        byteSize = wavFile.length(),
        durationMs = preset.durationMs,
        partialSha256 = "studio_${preset.id}",
        mimeType = "audio/wav",
        hasAudio = true,
      )
    val assetId = "asset_${preset.id}"
    val existingAsset = _uiState.value.assets.find { it.assetId == assetId }
    val asset =
      existingAsset ?: MediaAsset(
        assetId = assetId,
        type = MediaType.AUDIO,
        displayName = preset.title,
        relativePath = relPath,
        fingerprint = fingerprint,
      )
    insertAudioAssetAtAnchor(asset = asset, targetTrackId = targetTrackId, anchorMs = insertAnchor)
  }

  private fun insertAudioAssetAtAnchor(
    asset: MediaAsset,
    targetTrackId: String?,
    anchorMs: Long,
  ) {
    val current = _uiState.value
    val durationMs = asset.fingerprint.durationMs.coerceAtLeast(500L)
    val (preparedTimeline, resolvedTrackId) =
      resolveTargetTrackAndState(
        initialState = current.timeline,
        preferredTrackId = targetTrackId,
        type = TrackType.AUDIO,
        isOverlay = false,
        startMs = anchorMs,
        durationMs = durationMs,
      )
    val clipId = "clip_aud_${UUID.randomUUID().toString().take(6)}"
    val newClip =
      TimelineClip(
        clipId = clipId,
        trackId = resolvedTrackId,
        kind = ClipKind.AUDIO,
        label = asset.displayName,
        timelineStartMs = anchorMs,
        timelineEndMs = anchorMs + durationMs,
        sourceInMs = 0L,
        sourceOutMs = durationMs,
        assetId = asset.assetId,
        audioConfig = AudioClipConfig(volume = 1.0f),
      )
    val inserted = engine.insertClip(preparedTimeline, newClip, ripple = false)
    if (inserted is TimelineOperationResult.Success) {
      pushHistory(current)
      val nextAssets = if (current.assets.any { it.assetId == asset.assetId }) current.assets else current.assets + asset
      _uiState.update {
        it.copy(
          assets = nextAssets,
          timeline = inserted.newState,
          selectedClipId = clipId,
          isImportingMedia = false,
          activeInsertRequest = null,
          activeSubPanel = EditorToolSubPanel.ADJUSTMENTS,
          activeInspectorTab = InspectorTab.AUDIO,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage = "Audio insertado en ${formatAnchorLabel(anchorMs)}",
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
      refreshCacheStorageStats()
    } else {
      _uiState.update { it.copy(isImportingMedia = false) }
    }
  }

  fun importOverlayImageAtPlayhead(
    uri: Uri,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
    opacity: Float = 1.0f,
    cropLeft: Float = 0.0f,
    cropTop: Float = 0.0f,
    cropRight: Float = 1.0f,
    cropBottom: Float = 1.0f,
  ) {
    val probe = mediaProbe ?: return
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    _uiState.update { it.copy(isImportingMedia = true, activeInsertRequest = null, lastStatusMessage = null) }
    viewModelScope.launch {
      probe.importIntoPortableProject(
        sourceUri = uri,
        projectRootDir = projectRootDir,
        mediaDirName = resolveMediaDirName(),
        preferredMediaType = MediaType.IMAGE,
      ).onSuccess { asset ->
        val durationMs = asset.fingerprint.durationMs.coerceAtLeast(MediaProbe.DEFAULT_IMAGE_DURATION_MS)
        insertOverlayClipInternal(
          asset = asset,
          label = asset.displayName,
          durationMs = durationMs,
          overlayKind = OverlayContentKind.IMAGE,
          stickerId = null,
          targetTrackId = targetTrackId,
          anchorMs = insertAnchor,
          opacity = opacity,
          cropLeft = cropLeft,
          cropTop = cropTop,
          cropRight = cropRight,
          cropBottom = cropBottom,
        )
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isImportingMedia = false,
            lastStatusMessage = err.message ?: "No se pudo importar la imagen seleccionada",
          )
        }
      }
    }
  }

  fun insertStickerOrLogoAtPlayhead(
    stickerId: String,
    title: String,
    isLogo: Boolean,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
    durationMs: Long = 3_500L,
    opacity: Float = 1.0f,
    cropLeft: Float = 0.0f,
    cropTop: Float = 0.0f,
    cropRight: Float = 1.0f,
    cropBottom: Float = 1.0f,
  ) {
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    insertOverlayClipInternal(
      asset = null,
      label = title,
      durationMs = durationMs.coerceAtLeast(500L),
      overlayKind = if (isLogo) OverlayContentKind.LOGO else OverlayContentKind.STICKER,
      stickerId = stickerId,
      targetTrackId = targetTrackId,
      anchorMs = insertAnchor,
      opacity = opacity,
      cropLeft = cropLeft,
      cropTop = cropTop,
      cropRight = cropRight,
      cropBottom = cropBottom,
    )
  }

  fun importPipVideoAtPlayhead(
    uri: Uri,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
    opacity: Float = 1.0f,
    cropLeft: Float = 0.0f,
    cropTop: Float = 0.0f,
    cropRight: Float = 1.0f,
    cropBottom: Float = 1.0f,
  ) {
    val probe = mediaProbe ?: return
    val insertAnchor = (anchorMs ?: _uiState.value.timeline.playheadMs).coerceAtLeast(0L)
    _uiState.update { it.copy(isImportingMedia = true, activeInsertRequest = null, lastStatusMessage = null) }
    viewModelScope.launch {
      probe.importIntoPortableProject(
        sourceUri = uri,
        projectRootDir = projectRootDir,
        mediaDirName = resolveMediaDirName(),
        preferredMediaType = MediaType.VIDEO,
      ).onSuccess { asset ->
        val durationMs = asset.fingerprint.durationMs.coerceAtLeast(1_500L)
        insertOverlayClipInternal(
          asset = asset,
          label = "PiP: ${asset.displayName}",
          durationMs = durationMs,
          overlayKind = OverlayContentKind.VIDEO_PIP,
          stickerId = null,
          targetTrackId = targetTrackId,
          anchorMs = insertAnchor,
          opacity = opacity,
          cropLeft = cropLeft,
          cropTop = cropTop,
          cropRight = cropRight,
          cropBottom = cropBottom,
        )
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isImportingMedia = false,
            lastStatusMessage = err.message ?: "No se pudo importar el video PiP",
          )
        }
      }
    }
  }

  fun insertAssetAsPipAtPlayhead(
    assetId: String,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
    opacity: Float = 1.0f,
    cropLeft: Float = 0.0f,
    cropTop: Float = 0.0f,
    cropRight: Float = 1.0f,
    cropBottom: Float = 1.0f,
  ) {
    val current = _uiState.value
    val asset = current.assets.find { it.assetId == assetId } ?: return
    val insertAnchor = (anchorMs ?: current.timeline.playheadMs).coerceAtLeast(0L)
    val durationMs = asset.fingerprint.durationMs.coerceAtLeast(1_500L)
    val kind = if (asset.type == MediaType.IMAGE) OverlayContentKind.IMAGE else OverlayContentKind.VIDEO_PIP
    insertOverlayClipInternal(
      asset = asset,
      label = if (kind == OverlayContentKind.IMAGE) asset.displayName else "PiP: ${asset.displayName}",
      durationMs = durationMs,
      overlayKind = kind,
      stickerId = null,
      targetTrackId = targetTrackId,
      anchorMs = insertAnchor,
      opacity = opacity,
      cropLeft = cropLeft,
      cropTop = cropTop,
      cropRight = cropRight,
      cropBottom = cropBottom,
    )
  }

  private fun insertOverlayClipInternal(
    asset: MediaAsset?,
    label: String,
    durationMs: Long,
    overlayKind: OverlayContentKind,
    stickerId: String?,
    targetTrackId: String?,
    anchorMs: Long,
    opacity: Float = 1.0f,
    cropLeft: Float = 0.0f,
    cropTop: Float = 0.0f,
    cropRight: Float = 1.0f,
    cropBottom: Float = 1.0f,
  ) {
    val current = _uiState.value
    val (preparedTimeline, resolvedTrackId) =
      resolveTargetTrackAndState(
        initialState = current.timeline,
        preferredTrackId = targetTrackId,
        type = TrackType.VIDEO,
        isOverlay = true,
        startMs = anchorMs,
        durationMs = durationMs,
      )
    val clipId = "clip_ovl_${UUID.randomUUID().toString().take(6)}"
    val initialScale =
      when (overlayKind) {
        OverlayContentKind.VIDEO_PIP -> 0.45f
        OverlayContentKind.IMAGE -> 0.55f
        OverlayContentKind.STICKER, OverlayContentKind.LOGO -> 0.50f
        OverlayContentKind.NONE -> 1.0f
      }
    val initialOffsetX = if (overlayKind == OverlayContentKind.VIDEO_PIP) 0.24f else 0.0f
    val initialOffsetY = if (overlayKind == OverlayContentKind.VIDEO_PIP) -0.20f else 0.0f
    val safeLeft = ColorGradeBounds.clampCropLeft(cropLeft)
    val safeTop = ColorGradeBounds.clampCropTop(cropTop)
    val safeRight = ColorGradeBounds.clampCropRight(cropRight, safeLeft)
    val safeBottom = ColorGradeBounds.clampCropBottom(cropBottom, safeTop)
    val newClip =
      TimelineClip(
        clipId = clipId,
        trackId = resolvedTrackId,
        kind = ClipKind.VIDEO,
        label = label,
        timelineStartMs = anchorMs,
        timelineEndMs = anchorMs + durationMs,
        sourceInMs = 0L,
        sourceOutMs = durationMs,
        assetId = asset?.assetId,
        videoConfig =
          VideoClipConfig(
            scale = initialScale,
            opacity = opacity.coerceIn(0.1f, 1.0f),
            offsetX = initialOffsetX,
            offsetY = initialOffsetY,
            overlayKind = overlayKind,
            stickerId = stickerId,
            cropLeft = safeLeft,
            cropTop = safeTop,
            cropRight = safeRight,
            cropBottom = safeBottom,
          ),
      )
    val inserted = engine.insertClip(preparedTimeline, newClip, ripple = false)
    if (inserted is TimelineOperationResult.Success) {
      pushHistory(current)
      val nextAssets =
        if (asset != null && current.assets.none { it.assetId == asset.assetId }) {
          current.assets + asset
        } else {
          current.assets
        }
      _uiState.update {
        it.copy(
          assets = nextAssets,
          timeline = inserted.newState,
          selectedClipId = clipId,
          isImportingMedia = false,
          activeInsertRequest = null,
          activeSubPanel = EditorToolSubPanel.ADJUSTMENTS,
          activeInspectorTab = InspectorTab.TRANSFORM,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage = "Superposición añadida en ${formatAnchorLabel(anchorMs)}",
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
      if (asset != null) refreshCacheStorageStats()
    } else {
      _uiState.update { it.copy(isImportingMedia = false, activeInsertRequest = null) }
    }
  }

  fun insertAdjustmentEffectAtPlayhead(
    label: String,
    config: AdjustmentClipConfig,
    durationMs: Long = 3_000L,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
  ) {
    val current = _uiState.value
    val insertAnchor = (anchorMs ?: current.timeline.playheadMs).coerceAtLeast(0L)
    val safeDuration = durationMs.coerceAtLeast(500L)
    val (preparedTimeline, resolvedTrackId) =
      resolveTargetTrackAndState(
        initialState = current.timeline,
        preferredTrackId = targetTrackId,
        type = TrackType.ADJUSTMENT,
        isOverlay = false,
        startMs = insertAnchor,
        durationMs = safeDuration,
      )
    val clipId = "clip_fx_${UUID.randomUUID().toString().take(6)}"
    val newClip =
      TimelineClip(
        clipId = clipId,
        trackId = resolvedTrackId,
        kind = ClipKind.ADJUSTMENT,
        label = label,
        timelineStartMs = insertAnchor,
        timelineEndMs = insertAnchor + safeDuration,
        adjustmentConfig = ColorGradeBounds.clampAdjustmentColor(config),
      )
    val inserted = engine.insertClip(preparedTimeline, newClip, ripple = false)
    if (inserted is TimelineOperationResult.Success) {
      pushHistory(current)
      _uiState.update {
        it.copy(
          timeline = inserted.newState,
          selectedClipId = clipId,
          activeInsertRequest = null,
          activeSubPanel = EditorToolSubPanel.ADJUSTMENTS,
          activeInspectorTab = InspectorTab.COLOR_LUT,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage = "Efecto '$label' insertado (${safeDuration / 1000.0}s)",
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    } else {
      _uiState.update { it.copy(activeInsertRequest = null) }
    }
  }

  fun insertTextSubtitleAtPlayhead(
    text: String,
    stylePreset: SubtitleStylePreset,
    targetTrackId: String? = _uiState.value.activeInsertRequest?.targetTrackId,
    anchorMs: Long? = _uiState.value.activeInsertRequest?.anchorMs,
    durationMs: Long = 3_000L,
  ) {
    val current = _uiState.value
    val insertAnchor = (anchorMs ?: current.timeline.playheadMs).coerceAtLeast(0L)
    val safeDuration = durationMs.coerceAtLeast(500L)
    val cleanText = text.trim().ifBlank { "Nuevo texto" }
    val (preparedTimeline, resolvedTrackId) =
      resolveTargetTrackAndState(
        initialState = current.timeline,
        preferredTrackId = targetTrackId,
        type = TrackType.SUBTITLE,
        isOverlay = false,
        startMs = insertAnchor,
        durationMs = safeDuration,
      )
    val clipId = "clip_sub_${UUID.randomUUID().toString().take(6)}"
    val newClip =
      TimelineClip(
        clipId = clipId,
        trackId = resolvedTrackId,
        kind = ClipKind.SUBTITLE,
        label = cleanText.take(24),
        timelineStartMs = insertAnchor,
        timelineEndMs = insertAnchor + safeDuration,
        subtitleConfig = stylePreset.toSubtitleConfig(cleanText),
      )
    val inserted = engine.insertClip(preparedTimeline, newClip, ripple = false)
    if (inserted is TimelineOperationResult.Success) {
      pushHistory(current)
      _uiState.update {
        it.copy(
          timeline = inserted.newState,
          selectedClipId = clipId,
          activeInsertRequest = null,
          activeSubPanel = EditorToolSubPanel.ADJUSTMENTS,
          activeInspectorTab = InspectorTab.SPEED_SUBTITLES,
          canUndo = historyManager.canUndo,
          canRedo = historyManager.canRedo,
          undoStepsCount = historyManager.undoCount,
          redoStepsCount = historyManager.redoCount,
          lastStatusMessage = "Texto añadido en ${formatAnchorLabel(insertAnchor)}",
        )
      }
      syncPreviewComposition()
      persistCurrentProjectAsync()
    } else {
      _uiState.update { it.copy(activeInsertRequest = null) }
    }
  }

  private fun formatAnchorLabel(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1_000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
  }

  private fun syncCurrentDocIntoStore() {
    val current = _uiState.value
    projectDoc =
      projectDoc.copy(
        name = current.projectName,
        updatedAtIso = Instant.now().toString(),
        timeline = current.timeline,
        assets = current.assets,
        snapshots = current.snapshots,
        sequences = current.sequences,
        activeSequenceId = current.activeSequenceId,
        appliedAiAdjustments = current.appliedAiAdjustments,
        selectedClipId = current.selectedClipId,
        selectedExportPresetName = current.selectedExportPreset.name,
        exportConfig = current.selectedExportPreset.config,
        savedLutPresets = current.savedLutPresets,
      )
    projectStore[projectDoc.projectId] = projectDoc
  }

  private fun persistCurrentProjectAsync() {
    syncCurrentDocIntoStore()
    val snapshot = projectDoc
    _uiState.update { it.copy(recentProjects = buildRecentSummaries(snapshot.projectId)) }
    val repo = projectRepository ?: return
    viewModelScope.launch {
      repo.saveProject(snapshot)
      refreshRecentProjectsFromDiskOrStore(snapshot.projectId)
      refreshCacheStorageStatsInternal()
    }
  }

  private suspend fun initializePersistentProjects() {
    val repo = projectRepository ?: return
    val savedDocs = repo.listRecentProjects()
    if (savedDocs.isEmpty()) {
      repo.saveProject(projectDoc)
      refreshRecentProjectsFromDiskOrStore(projectDoc.projectId)
    } else {
      savedDocs.forEach { doc -> projectStore[doc.projectId] = doc }
      applyLoadedProjectDocument(savedDocs.first())
    }
    refreshCacheStorageStatsInternal()
  }

  private fun applyLoadedProjectDocument(loaded: ProjectDocument) {
    projectStore[loaded.projectId] = loaded
    projectDoc = loaded
    BuiltInLutPresets.syncCustomPresets(loaded.savedLutPresets)
    historyManager.clear()
    val resolvedPreset =
      ExportPreset.entries.firstOrNull { it.name == loaded.selectedExportPresetName }
        ?: ExportPreset.HD_1080P_H264
    val resolvedClipId =
      loaded.selectedClipId?.takeIf { id -> loaded.timeline.clips.any { it.clipId == id } }
        ?: loaded.timeline.clips.firstOrNull()?.clipId
    _uiState.update {
      it.copy(
        projectName = loaded.name,
        recentProjects = buildRecentSummaries(loaded.projectId),
        timeline = loaded.timeline,
        assets = loaded.assets,
        selectedClipId = resolvedClipId,
        selectedExportPreset = resolvedPreset,
        appliedAiAdjustments = loaded.appliedAiAdjustments,
        editAnalysisReport = null,
        canUndo = false,
        canRedo = false,
        undoStepsCount = 0,
        redoStepsCount = 0,
        snapshots = loaded.snapshots,
        sequences = loaded.sequences,
        activeSequenceId = loaded.activeSequenceId,
        savedLutPresets = loaded.savedLutPresets,
        lastStatusMessage = null,
      )
    }
    syncPreviewComposition()
  }

  private suspend fun refreshRecentProjectsFromDiskOrStore(activeId: String) {
    val repo = projectRepository
    if (repo != null) {
      val onDisk = repo.listRecentProjects()
      onDisk.forEach { doc -> projectStore[doc.projectId] = doc }
    }
    _uiState.update { it.copy(recentProjects = buildRecentSummaries(activeId)) }
  }

  private fun buildRecentSummaries(activeId: String): List<RecentProjectSummary> =
    projectStore.values
      .sortedByDescending { it.updatedAtIso }
      .map { doc ->
        val isSavedFile = projectRepository?.projectFileFor(doc.projectId)?.exists() == true
        RecentProjectSummary(
          projectId = doc.projectId,
          name = doc.name,
          updatedAtIso = doc.updatedAtIso,
          clipCount = doc.timeline.clips.size,
          durationMs = doc.timeline.totalDurationMs,
          isActive = doc.projectId == activeId,
          aiAdjustmentCount = doc.appliedAiAdjustments.size,
          isPersistedJson = isSavedFile,
        )
      }

  fun refreshCacheStorageStats() {
    viewModelScope.launch {
      refreshCacheStorageStatsInternal()
    }
  }

  private suspend fun refreshCacheStorageStatsInternal() {
    val manager = tempCacheStorageManager ?: return
    val stats = manager.measureStorage()
    _uiState.update { it.copy(cacheStorageStats = stats) }
  }

  fun clearTemporaryCache() {
    val manager = tempCacheStorageManager ?: return
    val activeJobs =
      exportQueue.value.filter {
        it.status == ExportJobStatus.RUNNING || it.status == ExportJobStatus.QUEUED
      }
    val hasActiveExport = activeJobs.isNotEmpty()
    val activePaths = exportQueueManager?.activeExportFilePaths().orEmpty()
    val preserveDirs =
      if (hasActiveExport && activePaths.isEmpty()) {
        setOf(TempCacheStorageManager.DIR_EXPORT_STAGING)
      } else {
        emptySet()
      }
    _uiState.update { it.copy(isClearingCache = true) }
    viewModelScope.launch {
      val updatedStats =
        manager.clearTemporaryCachePreservingFiles(
          preserveDirectories = preserveDirs,
          preserveFilePaths = if (hasActiveExport) activePaths else emptySet(),
        )
      thumbnailCache?.clear()
      waveformCache?.clearMemoryCache()
      _uiState.update { state ->
        val cleanedAssets =
          state.assets.map { asset ->
            if (asset.proxyRelativePath != null) {
              val proxyExists = File(projectRootDir, asset.proxyRelativePath).exists()
              if (!proxyExists) asset.copy(proxyRelativePath = null) else asset
            } else {
              asset
            }
          }
        state.copy(
          assets = cleanedAssets,
          cacheStorageStats = updatedStats,
          isClearingCache = false,
          lastStatusMessage =
            if (hasActiveExport) {
              "Caché temporal limpiada (exportación activa y proyectos .json intactos)."
            } else {
              "Caché temporal limpiada. Tus proyectos .json permanecen intactos."
            },
        )
      }
      persistCurrentProjectAsync()
      syncPreviewComposition()
    }
  }

  fun generateProxiesForAllAssets() {
    val manager = proxyManager ?: return
    val assetsSnapshot = _uiState.value.assets
    if (assetsSnapshot.isEmpty()) {
      _uiState.update {
        it.copy(lastStatusMessage = "Importa un video real primero para generar su proxy")
      }
      return
    }
    val targetHeight = _uiState.value.renderSettings.proxyTargetHeight

    viewModelScope.launch {
      var updatedAssets = assetsSnapshot
      for (asset in assetsSnapshot) {
        manager.generateProxyForAsset(
          asset = asset,
          projectRootDir = projectRootDir,
          targetHeight = targetHeight,
        ).collect { (batchState, updatedAsset) ->
          _uiState.update { it.copy(proxyProgress = batchState) }
          if (updatedAsset != null) {
            updatedAssets =
              updatedAssets.map { existing ->
                if (existing.assetId == updatedAsset.assetId) updatedAsset else existing
              }
          }
        }
      }
      _uiState.update { it.copy(assets = updatedAssets, proxyProgress = null) }
      persistCurrentProjectAsync()
      refreshCacheStorageStatsInternal()
      syncPreviewComposition()
    }
  }

  fun selectExportPreset(preset: ExportPreset) {
    _uiState.update { it.copy(selectedExportPreset = preset) }
    persistCurrentProjectAsync()
  }

  fun enqueueExportRange(range: ExportRange) {
    val manager = exportQueueManager ?: return
    val current = _uiState.value
    manager.enqueue(
      title = "FrameLine_${range.label}",
      range = range,
      exportConfig = current.selectedExportPreset.config,
      timelineSnapshot = current.timeline,
      assetsSnapshot = current.assets,
      projectRootDir = projectRootDir,
    )
    refreshCacheStorageStats()
  }

  fun enqueueAllMarkerChapters() {
    val manager = exportQueueManager ?: return
    val current = _uiState.value
    manager.enqueueMarkerChapters(
      projectName = "FrameLine",
      exportConfig = current.selectedExportPreset.config,
      timelineSnapshot = current.timeline,
      assetsSnapshot = current.assets,
      projectRootDir = projectRootDir,
    )
    refreshCacheStorageStats()
  }

  fun cancelExportJob(jobId: String) {
    exportQueueManager?.cancelJob(jobId)
  }

  fun retryExportJob(jobId: String) {
    exportQueueManager?.retryJob(jobId)
  }

  fun clearFinishedExports() {
    exportQueueManager?.clearFinished()
    refreshCacheStorageStats()
  }

  private fun syncPreviewComposition() {
    val current = _uiState.value
    if (current.assets.isNotEmpty()) {
      previewController?.loadTimelinePreview(
        timeline = current.timeline,
        assets = current.assets,
        projectRootDir = projectRootDir,
        renderSettings = current.renderSettings,
        deviceProfile = current.deviceProfile,
        customLutPresets = current.savedLutPresets,
      )
    }
  }

  // --- Paso 5: Módulo opcional y desactivable de IA Local ---

  fun setLocalAiEnabled(enabled: Boolean) {
    val nextSettings = _uiState.value.localAiSettings.copy(enabled = enabled)
    if (enabled) {
      resourceGuard?.startMonitoring(nextSettings)
    } else {
      dictationJob?.cancel()
      dictationJob = null
      resourceGuard?.stopMonitoring()
    }
    val snap = resourceGuard?.refreshNow(nextSettings) ?: _uiState.value.resourceSnapshot
    val profile = resourceGuard?.resolveDeviceProfile(nextSettings) ?: _uiState.value.deviceProfile
    _uiState.update {
      it.copy(
        localAiSettings = nextSettings,
        resourceSnapshot = snap,
        deviceProfile = profile,
        editAnalysisReport = if (enabled) it.editAnalysisReport else null,
        beatCandidates = if (enabled) it.beatCandidates else emptyList(),
        isDictatingSubtitle = if (enabled) it.isDictatingSubtitle else false,
        partialTranscript = if (enabled) it.partialTranscript else null,
        lastStatusMessage = null,
      )
    }
  }

  fun selectLocalModelProfile(profile: LocalModelProfile) {
    val nextSettings = _uiState.value.localAiSettings.copy(profile = profile)
    val snap = resourceGuard?.refreshNow(nextSettings) ?: _uiState.value.resourceSnapshot
    val deviceProf = resourceGuard?.resolveDeviceProfile(nextSettings) ?: _uiState.value.deviceProfile
    _uiState.update {
      it.copy(localAiSettings = nextSettings, resourceSnapshot = snap, deviceProfile = deviceProf)
    }
  }

  fun setAllowOnBatterySaver(allow: Boolean) {
    val nextSettings = _uiState.value.localAiSettings.copy(allowOnBatterySaver = allow)
    val snap = resourceGuard?.refreshNow(nextSettings) ?: _uiState.value.resourceSnapshot
    val deviceProf = resourceGuard?.resolveDeviceProfile(nextSettings) ?: _uiState.value.deviceProfile
    _uiState.update {
      it.copy(localAiSettings = nextSettings, resourceSnapshot = snap, deviceProfile = deviceProf)
    }
  }

  fun analyzeCurrentClipWithAssistant() {
    val current = _uiState.value
    if (!current.localAiSettings.enabled) {
      _uiState.update { it.copy(lastStatusMessage = "Activa el módulo de IA local primero") }
      return
    }
    val targetClip =
      current.selectedClip
        ?: current.timeline.clips.firstOrNull { it.kind == ClipKind.VIDEO && it.isEnabled }
        ?: current.timeline.clips.firstOrNull()
    if (targetClip == null) {
      _uiState.update { it.copy(lastStatusMessage = "No hay clips en la línea de tiempo para analizar") }
      return
    }
    val asset = targetClip.assetId?.let { id -> current.assets.find { it.assetId == id } }
    val assistant = localEditAssistant ?: return

    _uiState.update { it.copy(isAnalyzingEdits = true, lastStatusMessage = null) }
    viewModelScope.launch {
      assistant
        .analyzeForEdits(
          timeline = current.timeline,
          selectedClip = targetClip,
          asset = asset,
          projectRootDir = projectRootDir,
          settings = current.localAiSettings,
        )
        .onSuccess { report ->
          _uiState.update {
            it.copy(
              deviceProfile = report.deviceProfile,
              editAnalysisReport = report,
              isAnalyzingEdits = false,
              lastStatusMessage = null,
            )
          }
        }
        .onFailure { error ->
          _uiState.update {
            it.copy(
              isAnalyzingEdits = false,
              lastStatusMessage = error.localizedMessage ?: "No se pudo analizar el clip",
            )
          }
        }
    }
  }

  fun applyEditSuggestion(suggestionId: String) {
    val current = _uiState.value
    val report = current.editAnalysisReport ?: return
    val suggestion = report.suggestions.find { it.suggestionId == suggestionId } ?: return
    if (suggestion.status != EditFixStatus.PENDING) return

    var updatedTimeline = current.timeline
    var nextSelectedId = current.selectedClipId
    var applied = false
    var rollbackRecord: AiSuggestionRollback? = null

    when (val payload = suggestion.payload) {
      is EditFixPayload.ColorLightFix -> {
        val overlappingAdj =
          updatedTimeline.clips.firstOrNull {
            it.kind == ClipKind.ADJUSTMENT &&
              it.timelineStartMs < payload.spanEndMs &&
              it.timelineEndMs > payload.spanStartMs
          }
        if (overlappingAdj != null) {
          val previousCfg = overlappingAdj.adjustmentConfig
          val res =
            engine.updateAdjustmentConfig(updatedTimeline, overlappingAdj.clipId) { cfg ->
              cfg.copy(
                brightness = payload.brightness,
                contrast = payload.contrast,
                saturation = payload.saturation,
              )
            }
          if (res is TimelineOperationResult.Success) {
            updatedTimeline = res.newState
            rollbackRecord = AiSuggestionRollback.ModifiedAdjustment(overlappingAdj.clipId, previousCfg)
            applied = true
          }
        } else {
          val adjTrackId =
            updatedTimeline.tracks.firstOrNull { it.type == TrackType.ADJUSTMENT }?.trackId
          if (adjTrackId != null) {
            val adjClip =
              TimelineClip(
                clipId = "clip_ai_lut_${UUID.randomUUID().toString().take(5)}",
                trackId = adjTrackId,
                kind = ClipKind.ADJUSTMENT,
                label = "IA Luz/Color",
                timelineStartMs = payload.spanStartMs,
                timelineEndMs = payload.spanEndMs,
                adjustmentConfig =
                  AdjustmentClipConfig(
                    brightness = payload.brightness,
                    contrast = payload.contrast,
                    saturation = payload.saturation,
                  ),
              )
            val res = engine.insertClip(updatedTimeline, adjClip, ripple = false)
            if (res is TimelineOperationResult.Success) {
              updatedTimeline = res.newState
              rollbackRecord = AiSuggestionRollback.CreatedClip(adjClip.clipId)
              applied = true
            }
          }
        }
      }

      is EditFixPayload.CutMarkersFix -> {
        if (payload.positionsMs.isNotEmpty()) {
          val beforeMarkerIds = updatedTimeline.markers.map { it.markerId }.toSet()
          payload.positionsMs.forEachIndexed { idx, posMs ->
            val label = payload.labels.getOrElse(idx) { "Corte IA ${idx + 1}" }
            updatedTimeline =
              engine.addMarker(
                state = updatedTimeline,
                positionMs = posMs,
                label = label,
                color = MarkerColor.CYAN,
                notes = "Sugerido por LocalEditAssistant",
              )
          }
          val addedIds = updatedTimeline.markers.map { it.markerId }.filterNot { it in beforeMarkerIds }
          rollbackRecord = AiSuggestionRollback.AddedMarkers(addedIds)
          applied = true
        }
      }

      is EditFixPayload.InitialKeyframesFix -> {
        val prevKeyframes =
          updatedTimeline.clips.find { it.clipId == payload.targetClipId }?.videoConfig?.keyframes.orEmpty()
        val res =
          engine.updateVideoTransform(updatedTimeline, payload.targetClipId) { cfg ->
            val merged =
              (cfg.keyframes + payload.keyframes)
                .distinctBy { it.offsetMs }
                .sortedBy { it.offsetMs }
            cfg.copy(keyframes = merged)
          }
        if (res is TimelineOperationResult.Success) {
          updatedTimeline = res.newState
          nextSelectedId = payload.targetClipId
          rollbackRecord = AiSuggestionRollback.AddedKeyframes(payload.targetClipId, prevKeyframes)
          applied = true
        }
      }

      is EditFixPayload.AudioFadeFix -> {
        val targetClip = updatedTimeline.clips.find { it.clipId == payload.targetClipId }
        val res =
          engine.updateClipAudioMix(
            state = updatedTimeline,
            clipId = payload.targetClipId,
            volume = payload.volume,
            fadeInMs = payload.fadeInMs,
            fadeOutMs = payload.fadeOutMs,
          )
        if (res is TimelineOperationResult.Success) {
          updatedTimeline = res.newState
          nextSelectedId = payload.targetClipId
          rollbackRecord =
            AiSuggestionRollback.ModifiedAudioMix(
              targetClipId = payload.targetClipId,
              previousVideoConfig = targetClip?.videoConfig,
              previousAudioConfig = targetClip?.audioConfig,
            )
          applied = true
        }
      }
    }

    if (!applied) return

    if (rollbackRecord != null) {
      aiSuggestionRollbackMap[suggestionId] = rollbackRecord
    }
    pushHistory(current)
    val nextSuggestions =
      report.suggestions.map { item ->
        if (item.suggestionId == suggestionId) item.copy(status = EditFixStatus.APPLIED) else item
      }
    val appliedRecord =
      AppliedAiAdjustment(
        adjustmentId = "ai_adj_${UUID.randomUUID().toString().take(6)}",
        suggestionId = suggestion.suggestionId,
        category = suggestion.category.name,
        title = suggestion.title,
        rationale = suggestion.rationale,
        targetClipId = suggestion.targetClipId,
        parametersSummary = summarizeAiPayload(suggestion.payload),
        appliedAtIso = Instant.now().toString(),
      )
    _uiState.update {
      it.copy(
        timeline = updatedTimeline,
        selectedClipId = nextSelectedId,
        appliedAiAdjustments = it.appliedAiAdjustments + appliedRecord,
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        editAnalysisReport = report.copy(suggestions = nextSuggestions),
        lastStatusMessage = "Arreglo aplicado al Editor: ${suggestion.title}",
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  fun revertEditSuggestion(suggestionId: String) {
    val current = _uiState.value
    val report = current.editAnalysisReport ?: return
    val suggestion = report.suggestions.find { it.suggestionId == suggestionId } ?: return
    if (suggestion.status != EditFixStatus.APPLIED) return

    val rollback = aiSuggestionRollbackMap.remove(suggestionId)
    val revertedTimeline =
      when (rollback) {
        is AiSuggestionRollback.CreatedClip ->
          current.timeline.copy(
            clips = current.timeline.clips.filterNot { it.clipId == rollback.clipId },
          )
        is AiSuggestionRollback.ModifiedAdjustment ->
          current.timeline.copy(
            clips =
              current.timeline.clips.map { clip ->
                if (clip.clipId == rollback.clipId) {
                  clip.copy(adjustmentConfig = rollback.previousConfig)
                } else {
                  clip
                }
              },
          )
        is AiSuggestionRollback.AddedMarkers -> {
          val removeSet = rollback.markerIds.toSet()
          current.timeline.copy(
            markers = current.timeline.markers.filterNot { it.markerId in removeSet },
          )
        }
        is AiSuggestionRollback.AddedKeyframes ->
          current.timeline.copy(
            clips =
              current.timeline.clips.map { clip ->
                if (clip.clipId == rollback.targetClipId && clip.videoConfig != null) {
                  clip.copy(videoConfig = clip.videoConfig.copy(keyframes = rollback.previousKeyframes))
                } else {
                  clip
                }
              },
          )
        is AiSuggestionRollback.ModifiedAudioMix ->
          current.timeline.copy(
            clips =
              current.timeline.clips.map { clip ->
                if (clip.clipId == rollback.targetClipId) {
                  clip.copy(
                    videoConfig = rollback.previousVideoConfig ?: clip.videoConfig,
                    audioConfig = rollback.previousAudioConfig ?: clip.audioConfig,
                  )
                } else {
                  clip
                }
              },
          )
        null -> revertSuggestionFallback(current.timeline, suggestion.payload)
      }

    pushHistory(current)
    val nextSuggestions =
      report.suggestions.map { item ->
        if (item.suggestionId == suggestionId) item.copy(status = EditFixStatus.PENDING) else item
      }
    _uiState.update { state ->
      val validSelected =
        state.selectedClipId?.takeIf { id -> revertedTimeline.clips.any { it.clipId == id } }
          ?: revertedTimeline.clips.firstOrNull()?.clipId
      state.copy(
        timeline = revertedTimeline,
        selectedClipId = validSelected,
        appliedAiAdjustments = state.appliedAiAdjustments.filterNot { it.suggestionId == suggestionId },
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        editAnalysisReport = report.copy(suggestions = nextSuggestions),
        lastStatusMessage = "Sugerencia revertida en el Editor: ${suggestion.title}",
      )
    }
    syncPreviewComposition()
    persistCurrentProjectAsync()
  }

  private fun revertSuggestionFallback(
    timeline: TimelineState,
    payload: EditFixPayload,
  ): TimelineState =
    when (payload) {
      is EditFixPayload.ColorLightFix -> {
        val aiClip =
          timeline.clips.firstOrNull {
            it.kind == ClipKind.ADJUSTMENT &&
              it.clipId.startsWith("clip_ai_lut_") &&
              it.timelineStartMs == payload.spanStartMs &&
              it.timelineEndMs == payload.spanEndMs
          }
        if (aiClip != null) {
          timeline.copy(clips = timeline.clips.filterNot { it.clipId == aiClip.clipId })
        } else {
          timeline.copy(
            clips =
              timeline.clips.map { clip ->
                if (clip.kind == ClipKind.ADJUSTMENT &&
                  clip.timelineStartMs < payload.spanEndMs &&
                  clip.timelineEndMs > payload.spanStartMs
                ) {
                  clip.copy(adjustmentConfig = AdjustmentClipConfig())
                } else {
                  clip
                }
              },
          )
        }
      }
      is EditFixPayload.CutMarkersFix -> {
        val posSet = payload.positionsMs.toSet()
        timeline.copy(
          markers =
            timeline.markers.filterNot {
              it.positionMs in posSet && it.notes == "Sugerido por LocalEditAssistant"
            },
        )
      }
      is EditFixPayload.InitialKeyframesFix -> {
        val kfIds = payload.keyframes.map { it.keyframeId }.toSet()
        timeline.copy(
          clips =
            timeline.clips.map { clip ->
              if (clip.clipId == payload.targetClipId && clip.videoConfig != null) {
                clip.copy(
                  videoConfig =
                    clip.videoConfig.copy(
                      keyframes = clip.videoConfig.keyframes.filterNot { it.keyframeId in kfIds },
                    ),
                )
              } else {
                clip
              }
            },
        )
      }
      is EditFixPayload.AudioFadeFix ->
        timeline.copy(
          clips =
            timeline.clips.map { clip ->
              if (clip.clipId == payload.targetClipId) {
                clip.copy(
                  videoConfig = clip.videoConfig?.copy(fadeInMs = 0L, fadeOutMs = 0L),
                  audioConfig = clip.audioConfig?.copy(fadeInMs = 0L, fadeOutMs = 0L),
                )
              } else {
                clip
              }
            },
        )
    }

  private fun summarizeAiPayload(payload: EditFixPayload): String =
    when (payload) {
      is EditFixPayload.ColorLightFix ->
        "Brillo ${payload.brightness}, Contraste ${payload.contrast}, Sat ${payload.saturation}"
      is EditFixPayload.CutMarkersFix ->
        "${payload.positionsMs.size} marcas (${payload.labels.joinToString()})"
      is EditFixPayload.InitialKeyframesFix ->
        "${payload.keyframes.size} keyframes iniciales"
      is EditFixPayload.AudioFadeFix ->
        "FadeIn ${payload.fadeInMs}ms / FadeOut ${payload.fadeOutMs}ms"
    }

  fun dismissEditSuggestion(suggestionId: String) {
    val current = _uiState.value
    val report = current.editAnalysisReport ?: return
    val nextSuggestions =
      report.suggestions.map { item ->
        if (item.suggestionId == suggestionId && item.status == EditFixStatus.PENDING) {
          item.copy(status = EditFixStatus.DISMISSED)
        } else {
          item
        }
      }
    _uiState.update {
      it.copy(editAnalysisReport = report.copy(suggestions = nextSuggestions))
    }
  }

  fun analyzeRhythmAndSilences(clipId: String) {
    val current = _uiState.value
    if (!current.localAiSettings.enabled) {
      _uiState.update { it.copy(lastStatusMessage = "Activa el módulo de IA local primero") }
      return
    }
    val clip = current.timeline.clips.find { it.clipId == clipId } ?: return
    val asset = clip.assetId?.let { id -> current.assets.find { it.assetId == id } }
    if (asset == null) {
      _uiState.update {
        it.copy(
          lastStatusMessage = "Importa un clip multimedia local para analizar ritmo y silencios",
        )
      }
      return
    }
    val engineAi = localAudioAssistEngine ?: return
    val mediaFile = File(projectRootDir, asset.relativePath)
    _uiState.update { it.copy(isAnalyzingRhythm = true, lastStatusMessage = null) }

    viewModelScope.launch {
      engineAi
        .analyzeMediaFile(mediaFile, current.localAiSettings)
        .onSuccess { result ->
          _uiState.update {
            it.copy(
              detectedSilences = result.silences,
              beatCandidates = result.beats,
              isAnalyzingRhythm = false,
              lastStatusMessage =
                if (result.beats.isEmpty() && result.silences.isEmpty()) {
                  "Sin pausas largas ni picos de ritmo destacados"
                } else {
                  null
                },
            )
          }
        }
        .onFailure { error ->
          _uiState.update {
            it.copy(
              isAnalyzingRhythm = false,
              lastStatusMessage = error.localizedMessage ?: "Error en análisis local",
            )
          }
        }
    }
  }

  fun createMarkersFromBeats() {
    val current = _uiState.value
    val candidates = current.beatCandidates
    if (candidates.isEmpty()) return
    val clipOffset = current.selectedClip?.timelineStartMs ?: 0L

    pushHistory(current)
    var updatedTimeline = current.timeline
    candidates.forEachIndexed { idx, beat ->
      updatedTimeline =
        engine.addMarker(
          state = updatedTimeline,
          positionMs = (clipOffset + beat.positionMs).coerceAtLeast(0L),
          label = "Beat ${idx + 1}",
          color = MarkerColor.AMBER,
        )
    }

    _uiState.update {
      it.copy(
        timeline = updatedTimeline,
        beatCandidates = emptyList(),
        canUndo = historyManager.canUndo,
        canRedo = historyManager.canRedo,
        undoStepsCount = historyManager.undoCount,
        redoStepsCount = historyManager.redoCount,
        lastStatusMessage = null,
      )
    }
    persistCurrentProjectAsync()
  }

  fun isOfflineSpeechSupported(): Boolean =
    localTranscriptionBackend?.isOfflineRecognitionSupported() == true

  fun startOfflineSubtitleDictation() {
    val current = _uiState.value
    if (!current.localAiSettings.enabled) {
      _uiState.update { it.copy(lastStatusMessage = "Activa el módulo de IA local primero") }
      return
    }
    val snap = resourceGuard?.refreshNow(current.localAiSettings) ?: current.resourceSnapshot
    if (!snap.canRunInference) {
      _uiState.update { it.copy(resourceSnapshot = snap, lastStatusMessage = snap.blockReason) }
      return
    }
    val backend = localTranscriptionBackend ?: return
    dictationJob?.cancel()
    dictationJob =
      viewModelScope.launch {
        backend.startOfflineDictation().collect { event ->
          when (event) {
            LocalTranscriptionEvent.Listening ->
              _uiState.update {
                it.copy(isDictatingSubtitle = true, partialTranscript = null, lastStatusMessage = null)
              }
            is LocalTranscriptionEvent.Partial ->
              _uiState.update { it.copy(partialTranscript = event.text) }
            is LocalTranscriptionEvent.Completed -> {
              val state = _uiState.value
              val startMs = state.timeline.playheadMs
              val durationMs = (event.segment.endMs - event.segment.startMs).coerceAtLeast(1_800L)
              val subClip =
                TimelineClip(
                  clipId = "clip_sub_${UUID.randomUUID().toString().take(6)}",
                  trackId = "t_sub",
                  kind = ClipKind.SUBTITLE,
                  label = event.segment.text,
                  timelineStartMs = startMs,
                  timelineEndMs = startMs + durationMs,
                  subtitleConfig = SubtitleClipConfig(text = event.segment.text),
                )
              val inserted = engine.insertClip(state.timeline, subClip, ripple = false)
              if (inserted is TimelineOperationResult.Success) {
                pushHistory(state)
                _uiState.update {
                  it.copy(
                    timeline = inserted.newState,
                    selectedClipId = subClip.clipId,
                    canUndo = historyManager.canUndo,
                    canRedo = historyManager.canRedo,
                    undoStepsCount = historyManager.undoCount,
                    redoStepsCount = historyManager.redoCount,
                    isDictatingSubtitle = false,
                    partialTranscript = null,
                    lastStatusMessage = null,
                  )
                }
                persistCurrentProjectAsync()
              } else {
                _uiState.update { it.copy(isDictatingSubtitle = false, partialTranscript = null) }
              }
            }
            is LocalTranscriptionEvent.UnavailableOrError ->
              _uiState.update {
                it.copy(
                  isDictatingSubtitle = false,
                  partialTranscript = null,
                  lastStatusMessage = event.reason,
                )
              }
          }
        }
      }
  }

  // --- Paso 5: Capa de Ergonomía Híbrida (Táctil / Stylus / Teclado) ---

  fun updateInputMode(mode: InputDeviceMode) {
    if (_uiState.value.ergonomics.inputMode == mode) return
    _uiState.update { it.copy(ergonomics = it.ergonomics.copy(inputMode = mode)) }
  }

  fun zoomTimelineBy(factor: Float) {
    _uiState.update { state ->
      val nextScale = TimelineErgonomicsHelper.clampZoom(state.ergonomics.zoomScale, factor)
      state.copy(ergonomics = state.ergonomics.copy(zoomScale = nextScale))
    }
  }

  fun setTimelineZoom(scale: Float) {
    val clamped =
      scale.coerceIn(
        TimelineViewportErgonomics.MIN_ZOOM,
        TimelineViewportErgonomics.MAX_ZOOM,
      )
    _uiState.update { it.copy(ergonomics = it.ergonomics.copy(zoomScale = clamped)) }
  }

  fun fitTimelineZoom(viewportWidthDp: Float) {
    val durationMs = _uiState.value.timeline.totalDurationMs
    val fitScale =
      TimelineErgonomicsHelper.calculateFitZoomScale(
        viewportWidthDp = viewportWidthDp,
        totalDurationMs = durationMs,
      )
    setTimelineZoom(fitScale)
  }

  fun toggleSnapEnabled() {
    _uiState.update {
      it.copy(ergonomics = it.ergonomics.copy(snapEnabled = !it.ergonomics.snapEnabled))
    }
  }

  fun updateHoverTimelineMs(ms: Long?) {
    if (_uiState.value.ergonomics.hoverTimelineMs == ms) return
    _uiState.update { it.copy(ergonomics = it.ergonomics.copy(hoverTimelineMs = ms)) }
  }

  fun scrubPlayheadWithSnap(rawPositionMs: Long) {
    val current = _uiState.value
    val snapped =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = rawPositionMs,
        state = current.timeline,
        thresholdMs = current.ergonomics.inputMode.snapThresholdMs,
        snapEnabled = current.ergonomics.snapEnabled,
      )
    _uiState.update { state ->
      state.copy(
        timeline = engine.seekPlayhead(state.timeline, snapped),
        lastStatusMessage = null,
      )
    }
    previewController?.scrubTo(snapped)
  }

  fun setShortcutsDialogOpen(open: Boolean) {
    _uiState.update { it.copy(showShortcutsDialog = open) }
  }

  fun toggleViewerZoomPanMode() {
    _uiState.update { state ->
      val nextEnabled = !state.viewerViewport.isZoomPanEnabled
      state.copy(
        viewerViewport =
          state.viewerViewport.copy(
            isZoomPanEnabled = nextEnabled,
            isSnappedToCenterX = false,
            isSnappedToCenterY = false,
          ),
      )
    }
  }

  fun toggleViewerRuleOfThirds() {
    _uiState.update { state ->
      state.copy(
        viewerViewport =
          state.viewerViewport.copy(showRuleOfThirds = !state.viewerViewport.showRuleOfThirds),
      )
    }
  }

  fun toggleViewerSafeMargins() {
    _uiState.update { state ->
      state.copy(
        viewerViewport =
          state.viewerViewport.copy(showSafeMargins = !state.viewerViewport.showSafeMargins),
      )
    }
  }

  fun toggleViewerMagneticCenterSnap() {
    _uiState.update { state ->
      val nextSnap = !state.viewerViewport.magneticCenterSnap
      val snapped =
        TimelineErgonomicsHelper.applyViewerCenterSnap(
          rawPanNormalizedX = state.viewerViewport.panNormalizedX,
          rawPanNormalizedY = state.viewerViewport.panNormalizedY,
          zoomScale = state.viewerViewport.zoomScale,
          magneticCenterSnap = nextSnap,
        )
      state.copy(
        viewerViewport =
          state.viewerViewport.copy(
            magneticCenterSnap = nextSnap,
            panNormalizedX = snapped.panNormalizedX,
            panNormalizedY = snapped.panNormalizedY,
            isSnappedToCenterX = snapped.snappedToCenterX,
            isSnappedToCenterY = snapped.snappedToCenterY,
          ),
      )
    }
  }

  fun setViewerTransform(
    zoomScale: Float,
    panNormalizedX: Float,
    panNormalizedY: Float,
  ) {
    _uiState.update { state ->
      val current = state.viewerViewport
      val clampedZoom =
        TimelineErgonomicsHelper.clampViewerZoom(
          currentScale = zoomScale,
          factor = 1.0f,
        )
      val snapped =
        TimelineErgonomicsHelper.applyViewerCenterSnap(
          rawPanNormalizedX = panNormalizedX,
          rawPanNormalizedY = panNormalizedY,
          zoomScale = clampedZoom,
          magneticCenterSnap = current.magneticCenterSnap,
        )
      state.copy(
        viewerViewport =
          current.copy(
            zoomScale = clampedZoom,
            panNormalizedX = snapped.panNormalizedX,
            panNormalizedY = snapped.panNormalizedY,
            isSnappedToCenterX = snapped.snappedToCenterX,
            isSnappedToCenterY = snapped.snappedToCenterY,
          ),
      )
    }
  }

  fun resetViewerViewport() {
    _uiState.update { state ->
      state.copy(
        viewerViewport =
          state.viewerViewport.copy(
            zoomScale = ViewerViewportState.MIN_ZOOM,
            panNormalizedX = 0f,
            panNormalizedY = 0f,
            isSnappedToCenterX = false,
            isSnappedToCenterY = false,
          ),
      )
    }
  }

  fun setViewerAbBypassHeld(held: Boolean) {
    if (_uiState.value.viewerAbBypassHeld == held) return
    _uiState.update { it.copy(viewerAbBypassHeld = held) }
    previewController?.setAbBypassEnabled(held)
  }

  fun toggleViewerAbBypass() {
    setViewerAbBypassHeld(!_uiState.value.viewerAbBypassHeld)
  }

  fun toggleViewerAbSplit() {
    _uiState.update { it.copy(viewerAbSplitEnabled = !it.viewerAbSplitEnabled) }
  }

  fun setViewerAbSplitFraction(fraction: Float) {
    val clamped = fraction.coerceIn(0.05f, 0.95f)
    _uiState.update { it.copy(viewerAbSplitFraction = clamped) }
  }

  fun toggleViewerHistogram() {
    _uiState.update { it.copy(viewerHistogramEnabled = !it.viewerHistogramEnabled) }
  }

  fun eraserTapClip(clipId: String) {
    applyOperation { current -> engine.toggleClipEnabled(current.timeline, clipId) }
  }

  fun handleShortcutAction(action: ShortcutAction) {
    updateInputMode(InputDeviceMode.KEYBOARD_MOUSE)
    val playheadMs = _uiState.value.timeline.playheadMs
    when (action) {
      ShortcutAction.PLAY_PAUSE -> previewController?.togglePlayPause()
      ShortcutAction.SPLIT_AT_PLAYHEAD -> splitAtPlayhead()
      ShortcutAction.TOGGLE_RIPPLE -> toggleRippleEnabled()
      ShortcutAction.ADD_MARKER -> addMarkerAtPlayhead("Marcador")
      ShortcutAction.TOGGLE_CLIP_ENABLED -> toggleSelectedClipEnabled()
      ShortcutAction.DELETE_CLIP -> deleteSelectedClip()
      ShortcutAction.UNDO -> undo()
      ShortcutAction.REDO -> redo()
      ShortcutAction.STEP_BACK_FRAME ->
        seekPlayhead((playheadMs - TimelineViewportErgonomics.FRAME_STEP_30FPS_MS).coerceAtLeast(0L))
      ShortcutAction.STEP_FORWARD_FRAME ->
        seekPlayhead(playheadMs + TimelineViewportErgonomics.FRAME_STEP_30FPS_MS)
      ShortcutAction.STEP_BACK_COARSE ->
        seekPlayhead((playheadMs - TimelineViewportErgonomics.COARSE_STEP_MS).coerceAtLeast(0L))
      ShortcutAction.STEP_FORWARD_COARSE ->
        seekPlayhead(playheadMs + TimelineViewportErgonomics.COARSE_STEP_MS)
      ShortcutAction.ZOOM_IN -> zoomTimelineBy(1.2f)
      ShortcutAction.ZOOM_OUT -> zoomTimelineBy(0.83f)
      ShortcutAction.PREV_MARKER -> jumpToPreviousMarker()
      ShortcutAction.NEXT_MARKER -> jumpToNextMarker()
    }
  }

  private fun applyOperation(block: (TimelineSkeletonUiState) -> TimelineOperationResult) {
    val current = _uiState.value
    when (val result = block(current)) {
      is TimelineOperationResult.Success -> {
        pushHistory(current)
        val nextSelected =
          current.selectedClipId?.takeIf { id ->
            result.newState.clips.any { it.clipId == id }
          } ?: result.affectedClipIds.firstOrNull { id ->
            result.newState.clips.any { it.clipId == id }
          } ?: result.newState.clips.firstOrNull()?.clipId

        _uiState.value =
          current.copy(
            timeline = result.newState,
            selectedClipId = nextSelected,
            canUndo = historyManager.canUndo,
            canRedo = historyManager.canRedo,
            undoStepsCount = historyManager.undoCount,
            redoStepsCount = historyManager.redoCount,
            lastStatusMessage = null,
          )
        syncPreviewComposition()
        persistCurrentProjectAsync()
      }
      is TimelineOperationResult.Rejected -> {
        _uiState.update {
          it.copy(lastStatusMessage = mapRejectionReason(result.reason))
        }
      }
    }
  }

  private fun captureHistorySnapshot(state: TimelineSkeletonUiState): EditorHistorySnapshot =
    EditorHistorySnapshot(
      timeline = state.timeline,
      selectedClipId = state.selectedClipId,
      appliedAiAdjustments = state.appliedAiAdjustments,
      editAnalysisReport = state.editAnalysisReport,
      snapshots = state.snapshots,
      sequences = state.sequences,
      activeSequenceId = state.activeSequenceId,
    )

  private fun pushHistory(beforeState: TimelineSkeletonUiState) {
    historyManager.record(captureHistorySnapshot(beforeState))
  }

  private fun mapRejectionReason(reason: RejectionReason): String =
    when (reason) {
      RejectionReason.CLIP_NOT_FOUND -> "Clip no encontrado"
      RejectionReason.TRACK_LOCKED -> "La pista está bloqueada"
      RejectionReason.CLIP_LOCKED -> "El clip está bloqueado"
      RejectionReason.NO_CLIP_UNDER_PLAYHEAD -> "Coloca el cabezal sobre el clip para cortar"
      RejectionReason.SEGMENT_TOO_SHORT -> "Segmento demasiado corto (< 100 ms)"
      RejectionReason.INVALID_BOUNDS -> "Límites de origen inválidos"
      RejectionReason.MARKER_NOT_FOUND -> "Marcador no encontrado"
    }

  override fun onCleared() {
    super.onCleared()
    playbackTickerJob?.cancel()
    dictationJob?.cancel()
    resourceGuard?.stopMonitoring()
    previewController?.disposePermanently()
    thumbnailCache?.clear()
    waveformCache?.clearMemoryCache()
  }

  companion object {
    private const val MAX_UNDO_HISTORY = EditorHistoryManager.DEFAULT_MAX_STEPS

    fun factory(
      engine: TimelineEngine,
      attributeTransferEngine: AttributeTransferEngine,
      snapshotManager: TimelineSnapshotManager,
      silenceDetector: SilenceDetectorEngine,
      subtitleStyleRepository: SubtitleStyleRepository,
      mediaProbe: MediaProbe,
      proxyManager: ProxyManager,
      exportQueueManager: ExportQueueManager,
      previewController: PreviewPlayerController,
      thumbnailCache: VideoThumbnailCache,
      resourceGuard: ResourceGuard,
      localAudioAssistEngine: LocalAudioAssistEngine,
      localEditAssistant: LocalEditAssistEngine,
      localTranscriptionBackend: LocalTranscriptionBackend,
      projectRepository: ProjectRepository,
      tempCacheStorageManager: TempCacheStorageManager,
      projectRootDir: File,
      waveformCache: AudioWaveformCache? = null,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return TimelineSkeletonViewModel(
            engine = engine,
            attributeEngine = attributeTransferEngine,
            snapshotManager = snapshotManager,
            silenceDetector = silenceDetector,
            subtitleStyleRepo = subtitleStyleRepository,
            mediaProbe = mediaProbe,
            proxyManager = proxyManager,
            exportQueueManager = exportQueueManager,
            previewController = previewController,
            thumbnailCache = thumbnailCache,
            waveformCache = waveformCache,
            resourceGuard = resourceGuard,
            localAudioAssistEngine = localAudioAssistEngine,
            localEditAssistant = localEditAssistant,
            localTranscriptionBackend = localTranscriptionBackend,
            projectRepository = projectRepository,
            tempCacheStorageManager = tempCacheStorageManager,
            projectRootDir = projectRootDir,
          ) as T
        }
      }

    fun createInitialProjectDocument(): ProjectDocument {
      val now = Instant.now().toString()
      val initialTimeline = createInitialSampleTimeline()
      return ProjectDocument(
        projectId = "proj_demo",
        name = "FrameLine Demo",
        createdAtIso = now,
        updatedAtIso = now,
        timeline = initialTimeline,
        activeSequenceId = "seq_main",
        sequences =
          listOf(
            TimelineSequence(
              sequenceId = "seq_main",
              name = "Principal",
              createdAtIso = now,
              timeline = initialTimeline,
            ),
          ),
      )
    }

    fun createInitialSampleTimeline(): TimelineState {
      val tracks =
        listOf(
          Track(trackId = "t_adj", type = TrackType.ADJUSTMENT, order = 0, name = "Ajuste LUT"),
          Track(trackId = "t_sub", type = TrackType.SUBTITLE, order = 1, name = "Subtítulos"),
          Track(trackId = "t_vid", type = TrackType.VIDEO, order = 2, name = "Video V1"),
          Track(trackId = "t_aud", type = TrackType.AUDIO, order = 3, name = "Audio A1"),
        )

      val clips =
        listOf(
          TimelineClip(
            clipId = "clip_adj1",
            trackId = "t_adj",
            kind = ClipKind.ADJUSTMENT,
            label = "Cinematic Contrast",
            timelineStartMs = 1_000L,
            timelineEndMs = 8_000L,
            adjustmentConfig = AdjustmentClipConfig(contrast = 1.12f, saturation = 1.08f),
          ),
          TimelineClip(
            clipId = "clip_sub1",
            trackId = "t_sub",
            kind = ClipKind.SUBTITLE,
            label = "Hola FrameLine",
            timelineStartMs = 500L,
            timelineEndMs = 4_200L,
            subtitleConfig = SubtitleClipConfig(text = "Hola FrameLine"),
          ),
          TimelineClip(
            clipId = "clip_v1",
            trackId = "t_vid",
            kind = ClipKind.VIDEO,
            label = "Intro_01.mp4",
            timelineStartMs = 0L,
            timelineEndMs = 5_000L,
            sourceInMs = 0L,
            sourceOutMs = 5_000L,
            videoConfig = VideoClipConfig(),
          ),
          TimelineClip(
            clipId = "clip_v2",
            trackId = "t_vid",
            kind = ClipKind.VIDEO,
            label = "B_Roll_02.mp4",
            timelineStartMs = 5_000L,
            timelineEndMs = 9_500L,
            sourceInMs = 1_200L,
            sourceOutMs = 5_700L,
            videoConfig = VideoClipConfig(),
          ),
          TimelineClip(
            clipId = "clip_a1",
            trackId = "t_aud",
            kind = ClipKind.AUDIO,
            label = "Beat_Loop.wav",
            timelineStartMs = 0L,
            timelineEndMs = 9_500L,
            sourceInMs = 0L,
            sourceOutMs = 9_500L,
            audioConfig = AudioClipConfig(volume = 0.8f),
          ),
        )

      val markers =
        listOf(
          TimelineMarker(
            markerId = "m_intro",
            positionMs = 2_500L,
            label = "Golpe de ritmo",
            color = MarkerColor.AMBER,
          ),
          TimelineMarker(
            markerId = "m_cut",
            positionMs = 5_000L,
            label = "Cambio de escena",
            color = MarkerColor.CYAN,
          ),
        )

      return TimelineState(
        playheadMs = 2_500L,
        tracks = tracks,
        clips = clips,
        markers = markers,
        editOptions = EditModeOptions(rippleEnabled = true, rippleScope = RippleScope.SAME_TRACK),
      )
    }
  }
}
