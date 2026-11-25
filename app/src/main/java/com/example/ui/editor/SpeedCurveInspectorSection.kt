package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.ClipKind
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.TimelineClip
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedCurveInspectorSection(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateSpeed: (speed: Float, fromSliderDrag: Boolean) -> Unit,
  onUpdateSpeedCurve: (speedCurve: List<SpeedCurvePoint>, fromSliderDrag: Boolean) -> Unit,
  onApplySpeedRampPreset: (presetId: String) -> Unit,
  onTogglePreservePitch: (preservePitch: Boolean) -> Unit,
) {
  val activeCurve =
    if (clip.hasVariableSpeed) {
      SpeedCurveEngine.normalizeCurve(clip.speedCurve, clip.speed)
    } else {
      SpeedCurveEngine.normalizeCurve(emptyList(), clip.speed)
    }

  var selectedPointIdx by remember(clip.clipId, activeCurve.size) {
    mutableIntStateOf((activeCurve.size / 2).coerceIn(0, activeCurve.lastIndex))
  }
  val safeSelectedIdx = selectedPointIdx.coerceIn(0, activeCurve.lastIndex)
  val selectedPoint = activeCurve[safeSelectedIdx]
  val preserveAudioPitch =
    when (clip.kind) {
      ClipKind.VIDEO -> clip.videoConfig?.preserveAudioPitch ?: true
      ClipKind.AUDIO -> clip.audioConfig?.preserveAudioPitch ?: true
      else -> true
    }

  // Constant base speed quick chips + slider
  val speedPresets = listOf(0.5f, 1.0f, 1.5f, 2.0f)
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    speedPresets.forEach { presetSpeed ->
      FilterChip(
        selected = !clip.hasVariableSpeed && abs(clip.speed - presetSpeed) < 0.03f,
        onClick = { onUpdateSpeed(presetSpeed, false) },
        label = { Text("${presetSpeed}x") },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("speed_preset_${presetSpeed}x"),
      )
    }
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_clip_speed),
    valueText = "${"%.2f".format(clip.speed)}x",
    value = clip.speed,
    valueRange = 0.25f..4.0f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateSpeed(next, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateSpeed(1.0f, false) },
    testTag = "inspector_speed_slider",
  )

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  // Speed Ramp Presets & Recalculated Duration Readout
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.label_speed_curve_section),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Surface(
      color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
      shape = RoundedCornerShape(6.dp),
      modifier = Modifier.testTag("speed_recalculated_duration_badge"),
    ) {
      val durationStr = String.format(Locale.US, "%.2fs", clip.durationMs / 1000.0)
      Text(
        text = stringResource(R.string.label_speed_recalc_duration, durationStr),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      )
    }
  }

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    val rampPresets =
      listOf(
        "normal" to R.string.speed_preset_normal,
        "pulse" to R.string.speed_preset_pulse,
        "hero" to R.string.speed_preset_hero,
        "bullet" to R.string.speed_preset_bullet,
      )
    rampPresets.forEach { (presetId, labelRes) ->
      FilterChip(
        selected = presetId == "normal" && !clip.hasVariableSpeed && abs(clip.speed - 1.0f) < 0.03f,
        onClick = { onApplySpeedRampPreset(presetId) },
        leadingIcon = {
          Icon(imageVector = Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
        },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("speed_ramp_preset_$presetId"),
      )
    }
  }

  // Interactive Speed Curve Canvas
  val curveColor = MaterialTheme.colorScheme.primary
  val accentColor = MaterialTheme.colorScheme.secondary
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val unityLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Canvas(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(108.dp)
          .clip(RoundedCornerShape(10.dp))
          .pointerInput(activeCurve) {
            detectTapGestures { offset ->
              val w = size.width.coerceAtLeast(1).toFloat()
              val h = size.height.coerceAtLeast(1).toFloat()
              val tapU = ((offset.x - 16f) / (w - 32f)).coerceIn(0f, 1f)
              val nearestIdx =
                activeCurve.indices.minByOrNull { idx ->
                  val px = 16f + activeCurve[idx].normalizedTime * (w - 32f)
                  val py =
                    16f + (1f - ((activeCurve[idx].speed - SpeedCurveEngine.MIN_SPEED) / (SpeedCurveEngine.MAX_SPEED - SpeedCurveEngine.MIN_SPEED))) * (h - 32f)
                  (px - offset.x) * (px - offset.x) + (py - offset.y) * (py - offset.y)
                }
              val hitExisting =
                nearestIdx != null &&
                  abs(activeCurve[nearestIdx].normalizedTime - tapU) < 0.09f
              if (hitExisting && nearestIdx != null) {
                selectedPointIdx = nearestIdx
              } else if (activeCurve.size < 8) {
                val speedNorm = (1f - ((offset.y - 16f) / (h - 32f))).coerceIn(0f, 1f)
                val targetSpeed =
                  SpeedCurveEngine.clampSpeed(
                    SpeedCurveEngine.MIN_SPEED +
                      speedNorm * (SpeedCurveEngine.MAX_SPEED - SpeedCurveEngine.MIN_SPEED),
                  )
                val updated = (activeCurve + SpeedCurvePoint(tapU, targetSpeed)).sortedBy { it.normalizedTime }
                val newIdx = updated.indexOfFirst { abs(it.normalizedTime - tapU) < 0.005f }.coerceAtLeast(0)
                selectedPointIdx = newIdx
                onUpdateSpeedCurve(updated, false)
              }
            }
          }
          .testTag("speed_curve_canvas"),
    ) {
      val pad = 16f
      val plotW = (size.width - pad * 2f).coerceAtLeast(1f)
      val plotH = (size.height - pad * 2f).coerceAtLeast(1f)
      fun mapX(u: Float): Float = pad + u.coerceIn(0f, 1f) * plotW
      fun mapY(spd: Float): Float {
        val norm = ((spd - SpeedCurveEngine.MIN_SPEED) / (SpeedCurveEngine.MAX_SPEED - SpeedCurveEngine.MIN_SPEED)).coerceIn(0f, 1f)
        return pad + (1f - norm) * plotH
      }

      // 1.0x reference line
      val unityY = mapY(1.0f)
      drawLine(
        color = unityLineColor,
        start = Offset(pad, unityY),
        end = Offset(size.width - pad, unityY),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
      )
      drawRect(color = gridColor, topLeft = Offset(pad, pad), size = androidx.compose.ui.geometry.Size(plotW, plotH), style = Stroke(1f))

      val path = Path()
      val steps = 32
      for (step in 0..steps) {
        val u = step.toFloat() / steps.toFloat()
        val spd = SpeedCurveEngine.instantaneousSpeedAtNormalizedSource(u, clip.speed, activeCurve)
        val px = mapX(u)
        val py = mapY(spd)
        if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
      }
      drawPath(path = path, color = curveColor, style = Stroke(width = 3.5f))

      activeCurve.forEachIndexed { idx, pt ->
        val cx = mapX(pt.normalizedTime)
        val cy = mapY(pt.speed)
        val selected = idx == safeSelectedIdx
        drawCircle(
          color = if (selected) accentColor else curveColor,
          radius = if (selected) 8.5f else 5.5f,
          center = Offset(cx, cy),
        )
        drawCircle(
          color = Color.Black.copy(alpha = 0.65f),
          radius = if (selected) 8.5f else 5.5f,
          center = Offset(cx, cy),
          style = Stroke(width = 2f),
        )
      }
    }
  }

  // Selected Keypoint Fine Sliders & Add/Remove Buttons
  val isEndpoint = safeSelectedIdx == 0 || safeSelectedIdx == activeCurve.lastIndex
  if (!isEndpoint) {
    val prevU = (activeCurve[safeSelectedIdx - 1].normalizedTime + 0.04f).coerceAtMost(0.95f)
    val nextU = (activeCurve[safeSelectedIdx + 1].normalizedTime - 0.04f).coerceAtLeast(prevU + 0.01f)
    PrecisionConsoleSlider(
      label = stringResource(R.string.label_speed_keyframe_pos),
      valueText = "${(selectedPoint.normalizedTime * 100f).toInt()}%",
      value = selectedPoint.normalizedTime.coerceIn(prevU, nextU),
      valueRange = prevU..nextU,
      defaultValue = (prevU + nextU) * 0.5f,
      onValueChange = { newU ->
        onBeginEdit()
        val nextCurve =
          activeCurve.toMutableList().also { list ->
            list[safeSelectedIdx] = selectedPoint.copy(normalizedTime = newU)
          }
        onUpdateSpeedCurve(nextCurve, true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        val nextCurve =
          activeCurve.toMutableList().also { list ->
            list[safeSelectedIdx] = selectedPoint.copy(normalizedTime = (prevU + nextU) * 0.5f)
          }
        onUpdateSpeedCurve(nextCurve, false)
      },
      testTag = "speed_keyframe_pos_slider",
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_speed_keyframe_rate),
    valueText = String.format(Locale.US, "%.2fx", selectedPoint.speed),
    value = selectedPoint.speed,
    valueRange = SpeedCurveEngine.MIN_SPEED..SpeedCurveEngine.MAX_SPEED,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { nextRate ->
      onBeginEdit()
      val nextCurve =
        activeCurve.toMutableList().also { list ->
          list[safeSelectedIdx] = selectedPoint.copy(speed = SpeedCurveEngine.clampSpeed(nextRate))
        }
      onUpdateSpeedCurve(nextCurve, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = {
      val nextCurve =
        activeCurve.toMutableList().also { list ->
          list[safeSelectedIdx] = selectedPoint.copy(speed = 1.0f)
        }
      onUpdateSpeedCurve(nextCurve, false)
    },
    testTag = "speed_keyframe_rate_slider",
  )

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AssistChip(
      onClick = {
        val midpointU =
          if (safeSelectedIdx < activeCurve.lastIndex) {
            (activeCurve[safeSelectedIdx].normalizedTime + activeCurve[safeSelectedIdx + 1].normalizedTime) * 0.5f
          } else {
            (activeCurve[safeSelectedIdx - 1].normalizedTime + activeCurve[safeSelectedIdx].normalizedTime) * 0.5f
          }
        val insertedSpeed = SpeedCurveEngine.instantaneousSpeedAtNormalizedSource(midpointU, clip.speed, activeCurve)
        val updated = (activeCurve + SpeedCurvePoint(midpointU, insertedSpeed)).sortedBy { it.normalizedTime }
        selectedPointIdx = updated.indexOfFirst { abs(it.normalizedTime - midpointU) < 0.005f }.coerceAtLeast(1)
        onUpdateSpeedCurve(updated, false)
      },
      enabled = activeCurve.size < 8,
      leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
      label = { Text(stringResource(R.string.action_add_speed_keyframe)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("add_speed_keyframe_button"),
    )

    AssistChip(
      onClick = {
        if (!isEndpoint && activeCurve.size > 2) {
          val updated = activeCurve.filterIndexed { idx, _ -> idx != safeSelectedIdx }
          selectedPointIdx = (safeSelectedIdx - 1).coerceIn(0, updated.lastIndex)
          onUpdateSpeedCurve(updated, false)
        }
      },
      enabled = !isEndpoint && activeCurve.size > 2,
      leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
      label = { Text(stringResource(R.string.action_remove_speed_keyframe)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("remove_speed_keyframe_button"),
    )
  }

  // Audio Pitch Preservation Switch
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.label_preserve_audio_pitch),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.size(8.dp))
      Switch(
        checked = preserveAudioPitch,
        onCheckedChange = onTogglePreservePitch,
        modifier = Modifier.testTag("preserve_pitch_switch"),
      )
    }
  }
}
