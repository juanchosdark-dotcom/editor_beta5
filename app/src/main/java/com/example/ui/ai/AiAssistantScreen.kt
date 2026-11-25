package com.example.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.ai.api.EditFixCategory
import com.example.core.ai.api.EditFixStatus
import com.example.core.ai.api.LocalEditAnalysisReport
import com.example.core.ai.api.LocalEditSuggestion
import com.example.core.ai.api.LocalModelProfile
import com.example.ui.editor.TimelineSkeletonUiState
import com.example.ui.theme.LocalTrackColors
import kotlin.math.roundToInt

private data class AiToolStatus(
  val label: String,
  val color: Color,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAssistantScreen(
  uiState: TimelineSkeletonUiState,
  isOfflineSpeechSupported: Boolean,
  onRefreshResources: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit,
  onSelectProfile: (LocalModelProfile) -> Unit,
  onToggleBatterySaverAllowed: (Boolean) -> Unit,
  onAnalyzeClipEdits: () -> Unit,
  onApplySuggestion: (String) -> Unit,
  onRevertSuggestion: (String) -> Unit = {},
  onDismissSuggestion: (String) -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onAnalyzeRhythmAndSilences: (String) -> Unit,
  onCreateBeatMarkers: () -> Unit,
  onRemoveSilences: (String) -> Unit,
  onStartOfflineDictation: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val trackColors = LocalTrackColors.current
  var permissionDeniedNotice by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    onRefreshResources()
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted) {
        permissionDeniedNotice = false
        onStartOfflineDictation()
      } else {
        permissionDeniedNotice = true
      }
    }

  val settings = uiState.localAiSettings
  val resourceSnapshot = uiState.resourceSnapshot
  val deviceProfile = uiState.deviceProfile
  val selectedClip = uiState.selectedClip
  val editReport = uiState.editAnalysisReport

  val statusAvailable = AiToolStatus(stringResource(R.string.ai_status_available), trackColors.audioTrack)
  val statusProcessing = AiToolStatus(stringResource(R.string.ai_status_processing), MaterialTheme.colorScheme.secondary)
  val statusLimited = AiToolStatus(stringResource(R.string.ai_status_limited), MaterialTheme.colorScheme.error)
  val statusDisabled = AiToolStatus(stringResource(R.string.ai_status_disabled), MaterialTheme.colorScheme.onSurfaceVariant)
  val statusNeedsClip = AiToolStatus(stringResource(R.string.ai_status_needs_clip), trackColors.markerAmber)

  LazyColumn(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Master console card + Undo/Redo unified history
    item {
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
                text = stringResource(R.string.title_local_ai),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.subtitle_local_ai),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AiStatusBadge(
              status =
                when {
                  !settings.enabled -> statusDisabled
                  !resourceSnapshot.canRunInference -> statusLimited
                  else -> statusAvailable
                },
            )
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
              ) {
                Icon(
                  imageVector = Icons.Default.AutoAwesome,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.size(20.dp),
                )
                Text(
                  text = stringResource(R.string.label_local_ai_master),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Switch(
                checked = settings.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("local_ai_master_switch"),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Historial unificado (20 pasos)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${uiState.appliedAiAdjustments.size} ajustes IA guardados en proyecto",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              OutlinedButton(
                onClick = onUndo,
                enabled = uiState.canUndo,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("ai_undo_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Undo,
                  contentDescription = stringResource(R.string.cd_undo),
                  modifier = Modifier.size(16.dp),
                )
              }
              OutlinedButton(
                onClick = onRedo,
                enabled = uiState.canRedo,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("ai_redo_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Redo,
                  contentDescription = stringResource(R.string.cd_redo),
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        }
      }
    }

    if (settings.enabled) {
      // Compact DeviceProfile & thermal guard console
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.label_device_profile),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                  text = deviceProfile.summaryLabel,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.testTag("device_profile_summary"),
                )
              }
              AiStatusBadge(
                status = if (resourceSnapshot.canRunInference) statusAvailable else statusLimited,
              )
            }

            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLowest,
              shape = RoundedCornerShape(10.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                AiTelemetryCell(
                  icon = Icons.Default.Thermostat,
                  label = "Térmica",
                  value = resourceSnapshot.thermalTier.name,
                  tint =
                    if (resourceSnapshot.thermalTier.name == "NORMAL") {
                      trackColors.audioTrack
                    } else {
                      trackColors.markerAmber
                    },
                )
                AiTelemetryCell(
                  icon = Icons.Default.Memory,
                  label = "RAM libre",
                  value = "${deviceProfile.availableRamMb}/${deviceProfile.totalRamMb} MB",
                  tint = MaterialTheme.colorScheme.secondary,
                )
                AiTelemetryCell(
                  icon = Icons.Default.BatteryChargingFull,
                  label = "Batería",
                  value = "${resourceSnapshot.batteryPercent}%",
                  tint =
                    if (resourceSnapshot.batteryPercent > 20) {
                      trackColors.audioTrack
                    } else {
                      MaterialTheme.colorScheme.error
                    },
                )
              }
            }

            if (!resourceSnapshot.canRunInference && resourceSnapshot.blockReason != null) {
              Text(
                text = resourceSnapshot.blockReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
              )
            }

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              LocalModelProfile.entries.forEach { profile ->
                FilterChip(
                  selected = settings.profile == profile,
                  onClick = { onSelectProfile(profile) },
                  label = { Text(profile.label, style = MaterialTheme.typography.labelSmall) },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                      selectedLabelColor = MaterialTheme.colorScheme.tertiary,
                    ),
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("ai_profile_${profile.name}"),
                )
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.label_allow_battery_saver),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
              Switch(
                checked = settings.allowOnBatterySaver,
                onCheckedChange = onToggleBatterySaverAllowed,
                modifier = Modifier.testTag("ai_battery_saver_switch"),
              )
            }
          }
        }
      }

      // Tool 1: Local Edit Assistant
      item {
        val editToolStatus =
          when {
            uiState.isAnalyzingEdits -> statusProcessing
            !resourceSnapshot.canRunInference -> statusLimited
            uiState.timeline.clips.isEmpty() -> statusNeedsClip
            else -> statusAvailable
          }
        LocalEditAssistantCard(
          selectedClipLabel = selectedClip?.label,
          hasAnyClip = uiState.timeline.clips.isNotEmpty(),
          canRunInference = resourceSnapshot.canRunInference,
          isAnalyzing = uiState.isAnalyzingEdits,
          toolStatus = editToolStatus,
          report = editReport,
          onAnalyze = onAnalyzeClipEdits,
        )
      }

      if (editReport != null && editReport.suggestions.isNotEmpty()) {
        items(editReport.suggestions, key = { it.suggestionId }) { suggestion ->
          EditSuggestionCard(
            suggestion = suggestion,
            onApply = { onApplySuggestion(suggestion.suggestionId) },
            onRevert = { onRevertSuggestion(suggestion.suggestionId) },
            onDismiss = { onDismissSuggestion(suggestion.suggestionId) },
          )
        }
      }

      // Tool 2: Rhythm & Silences
      item {
        val rhythmStatus =
          when {
            uiState.isAnalyzingRhythm -> statusProcessing
            !resourceSnapshot.canRunInference -> statusLimited
            selectedClip == null -> statusNeedsClip
            else -> statusAvailable
          }
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiToolCardHeader(
              icon = Icons.Default.GraphicEq,
              accent = MaterialTheme.colorScheme.secondary,
              title = stringResource(R.string.ai_tool_rhythm_title),
              subtitle =
                selectedClip?.let { "Clip activo: ${it.label}" }
                  ?: stringResource(R.string.ai_tool_rhythm_desc),
              status = rhythmStatus,
            )

            if (uiState.isAnalyzingRhythm) {
              AiToolProgressStrip(label = "Analizando envolvente RMS y picos de ritmo…")
            }

            Button(
              onClick = { selectedClip?.let { onAnalyzeRhythmAndSilences(it.clipId) } },
              enabled = selectedClip != null && resourceSnapshot.canRunInference && !uiState.isAnalyzingRhythm,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("ai_analyze_rhythm_button"),
            ) {
              Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_analyze_rhythm_silence))
            }

            if (uiState.beatCandidates.isNotEmpty() || uiState.detectedSilences.isNotEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (uiState.beatCandidates.isNotEmpty()) {
                  FilledTonalButton(
                    onClick = onCreateBeatMarkers,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("apply_beat_markers_button"),
                  ) {
                    Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = stringResource(R.string.action_apply_beat_markers, uiState.beatCandidates.size),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                if (uiState.detectedSilences.isNotEmpty() && selectedClip != null) {
                  FilledTonalButton(
                    onClick = { onRemoveSilences(selectedClip.clipId) },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                  ) {
                    Text(
                      text = "Cortar ${uiState.detectedSilences.size} pausas",
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Tool 3: Offline Subtitle Dictation
      item {
        val dictationStatus =
          when {
            uiState.isDictatingSubtitle -> statusProcessing
            !resourceSnapshot.canRunInference -> statusLimited
            isOfflineSpeechSupported -> statusAvailable
            else -> AiToolStatus("Estándar", trackColors.markerAmber)
          }
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiToolCardHeader(
              icon = Icons.Default.Mic,
              accent = trackColors.subtitleTrack,
              title = stringResource(R.string.ai_tool_dictation_title),
              subtitle = stringResource(R.string.ai_tool_dictation_desc),
              status = dictationStatus,
            )

            if (uiState.isDictatingSubtitle) {
              AiToolProgressStrip(
                label =
                  if (isOfflineSpeechSupported) {
                    "Escuchando reconocimiento offline en el dispositivo…"
                  } else {
                    "Escuchando entrada de voz…"
                  },
              )
            }

            FilledTonalButton(
              onClick = {
                val hasMic =
                  ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                  permissionDeniedNotice = false
                  onStartOfflineDictation()
                } else {
                  micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
              },
              enabled = resourceSnapshot.canRunInference && !uiState.isDictatingSubtitle,
              modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dictate_subtitle_button"),
            ) {
              Icon(imageVector = Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text =
                  if (uiState.isDictatingSubtitle) {
                    "Escuchando (offline=$isOfflineSpeechSupported)…"
                  } else {
                    stringResource(R.string.action_dictate_subtitle)
                  },
              )
            }

            if (!uiState.partialTranscript.isNullOrBlank()) {
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Transcripción: ${uiState.partialTranscript}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.padding(10.dp),
                )
              }
            }
            if (permissionDeniedNotice) {
              Text(
                text = stringResource(R.string.msg_mic_permission_needed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            if (uiState.lastStatusMessage != null) {
              Text(
                text = uiState.lastStatusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AiToolCardHeader(
  icon: ImageVector,
  accent: Color,
  title: String,
  subtitle: String,
  status: AiToolStatus,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.weight(1f),
    ) {
      Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.padding(7.dp).size(18.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(modifier = Modifier.width(8.dp))
    AiStatusBadge(status = status)
  }
}

@Composable
private fun AiStatusBadge(status: AiToolStatus) {
  Surface(
    color = status.color.copy(alpha = 0.14f),
    shape = RoundedCornerShape(6.dp),
    border = BorderStroke(1.dp, status.color.copy(alpha = 0.45f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(status.color),
      )
      Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = status.color,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun AiToolProgressStrip(label: String) {
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
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
      )
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      )
    }
  }
}

@Composable
private fun LocalEditAssistantCard(
  selectedClipLabel: String?,
  hasAnyClip: Boolean,
  canRunInference: Boolean,
  isAnalyzing: Boolean,
  toolStatus: AiToolStatus,
  report: LocalEditAnalysisReport?,
  onAnalyze: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AiToolCardHeader(
        icon = Icons.Default.AutoFixHigh,
        accent = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.title_local_edit_assistant),
        subtitle =
          selectedClipLabel?.let { "Objetivo: $it" }
            ?: stringResource(R.string.ai_tool_edit_desc),
        status = toolStatus,
      )

      if (isAnalyzing) {
        AiToolProgressStrip(label = "Muestreando fotogramas y métricas del clip…")
      }

      Button(
        onClick = onAnalyze,
        enabled = hasAnyClip && canRunInference && !isAnalyzing,
        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("analyze_clip_edits_button"),
      ) {
        Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.action_analyze_clip_edits))
      }

      if (report != null) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = report.memoryGuardNote,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
            )
            val pm = report.projectMetrics
            Text(
              text = "Proyecto: ${pm.totalClips} clips · ${pm.markerCount} marcadores · Keyframes: ${pm.selectedClipKeyframeCount}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            report.clipMetrics?.let { cm ->
              Text(
                text = "Frames (${cm.sampledFrames}): Luma ${(cm.averageLuma * 100).roundToInt()}% · Contraste ${(cm.contrastSpread * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EditSuggestionCard(
  suggestion: LocalEditSuggestion,
  onApply: () -> Unit,
  onRevert: () -> Unit,
  onDismiss: () -> Unit,
) {
  val trackColors = LocalTrackColors.current
  val accent: Color =
    when (suggestion.category) {
      EditFixCategory.COLOR_LIGHT -> trackColors.adjustmentTrack
      EditFixCategory.CUT_MARKERS -> trackColors.playhead
      EditFixCategory.KEYFRAME_MOTION -> trackColors.videoTrack
      EditFixCategory.AUDIO_FADE -> trackColors.audioTrack
    }
  val icon: ImageVector =
    when (suggestion.category) {
      EditFixCategory.COLOR_LIGHT -> Icons.Default.Palette
      EditFixCategory.CUT_MARKERS -> Icons.Default.ContentCut
      EditFixCategory.KEYFRAME_MOTION -> Icons.Default.Tune
      EditFixCategory.AUDIO_FADE -> Icons.AutoMirrored.Filled.VolumeUp
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(14.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          when (suggestion.status) {
            EditFixStatus.PENDING -> accent.copy(alpha = 0.55f)
            EditFixStatus.APPLIED -> trackColors.audioTrack.copy(alpha = 0.7f)
            EditFixStatus.DISMISSED -> MaterialTheme.colorScheme.outlineVariant
          },
      ),
    modifier = Modifier.fillMaxWidth().testTag("suggestion_card_${suggestion.suggestionId}"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
          Text(
            text = suggestion.category.label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
          )
        }
        when (suggestion.status) {
          EditFixStatus.APPLIED ->
            AiStatusBadge(
              AiToolStatus(stringResource(R.string.status_fix_applied), trackColors.audioTrack),
            )
          EditFixStatus.DISMISSED ->
            AiStatusBadge(
              AiToolStatus(stringResource(R.string.status_fix_dismissed), MaterialTheme.colorScheme.onSurfaceVariant),
            )
          EditFixStatus.PENDING -> Unit
        }
      }

      Text(
        text = suggestion.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = suggestion.rationale,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = suggestion.metricsEvidence,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
      }

      if (suggestion.status == EditFixStatus.PENDING) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(
            onClick = onApply,
            modifier =
              Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("apply_fix_${suggestion.suggestionId}"),
          ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_apply_fix))
          }
          OutlinedButton(
            onClick = onDismiss,
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("dismiss_fix_${suggestion.suggestionId}"),
          ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_dismiss_fix))
          }
        }
      } else if (suggestion.status == EditFixStatus.APPLIED) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = null,
              tint = trackColors.audioTrack,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "Aplicado en la línea de tiempo.",
              style = MaterialTheme.typography.labelSmall,
              color = trackColors.audioTrack,
            )
          }
          OutlinedButton(
            onClick = onRevert,
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("revert_fix_${suggestion.suggestionId}"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Undo,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_revert_fix))
          }
        }
      }
    }
  }
}

@Composable
private fun AiTelemetryCell(
  icon: ImageVector,
  label: String,
  value: String,
  tint: Color,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(13.dp),
        tint = tint,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
