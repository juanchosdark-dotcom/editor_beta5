package com.example.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.SubtitleStylePreset
import com.example.core.model.TimelineClip
import com.example.core.subtitles.SubtitleBatchEdit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchSubtitlesSheet(
  subtitleClips: List<TimelineClip>,
  savedStyles: List<SubtitleStylePreset>,
  selectedClip: TimelineClip?,
  onApplyStyleToAll: (SubtitleStylePreset) -> Unit,
  onSaveStyleFromSelected: (name: String) -> Unit,
  onGenerateBatchFromScript: (rawScriptOrSrt: String, style: SubtitleStylePreset) -> Unit,
  onSaveBatchEdits: (List<SubtitleBatchEdit>) -> Unit,
  onDismiss: () -> Unit,
  onAutoGenerateSubtitles: (() -> Unit)? = null,
) {
  var selectedStyleId by remember(savedStyles) {
    mutableStateOf(savedStyles.firstOrNull()?.styleId ?: BuiltInSubtitleStyles.CLASSIC_CINEMA.styleId)
  }
  val activeStyle =
    savedStyles.find { it.styleId == selectedStyleId } ?: BuiltInSubtitleStyles.CLASSIC_CINEMA

  var scriptInput by remember { mutableStateOf("") }
  var customStyleName by remember { mutableStateOf("") }
  var editableRows by remember(subtitleClips) {
    mutableStateOf(
      subtitleClips.sortedBy { it.timelineStartMs }.map { clip ->
        SubtitleBatchEdit(
          clipId = clip.clipId,
          text = clip.subtitleConfig?.text ?: clip.label,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineEndMs,
          isEnabled = clip.isEnabled,
        )
      },
    )
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.title_batch_subtitles),
          style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subtitles_sheet"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_sheet))
        }
      }

      Text(
        text = stringResource(R.string.label_saved_styles),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
      ) {
        savedStyles.forEach { style ->
          FilterChip(
            selected = style.styleId == activeStyle.styleId,
            onClick = { selectedStyleId = style.styleId },
            label = { Text("${style.name} (${style.fontSizeSp.toInt()}sp)") },
            modifier = Modifier.testTag("subtitle_style_${style.styleId}"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = { onApplyStyleToAll(activeStyle) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("apply_style_all_button"),
        ) {
          Text(stringResource(R.string.action_apply_style_all))
        }

        if (selectedClip?.subtitleConfig != null) {
          OutlinedTextField(
            value = customStyleName,
            onValueChange = { customStyleName = it },
            placeholder = { Text("Nombre estilo") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          FilledTonalButton(
            onClick = {
              onSaveStyleFromSelected(customStyleName)
              customStyleName = ""
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("save_custom_style_button"),
          ) {
            Text(stringResource(R.string.action_save_custom_style))
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

      // Bulk script or SRT import
      OutlinedTextField(
        value = scriptInput,
        onValueChange = { scriptInput = it },
        label = { Text(stringResource(R.string.label_batch_script_hint)) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("batch_script_input"),
      )
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalButton(
          onClick = {
            if (scriptInput.isNotBlank()) {
              onGenerateBatchFromScript(scriptInput, activeStyle)
              scriptInput = ""
            }
          },
          enabled = scriptInput.isNotBlank(),
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("generate_batch_subtitles_button"),
        ) {
          Text(stringResource(R.string.action_import_batch_subtitles))
        }

        if (onAutoGenerateSubtitles != null) {
          FilledTonalButton(
            onClick = { onAutoGenerateSubtitles() },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("batch_auto_subtitles_button"),
          ) {
            Text(stringResource(R.string.action_auto_subtitles))
          }
        }

        Button(
          onClick = { onSaveBatchEdits(editableRows) },
          enabled = editableRows.isNotEmpty(),
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_batch_edits_button"),
        ) {
          Text("Aplicar edición (${editableRows.size})")
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      LazyColumn(
        modifier = Modifier.fillMaxWidth().height(190.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        itemsIndexed(editableRows, key = { _, item -> item.clipId }) { index, row ->
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = row.text,
                onValueChange = { nextText ->
                  editableRows =
                    editableRows.toMutableList().also { list ->
                      list[index] = row.copy(text = nextText)
                    }
                },
                label = { Text("${row.timelineStartMs / 1000f}s - ${row.timelineEndMs / 1000f}s") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              Switch(
                checked = row.isEnabled,
                onCheckedChange = { enabled ->
                  editableRows =
                    editableRows.toMutableList().also { list ->
                      list[index] = row.copy(isEnabled = enabled)
                    }
                },
              )
            }
          }
        }
      }
    }
  }
}
