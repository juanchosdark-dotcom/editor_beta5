package com.example.ui.editor

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.TimelineClip
import java.util.Locale
import kotlin.math.roundToInt

private data class SubtitleColorOption(
  val id: String,
  val hex: String,
)

private val TEXT_COLOR_SWATCHES =
  listOf(
    SubtitleColorOption("white", "#FFFFFF"),
    SubtitleColorOption("amber", "#FBBF24"),
    SubtitleColorOption("cyan", "#38BDF8"),
    SubtitleColorOption("coral", "#FB7185"),
    SubtitleColorOption("emerald", "#34D399"),
    SubtitleColorOption("obsidian", "#0B0D12"),
  )

private val BG_COLOR_SWATCHES =
  listOf(
    SubtitleColorOption("clear", "#00000000"),
    SubtitleColorOption("scrim", "#99000000"),
    SubtitleColorOption("dark", "#CC0B0D12"),
    SubtitleColorOption("slate", "#CC1E293B"),
    SubtitleColorOption("sky", "#E638BDF8"),
    SubtitleColorOption("amber", "#D9F59E0B"),
  )

private val OUTLINE_COLOR_SWATCHES =
  listOf(
    SubtitleColorOption("black", "#FF000000"),
    SubtitleColorOption("dark", "#CC000000"),
    SubtitleColorOption("white", "#FFFFFFFF"),
    SubtitleColorOption("amber", "#FFF59E0B"),
    SubtitleColorOption("cyan", "#FF38BDF8"),
  )

private val FONT_FAMILY_OPTIONS =
  listOf(
    "sans-serif" to "Sans",
    "sans-serif-medium" to "Medium",
    "sans-serif-condensed" to "Cond.",
    "serif" to "Serif",
    "monospace" to "Mono",
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleInspectorSection(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateSubtitle: (
    text: String?,
    fontSizeSp: Float?,
    alignment: SubtitleAlignment?,
    fromSliderDrag: Boolean,
    fontFamily: String?,
    textColorHex: String?,
    backgroundColorHex: String?,
    outlineColorHex: String?,
    outlineWidthDp: Float?,
    verticalOffsetNormalized: Float?,
    animation: SubtitleAnimation?,
  ) -> Unit,
  onUpdateTiming: (newStartMs: Long, newEndMs: Long) -> Unit,
  onSplitSubtitle: () -> Unit,
  onMergeSubtitleWithNext: () -> Unit,
  onAutoGenerateSubtitles: () -> Unit,
  onOpenSubtitlesSheet: () -> Unit,
) {
  val subCfg = clip.subtitleConfig ?: return

  OutlinedTextField(
    value = subCfg.text,
    onValueChange = { nextText ->
      onBeginEdit()
      onUpdateSubtitle(nextText, null, null, true, null, null, null, null, null, null, null)
    },
    label = { Text(stringResource(R.string.label_subtitle_text)) },
    minLines = 1,
    maxLines = 3,
    modifier = Modifier.fillMaxWidth().testTag("inspector_subtitle_text_field"),
  )

  // Timing & segment split/merge/auto controls
  Text(
    text = stringResource(R.string.label_subtitle_timing_row),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val startMs = clip.timelineStartMs
    val endMs = clip.timelineEndMs
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = RoundedCornerShape(8.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = { onUpdateTiming((startMs - 200L).coerceAtLeast(0L), endMs) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_start_minus_btn"),
        ) { Text("-0.2s", style = MaterialTheme.typography.labelSmall) }
        Text(
          text = formatSecReadout(startMs),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.testTag("subtitle_start_ms_readout"),
        )
        OutlinedButton(
          onClick = { onUpdateTiming((startMs + 200L).coerceAtMost(endMs - 100L), endMs) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_start_plus_btn"),
        ) { Text("+0.2s", style = MaterialTheme.typography.labelSmall) }
      }
    }
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = RoundedCornerShape(8.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = { onUpdateTiming(startMs, (endMs - 200L).coerceAtLeast(startMs + 100L)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_end_minus_btn"),
        ) { Text("-0.2s", style = MaterialTheme.typography.labelSmall) }
        Text(
          text = formatSecReadout(endMs),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.testTag("subtitle_end_ms_readout"),
        )
        OutlinedButton(
          onClick = { onUpdateTiming(startMs, endMs + 200L) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_end_plus_btn"),
        ) { Text("+0.2s", style = MaterialTheme.typography.labelSmall) }
      }
    }
  }

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AssistChip(
      onClick = onSplitSubtitle,
      enabled = clip.durationMs >= 200L,
      leadingIcon = {
        Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
      },
      label = { Text(stringResource(R.string.action_split_subtitle)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_split_subtitle_button"),
    )
    AssistChip(
      onClick = onMergeSubtitleWithNext,
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.CallMerge,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_merge_subtitle)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_merge_subtitle_button"),
    )
    AssistChip(
      onClick = onAutoGenerateSubtitles,
      leadingIcon = {
        Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
      },
      label = { Text(stringResource(R.string.action_auto_subtitles)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_auto_subtitles_button"),
    )
  }

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  // Typography & Font Size
  Text(
    text = stringResource(R.string.label_subtitle_font),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    FONT_FAMILY_OPTIONS.forEach { (familyId, displayLabel) ->
      FilterChip(
        selected = subCfg.fontFamily == familyId,
        onClick = {
          onUpdateSubtitle(null, null, null, false, familyId, null, null, null, null, null, null)
        },
        label = { Text(displayLabel) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_font_$familyId"),
      )
    }
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_subtitle_size),
    valueText = "${subCfg.fontSizeSp.roundToInt()} sp",
    value = subCfg.fontSizeSp,
    valueRange = 12f..44f,
    defaultValue = 20f,
    onValueChange = { nextSize ->
      onBeginEdit()
      onUpdateSubtitle(null, nextSize, null, true, null, null, null, null, null, null, null)
    },
    onValueChangeFinished = onEndEdit,
    onReset = {
      onUpdateSubtitle(null, 20f, null, false, null, null, null, null, null, null, null)
    },
    testTag = "inspector_subtitle_size_slider",
  )

  // Alignment & Vertical Fine Offset
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    SubtitleAlignment.entries.forEach { alignment ->
      val shortName =
        when (alignment) {
          SubtitleAlignment.TOP_CENTER -> "Sup"
          SubtitleAlignment.MIDDLE_CENTER -> "Centro"
          SubtitleAlignment.BOTTOM_CENTER -> "Inf"
        }
      FilterChip(
        selected = subCfg.alignment == alignment,
        onClick = {
          onUpdateSubtitle(null, null, alignment, false, null, null, null, null, null, null, null)
        },
        label = { Text(shortName) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_align_${alignment.name}"),
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    AssistChip(
      onClick = onOpenSubtitlesSheet,
      label = { Text(stringResource(R.string.action_open_subtitles_sheet)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_open_subtitles_sheet"),
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_subtitle_vertical_offset),
    valueText = "${(subCfg.verticalOffsetNormalized * 100f).roundToInt()}%",
    value = subCfg.verticalOffsetNormalized,
    valueRange = -0.35f..0.35f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { nextOffset ->
      onBeginEdit()
      onUpdateSubtitle(null, null, null, true, null, null, null, null, null, nextOffset, null)
    },
    onValueChangeFinished = onEndEdit,
    onReset = {
      onUpdateSubtitle(null, null, null, false, null, null, null, null, null, 0.0f, null)
    },
    testTag = "inspector_subtitle_vertical_offset_slider",
  )

  // Text / Background / Outline Color Swatches
  SubtitleSwatchRow(
    label = stringResource(R.string.label_subtitle_text_color),
    swatches = TEXT_COLOR_SWATCHES,
    selectedHex = subCfg.textColorHex,
    tagPrefix = "subtitle_text_color",
    onSelectHex = { hex ->
      onUpdateSubtitle(null, null, null, false, null, hex, null, null, null, null, null)
    },
  )

  SubtitleSwatchRow(
    label = stringResource(R.string.label_subtitle_bg_color),
    swatches = BG_COLOR_SWATCHES,
    selectedHex = subCfg.backgroundColorHex,
    tagPrefix = "subtitle_bg_color",
    onSelectHex = { hex ->
      onUpdateSubtitle(null, null, null, false, null, null, hex, null, null, null, null)
    },
  )

  SubtitleSwatchRow(
    label = stringResource(R.string.label_subtitle_outline_color),
    swatches = OUTLINE_COLOR_SWATCHES,
    selectedHex = subCfg.outlineColorHex,
    tagPrefix = "subtitle_outline_color",
    onSelectHex = { hex ->
      onUpdateSubtitle(null, null, null, false, null, null, null, hex, null, null, null)
    },
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_subtitle_outline_width),
    valueText = String.format(Locale.US, "%.1f dp", subCfg.outlineWidthDp),
    value = subCfg.outlineWidthDp,
    valueRange = 0.0f..6.0f,
    defaultValue = 2.0f,
    onValueChange = { nextW ->
      onBeginEdit()
      onUpdateSubtitle(null, null, null, true, null, null, null, null, nextW, null, null)
    },
    onValueChangeFinished = onEndEdit,
    onReset = {
      onUpdateSubtitle(null, null, null, false, null, null, null, null, 2.0f, null, null)
    },
    testTag = "inspector_subtitle_outline_width_slider",
  )

  // Smooth Entry/Exit Animations
  Text(
    text = stringResource(R.string.label_subtitle_animation),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    SubtitleAnimation.entries.forEach { anim ->
      val labelRes =
        when (anim) {
          SubtitleAnimation.NONE -> R.string.sub_anim_none
          SubtitleAnimation.FADE -> R.string.sub_anim_fade
          SubtitleAnimation.POP -> R.string.sub_anim_pop
          SubtitleAnimation.SLIDE_UP -> R.string.sub_anim_slide_up
        }
      FilterChip(
        selected = subCfg.animation == anim,
        onClick = {
          onUpdateSubtitle(null, null, null, false, null, null, null, null, null, null, anim)
        },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("subtitle_anim_${anim.name}"),
      )
    }
  }
}

@Composable
private fun SubtitleSwatchRow(
  label: String,
  swatches: List<SubtitleColorOption>,
  selectedHex: String,
  tagPrefix: String,
  onSelectHex: (String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    swatches.forEach { option ->
      val isSelected = selectedHex.equals(option.hex, ignoreCase = true)
      val colorValue = parseSubtitleSwatchColor(option.hex)
      Box(
        modifier =
          Modifier
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .clip(CircleShape)
            .clickable { onSelectHex(option.hex) }
            .testTag("${tagPrefix}_${option.id}"),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          color = if (colorValue.alpha < 0.05f) MaterialTheme.colorScheme.surfaceContainerLowest else colorValue,
          shape = CircleShape,
          border =
            BorderStroke(
              width = if (isSelected) 2.5.dp else 1.dp,
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.outlineVariant
                },
            ),
          modifier = Modifier.size(22.dp),
        ) {
          if (colorValue.alpha < 0.05f) {
            Box(modifier = Modifier.background(Color.Transparent))
          }
        }
      }
    }
  }
}

private fun parseSubtitleSwatchColor(hex: String): Color =
  runCatching { Color(AndroidColor.parseColor(hex)) }.getOrElse { Color.White }

private fun formatSecReadout(ms: Long): String =
  String.format(Locale.US, "%.1fs", ms.coerceAtLeast(0L) / 1000.0)
