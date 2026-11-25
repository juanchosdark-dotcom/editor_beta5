package com.example.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.media.audio.VoiceoverRecorderManager
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.LutPreset
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import com.example.core.model.OverlayCatalog
import com.example.core.model.StickerLogoPreset
import com.example.core.model.Track
import kotlinx.coroutines.delay
import java.io.File

/**
- Playhead-anchored quick insertion sheet launched from timeline `+` buttons (track header or
- end of clips) and from the quick dock (`+ Audio`, `+ Overlay`, `+ Efectos`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TimelineInsertSheet(
  request: TimelineInsertRequest,
  targetTrack: Track?,
  assets: List<MediaAsset>,
  savedLutPresets: List<LutPreset>,
  mediaDirectory: File,
  onImportAudioUri: (android.net.Uri) -> Unit,
  onExtractAudioFromVideoAsset: (assetId: String) -> Unit,
  onExtractAudioFromVideoUri: (android.net.Uri) -> Unit,
  onInsertRecordedVoiceover: (File) -> Unit,
  onImportOverlayImageUri: (android.net.Uri) -> Unit,
  onInsertStickerOrLogo: (StickerLogoPreset) -> Unit,
  onImportPipVideoUri: (android.net.Uri) -> Unit,
  onInsertAssetAsPip: (assetId: String) -> Unit,
  onInsertAdjustmentEffect: (label: String, config: AdjustmentClipConfig, durationMs: Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val recorderManager = remember(context) { VoiceoverRecorderManager(context) }
  var isRecording by remember { mutableStateOf(false) }
  var recordingElapsedMs by remember { mutableLongStateOf(0L) }
  var recorderError by remember { mutableStateOf<String?>(null) }

  DisposableEffect(recorderManager) {
    onDispose {
      if (recorderManager.isRecording) {
        recorderManager.cancelRecording()
      }
    }
  }

  LaunchedEffect(isRecording) {
    while (isRecording) {
      recordingElapsedMs = recorderManager.elapsedDurationMs()
      delay(120L)
    }
  }

  val openAudioDocLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onImportAudioUri(uri)
      }
    }

  val pickVideoForAudioLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onExtractAudioFromVideoUri(uri)
      }
    }

  val pickOverlayImageLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onImportOverlayImageUri(uri)
      }
    }

  val pickPipVideoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onImportPipVideoUri(uri)
      }
    }

  val startMicRecording: () -> Unit = {
    recorderError = null
    recorderManager.startRecording(mediaDirectory)
      .onSuccess {
        isRecording = true
        recordingElapsedMs = 0L
      }
      .onFailure { err ->
        isRecording = false
        recorderError = err.message ?: context.getString(R.string.insert_audio_mic_denied)
      }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        startMicRecording()
      } else {
        recorderError = context.getString(R.string.insert_audio_mic_denied)
      }
    }

  val timeLabel = formatTimecode(request.anchorMs)
  val sheetTitle =
    when (request.kind) {
      TimelineInsertKind.AUDIO -> stringResource(R.string.insert_sheet_title_audio, timeLabel)
      TimelineInsertKind.OVERLAY -> stringResource(R.string.insert_sheet_title_overlay, timeLabel)
      TimelineInsertKind.EFFECTS -> stringResource(R.string.insert_sheet_title_effects, timeLabel)
      TimelineInsertKind.TEXT -> "Texto / Subtítulos ($timeLabel)"
    }
  val headerIcon =
    when (request.kind) {
      TimelineInsertKind.AUDIO -> Icons.Default.LibraryMusic
      TimelineInsertKind.OVERLAY -> Icons.Default.Layers
      TimelineInsertKind.EFFECTS -> Icons.Default.AutoFixHigh
      TimelineInsertKind.TEXT -> Icons.Default.TextFields
    }
  val accentColor =
    when (request.kind) {
      TimelineInsertKind.AUDIO -> MaterialTheme.colorScheme.secondary
      TimelineInsertKind.OVERLAY -> MaterialTheme.colorScheme.primary
      TimelineInsertKind.EFFECTS -> MaterialTheme.colorScheme.tertiary
      TimelineInsertKind.TEXT -> MaterialTheme.colorScheme.primary
    }

  ModalBottomSheet(
    onDismissRequest = {
      if (isRecording) {
        recorderManager.cancelRecording()
        isRecording = false
      }
      onDismiss()
    },
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .padding(bottom = 20.dp)
          .testTag("timeline_insert_sheet"),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            color = accentColor.copy(alpha = 0.16f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.65f)),
          ) {
            Icon(
              imageVector = headerIcon,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.padding(8.dp).size(20.dp),
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = sheetTitle,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                if (targetTrack != null) {
                  stringResource(R.string.insert_sheet_target_track, targetTrack.name)
                } else {
                  stringResource(R.string.insert_sheet_auto_track)
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        IconButton(
          onClick = {
            if (isRecording) {
              recorderManager.cancelRecording()
              isRecording = false
            }
            onDismiss()
          },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_insert_sheet_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_subpanel),
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      when (request.kind) {
        TimelineInsertKind.AUDIO -> {
          InsertOptionCard(
            icon = Icons.Default.AudioFile,
            title = stringResource(R.string.insert_audio_import_device),
            subtitle = stringResource(R.string.insert_audio_import_device_desc),
            accent = MaterialTheme.colorScheme.secondary,
            onClick = { openAudioDocLauncher.launch(arrayOf("audio/*")) },
            tag = "audio_import_device_button",
          )

          InsertOptionCard(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.insert_audio_extract_video_picker),
            subtitle = stringResource(R.string.insert_audio_extract_project_section),
            accent = MaterialTheme.colorScheme.primary,
            onClick = {
              pickVideoForAudioLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
              )
            },
            tag = "audio_extract_video_picker_button",
          )

          val projectVideos = assets.filter { it.type == MediaType.VIDEO }
          if (projectVideos.isNotEmpty()) {
            Text(
              text = stringResource(R.string.insert_audio_extract_project_section),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              projectVideos.forEach { videoAsset ->
                AssistChip(
                  onClick = { onExtractAudioFromVideoAsset(videoAsset.assetId) },
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.Movie,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = { Text(videoAsset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  modifier =
                    Modifier
                      .minimumInteractiveComponentSize()
                      .testTag("audio_extract_asset_${videoAsset.assetId}"),
                )
              }
            }
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            border =
              BorderStroke(
                width = 1.dp,
                color =
                  if (isRecording) {
                    MaterialTheme.colorScheme.tertiary
                  } else {
                    MaterialTheme.colorScheme.outlineVariant
                  },
              ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (isRecording) {
                  Box(
                    modifier =
                      Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                  )
                } else {
                  Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                  )
                }
                Text(
                  text =
                    if (isRecording) {
                      stringResource(R.string.insert_audio_voiceover_recording, formatTimecode(recordingElapsedMs))
                    } else {
                      stringResource(R.string.insert_audio_voiceover_title)
                    },
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                )
              }

              if (recorderError != null) {
                Text(
                  text = recorderError!!,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (!isRecording) {
                  Button(
                    onClick = {
                      val hasMicPermission =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                          PackageManager.PERMISSION_GRANTED
                      if (hasMicPermission) {
                        startMicRecording()
                      } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                      }
                    },
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                      ),
                    modifier =
                      Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("audio_record_voiceover_button"),
                  ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.insert_audio_voiceover_start))
                  }
                } else {
                  Button(
                    onClick = {
                      recorderManager.stopRecording()
                        .onSuccess { outFile ->
                          isRecording = false
                          onInsertRecordedVoiceover(outFile)
                        }
                        .onFailure { err ->
                          isRecording = false
                          recorderError = err.message
                        }
                    },
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                      ),
                    modifier =
                      Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("audio_stop_voiceover_button"),
                  ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.insert_audio_voiceover_stop))
                  }
                  OutlinedButton(
                    onClick = {
                      recorderManager.cancelRecording()
                      isRecording = false
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                  ) {
                    Text(stringResource(R.string.insert_audio_voiceover_cancel))
                  }
                }
              }
            }
          }
        }

        TimelineInsertKind.OVERLAY -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            InsertCompactTile(
              icon = Icons.Default.Image,
              title = stringResource(R.string.insert_overlay_import_image),
              subtitle = stringResource(R.string.insert_overlay_import_image_desc),
              accent = MaterialTheme.colorScheme.primary,
              onClick = {
                pickOverlayImageLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
              },
              tag = "overlay_import_image_button",
              modifier = Modifier.weight(1f),
            )
            InsertCompactTile(
              icon = Icons.Default.PictureInPictureAlt,
              title = stringResource(R.string.insert_overlay_import_pip),
              subtitle = stringResource(R.string.insert_overlay_import_pip_desc),
              accent = MaterialTheme.colorScheme.tertiary,
              onClick = {
                pickPipVideoLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
              },
              tag = "overlay_import_pip_button",
              modifier = Modifier.weight(1f),
            )
          }

          val projectMedia = assets.filter { it.type == MediaType.VIDEO || it.type == MediaType.IMAGE }
          if (projectMedia.isNotEmpty()) {
            Text(
              text = stringResource(R.string.insert_overlay_project_pip_section),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              projectMedia.forEach { mediaAsset ->
                AssistChip(
                  onClick = { onInsertAssetAsPip(mediaAsset.assetId) },
                  leadingIcon = {
                    Icon(
                      imageVector =
                        if (mediaAsset.type == MediaType.IMAGE) Icons.Default.Image else Icons.Default.PictureInPictureAlt,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = { Text(mediaAsset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  modifier =
                    Modifier
                      .minimumInteractiveComponentSize()
                      .testTag("overlay_pip_asset_${mediaAsset.assetId}"),
                )
              }
            }
          }

          Text(
            text = stringResource(R.string.insert_overlay_stickers_section),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            OverlayCatalog.stickerPresets.forEach { preset ->
              val primaryColor = Color(preset.primaryColorArgb)
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.65f)),
                modifier =
                  Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onInsertStickerOrLogo(preset) }
                    .testTag("overlay_sticker_${preset.id}"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(color = primaryColor, center = Offset(size.width / 2f, size.height / 2f))
                  }
                  Column {
                    Text(
                      text = preset.title,
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = if (preset.isLogo) "LOGO" else "STICKER",
                      style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                      color = primaryColor,
                    )
                  }
                }
              }
            }
          }
        }

        TimelineInsertKind.EFFECTS -> {
          Text(
            text = stringResource(R.string.insert_effects_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = stringResource(R.string.insert_effects_presets_section),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
          )
          val presets = remember(savedLutPresets) { OverlayCatalog.adjustmentPresets(savedLutPresets) }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .
