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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.timeline.AttributeCategory
import com.example.core.timeline.CopiedClipAttributes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AttributesAndSnapshotsSheet(
  selectedClip: TimelineClip?,
  availableCategories: Set<AttributeCategory>,
  clipboard: CopiedClipAttributes?,
  snapshots: List<TimelineSnapshot>,
  sequences: List<TimelineSequence>,
  activeSequenceId: String,
  onCopyAttributes: (Set<AttributeCategory>) -> Unit,
  onPasteToCompatibleClips: (Set<AttributeCategory>) -> Unit,
  onCreateSnapshot: () -> Unit,
  onRestoreSnapshot: (String) -> Unit,
  onDuplicateSequence: () -> Unit,
  onSwitchSequence: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var chosenCategories by remember(selectedClip?.clipId, availableCategories) {
    mutableStateOf(availableCategories)
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
          text = stringResource(R.string.title_attributes_snapshots),
          style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_attrs_sheet"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_sheet))
        }
      }

      // 1. Selective Attribute Copy / Paste
      Text(
        text = stringResource(R.string.label_copy_attributes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (selectedClip != null) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
          availableCategories.forEach { category ->
            val selected = category in chosenCategories
            FilterChip(
              selected = selected,
              onClick = {
                chosenCategories =
                  if (selected) chosenCategories - category else chosenCategories + category
              },
              label = { Text(labelForCategory(category)) },
              modifier = Modifier.testTag("attr_chip_${category.name}"),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalButton(
          onClick = { onCopyAttributes(chosenCategories) },
          enabled = selectedClip != null && chosenCategories.isNotEmpty(),
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("copy_attrs_button"),
        ) {
          Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
          Spacer(modifier = Modifier.padding(horizontal = 4.dp))
          Text(stringResource(R.string.action_copy_selected_attrs))
        }

        Button(
          onClick = {
            val active = clipboard?.availableCategories?.intersect(chosenCategories).orEmpty()
            val toApply = active.ifEmpty { clipboard?.availableCategories.orEmpty() }
            onPasteToCompatibleClips(toApply)
          },
          enabled = clipboard != null,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("paste_attrs_button"),
        ) {
          Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null)
          Spacer(modifier = Modifier.padding(horizontal = 4.dp))
          Text(stringResource(R.string.action_paste_attrs_same_kind))
        }
      }

      if (clipboard != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text =
            "Portapapeles (${clipboard.sourceLabel}): " +
              clipboard.availableCategories.joinToString(", ") { it.name },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

      // 2. Snapshots & Duplicate Sequences
      Text(
        text = stringResource(R.string.label_snapshots_sequences),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalButton(
          onClick = onCreateSnapshot,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("create_snapshot_button"),
        ) {
          Icon(imageVector = Icons.Default.Save, contentDescription = null)
          Spacer(modifier = Modifier.padding(horizontal = 4.dp))
          Text(stringResource(R.string.action_take_snapshot))
        }

        Button(
          onClick = onDuplicateSequence,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("duplicate_sequence_button"),
        ) {
          Icon(imageVector = Icons.Default.FileCopy, contentDescription = null)
          Spacer(modifier = Modifier.padding(horizontal = 4.dp))
          Text(stringResource(R.string.action_duplicate_sequence))
        }
      }

      if (sequences.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
          sequences.forEach { seq ->
            FilterChip(
              selected = seq.sequenceId == activeSequenceId,
              onClick = { onSwitchSequence(seq.sequenceId) },
              label = { Text(seq.name) },
              modifier = Modifier.testTag("sequence_chip_${seq.sequenceId}"),
            )
          }
        }
      }

      LazyColumn(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items(snapshots, key = { it.snapshotId }) { snap ->
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(text = snap.name, style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "${snap.timeline.clips.size} clips · ${snap.timeline.markers.size} marcadores",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              TextButton(
                onClick = { onRestoreSnapshot(snap.snapshotId) },
                modifier = Modifier.testTag("restore_snapshot_${snap.snapshotId}"),
              ) {
                Icon(imageVector = Icons.Default.Restore, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(stringResource(R.string.action_restore_snapshot))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun labelForCategory(category: AttributeCategory): String =
  when (category) {
    AttributeCategory.COLOR_LUT -> stringResource(R.string.attr_color_lut)
    AttributeCategory.SCALE_POSITION -> stringResource(R.string.attr_scale_pos)
    AttributeCategory.TEXT_STYLE -> stringResource(R.string.attr_text_style)
    AttributeCategory.AUDIO_MIX -> stringResource(R.string.attr_audio_mix)
  }
