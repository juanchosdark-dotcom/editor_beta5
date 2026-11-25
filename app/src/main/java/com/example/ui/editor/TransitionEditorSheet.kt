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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.TransitionType
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TransitionEditorSheet(
  cut: SelectedTransitionCut,
  onApplyTransition: (type: TransitionType?, durationMs: Long) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedType by remember(cut) {
    mutableStateOf<TransitionType?>(cut.currentTransition?.type ?: TransitionType.CROSS_DISSOLVE)
  }
  var durationMsFloat by remember(cut) {
    mutableFloatStateOf(
      (cut.currentTransition?.durationMs ?: 500L)
        .coerceIn(100L, cut.maxDurationMs)
        .toFloat(),
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
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .testTag("transition_editor_sheet"),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.title_transition_editor),
          style = MaterialTheme.typography.titleMedium,
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_transition_sheet"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_sheet),
          )
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        FilterChip(
          selected = selectedType == null,
          onClick = { selectedType = null },
          label = { Text(stringResource(R.string.trans_none)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("transition_type_NONE"),
        )
        TransitionType.entries.forEach { type ->
          val labelRes =
            when (type) {
              TransitionType.CROSS_DISSOLVE -> R.string.trans_cross_dissolve
              TransitionType.FADE_BLACK -> R.string.trans_fade_black
              TransitionType.FADE_WHITE -> R.string.trans_fade_white
              TransitionType.SLIDE_LEFT -> R.string.trans_slide_left
              TransitionType.SLIDE_RIGHT -> R.string.trans_slide_right
              TransitionType.WIPE_LEFT -> R.string.trans_wipe_left
              TransitionType.WIPE_RIGHT -> R.string.trans_wipe_right
            }
          FilterChip(
            selected = selectedType == type,
            onClick = { selectedType = type },
            label = { Text(stringResource(labelRes)) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("transition_type_${type.name}"),
          )
        }
      }

      val clampedDur = durationMsFloat.roundToLong().coerceIn(100L, cut.maxDurationMs)
      Text(
        text = stringResource(R.string.label_transition_duration, clampedDur.toInt()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Slider(
        value = durationMsFloat,
        onValueChange = { durationMsFloat = it },
        valueRange = 100f..cut.maxDurationMs.toFloat().coerceAtLeast(150f),
        enabled = selectedType != null,
        modifier = Modifier.fillMaxWidth().testTag("transition_duration_slider"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (cut.currentTransition != null) {
          OutlinedButton(
            onClick = { onApplyTransition(null, clampedDur) },
            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("remove_transition_button"),
          ) {
            Text(stringResource(R.string.action_remove_transition))
          }
        }
        Button(
          onClick = { onApplyTransition(selectedType, clampedDur) },
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("apply_transition_button"),
        ) {
          Text(stringResource(R.string.action_apply_transition))
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
    }
  }
}
