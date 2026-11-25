package com.example.ui.mediapicker

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.media.playback.ThumbnailLoadResult
import com.example.core.model.MediaAsset
import com.example.core.model.RelinkStatus
import com.example.ui.editor.MediaValidationReport
import com.example.ui.editor.TimelineSkeletonUiState
import com.example.ui.editor.formatTimecode
import com.example.ui.theme.LocalTrackColors

private enum class MediaFilterOption {
  ALL,
  READY,
  WITH_PROXY,
  REVIEW_OR_HDR,
}

@Composable
fun MediaPickerScreen(
  uiState: TimelineSkeletonUiState,
  validateAsset: (MediaAsset) -> MediaValidationReport,
  onImportVideoUri: (android.net.Uri) -> Unit,
  onSendAssetToEditor: (String) -> Unit,
  onOpenEditor: () -> Unit,
  onSendAssetsToEditor: (List<String>) -> Unit = { ids -> ids.forEach(onSendAssetToEditor) },
  onLoadThumbnail: (suspend (String) -> ThumbnailLoadResult?)? = null,
  modifier: Modifier = Modifier,
) {
  var pickerErrorMessage by remember { mutableStateOf<String?>(null) }
  var activeFilter by remember { mutableStateOf(MediaFilterOption.ALL) }
  var selectedAssetIds by remember { mutableStateOf<Set<String>>(emptySet()) }

  val fallbackOpenDocumentLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
      if (uri != null) {
        pickerErrorMessage = null
        onImportVideoUri(uri)
      }
    }
  val photoPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
      if (uri != null) {
        pickerErrorMessage = null
        onImportVideoUri(uri)
      }
    }
  val unavailableMessage = stringResource(R.string.msg_picker_unavailable)

  val assetReports =
    remember(uiState.assets) {
      uiState.assets.map { asset -> asset to validateAsset(asset) }
    }

  val filteredAssets =
    remember(assetReports, activeFilter) {
      when (activeFilter) {
        MediaFilterOption.ALL -> assetReports
        MediaFilterOption.READY -> assetReports.filter { (_, report) -> report.isReadyForEditor }
        MediaFilterOption.WITH_PROXY ->
          assetReports.filter { (asset, _) ->
            !asset.proxyRelativePath.isNullOrBlank() || asset.relinkState.status == RelinkStatus.PROXY_ONLY
          }
        MediaFilterOption.REVIEW_OR_HDR ->
          assetReports.filter { (asset, report) -> !report.isReadyForEditor || asset.fingerprint.isHdr }
      }
    }

  val readyAssetIdsInView =
    remember(filteredAssets) {
      filteredAssets.filter { (_, report) -> report.isReadyForEditor }.map { (asset, _) -> asset.assetId }.toSet()
    }
  val validSelectedIds =
    remember(selectedAssetIds, assetReports) {
      val availableIds = assetReports.filter { (_, r) -> r.isReadyForEditor }.map { it.first.assetId }.toSet()
      selectedAssetIds.intersect(availableIds)
    }

  val proxyCount =
    remember(uiState.assets) {
      uiState.assets.count { !it.proxyRelativePath.isNullOrBlank() || it.relinkState.status == RelinkStatus.PROXY_ONLY }
    }

  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(16.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item(span = { GridItemSpan(maxLineSpan) }) {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
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
                text = stringResource(R.string.media_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = stringResource(R.string.media_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "${uiState.assets.size} clips · $proxyCount con proxy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
              )
              Text(
                text = if (uiState.renderSettings.useProxiesForPreview) "Preview: PROXY ON" else "Preview: ORIG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = {
                pickerErrorMessage = null
                runCatching {
                  photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                  )
                }.onFailure {
                  runCatching {
                    fallbackOpenDocumentLauncher.launch(arrayOf("video/*"))
                  }.onFailure {
                    pickerErrorMessage = unavailableMessage
                  }
                }
              },
              enabled = !uiState.isImportingMedia,
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("mediapicker_import_button"),
            ) {
              Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = stringResource(R.string.cd_import_video),
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_import_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (uiState.assets.isNotEmpty()) {
              FilledTonalButton(
                onClick = onOpenEditor,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("mediapicker_go_editor_button"),
              ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.nav_editor), maxLines = 1)
              }
            }
          }

          if (uiState.isImportingMedia) {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
              trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Text(
              text = "Importando video y verificando compatibilidad…",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }

          val visibleStatus = pickerErrorMessage ?: uiState.lastStatusMessage
          if (visibleStatus != null) {
            Text(
              text = visibleStatus,
              style = MaterialTheme.typography.labelSmall,
              color =
                if (pickerErrorMessage != null) {
                  MaterialTheme.colorScheme.error
                } else {
                  MaterialTheme.colorScheme.secondary
                },
              modifier = Modifier.testTag("mediapicker_status_message"),
            )
          }
        }
      }
    }

    if (uiState.assets.isNotEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        val filters =
          listOf(
            MediaFilterOption.ALL to "${stringResource(R.string.media_filter_all)} (${assetReports.size})",
            MediaFilterOption.READY to
              "${stringResource(R.string.media_filter_ready)} (${assetReports.count { it.second.isReadyForEditor }})",
            MediaFilterOption.WITH_PROXY to
              "${stringResource(R.string.media_filter_proxy)} ($proxyCount)",
            MediaFilterOption.REVIEW_OR_HDR to
              "${stringResource(R.string.media_filter_review)} (${assetReports.count { !it.second.isReadyForEditor || it.first.fingerprint.isHdr }})",
          )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            items(filters) { (option, label) ->
              val selected = activeFilter == option
              FilterChip(
                selected = selected,
                onClick = { activeFilter = option },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.secondary,
                  ),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("mediapicker_filter_${option.name}"),
              )
            }
          }

          Surface(
            color =
              if (validSelectedIds.isNotEmpty()) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
              } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
              },
            shape = RoundedCornerShape(12.dp),
            border =
              BorderStroke(
                1.dp,
                if (validSelectedIds.isNotEmpty()) {
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                  MaterialTheme.colorScheme.outlineVariant
                },
              ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text =
                  if (validSelectedIds.isNotEmpty()) {
                    stringResource(R.string.media_selected_count, validSelectedIds.size)
                  } else {
                    "Toca tarjetas para selección múltiple"
                  },
                style = MaterialTheme.typography.labelMedium,
                color =
                  if (validSelectedIds.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
                modifier = Modifier.weight(1f),
              )
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                if (validSelectedIds.isNotEmpty()) {
                  TextButton(
                    onClick = { selectedAssetIds = emptySet() },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("mediapicker_clear_selection_button"),
                  ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(stringResource(R.string.media_clear_selection))
                  }
                  Button(
                    onClick = {
                      onSendAssetsToEditor(validSelectedIds.toList())
                      selectedAssetIds = emptySet()
                      onOpenEditor()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("mediapicker_batch_add_button"),
                  ) {
                    Text(stringResource(R.string.media_add_selected_to_editor, validSelectedIds.size))
                  }
                } else if (readyAssetIdsInView.isNotEmpty()) {
                  OutlinedButton(
                    onClick = { selectedAssetIds = readyAssetIdsInView },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("mediapicker_select_all_button"),
                  ) {
                    Text(stringResource(R.string.media_select_all))
                  }
                }
              }
            }
          }
        }
      }
    }

    if (uiState.assets.isEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Default.VideoFile,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(36.dp),
            )
            Text(
              text = stringResource(R.string.media_picker_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else if (filteredAssets.isEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.media_filter_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(18.dp),
          )
        }
      }
    } else {
      items(filteredAssets, key = { (asset, _) -> asset.assetId }) { (asset, report) ->
        val isSelected = validSelectedIds.contains(asset.assetId)
        ValidatedAssetGridCard(
          asset = asset,
          report = report,
          isSelected = isSelected,
          onLoadThumbnail = onLoadThumbnail,
          onToggleSelection = {
            if (report.isReadyForEditor) {
              selectedAssetIds =
                if (isSelected) {
                  selectedAssetIds - asset.assetId
                } else {
                  selectedAssetIds + asset.assetId
                }
            }
          },
          onSendToEditor = {
            onSendAssetToEditor(asset.assetId)
            onOpenEditor()
          },
        )
      }
    }
  }
}

@Composable
private fun ValidatedAssetGridCard(
  asset: MediaAsset,
  report: MediaValidationReport,
  isSelected: Boolean,
  onLoadThumbnail: (suspend (String) -> ThumbnailLoadResult?)?,
  onToggleSelection: () -> Unit,
  onSendToEditor: () -> Unit,
) {
  val trackColors = LocalTrackColors.current
  val sizeMb = asset.fingerprint.byteSize / (1024f * 1024f)
  val hasProxy = !asset.proxyRelativePath.isNullOrBlank() || asset.relinkState.status == RelinkStatus.PROXY_ONLY

  val thumbnailBitmap by
    produceState<Bitmap?>(initialValue = null, key1 = asset.assetId, key2 = onLoadThumbnail) {
      value = (onLoadThumbnail?.invoke(asset.assetId) as? ThumbnailLoadResult.Ready)?.bitmap
    }

  val borderColor =
    when {
      isSelected -> MaterialTheme.colorScheme.primary
      !report.isReadyForEditor -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
      else -> MaterialTheme.colorScheme.outlineVariant
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(width = if (isSelected) 1.5.dp else 1.dp, color = borderColor),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(enabled = report.isReadyForEditor, onClick = onToggleSelection)
        .testTag("validated_asset_${asset.assetId}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
      ) {
        if (thumbnailBitmap != null) {
          Image(
            bitmap = thumbnailBitmap!!.asImageBitmap(),
            contentDescription = asset.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(
                  Brush.linearGradient(
                    listOf(
                      MaterialTheme.colorScheme.surfaceContainerLowest,
                      MaterialTheme.colorScheme.surfaceVariant,
                    ),
                  ),
                ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = if (report.isReadyForEditor) Icons.Default.VideoFile else Icons.Default.Warning,
              contentDescription = null,
              tint =
                if (report.isReadyForEditor) {
                  MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)
                } else {
                  MaterialTheme.colorScheme.error
                },
              modifier = Modifier.size(30.dp),
            )
          }
        }

        // Top-left resolution / HDR pill
        val shortRes =
          asset.fingerprint.orientedResolution().let { (w, h) ->
            val minDim = minOf(w ?: 0, h ?: 0)
            when {
              minDim >= 2160 -> "4K"
              minDim >= 1080 -> "1080p"
              minDim >= 720 -> "720p"
              minDim > 0 -> "${minDim}p"
              else -> report.resolutionLabel
            }
          }
        Row(
          modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          SlateBadge(
            text = if (asset.fingerprint.isHdr) "$shortRes HDR" else shortRes,
            contentColor =
              if (asset.fingerprint.isHdr) {
                trackColors.markerAmber
              } else {
                MaterialTheme.colorScheme.onSurface
              },
          )
        }

        // Top-right selection toggle indicator
        Box(
          modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
          Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
          ) {
            Icon(
              imageVector =
                if (isSelected) {
                  Icons.Default.CheckCircle
                } else {
                  Icons.Default.RadioButtonUnchecked
                },
              contentDescription = if (isSelected) "Seleccionado" else "Seleccionar clip",
              tint =
                if (isSelected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              modifier = Modifier.padding(3.dp).size(18.dp),
            )
          }
        }

        // Bottom-left proxy / readiness badge
        Box(
          modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        ) {
          val badgeLabel =
            when {
              !report.isReadyForEditor -> stringResource(R.string.media_badge_review)
              hasProxy -> stringResource(R.string.media_badge_proxy)
              else -> stringResource(R.string.media_badge_ready)
            }
          val badgeTint =
            when {
              !report.isReadyForEditor -> MaterialTheme.colorScheme.error
              hasProxy -> MaterialTheme.colorScheme.secondary
              else -> trackColors.audioTrack
            }
          SlateBadge(text = badgeLabel, contentColor = badgeTint)
        }

        // Bottom-right duration badge
        Box(
          modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        ) {
          SlateBadge(
            text = formatTimecode(asset.fingerprint.durationMs),
            contentColor = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      Column(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = asset.displayName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "${report.resolutionLabel} · ${"%.1f".format(sizeMb)} MB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Icon(
            imageVector =
              if (report.hasAudioStream) {
                Icons.AutoMirrored.Filled.VolumeUp
              } else {
                Icons.AutoMirrored.Filled.VolumeOff
              },
            contentDescription = if (report.hasAudioStream) "Con audio" else "Sin audio",
            tint =
              if (report.hasAudioStream) {
                trackColors.audioTrack
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.size(14.dp),
          )
        }

        if (report.warnings.isNotEmpty()) {
          Text(
            text = report.warnings.first(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }

        FilledTonalButton(
          onClick = onSendToEditor,
          enabled = report.isReadyForEditor,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        ) {
          Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.media_quick_add),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun SlateBadge(
  text: String,
  contentColor: Color,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.84f),
    shape = RoundedCornerShape(5.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = contentColor,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
    )
  }
}
