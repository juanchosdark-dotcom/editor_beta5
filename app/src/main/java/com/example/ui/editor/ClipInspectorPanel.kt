package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.ColorGradeBounds
import com.example.core.model.ColorGradeStageParams
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.LutPreset
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.TimelineClip
import com.example.ui.theme.LocalTrackColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun ClipInspectorPanel(
  selectedClip: TimelineClip?,
  activeTab: InspectorTab,
  onSelectTab: (InspectorTab) -> Unit,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateTransform: (
    scale: Float?,
    rotationDegrees: Float?,
    offsetX: Float?,
    offsetY: Float?,
    opacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onRotate90: () -> Unit,
  onUpdateColor: (
    brightness: Float?,
    contrast: Float?,
    saturation: Float?,
    exposure: Float?,
    temperature: Float?,
    vignette: Float?,
    lutPresetId: String?,
    clearLutPreset: Boolean,
    lutStrength: Float?,
    tint: Float?,
    shadows: Float?,
    midtones: Float?,
    highlights: Float?,
    blendOpacity: Float?,
    whites: Float?,
    blacks: Float?,
    vibrance: Float?,
    liftX: Float?,
    liftY: Float?,
    gammaX: Float?,
    gammaY: Float?,
    gainX: Float?,
    gainY: Float?,
    depthBlur: Float?,
    depthBlurRadius: Float?,
    depthBlurFeather: Float?,
    depthBlurMode: DepthBlurMaskMode?,
    depthFocusX: Float?,
    depthFocusY: Float?,
    depthFocusRange: Float?,
    chromaticAberration: Float?,
    glitchIntensity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onSelectLutPreset: (presetId: String) -> Unit,
  onUpdateAudio: (
    volume: Float?,
    pan: Float?,
    fadeInMs: Long?,
    fadeOutMs: Long?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onUpdateSpeed: (speed: Float, fromSliderDrag: Boolean) -> Unit,
  onUpdateSubtitle: (
    text: String?,
    fontSizeSp: Float?,
    alignment: SubtitleAlignment?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onResetTab: (InspectorTab) -> Unit,
  onResetAll: () -> Unit,
  onOpenAudioSheet: () -> Unit,
  onOpenSubtitlesSheet: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  savedLutPresets: List<LutPreset> = emptyList(),
  onSaveLutPreset: (String) -> Unit = {},
  onDeleteLutPreset: (String) -> Unit = {},
  onAddRackFocusKeyframe: () -> Unit = {},
  onUpdateSpeedCurve: (speedCurve: List<SpeedCurvePoint>, fromSliderDrag: Boolean) -> Unit = { _, _ -> },
  onApplySpeedRampPreset: (presetId: String) -> Unit = {},
  onTogglePreservePitch: (preservePitch: Boolean) -> Unit = {},
  onUpdateSubtitleExtended: (
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
  ) -> Unit = { text, size, align, drag, _, _, _, _, _, _, _ ->
    onUpdateSubtitle(text, size, align, drag)
  },
  onUpdateSubtitleTiming: (newStartMs: Long, newEndMs: Long) -> Unit = { _, _ -> },
  onSplitSubtitle: () -> Unit = {},
  onMergeSubtitleWithNext: () -> Unit = {},
  onAutoGenerateSubtitles: () -> Unit = {},
  fillAvailableHeight: Boolean = false,
  maxContentHeight: Dp = 220.dp,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  val haptic = LocalHapticFeedback.current
  val trackColors = LocalTrackColors.current

  val kindBadgeText =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> "V1"
      ClipKind.ADJUSTMENT -> "LUT"
      ClipKind.AUDIO -> "A1"
      ClipKind.SUBTITLE -> "SUB"
      null -> "--"
    }
  val kindBadgeColor =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> trackColors.videoTrack
      ClipKind.ADJUSTMENT -> trackColors.adjustmentTrack
      ClipKind.AUDIO -> trackColors.audioTrack
      ClipKind.SUBTITLE -> trackColors.subtitleTrack
      null -> MaterialTheme.colorScheme.outline
    }

  val hasTransformMod =
    selectedClip?.videoConfig?.let {
      abs(it.scale - 1f) > 0.01f ||
        abs(it.rotationDegrees) > 0.5f ||
        abs(it.offsetX) > 0.01f ||
        abs(it.offsetY) > 0.01f ||
        abs(it.opacity - 1f) > 0.01f
    } == true

  val hasColorMod =
    when (selectedClip?.kind) {
      ClipKind.VIDEO ->
        selectedClip.videoConfig?.let {
          BuiltInLutPresets.resolveEffectiveColor(it, savedLutPresets).hasColorModifications
        } == true
      ClipKind.ADJUSTMENT ->
        selectedClip.adjustmentConfig?.let {
          BuiltInLutPresets.resolveEffectiveColor(it, savedLutPresets).hasColorModifications || it.blendOpacity < 0.99f
        } == true
      else -> false
    }

  val hasAudioMod =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> selectedClip.videoConfig?.let { abs(it.volume - 1f) > 0.01f || it.fadeInMs > 0L || it.fadeOutMs > 0L } == true
      ClipKind.AUDIO -> selectedClip.audioConfig?.let { abs(it.volume - 1f) > 0.01f || abs(it.pan) > 0.01f || it.fadeInMs > 0L || it.fadeOutMs > 0L } == true
      else -> false
    }

  val hasSpeedOrSubMod =
    selectedClip?.let { clip ->
      clip.hasVariableSpeed ||
        abs(clip.speed - 1f) > 0.01f ||
        clip.videoConfig?.preserveAudioPitch == false ||
        clip.audioConfig?.preserveAudioPitch == false ||
        clip.kind == ClipKind.SUBTITLE
    } == true

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it.heightIn(max = maxContentHeight) }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
        .testTag("adjustments_subpanel"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.fillMaxHeight() else it },
    ) {
      // Pinned Active Clip Context Header (always stays visible while scrolling controls)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          color = kindBadgeColor.copy(alpha = 0.2f),
          shape = RoundedCornerShape(5.dp),
          border = BorderStroke(1.dp, kindBadgeColor.copy(alpha = 0.8f)),
        ) {
          Text(
            text = kindBadgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = kindBadgeColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = selectedClip?.label ?: stringResource(R.string.title_inspector_console),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (selectedClip != null) {
            val activeLutName =
              when (selectedClip.kind) {
                ClipKind.VIDEO ->
                  BuiltInLutPresets.findById(selectedClip.videoConfig?.lutPresetId, savedLutPresets)?.name
                ClipKind.ADJUSTMENT ->
                  BuiltInLutPresets.findById(selectedClip.adjustmentConfig?.lutPresetId, savedLutPresets)?.name
                else -> null
              }
            Text(
              text =
                buildString {
                  append(formatTimecode(selectedClip.durationMs))
                  if (selectedClip.hasVariableSpeed) {
                    append(" · Ramp ${"%.2f".format(selectedClip.speed)}x")
                  } else if (abs(selectedClip.speed - 1f) > 0.01f) {
                    append(" · ${"%.2f".format(selectedClip.speed)}x")
                  }
                  if (activeLutName != null) {
                    append(" · LUT: $activeLutName")
                  }
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        if (selectedClip != null) {
          IconButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onResetTab(activeTab)
            },
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("inspector_reset_tab_button"),
          ) {
            Icon(
              imageVector = Icons.Default.RestartAlt,
              contentDescription = stringResource(R.string.action_inspector_reset_tab),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }

        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_subpanel),
            modifier = Modifier.size(18.dp),
          )
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

      // Compact Category Tabs Row (always pinned above parameter viewport)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_transform),
          icon = Icons.Default.CropFree,
          isSelected = activeTab == InspectorTab.TRANSFORM,
          hasModified = hasTransformMod,
          onClick = { onSelectTab(InspectorTab.TRANSFORM) },
          testTag = "inspector_tab_TRANSFORM",
        )
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_color_lut),
          icon = Icons.Default.Palette,
          isSelected = activeTab == InspectorTab.COLOR_LUT,
          hasModified = hasColorMod,
          onClick = { onSelectTab(InspectorTab.COLOR_LUT) },
          testTag = "inspector_tab_COLOR_LUT",
        )
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_audio),
          icon = Icons.Default.GraphicEq,
          isSelected = activeTab == InspectorTab.AUDIO,
          hasModified = hasAudioMod,
          onClick = { onSelectTab(InspectorTab.AUDIO) },
          testTag = "inspector_tab_AUDIO",
        )
        val fourthLabel =
          when (selectedClip?.kind) {
            ClipKind.SUBTITLE -> stringResource(R.string.inspector_tab_subtitles)
            ClipKind.VIDEO, ClipKind.AUDIO -> stringResource(R.string.inspector_tab_speed)
            else -> stringResource(R.string.inspector_tab_speed_subtitles)
          }
        val fourthIcon =
          if (selectedClip?.kind == ClipKind.SUBTITLE) Icons.Default.Subtitles else Icons.Default.Speed
        InspectorCategoryChip(
          label = fourthLabel,
          icon = fourthIcon,
          isSelected = activeTab == InspectorTab.SPEED_SUBTITLES,
          hasModified = hasSpeedOrSubMod,
          onClick = { onSelectTab(InspectorTab.SPEED_SUBTITLES) },
          testTag = "inspector_tab_SPEED_SUBTITLES",
        )
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

      if (selectedClip == null) {
        Text(
          text = stringResource(R.string.msg_inspector_select_clip),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
      } else {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f, fill = fillAvailableHeight)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          when (activeTab) {
            InspectorTab.TRANSFORM ->
              TransformInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateTransform = onUpdateTransform,
                onRotate90 = onRotate90,
                onResetAll = onResetAll,
              )

            InspectorTab.COLOR_LUT ->
              ColorLutInspectorTab(
                clip = selectedClip,
                savedLutPresets = savedLutPresets,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateColor = onUpdateColor,
                onSelectLutPreset = onSelectLutPreset,
                onSaveLutPreset = onSaveLutPreset,
                onDeleteLutPreset = onDeleteLutPreset,
                onAddRackFocusKeyframe = onAddRackFocusKeyframe,
                onResetAll = onResetAll,
              )

            InspectorTab.AUDIO ->
              AudioInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateAudio = onUpdateAudio,
                onOpenAudioSheet = onOpenAudioSheet,
              )

            InspectorTab.SPEED_SUBTITLES ->
              SpeedAndSubtitlesInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateSpeed = onUpdateSpeed,
                onUpdateSpeedCurve = onUpdateSpeedCurve,
                onApplySpeedRampPreset = onApplySpeedRampPreset,
                onTogglePreservePitch = onTogglePreservePitch,
                onUpdateSubtitleExtended = onUpdateSubtitleExtended,
                onUpdateSubtitleTiming = onUpdateSubtitleTiming,
                onSplitSubtitle = onSplitSubtitle,
                onMergeSubtitleWithNext = onMergeSubtitleWithNext,
                onAutoGenerateSubtitles = onAutoGenerateSubtitles,
                onOpenSubtitlesSheet = onOpenSubtitlesSheet,
              )
          }
        }
      }
    }
  }
}

@Composable
private fun InspectorCategoryChip(
  label: String,
  icon: ImageVector,
  isSelected: Boolean,
  hasModified: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(8.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.outlineVariant
          },
      ),
    modifier =
      Modifier
        .heightIn(min = 34.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        maxLines = 1,
      )
      if (hasModified) {
        Box(
          modifier =
            Modifier
              .size(5.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.secondary),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransformInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateTransform: (
    scale: Float?,
    rotationDegrees: Float?,
    offsetX: Float?,
    offsetY: Float?,
    opacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onRotate90: () -> Unit,
  onResetAll: () -> Unit,
) {
  if (clip.kind != ClipKind.VIDEO) {
    InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_transform))
    return
  }
  val cfg = clip.videoConfig ?: return

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AssistChip(
      onClick = onRotate90,
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.RotateRight,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_rotate_90)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_rotate_90_button"),
    )
    AssistChip(
      onClick = onResetAll,
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.RestartAlt,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_reset_adjustments)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_reset_button"),
    )
  }

  val isOverlay = cfg.isOverlayContent
  val scaleRange = if (isOverlay) 0.15f..3.0f else 0.5f..2.0f
  val offsetRange = if (isOverlay) -0.95f..0.95f else -0.5f..0.5f

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_scale),
    valueText = "${"%.2f".format(cfg.scale)}x",
    value = cfg.scale,
    valueRange = scaleRange,
    defaultValue = 1.0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(next, null, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(1.0f, null, null, null, null, false) },
    testTag = "adjust_scale_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_rotation),
    valueText = "${"%.1f".format(cfg.rotationDegrees)}°",
    value = cfg.rotationDegrees,
    valueRange = -180f..180f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, next, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, 0.0f, null, null, null, false) },
    testTag = "adjust_rotation_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_position_x),
    valueText = "${if (cfg.offsetX >= 0f) "+" else ""}${"%.2f".format(cfg.offsetX)}",
    value = cfg.offsetX,
    valueRange = offsetRange,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, next, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, 0.0f, null, null, false) },
    testTag = "adjust_offset_x_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_position_y),
    valueText = "${if (cfg.offsetY >= 0f) "+" else ""}${"%.2f".format(cfg.offsetY)}",
    value = cfg.offsetY,
    valueRange = offsetRange,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, null, next, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, null, 0.0f, null, false) },
    testTag = "adjust_offset_y_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_opacity),
    valueText = "${(cfg.opacity * 100f).roundToInt()}%",
    value = cfg.opacity,
    valueRange = 0.1f..1.0f,
    defaultValue = 1.0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, null, null, next, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, null, null, 1.0f, false) },
    testTag = "adjust_opacity_slider",
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorLutInspectorTab(
  clip: TimelineClip,
  savedLutPresets: List<LutPreset>,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateColor: (
    brightness: Float?,
    contrast: Float?,
    saturation: Float?,
    exposure: Float?,
    temperature: Float?,
    vignette: Float?,
    lutPresetId: String?,
    clearLutPreset: Boolean,
    lutStrength: Float?,
    tint: Float?,
    shadows: Float?,
    midtones: Float?,
    highlights: Float?,
    blendOpacity: Float?,
    whites: Float?,
    blacks: Float?,
    vibrance: Float?,
    liftX: Float?,
    liftY: Float?,
    gammaX: Float?,
    gammaY: Float?,
    gainX: Float?,
    gainY: Float?,
    depthBlur: Float?,
    depthBlurRadius: Float?,
    depthBlurFeather: Float?,
    depthBlurMode: DepthBlurMaskMode?,
    depthFocusX: Float?,
    depthFocusY: Float?,
    depthFocusRange: Float?,
    chromaticAberration: Float?,
    glitchIntensity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onSelectLutPreset: (presetId: String) -> Unit,
  onSaveLutPreset: (String) -> Unit,
  onDeleteLutPreset: (String) -> Unit,
  onAddRackFocusKeyframe: () -> Unit,
  onResetAll: () -> Unit,
) {
  val brightness: Float
  val contrast: Float
  val saturation: Float
  val exposure: Float
  val temperature: Float
  val tint: Float
  val shadows: Float
  val midtones: Float
  val highlights: Float
  val whites: Float
  val blacks: Float
  val vibrance: Float
  val liftX: Float
  val liftY: Float
  val gammaX: Float
  val gammaY: Float
  val gainX: Float
  val gainY: Float
  val vignette: Float
  val lutPresetId: String?
  val lutStrength: Float
  val blendOpacity: Float
  val depthBlur: Float
  val depthBlurRadius: Float
  val depthBlurFeather: Float
  val depthBlurMode: DepthBlurMaskMode
  val depthFocusX: Float
  val depthFocusY: Float
  val depthFocusRange: Float
  val chromaticAberration: Float
  val glitchIntensity: Float

  when (clip.kind) {
    ClipKind.VIDEO -> {
      val cfg = clip.videoConfig ?: return
      brightness = cfg.brightness
      contrast = cfg.contrast
      saturation = cfg.saturation
      exposure = cfg.exposure
      temperature = cfg.temperature
      tint = cfg.tint
      shadows = cfg.shadows
      midtones = cfg.midtones
      highlights = cfg.highlights
      whites = cfg.whites
      blacks = cfg.blacks
      vibrance = cfg.vibrance
      liftX = cfg.liftX
      liftY = cfg.liftY
      gammaX = cfg.gammaX
      gammaY = cfg.gammaY
      gainX = cfg.gainX
      gainY = cfg.gainY
      vignette = cfg.vignette
      lutPresetId = cfg.lutPresetId
      lutStrength = cfg.lutStrength
      blendOpacity = 1.0f
      depthBlur = cfg.depthBlur
      depthBlurRadius = cfg.depthBlurRadius
      depthBlurFeather = cfg.depthBlurFeather
      depthBlurMode = cfg.depthBlurMode
      depthFocusX = cfg.depthFocusX
      depthFocusY = cfg.depthFocusY
      depthFocusRange = cfg.depthFocusRange
      chromaticAberration = cfg.chromaticAberration
      glitchIntensity = cfg.glitchIntensity
    }
    ClipKind.ADJUSTMENT -> {
      val cfg = clip.adjustmentConfig ?: return
      brightness = cfg.brightness
      contrast = cfg.contrast
      saturation = cfg.saturation
      exposure = cfg.exposure
      temperature = cfg.temperature
      tint = cfg.tint
      shadows = cfg.shadows
      midtones = cfg.midtones
      highlights = cfg.highlights
      whites = cfg.whites
      blacks = cfg.blacks
      vibrance = cfg.vibrance
      liftX = cfg.liftX
      liftY = cfg.liftY
      gammaX = cfg.gammaX
      gammaY = cfg.gammaY
      gainX = cfg.gainX
      gainY = cfg.gainY
      vignette = cfg.vignette
      lutPresetId = cfg.lutPresetId
      lutStrength = cfg.lutStrength
      blendOpacity = cfg.blendOpacity
      depthBlur = cfg.depthBlur
      depthBlurRadius = cfg.depthBlurRadius
      depthBlurFeather = cfg.depthBlurFeather
      depthBlurMode = cfg.depthBlurMode
      depthFocusX = cfg.depthFocusX
      depthFocusY = cfg.depthFocusY
      depthFocusRange = cfg.depthFocusRange
      chromaticAberration = cfg.chromaticAberration
      glitchIntensity = cfg.glitchIntensity
    }
    else -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_color))
      return
    }
  }

  fun updateColor(
    brightness: Float? = null,
    contrast: Float? = null,
    saturation: Float? = null,
    exposure: Float? = null,
    temperature: Float? = null,
    vignette: Float? = null,
    lutStrength: Float? = null,
    tint: Float? = null,
    shadows: Float? = null,
    midtones: Float? = null,
    highlights: Float? = null,
    blendOpacity: Float? = null,
    whites: Float? = null,
    blacks: Float? = null,
    vibrance: Float? = null,
    liftX: Float? = null,
    liftY: Float? = null,
    gammaX: Float? = null,
    gammaY: Float? = null,
    gainX: Float? = null,
    gainY: Float? = null,
    depthBlur: Float? = null,
    depthBlurRadius: Float? = null,
    depthBlurFeather: Float? = null,
    depthBlurMode: DepthBlurMaskMode? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
    chromaticAberration: Float? = null,
    glitchIntensity: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    onUpdateColor(
      brightness,
      contrast,
      saturation,
      exposure,
      temperature,
      vignette,
      null,
      false,
      lutStrength,
      tint,
      shadows,
      midtones,
      highlights,
      blendOpacity,
      whites,
      blacks,
      vibrance,
      liftX,
      liftY,
      gammaX,
      gammaY,
      gainX,
      gainY,
      depthBlur,
      depthBlurRadius,
      depthBlurFeather,
      depthBlurMode,
      depthFocusX,
      depthFocusY,
      depthFocusRange,
      chromaticAberration,
      glitchIntensity,
      fromSliderDrag,
    )
  }

  LutPresetThumbnailStrip(
    selectedLutId = lutPresetId,
    lutStrength = lutStrength,
    onSelectLut = onSelectLutPreset,
    onBeginDragStrength = onBeginEdit,
    onStrengthChange = { next -> updateColor(lutStrength = next, fromSliderDrag = true) },
    onEndDragStrength = onEndEdit,
    onResetStrength = { updateColor(lutStrength = 1.0f, fromSliderDrag = false) },
    savedLutPresets = savedLutPresets,
    onSavePreset = onSaveLutPreset,
    onDeletePreset = onDeleteLutPreset,
  )

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_exposure),
    valueText = "${if (exposure >= 0f) "+" else ""}${"%.2f".format(exposure)} EV",
    value = exposure,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(exposure = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(exposure = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_exposure_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_brightness),
    valueText = "${if (brightness >= 0f) "+" else ""}${(brightness * 100f).roundToInt()}",
    value = brightness,
    valueRange = -0.5f..0.5f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(brightness = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(brightness = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_brightness_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_contrast),
    valueText = "${(contrast * 100f).roundToInt()}%",
    value = contrast,
    valueRange = 0.5f..1.8f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(contrast = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(contrast = 1.0f, fromSliderDrag = false) },
    testTag = "adjust_contrast_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_saturation),
    valueText = "${(saturation * 100f).roundToInt()}%",
    value = saturation,
    valueRange = 0.0f..2.0f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(saturation = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(saturation = 1.0f, fromSliderDrag = false) },
    testTag = "adjust_saturation_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_vibrance),
    valueText = "${if (vibrance >= 0f) "+" else ""}${(vibrance * 100f).roundToInt()}",
    value = vibrance,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(vibrance = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(vibrance = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_vibrance_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_temperature),
    valueText = "${if (temperature >= 0f) "+" else ""}${(temperature * 100f).roundToInt()}",
    value = temperature,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(temperature = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(temperature = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_temperature_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_tint),
    valueText = "${if (tint >= 0f) "+" else ""}${(tint * 100f).roundToInt()}",
    value = tint,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(tint = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(tint = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_tint_slider",
  )

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  Text(
    text = stringResource(R.string.label_tonal_balance_section),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.secondary,
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_highlights_gain),
    valueText = "${if (highlights >= 0f) "+" else ""}${(highlights * 100f).roundToInt()}",
    value = highlights,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(highlights = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(highlights = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_highlights_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_shadows_lift),
    valueText = "${if (shadows >= 0f) "+" else ""}${(shadows * 100f).roundToInt()}",
    value = shadows,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(shadows = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(shadows = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_shadows_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_midtones_gamma),
    valueText = "${if (midtones >= 0f) "+" else ""}${(midtones * 100f).roundToInt()}",
    value = midtones,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(midtones = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(midtones = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_midtones_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_whites),
    valueText = "${if (whites >= 0f) "+" else ""}${(whites * 100f).roundToInt()}",
    value = whites,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(whites = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(whites = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_whites_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_blacks),
    valueText = "${if (blacks >= 0f) "+" else ""}${(blacks * 100f).roundToInt()}",
    value = blacks,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(blacks = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(blacks = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_blacks_slider",
  )

  LiftGammaGainControlPanel(
    liftX = liftX,
    liftY = liftY,
    gammaX = gammaX,
    gammaY = gammaY,
    gainX = gainX,
    gainY = gainY,
    stageParams =
      ColorGradeStageParams(
        brightness = brightness,
        exposure = exposure,
        contrast = contrast,
        saturation = saturation,
        temperature = temperature,
        tint = tint,
        shadows = shadows,
        midtones = midtones,
        highlights = highlights,
        whites = whites,
        blacks = blacks,
        vibrance = vibrance,
        liftX = liftX,
        liftY = liftY,
        gammaX = gammaX,
        gammaY = gammaY,
        gainX = gainX,
        gainY = gainY,
      ),
    onBeginEdit = onBeginEdit,
    onUpdateLift = { nx, ny, fromDrag -> updateColor(liftX = nx, liftY = ny, fromSliderDrag = fromDrag) },
    onUpdateGamma = { nx, ny, fromDrag -> updateColor(gammaX = nx, gammaY = ny, fromSliderDrag = fromDrag) },
    onUpdateGain = { nx, ny, fromDrag -> updateColor(gainX = nx, gainY = ny, fromSliderDrag = fromDrag) },
    onEndEdit = onEndEdit,
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_vignette),
    valueText = "${(vignette * 100f).roundToInt()}%",
    value = vignette,
    valueRange = 0.0f..1.0f,
    defaultValue = 0.0f,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(vignette = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(vignette = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_vignette_slider",
  )

  if (clip.kind == ClipKind.ADJUSTMENT) {
    PrecisionConsoleSlider(
      label = stringResource(R.string.label_adjustment_blend_opacity),
      valueText = "${(blendOpacity * 100f).roundToInt()}%",
      value = blendOpacity,
      valueRange = 0.0f..1.0f,
      defaultValue = 1.0f,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(blendOpacity = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = { updateColor(blendOpacity = 1.0f, fromSliderDrag = false) },
      testTag = "adjust_layer_blend_slider",
    )
  }

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  Column(
    modifier = Modifier.fillMaxWidth().testTag("depth_blur_section"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = stringResource(R.string.label_depth_blur_section),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.secondary,
    )

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_blur_intensity),
      valueText = "${(depthBlur * 100f).roundToInt()}%",
      value = depthBlur,
      valueRange = 0.0f..1.0f,
      defaultValue = 0.0f,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthBlur = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = { updateColor(depthBlur = 0.0f, fromSliderDrag = false) },
      testTag = "adjust_depth_blur_slider",
    )

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_blur_radius),
      valueText = "${depthBlurRadius.roundToInt()} px",
      value = depthBlurRadius,
      valueRange = ColorGradeBounds.MIN_DEPTH_BLUR_RADIUS..ColorGradeBounds.MAX_DEPTH_BLUR_RADIUS,
      defaultValue = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthBlurRadius = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        updateColor(depthBlurRadius = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS, fromSliderDrag = false)
      },
      testTag = "adjust_depth_radius_slider",
    )

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_blur_feather),
      valueText = "${(depthBlurFeather * 100f).roundToInt()}%",
      value = depthBlurFeather,
      valueRange = ColorGradeBounds.MIN_DEPTH_BLUR_FEATHER..ColorGradeBounds.MAX_DEPTH_BLUR_FEATHER,
      defaultValue = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthBlurFeather = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        updateColor(depthBlurFeather = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER, fromSliderDrag = false)
      },
      testTag = "adjust_depth_feather_slider",
    )

    Text(
      text = stringResource(R.string.label_depth_mask_mode),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      DepthBlurMaskMode.entries.forEach { mode ->
        val labelRes =
          when (mode) {
            DepthBlurMaskMode.RADIAL -> R.string.depth_mode_radial
            DepthBlurMaskMode.LINEAR -> R.string.depth_mode_linear
            DepthBlurMaskMode.FOREGROUND_SUBJECT -> R.string.depth_mode_foreground
          }
        FilterChip(
          selected = depthBlurMode == mode,
          onClick = { updateColor(depthBlurMode = mode, fromSliderDrag = false) },
          label = { Text(stringResource(labelRes)) },
          modifier = Modifier.testTag("depth_mode_${mode.name}"),
        )
      }
    }

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_focus_x),
      valueText = "${(depthFocusX * 100f).roundToInt()}%",
      value = depthFocusX,
      valueRange = 0.0f..1.0f,
      defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
      bipolarOrigin = true,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthFocusX = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        updateColor(depthFocusX = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD, fromSliderDrag = false)
      },
      testTag = "adjust_depth_focus_x_slider",
    )

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_focus_y),
      valueText = "${(depthFocusY * 100f).roundToInt()}%",
      value = depthFocusY,
      valueRange = 0.0f..1.0f,
      defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
      bipolarOrigin = true,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthFocusY = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        updateColor(depthFocusY = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD, fromSliderDrag = false)
      },
      testTag = "adjust_depth_focus_y_slider",
    )

    PrecisionConsoleSlider(
      label = stringResource(R.string.label_depth_focus_range),
      valueText = "${(depthFocusRange * 100f).roundToInt()}%",
      value = depthFocusRange,
      valueRange = ColorGradeBounds.MIN_DEPTH_FOCUS_RANGE..ColorGradeBounds.MAX_DEPTH_FOCUS_RANGE,
      defaultValue = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(depthFocusRange = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = {
        updateColor(depthFocusRange = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE, fromSliderDrag = false)
      },
      testTag = "adjust_depth_focus_range_slider",
    )

    if (clip.kind == ClipKind.VIDEO) {
      AssistChip(
        onClick = onAddRackFocusKeyframe,
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Diamond,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        label = { Text(stringResource(R.string.action_add_rack_focus_kf)) },
        modifier =
          Modifier
            .minimumInteractiveComponentSize()
            .testTag("depth_add_rack_focus_kf_button"),
      )
    } else {
      Text(
        text = stringResource(R.string.msg_depth_adjustment_no_kf),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  Text(
    text = stringResource(R.string.label_effects_section),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.tertiary,
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_chromatic_aberration),
    valueText = "${(chromaticAberration * 100f).roundToInt()}%",
    value = chromaticAberration,
    valueRange = 0.0f..1.0f,
    defaultValue = 0.0f,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(chromaticAberration = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(chromaticAberration = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_chromatic_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_glitch_intensity),
    valueText = "${(glitchIntensity * 100f).roundToInt()}%",
    value = glitchIntensity,
    valueRange = 0.0f..1.0f,
    defaultValue = 0.0f,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(glitchIntensity = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(glitchIntensity = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_glitch_slider",
  )

  AssistChip(
    onClick = onResetAll,
    leadingIcon = {
      Icon(
        imageVector = Icons.Default.RestartAlt,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
    },
    label = { Text(stringResource(R.string.action_reset_adjustments)) },
    modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_reset_button"),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateAudio: (
    volume: Float?,
    pan: Float?,
    fadeInMs: Long?,
    fadeOutMs: Long?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onOpenAudioSheet: () -> Unit,
) {
  val volume: Float
  val pan: Float
  val fadeInMs: Long
  val fadeOutMs: Long
  val supportsPan: Boolean

  when (clip.kind) {
    ClipKind.VIDEO -> {
      val cfg = clip.videoConfig ?: return
      volume = cfg.volume
      pan = 0.0f
      fadeInMs = cfg.fadeInMs
      fadeOutMs = cfg.fadeOutMs
      supportsPan = false
    }
    ClipKind.AUDIO -> {
      val cfg = clip.audioConfig ?: return
      volume = cfg.volume
      pan = cfg.pan
      fadeInMs = cfg.fadeInMs
      fadeOutMs = cfg.fadeOutMs
      supportsPan = true
    }
    else -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_audio))
      return
    }
  }

  val maxFadeMs = clip.durationMs.coerceIn(500L, 4_000L).toFloat()

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AssistChip(
      onClick = { onUpdateAudio(0.0f, null, null, null, false) },
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeOff,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text("0%") },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("audio_quick_mute_chip"),
    )
    AssistChip(
      onClick = { onUpdateAudio(1.0f, null, null, null, false) },
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text("100%") },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("audio_quick_unity_chip"),
    )
    AssistChip(
      onClick = onOpenAudioSheet,
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_open_audio_sheet)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_open_audio_sheet"),
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_volume),
    valueText = "${(volume * 100f).roundToInt()}%",
    value = volume,
    valueRange = 0f..2.0f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(next, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(1.0f, null, null, null, false) },
    testTag = "inspector_audio_volume_slider",
  )

  if (supportsPan) {
    val panText =
      when {
        abs(pan) < 0.03f -> "C"
        pan < 0f -> "L ${(-pan * 100f).roundToInt()}"
        else -> "R ${(pan * 100f).roundToInt()}"
      }
    PrecisionConsoleSlider(
      label = stringResource(R.string.label_audio_pan),
      valueText = panText,
      value = pan,
      valueRange = -1.0f..1.0f,
      defaultValue = 0.0f,
      bipolarOrigin = true,
      onValueChange = { next ->
        onBeginEdit()
        onUpdateAudio(null, next, null, null, true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = { onUpdateAudio(null, 0.0f, null, null, false) },
      testTag = "inspector_audio_pan_slider",
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_fade_in),
    valueText = "$fadeInMs ms",
    value = fadeInMs.toFloat(),
    valueRange = 0f..maxFadeMs,
    defaultValue = 0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(null, null, next.roundToLong(), null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(null, null, 0L, null, false) },
    testTag = "inspector_audio_fade_in_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_fade_out),
    valueText = "$fadeOutMs ms",
    value = fadeOutMs.toFloat(),
    valueRange = 0f..maxFadeMs,
    defaultValue = 0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(null, null, null, next.roundToLong(), true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(null, null, null, 0L, false) },
    testTag = "inspector_audio_fade_out_slider",
  )
}

@Composable
private fun SpeedAndSubtitlesInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateSpeed: (speed: Float, fromSliderDrag: Boolean) -> Unit,
  onUpdateSpeedCurve: (speedCurve: List<SpeedCurvePoint>, fromSliderDrag: Boolean) -> Unit,
  onApplySpeedRampPreset: (presetId: String) -> Unit,
  onTogglePreservePitch: (preservePitch: Boolean) -> Unit,
  onUpdateSubtitleExtended: (
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
  onUpdateSubtitleTiming: (newStartMs: Long, newEndMs: Long) -> Unit,
  onSplitSubtitle: () -> Unit,
  onMergeSubtitleWithNext: () -> Unit,
  onAutoGenerateSubtitles: () -> Unit,
  onOpenSubtitlesSheet: () -> Unit,
) {
  when (clip.kind) {
    ClipKind.VIDEO, ClipKind.AUDIO -> {
      SpeedCurveInspectorSection(
        clip = clip,
        onBeginEdit = onBeginEdit,
        onEndEdit = onEndEdit,
        onUpdateSpeed = onUpdateSpeed,
        onUpdateSpeedCurve = onUpdateSpeedCurve,
        onApplySpeedRampPreset = onApplySpeedRampPreset,
        onTogglePreservePitch = onTogglePreservePitch,
      )
    }

    ClipKind.SUBTITLE -> {
      SubtitleInspectorSection(
        clip = clip,
        onBeginEdit = onBeginEdit,
        onEndEdit = onEndEdit,
        onUpdateSubtitle = onUpdateSubtitleExtended,
        onUpdateTiming = onUpdateSubtitleTiming,
        onSplitSubtitle = onSplitSubtitle,
        onMergeSubtitleWithNext = onMergeSubtitleWithNext,
        onAutoGenerateSubtitles = onAutoGenerateSubtitles,
        onOpenSubtitlesSheet = onOpenSubtitlesSheet,
      )
    }

    ClipKind.ADJUSTMENT -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_speed))
    }
  }
}

@Composable
private fun InspectorUnsupportedNotice(message: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
  }
}
