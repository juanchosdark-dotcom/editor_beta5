package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Built-in DaVinci Resolve-style color look / LUT preset with preview swatch palette
 * and parametric grading contributions scaled by [lutStrength].
 */
@JsonClass(generateAdapter = true)
data class LutPreset(
  val id: String,
  val name: String,
  val shortCode: String,
  val category: String,
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val shadowColorArgb: Long,
  val midtoneColorArgb: Long,
  val highlightColorArgb: Long,
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
  val whites: Float = 0.0f,
  val blacks: Float = 0.0f,
  val vibrance: Float = 0.0f,
  val liftX: Float = 0.0f,
  val liftY: Float = 0.0f,
  val gammaX: Float = 0.0f,
  val gammaY: Float = 0.0f,
  val gainX: Float = 0.0f,
  val gainY: Float = 0.0f,
  val isCustom: Boolean = false,
)

data class EffectiveColorGrading(
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val activeLut: LutPreset? = null,
  val lutStrength: Float = 1.0f,
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
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
  val hasColorModifications: Boolean
    get() =
      kotlin.math.abs(brightness) > 0.01f ||
        kotlin.math.abs(contrast - 1.0f) > 0.01f ||
        kotlin.math.abs(saturation - 1.0f) > 0.01f ||
        kotlin.math.abs(exposure) > 0.01f ||
        kotlin.math.abs(temperature) > 0.01f ||
        kotlin.math.abs(tint) > 0.01f ||
        kotlin.math.abs(shadows) > 0.01f ||
        kotlin.math.abs(midtones) > 0.01f ||
        kotlin.math.abs(highlights) > 0.01f ||
        kotlin.math.abs(whites) > 0.01f ||
        kotlin.math.abs(blacks) > 0.01f ||
        kotlin.math.abs(vibrance) > 0.01f ||
        kotlin.math.abs(liftX) > 0.01f ||
        kotlin.math.abs(liftY) > 0.01f ||
        kotlin.math.abs(gammaX) > 0.01f ||
        kotlin.math.abs(gammaY) > 0.01f ||
        kotlin.math.abs(gainX) > 0.01f ||
        kotlin.math.abs(gainY) > 0.01f ||
        vignette > 0.01f ||
        depthBlur > 0.01f ||
        chromaticAberration > 0.01f ||
        glitchIntensity > 0.01f ||
        (activeLut != null && activeLut.id != BuiltInLutPresets.ID_NONE && lutStrength > 0.01f)
}

object BuiltInLutPresets {
  const val ID_NONE = "none"

  val NONE =
    LutPreset(
      id = ID_NONE,
      name = "Original",
      shortCode = "ORIG",
      category = "Neutro",
      shadowColorArgb = 0xFF1E2430L,
      midtoneColorArgb = 0xFF475569L,
      highlightColorArgb = 0xFFCBD5E1L,
    )

  val REC709_CLEAN =
    LutPreset(
      id = "resolve_rec709",
      name = "Rec.709",
      shortCode = "709",
      category = "Técnico",
      brightness = 0.01f,
      contrast = 1.14f,
      saturation = 1.08f,
      exposure = 0.05f,
      temperature = 0.0f,
      vignette = 0.05f,
      shadowColorArgb = 0xFF0F172AL,
      midtoneColorArgb = 0xFF334155L,
      highlightColorArgb = 0xFFF1F5F9L,
    )

  val TEAL_ORANGE =
    LutPreset(
      id = "teal_orange",
      name = "Teal & Orange",
      shortCode = "T&O",
      category = "Cine",
      brightness = -0.02f,
      contrast = 1.20f,
      saturation = 1.18f,
      exposure = 0.04f,
      temperature = 0.24f,
      vignette = 0.20f,
      shadowColorArgb = 0xFF0D4F5CL,
      midtoneColorArgb = 0xFF1E7884L,
      highlightColorArgb = 0xFFF59E42L,
    )

  val FILM_2383 =
    LutPreset(
      id = "kodak_2383",
      name = "Film 2383",
      shortCode = "2383",
      category = "Fílmico",
      brightness = -0.02f,
      contrast = 1.24f,
      saturation = 1.12f,
      exposure = -0.04f,
      temperature = 0.14f,
      vignette = 0.25f,
      shadowColorArgb = 0xFF28191DL,
      midtoneColorArgb = 0xFF9E4737L,
      highlightColorArgb = 0xFFF3C677L,
    )

  val BLEACH_NOIR =
    LutPreset(
      id = "bleach_noir",
      name = "Silver Noir",
      shortCode = "NOIR",
      category = "Drama",
      brightness = -0.04f,
      contrast = 1.36f,
      saturation = 0.48f,
      exposure = -0.02f,
      temperature = -0.08f,
      vignette = 0.34f,
      shadowColorArgb = 0xFF0B0E14L,
      midtoneColorArgb = 0xFF4B5563L,
      highlightColorArgb = 0xFFE5E7EBL,
    )

  val ARCTIC_COOL =
    LutPreset(
      id = "arctic_cool",
      name = "Arctic Cool",
      shortCode = "COOL",
      category = "Atmósfera",
      brightness = 0.03f,
      contrast = 1.10f,
      saturation = 0.90f,
      exposure = 0.06f,
      temperature = -0.34f,
      vignette = 0.12f,
      shadowColorArgb = 0xFF11203AL,
      midtoneColorArgb = 0xFF2563EBL,
      highlightColorArgb = 0xFFBAE6FDL,
    )

  val GOLDEN_HOUR =
    LutPreset(
      id = "golden_hour",
      name = "Golden Hour",
      shortCode = "GOLD",
      category = "Cálido",
      brightness = 0.05f,
      contrast = 1.10f,
      saturation = 1.24f,
      exposure = 0.08f,
      temperature = 0.38f,
      vignette = 0.14f,
      shadowColorArgb = 0xFF3F1D14L,
      midtoneColorArgb = 0xFFD97706L,
      highlightColorArgb = 0xFFFDE68AL,
    )

  val ALL: List<LutPreset> =
    listOf(
      NONE,
      REC709_CLEAN,
      TEAL_ORANGE,
      FILM_2383,
      BLEACH_NOIR,
      ARCTIC_COOL,
      GOLDEN_HOUR,
    )

  private val customPresetRegistry = java.util.concurrent.ConcurrentHashMap<String, LutPreset>()

  fun syncCustomPresets(customPresets: List<LutPreset>) {
    customPresetRegistry.clear()
    for (preset in customPresets) {
      customPresetRegistry[preset.id] = preset
    }
  }

  fun registeredCustomPresets(): List<LutPreset> = customPresetRegistry.values.toList()

  fun findById(
    id: String?,
    customPresets: List<LutPreset> = emptyList(),
  ): LutPreset? {
    if (id.isNullOrBlank()) return null
    return customPresets.find { it.id == id }
      ?: customPresetRegistry[id]
      ?: ALL.find { it.id == id }
  }

  fun allWithCustom(customPresets: List<LutPreset> = emptyList()): List<LutPreset> =
    ALL + customPresets.filter { custom -> ALL.none { it.id == custom.id } }

  fun resolveEffectiveColor(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    exposure: Float,
    temperature: Float,
    vignette: Float,
    lutPresetId: String?,
    lutStrength: Float,
    tint: Float = 0.0f,
    shadows: Float = 0.0f,
    midtones: Float = 0.0f,
    highlights: Float = 0.0f,
    blendOpacity: Float = 1.0f,
    depthBlur: Float = 0.0f,
    whites: Float = 0.0f,
    blacks: Float = 0.0f,
    vibrance: Float = 0.0f,
    liftX: Float = 0.0f,
    liftY: Float = 0.0f,
    gammaX: Float = 0.0f,
    gammaY: Float = 0.0f,
    gainX: Float = 0.0f,
    gainY: Float = 0.0f,
    depthBlurRadius: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
    depthBlurFeather: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
    depthBlurMode: DepthBlurMaskMode = DepthBlurMaskMode.RADIAL,
    depthFocusX: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
    depthFocusY: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
    depthFocusRange: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
    chromaticAberration: Float = 0.0f,
    glitchIntensity: Float = 0.0f,
    customPresets: List<LutPreset> = emptyList(),
  ): EffectiveColorGrading {
    val layerBlend = ColorGradeBounds.clampBlendOpacity(blendOpacity)
    val preset = findById(lutPresetId, customPresets)?.takeIf { it.id != ID_NONE }
    val rawStrength = if (preset != null) ColorGradeBounds.clampLutStrength(lutStrength) else 0f
    val effectiveStrength = (rawStrength * layerBlend).coerceIn(0f, 1f)

    val lutBrightness = if (preset != null) preset.brightness * effectiveStrength else 0f
    val lutContrastDelta = if (preset != null) (preset.contrast - 1.0f) * effectiveStrength else 0f
    val lutSaturationDelta = if (preset != null) (preset.saturation - 1.0f) * effectiveStrength else 0f
    val lutExposure = if (preset != null) preset.exposure * effectiveStrength else 0f
    val lutTemperature = if (preset != null) preset.temperature * effectiveStrength else 0f
    val lutTint = if (preset != null) preset.tint * effectiveStrength else 0f
    val lutShadows = if (preset != null) preset.shadows * effectiveStrength else 0f
    val lutMidtones = if (preset != null) preset.midtones * effectiveStrength else 0f
    val lutHighlights = if (preset != null) preset.highlights * effectiveStrength else 0f
    val lutWhites = if (preset != null) preset.whites * effectiveStrength else 0f
    val lutBlacks = if (preset != null) preset.blacks * effectiveStrength else 0f
    val lutVibrance = if (preset != null) preset.vibrance * effectiveStrength else 0f
    val lutLiftX = if (preset != null) preset.liftX * effectiveStrength else 0f
    val lutLiftY = if (preset != null) preset.liftY * effectiveStrength else 0f
    val lutGammaX = if (preset != null) preset.gammaX * effectiveStrength else 0f
    val lutGammaY = if (preset != null) preset.gammaY * effectiveStrength else 0f
    val lutGainX = if (preset != null) preset.gainX * effectiveStrength else 0f
    val lutGainY = if (preset != null) preset.gainY * effectiveStrength else 0f
    val lutVignette = if (preset != null) preset.vignette * effectiveStrength else 0f

    val baseBrightness = ColorGradeBounds.clampBrightness(brightness) * layerBlend
    val baseContrastDelta = (ColorGradeBounds.clampContrast(contrast) - 1.0f) * layerBlend
    val baseSaturationDelta = (ColorGradeBounds.clampSaturation(saturation) - 1.0f) * layerBlend
    val baseExposure = ColorGradeBounds.clampExposure(exposure) * layerBlend
    val baseTemperature = ColorGradeBounds.clampTemperature(temperature) * layerBlend
    val baseTint = ColorGradeBounds.clampTint(tint) * layerBlend
    val baseShadows = ColorGradeBounds.clampShadows(shadows) * layerBlend
    val baseMidtones = ColorGradeBounds.clampMidtones(midtones) * layerBlend
    val baseHighlights = ColorGradeBounds.clampHighlights(highlights) * layerBlend
    val baseWhites = ColorGradeBounds.clampWhites(whites) * layerBlend
    val baseBlacks = ColorGradeBounds.clampBlacks(blacks) * layerBlend
    val baseVibrance = ColorGradeBounds.clampVibrance(vibrance) * layerBlend
    val baseLiftX = ColorGradeBounds.clampWheelCoord(liftX) * layerBlend
    val baseLiftY = ColorGradeBounds.clampWheelCoord(liftY) * layerBlend
    val baseGammaX = ColorGradeBounds.clampWheelCoord(gammaX) * layerBlend
    val baseGammaY = ColorGradeBounds.clampWheelCoord(gammaY) * layerBlend
    val baseGainX = ColorGradeBounds.clampWheelCoord(gainX) * layerBlend
    val baseGainY = ColorGradeBounds.clampWheelCoord(gainY) * layerBlend
    val baseVignette = ColorGradeBounds.clampVignette(vignette) * layerBlend
    val baseDepthBlur = ColorGradeBounds.clampDepthBlur(depthBlur) * layerBlend
    val baseChromatic = ColorGradeBounds.clampChromaticAberration(chromaticAberration) * layerBlend
    val baseGlitch = ColorGradeBounds.clampGlitchIntensity(glitchIntensity) * layerBlend

    return EffectiveColorGrading(
      brightness = (baseBrightness + lutBrightness).coerceIn(-0.8f, 0.8f),
      contrast = (1.0f + baseContrastDelta + lutContrastDelta).coerceIn(0.3f, 2.0f),
      saturation = (1.0f + baseSaturationDelta + lutSaturationDelta).coerceIn(0.0f, 2.2f),
      exposure = (baseExposure + lutExposure).coerceIn(-1.2f, 1.2f),
      temperature = (baseTemperature + lutTemperature).coerceIn(-1.0f, 1.0f),
      vignette = (baseVignette + lutVignette).coerceIn(0.0f, 1.0f),
      activeLut = if (effectiveStrength > 0.005f) preset else null,
      lutStrength = effectiveStrength,
      tint = (baseTint + lutTint).coerceIn(-1.0f, 1.0f),
      shadows = (baseShadows + lutShadows).coerceIn(-1.0f, 1.0f),
      midtones = (baseMidtones + lutMidtones).coerceIn(-1.0f, 1.0f),
      highlights = (baseHighlights + lutHighlights).coerceIn(-1.0f, 1.0f),
      blendOpacity = layerBlend,
      depthBlur = baseDepthBlur.coerceIn(0.0f, 1.0f),
      whites = (baseWhites + lutWhites).coerceIn(-1.0f, 1.0f),
      blacks = (baseBlacks + lutBlacks).coerceIn(-1.0f, 1.0f),
      vibrance = (baseVibrance + lutVibrance).coerceIn(-1.0f, 1.0f),
      liftX = (baseLiftX + lutLiftX).coerceIn(-1.0f, 1.0f),
      liftY = (baseLiftY + lutLiftY).coerceIn(-1.0f, 1.0f),
      gammaX = (baseGammaX + lutGammaX).coerceIn(-1.0f, 1.0f),
      gammaY = (baseGammaY + lutGammaY).coerceIn(-1.0f, 1.0f),
      gainX = (baseGainX + lutGainX).coerceIn(-1.0f, 1.0f),
      gainY = (baseGainY + lutGainY).coerceIn(-1.0f, 1.0f),
      depthBlurRadius = ColorGradeBounds.clampDepthBlurRadius(depthBlurRadius),
      depthBlurFeather = ColorGradeBounds.clampDepthBlurFeather(depthBlurFeather),
      depthBlurMode = depthBlurMode,
      depthFocusX = ColorGradeBounds.clampDepthFocusCoord(depthFocusX),
      depthFocusY = ColorGradeBounds.clampDepthFocusCoord(depthFocusY),
      depthFocusRange = ColorGradeBounds.clampDepthFocusRange(depthFocusRange),
      chromaticAberration = baseChromatic.coerceIn(0.0f, 1.0f),
      glitchIntensity = baseGlitch.coerceIn(0.0f, 1.0f),
    )
  }

  fun resolveEffectiveColor(
    cfg: VideoClipConfig,
    customPresets: List<LutPreset> = emptyList(),
  ): EffectiveColorGrading =
    resolveEffectiveColor(
      brightness = cfg.brightness,
      contrast = cfg.contrast,
      saturation = cfg.saturation,
      exposure = cfg.exposure,
      temperature = cfg.temperature,
      vignette = cfg.vignette,
      lutPresetId = cfg.lutPresetId,
      lutStrength = cfg.lutStrength,
      tint = cfg.tint,
      shadows = cfg.shadows,
      midtones = cfg.midtones,
      highlights = cfg.highlights,
      blendOpacity = 1.0f,
      depthBlur = cfg.depthBlur,
      whites = cfg.whites,
      blacks = cfg.blacks,
      vibrance = cfg.vibrance,
      liftX = cfg.liftX,
      liftY = cfg.liftY,
      gammaX = cfg.gammaX,
      gammaY = cfg.gammaY,
      gainX = cfg.gainX,
      gainY = cfg.gainY,
      depthBlurRadius = cfg.depthBlurRadius,
      depthBlurFeather = cfg.depthBlurFeather,
      depthBlurMode = cfg.depthBlurMode,
      depthFocusX = cfg.depthFocusX,
      depthFocusY = cfg.depthFocusY,
      depthFocusRange = cfg.depthFocusRange,
      chromaticAberration = cfg.chromaticAberration,
      glitchIntensity = cfg.glitchIntensity,
      customPresets = customPresets,
    )

  fun resolveEffectiveColor(
    cfg: AdjustmentClipConfig,
    customPresets: List<LutPreset> = emptyList(),
  ): EffectiveColorGrading =
    resolveEffectiveColor(
      brightness = cfg.brightness,
      contrast = cfg.contrast,
      saturation = cfg.saturation,
      exposure = cfg.exposure,
      temperature = cfg.temperature,
      vignette = cfg.vignette,
      lutPresetId = cfg.lutPresetId,
      lutStrength = cfg.lutStrength,
      tint = cfg.tint,
      shadows = cfg.shadows,
      midtones = cfg.midtones,
      highlights = cfg.highlights,
      blendOpacity = cfg.blendOpacity,
      depthBlur = cfg.depthBlur,
      whites = cfg.whites,
      blacks = cfg.blacks,
      vibrance = cfg.vibrance,
      liftX = cfg.liftX,
      liftY = cfg.liftY,
      gammaX = cfg.gammaX,
      gammaY = cfg.gammaY,
      gainX = cfg.gainX,
      gainY = cfg.gainY,
      depthBlurRadius = cfg.depthBlurRadius,
      depthBlurFeather = cfg.depthBlurFeather,
      depthBlurMode = cfg.depthBlurMode,
      depthFocusX = cfg.depthFocusX,
      depthFocusY = cfg.depthFocusY,
      depthFocusRange = cfg.depthFocusRange,
      chromaticAberration = cfg.chromaticAberration,
      glitchIntensity = cfg.glitchIntensity,
      customPresets = customPresets,
    )
}
