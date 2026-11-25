package com.example.ui.export

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.export.ExportEstimator
import com.example.core.export.ExportJobStatus
import com.example.core.export.ExportQueueItem
import com.example.core.export.ExportRange
import com.example.core.export.RangeSliceHelper
import com.example.core.media.model.ExportPreset
import com.example.core.model.ClipKind
import com.example.core.model.TimelineMarker
import com.example.core.storage.TempCacheStorageManager
import com.example.ui.editor.TimelineSkeletonUiState
import com.example.ui.editor.formatTimecode
import com.example.ui.theme.LocalTrackColors

private enum class ExportRangeSelectionMode {
  FULL,
  BETWEEN_MARKERS,
  CHAPTERS,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
  uiState: TimelineSkeletonUiState,
  exportQueue: List<ExportQueueItem>,
  onSelectPreset: (ExportPreset) -> Unit,
  onEnqueueRange: (ExportRange) -> Unit,
  onEnqueueChapters: () -> Unit,
  onGenerateProxies: () -> Unit,
  onCancelJob: (String) -> Unit,
  onRetryJob: (String) -> Unit,
  onClearFinished: () -> Unit,
  onRefreshStorageStats: () -> Unit,
  onClearTemporaryCache: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val trackColors = LocalTrackColors.current
  val sortedMarkers =
    remember(uiState.timeline.markers) { uiState.timeline.markers.sortedBy { it.positionMs } }
  val totalDurationMs = uiState.timeline.totalDurationMs
  var rangeMode by remember { mutableStateOf(ExportRangeSelectionMode.FULL) }
  var startBoundaryIdx by remember(sortedMarkers) { mutableIntStateOf(-1) }
  var endBoundaryIdx by remember(sortedMarkers) {
    mutableIntStateOf(if (sortedMarkers.isNotEmpty()) 0 else 1)
  }

  LaunchedEffect(Unit) {
    onRefreshStorageStats()
  }

  val activeJob =
    remember(exportQueue) {
      exportQueue.firstOrNull { it.status == ExportJobStatus.RUNNING }
        ?: exportQueue.firstOrNull { it.status == ExportJobStatus.QUEUED }
    }

  val selectedRangeDurationMs =
    remember(rangeMode, sortedMarkers, startBoundaryIdx, endBoundaryIdx, totalDurationMs) {
      when (rangeMode) {
        ExportRangeSelectionMode.FULL, ExportRangeSelectionMode.CHAPTERS ->
          RangeSliceHelper.calculateExportDurationMs(ExportRange.FullTimeline, totalDurationMs)
        ExportRangeSelectionMode.BETWEEN_MARKERS ->
          RangeSliceHelper.resolveMarkerSelectionBounds(
            sortedMarkers = sortedMarkers,
            startBoundaryIndex = startBoundaryIdx,
            endBoundaryIndex = endBoundaryIdx,
            totalDurationMs = totalDurationMs,
          ).durationMs
      }
    }

  val realClipsCount =
    remember(uiState.timeline.clips) {
      uiState.timeline.clips.count { it.assetId != null && it.isEnabled }
    }
  val activeAdjustmentsCount =
    remember(uiState.timeline.clips) {
      uiState.timeline.clips.count { clip ->
        clip.isEnabled &&
          (clip.kind == ClipKind.ADJUSTMENT ||
            (clip.videoConfig != null &&
              (clip.videoConfig.brightness != 0f ||
                clip.videoConfig.contrast != 1f ||
                clip.videoConfig.saturation != 1f ||
                clip.videoConfig.exposure != 0f ||
                clip.videoConfig.temperature != 0f ||
                clip.videoConfig.tint != 0f ||
                clip.videoConfig.shadows != 0f ||
                clip.videoConfig.midtones != 0f ||
                clip.videoConfig.highlights != 0f ||
                clip.videoConfig.vignette != 0f ||
                clip.videoConfig.lutPresetId != null ||
                clip.videoConfig.rotationDegrees != 0f ||
                clip.videoConfig.scale != 1f ||
                clip.videoConfig.keyframes.isNotEmpty())))
      }
    }

  val exportEstimate =
    remember(uiState.selectedExportPreset, selectedRangeDurationMs, uiState.deviceProfile, activeAdjustmentsCount) {
      ExportEstimator.estimate(
        config = uiState.selectedExportPreset.config,
        rangeDurationMs = selectedRangeDurationMs,
        deviceProfile = uiState.deviceProfile,
        activeEffectsCount = activeAdjustmentsCount,
      )
    }

  LazyColumn(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Studio Export Header
    item {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.title_export_studio),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.subtitle_export_independent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (uiState.assets.isEmpty()) {
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)),
          modifier = Modifier.fillMaxWidth().testTag("export_missing_assets_banner"),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Info,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = stringResource(R.string.export_missing_assets_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }

    if (activeJob != null) {
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f)),
          modifier = Modifier.fillMaxWidth().testTag("active_export_banner"),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = activeJob.title,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text =
                    if (activeJob.status == ExportJobStatus.RUNNING) {
                      stringResource(R.string.export_status_running, activeJob.progressPercent)
                    } else {
                      stringResource(R.string.export_status_queued)
                    },
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.testTag("active_export_percent_text"),
                )
              }
              OutlinedButton(
                onClick = { onCancelJob(activeJob.jobId) },
                modifier =
                  Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("cancel_active_export_button"),
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = stringResource(R.string.cd_cancel_export),
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.export_action_cancel))
              }
            }
            LinearProgressIndicator(
              progress = { (activeJob.progressPercent / 100f).coerceIn(0f, 1f) },
              color = MaterialTheme.colorScheme.tertiary,
              trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(6.dp)
                  .clip(RoundedCornerShape(3.dp))
                  .testTag("active_export_progress_bar"),
            )
          }
        }
      }
    }

    // Export Preset, Range, Technical Output Summary & Actions
    item {
      val cfg = uiState.selectedExportPreset.config
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = stringResource(R.string.label_export_preset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            ExportPreset.entries.forEach { preset ->
              FilterChip(
                selected = preset == uiState.selectedExportPreset,
                onClick = { onSelectPreset(preset) },
                label = { Text(preset.displayName, style = MaterialTheme.typography.labelSmall) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                  ),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("export_preset_${preset.name}"),
              )
            }
          }

          // Technical Output Summary Console Grid
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().testTag("export_summary_grid"),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.export_summary_title),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                  text = "${cfg.videoBitrateKbps / 1_000} Mbps · ${cfg.audioBitrateKbps} kbps",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_resolution),
                  value = "${cfg.width}×${cfg.height}",
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_fps),
                  value = "${cfg.fps} fps",
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_format),
                  value = "MP4 · ${cfg.videoCodec.name}",
                  modifier = Modifier.weight(1f),
                )
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_duration),
                  value = formatTimecode(selectedRangeDurationMs),
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_size),
                  value =
                    if (exportEstimate.estimatedSizeBytes > 0L) {
                      "~${TempCacheStorageManager.formatBytes(exportEstimate.estimatedSizeBytes)}"
                    } else {
                      "—"
                    },
                  valueColor = MaterialTheme.colorScheme.primary,
                  valueTag = "export_estimated_size_text",
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = stringResource(R.string.export_spec_eta),
                  value = ExportEstimator.formatEtaSeconds(exportEstimate.estimatedRenderTimeSec),
                  valueColor = trackColors.audioTrack,
                  valueTag = "export_estimated_time_text",
                  modifier = Modifier.weight(1f),
                )
              }

              Text(
                text = "Media3 Transformer: $realClipsCount clip(s) con recorte · $activeAdjustmentsCount ajuste(s) activos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Text(
            text = stringResource(R.string.label_export_range),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            FilterChip(
              selected = rangeMode == ExportRangeSelectionMode.FULL,
              onClick = { rangeMode = ExportRangeSelectionMode.FULL },
              label = { Text(stringResource(R.string.range_full_timeline), style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("range_mode_full"),
            )
            FilterChip(
              selected = rangeMode == ExportRangeSelectionMode.BETWEEN_MARKERS,
              onClick = { rangeMode = ExportRangeSelectionMode.BETWEEN_MARKERS },
              enabled = sortedMarkers.isNotEmpty(),
              label = { Text(stringResource(R.string.range_between_markers), style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("range_mode_between_markers"),
            )
            FilterChip(
              selected = rangeMode == ExportRangeSelectionMode.CHAPTERS,
              onClick = { rangeMode = ExportRangeSelectionMode.CHAPTERS },
              enabled = sortedMarkers.isNotEmpty(),
              label = { Text(stringResource(R.string.range_marker_chapters), style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("range_mode_chapters"),
            )
          }

          if (rangeMode == ExportRangeSelectionMode.BETWEEN_MARKERS && totalDurationMs > 0L) {
            MarkerSpanSelectorRow(
              markers = sortedMarkers,
              totalDurationMs = totalDurationMs,
              startIdx = startBoundaryIdx,
              endIdx = endBoundaryIdx,
              onCycleStart = {
                val maxStart = (sortedMarkers.size - 1).coerceAtLeast(-1)
                startBoundaryIdx = if (startBoundaryIdx >= maxStart) -1 else startBoundaryIdx + 1
                if (endBoundaryIdx <= startBoundaryIdx) {
                  endBoundaryIdx = (startBoundaryIdx + 1).coerceAtMost(sortedMarkers.size)
                }
              },
              onCycleEnd = {
                val minEnd = (startBoundaryIdx + 1).coerceAtLeast(0)
                endBoundaryIdx =
                  if (endBoundaryIdx >= sortedMarkers.size) minEnd else endBoundaryIdx + 1
              },
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = {
                when (rangeMode) {
                  ExportRangeSelectionMode.FULL -> onEnqueueRange(ExportRange.FullTimeline)
                  ExportRangeSelectionMode.CHAPTERS -> onEnqueueChapters()
                  ExportRangeSelectionMode.BETWEEN_MARKERS -> {
                    val bounds =
                      RangeSliceHelper.resolveMarkerSelectionBounds(
                        sortedMarkers = sortedMarkers,
                        startBoundaryIndex = startBoundaryIdx,
                        endBoundaryIndex = endBoundaryIdx,
                        totalDurationMs = totalDurationMs,
                      )
                    if (bounds.durationMs > 0L) {
                      val startLabel =
                        if (startBoundaryIdx < 0) "Inicio" else sortedMarkers[startBoundaryIdx].label
                      val endLabel =
                        if (endBoundaryIdx >= sortedMarkers.size) {
                          "Final"
                        } else {
                          sortedMarkers[endBoundaryIdx].label
                        }
                      onEnqueueRange(
                        ExportRange.MarkerSpan(
                          startMarkerId = sortedMarkers.getOrNull(startBoundaryIdx)?.markerId,
                          endMarkerId = sortedMarkers.getOrNull(endBoundaryIdx)?.markerId,
                          startMs = bounds.startMs,
                          endMs = bounds.endMs,
                          label = "$startLabel→$endLabel",
                        ),
                      )
                    }
                  }
                }
              },
              enabled = totalDurationMs > 0L && uiState.assets.isNotEmpty(),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.tertiary,
                  contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("enqueue_export_button"),
            ) {
              Icon(imageVector = Icons.Default.MovieCreation, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text =
                  if (rangeMode == ExportRangeSelectionMode.CHAPTERS) {
                    stringResource(R.string.action_enqueue_chapters)
                  } else {
                    stringResource(R.string.action_enqueue_export)
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }

            FilledTonalButton(
              onClick = onGenerateProxies,
              enabled = uiState.proxyProgress == null && uiState.assets.isNotEmpty(),
              modifier = Modifier.minimumInteractiveComponentSize().testTag("generate_proxies_button"),
            ) {
              Icon(imageVector = Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = stringResource(R.string.action_generate_proxies), maxLines = 1)
            }
          }

          uiState.proxyProgress?.let { proxy ->
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLowest,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = "Generando proxy: ${proxy.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Text(
                    text = "${proxy.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
                LinearProgressIndicator(
                  progress = { (proxy.percent / 100f).coerceIn(0f, 1f) },
                  trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                )
              }
            }
          }

          if (uiState.lastStatusMessage != null) {
            Text(
              text = uiState.lastStatusMessage,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
        }
      }
    }

    // Cache & Storage Console Card
    item {
      val stats = uiState.cacheStorageStats
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("cache_storage_card"),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
              )
              Text(
                text = stringResource(R.string.title_cache_storage),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text = TempCacheStorageManager.formatBytes(stats.totalCacheBytes),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("cache_total_size_text"),
            )
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                ExportSpecCell(
                  label = "Proxies",
                  value = TempCacheStorageManager.formatBytes(stats.proxiesBytes),
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = "Medios temp",
                  value = TempCacheStorageManager.formatBytes(stats.tempMediaBytes),
                  modifier = Modifier.weight(1f),
                )
                ExportSpecCell(
                  label = "Staging",
                  value = TempCacheStorageManager.formatBytes(stats.exportScratchBytes),
                  modifier = Modifier.weight(1f),
                )
              }
              if (uiState.isClearingCache) {
                LinearProgressIndicator(
                  trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text =
                "${stats.savedProjectCount} proyectos guardados (" +
                  "${TempCacheStorageManager.formatBytes(stats.savedProjectsBytes)})",
              style = MaterialTheme.typography.labelSmall,
              color = trackColors.audioTrack,
            )
          }
          Text(
            text = stringResource(R.string.subtitle_cache_safe),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = onRefreshStorageStats,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_cache_stats_button"),
            ) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Calcular")
            }
            FilledTonalButton(
              onClick = onClearTemporaryCache,
              enabled = !uiState.isClearingCache,
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("clear_temp_cache_button"),
            ) {
              Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text =
                  if (uiState.isClearingCache) {
                    "Limpiando…"
                  } else {
                    stringResource(R.string.action_clear_temp_cache)
                  },
              )
            }
          }
        }
      }
    }

    // Export Queue Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_export_queue, exportQueue.size),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
        )
        if (exportQueue.any {
            it.status == ExportJobStatus.COMPLETED ||
              it.status == ExportJobStatus.CANCELLED ||
              it.status == ExportJobStatus.FAILED
          }
        ) {
          TextButton(
            onClick = onClearFinished,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_finished_exports_button"),
          ) {
            Text(stringResource(R.string.action_clear_finished))
          }
        }
      }
    }

    if (exportQueue.isEmpty()) {
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.label_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    } else {
      items(exportQueue, key = { it.jobId }) { item ->
        ExportQueueJobCard(
          item = item,
          onCancel = { onCancelJob(item.jobId) },
          onRetry = { onRetryJob(item.jobId) },
        )
      }
    }
  }
}

@Composable
private fun ExportSpecCell(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
  valueTag: String? = null,
) {
  Column(
    modifier = modifier.padding(end = 6.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = valueColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
    )
  }
}

@Composable
private fun MarkerSpanSelectorRow(
  markers: List<TimelineMarker>,
  totalDurationMs: Long,
  startIdx: Int,
  endIdx: Int,
  onCycleStart: () -> Unit,
  onCycleEnd: () -> Unit,
) {
  val startText =
    if (startIdx < 0) {
      stringResource(R.string.label_marker_origin)
    } else {
      markers.getOrNull(startIdx)?.label ?: stringResource(R.string.label_marker_origin)
    }
  val endText =
    if (endIdx >= markers.size) {
      "${stringResource(R.string.label_marker_finish)} (${totalDurationMs / 1000}s)"
    } else {
      markers.getOrNull(endIdx)?.label ?: stringResource(R.string.label_marker_finish)
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilledTonalButton(
      onClick = onCycleStart,
      modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("cycle_marker_start_button"),
    ) {
      Text(
        text = "${stringResource(R.string.label_marker_start)}: $startText",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    FilledTonalButton(
      onClick = onCycleEnd,
      modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("cycle_marker_end_button"),
    ) {
      Text(
        text = "${stringResource(R.string.label_marker_end)}: $endText",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ExportQueueJobCard(
  item: ExportQueueItem,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
) {
  val trackColors = LocalTrackColors.current
  val statusLabel =
    when (item.status) {
      ExportJobStatus.QUEUED -> stringResource(R.string.export_status_queued)
      ExportJobStatus.RUNNING -> stringResource(R.string.export_status_running, item.progressPercent)
      ExportJobStatus.COMPLETED ->
        stringResource(
          R.string.export_status_completed,
          TempCacheStorageManager.formatBytes(item.outputSizeBytes ?: 0L),
        )
      ExportJobStatus.CANCELLED -> stringResource(R.string.export_status_cancelled)
      ExportJobStatus.FAILED -> stringResource(R.string.export_status_failed)
    }

  val statusColor =
    when (item.status) {
      ExportJobStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
      ExportJobStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
      ExportJobStatus.COMPLETED -> trackColors.audioTrack
      ExportJobStatus.CANCELLED -> trackColors.markerAmber
      ExportJobStatus.FAILED -> MaterialTheme.colorScheme.error
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (item.status == ExportJobStatus.RUNNING) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.72f)
          } else {
            MaterialTheme.colorScheme.outlineVariant
          },
      ),
    modifier = Modifier.fillMaxWidth().testTag("export_job_${item.jobId}"),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${item.exportConfig.width}×${item.exportConfig.height} · ${item.exportConfig.fps}fps · ${item.range.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(statusColor),
            )
            Text(
              text = statusLabel,
              style = MaterialTheme.typography.labelMedium,
              color = statusColor,
              modifier = Modifier.testTag("export_status_${item.jobId}"),
            )
          }
        }

        when (item.status) {
          ExportJobStatus.QUEUED, ExportJobStatus.RUNNING -> {
            OutlinedButton(
              onClick = onCancel,
              modifier =
                Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("cancel_export_${item.jobId}"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_cancel_export),
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.export_action_cancel))
            }
          }
          ExportJobStatus.FAILED, ExportJobStatus.CANCELLED -> {
            TextButton(
              onClick = onRetry,
              modifier =
                Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("retry_export_${item.jobId}"),
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_retry_export),
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.export_action_retry))
            }
          }
          ExportJobStatus.COMPLETED -> {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = statusLabel,
              tint = trackColors.audioTrack,
              modifier = Modifier.padding(8.dp),
            )
          }
        }
      }

      if (item.status == ExportJobStatus.RUNNING) {
        LinearProgressIndicator(
          progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
          color = MaterialTheme.colorScheme.tertiary,
          trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
          modifier =
            Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(3.dp))
              .testTag("export_progress_${item.jobId}"),
        )
      }

      val hasDestination = !item.galleryUri.isNullOrBlank() || !item.outputFilePath.isNullOrBlank()
      if (item.status == ExportJobStatus.COMPLETED && hasDestination) {
        val displayDestination =
          if (!item.galleryUri.isNullOrBlank()) {
            "Galería (Movies/FrameLine Studio)"
          } else {
            "Exportación MP4 lista"
          }
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Default.VideoLibrary,
              contentDescription = null,
              tint = trackColors.audioTrack,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = stringResource(R.string.export_gallery_path_label, displayDestination),
              style = MaterialTheme.typography.labelSmall,
              color = trackColors.audioTrack,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.testTag("export_result_uri_${item.jobId}"),
            )
          }
        }
      }

      if (item.errorMessage != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = item.errorMessage,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("export_error_${item.jobId}"),
          )
        }
      }
    }
  }
}
