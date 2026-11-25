package com.example.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddToPhotos
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.media.model.PreviewQualityProfile
import com.example.core.media.model.RenderSettings
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.RippleScope
import com.example.core.storage.TempCacheStorageManager
import com.example.ui.theme.LocalTrackColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun StudioHeaderBar(
  projectName: String,
  playheadMs: Long,
  totalDurationMs: Long,
  inputMode: InputDeviceMode,
  renderSettings: RenderSettings,
  isLoopEnabled: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onSetPreviewQuality: (PreviewQualityProfile) -> Unit,
  onToggleProxies: () -> Unit,
  onToggleLoop: () -> Unit,
  onOpenShortcuts: () -> Unit,
  compactSingleRow: Boolean = false,
) {
  var showPlaybackSettings by remember { mutableStateOf(false) }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(
              horizontal = if (compactSingleRow) 10.dp else 12.dp,
              vertical = if (compactSingleRow) 2.dp else 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (compactSingleRow) {
          Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = projectName,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(4.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.testTag("input_mode_badge"),
            ) {
              Text(
                text = inputMode.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
              )
            }
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLowest,
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
              modifier = Modifier.testTag("header_timecode_readout"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = formatTimecode(playheadMs),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = " / ${formatTimecode(totalDurationMs)}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Text(
              text = "${renderSettings.previewQuality.targetHeight}p",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        } else {
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = projectName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
              )
              Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("input_mode_badge"),
              ) {
                Text(
                  text = inputMode.label,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
                modifier = Modifier.testTag("header_timecode_readout"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = formatTimecode(playheadMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = " / ${formatTimecode(totalDurationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              Text(
                text = "${renderSettings.previewQuality.targetHeight}p",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("undo_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Undo,
              contentDescription = stringResource(R.string.cd_undo),
            )
          }
          IconButton(
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("redo_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Redo,
              contentDescription = stringResource(R.string.cd_redo),
            )
          }
          Box {
            IconButton(
              onClick = { showPlaybackSettings = true },
              modifier =
                Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("quick_playback_settings_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.cd_quick_playback_settings),
                tint =
                  if (showPlaybackSettings) {
                    MaterialTheme.colorScheme.secondary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
              )
            }

            DropdownMenu(
              expanded = showPlaybackSettings,
              onDismissRequest = { showPlaybackSettings = false },
            ) {
              Text(
                text = stringResource(R.string.menu_preview_resolution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
              )
              PreviewQualityProfile.entries.forEach { profile ->
                val isSelected = renderSettings.previewQuality == profile
                DropdownMenuItem(
                  text = { Text(profile.label) },
                  leadingIcon = {
                    if (isSelected) {
                      Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                      )
                    } else {
                      Spacer(modifier = Modifier.size(20.dp))
                    }
                  },
                  onClick = {
                    onSetPreviewQuality(profile)
                    showPlaybackSettings = false
                  },
                  modifier = Modifier.testTag("menu_quality_${profile.name}"),
                )
              }
              HorizontalDivider()
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_use_proxies)) },
                trailingIcon = {
                  Text(
                    text = if (renderSettings.useProxiesForPreview) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                      if (renderSettings.useProxiesForPreview) {
                        MaterialTheme.colorScheme.secondary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                  )
                },
                onClick = {
                  onToggleProxies()
                  showPlaybackSettings = false
                },
                modifier = Modifier.testTag("menu_toggle_proxies"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_loop_playback)) },
                trailingIcon = {
                  Text(
                    text = if (isLoopEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                      if (isLoopEnabled) {
                        MaterialTheme.colorScheme.secondary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                  )
                },
                onClick = {
                  onToggleLoop()
                  showPlaybackSettings = false
                },
                modifier = Modifier.testTag("menu_toggle_loop"),
              )
              HorizontalDivider()
              DropdownMenuItem(
                text = { Text(stringResource(R.string.title_keyboard_shortcuts)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Default.Keyboard, contentDescription = null)
                },
                onClick = {
                  showPlaybackSettings = false
                  onOpenShortcuts()
                },
                modifier = Modifier.testTag("menu_open_shortcuts"),
              )
            }
          }
        }
      }
      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivePreviewSummaryCard(
  uiState: TimelineSkeletonUiState,
  previewState: PreviewPlayerState,
  onBeginScrub: () -> Unit,
  onPlayheadScrub: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onSeekToStart: () -> Unit,
  onStepBackFrame: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onStepForwardFrame: () -> Unit,
  onToggleLoop: () -> Unit,
  onPreviousMarker: () -> Unit,
  onNextMarker: () -> Unit,
  onCyclePreviewQuality: () -> Unit,
  onToggleProxies: () -> Unit,
  onToggleSnap: () -> Unit,
  onZoomOut: () -> Unit,
  onZoomIn: () -> Unit,
  onResetZoom: () -> Unit,
  onFitZoom: () -> Unit = {},
  onOpenShortcuts: () -> Unit,
  onQuickOpenColorLut: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  fillAvailableHeight: Boolean = false,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 2.dp,
) {
  val totalMs = uiState.timeline.totalDurationMs.coerceAtLeast(1_000L)
  val selectedClip = uiState.selectedClip
  val zoomPct = (uiState.ergonomics.zoomScale * 100f).roundToInt()
  val isLoopEnabled = uiState.isPreviewLoopEnabled || previewState.isLoopEnabled
  val trackColors = LocalTrackColors.current

  val videoCfg = selectedClip?.takeIf { it.kind == ClipKind.VIDEO }?.videoConfig
  val adjCfg = selectedClip?.takeIf { it.kind == ClipKind.ADJUSTMENT }?.adjustmentConfig
  val resolvedColor =
    when {
      videoCfg != null -> BuiltInLutPresets.resolveEffectiveColor(videoCfg, uiState.savedLutPresets)
      adjCfg != null -> BuiltInLutPresets.resolveEffectiveColor(adjCfg, uiState.savedLutPresets)
      else -> null
    }
  val hasFx =
    (resolvedColor?.hasColorModifications == true) ||
      (videoCfg != null && (abs(videoCfg.rotationDegrees) > 0.5f || abs(videoCfg.scale - 1f) > 0.01f))
  val videoTelemetry =
    buildString {
      if (previewState.videoWidth > 0 && previewState.videoHeight > 0) {
        append(" · ${previewState.videoWidth}x${previewState.videoHeight}")
      }
      if (previewState.videoRotationDegrees != 0) {
        append(" · ${previewState.videoRotationDegrees}°")
      }
      val activeLut = resolvedColor?.activeLut
      if (activeLut != null) {
        append(" · ${activeLut.shortCode}")
      } else if (hasFx) {
        append(" · FX")
      }
      if (previewState.isScrubbing) append(" · SYNC")
    }

  val cacheLedColor =
    when {
      previewState.activeProxyCount > 0 ||
        (uiState.renderSettings.useProxiesForPreview && uiState.cacheStorageStats.proxiesBytes > 0L) ->
        trackColors.audioTrack
      previewState.diagnosticNotice != null ||
        uiState.cacheStorageStats.totalCacheBytes > 150L * 1024L * 1024L ->
        trackColors.markerAmber
      else -> MaterialTheme.colorScheme.secondary
    }
  val cacheStatusLabel =
    if (uiState.cacheStorageStats.totalCacheBytes > 0L) {
      stringResource(
        R.string.label_cache_status,
        TempCacheStorageManager.formatBytes(uiState.cacheStorageStats.totalCacheBytes),
      )
    } else {
      stringResource(R.string.label_cache_clean)
    }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.fillMaxHeight().verticalScroll(rememberScrollState()) else it }
          .padding(
            horizontal = if (compact) 8.dp else 10.dp,
            vertical = if (compact) 4.dp else 6.dp,
          ),
      verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            selectedClip?.let {
              "${it.label} (${formatTimecode(it.durationMs)})$videoTelemetry"
            } ?: "${stringResource(R.string.label_no_clip_selected)}$videoTelemetry",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )

        if (selectedClip?.kind == ClipKind.VIDEO || selectedClip?.kind == ClipKind.ADJUSTMENT) {
          val isLutOpen =
            uiState.activeSubPanel == EditorToolSubPanel.ADJUSTMENTS &&
              uiState.activeInspectorTab == InspectorTab.COLOR_LUT
          Surface(
            color =
              if (isLutOpen) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
              } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
              },
            shape = RoundedCornerShape(6.dp),
            border =
              BorderStroke(
                1.dp,
                if (isLutOpen || resolvedColor?.activeLut != null) {
                  MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                } else {
                  MaterialTheme.colorScheme.outlineVariant
                },
              ),
            modifier =
              Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onQuickOpenColorLut)
                .testTag("quick_inspector_lut_chip"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = stringResource(R.string.cd_quick_inspector_lut),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(12.dp),
              )
              Text(
                text = resolvedColor?.activeLut?.shortCode ?: stringResource(R.string.action_quick_lut),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
              )
            }
          }
        }

        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          shape = RoundedCornerShape(6.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.testTag("cache_proxy_status_indicator"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .size(7.dp)
                  .clip(CircleShape)
                  .background(cacheLedColor),
            )
            Text(
              text =
                if (previewState.activeProxyCount > 0) {
                  "$cacheStatusLabel · ${previewState.activeProxyCount}p"
                } else {
                  cacheStatusLabel
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
            )
          }
        }
      }

      val controlsContent: @Composable () -> Unit = {
        AssistChip(
          onClick = onCyclePreviewQuality,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.HighQuality,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          label = {
            Text(
              stringResource(
                R.string.label_preview_quality,
                uiState.renderSettings.previewQuality.label,
              ),
            )
          },
          modifier = Modifier.testTag("preview_quality_chip"),
        )

        FilterChip(
          selected = uiState.renderSettings.useProxiesForPreview,
          onClick = onToggleProxies,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Speed,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          label = {
            Text(
              if (uiState.renderSettings.useProxiesForPreview) {
                stringResource(R.string.label_proxies_on)
              } else {
                stringResource(R.string.label_proxies_off)
              },
            )
          },
          modifier = Modifier.testTag("proxy_toggle_chip"),
        )

        FilterChip(
          selected = uiState.ergonomics.snapEnabled,
          onClick = onToggleSnap,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.GridOn,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          label = {
            Text(
              if (uiState.ergonomics.snapEnabled) {
                stringResource(R.string.label_snap_on)
              } else {
                stringResource(R.string.label_snap_off)
              },
            )
          },
          modifier = Modifier.testTag("snap_toggle_chip"),
        )

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          val resetZoomCd = stringResource(R.string.cd_reset_zoom)
          val fitZoomCd = stringResource(R.string.cd_fit_zoom)
          IconButton(
            onClick = onZoomOut,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("zoom_out_button"),
          ) {
            Icon(
              imageVector = Icons.Default.ZoomOut,
              contentDescription = stringResource(R.string.cd_zoom_out),
            )
          }
          AssistChip(
            onClick = onResetZoom,
            label = { Text("${zoomPct}%") },
            modifier =
              Modifier
                .semantics { contentDescription = resetZoomCd }
                .testTag("zoom_reset_chip"),
          )
          IconButton(
            onClick = onZoomIn,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("zoom_in_button"),
          ) {
            Icon(
              imageVector = Icons.Default.ZoomIn,
              contentDescription = stringResource(R.string.cd_zoom_in),
            )
          }
          AssistChip(
            onClick = onFitZoom,
            leadingIcon = {
              Icon(
                imageVector = Icons.Default.FitScreen,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
              )
            },
            label = { Text(stringResource(R.string.action_fit_zoom)) },
            modifier =
              Modifier
                .semantics { contentDescription = fitZoomCd }
                .testTag("zoom_fit_button"),
          )
        }

        IconButton(
          onClick = onPreviousMarker,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("prev_marker_button"),
        ) {
          Icon(
            imageVector = Icons.Default.FastRewind,
            contentDescription = stringResource(R.string.cd_prev_marker),
          )
        }
        IconButton(
          onClick = onNextMarker,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("next_marker_button"),
        ) {
          Icon(
            imageVector = Icons.Default.FastForward,
            contentDescription = stringResource(R.string.cd_next_marker),
          )
        }
        IconButton(
          onClick = onOpenShortcuts,
          modifier =
            Modifier
              .minimumInteractiveComponentSize()
              .testTag("keyboard_shortcuts_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Keyboard,
            contentDescription = stringResource(R.string.cd_keyboard_shortcuts),
          )
        }
      }

      if (compact && fillAvailableHeight) {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          controlsContent()
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          controlsContent()
        }
      }

      if (uiState.lastStatusMessage != null) {
        Text(
          text = uiState.lastStatusMessage,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.testTag("status_feedback_text"),
        )
      }

      Slider(
        value = uiState.timeline.playheadMs.toFloat().coerceIn(0f, totalMs.toFloat()),
        onValueChange = {
          onBeginScrub()
          onPlayheadScrub(it.toLong())
        },
        onValueChangeFinished = onEndScrub,
        valueRange = 0f..totalMs.toFloat(),
        colors =
          SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
          ),
        modifier = Modifier.fillMaxWidth().height(24.dp).testTag("playhead_slider"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        TransportControlsTray(
          hasPlayableMedia = previewState.hasPlayableMedia,
          isPlaying = previewState.isPlaying,
          isLoopEnabled = isLoopEnabled,
          onSeekToStart = onSeekToStart,
          onStepBackFrame = onStepBackFrame,
          onTogglePlayPause = onTogglePlayPause,
          onStepForwardFrame = onStepForwardFrame,
          onToggleLoop = onToggleLoop,
        )
      }
    }
  }
}

@Composable
internal fun CompactLandscapeTransportStrip(
  uiState: TimelineSkeletonUiState,
  previewState: PreviewPlayerState,
  onBeginScrub: () -> Unit,
  onPlayheadScrub: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onSeekToStart: () -> Unit,
  onStepBackFrame: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onStepForwardFrame: () -> Unit,
  onToggleLoop: () -> Unit,
  onToggleSnap: () -> Unit,
  onResetZoom: () -> Unit,
  onFitZoom: () -> Unit,
  modifier: Modifier = Modifier,
  outerHorizontalPadding: Dp = 8.dp,
) {
  val totalMs = uiState.timeline.totalDurationMs.coerceAtLeast(1_000L)
  val zoomPct = (uiState.ergonomics.zoomScale * 100f).roundToInt()
  val isLoopEnabled = uiState.isPreviewLoopEnabled || previewState.isLoopEnabled
  val resetZoomCd = stringResource(R.string.cd_reset_zoom)
  val fitZoomCd = stringResource(R.string.cd_fit_zoom)

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = outerHorizontalPadding, vertical = 2.dp)
        .testTag("compact_landscape_transport_strip"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Slider(
        value = uiState.timeline.playheadMs.toFloat().coerceIn(0f, totalMs.toFloat()),
        onValueChange = {
          onBeginScrub()
          onPlayheadScrub(it.toLong())
        },
        onValueChangeFinished = onEndScrub,
        valueRange = 0f..totalMs.toFloat(),
        colors =
          SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
          ),
        modifier = Modifier.fillMaxWidth().height(20.dp).testTag("playhead_slider"),
      )

      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FilledIconButton(
          onClick = onTogglePlayPause,
          enabled = previewState.hasPlayableMedia,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
              disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("preview_play_pause_button"),
        ) {
          Icon(
            imageVector = if (previewState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.action_play_pause_preview),
          )
        }

        AssistChip(
          onClick = onResetZoom,
          label = { Text("${zoomPct}%") },
          modifier =
            Modifier
              .semantics { contentDescription = resetZoomCd }
              .testTag("zoom_reset_chip"),
        )

        AssistChip(
          onClick = onFitZoom,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.FitScreen,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
            )
          },
          label = { Text(stringResource(R.string.action_fit_zoom)) },
          modifier =
            Modifier
              .semantics { contentDescription = fitZoomCd }
              .testTag("zoom_fit_button"),
        )

        FilterChip(
          selected = uiState.ergonomics.snapEnabled,
          onClick = onToggleSnap,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.GridOn,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
            )
          },
          label = {
            Text(
              if (uiState.ergonomics.snapEnabled) {
                stringResource(R.string.label_snap_on)
              } else {
                stringResource(R.string.label_snap_off)
              },
            )
          },
          modifier = Modifier.testTag("snap_toggle_chip"),
        )

        IconButton(
          onClick = onStepBackFrame,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_step_back_button"),
        ) {
          Icon(
            imageVector = Icons.Default.ChevronLeft,
            contentDescription = stringResource(R.string.cd_transport_step_back),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = onStepForwardFrame,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_step_forward_button"),
        ) {
          Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.cd_transport_step_forward),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = onSeekToStart,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_start_button"),
        ) {
          Icon(
            imageVector = Icons.Default.SkipPrevious,
            contentDescription = stringResource(R.string.cd_transport_start),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = onToggleLoop,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_loop_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = stringResource(R.string.cd_transport_loop),
            tint =
              if (isLoopEnabled) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
        }
      }
    }
  }
}

@Composable
private fun TransportControlsTray(
  hasPlayableMedia: Boolean,
  isPlaying: Boolean,
  isLoopEnabled: Boolean,
  onSeekToStart: () -> Unit,
  onStepBackFrame: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onStepForwardFrame: () -> Unit,
  onToggleLoop: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.testTag("transport_controls_tray"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      IconButton(
        onClick = onSeekToStart,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_start_button"),
      ) {
        Icon(
          imageVector = Icons.Default.SkipPrevious,
          contentDescription = stringResource(R.string.cd_transport_start),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }

      IconButton(
        onClick = onStepBackFrame,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_step_back_button"),
      ) {
        Icon(
          imageVector = Icons.Default.ChevronLeft,
          contentDescription = stringResource(R.string.cd_transport_step_back),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }

      FilledIconButton(
        onClick = onTogglePlayPause,
        enabled = hasPlayableMedia,
        colors =
          IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        modifier = Modifier.minimumInteractiveComponentSize().testTag("preview_play_pause_button"),
      ) {
        Icon(
          imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = stringResource(R.string.action_play_pause_preview),
        )
      }

      IconButton(
        onClick = onStepForwardFrame,
        modifier =
          Modifier
            .minimumInteractiveComponentSize()
            .testTag("transport_step_forward_button"),
      ) {
        Icon(
          imageVector = Icons.Default.ChevronRight,
          contentDescription = stringResource(R.string.cd_transport_step_forward),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }

      IconButton(
        onClick = onToggleLoop,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("transport_loop_button"),
      ) {
        Icon(
          imageVector = Icons.Default.Repeat,
          contentDescription = stringResource(R.string.cd_transport_loop),
          tint =
            if (isLoopEnabled) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }
  }
}

@Composable
internal fun EditActionDeck(
  uiState: TimelineSkeletonUiState,
  onSplitAtPlayhead: () -> Unit,
  onToggleTrimPanel: () -> Unit,
  onToggleAdjustmentsPanel: () -> Unit,
  onQuickColorLut: () -> Unit,
  onToggleKeyframesPanel: () -> Unit,
  onDuplicateClip: () -> Unit,
  onToggleRipple: () -> Unit,
  onCycleRippleScope: () -> Unit,
  onToggleClipEnabled: () -> Unit,
  onDeleteClip: () -> Unit,
  onAddMarker: () -> Unit,
  onOpenAudioTools: () -> Unit,
  onOpenSubtitlesBatch: () -> Unit,
  onOpenAttributesSnapshots: () -> Unit,
  onQuickAddAudio: () -> Unit = {},
  onQuickAddOverlay: () -> Unit = {},
  onQuickAddEffects: () -> Unit = {},
  compact: Boolean = false,
) {
  val selectedClip = uiState.selectedClip
  val selectedTrack = selectedClip?.let { clip -> uiState.timeline.tracks.find { it.trackId == clip.trackId } }
  val isSelectedLocked = selectedClip?.isLocked == true || selectedTrack?.isLocked == true
  val rippleEnabled = uiState.timeline.editOptions.rippleEnabled
  val rippleScope = uiState.timeline.editOptions.rippleScope
  val canInspectSelected = selectedClip != null
  val canColorLutSelected = selectedClip?.kind == ClipKind.VIDEO || selectedClip?.kind == ClipKind.ADJUSTMENT
  val canKeyframeSelected = selectedClip?.kind == ClipKind.VIDEO || selectedClip?.kind == ClipKind.AUDIO

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(
          horizontal = if (compact) 8.dp else 10.dp,
          vertical = if (compact) 2.dp else 6.dp,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shadowElevation = 8.dp,
      modifier = Modifier.fillMaxWidth().testTag("floating_contextual_dock"),
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
              horizontal = if (compact) 6.dp else 8.dp,
              vertical = if (compact) 4.dp else 6.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        StudioDockActionButton(
          icon = Icons.Default.LibraryMusic,
          label = stringResource(R.string.quick_dock_add_audio),
          contentDescription = stringResource(R.string.cd_quick_dock_add_audio),
          isSelected = uiState.activeInsertRequest?.kind == TimelineInsertKind.AUDIO,
          enabled = true,
          compact = compact,
          onClick = onQuickAddAudio,
          tag = "quick_add_audio_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.AddToPhotos,
          label = stringResource(R.string.quick_dock_add_overlay),
          contentDescription = stringResource(R.string.cd_quick_dock_add_overlay),
          isSelected = uiState.activeInsertRequest?.kind == TimelineInsertKind.OVERLAY,
          enabled = true,
          compact = compact,
          onClick = onQuickAddOverlay,
          tag = "quick_add_overlay_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.AutoFixHigh,
          label = stringResource(R.string.quick_dock_add_effects),
          contentDescription = stringResource(R.string.cd_quick_dock_add_effects),
          isSelected = uiState.activeInsertRequest?.kind == TimelineInsertKind.EFFECTS,
          isCutOrTrim = true,
          enabled = true,
          compact = compact,
          onClick = onQuickAddEffects,
          tag = "quick_add_effects_button",
        )

        VerticalDivider(
          thickness = 1.dp,
          color = MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.height(if (compact) 28.dp else 34.dp).padding(horizontal = 2.dp),
        )

        StudioDockActionButton(
          icon = Icons.Default.ContentCut,
          label = stringResource(R.string.action_split_playhead),
          contentDescription = stringResource(R.string.cd_split_clip),
          isSelected = false,
          isCutOrTrim = true,
          enabled = uiState.timeline.clips.isNotEmpty(),
          compact = compact,
          onClick = onSplitAtPlayhead,
          tag = "split_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.Tune,
          label = stringResource(R.string.title_inspector_console),
          contentDescription = stringResource(R.string.title_adjustments_panel),
          isSelected = uiState.activeSubPanel == EditorToolSubPanel.ADJUSTMENTS,
          enabled = canInspectSelected,
          compact = compact,
          onClick = onToggleAdjustmentsPanel,
          tag = "open_adjustments_panel_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.Palette,
          label = stringResource(R.string.action_quick_lut),
          contentDescription = stringResource(R.string.cd_quick_inspector_lut),
          isSelected =
            uiState.activeSubPanel == EditorToolSubPanel.ADJUSTMENTS &&
              uiState.activeInspectorTab == InspectorTab.COLOR_LUT,
          enabled = canColorLutSelected,
          compact = compact,
          onClick = onQuickColorLut,
          tag = "open_lut_quick_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.FileCopy,
          label = stringResource(R.string.action_duplicate_clip),
          contentDescription = stringResource(R.string.cd_duplicate_clip),
          isSelected = false,
          enabled = selectedClip != null && selectedTrack?.isLocked != true,
          compact = compact,
          onClick = onDuplicateClip,
          tag = "duplicate_clip_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.Straighten,
          label = stringResource(R.string.action_trim_range),
          contentDescription = stringResource(R.string.title_trim_panel),
          isSelected = uiState.activeSubPanel == EditorToolSubPanel.TRIM,
          isCutOrTrim = true,
          enabled = selectedClip != null && !isSelectedLocked,
          compact = compact,
          onClick = onToggleTrimPanel,
          tag = "open_trim_panel_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.DeleteOutline,
          label = stringResource(R.string.action_delete_clip),
          contentDescription = stringResource(R.string.cd_delete_clip),
          isSelected = false,
          isDanger = true,
          enabled = selectedClip != null && !isSelectedLocked,
          compact = compact,
          onClick = onDeleteClip,
          tag = "delete_clip_button",
        )

        VerticalDivider(
          thickness = 1.dp,
          color = MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.height(if (compact) 28.dp else 34.dp).padding(horizontal = 2.dp),
        )

        StudioDockActionButton(
          icon = Icons.Default.Diamond,
          label = stringResource(R.string.action_keyframes),
          contentDescription = stringResource(R.string.editor_keyframes_title),
          isSelected = uiState.activeSubPanel == EditorToolSubPanel.KEYFRAMES,
          enabled = canKeyframeSelected,
          compact = compact,
          onClick = onToggleKeyframesPanel,
          tag = "open_keyframes_panel_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.Layers,
          label = if (rippleEnabled) stringResource(R.string.action_ripple_on) else stringResource(R.string.action_ripple_off),
          contentDescription = stringResource(R.string.cd_toggle_ripple),
          isSelected = rippleEnabled,
          compact = compact,
          onClick = onToggleRipple,
          tag = "ripple_toggle_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.Speed,
          label =
            when (rippleScope) {
              RippleScope.SAME_TRACK -> stringResource(R.string.action_ripple_scope_track)
              RippleScope.ALL_TRACKS -> stringResource(R.string.action_ripple_scope_all)
            },
          contentDescription = stringResource(R.string.action_ripple_scope_all),
          isSelected = rippleScope == RippleScope.ALL_TRACKS,
          compact = compact,
          onClick = onCycleRippleScope,
          tag = "ripple_scope_chip",
        )

        StudioDockActionButton(
          icon = Icons.Default.GraphicEq,
          label = stringResource(R.string.action_audio_tools),
          contentDescription = stringResource(R.string.action_audio_tools),
          isSelected = uiState.activeSheet == EditorToolSheet.AUDIO,
          compact = compact,
          onClick = onOpenAudioTools,
          tag = "open_audio_tools_chip",
        )

        StudioDockActionButton(
          icon = Icons.Default.Subtitles,
          label = stringResource(R.string.action_subtitles_batch),
          contentDescription = stringResource(R.string.action_subtitles_batch),
          isSelected = uiState.activeSheet == EditorToolSheet.SUBTITLES,
          compact = compact,
          onClick = onOpenSubtitlesBatch,
          tag = "open_subtitles_chip",
        )

        StudioDockActionButton(
          icon = Icons.Default.BookmarkAdd,
          label = stringResource(R.string.action_add_marker),
          contentDescription = stringResource(R.string.cd_add_marker),
          isSelected = false,
          compact = compact,
          onClick = onAddMarker,
          tag = "add_marker_button",
        )

        val isClipEnabled = selectedClip?.isEnabled ?: true
        StudioDockActionButton(
          icon = if (isClipEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
          label = if (isClipEnabled) stringResource(R.string.action_disable_clip) else stringResource(R.string.action_enable_clip),
          contentDescription = stringResource(R.string.cd_toggle_clip_enabled),
          isSelected = !isClipEnabled,
          isCutOrTrim = !isClipEnabled,
          enabled = selectedClip != null && selectedTrack?.isLocked != true,
          compact = compact,
          onClick = onToggleClipEnabled,
          tag = "toggle_enable_button",
        )

        StudioDockActionButton(
          icon = Icons.Default.ContentCopy,
          label = stringResource(R.string.action_attributes_snapshots),
          contentDescription = stringResource(R.string.action_attributes_snapshots),
          isSelected = uiState.activeSheet == EditorToolSheet.ATTRIBUTES_SNAPSHOTS,
          compact = compact,
          onClick = onOpenAttributesSnapshots,
          tag = "open_attrs_snapshots_chip",
        )
      }
    }
  }
}

@Composable
private fun StudioDockActionButton(
  icon: ImageVector,
  label: String,
  contentDescription: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  tag: String,
  enabled: Boolean = true,
  isCutOrTrim: Boolean = false,
  isDanger: Boolean = false,
  compact: Boolean = false,
) {
  val haptic = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val pressScale by animateFloatAsState(
    targetValue = if (isPressed && enabled) 0.92f else 1f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
    label = "dock_press_scale",
  )

  val accentColor =
    when {
      isDanger -> MaterialTheme.colorScheme.error
      isCutOrTrim -> MaterialTheme.colorScheme.tertiary
      else -> MaterialTheme.colorScheme.primary
    }

  val targetContainer =
    when {
      isPressed && enabled -> accentColor.copy(alpha = 0.24f)
      isSelected -> accentColor.copy(alpha = 0.18f)
      isCutOrTrim && !isSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
      else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    }

  val targetContent =
    when {
      isDanger -> MaterialTheme.colorScheme.error
      isSelected -> accentColor
      isCutOrTrim -> MaterialTheme.colorScheme.tertiary
      isPressed && enabled -> accentColor
      else -> MaterialTheme.colorScheme.onSurface
    }

  val targetBorder =
    when {
      isPressed && enabled -> accentColor.copy(alpha = 0.9f)
      isSelected -> accentColor.copy(alpha = 0.85f)
      isCutOrTrim -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
      else -> MaterialTheme.colorScheme.outlineVariant
    }

  val containerColor by animateColorAsState(targetValue = targetContainer, label = "dock_bg")
  val contentColor by animateColorAsState(targetValue = targetContent, label = "dock_fg")
  val borderColor by animateColorAsState(targetValue = targetBorder, label = "dock_border")

  Surface(
    color = containerColor,
    shape = RoundedCornerShape(11.dp),
    border = BorderStroke(1.dp, borderColor),
    modifier =
      Modifier
        .graphicsLayer {
          scaleX = pressScale
          scaleY = pressScale
        }
        .defaultMinSize(minWidth = if (compact) 54.dp else 60.dp, minHeight = 48.dp)
        .minimumInteractiveComponentSize()
        .clip(RoundedCornerShape(11.dp))
        .alpha(if (enabled) 1f else 0.36f)
        .clickable(
          enabled = enabled,
          interactionSource = interactionSource,
          indication = ripple(bounded = true, color = accentColor),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
          },
        )
        .testTag(tag),
  ) {
    Column(
      modifier =
        Modifier.padding(
          horizontal = if (compact) 8.dp else 10.dp,
          vertical = if (compact) 4.dp else 6.dp,
        ),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = Modifier.size(if (compact) 17.dp else 19.dp),
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (compact) 10.sp else 11.sp),
        fontWeight = if (isSelected || isCutOrTrim) FontWeight.SemiBold else FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

internal fun formatTimecode(ms: Long): String {
  val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  val tenths = (ms % 1000L) / 100L
  return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
}
