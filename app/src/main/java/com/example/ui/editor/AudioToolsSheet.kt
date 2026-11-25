package com.example.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.ClipKind
import com.example.core.model.SilenceSegment
import com.example.core.model.TimelineClip
import com.example.core.model.Track
import com.example.core.model.TrackType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioToolsSheet(
  selectedClip: TimelineClip?,
  duckingConfig: AudioDuckingConfig,
  detectedSilences: List<SilenceSegment>,
  isAnalyzingSilence: Boolean,
  onUpdateClipAudio: (clipId: String, volume: Float, fadeInMs: Long, fadeOutMs: Long) -> Unit,
  onUpdateDucking: (AudioDuckingConfig) -> Unit,
  onAnalyzeSilences: (clipId: String) -> Unit,
  onRemoveDetectedSilences: (clipId: String) -> Unit,
  onDismiss: () -> Unit,
  tracks: List<Track> = emptyList(),
  onUpdateTrackVolume: (trackId: String, volume: Float, fromSliderDrag: Boolean) -> Unit = { _, _, _ -> },
  onToggleTrackMute: (trackId: String) -> Unit = {},
  onToggleTrackSolo: (trackId: String) -> Unit = {},
  onBeginTrackEdit: () -> Unit = {},
  onEndTrackEdit: () -> Unit = {},
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.title_audio_tools),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_audio_sheet"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_sheet))
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Gain & direct fades for selected video/audio clip
      val supportsAudio =
        selectedClip != null &&
          (selectedClip.kind == ClipKind.VIDEO || selectedClip.kind == ClipKind.AUDIO)

      if (supportsAudio && selectedClip != null) {
        val currentVol =
          when (selectedClip.kind) {
            ClipKind.VIDEO -> selectedClip.videoConfig?.volume ?: 1f
            ClipKind.AUDIO -> selectedClip.audioConfig?.volume ?: 1f
            else -> 1f
          }
        val currentFadeIn =
          when (selectedClip.kind) {
            ClipKind.VIDEO -> selectedClip.videoConfig?.fadeInMs ?: 0L
            ClipKind.AUDIO -> selectedClip.audioConfig?.fadeInMs ?: 0L
            else -> 0L
          }
        val currentFadeOut =
          when (selectedClip.kind) {
            ClipKind.VIDEO -> selectedClip.videoConfig?.fadeOutMs ?: 0L
            ClipKind.AUDIO -> selectedClip.audioConfig?.fadeOutMs ?: 0L
            else -> 0L
          }
        val maxFadeMs = (selectedClip.durationMs / 2L).coerceAtLeast(100L).toFloat()

        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              text = "${stringResource(R.string.label_clip_gain_fades)} · ${selectedClip.label}",
              style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.label_volume_pct, (currentVol * 100).roundToInt()),
              style = MaterialTheme.typography.labelMedium,
            )
            Slider(
              value = currentVol,
              onValueChange = { nextVol ->
                onUpdateClipAudio(selectedClip.clipId, nextVol, currentFadeIn, currentFadeOut)
              },
              valueRange = 0f..2f,
              modifier = Modifier.testTag("audio_volume_slider"),
            )

            Text(
              text = stringResource(R.string.label_fade_in_ms, currentFadeIn),
              style = MaterialTheme.typography.labelMedium,
            )
            Slider(
              value = currentFadeIn.toFloat().coerceIn(0f, maxFadeMs),
              onValueChange = { nextIn ->
                onUpdateClipAudio(selectedClip.clipId, currentVol, nextIn.toLong(), currentFadeOut)
              },
              valueRange = 0f..maxFadeMs,
              modifier = Modifier.testTag("audio_fade_in_slider"),
            )

            Text(
              text = stringResource(R.string.label_fade_out_ms, currentFadeOut),
              style = MaterialTheme.typography.labelMedium,
            )
            Slider(
              value = currentFadeOut.toFloat().coerceIn(0f, maxFadeMs),
              onValueChange = { nextOut ->
                onUpdateClipAudio(selectedClip.clipId, currentVol, currentFadeIn, nextOut.toLong())
              },
              valueRange = 0f..maxFadeMs,
              modifier = Modifier.testTag("audio_fade_out_slider"),
            )
          }
        }
      } else {
        Text(
          text = "Selecciona un clip de Video o Audio para ajustar ganancia, fades y silencios.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      val audioTracks = tracks.filter { it.type == TrackType.VIDEO || it.type == TrackType.AUDIO }
      if (audioTracks.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
          text = stringResource(R.string.label_track_mixer_section),
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          audioTracks.forEach { track ->
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                      selected = track.isMuted,
                      onClick = { onToggleTrackMute(track.trackId) },
                      label = { Text(stringResource(R.string.label_track_mute_short)) },
                      modifier =
                        Modifier
                          .minimumInteractiveComponentSize()
                          .testTag("track_mute_chip_${track.trackId}"),
                    )
                    FilterChip(
                      selected = track.isSolo,
                      onClick = { onToggleTrackSolo(track.trackId) },
                      label = { Text(stringResource(R.string.label_track_solo_short)) },
                      modifier =
                        Modifier
                          .minimumInteractiveComponentSize()
                          .testTag("track_solo_chip_${track.trackId}"),
                    )
                  }
                }
                Text(
                  text =
                    stringResource(
                      R.string.label_track_gain_pct,
                      (track.volume.coerceIn(0f, 2f) * 100f).roundToInt(),
                    ),
                  style = MaterialTheme.typography.labelMedium,
                  color =
                    if (track.isMuted) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Slider(
                  value = track.volume.coerceIn(0f, 2f),
                  onValueChange = { nextVol ->
                    onBeginTrackEdit()
                    onUpdateTrackVolume(track.trackId, nextVol, true)
                  },
                  onValueChangeFinished = onEndTrackEdit,
                  valueRange = 0f..2f,
                  modifier = Modifier.testTag("track_volume_slider_${track.trackId}"),
                )
              }
            }
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      // Basic Ducking toggle & reduction control
      Text(
        text = stringResource(R.string.label_ducking_section),
        style = MaterialTheme.typography.titleMedium,
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.label_ducking_enabled),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        Switch(
          checked = duckingConfig.enabled,
          onCheckedChange = { enabled -> onUpdateDucking(duckingConfig.copy(enabled = enabled)) },
          modifier = Modifier.testTag("ducking_switch"),
        )
      }
      if (duckingConfig.enabled) {
        Text(
          text = "Reducción música: ${(duckingConfig.attenuationFactor * 100).roundToInt()}%",
          style = MaterialTheme.typography.labelMedium,
        )
        Slider(
          value = duckingConfig.attenuationFactor,
          onValueChange = { factor ->
            onUpdateDucking(duckingConfig.copy(attenuationFactor = factor))
          },
          valueRange = 0.1f..0.8f,
          modifier = Modifier.testTag("ducking_amount_slider"),
        )
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      // Silence / Pause Detection
      Text(
        text = stringResource(R.string.label_silence_detection),
        style = MaterialTheme.typography.titleMedium,
      )
      Spacer(modifier = Modifier.height(6.dp))

      if (isAnalyzingSilence) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalButton(
          onClick = { selectedClip?.let { onAnalyzeSilences(it.clipId) } },
          enabled = supportsAudio && !isAnalyzingSilence,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("analyze_silences_button"),
        ) {
          Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.action_detect_silences))
        }

        Button(
          onClick = { selectedClip?.let { onRemoveDetectedSilences(it.clipId) } },
          enabled = supportsAudio && detectedSilences.isNotEmpty() && !isAnalyzingSilence,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("remove_silences_button"),
        ) {
          Icon(
            imageVector = Icons.Default.ContentCut,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.action_remove_silences))
        }
      }

      if (detectedSilences.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Pausas detectadas (${detectedSilences.size}): " +
            detectedSilences.joinToString(" · ") { "${it.startMs / 1000f}s-${it.endMs / 1000f}s" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}
