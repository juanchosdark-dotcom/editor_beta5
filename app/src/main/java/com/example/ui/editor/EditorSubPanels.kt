package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.ClipKind
import com.example.core.model.TimelineClip
import com.example.core.timeline.TimelineEngine
import kotlin.math.roundToLong

@Composable
internal fun TrimRangeSubPanel(
  selectedClip: TimelineClip?,
  maxSourceDurationMs: Long,
  onBeginEdit: () -> Unit,
  onTrimRangeChange: (startMs: Long, endMs: Long, fromSliderDrag: Boolean) -> Unit,
  onEndEdit: () -> Unit,
  onTrimStartToPlayhead: () -> Unit,
  onTrimEndToPlayhead: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  fillAvailableHeight: Boolean = false,
  maxContentHeight: Dp = 240.dp,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
        .testTag("trim_subpanel"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.fillMaxHeight() else it.heightIn(max = maxContentHeight) }
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = Icons.Default.ContentCut,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text =
              if (selectedClip != null) {
                "${stringResource(R.string.title_trim_panel)} · ${selectedClip.label}"
              } else {
                stringResource(R.string.title_trim_panel)
              },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_subpanel),
          )
        }
      }

      if (selectedClip == null) {
        Text(
          text = stringResource(R.string.msg_select_clip_trim),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 4.dp),
        )
        return@Column
      }

      val isMedia = selectedClip.kind == ClipKind.VIDEO || selectedClip.kind == ClipKind.AUDIO
      val upperBoundMs = maxSourceDurationMs.coerceAtLeast(TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS * 2L)
      val committedStart =
        if (isMedia) {
          selectedClip.sourceInMs.coerceIn(0L, upperBoundMs - TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS)
        } else {
          selectedClip.timelineStartMs.coerceIn(0L, upperBoundMs - TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS)
        }
      val committedEnd =
        if (isMedia) {
          selectedClip.sourceOutMs.coerceIn(committedStart + TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS, upperBoundMs)
        } else {
          selectedClip.timelineEndMs.coerceIn(committedStart + TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS, upperBoundMs)
        }

      var sliderRange by remember(selectedClip.clipId, committedStart, committedEnd) {
        mutableStateOf(committedStart.toFloat()..committedEnd.toFloat())
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = onTrimStartToPlayhead,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("trim_start_playhead_button"),
        ) {
          Text(stringResource(R.string.action_set_start_playhead), maxLines = 1)
        }
        FilledTonalButton(
          onClick = onTrimEndToPlayhead,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("trim_end_playhead_button"),
        ) {
          Text(stringResource(R.string.action_set_end_playhead), maxLines = 1)
        }
      }

      val currentStartMs = sliderRange.start.roundToLong()
      val currentEndMs = sliderRange.endInclusive.roundToLong()
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "${stringResource(R.string.label_trim_start)}: ${formatTimecode(currentStartMs)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
        Text(
          text = "${stringResource(R.string.label_duration)}: ${formatTimecode((currentEndMs - currentStartMs).coerceAtLeast(0L))}",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
          text = "${stringResource(R.string.label_trim_end)}: ${formatTimecode(currentEndMs)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }

      RangeSlider(
        value = sliderRange,
        onValueChange = { proposed ->
          onBeginEdit()
          val minSpan = TimelineEngine.DEFAULT_MIN_CLIP_DURATION_MS.toFloat()
          val safeStart = proposed.start.coerceIn(0f, (upperBoundMs.toFloat() - minSpan).coerceAtLeast(0f))
          val safeEnd = proposed.endInclusive.coerceIn(safeStart + minSpan, upperBoundMs.toFloat())
          sliderRange = safeStart..safeEnd
          onTrimRangeChange(safeStart.roundToLong(), safeEnd.roundToLong(), true)
        },
        onValueChangeFinished = onEndEdit,
        valueRange = 0f..upperBoundMs.toFloat(),
        colors =
          SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.tertiary,
            activeTrackColor = MaterialTheme.colorScheme.tertiary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
          ),
        modifier = Modifier.fillMaxWidth().testTag("trim_range_slider"),
      )
    }
  }
}
