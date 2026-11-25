package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.core.ai.api.DeviceProfile
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.AudioWaveformCache
import com.example.core.media.playback.ThumbnailLoadResult
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.MarkerColor
import com.example.core.model.OverlayContentKind
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.ui.theme.LocalTrackColors
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val BASE_PX_PER_SECOND_DP = 56.dp
private val DEFAULT_TRACK_ROW_HEIGHT = 64.dp

@Composable
fun MultitrackTimelineDeck(
  uiState: TimelineSkeletonUiState,
  onSelectClip: (String) -> Unit,
  onSelectMarker: (Long) -> Unit,
  onBeginScrub: () -> Unit,
  onScrubPlayhead: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onToggleTrackMute: (String) -> Unit,
  onToggleTrackSolo: (String) -> Unit,
  onToggleTrackLocked: (String) -> Unit,
  onToggleTrackVisible: (String) -> Unit,
  onBeginClipTrim: () -> Unit,
  onTrimClipEdge: (clipId: String, isStartEdge: Boolean, targetMs: Long) -> Unit,
  onEndClipTrim: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
  onHoverTimelineMsChanged: (Long?) -> Unit,
  onZoomBy: (Float) -> Unit,
  onSetZoom: ((Float) -> Unit)? = null,
  onFitZoom: (Float) -> Unit = {},
  onEraserTapClip: (String) -> Unit,
  loadClipThumbnail: suspend (String?, Long) -> ThumbnailLoadResult?,
  loadClipWaveform: suspend (String?, Long, Long, Int) -> FloatArray? = { _, _, _, _ -> null },
  onSelectTransitionCut: (leftClipId: String, rightClipId: String) -> Unit = { _, _ -> },
  onAddToTrack: (track: Track, useTrackEnd: Boolean) -> Unit = { _, _ -> },
  onAddNewTrack: (type: TrackType, isOverlay: Boolean) -> Unit = { _, _ -> },
  ergonomics: TimelineViewportErgonomics = uiState.ergonomics,
  outerHorizontalPadding: Dp = 12.dp,
  modifier: Modifier = Modifier,
) {
  val trackColors = LocalTrackColors.current
  val density = LocalDensity.current
  val coroutineScope = rememberCoroutineScope()
  val timeline = uiState.timeline
  val audibilityMap = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
  val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)
  val horizontalScroll = rememberScrollState()
  val verticalScroll = rememberScrollState()
  val pxPerSecondDp = BASE_PX_PER_SECOND_DP * ergonomics.zoomScale
  val pxPerSecond = with(density) { pxPerSecondDp.toPx() }
  val totalTimelineMs = (timeline.totalDurationMs + 3_000L).coerceAtLeast(10_000L)
  val playheadOffsetDp = msToDp(timeline.playheadMs, pxPerSecondDp)
  val latestPlayheadMs by rememberUpdatedState(timeline.playheadMs)
  val latestInputMode by rememberUpdatedState(ergonomics.inputMode)
  val latestZoomScale by rememberUpdatedState(ergonomics.zoomScale)
  val latestOnZoomBy by rememberUpdatedState(onZoomBy)
  val latestOnSetZoom by rememberUpdatedState(onSetZoom)
  val latestOnPointerInputDetected by rememberUpdatedState(onPointerInputDetected)
  val hoverColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
  var isPinchGestureActive by remember { mutableStateOf(false) }
  var lastPinchFocalViewportX by remember { mutableStateOf<Float?>(null) }
  var lastAnchoredZoomScale by remember { mutableStateOf(ergonomics.zoomScale) }
  var fitScrollResetPending by remember { mutableStateOf(false) }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = outerHorizontalPadding)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        .testTag("multitrack_timeline_deck"),
  ) {
    val headerWidth =
      when {
        maxWidth >= 680.dp -> 136.dp
        maxWidth >= 420.dp -> 124.dp
        else -> 110.dp
      }
    val headerWidthPx = with(density) { headerWidth.toPx() }
    val trackViewportWidthDp = (maxWidth - headerWidth).coerceAtLeast(160.dp)
    val rulerHeight = if (maxHeight < 210.dp) 34.dp else 42.dp
    val footerHeight = 40.dp
    val trackCount = timeline.tracks.size.coerceAtLeast(1)
    val trackRowHeight =
      ((maxHeight - rulerHeight - footerHeight) / trackCount.toFloat()).coerceAtLeast(52.dp)
    val canvasWidthDp = msToDp(totalTimelineMs, pxPerSecondDp).coerceAtLeast(trackViewportWidthDp)
    val rulerTotalMs =
      ((canvasWidthDp.value / pxPerSecondDp.value) * 1000f)
        .roundToLong()
        .coerceAtLeast(totalTimelineMs)
    val latestHeaderWidthPx by rememberUpdatedState(headerWidthPx)
    val trackViewportWidthPx = with(density) { trackViewportWidthDp.toPx() }

    LaunchedEffect(ergonomics.zoomScale) {
      val prevZoom = lastAnchoredZoomScale
      val newZoom = ergonomics.zoomScale
      lastAnchoredZoomScale = newZoom
      if (fitScrollResetPending) {
        fitScrollResetPending = false
        lastPinchFocalViewportX = null
        horizontalScroll.scrollTo(0)
      } else if (abs(newZoom - prevZoom) > 0.0005f) {
        val anchorX = lastPinchFocalViewportX ?: (trackViewportWidthPx * 0.35f)
        val targetScrollPx =
          TimelineErgonomicsHelper.calculateFocalAnchoredScrollPx(
            currentScrollPx = horizontalScroll.value.toFloat(),
            focalViewportX = anchorX,
            oldZoom = prevZoom,
            newZoom = newZoom,
          )
        horizontalScroll.scrollTo(targetScrollPx)
        if (horizontalScroll.value != targetScrollPx) {
          withFrameNanos { }
          horizontalScroll.scrollTo(targetScrollPx)
        }
      }
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            awaitEachGesture {
              val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
              latestOnPointerInputDetected(InputDeviceMode.fromPointerType(firstDown.type))
              var virtualZoom = latestZoomScale
              try {
                do {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  event.changes.firstOrNull()?.type?.let { type ->
                    latestOnPointerInputDetected(InputDeviceMode.fromPointerType(type))
                  }
                  val pressedChanges = event.changes.filter { it.pressed }
                  if (pressedChanges.size >= 2) {
                    if (!isPinchGestureActive) {
                      isPinchGestureActive = true
                      virtualZoom = latestZoomScale
                    }
                    val rawZoomChange = event.calculateZoom()
                    if (rawZoomChange.isFinite() && rawZoomChange > 0f && abs(rawZoomChange - 1f) > 0.002f) {
                      val stepFactor = rawZoomChange.coerceIn(0.75f, 1.35f)
                      val prevZoom = virtualZoom
                      val nextZoom = TimelineErgonomicsHelper.clampZoom(prevZoom, stepFactor)
                      if (abs(nextZoom - prevZoom) > 0.0005f) {
                        virtualZoom = nextZoom
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val focalViewportX = (centroid.x - latestHeaderWidthPx).coerceAtLeast(0f)
                        lastPinchFocalViewportX = focalViewportX
                        val setter = latestOnSetZoom
                        if (setter != null) {
                          setter(nextZoom)
                        } else {
                          latestOnZoomBy(stepFactor)
                        }
                      }
                    }
                    // Consume all pointer changes during multi-touch pinch so trim/scrub/scroll never fire.
                    event.changes.forEach { it.consume() }
                  } else if (isPinchGestureActive) {
                    // Keep remaining finger lifts suppressed until all touches are released.
                    event.changes.forEach { it.consume() }
                  }
                } while (event.changes.any { it.pressed })
              } finally {
                isPinchGestureActive = false
              }
            }
          },
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(verticalScroll, enabled = !isPinchGestureActive),
      ) {
        Row(modifier = Modifier.fillMaxWidth().height(rulerHeight)) {
          Box(
            modifier =
              Modifier
                .width(headerWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.CenterStart,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text =
                  if (timeline.markers.isNotEmpty()) {
                    "${stringResource(R.string.label_markers)} (${timeline.markers.size})"
                  } else {
                    stringResource(R.string.label_markers)
                  },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Box(
              modifier =
                Modifier
                  .align(Alignment.CenterEnd)
                  .width(1.dp)
                  .fillMaxHeight()
                  .background(MaterialTheme.colorScheme.outlineVariant),
            )
          }

          val latestPinchActive by rememberUpdatedState(isPinchGestureActive)
          Box(
            modifier =
              Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(horizontalScroll, enabled = !isPinchGestureActive)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .pointerInput(pxPerSecond) {
                  awaitPointerEventScope {
                    while (true) {
                      val event = awaitPointerEvent()
                      val first = event.changes.firstOrNull()
                      if (first != null && event.changes.count { it.pressed } <= 1 && !latestPinchActive) {
                        onPointerInputDetected(InputDeviceMode.fromPointerType(first.type))
                        when (event.type) {
                          PointerEventType.Move -> {
                            val hoverMs =
                              ((first.position.x / pxPerSecond) * 1000f)
                                .roundToLong()
                                .coerceAtLeast(0L)
                            onHoverTimelineMsChanged(hoverMs)
                          }
                          PointerEventType.Exit -> onHoverTimelineMsChanged(null)
                          else -> Unit
                        }
                      }
                    }
                  }
                }
                .pointerInput(pxPerSecond) {
                  var accumMs = 0f
                  detectHorizontalDragGestures(
                    onDragStart = { offset ->
                      if (latestPinchActive) return@detectHorizontalDragGestures
                      onBeginScrub()
                      accumMs = ((offset.x / pxPerSecond) * 1000f).coerceAtLeast(0f)
                      onScrubPlayhead(accumMs.roundToLong())
                    },
                    onDragEnd = {
                      accumMs = latestPlayheadMs.toFloat()
                      onEndScrub()
                    },
                    onDragCancel = {
                      onEndScrub()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                      if (latestPinchActive || change.isConsumed) return@detectHorizontalDragGestures
                      onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                      change.consume()
                      val deltaMs =
                        TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                          deltaPx = dragAmount,
                          pxPerSecond = pxPerSecond,
                          inputMode = latestInputMode,
                        )
                      accumMs = (accumMs + deltaMs).coerceAtLeast(0f)
                      onScrubPlayhead(accumMs.roundToLong())
                    },
                  )
                }
                .pointerInput(pxPerSecond) {
                  detectTapGestures { offset ->
                    if (latestPinchActive) return@detectTapGestures
                    onBeginScrub()
                    val tapMs = ((offset.x / pxPerSecond) * 1000f).roundToLong().coerceAtLeast(0L)
                    onScrubPlayhead(tapMs)
                    onEndScrub()
                  }
                },
          ) {
            Box(modifier = Modifier.width(canvasWidthDp).fillMaxHeight()) {
              TimeRulerCanvas(
                totalMs = rulerTotalMs,
                playheadMs = timeline.playheadMs,
                hoverMs = ergonomics.hoverTimelineMs,
                playheadColor = trackColors.playhead,
                hoverColor = hoverColor,
                majorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                minorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(),
              )

              timeline.markers.forEach { marker ->
                MarkerPin(
                  marker = marker,
                  color = markerColorFor(marker.color),
                  offsetX = msToDp(marker.positionMs, pxPerSecondDp),
                  rulerHeight = rulerHeight,
                  onClick = { onSelectMarker(marker.positionMs) },
                )
              }

              PlayheadRulerHandle(
                playheadOffsetDp = playheadOffsetDp,
                playheadMs = timeline.playheadMs,
                pxPerSecond = pxPerSecond,
                inputMode = latestInputMode,
                isPinchActive = isPinchGestureActive,
                playheadColor = trackColors.playhead,
                outlineColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                onBeginScrub = onBeginScrub,
                onScrubPlayhead = onScrubPlayhead,
                onEndScrub = onEndScrub,
                onPointerInputDetected = onPointerInputDetected,
              )
            }
          }
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

        timeline.tracksSorted().forEach { track ->
          val trackClips = timeline.clipsForTrack(track.trackId)
          val isAudioBearing = track.type == TrackType.VIDEO || track.type == TrackType.AUDIO
          val isTrackAudible = !isAudioBearing || audibilityMap[track.trackId] == true
          val laneAlpha =
            when {
              !track.isVisible -> 0.36f
              !isTrackAudible -> 0.48f
              else -> 1f
            }

          Row(
            modifier = Modifier.fillMaxWidth().height(trackRowHeight),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            TrackHeaderCell(
              track = track,
              headerWidth = headerWidth,
              onToggleMute = { onToggleTrackMute(track.trackId) },
              onToggleSolo = { onToggleTrackSolo(track.trackId) },
              onToggleLocked = { onToggleTrackLocked(track.trackId) },
              onToggleVisible = { onToggleTrackVisible(track.trackId) },
              onAddAtPlayhead = { onAddToTrack(track, false) },
            )

            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .horizontalScroll(horizontalScroll, enabled = !isPinchGestureActive)
                  .background(
                    if (track.isLocked) {
                      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                    } else {
                      MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f)
                    },
                  )
                  .alpha(laneAlpha),
            ) {
              Box(modifier = Modifier.width(canvasWidthDp).fillMaxHeight()) {
                trackClips.forEach { clip ->
                  val isSelected = clip.clipId == uiState.selectedClipId
                  val startDp = msToDp(clip.timelineStartMs, pxPerSecondDp)
                  val widthDp = msToDp(clip.durationMs, pxPerSecondDp).coerceAtLeast(30.dp)
                  val clipDuckingWindows =
                    if (timeline.duckingConfig.targetTrackId == track.trackId) {
                      AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
                    } else {
                      emptyList()
                    }

                  ClipBlock(
                    clip = clip,
                    isSelected = isSelected,
                    isTrackLocked = track.isLocked,
                    isTrackAudible = isTrackAudible,
                    trackVolume = track.volume,
                    duckingWindows = clipDuckingWindows,
                    startDp = startDp,
                    widthDp = widthDp,
                    trackRowHeight = trackRowHeight,
                    pxPerSecondDp = pxPerSecondDp,
                    accentColor = clipAccentColor(clip.kind),
                    deviceProfile = uiState.deviceProfile,
                    interactionEnabled = !isPinchGestureActive,
                    loadClipThumbnail = loadClipThumbnail,
                    loadClipWaveform = loadClipWaveform,
                    onClick = { onSelectClip(clip.clipId) },
                    onEraserTap = { onEraserTapClip(clip.clipId) },
                  )

                  if (isSelected && !track.isLocked && !clip.isLocked) {
                    ClipTrimHandle(
                      clipId = clip.clipId,
                      isStartEdge = true,
                      edgeAnchorMs = clip.timelineStartMs,
                      edgeOffsetDp = startDp,
                      trackRowHeight = trackRowHeight,
                      pxPerSecond = pxPerSecond,
                      inputMode = latestInputMode,
                      isPinchActive = isPinchGestureActive,
                      onBeginTrim = onBeginClipTrim,
                      onTrimEdge = { targetMs -> onTrimClipEdge(clip.clipId, true, targetMs) },
                      onEndTrim = onEndClipTrim,
                      onPointerInputDetected = onPointerInputDetected,
                    )
                    ClipTrimHandle(
                      clipId = clip.clipId,
                      isStartEdge = false,
                      edgeAnchorMs = clip.timelineEndMs,
                      edgeOffsetDp = startDp + widthDp,
                      trackRowHeight = trackRowHeight,
                      pxPerSecond = pxPerSecond,
                      inputMode = latestInputMode,
                      isPinchActive = isPinchGestureActive,
                      onBeginTrim = onBeginClipTrim,
                      onTrimEdge = { targetMs -> onTrimClipEdge(clip.clipId, false, targetMs) },
                      onEndTrim = onEndClipTrim,
                      onPointerInputDetected = onPointerInputDetected,
                    )
                  }
                }

                if (track.type == TrackType.VIDEO && !track.isLocked) {
                  val enabledVideoClips =
                    trackClips.filter { it.kind == ClipKind.VIDEO && it.isEnabled }.sortedBy { it.timelineStartMs }
                  for (i in 0 until enabledVideoClips.lastIndex) {
                    val left = enabledVideoClips[i]
                    val right = enabledVideoClips[i + 1]
                    if (abs(right.timelineStartMs - left.timelineEndMs) <= 80L) {
                      val activeTransition = left.videoConfig?.transitionOut ?: right.videoConfig?.transitionIn
                      val cutOffsetDp = msToDp(left.timelineEndMs, pxPerSecondDp)
                      val badgeCd = stringResource(R.string.cd_cut_transition_badge)
                      Box(
                        modifier =
                          Modifier
                            .offset(x = (cutOffsetDp - 24.dp).coerceAtLeast(0.dp), y = 0.dp)
                            .width(48.dp)
                            .fillMaxHeight()
                            .zIndex(2.5f)
                            .semantics { contentDescription = badgeCd }
                            .clickable(enabled = !isPinchGestureActive) {
                              onSelectTransitionCut(left.clipId, right.clipId)
                            }
                            .testTag("cut_transition_badge_${left.clipId}_${right.clipId}"),
                        contentAlignment = Alignment.Center,
                      ) {
                        Surface(
                          color =
                            if (activeTransition != null) {
                              MaterialTheme.colorScheme.secondary
                            } else {
                              MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f)
                            },
                          shape = RoundedCornerShape(6.dp),
                          border =
                            BorderStroke(
                              width = 1.dp,
                              color =
                                if (activeTransition != null) {
                                  MaterialTheme.colorScheme.onSecondary
                                } else {
                                  MaterialTheme.colorScheme.outline
                                },
                            ),
                          shadowElevation = 2.dp,
                        ) {
                          if (activeTransition != null) {
                            Text(
                              text = "${activeTransition.durationMs}ms",
                              style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.onSecondary,
                              modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                          } else {
                            Icon(
                              imageVector = Icons.Default.CompareArrows,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.padding(3.dp).size(12.dp),
                            )
                          }
                        }
                      }
                    }
                  }
                }

                if (!track.isLocked && track.type != TrackType.SUBTITLE) {
                  val trackMaxEndMs = trackClips.maxOfOrNull { it.timelineEndMs } ?: 0L
                  val endAddOffsetDp = msToDp(trackMaxEndMs, pxPerSecondDp) + 8.dp
                  val trackAddCd = stringResource(R.string.cd_track_add_at_end)
                  val endAccent = trackAccentColor(track)
                  Box(
                    modifier =
                      Modifier
                        .offset(x = endAddOffsetDp)
                        .align(Alignment.CenterStart)
                        .minimumInteractiveComponentSize()
                        .zIndex(1.8f)
                        .semantics { contentDescription = trackAddCd }
                        .clickable(enabled = !isPinchGestureActive) {
                          onAddToTrack(track, true)
                        }
                        .testTag("track_end_add_button_${track.trackId}"),
                    contentAlignment = Alignment.Center,
                  ) {
                    Surface(
                      color = endAccent.copy(alpha = 0.16f),
                      shape = RoundedCornerShape(8.dp),
                      border = BorderStroke(1.dp, endAccent.copy(alpha = 0.65f)),
                      modifier = Modifier.size(30.dp),
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(
                          imageVector = Icons.Default.Add,
                          contentDescription = null,
                          tint = endAccent,
                          modifier = Modifier.size(16.dp),
                        )
                      }
                    }
                  }
                }

                Box(
                  modifier =
                    Modifier
                      .offset(x = (playheadOffsetDp - 2.dp).coerceAtLeast(0.dp))
                      .width(6.dp)
                      .fillMaxHeight()
                      .zIndex(0.9f)
                      .background(trackColors.playhead.copy(alpha = 0.20f)),
                )
                Box(
                  modifier =
                    Modifier
                      .offset(x = playheadOffsetDp)
                      .width(2.dp)
                      .fillMaxHeight()
                      .zIndex(1f)
                      .background(trackColors.playhead),
                )
              }
            }
          }
          HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Dynamic track creator bar (`+ Pista`) anchored below tracks inside vertical scroll
        var showAddTrackMenu by remember { mutableStateOf(false) }
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(footerHeight)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
              .padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Start,
        ) {
          val addTrackCd = stringResource(R.string.cd_add_track)
          Box {
            Surface(
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
              modifier =
                Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .semantics { contentDescription = addTrackCd }
                  .clickable { showAddTrackMenu = true }
                  .testTag("add_track_button"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Add,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = stringResource(R.string.action_add_track),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }

            DropdownMenu(
              expanded = showAddTrackMenu,
              onDismissRequest = { showAddTrackMenu = false },
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.track_add_audio_option)) },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                onClick = {
                  showAddTrackMenu = false
                  onAddNewTrack(TrackType.AUDIO, false)
                },
                modifier = Modifier.testTag("add_track_option_AUDIO"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.track_add_overlay_option)) },
                leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                onClick = {
                  showAddTrackMenu = false
                  onAddNewTrack(TrackType.VIDEO, true)
                },
                modifier = Modifier.testTag("add_track_option_OVERLAY"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.track_add_adjustment_option)) },
                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                onClick = {
                  showAddTrackMenu = false
                  onAddNewTrack(TrackType.ADJUSTMENT, false)
                },
                modifier = Modifier.testTag("add_track_option_ADJUSTMENT"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.track_add_subtitle_option)) },
                leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                onClick = {
                  showAddTrackMenu = false
                  onAddNewTrack(TrackType.SUBTITLE, false)
                },
                modifier = Modifier.testTag("add_track_option_SUBTITLE"),
              )
            }
          }
        }
      }

      val isZoomModified = abs(ergonomics.zoomScale - 1.0f) > 0.02f || isPinchGestureActive
      val zoomPct = (ergonomics.zoomScale * 100f).roundToInt()
      val resetCd = stringResource(R.string.cd_reset_zoom)
      val fitCd = stringResource(R.string.cd_fit_zoom)

      Row(
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 8.dp, bottom = 6.dp)
            .zIndex(4f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (isZoomModified) {
          Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
            shadowElevation = 2.dp,
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(999.dp))
                .semantics { contentDescription = resetCd }
                .clickable {
                  onSetZoom?.invoke(1.0f) ?: onZoomBy(1.0f / ergonomics.zoomScale.coerceAtLeast(0.1f))
                }
                .testTag("timeline_zoom_reset_pill"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = stringResource(R.string.label_timeline_zoom_badge, zoomPct),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }

        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
          shape = RoundedCornerShape(999.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          shadowElevation = 2.dp,
          modifier =
            Modifier
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(999.dp))
              .semantics { contentDescription = fitCd }
              .clickable {
                fitScrollResetPending = true
                coroutineScope.launch { horizontalScroll.scrollTo(0) }
                onFitZoom(trackViewportWidthDp.value)
              }
              .testTag("timeline_zoom_fit_pill"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Default.FitScreen,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = stringResource(R.string.action_fit_zoom),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TimeRulerCanvas(
  totalMs: Long,
  playheadMs: Long,
  hoverMs: Long?,
  playheadColor: Color,
  hoverColor: Color,
  majorTickColor: Color,
  minorTickColor: Color,
  labelColor: Color,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = labelColor)

  Canvas(modifier = modifier) {
    val totalSeconds = (totalMs / 1_000L).toInt()
    val pxPerSecond = size.width / (totalMs / 1000f)

    val majorIntervalSec =
      when {
        pxPerSecond >= 95f -> 1
        pxPerSecond >= 45f -> 2
        else -> 5
      }
    val subDivisionsPerSecond =
      when {
        pxPerSecond >= 160f -> 10
        pxPerSecond >= 75f -> 5
        else -> 2
      }

    for (sec in 0..totalSeconds) {
      val secX = sec * pxPerSecond
      val isMajor = sec % majorIntervalSec == 0
      val tickTopY = if (isMajor) size.height * 0.44f else size.height * 0.62f

      drawLine(
        color = if (isMajor) majorTickColor else minorTickColor,
        start = Offset(secX, tickTopY),
        end = Offset(secX, size.height),
        strokeWidth = if (isMajor) 2f else 1.2f,
      )

      if (isMajor) {
        val labelText = formatRulerSecondsLabel(sec)
        val measured = textMeasurer.measure(labelText, style = labelStyle)
        val drawX = (secX + 4f).coerceAtMost((size.width - measured.size.width).coerceAtLeast(0f))
        drawText(
          textLayoutResult = measured,
          topLeft = Offset(drawX, 3f),
        )
      }

      if (sec < totalSeconds) {
        for (sub in 1 until subDivisionsPerSecond) {
          val subX = secX + (pxPerSecond * sub / subDivisionsPerSecond.toFloat())
          drawLine(
            color = minorTickColor,
            start = Offset(subX, size.height * 0.78f),
            end = Offset(subX, size.height),
            strokeWidth = 1f,
          )
        }
      }
    }

    if (hoverMs != null) {
      val hoverX = (hoverMs / 1000f) * pxPerSecond
      drawLine(
        color = hoverColor,
        start = Offset(hoverX, 0f),
        end = Offset(hoverX, size.height),
        strokeWidth = 2f,
      )
    }

    val playheadX = (playheadMs / 1000f) * pxPerSecond
    drawLine(
      color = playheadColor.copy(alpha = 0.26f),
      start = Offset(playheadX, 0f),
      end = Offset(playheadX, size.height),
      strokeWidth = 7f,
    )
    drawLine(
      color = playheadColor,
      start = Offset(playheadX, 0f),
      end = Offset(playheadX, size.height),
      strokeWidth = 2.5f,
    )
  }
}

@Composable
private fun PlayheadRulerHandle(
  playheadOffsetDp: Dp,
  playheadMs: Long,
  pxPerSecond: Float,
  inputMode: InputDeviceMode,
  isPinchActive: Boolean,
  playheadColor: Color,
  outlineColor: Color,
  onBeginScrub: () -> Unit,
  onScrubPlayhead: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
) {
  val latestPlayheadMs by rememberUpdatedState(playheadMs)
  val latestInputMode by rememberUpdatedState(inputMode)
  val latestPinchActive by rememberUpdatedState(isPinchActive)
  var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var isDragging by remember { mutableStateOf(false) }
  val handleDesc = stringResource(R.string.cd_playhead_handle)

  Box(
    modifier =
      Modifier
        .offset(x = (playheadOffsetDp - 24.dp).coerceAtLeast(0.dp))
        .width(48.dp)
        .fillMaxHeight()
        .zIndex(3f)
        .onGloballyPositioned { handleCoords = it }
        .semantics { contentDescription = handleDesc }
        .pointerInput(pxPerSecond) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (latestPinchActive) return@awaitEachGesture
            onPointerInputDetected(InputDeviceMode.fromPointerType(down.type))
            val coordsAtStart = handleCoords
            val startRootX =
              if (coordsAtStart != null && coordsAtStart.isAttached) {
                coordsAtStart.positionInRoot().x + down.position.x
              } else {
                down.position.x
              }
            val startMs = latestPlayheadMs
            var didActivateDrag = false

            val afterSlop =
              awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                if (!latestPinchActive && !change.isConsumed && currentEvent.changes.count { it.pressed } <= 1) {
                  didActivateDrag = true
                  isDragging = true
                  onBeginScrub()
                  change.consume()
                }
              }

            if (afterSlop != null && didActivateDrag && !latestPinchActive) {
              val dragCompleted =
                horizontalDrag(afterSlop.id) { change ->
                  if (latestPinchActive || change.isConsumed || currentEvent.changes.count { it.pressed } > 1) {
                    return@horizontalDrag
                  }
                  onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                  change.consume()
                  val currentCoords = handleCoords
                  val currentRootX =
                    if (currentCoords != null && currentCoords.isAttached) {
                      currentCoords.positionInRoot().x + change.position.x
                    } else {
                      change.position.x
                    }
                  val deltaPx = currentRootX - startRootX
                  val deltaMs =
                    TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                      deltaPx = deltaPx,
                      pxPerSecond = pxPerSecond,
                      inputMode = latestInputMode,
                    )
                  onScrubPlayhead((startMs + deltaMs).coerceAtLeast(0L))
                }
              if (dragCompleted || !dragCompleted) {
                isDragging = false
                onEndScrub()
              }
            } else {
              isDragging = false
            }
          }
        }
        .testTag("timeline_playhead_handle"),
    contentAlignment = Alignment.TopCenter,
  ) {
    Canvas(modifier = Modifier.padding(top = 2.dp).size(width = 24.dp, height = 22.dp)) {
      val w = size.width
      val h = size.height
      val capBottom = h * 0.65f
      val shieldPath =
        Path().apply {
          moveTo(2f, 0f)
          lineTo(w - 2f, 0f)
          lineTo(w - 2f, capBottom)
          lineTo(w * 0.5f, h)
          lineTo(2f, capBottom)
          close()
        }
      drawPath(path = shieldPath, color = playheadColor)
      drawPath(
        path = shieldPath,
        color = outlineColor,
        style = Stroke(width = if (isDragging) 3f else 2f),
      )
      drawLine(
        color = outlineColor,
        start = Offset(w * 0.5f, 4f),
        end = Offset(w * 0.5f, capBottom - 2f),
        strokeWidth = 2f,
      )
    }
  }
}

@Composable
private fun MarkerPin(
  marker: TimelineMarker,
  color: Color,
  offsetX: Dp,
  rulerHeight: Dp,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .offset(
          x = (offsetX - 8.dp).coerceAtLeast(0.dp),
          y = (rulerHeight - 20.dp).coerceAtLeast(12.dp),
        )
        .size(16.dp)
        .zIndex(2f)
        .clip(CircleShape)
        .background(color)
        .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
        .clickable(onClick = onClick)
        .testTag("marker_pin_${marker.markerId}"),
  )
}

@Composable
private fun TrackHeaderCell(
  track: Track,
  headerWidth: Dp,
  onToggleMute: () -> Unit,
  onToggleSolo: () -> Unit,
  onToggleLocked: () -> Unit,
  onToggleVisible: () -> Unit,
  onAddAtPlayhead: () -> Unit,
) {
  val accent = trackAccentColor(track)
  val icon =
    when {
      track.type == TrackType.VIDEO && track.isOverlay -> Icons.Default.Layers
      track.type == TrackType.VIDEO -> Icons.Default.Videocam
      track.type == TrackType.AUDIO -> Icons.Default.MusicNote
      track.type == TrackType.SUBTITLE -> Icons.Default.Subtitles
      else -> Icons.Default.Tune
    }
  val hasAudioControls = track.type == TrackType.VIDEO || track.type == TrackType.AUDIO
  val hasVisibilityControl =
    track.type == TrackType.VIDEO ||
      track.type == TrackType.SUBTITLE ||
      track.type == TrackType.ADJUSTMENT
  val addAtPlayheadCd = stringResource(R.string.cd_track_add_at_playhead)

  Row(
    modifier =
      Modifier
        .width(headerWidth)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .width(3.dp)
          .fillMaxHeight()
          .background(accent),
    )

    Column(
      modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 5.dp, vertical = 3.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = track.name,
          tint = accent,
          modifier = Modifier.size(13.dp),
        )
        Text(
          text = track.name,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (!track.isLocked && track.type != TrackType.SUBTITLE) {
          Surface(
            color = accent.copy(alpha = 0.18f),
            shape = RoundedCornerShape(5.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
            modifier =
              Modifier
                .size(21.dp)
                .clip(RoundedCornerShape(5.dp))
                .semantics { contentDescription = addAtPlayheadCd }
                .clickable(onClick = onAddAtPlayhead)
                .testTag("track_add_button_${track.trackId}"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp),
              )
            }
          }
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TrackHeaderIconToggle(
          icon = if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
          contentDescription = stringResource(R.string.cd_track_lock),
          active = track.isLocked,
          activeColor = LocalTrackColors.current.markerAmber,
          onClick = onToggleLocked,
          tag = "lock_${track.trackId}",
        )

        if (hasVisibilityControl) {
          TrackHeaderIconToggle(
            icon = if (track.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = stringResource(R.string.cd_track_visibility),
            active = !track.isVisible,
            activeColor = MaterialTheme.colorScheme.error,
            onClick = onToggleVisible,
            tag = "visibility_${track.trackId}",
          )
        }

        if (hasAudioControls) {
          TrackHeaderIconToggle(
            icon =
              if (track.isMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
              } else {
                Icons.AutoMirrored.Filled.VolumeUp
              },
            contentDescription = stringResource(R.string.cd_track_mute),
            active = track.isMuted,
            activeColor = MaterialTheme.colorScheme.error,
            onClick = onToggleMute,
            tag = "mute_${track.trackId}",
          )
          TrackHeaderTextToggle(
            label = "S",
            contentDescription = stringResource(R.string.cd_track_solo),
            active = track.isSolo,
            activeColor = LocalTrackColors.current.subtitleTrack,
            onClick = onToggleSolo,
            tag = "solo_${track.trackId}",
          )
        }
      }
    }

    Box(
      modifier =
        Modifier
          .width(1.dp)
          .fillMaxHeight()
          .background(MaterialTheme.colorScheme.outlineVariant),
    )
  }
}

@Composable
private fun TrackHeaderIconToggle(
  icon: ImageVector,
  contentDescription: String,
  active: Boolean,
  activeColor: Color,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    color =
      if (active) {
        activeColor.copy(alpha = 0.22f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(4.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color = if (active) activeColor else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier
        .size(25.dp)
        .clip(RoundedCornerShape(4.dp))
        .clickable(onClick = onClick)
        .testTag(tag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

@Composable
private fun TrackHeaderTextToggle(
  label: String,
  contentDescription: String,
  active: Boolean,
  activeColor: Color,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    color =
      if (active) {
        activeColor.copy(alpha = 0.22f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(4.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color = if (active) activeColor else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier
        .size(25.dp)
        .clip(RoundedCornerShape(4.dp))
        .semantics { this.contentDescription = contentDescription }
        .clickable(onClick = onClick)
        .testTag(tag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ClipBlock(
  clip: TimelineClip,
  isSelected: Boolean,
  isTrackLocked: Boolean,
  isTrackAudible: Boolean,
  trackVolume: Float,
  duckingWindows: List<DuckingWindow>,
  startDp: Dp,
  widthDp: Dp,
  trackRowHeight: Dp = DEFAULT_TRACK_ROW_HEIGHT,
  pxPerSecondDp: Dp,
  accentColor: Color,
  deviceProfile: DeviceProfile,
  interactionEnabled: Boolean = true,
  loadClipThumbnail: suspend (String?, Long) -> ThumbnailLoadResult?,
  loadClipWaveform: suspend (String?, Long, Long, Int) -> FloatArray?,
  onClick: () -> Unit,
  onEraserTap: () -> Unit,
) {
  val trackColors = LocalTrackColors.current
  val containerAlpha = if (clip.isEnabled) 1f else 0.42f
  val borderColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.outlineVariant
    }
  val borderWidth = if (isSelected) 1.5.dp else 1.dp

  val maxProfileFrames = VideoThumbnailCache.maxThumbnailsPerClip(deviceProfile)
  val targetFrameCount =
    when {
      maxProfileFrames <= 0 || clip.kind != ClipKind.VIDEO || clip.assetId == null -> 0
      widthDp < 84.dp -> 1
      else -> (widthDp / 52.dp).toInt().coerceIn(1, maxProfileFrames)
    }

  val thumbSamples by produceState<List<ThumbnailLoadResult>>(
    initialValue = emptyList(),
    key1 = clip.assetId,
    key2 = clip.sourceInMs,
    key3 = "${clip.sourceOutMs}_${targetFrameCount}_${deviceProfile.tier}_${deviceProfile.allowFrameSampling}",
  ) {
    value =
      if (targetFrameCount <= 0 || clip.kind != ClipKind.VIDEO || clip.assetId == null) {
        emptyList()
      } else {
        val sourceSpanMs = (clip.sourceOutMs - clip.sourceInMs).coerceAtLeast(1L)
        (0 until targetFrameCount).mapNotNull { idx ->
          val ratio = if (targetFrameCount == 1) 0f else idx.toFloat() / targetFrameCount.toFloat()
          val sampleMs = clip.sourceInMs + (sourceSpanMs * ratio).toLong()
          loadClipThumbnail(clip.assetId, sampleMs)
        }
      }
  }

  val supportsWaveform = clip.kind == ClipKind.AUDIO || clip.kind == ClipKind.VIDEO
  val targetWaveformBuckets =
    if (supportsWaveform) {
      (widthDp.value / 4f).toInt().coerceIn(12, AudioWaveformCache.DEFAULT_SOURCE_BUCKETS)
    } else {
      0
    }
  val waveformPeaks by produceState<FloatArray?>(
    initialValue = null,
    key1 = clip.assetId,
    key2 = "${clip.sourceInMs}_${clip.sourceOutMs}",
    key3 = targetWaveformBuckets,
  ) {
    value =
      if (!supportsWaveform || clip.assetId == null || targetWaveformBuckets <= 0) {
        null
      } else {
        loadClipWaveform(clip.assetId, clip.sourceInMs, clip.sourceOutMs, targetWaveformBuckets)
      }
  }

  val capsuleShape = RoundedCornerShape(8.dp)
  val capsuleBaseColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val capsuleWashColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    } else {
      accentColor.copy(alpha = 0.14f)
    }

  Box(
    modifier =
      Modifier
        .offset(x = startDp, y = 5.dp)
        .width(widthDp)
        .height((trackRowHeight - 10.dp).coerceAtLeast(42.dp))
        .alpha(containerAlpha)
        .clip(capsuleShape)
        .background(capsuleBaseColor)
        .background(
          Brush.horizontalGradient(
            listOf(
              capsuleWashColor,
              capsuleBaseColor.copy(alpha = 0.92f),
            ),
          ),
        )
        .border(borderWidth, borderColor, capsuleShape)
        .pointerInput(clip.clipId) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Eraser && interactionEnabled) {
              down.consume()
              onEraserTap()
            }
          }
        }
        .clickable(enabled = interactionEnabled, onClick = onClick)
        .testTag("clip_block_${clip.clipId}"),
  ) {
    val readyBitmaps =
      thumbSamples.mapNotNull { (it as? ThumbnailLoadResult.Ready)?.bitmap?.takeIf { bmp -> !bmp.isRecycled } }
    if (readyBitmaps.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        readyBitmaps.forEach { bmp ->
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.weight(1f).fillMaxHeight().alpha(0.84f),
          )
        }
      }
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                listOf(
                  MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                  Color.Transparent,
                  MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                ),
              ),
            ),
      )
    }

    if (supportsWaveform) {
      val clipBaseVol =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.volume ?: 1f
          ClipKind.VIDEO -> clip.videoConfig?.volume ?: 1f
          else -> 1f
        }
      val clipFadeInMs =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.fadeInMs ?: 0L
          ClipKind.VIDEO -> clip.videoConfig?.fadeInMs ?: 0L
          else -> 0L
        }
      val clipFadeOutMs =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.fadeOutMs ?: 0L
          ClipKind.VIDEO -> clip.videoConfig?.fadeOutMs ?: 0L
          else -> 0L
        }
      val effectiveTrackVol = if (isTrackAudible) trackVolume.coerceIn(0f, 2f) else 0f
      val peaks = waveformPeaks
      val hasWaveformOrAudioEnvelope =
        peaks != null ||
          clip.kind == ClipKind.AUDIO ||
          clipFadeInMs > 0L ||
          clipFadeOutMs > 0L ||
          clipBaseVol != 1f ||
          clip.keyframes().any { it.volume != null }

      if (hasWaveformOrAudioEnvelope) {
        val waveColor =
          if (clip.kind == ClipKind.AUDIO) {
            accentColor.copy(alpha = 0.88f)
          } else {
            accentColor.copy(alpha = 0.72f)
          }
        val envelopeStrokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        Canvas(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
          val w = size.width
          val h = size.height
          if (w <= 2f || h <= 2f) return@Canvas

          val isAudioClip = clip.kind == ClipKind.AUDIO
          val centerY = if (isAudioClip) h * 0.58f else h * 0.82f
          val maxAmpHalf = if (isAudioClip) h * 0.36f else h * 0.16f

          if (peaks != null && peaks.isNotEmpty()) {
            val count = peaks.size
            val stepX = w / count.toFloat()
            val barWidth = (stepX * 0.62f).coerceIn(1.5f, 4.5f)
            for (i in 0 until count) {
              val offsetMs = ((i + 0.5f) / count.toFloat() * clip.durationMs).toLong()
              val gain =
                AudioTrackMixer.computeClipEnvelopeGainAt(
                  clipStartOnTimelineMs = clip.timelineStartMs,
                  clipOffsetMs = offsetMs,
                  clipDurationMs = clip.durationMs,
                  baseVolume = clipBaseVol,
                  fadeInMs = clipFadeInMs,
                  fadeOutMs = clipFadeOutMs,
                  duckingWindows = duckingWindows,
                  volumeKeyframes = clip.keyframes(),
                  trackVolume = effectiveTrackVol,
                ).coerceIn(0f, 2f)
              val normalizedAmp = (peaks[i] * gain).coerceIn(0.05f, 1.0f)
              val x = (i + 0.5f) * stepX
              val halfBar = (normalizedAmp * maxAmpHalf).coerceAtLeast(1.5f)
              val topY = (centerY - halfBar).coerceAtLeast(0f)
              val bottomY = if (isAudioClip) (centerY + halfBar).coerceAtMost(h) else h
              drawLine(
                color = waveColor,
                start = Offset(x, topY),
                end = Offset(x, bottomY),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
              )
            }
          } else if (isAudioClip) {
            drawLine(
              color = waveColor.copy(alpha = 0.35f),
              start = Offset(0f, centerY),
              end = Offset(w, centerY),
              strokeWidth = 1.5f,
            )
          }

          if (clipFadeInMs > 0L || clipFadeOutMs > 0L || clip.keyframes().any { it.volume != null } || clipBaseVol != 1f || effectiveTrackVol != 1f) {
            val envelopePoints = 24
            val path = Path()
            for (step in 0..envelopePoints) {
              val frac = step.toFloat() / envelopePoints.toFloat()
              val offsetMs = (frac * clip.durationMs).toLong()
              val gain =
                AudioTrackMixer.computeClipEnvelopeGainAt(
                  clipStartOnTimelineMs = clip.timelineStartMs,
                  clipOffsetMs = offsetMs,
                  clipDurationMs = clip.durationMs,
                  baseVolume = clipBaseVol,
                  fadeInMs = clipFadeInMs,
                  fadeOutMs = clipFadeOutMs,
                  duckingWindows = duckingWindows,
                  volumeKeyframes = clip.keyframes(),
                  trackVolume = effectiveTrackVol,
                )
              val normY = h - (gain.coerceIn(0f, 1.5f) / 1.5f) * (h * 0.72f) - 2f
              val x = frac * w
              if (step == 0) path.moveTo(x, normY) else path.lineTo(x, normY)
            }
            drawPath(
              path = path,
              color = envelopeStrokeColor,
              style = Stroke(width = 1.5f),
            )
          }
        }
      }
    }

    // Track-type color sidebar rail on the left edge of the modular capsule
    Box(
      modifier =
        Modifier
          .align(Alignment.CenterStart)
          .width(4.dp)
          .fillMaxHeight()
          .background(if (isSelected) MaterialTheme.colorScheme.primary else accentColor),
    )

    // Floating status badges & modular capsule header
    Row(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .fillMaxWidth()
          .padding(
            start = if (isSelected) 9.dp else 7.dp,
            end = if (isSelected) 7.dp else 5.dp,
            top = 4.dp,
          ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
        shape = RoundedCornerShape(5.dp),
        border =
          BorderStroke(
            width = 1.dp,
            color =
              if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
              } else {
                MaterialTheme.colorScheme.outlineVariant
              },
          ),
        modifier = Modifier.weight(1f, fill = false),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (!clip.isEnabled) {
            Text(
              text = stringResource(R.string.label_disabled_badge),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              fontWeight = FontWeight.Bold,
              color = trackColors.trimAmber,
            )
          }
          if (clip.isLocked || isTrackLocked) {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = stringResource(R.string.label_track_locked_badge),
              tint = trackColors.markerAmber,
              modifier = Modifier.size(11.dp),
            )
          }
          Text(
            text = clip.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 4.dp),
      ) {
        val overlayTag =
          when (clip.videoConfig?.overlayKind) {
            OverlayContentKind.IMAGE -> "PNG"
            OverlayContentKind.STICKER -> "STK"
            OverlayContentKind.LOGO -> "LOGO"
            OverlayContentKind.VIDEO_PIP -> "PiP"
            else -> null
          }
        if (overlayTag != null) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            shape = RoundedCornerShape(5.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
          ) {
            Text(
              text = overlayTag,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
            )
          }
        }
        if (clip.hasVariableSpeed || abs(clip.speed - 1f) > 0.02f) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.90f),
            shape = RoundedCornerShape(5.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
          ) {
            Text(
              text = if (clip.hasVariableSpeed) "Ramp" else String.format(Locale.US, "%.1fx", clip.speed),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
            )
          }
        }
        if (widthDp >= 78.dp) {
          Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
            shape = RoundedCornerShape(5.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Text(
              text = formatClipDurationBadge(clip.durationMs),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
            )
          }
        }
      }
    }

    val keyframes = clip.keyframes()
    if (keyframes.isNotEmpty()) {
      keyframes.forEach { kf ->
        val kfOffsetDp =
          msToDp(kf.offsetMs, pxPerSecondDp).coerceIn(6.dp, (widthDp - 12.dp).coerceAtLeast(6.dp))
        Box(
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .offset(x = kfOffsetDp, y = (-3).dp)
              .size(7.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary)
              .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
        )
      }
    }
  }
}

@Composable
private fun ClipTrimHandle(
  clipId: String,
  isStartEdge: Boolean,
  edgeAnchorMs: Long,
  edgeOffsetDp: Dp,
  trackRowHeight: Dp = DEFAULT_TRACK_ROW_HEIGHT,
  pxPerSecond: Float,
  inputMode: InputDeviceMode,
  isPinchActive: Boolean,
  onBeginTrim: () -> Unit,
  onTrimEdge: (Long) -> Unit,
  onEndTrim: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
) {
  val latestEdgeAnchorMs by rememberUpdatedState(edgeAnchorMs)
  val latestInputMode by rememberUpdatedState(inputMode)
  val latestPinchActive by rememberUpdatedState(isPinchActive)
  var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var isDragging by remember { mutableStateOf(false) }

  val handleDesc =
    stringResource(
      if (isStartEdge) R.string.cd_trim_start_handle else R.string.cd_trim_end_handle,
    )
  val trackColors = LocalTrackColors.current
  val pillColor =
    if (isDragging) trackColors.trimAmber else MaterialTheme.colorScheme.surfaceContainerHigh
  val pillBorderColor =
    if (isDragging) MaterialTheme.colorScheme.background else trackColors.trimAmber
  val gripColor =
    if (isDragging) MaterialTheme.colorScheme.onTertiary else trackColors.trimAmber

  Box(
    modifier =
      Modifier
        .offset(x = (edgeOffsetDp - 14.dp).coerceAtLeast(0.dp), y = 4.dp)
        .width(28.dp)
        .height((trackRowHeight - 8.dp).coerceAtLeast(44.dp))
        .zIndex(2f)
        .onGloballyPositioned { handleCoords = it }
        .semantics { contentDescription = handleDesc }
        .pointerInput(clipId, isStartEdge, pxPerSecond) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (latestPinchActive) return@awaitEachGesture
            onPointerInputDetected(InputDeviceMode.fromPointerType(down.type))
            val coordsAtStart = handleCoords
            val startRootX =
              if (coordsAtStart != null && coordsAtStart.isAttached) {
                coordsAtStart.positionInRoot().x + down.position.x
              } else {
                down.position.x
              }
            val initialEdgeMs = latestEdgeAnchorMs
            var didActivate = false

            val afterSlop =
              awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                if (!latestPinchActive && !change.isConsumed && currentEvent.changes.count { it.pressed } <= 1) {
                  didActivate = true
                  isDragging = true
                  onBeginTrim()
                  change.consume()
                }
              }

            if (afterSlop != null && didActivate && !latestPinchActive) {
              horizontalDrag(afterSlop.id) { change ->
                if (latestPinchActive || change.isConsumed || currentEvent.changes.count { it.pressed } > 1) {
                  return@horizontalDrag
                }
                onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                change.consume()
                val currentCoords = handleCoords
                val currentRootX =
                  if (currentCoords != null && currentCoords.isAttached) {
                    currentCoords.positionInRoot().x + change.position.x
                  } else {
                    change.position.x
                  }
                val deltaPx = currentRootX - startRootX
                val deltaMs =
                  TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                    deltaPx = deltaPx,
                    pxPerSecond = pxPerSecond,
                    inputMode = latestInputMode,
                  )
                onTrimEdge((initialEdgeMs + deltaMs).coerceAtLeast(0L))
              }
              isDragging = false
              onEndTrim()
            } else {
              isDragging = false
            }
          }
        }
        .testTag(if (isStartEdge) "trim_handle_start_$clipId" else "trim_handle_end_$clipId"),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = pillColor,
      shape = RoundedCornerShape(4.dp),
      border = BorderStroke(1.dp, pillBorderColor),
      modifier =
        Modifier
          .width(if (isDragging) 14.dp else 12.dp)
          .height((trackRowHeight - 12.dp).coerceAtLeast(40.dp)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Box(
          modifier =
            Modifier
              .width(2.dp)
              .height(18.dp)
              .clip(RoundedCornerShape(1.dp))
              .background(gripColor),
        )
      }
    }
  }
}

private fun formatRulerSecondsLabel(sec: Int): String {
  val minutes = sec / 60
  val seconds = sec % 60
  return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatClipDurationBadge(durationMs: Long): String {
  val safeSeconds = durationMs.coerceAtLeast(0L) / 1000.0
  return String.format(Locale.US, "%.1fs", safeSeconds)
}

@Composable
private fun trackAccentColor(track: Track): Color {
  val colors = LocalTrackColors.current
  return when {
    track.type == TrackType.VIDEO && track.isOverlay -> MaterialTheme.colorScheme.primary
    track.type == TrackType.VIDEO -> colors.videoTrack
    track.type == TrackType.AUDIO -> colors.audioTrack
    track.type == TrackType.SUBTITLE -> colors.subtitleTrack
    else -> colors.adjustmentTrack
  }
}

@Composable
private fun clipAccentColor(kind: ClipKind): Color {
  val colors = LocalTrackColors.current
  return when (kind) {
    ClipKind.VIDEO -> colors.videoTrack
    ClipKind.AUDIO -> colors.audioTrack
    ClipKind.SUBTITLE -> colors.subtitleTrack
    ClipKind.ADJUSTMENT -> colors.adjustmentTrack
  }
}

@Composable
private fun markerColorFor(markerColor: MarkerColor): Color {
  val colors = LocalTrackColors.current
  return when (markerColor) {
    MarkerColor.AMBER -> colors.markerAmber
    MarkerColor.CYAN -> colors.markerCyan
    MarkerColor.CORAL -> colors.markerCoral
    MarkerColor.EMERALD -> colors.markerEmerald
    MarkerColor.VIOLET -> colors.markerViolet
  }
}

private fun msToDp(ms: Long, pxPerSecondDp: Dp): Dp = pxPerSecondDp * (ms / 1000f)
