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
        horizontalArrangement
