package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ColorGradeStageParams
import com.example.core.model.ColorGradingEngine
import com.example.core.model.LutPreset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PrecisionConsoleSlider(
  label: String,
  valueText: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  defaultValue: Float,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  onReset: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
  bipolarOrigin: Boolean = false,
  enabled: Boolean = true,
) {
  val haptic = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val isDragged by interactionSource.collectIsDraggedAsState()

  val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
  val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
  val isModified = abs(clampedValue - defaultValue) > span * 0.008f
  val currentDetentBucket = ((clampedValue - valueRange.start) / span * 20f).roundToInt()
  var lastHapticBucket by remember(testTag) { mutableIntStateOf(currentDetentBucket) }

  LaunchedEffect(isDragged) {
    if (isDragged) {
      lastHapticBucket = currentDetentBucket
    }
  }

  val activeAccent =
    if (isDragged) {
      MaterialTheme.colorScheme.secondary
    } else {
      MaterialTheme.colorScheme.primary
    }
  val trackBaseColor = MaterialTheme.colorScheme.surfaceContainerLowest
  val tickColor = MaterialTheme.colorScheme.outlineVariant
  val detentTickColor = MaterialTheme.colorScheme.onSurfaceVariant
  val thumbFillColor =
    if (isDragged) {
      MaterialTheme.colorScheme.secondary
    } else if (isModified) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val thumbBorderColor = MaterialTheme.colorScheme.surfaceContainerLowest
  val thumbNotchColor = MaterialTheme.colorScheme.surfaceContainerLowest

  Column(
    modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier =
          Modifier
            .weight(1f)
            .combinedClickable(
              enabled = enabled,
              onClick = {},
              onDoubleClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onReset()
              },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Box(
          modifier =
            Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(
                when {
                  isDragged -> MaterialTheme.colorScheme.secondary
                  isModified -> MaterialTheme.colorScheme.primary
                  else -> MaterialTheme.colorScheme.outlineVariant
                },
              ),
        )
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (isDragged || isModified) FontWeight.SemiBold else FontWeight.Medium,
          color =
            if (isDragged) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Surface(
          color =
            if (isDragged) {
              activeAccent.copy(alpha = 0.16f)
            } else {
              MaterialTheme.colorScheme.surfaceContainerLowest
            },
          shape = RoundedCornerShape(5.dp),
          border =
            BorderStroke(
              width = 1.dp,
              color =
                when {
                  isDragged -> activeAccent
                  isModified -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                  else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
          modifier =
            Modifier.combinedClickable(
              enabled = enabled,
              onClick = {},
              onDoubleClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onReset()
              },
            ).testTag("${testTag}_readout"),
        ) {
          Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color =
              when {
                isDragged -> MaterialTheme.colorScheme.secondary
                isModified -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
              },
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
          )
        }

        IconButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onReset()
          },
          enabled = enabled && isModified,
          modifier =
            Modifier
              .minimumInteractiveComponentSize()
              .size(28.dp)
              .testTag("${testTag}_reset"),
        ) {
          Icon(
            imageVector = Icons.Default.RestartAlt,
            contentDescription = stringResource(R.string.action_inspector_reset_param),
            tint =
              if (isModified) {
                MaterialTheme.colorScheme.onSurfaceVariant
              } else {
                MaterialTheme.colorScheme.outlineVariant
              },
            modifier = Modifier.size(15.dp),
          )
        }
      }
    }

    Slider(
      value = clampedValue,
      onValueChange = { rawNext ->
        val nextBucket = ((rawNext - valueRange.start) / span * 20f).roundToInt()
        val crossedDefault =
          (clampedValue < defaultValue && rawNext >= defaultValue) ||
            (clampedValue > defaultValue && rawNext <= defaultValue)
        if (crossedDefault || nextBucket != lastHapticBucket) {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          lastHapticBucket = nextBucket
        }
        onValueChange(rawNext)
      },
      onValueChangeFinished = onValueChangeFinished,
      valueRange = valueRange,
      enabled = enabled,
      interactionSource = interactionSource,
      thumb = {
        Box(
          modifier = Modifier.size(width = 20.dp, height = 26.dp),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier =
              Modifier
                .width(if (isDragged) 14.dp else 11.dp)
                .height(if (isDragged) 22.dp else 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(thumbFillColor)
                .padding(1.dp),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .clip(RoundedCornerShape(3.dp))
                  .background(thumbFillColor),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier =
                  Modifier
                    .width(2.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(thumbNotchColor.copy(alpha = 0.85f)),
              )
            }
          }
          Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, thumbBorderColor),
            modifier =
              Modifier
                .width(if (isDragged) 14.dp else 11.dp)
                .height(if (isDragged) 22.dp else 18.dp),
          ) {}
        }
      },
      track = { sliderState ->
        Canvas(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(20.dp),
        ) {
          val trackHeight = if (isDragged) 5.dp.toPx() else 4.dp.toPx()
          val centerY = size.height / 2f
          val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

          // Base console groove
          drawRoundRect(
            color = trackBaseColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
          )

          // Precision calibration ticks
          val tickCount = 10
          for (i in 0..tickCount) {
            val x = (i.toFloat() / tickCount.toFloat()) * size.width
            val isEdge = i == 0 || i == tickCount
            val tickH = if (isEdge) 8.dp.toPx() else 4.dp.toPx()
            drawLine(
              color = tickColor.copy(alpha = 0.6f),
              start = Offset(x, centerY + trackHeight / 2f + 1.dp.toPx()),
              end = Offset(x, centerY + trackHeight / 2f + 1.dp.toPx() + tickH * 0.5f),
              strokeWidth = 1.dp.toPx(),
            )
          }

          val normValue = ((sliderState.value - valueRange.start) / span).coerceIn(0f, 1f)
          val normDefault = ((defaultValue - valueRange.start) / span).coerceIn(0f, 1f)

          val fillStartNorm = if (bipolarOrigin) minOf(normDefault, normValue) else 0f
          val fillEndNorm = if (bipolarOrigin) maxOf(normDefault, normValue) else normValue
          val fillWidth = ((fillEndNorm - fillStartNorm) * size.width).coerceAtLeast(0f)

          if (fillWidth > 1f) {
            drawRoundRect(
              color = activeAccent,
              topLeft = Offset(fillStartNorm * size.width, centerY - trackHeight / 2f),
              size = Size(fillWidth, trackHeight),
              cornerRadius = radius,
            )
          }

          // Center / default detent tick marker
          if (normDefault in 0.03f..0.97f) {
            val detentX = normDefault * size.width
            drawLine(
              color = detentTickColor,
              start = Offset(detentX, centerY - 6.dp.toPx()),
              end = Offset(detentX, centerY - 2.dp.toPx()),
              strokeWidth = 1.5.dp.toPx(),
            )
          }
        }
      },
      modifier = Modifier.fillMaxWidth().height(28.dp).testTag(testTag),
    )
  }
}

@Composable
internal fun LutPresetThumbnailStrip(
  selectedLutId: String?,
  lutStrength: Float,
  onSelectLut: (String) -> Unit,
  onBeginDragStrength: () -> Unit,
  onStrengthChange: (Float) -> Unit,
  onEndDragStrength: () -> Unit,
  onResetStrength: () -> Unit,
  modifier: Modifier = Modifier,
  savedLutPresets: List<LutPreset> = emptyList(),
  onSavePreset: ((String) -> Unit)? = null,
  onDeletePreset: ((String) -> Unit)? = null,
) {
  val activeId = selectedLutId?.takeIf { it.isNotBlank() } ?: BuiltInLutPresets.ID_NONE
  val hasActiveLut = activeId != BuiltInLutPresets.ID_NONE
  val allPresets = remember(savedLutPresets) { BuiltInLutPresets.allWithCustom(savedLutPresets) }
  var showSaveDialog by remember { mutableStateOf(false) }
  var presetNameInput by remember { mutableStateOf("") }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.label_lut_presets),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val activePreset = BuiltInLutPresets.findById(activeId, savedLutPresets) ?: BuiltInLutPresets.NONE
        Text(
          text =
            if (hasActiveLut) {
              "${activePreset.name} · ${(lutStrength * 100f).roundToInt()}%"
            } else {
              activePreset.name
            },
          style = MaterialTheme.typography.labelSmall,
          color =
            if (hasActiveLut) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      if (onSavePreset != null) {
        OutlinedButton(
          onClick = {
            presetNameInput = "Look ${savedLutPresets.size + 1}"
            showSaveDialog = true
          },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier =
            Modifier
              .minimumInteractiveComponentSize()
              .testTag("save_lut_preset_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.action_save_lut_preset),
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }

    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(horizontal = 2.dp),
      modifier = Modifier.fillMaxWidth().testTag("lut_presets_row"),
    ) {
      items(allPresets, key = { it.id }) { preset ->
        val isSelected = preset.id == activeId
        LutPresetThumbnailCard(
          preset = preset,
          isSelected = isSelected,
          strengthPct = if (isSelected && preset.id != BuiltInLutPresets.ID_NONE) (lutStrength * 100f).roundToInt() else null,
          onClick = { onSelectLut(preset.id) },
          onDelete = if (preset.isCustom && onDeletePreset != null) {
            { onDeletePreset(preset.id) }
          } else {
            null
          },
        )
      }
    }

    if (hasActiveLut) {
      PrecisionConsoleSlider(
        label = stringResource(R.string.label_lut_strength),
        valueText = "${(lutStrength * 100f).roundToInt()}%",
        value = lutStrength.coerceIn(0f, 1f),
        valueRange = 0f..1f,
        defaultValue = 1.0f,
        onValueChange = { next ->
          onBeginDragStrength()
          onStrengthChange(next)
        },
        onValueChangeFinished = onEndDragStrength,
        onReset = onResetStrength,
        testTag = "lut_strength_slider",
      )
    }
  }

  if (showSaveDialog && onSavePreset != null) {
    AlertDialog(
      onDismissRequest = { showSaveDialog = false },
      title = { Text(stringResource(R.string.title_save_lut_dialog)) },
      text = {
        OutlinedTextField(
          value = presetNameInput,
          onValueChange = { presetNameInput = it },
          label = { Text(stringResource(R.string.label_lut_preset_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("lut_save_name_input"),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onSavePreset(presetNameInput)
            showSaveDialog = false
          },
          modifier = Modifier.testTag("lut_save_confirm_button"),
        ) {
          Text(stringResource(R.string.action_confirm_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { showSaveDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun LutPresetThumbnailCard(
  preset: LutPreset,
  isSelected: Boolean,
  strengthPct: Int?,
  onClick: () -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  val borderColor =
    if (isSelected) {
      MaterialTheme.colorScheme.secondary
    } else {
      MaterialTheme.colorScheme.outlineVariant
    }
  val badgeColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)
  val vignetteTint = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = (preset.vignette * 1.1f).coerceIn(0f, 0.45f))

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    modifier =
      Modifier
        .width(88.dp)
        .minimumInteractiveComponentSize()
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .testTag("lut_card_${preset.id}"),
  ) {
    Column {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp)),
      ) {
        val shadow = Color(preset.shadowColorArgb)
        val midtone = Color(preset.midtoneColorArgb)
        val highlight = Color(preset.highlightColorArgb)

        Canvas(modifier = Modifier.fillMaxSize()) {
          drawRect(
            brush =
              Brush.linearGradient(
                colors = listOf(highlight, midtone, shadow),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
              ),
          )
          // Stylized mountain / cinema horizon silhouette to show shadow vs highlight separation
          val horizonPath =
            Path().apply {
              moveTo(0f, size.height)
              lineTo(0f, size.height * 0.65f)
              lineTo(size.width * 0.35f, size.height * 0.42f)
              lineTo(size.width * 0.64f, size.height * 0.62f)
              lineTo(size.width, size.height * 0.38f)
              lineTo(size.width, size.height)
              close()
            }
          drawPath(path = horizonPath, color = shadow.copy(alpha = 0.78f))
          drawCircle(
            color = highlight.copy(alpha = 0.85f),
            radius = size.minDimension * 0.16f,
            center = Offset(size.width * 0.76f, size.height * 0.28f),
          )
          if (preset.vignette > 0.05f) {
            drawRect(color = vignetteTint)
          }
        }

        // Top-left LUT technical code pill
        Surface(
          color = badgeColor,
          shape = RoundedCornerShape(4.dp),
          modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
        ) {
          Text(
            text = preset.shortCode,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }

        // Active check or delete pill for custom LUTs
        if (onDelete != null) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = CircleShape,
            modifier =
              Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .testTag("delete_lut_${preset.id}"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_delete_lut_preset),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(11.dp),
              )
            }
          }
        } else if (isSelected) {
          Surface(
            color = MaterialTheme.colorScheme.secondary,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(15.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.size(10.dp),
              )
            }
          }
        }
        if (strengthPct != null) {
          Surface(
            color = badgeColor,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
          ) {
            Text(
              text = "$strengthPct%",
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              color = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }
      }

      Text(
        text = preset.name,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.secondary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
      )
    }
  }
}

@Composable
internal fun LiftGammaGainControlPanel(
  liftX: Float,
  liftY: Float,
  gammaX: Float,
  gammaY: Float,
  gainX: Float,
  gainY: Float,
  stageParams: ColorGradeStageParams,
  onBeginEdit: () -> Unit,
  onUpdateLift: (x: Float, y: Float, fromDrag: Boolean) -> Unit,
  onUpdateGamma: (x: Float, y: Float, fromDrag: Boolean) -> Unit,
  onUpdateGain: (x: Float, y: Float, fromDrag: Boolean) -> Unit,
  onEndEdit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.fillMaxWidth().testTag("color_wheels_section"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_color_wheels_section),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.secondary,
        )
        Text(
          text = stringResource(R.string.hint_wheel_double_tap),
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ColorWheelPad(
          title = stringResource(R.string.label_wheel_lift),
          x = liftX,
          y = liftY,
          onBeginEdit = onBeginEdit,
          onValueChange = { nx, ny -> onUpdateLift(nx, ny, true) },
          onEndEdit = onEndEdit,
          onReset = { onUpdateLift(0f, 0f, false) },
          testTag = "lift_wheel_control",
          modifier = Modifier.weight(1f),
        )
        ColorWheelPad(
          title = stringResource(R.string.label_wheel_gamma),
          x = gammaX,
          y = gammaY,
          onBeginEdit = onBeginEdit,
          onValueChange = { nx, ny -> onUpdateGamma(nx, ny, true) },
          onEndEdit = onEndEdit,
          onReset = { onUpdateGamma(0f, 0f, false) },
          testTag = "gamma_wheel_control",
          modifier = Modifier.weight(1f),
        )
        ColorWheelPad(
          title = stringResource(R.string.label_wheel_gain),
          x = gainX,
          y = gainY,
          onBeginEdit = onBeginEdit,
          onValueChange = { nx, ny -> onUpdateGain(nx, ny, true) },
          onEndEdit = onEndEdit,
          onReset = { onUpdateGain(0f, 0f, false) },
          testTag = "gain_wheel_control",
          modifier = Modifier.weight(1f),
        )
      }

      Text(
        text = stringResource(R.string.label_tonal_curve),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TonalCurveCanvas(
        stageParams = stageParams,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .testTag("tonal_curve_preview"),
      )
    }
  }
}

@Composable
private fun ColorWheelPad(
  title: String,
  x: Float,
  y: Float,
  onBeginEdit: () -> Unit,
  onValueChange: (Float, Float) -> Unit,
  onEndEdit: () -> Unit,
  onReset: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  val latestOnBegin by rememberUpdatedState(onBeginEdit)
  val latestOnChange by rememberUpdatedState(onValueChange)
  val latestOnEnd by rememberUpdatedState(onEndEdit)
  val latestOnReset by rememberUpdatedState(onReset)
  val isModified = abs(x) > 0.01f || abs(y) > 0.01f
  val borderColor =
    if (isModified) {
      MaterialTheme.colorScheme.secondary
    } else {
      MaterialTheme.colorScheme.outlineVariant
    }

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
      fontWeight = if (isModified) FontWeight.Bold else FontWeight.Medium,
      color = if (isModified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(CircleShape)
          .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { latestOnReset() })
          }
          .pointerInput(Unit) {
            fun updateFromPosition(pos: Offset) {
              val cx = size.width * 0.5f
              val cy = size.height * 0.5f
              val radius = (minOf(size.width, size.height) * 0.46f).coerceAtLeast(1f)
              var nx = (pos.x - cx) / radius
              var ny = (pos.y - cy) / radius
              val mag = hypot(nx.toDouble(), ny.toDouble()).toFloat()
              if (mag > 1f) {
                nx /= mag
                ny /= mag
              }
              latestOnChange(nx.coerceIn(-1f, 1f), ny.coerceIn(-1f, 1f))
            }
            detectDragGestures(
              onDragStart = { offset ->
                latestOnBegin()
                updateFromPosition(offset)
              },
              onDragEnd = { latestOnEnd() },
              onDragCancel = { latestOnEnd() },
              onDrag = { change, _ ->
                change.consume()
                updateFromPosition(change.position)
              },
            )
          }
          .testTag(testTag),
      contentAlignment = Alignment.Center,
    ) {
      val surfaceTint = MaterialTheme.colorScheme.surface
      val onSurface = MaterialTheme.colorScheme.onSurface
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = (minOf(size.width, size.height) * 0.46f).coerceAtLeast(1f)
        // Chromatic sweep wheel
        drawCircle(
          brush =
            Brush.sweepGradient(
              colors =
                listOf(
                  Color(0xFFE5534B),
                  Color(0xFFE59B3E),
                  Color(0xFF42B983),
                  Color(0xFF38BDF8),
                  Color(0xFF818CF8),
                  Color(0xFFD946EF),
                  Color(0xFFE5534B),
                ),
              center = Offset(cx, cy),
            ),
          radius = r,
          center = Offset(cx, cy),
        )
        // Neutral desaturated center blend
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(surfaceTint.copy(alpha = 0.90f), surfaceTint.copy(alpha = 0.25f)),
              center = Offset(cx, cy),
              radius = r,
            ),
          radius = r,
          center = Offset(cx, cy),
        )
        drawCircle(
          color = borderColor,
          radius = r,
          center = Offset(cx, cy),
          style = Stroke(width = 1.5.dp.toPx()),
        )
        // Center crosshair
        drawLine(
          color = onSurface.copy(alpha = 0.28f),
          start = Offset(cx - r * 0.45f, cy),
          end = Offset(cx + r * 0.45f, cy),
          strokeWidth = 1.dp.toPx(),
        )
        drawLine(
          color = onSurface.copy(alpha = 0.28f),
          start = Offset(cx, cy - r * 0.45f),
          end = Offset(cx, cy + r * 0.45f),
          strokeWidth = 1.dp.toPx(),
        )
        // Puck
        val puckX = cx + x.coerceIn(-1f, 1f) * r * 0.85f
        val puckY = cy + y.coerceIn(-1f, 1f) * r * 0.85f
        drawCircle(
          color = surfaceTint,
          radius = 5.5.dp.toPx(),
          center = Offset(puckX, puckY),
        )
        drawCircle(
          color = borderColor,
          radius = 5.5.dp.toPx(),
          center = Offset(puckX, puckY),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
    }

    Text(
      text = "${"%+.1f".format(x)}, ${"%+.1f".format(y)}",
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

@Composable
private fun TonalCurveCanvas(
  stageParams: ColorGradeStageParams,
  modifier: Modifier = Modifier,
) {
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
  val borderColor = MaterialTheme.colorScheme.outlineVariant
  val lumaCurveColor = MaterialTheme.colorScheme.onSurface

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    if (w <= 4f || h <= 4f) return@Canvas
    val pad = 4.dp.toPx()
    val plotW = (w - pad * 2f).coerceAtLeast(1f)
    val plotH = (h - pad * 2f).coerceAtLeast(1f)

    // 4x4 grid
    for (i in 1..3) {
      val gx = pad + plotW * (i / 4f)
      val gy = pad + plotH * (i / 4f)
      drawLine(gridColor, Offset(gx, pad), Offset(gx, pad + plotH), 0.8.dp.toPx())
      drawLine(gridColor, Offset(pad, gy), Offset(pad + plotW, gy), 0.8.dp.toPx())
    }
    // Diagonal reference line
    drawLine(
      color = gridColor,
      start = Offset(pad, pad + plotH),
      end = Offset(pad + plotW, pad),
      strokeWidth = 1.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
    )

    val steps = 28
    val redPath = Path()
    val greenPath = Path()
    val bluePath = Path()
    val lumaPath = Path()

    for (i in 0..steps) {
      val t = i.toDouble() / steps.toDouble()
      val (r, g, b) = ColorGradingEngine.applyBaseAdjustmentsToRgb(t, t, t, stageParams)
      val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 1.0)
      val px = pad + (t * plotW).toFloat()
      val ry = pad + ((1.0 - r.coerceIn(0.0, 1.0)) * plotH).toFloat()
      val gy = pad + ((1.0 - g.coerceIn(0.0, 1.0)) * plotH).toFloat()
      val by = pad + ((1.0 - b.coerceIn(0.0, 1.0)) * plotH).toFloat()
      val ly = pad + ((1.0 - luma) * plotH).toFloat()
      if (i == 0) {
        redPath.moveTo(px, ry)
        greenPath.moveTo(px, gy)
        bluePath.moveTo(px, by)
        lumaPath.moveTo(px, ly)
      } else {
        redPath.lineTo(px, ry)
        greenPath.lineTo(px, gy)
        bluePath.lineTo(px, by)
        lumaPath.lineTo(px, ly)
      }
    }

    drawPath(redPath, Color(0xFFEF4444).copy(alpha = 0.78f), style = Stroke(width = 1.3.dp.toPx()))
    drawPath(greenPath, Color(0xFF22C55E).copy(alpha = 0.78f), style = Stroke(width = 1.3.dp.toPx()))
    drawPath(bluePath, Color(0xFF3B82F6).copy(alpha = 0.78f), style = Stroke(width = 1.3.dp.toPx()))
    drawPath(lumaPath, lumaCurveColor, style = Stroke(width = 2.dp.toPx()))
    drawRect(
      color = borderColor,
      topLeft = Offset(pad, pad),
      size = Size(plotW, plotH),
      style = Stroke(width = 1.dp.toPx()),
    )
  }
}
