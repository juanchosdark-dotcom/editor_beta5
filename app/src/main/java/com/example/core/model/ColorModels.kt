package com.example.core.model

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deterministic ordered stages of FrameLine's non-destructive color grading pipeline.
 */
enum class ColorPipelineStage {
  BASE_ADJUSTMENTS,
  LUT_APPLICATION,
  OPACITY_BLEND,
}

/**
 * Validation & deterministic clamping bounds for parametric clip and adjustment-layer color controls.
 */
object ColorGradeBounds {
  const val MIN_BRIGHTNESS = -0.5f
  const val MAX_BRIGHTNESS = 0.5f
  const val DEFAULT_BRIGHTNESS = 0.0f

  const val MIN_EXPOSURE = -1.0f
  const val MAX_EXPOSURE = 1.0f
  const val DEFAULT_EXPOSURE = 0.0f

  const val MIN_CONTRAST = 0.5f
  const val MAX_CONTRAST = 1.8f
  const val DEFAULT_CONTRAST = 1.0f

  const val MIN_SATURATION = 0.0f
  const val MAX_SATURATION = 2.0f
  const val DEFAULT_SATURATION = 1.0f

  const val MIN_VIBRANCE = -1.0f
  const val MAX_VIBRANCE = 1.0f
  const val DEFAULT_VIBRANCE = 0.0f

  const val MIN_TEMPERATURE = -1.0f
  const val MAX_TEMPERATURE = 1.0f
  const val DEFAULT_TEMPERATURE = 0.0f

  const val MIN_TINT = -1.0f
  const val MAX_TINT = 1.0f
  const val DEFAULT_TINT = 0.0f

  const val MIN_SHADOWS = -1.0f
  const val MAX_SHADOWS = 1.0f
  const val DEFAULT_SHADOWS = 0.0f

  const val MIN_MIDTONES = -1.0f
  const val MAX_MIDTONES = 1.0f
  const val DEFAULT_MIDTONES = 0.0f

  const val MIN_HIGHLIGHTS = -1.0f
  const val MAX_HIGHLIGHTS = 1.0f
  const val DEFAULT_HIGHLIGHTS = 0.0f

  const val MIN_WHITES = -1.0f
  const val MAX_WHITES = 1.0f
  const val DEFAULT_WHITES = 0.0f

  const val MIN_BLACKS = -1.0f
  const val MAX_BLACKS = 1.0f
  const val DEFAULT_BLACKS = 0.0f

  const val MIN_WHEEL_COORD = -1.0f
  const val MAX_WHEEL_COORD = 1.0f
  const val DEFAULT_WHEEL_COORD = 0.0f

  const val MIN_VIGNETTE = 0.0f
  const val MAX_VIGNETTE = 1.0f
  const val DEFAULT_VIGNETTE = 0.0f

  const val MIN_DEPTH_BLUR = 0.0f
  const val MAX_DEPTH_BLUR = 1.0f
  const val DEFAULT_DEPTH_BLUR = 0.0f

  const val MIN_DEPTH_BLUR_RADIUS = 1.0f
  const val MAX_DEPTH_BLUR_RADIUS = 25.0f
  const val DEFAULT_DEPTH_BLUR_RADIUS = 12.0f

  const val MIN_DEPTH_BLUR_FEATHER = 0.05f
  const val MAX_DEPTH_BLUR_FEATHER = 1.0f
  const val DEFAULT_DEPTH_BLUR_FEATHER = 0.35f

  const val MIN_DEPTH_FOCUS_COORD = 0.0f
  const val MAX_DEPTH_FOCUS_COORD = 1.0f
  const val DEFAULT_DEPTH_FOCUS_COORD = 0.5f

  const val MIN_DEPTH_FOCUS_RANGE = 0.05f
  const val MAX_DEPTH_FOCUS_RANGE = 0.85f
  const val DEFAULT_DEPTH_FOCUS_RANGE = 0.28f

  const val MIN_LUT_STRENGTH = 0.0f
  const val MAX_LUT_STRENGTH = 1.0f
  const val DEFAULT_LUT_STRENGTH = 1.0f

  const val MIN_BLEND_OPACITY = 0.0f
  const val MAX_BLEND_OPACITY = 1.0f
  const val DEFAULT_BLEND_OPACITY = 1.0f

  const val MIN_CHROMATIC_ABERRATION = 0.0f
  const val MAX_CHROMATIC_ABERRATION = 1.0f
  const val DEFAULT_CHROMATIC_ABERRATION = 0.0f

  const val MIN_GLITCH_INTENSITY = 0.0f
  const val MAX_GLITCH_INTENSITY = 1.0f
  const val DEFAULT_GLITCH_INTENSITY = 0.0f

  fun clampFinite(
    value: Float,
    min: Float,
    max: Float,
    defaultValue: Float,
  ): Float = (if (value.isFinite()) value else defaultValue).coerceIn(min, max)

  fun clampBrightness(value: Float): Float =
    clampFinite(value, MIN_BRIGHTNESS, MAX_BRIGHTNESS, DEFAULT_BRIGHTNESS)

  fun clampExposure(value: Float): Float =
    clampFinite(value, MIN_EXPOSURE, MAX_EXPOSURE, DEFAULT_EXPOSURE)

  fun clampContrast(value: Float): Float =
    clampFinite(value, MIN_CONTRAST, MAX_CONTRAST, DEFAULT_CONTRAST)

  fun clampSaturation(value: Float): Float =
    clampFinite(value, MIN_SATURATION, MAX_SATURATION, DEFAULT_SATURATION)

  fun clampVibrance(value: Float): Float =
    clampFinite(value, MIN_VIBRANCE, MAX_VIBRANCE, DEFAULT_VIBRANCE)

  fun clampTemperature(value: Float): Float =
    clampFinite(value, MIN_TEMPERATURE, MAX_TEMPERATURE, DEFAULT_TEMPERATURE)

  fun clampTint(value: Float): Float =
    clampFinite(value, MIN_TINT, MAX_TINT, DEFAULT_TINT)

  fun clampShadows(value: Float): Float =
    clampFinite(value, MIN_SHADOWS, MAX_SHADOWS, DEFAULT_SHADOWS)

  fun clampMidtones(value: Float): Float =
    clampFinite(value, MIN_MIDTONES, MAX_MIDTONES, DEFAULT_MIDTONES)

  fun clampHighlights(value: Float): Float =
    clampFinite(value, MIN_HIGHLIGHTS, MAX_HIGHLIGHTS, DEFAULT_HIGHLIGHTS)

  fun clampWhites(value: Float): Float =
    clampFinite(value, MIN_WHITES, MAX_WHITES, DEFAULT_WHITES)

  fun clampBlacks(value: Float): Float =
    clampFinite(value, MIN_BLACKS, MAX_BLACKS, DEFAULT_BLACKS)

  fun clampWheelCoord(value: Float): Float =
    clampFinite(value, MIN_WHEEL_COORD, MAX_WHEEL_COORD, DEFAULT_WHEEL_COORD)

  fun clampVignette(value: Float): Float =
    clampFinite(value, MIN_VIGNETTE, MAX_VIGNETTE, DEFAULT_VIGNETTE)

  fun clampDepthBlur(value: Float): Float =
    clampFinite(value, MIN_DEPTH_BLUR, MAX_DEPTH_BLUR, DEFAULT_DEPTH_BLUR)

  fun clampDepthBlurRadius(value: Float): Float =
    clampFinite(value, MIN_DEPTH_BLUR_RADIUS, MAX_DEPTH_BLUR_RADIUS, DEFAULT_DEPTH_BLUR_RADIUS)

  fun clampDepthBlurFeather(value: Float): Float =
    clampFinite(value, MIN_DEPTH_BLUR_FEATHER, MAX_DEPTH_BLUR_FEATHER, DEFAULT_DEPTH_BLUR_FEATHER)

  fun clampDepthFocusCoord(value: Float): Float =
    clampFinite(value, MIN_DEPTH_FOCUS_COORD, MAX_DEPTH_FOCUS_COORD, DEFAULT_DEPTH_FOCUS_COORD)

  fun clampDepthFocusRange(value: Float): Float =
    clampFinite(value, MIN_DEPTH_FOCUS_RANGE, MAX_DEPTH_FOCUS_RANGE, DEFAULT_DEPTH_FOCUS_RANGE)

  fun clampLutStrength(value: Float): Float =
    clampFinite(value, MIN_LUT_STRENGTH, MAX_LUT_STRENGTH, DEFAULT_LUT_STRENGTH)

  fun clampBlendOpacity(value: Float): Float =
    clampFinite(value, MIN_BLEND_OPACITY, MAX_BLEND_OPACITY, DEFAULT_BLEND_OPACITY)

  fun clampChromaticAberration(value: Float): Float =
    clampFinite(value, MIN_CHROMATIC_ABERRATION, MAX_CHROMATIC_ABERRATION, DEFAULT_CHROMATIC_ABERRATION)

  fun clampGlitchIntensity(value: Float): Float =
    clampFinite(value, MIN_GLITCH_INTENSITY, MAX_GLITCH_INTENSITY, DEFAULT_GLITCH_INTENSITY)

  fun clampCropLeft(value: Float): Float = clampFinite(value, 0.0f, 0.85f, 0.0f)

  fun clampCropTop(value: Float): Float = clampFinite(value, 0.0f, 0.85f, 0.0f)

  fun clampCropRight(value: Float, left: Float = 0.0f): Float =
    clampFinite(value, (left + 0.1f).coerceAtMost(1.0f), 1.0f, 1.0f)

  fun clampCropBottom(value: Float, top: Float = 0.0f): Float =
    clampFinite(value, (top + 0.1f).coerceAtMost(1.0f), 1.0f, 1.0f)

  fun clampVideoColor(cfg: VideoClipConfig): VideoClipConfig =
    cfg.copy(
      brightness = clampBrightness(cfg.brightness),
      contrast = clampContrast(cfg.contrast),
      saturation = clampSaturation(cfg.saturation),
      exposure = clampExposure(cfg.exposure),
      temperature = clampTemperature(cfg.temperature),
      vignette = clampVignette(cfg.vignette),
      lutPresetId = cfg.lutPresetId?.takeUnless { it.isBlank() || it == BuiltInLutPresets.ID_NONE },
      lutStrength = clampLutStrength(cfg.lutStrength),
      tint = clampTint(cfg.tint),
      shadows = clampShadows(cfg.shadows),
      midtones = clampMidtones(cfg.midtones),
      highlights = clampHighlights(cfg.highlights),
      depthBlur = clampDepthBlur(cfg.depthBlur),
      whites = clampWhites(cfg.whites),
      blacks = clampBlacks(cfg.blacks),
      vibrance = clampVibrance(cfg.vibrance),
      liftX = clampWheelCoord(cfg.liftX),
      liftY = clampWheelCoord(cfg.liftY),
      gammaX = clampWheelCoord(cfg.gammaX),
      gammaY = clampWheelCoord(cfg.gammaY),
      gainX = clampWheelCoord(cfg.gainX),
      gainY = clampWheelCoord(cfg.gainY),
      depthBlurRadius = clampDepthBlurRadius(cfg.depthBlurRadius),
      depthBlurFeather = clampDepthBlurFeather(cfg.depthBlurFeather),
      depthFocusX = clampDepthFocusCoord(cfg.depthFocusX),
      depthFocusY = clampDepthFocusCoord(cfg.depthFocusY),
      depthFocusRange = clampDepthFocusRange(cfg.depthFocusRange),
      chromaticAberration = clampChromaticAberration(cfg.chromaticAberration),
      glitchIntensity = clampGlitchIntensity(cfg.glitchIntensity),
      cropLeft = clampCropLeft(cfg.cropLeft),
      cropTop = clampCropTop(cfg.cropTop),
      cropRight = clampCropRight(cfg.cropRight, clampCropLeft(cfg.cropLeft)),
      cropBottom = clampCropBottom(cfg.cropBottom, clampCropTop(cfg.cropTop)),
    )

  fun clampAdjustmentColor(cfg: AdjustmentClipConfig): AdjustmentClipConfig =
    cfg.copy(
      brightness = clampBrightness(cfg.brightness),
      contrast = clampContrast(cfg.contrast),
      saturation = clampSaturation(cfg.saturation),
      exposure = clampExposure(cfg.exposure),
      temperature = clampTemperature(cfg.temperature),
      vignette = clampVignette(cfg.vignette),
      lutPresetId = cfg.lutPresetId?.takeUnless { it.isBlank() || it == BuiltInLutPresets.ID_NONE },
      lutStrength = clampLutStrength(cfg.lutStrength),
      tint = clampTint(cfg.tint),
      shadows = clampShadows(cfg.shadows),
      midtones = clampMidtones(cfg.midtones),
      highlights = clampHighlights(cfg.highlights),
      blendOpacity = clampBlendOpacity(cfg.blendOpacity),
      depthBlur = clampDepthBlur(cfg.depthBlur),
      whites = clampWhites(cfg.whites),
      blacks = clampBlacks(cfg.blacks),
      vibrance = clampVibrance(cfg.vibrance),
      liftX = clampWheelCoord(cfg.liftX),
      liftY = clampWheelCoord(cfg.liftY),
      gammaX = clampWheelCoord(cfg.gammaX),
      gammaY = clampWheelCoord(cfg.gammaY),
      gainX = clampWheelCoord(cfg.gainX),
      gainY = clampWheelCoord(cfg.gainY),
      depthBlurRadius = clampDepthBlurRadius(cfg.depthBlurRadius),
      depthBlurFeather = clampDepthBlurFeather(cfg.depthBlurFeather),
      depthFocusX = clampDepthFocusCoord(cfg.depthFocusX),
      depthFocusY = clampDepthFocusCoord(cfg.depthFocusY),
      depthFocusRange = clampDepthFocusRange(cfg.depthFocusRange),
      chromaticAberration = clampChromaticAberration(cfg.chromaticAberration),
      glitchIntensity = clampGlitchIntensity(cfg.glitchIntensity),
    )
}

/**
 * Resolved parametric grading values for a single stage or layer, already clamped and blend-scaled.
 */
data class ColorGradeStageParams(
  val brightness: Float = 0.0f,
  val exposure: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val temperature: Float = 0.0f,
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
  val vignette: Float = 0.0f,
  val activeLut: LutPreset? = null,
  val lutStrength: Float = 0.0f,
  val blendOpacity: Float = 1.0f,
  val depthBlur: Float = 0.0f,
  val whites: Float = 0.0f,
  val blacks: Float = 0.0f,
  val vibrance: Float = 0.0f,
  val liftX: Float = 0.0f,
  val liftY: Float = 0.0f,
  val gammaX: Float = 0.0f,
  val gammaY: Float = 0.0f,
  val gainX: Float = 0.0f,
  val gainY: Float = 0.0f,
  val depthBlurRadius: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
  val depthBlurFeather: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
  val depthBlurMode: DepthBlurMaskMode = DepthBlurMaskMode.RADIAL,
  val depthFocusX: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusY: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusRange: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
  val chromaticAberration: Float = 0.0f,
  val glitchIntensity: Float = 0.0f,
) {
  val netBrightnessAndExposure: Float
    get() = (brightness + exposure * 0.35f).coerceIn(-1.0f, 1.0f)

  val hasScalarBaseAdjustments: Boolean
    get() =
      abs(netBrightnessAndExposure) > 0.005f ||
        abs(contrast - 1.0f) > 0.005f ||
        abs(saturation - 1.0f) > 0.005f ||
        abs(temperature) > 0.005f ||
        abs(tint) > 0.005f

  val hasToneWheelAdjustments: Boolean
    get() =
      abs(shadows) > 0.005f ||
        abs(midtones) > 0.005f ||
        abs(highlights) > 0.005f ||
        abs(whites) > 0.005f ||
        abs(blacks) > 0.005f ||
        abs(vibrance) > 0.005f ||
        abs(liftX) > 0.005f ||
        abs(liftY) > 0.005f ||
        abs(gammaX) > 0.005f ||
        abs(gammaY) > 0.005f ||
        abs(gainX) > 0.005f ||
        abs(gainY) > 0.005f

  val hasLutApplication: Boolean
    get() =
      activeLut != null &&
        activeLut.id != BuiltInLutPresets.ID_NONE &&
        lutStrength > 0.005f
}

/**
 * Real-time luminance histogram + tonal balance summary rendered in the viewer scopes HUD.
 */
data class ColorScopeSnapshot(
  val lumaBins: List<Float>,
  val shadowsPct: Int,
  val midtonesPct: Int,
  val highlightsPct: Int,
  val meanRed: Float,
  val meanGreen: Float,
  val meanBlue: Float,
)

/**
 * Deterministic representation of the complete ordered color pipeline:
 * 1) Base parametric adjustments (clip + blend-scaled adjustment base)
 * 2) LUT application (clip LUT + blend-scaled adjustment LUT)
 * 3) Opacity / blend (clip opacity and layer blend opacity)
 */
data class ResolvedColorPipeline(
  val orderedStages: List<ColorPipelineStage> =
    listOf(
      ColorPipelineStage.BASE_ADJUSTMENTS,
      ColorPipelineStage.LUT_APPLICATION,
      ColorPipelineStage.OPACITY_BLEND,
    ),
  val clipBaseStage: ColorGradeStageParams = ColorGradeStageParams(),
  val clipLutStage: ColorGradeStageParams = ColorGradeStageParams(),
  val adjustmentBaseStage: ColorGradeStageParams? = null,
  val adjustmentLutStage: ColorGradeStageParams? = null,
  val clipOpacity: Float = 1.0f,
  val combinedGrade: EffectiveColorGrading = EffectiveColorGrading(),
)

/**
 * Deterministic CPU + 3D-LUT color & depth math shared by preview, standby UI monitor, and export.
 */
object ColorGradingEngine {

  fun extractClipBaseStage(cfg: VideoClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampVideoColor(cfg)
    return ColorGradeStageParams(
      brightness = clean.brightness,
      exposure = clean.exposure,
      contrast = clean.contrast,
      saturation = clean.saturation,
      temperature = clean.temperature,
      tint = clean.tint,
      shadows = clean.shadows,
      midtones = clean.midtones,
      highlights = clean.highlights,
      vignette = clean.vignette,
      blendOpacity = 1.0f,
      depthBlur = clean.depthBlur,
      whites = clean.whites,
      blacks = clean.blacks,
      vibrance = clean.vibrance,
      liftX = clean.liftX,
      liftY = clean.liftY,
      gammaX = clean.gammaX,
      gammaY = clean.gammaY,
      gainX = clean.gainX,
      gainY = clean.gainY,
      depthBlurRadius = clean.depthBlurRadius,
      depthBlurFeather = clean.depthBlurFeather,
      depthBlurMode = clean.depthBlurMode,
      depthFocusX = clean.depthFocusX,
      depthFocusY = clean.depthFocusY,
      depthFocusRange = clean.depthFocusRange,
      chromaticAberration = clean.chromaticAberration,
      glitchIntensity = clean.glitchIntensity,
    )
  }

  fun extractClipLutStage(
    cfg: VideoClipConfig,
    customPresets: List<LutPreset> = emptyList(),
  ): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampVideoColor(cfg)
    val preset =
      BuiltInLutPresets.findById(clean.lutPresetId, customPresets)
        ?.takeIf { it.id != BuiltInLutPresets.ID_NONE }
    val strength = if (preset != null) clean.lutStrength else 0.0f
    return ColorGradeStageParams(
      activeLut = preset,
      lutStrength = strength,
      blendOpacity = 1.0f,
    )
  }

  fun extractAdjustmentBaseStage(cfg: AdjustmentClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampAdjustmentColor(cfg)
    val op = clean.blendOpacity
    return ColorGradeStageParams(
      brightness = clean.brightness * op,
      exposure = clean.exposure * op,
      contrast = 1.0f + (clean.contrast - 1.0f) * op,
      saturation = 1.0f + (clean.saturation - 1.0f) * op,
      temperature = clean.temperature * op,
      tint = clean.tint * op,
      shadows = clean.shadows * op,
      midtones = clean.midtones * op,
      highlights = clean.highlights * op,
      vignette = clean.vignette * op,
      blendOpacity = op,
      depthBlur = clean.depthBlur * op,
      whites = clean.whites * op,
      blacks = clean.blacks * op,
      vibrance = clean.vibrance * op,
      liftX = clean.liftX * op,
      liftY = clean.liftY * op,
      gammaX = clean.gammaX * op,
      gammaY = clean.gammaY * op,
      gainX = clean.gainX * op,
      gainY = clean.gainY * op,
      depthBlurRadius = clean.depthBlurRadius,
      depthBlurFeather = clean.depthBlurFeather,
      depthBlurMode = clean.depthBlurMode,
      depthFocusX = clean.depthFocusX,
      depthFocusY = clean.depthFocusY,
      depthFocusRange = clean.depthFocusRange,
      chromaticAberration = clean.chromaticAberration * op,
      glitchIntensity = clean.glitchIntensity * op,
    )
  }

  fun extractAdjustmentLutStage(
    cfg: AdjustmentClipConfig,
    customPresets: List<LutPreset> = emptyList(),
  ): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampAdjustmentColor(cfg)
    val op = clean.blendOpacity
    val preset =
      BuiltInLutPresets.findById(clean.lutPresetId, customPresets)
        ?.takeIf { it.id != BuiltInLutPresets.ID_NONE }
    val effectiveStrength = if (preset != null) (clean.lutStrength * op).coerceIn(0f, 1f) else 0f
    return ColorGradeStageParams(
      activeLut = preset,
      lutStrength = effectiveStrength,
      blendOpacity = op,
    )
  }

  fun buildPipeline(
    videoCfg: VideoClipConfig?,
    adjustmentCfg: AdjustmentClipConfig?,
    evaluatedClipOpacity: Float? = null,
    customPresets: List<LutPreset> = emptyList(),
  ): ResolvedColorPipeline {
    val clipBase = videoCfg?.let(::extractClipBaseStage) ?: ColorGradeStageParams()
    val clipLut = videoCfg?.let { extractClipLutStage(it, customPresets) } ?: ColorGradeStageParams()
    val adjBase = adjustmentCfg?.let(::extractAdjustmentBaseStage)
    val adjLut = adjustmentCfg?.let { extractAdjustmentLutStage(it, customPresets) }
    val finalOpacity =
      ColorGradeBounds.clampFinite(
        evaluatedClipOpacity ?: videoCfg?.opacity ?: 1.0f,
        0.0f,
        1.0f,
        1.0f,
      )

    val resolvedClip = videoCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it, customPresets) }
    val resolvedAdj = adjustmentCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it, customPresets) }

    val combinedBrightness =
      ((resolvedClip?.brightness ?: 0f) + (resolvedAdj?.brightness ?: 0f)).coerceIn(-0.8f, 0.8f)
    val combinedExposure =
      ((resolvedClip?.exposure ?: 0f) + (resolvedAdj?.exposure ?: 0f)).coerceIn(-1.2f, 1.2f)
    val combinedContrast =
      ((resolvedClip?.contrast ?: 1f) * (resolvedAdj?.contrast ?: 1f)).coerceIn(0.3f, 2.0f)
    val combinedSaturation =
      ((resolvedClip?.saturation ?: 1f) * (resolvedAdj?.saturation ?: 1f)).coerceIn(0.0f, 2.2f)
    val combinedTemp =
      ((resolvedClip?.temperature ?: 0f) + (resolvedAdj?.temperature ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedTint =
      ((resolvedClip?.tint ?: 0f) + (resolvedAdj?.tint ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedShadows =
      ((resolvedClip?.shadows ?: 0f) + (resolvedAdj?.shadows ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedMidtones =
      ((resolvedClip?.midtones ?: 0f) + (resolvedAdj?.midtones ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedHighlights =
      ((resolvedClip?.highlights ?: 0f) + (resolvedAdj?.highlights ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedWhites =
      ((resolvedClip?.whites ?: 0f) + (resolvedAdj?.whites ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedBlacks =
      ((resolvedClip?.blacks ?: 0f) + (resolvedAdj?.blacks ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedVibrance =
      ((resolvedClip?.vibrance ?: 0f) + (resolvedAdj?.vibrance ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedLiftX =
      ((resolvedClip?.liftX ?: 0f) + (resolvedAdj?.liftX ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedLiftY =
      ((resolvedClip?.liftY ?: 0f) + (resolvedAdj?.liftY ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedGammaX =
      ((resolvedClip?.gammaX ?: 0f) + (resolvedAdj?.gammaX ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedGammaY =
      ((resolvedClip?.gammaY ?: 0f) + (resolvedAdj?.gammaY ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedGainX =
      ((resolvedClip?.gainX ?: 0f) + (resolvedAdj?.gainX ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedGainY =
      ((resolvedClip?.gainY ?: 0f) + (resolvedAdj?.gainY ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedVignette =
      ((resolvedClip?.vignette ?: 0f) + (resolvedAdj?.vignette ?: 0f)).coerceIn(0.0f, 1.0f)
    val combinedDepthBlur =
      ((resolvedClip?.depthBlur ?: 0f) + (resolvedAdj?.depthBlur ?: 0f)).coerceIn(0.0f, 1.0f)
    val combinedChromatic =
      ((resolvedClip?.chromaticAberration ?: 0f) + (resolvedAdj?.chromaticAberration ?: 0f)).coerceIn(0.0f, 1.0f)
    val combinedGlitch =
      ((resolvedClip?.glitchIntensity ?: 0f) + (resolvedAdj?.glitchIntensity ?: 0f)).coerceIn(0.0f, 1.0f)

    val useAdjDepth = (resolvedAdj?.depthBlur ?: 0f) > 0.005f
    val depthSource = if (useAdjDepth) resolvedAdj else resolvedClip

    val combinedEffective =
      EffectiveColorGrading(
        brightness = combinedBrightness,
        contrast = combinedContrast,
        saturation = combinedSaturation,
        exposure = combinedExposure,
        temperature = combinedTemp,
        vignette = combinedVignette,
        activeLut = resolvedAdj?.activeLut ?: resolvedClip?.activeLut,
        lutStrength = resolvedAdj?.lutStrength ?: resolvedClip?.lutStrength ?: 0.0f,
        tint = combinedTint,
        shadows = combinedShadows,
        midtones = combinedMidtones,
        highlights = combinedHighlights,
        blendOpacity = finalOpacity,
        depthBlur = combinedDepthBlur,
        whites = combinedWhites,
        blacks = combinedBlacks,
        vibrance = combinedVibrance,
        liftX = combinedLiftX,
        liftY = combinedLiftY,
        gammaX = combinedGammaX,
        gammaY = combinedGammaY,
        gainX = combinedGainX,
        gainY = combinedGainY,
        depthBlurRadius = depthSource?.depthBlurRadius ?: ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
        depthBlurFeather = depthSource?.depthBlurFeather ?: ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
        depthBlurMode = depthSource?.depthBlurMode ?: DepthBlurMaskMode.RADIAL,
        depthFocusX = depthSource?.depthFocusX ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
        depthFocusY = depthSource?.depthFocusY ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
        depthFocusRange = depthSource?.depthFocusRange ?: ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
        chromaticAberration = combinedChromatic,
        glitchIntensity = combinedGlitch,
      )

    return ResolvedColorPipeline(
      clipBaseStage = clipBase,
      clipLutStage = clipLut,
      adjustmentBaseStage = adjBase,
      adjustmentLutStage = adjLut,
      clipOpacity = finalOpacity,
      combinedGrade = combinedEffective,
    )
  }

  /**
   * Computes Stage 1 RGB transformations:
   * Exposure/Brightness -> Contrast -> Temp/Tint -> Saturation -> Vibrance -> Whites/Blacks -> Lift/Gamma/Gain (3-way).
   */
  fun applyBaseAdjustmentsToRgb(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    stage: ColorGradeStageParams,
  ): Triple<Double, Double, Double> {
    var r = rIn.coerceIn(0.0, 1.0)
    var g = gIn.coerceIn(0.0, 1.0)
    var b = bIn.coerceIn(0.0, 1.0)

    // 1. Exposure + Brightness
    val exposureGain = 2.0.pow(stage.exposure.toDouble() * 0.55)
    val brightnessShift = stage.brightness.toDouble()
    r = (r * exposureGain + brightnessShift).coerceIn(0.0, 1.0)
    g = (g * exposureGain + brightnessShift).coerceIn(0.0, 1.0)
    b = (b * exposureGain + brightnessShift).coerceIn(0.0, 1.0)

    // 2. Contrast around neutral mid-grey pivot 0.5
    val c = stage.contrast.toDouble()
    if (abs(c - 1.0) > 1e-5) {
      r = ((r - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
      g = ((g - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
      b = ((b - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
    }

    // 3. White balance: Temperature (Warm/Cool) & Tint (Green/Magenta)
    val tempShift = stage.temperature.toDouble() * 0.18
    val tintShift = stage.tint.toDouble() * 0.16
    val rScale = (1.0 + tempShift + tintShift * 0.5).coerceAtLeast(0.2)
    val gScale = (1.0 - tintShift).coerceAtLeast(0.2)
    val bScale = (1.0 - tempShift + tintShift * 0.5).coerceAtLeast(0.2)
    r = (r * rScale).coerceIn(0.0, 1.0)
    g = (g * gScale).coerceIn(0.0, 1.0)
    b = (b * bScale).coerceIn(0.0, 1.0)

    // 4. Saturation (Rec.709 luminance)
    val sat = stage.saturation.toDouble()
    if (abs(sat - 1.0) > 1e-5) {
      val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
      r = (luma + (r - luma) * sat).coerceIn(0.0, 1.0)
      g = (luma + (g - luma) * sat).coerceIn(0.0, 1.0)
      b = (luma + (b - luma) * sat).coerceIn(0.0, 1.0)
    }

    // 5. Vibrance + Whites + Blacks
    val afterExt =
      applyWhitesBlacksAndVibrance(
        rIn = r,
        gIn = g,
        bIn = b,
        whites = stage.whites.toDouble(),
        blacks = stage.blacks.toDouble(),
        vibrance = stage.vibrance.toDouble(),
      )
    r = afterExt.first
    g = afterExt.second
    b = afterExt.third

    // 6. Tonal balance & 3-way wheels: Lift (Shadows) -> Gain (Highlights) -> Gamma (Midtones)
    return applyLiftGammaGain(
      rIn = r,
      gIn = g,
      bIn = b,
      shadows = stage.shadows.toDouble(),
      midtones = stage.midtones.toDouble(),
      highlights = stage.highlights.toDouble(),
      liftX = stage.liftX.toDouble(),
      liftY = stage.liftY.toDouble(),
      gammaX = stage.gammaX.toDouble(),
      gammaY = stage.gammaY.toDouble(),
      gainX = stage.gainX.toDouble(),
      gainY = stage.gainY.toDouble(),
    )
  }

  /**
   * Selective Vibrance (boosts less-saturated hues while protecting skin/already-saturated colors)
   * paired with black-point (`blacks`) and white-point (`whites`) endpoint anchoring.
   */
  fun applyWhitesBlacksAndVibrance(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    whites: Double,
    blacks: Double,
    vibrance: Double,
  ): Triple<Double, Double, Double> {
    var r = rIn.coerceIn(0.0, 1.0)
    var g = gIn.coerceIn(0.0, 1.0)
    var b = bIn.coerceIn(0.0, 1.0)

    if (abs(vibrance) > 1e-5) {
      val maxC = max(r, max(g, b))
      val minC = min(r, min(g, b))
      val currentSat = (maxC - minC).coerceIn(0.0, 1.0)
      val avg = (r + g + b) / 3.0
      val factor = vibrance * (1.0 - currentSat) * 0.85
      r = (r + (r - avg) * factor).coerceIn(0.0, 1.0)
      g = (g + (g - avg) * factor).coerceIn(0.0, 1.0)
      b = (b + (b - avg) * factor).coerceIn(0.0, 1.0)
    }

    if (abs(blacks) > 1e-5 || abs(whites) > 1e-5) {
      val blackOffset = blacks * 0.14
      val whiteGain = 1.0 + whites * 0.22

      fun mapEndpoint(v: Double): Double {
        val toeWeight = (1.0 - v).pow(2.0)
        val shoulderWeight = v.pow(2.0)
        val afterBlack = v + blackOffset * toeWeight
        val afterWhite = afterBlack * (1.0 + (whiteGain - 1.0) * shoulderWeight)
        return afterWhite.coerceIn(0.0, 1.0)
      }

      r = mapEndpoint(r)
      g = mapEndpoint(g)
      b = mapEndpoint(b)
    }

    return Triple(r, g, b)
  }

  /**
   * Converts 2D color wheel coordinates `(x, y)` in `[-1, 1]` into balanced RGB offsets summing near 0:
   * `+x` leans Warm/Red-Amber, `-x` leans Cyan/Blue, `+y` leans Magenta/Red, `-y` leans Green/Emerald.
   */
  private fun wheelOffsetsRgb(x: Double, y: Double): Triple<Double, Double, Double> {
    val cx = x.coerceIn(-1.0, 1.0)
    val cy = y.coerceIn(-1.0, 1.0)
    val dr = (0.65 * cx + 0.35 * cy).coerceIn(-1.0, 1.0)
    val dg = (-0.15 * cx - 0.60 * cy).coerceIn(-1.0, 1.0)
    val db = (-0.65 * cx + 0.35 * cy).coerceIn(-1.0, 1.0)
    return Triple(dr, dg, db)
  }

  /**
   * Monotonic Lift / Gamma / Gain tonal & 3-way color wheel curve
   * (`0,0` identity when all parameters are `0.0`).
   */
  fun applyLiftGammaGain(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    shadows: Double,
    midtones: Double,
    highlights: Double,
    liftX: Double = 0.0,
    liftY: Double = 0.0,
    gammaX: Double = 0.0,
    gammaY: Double = 0.0,
    gainX: Double = 0.0,
    gainY: Double = 0.0,
  ): Triple<Double, Double, Double> {
    val hasWheels =
      abs(liftX) > 1e-5 ||
        abs(liftY) > 1e-5 ||
        abs(gammaX) > 1e-5 ||
        abs(gammaY) > 1e-5 ||
        abs(gainX) > 1e-5 ||
        abs(gainY) > 1e-5
    if (abs(shadows) <= 1e-5 && abs(midtones) <= 1e-5 && abs(highlights) <= 1e-5 && !hasWheels) {
      return Triple(rIn.coerceIn(0.0, 1.0), gIn.coerceIn(0.0, 1.0), bIn.coerceIn(0.0, 1.0))
    }

    val (liftR, liftG, liftB) = wheelOffsetsRgb(liftX, liftY)
    val (gammaR, gammaG, gammaB) = wheelOffsetsRgb(gammaX, gammaY)
    val (gainR, gainG, gainB) = wheelOffsetsRgb(gainX, gainY)

    val baseLift = shadows * 0.25
    val baseGain = 1.0 + highlights * 0.35
    val baseGammaExp = 2.0.pow(-midtones * 0.65)

    fun mapChannel(x: Double, wLift: Double, wGamma: Double, wGain: Double): Double {
      val clamped = x.coerceIn(0.0, 1.0)
      val channelLift = baseLift + wLift * 0.16
      val channelGain = (baseGain + wGain * 0.28).coerceAtLeast(0.2)
      val channelGammaExp = (baseGammaExp * 2.0.pow(-wGamma * 0.42)).coerceIn(0.25, 3.5)
      val lifted = (clamped + channelLift * (1.0 - clamped)).coerceIn(0.0, 1.0)
      val gained = (lifted * channelGain).coerceIn(0.0, 1.0)
      return gained.pow(channelGammaExp).coerceIn(0.0, 1.0)
    }

    return Triple(
      mapChannel(rIn, liftR, gammaR, gainR),
      mapChannel(gIn, liftG, gammaG, gainG),
      mapChannel(bIn, liftB, gammaB, gainB),
    )
  }

  /**
   * Applies a [LutPreset] look (parametric grading + 3-way shadow/midtone/highlight split-toning)
   * mixed linearly with the input pixel according to [strength] in `[0, 1]`.
   */
  fun applyLutToRgb(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    preset: LutPreset?,
    strength: Double,
  ): Triple<Double, Double, Double> {
    if (preset == null || preset.id == BuiltInLutPresets.ID_NONE || strength <= 1e-5) {
      return Triple(rIn.coerceIn(0.0, 1.0), gIn.coerceIn(0.0, 1.0), bIn.coerceIn(0.0, 1.0))
    }
    val safeStrength = strength.coerceIn(0.0, 1.0)
    val presetStage =
      ColorGradeStageParams(
        brightness = preset.brightness,
        exposure = preset.exposure,
        contrast = preset.contrast,
        saturation = preset.saturation,
        temperature = preset.temperature,
        tint = preset.tint,
        shadows = preset.shadows,
        midtones = preset.midtones,
        highlights = preset.highlights,
        whites = preset.whites,
        blacks = preset.blacks,
        vibrance = preset.vibrance,
        liftX = preset.liftX,
        liftY = preset.liftY,
        gammaX = preset.gammaX,
        gammaY = preset.gammaY,
        gainX = preset.gainX,
        gainY = preset.gainY,
      )
    val (gradedR, gradedG, gradedB) = applyBaseAdjustmentsToRgb(rIn, gIn, bIn, presetStage)

    // Subtle 3-way shadow / midtone / highlight chromatic split-tone from LUT palette
    val luma = (0.2126 * gradedR + 0.7152 * gradedG + 0.0722 * gradedB).coerceIn(0.0, 1.0)
    val wShadow = (1.0 - luma) * (1.0 - luma)
    val wHighlight = luma * luma
    val wMid = (1.0 - wShadow - wHighlight).coerceAtLeast(0.0)

    fun extractTintOffset(argb: Long): Triple<Double, Double, Double> {
      val tr = ((argb shr 16) and 0xFF) / 255.0 - 0.5
      val tg = ((argb shr 8) and 0xFF) / 255.0 - 0.5
      val tb = (argb and 0xFF) / 255.0 - 0.5
      return Triple(tr, tg, tb)
    }

    val (sr, sg, sb) = extractTintOffset(preset.shadowColorArgb)
    val (mr, mg, mb) = extractTintOffset(preset.midtoneColorArgb)
    val (hr, hg, hb) = extractTintOffset(preset.highlightColorArgb)

    val splitWeight = if (preset.isCustom) 0.0 else 0.14
    val lutR = (gradedR + splitWeight * (wShadow * sr + wMid * mr + wHighlight * hr)).coerceIn(0.0, 1.0)
    val lutG = (gradedG + splitWeight * (wShadow * sg + wMid * mg + wHighlight * hg)).coerceIn(0.0, 1.0)
    val lutB = (gradedB + splitWeight * (wShadow * sb + wMid * mb + wHighlight * hb)).coerceIn(0.0, 1.0)

    return Triple(
      (rIn * (1.0 - safeStrength) + lutR * safeStrength).coerceIn(0.0, 1.0),
      (gIn * (1.0 - safeStrength) + lutG * safeStrength).coerceIn(0.0, 1.0),
      (bIn * (1.0 - safeStrength) + lutB * safeStrength).coerceIn(0.0, 1.0),
    )
  }

  /**
   * Evaluates a single `[0..1]` RGB sample deterministically through the complete ordered pipeline:
   * `BASE_ADJUSTMENTS -> LUT_APPLICATION -> OPACITY_BLEND`.
   */
  fun evaluatePixelThroughPipeline(
    rIn: Float,
    gIn: Float,
    bIn: Float,
    pipeline: ResolvedColorPipeline,
  ): FloatArray {
    // Stage 1: Clip Base Adjustments
    var (r, g, b) = applyBaseAdjustmentsToRgb(rIn.toDouble(), gIn.toDouble(), bIn.toDouble(), pipeline.clipBaseStage)

    // Stage 2: Clip LUT
    if (pipeline.clipLutStage.hasLutApplication) {
      val afterClipLut =
        applyLutToRgb(r, g, b, pipeline.clipLutStage.activeLut, pipeline.clipLutStage.lutStrength.toDouble())
      r = afterClipLut.first
      g = afterClipLut.second
      b = afterClipLut.third
    }

    // Adjustment layer (Base -> LUT, already scaled by adjustment.blendOpacity)
    pipeline.adjustmentBaseStage?.let { adjBase ->
      val afterAdjBase = applyBaseAdjustmentsToRgb(r, g, b, adjBase)
      r = afterAdjBase.first
      g = afterAdjBase.second
      b = afterAdjBase.third
    }
    pipeline.adjustmentLutStage?.let { adjLut ->
      if (adjLut.hasLutApplication) {
        val afterAdjLut = applyLutToRgb(r, g, b, adjLut.activeLut, adjLut.lutStrength.toDouble())
        r = afterAdjLut.first
        g = afterAdjLut.second
        b = afterAdjLut.third
      }
    }

    // Stage 3: Opacity / Blend
    val op = pipeline.clipOpacity.toDouble().coerceIn(0.0, 1.0)
    r = (r * op).coerceIn(0.0, 1.0)
    g = (g * op).coerceIn(0.0, 1.0)
    b = (b * op).coerceIn(0.0, 1.0)

    return floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), op.toFloat())
  }

  /**
   * Generates a 3D LUT cube `[R][G][B]` compatible with Media3 `SingleColorLut.createFromCube(...)`
   * for Lift/Gamma/Gain + Whites/Blacks/Vibrance + LUT application in strict stage order.
   */
  fun buildStage3dLutCube(
    size: Int = 17,
    toneStage: ColorGradeStageParams? = null,
    lutStage: ColorGradeStageParams? = null,
  ): Array<Array<IntArray>> {
    val safeSize = size.coerceIn(4, 33)
    val denom = (safeSize - 1).toDouble()
    return Array(safeSize) { rIdx ->
      val r0 = rIdx / denom
      Array(safeSize) { gIdx ->
        val g0 = gIdx / denom
        IntArray(safeSize) { bIdx ->
          val b0 = bIdx / denom
          var r = r0
          var g = g0
          var b = b0

          if (toneStage != null && toneStage.hasToneWheelAdjustments) {
            val afterExt =
              applyWhitesBlacksAndVibrance(
                rIn = r,
                gIn = g,
                bIn = b,
                whites = toneStage.whites.toDouble(),
                blacks = toneStage.blacks.toDouble(),
                vibrance = toneStage.vibrance.toDouble(),
              )
            val afterTone =
              applyLiftGammaGain(
                rIn = afterExt.first,
                gIn = afterExt.second,
                bIn = afterExt.third,
                shadows = toneStage.shadows.toDouble(),
                midtones = toneStage.midtones.toDouble(),
                highlights = toneStage.highlights.toDouble(),
                liftX = toneStage.liftX.toDouble(),
                liftY = toneStage.liftY.toDouble(),
                gammaX = toneStage.gammaX.toDouble(),
                gammaY = toneStage.gammaY.toDouble(),
                gainX = toneStage.gainX.toDouble(),
                gainY = toneStage.gainY.toDouble(),
              )
            r = afterTone.first
            g = afterTone.second
            b = afterTone.third
          }

          if (lutStage != null && lutStage.hasLutApplication) {
            val afterLut = applyLutToRgb(r, g, b, lutStage.activeLut, lutStage.lutStrength.toDouble())
            r = afterLut.first
            g = afterLut.second
            b = afterLut.third
          }

          Color.rgb(
            (r * 255.0).roundToInt().coerceIn(0, 255),
            (g * 255.0).roundToInt().coerceIn(0, 255),
            (b * 255.0).roundToInt().coerceIn(0, 255),
          )
        }
      }
    }
  }

  /**
   * Computes deterministic depth-blur mask weight in `[0.0, 1.0]` (`0` = sharp focal zone, `1` = full optical bokeh)
   * matching the GLSL selective lens-blur shader across proxy preview and 1080p export.
   */
  fun computeDepthBlurMaskWeight(
    u: Float,
    v: Float,
    focusX: Float,
    focusY: Float,
    focusRange: Float,
    feather: Float,
    mode: DepthBlurMaskMode,
    subjectSimilarity: Float = 0.0f,
  ): Float {
    val safeU = u.coerceIn(0f, 1f)
    val safeV = v.coerceIn(0f, 1f)
    val fx = ColorGradeBounds.clampDepthFocusCoord(focusX)
    val fy = ColorGradeBounds.clampDepthFocusCoord(focusY)
    val range = ColorGradeBounds.clampDepthFocusRange(focusRange)
    val safeFeather = ColorGradeBounds.clampDepthBlurFeather(feather)

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
      if (edge1 <= edge0 + 1e-5f) return if (x >= edge1) 1f else 0f
      val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
      return t * t * (3f - 2f * t)
    }

    return when (mode) {
      DepthBlurMaskMode.RADIAL -> {
        val dist = hypot((safeU - fx).toDouble(), (safeV - fy).toDouble()).toFloat()
        smoothstep(range, range + safeFeather * 0.55f, dist)
      }
      DepthBlurMaskMode.LINEAR -> {
        val dist = abs(safeV - fy)
        val band = range * 0.8f
        smoothstep(band, band + safeFeather * 0.5f, dist)
      }
      DepthBlurMaskMode.FOREGROUND_SUBJECT -> {
        val dist = hypot((safeU - fx).toDouble(), (safeV - fy).toDouble()).toFloat()
        val spatial = smoothstep(range * 0.85f, range * 0.85f + safeFeather * 0.55f, dist)
        val sim = subjectSimilarity.coerceIn(0f, 1f)
        // High foreground similarity near focus center keeps the subject crisp even along edges.
        (spatial * (1f - sim * 0.72f)).coerceIn(0f, 1f)
      }
    }
  }

  /**
   * Computes a normalized 24-bin Rec.709 luminance histogram & tonal balance percentages
   * (`Sombras / Medios / Luces`) for real-time viewer scopes and A/B comparator inspection.
   */
  fun computeColorScopes(
    pipeline: ResolvedColorPipeline,
    bypass: Boolean = false,
    binCount: Int = 24,
  ): ColorScopeSnapshot {
    val safeBins = binCount.coerceIn(8, 64)
    val counts = IntArray(safeBins)
    var shadowsCount = 0
    var midtonesCount = 0
    var highlightsCount = 0
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var total = 0

    // Balanced 6x6x2 luminance + chroma test ramp covering shadows, skin/midtones, sky/highlights.
    val steps = 6
    for (iy in 0 until steps) {
      val baseLuma = (iy + 0.5f) / steps.toFloat()
      for (ix in 0 until steps) {
        val hueBias = (ix.toFloat() / (steps - 1)) - 0.5f
        val r0 = (baseLuma + hueBias * 0.22f).coerceIn(0.02f, 0.98f)
        val g0 = (baseLuma - abs(hueBias) * 0.06f).coerceIn(0.02f, 0.98f)
        val b0 = (baseLuma - hueBias * 0.22f).coerceIn(0.02f, 0.98f)

        val rgb =
          if (bypass) {
            floatArrayOf(r0, g0, b0, 1.0f)
          } else {
            evaluatePixelThroughPipeline(r0, g0, b0, pipeline)
          }

        val r = rgb[0].coerceIn(0f, 1f)
        val g = rgb[1].coerceIn(0f, 1f)
        val b = rgb[2].coerceIn(0f, 1f)
        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
        val binIdx = (luma * safeBins).toInt().coerceIn(0, safeBins - 1)
        counts[binIdx]++

        when {
          luma < 0.28f -> shadowsCount++
          luma <= 0.72f -> midtonesCount++
          else -> highlightsCount++
        }
        sumR += r
        sumG += g
        sumB += b
        total++
      }
    }

    val maxCount = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val normalizedBins = counts.map { it.toFloat() / maxCount.toFloat() }
    val safeTotal = total.coerceAtLeast(1)
    val sPct = ((shadowsCount * 100f) / safeTotal).roundToInt()
    val mPct = ((midtonesCount * 100f) / safeTotal).roundToInt()
    val hPct = (100 - sPct - mPct).coerceAtLeast(0)

    return ColorScopeSnapshot(
      lumaBins = normalizedBins,
      shadowsPct = sPct,
      midtonesPct = mPct,
      highlightsPct = hPct,
      meanRed = (sumR / safeTotal).toFloat(),
      meanGreen = (sumG / safeTotal).toFloat(),
      meanBlue = (sumB / safeTotal).toFloat(),
    )
  }
}
