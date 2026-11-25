package com.example.core.timeline

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AudioClipConfig
import com.example.core.model.ClipKind
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.VideoClipConfig

/**
 * Attribute groups that can be selectively copied and pasted between clips.
 */
enum class AttributeCategory {
  /** Brightness, contrast, saturation, exposure, temperature, vignette, and LUT path. */
  COLOR_LUT,

  /** Video scale, X/Y offset, rotation, and opacity without overwriting audio or transitions. */
  SCALE_POSITION,

  /** Subtitle font family, size, text/background colors, and alignment while keeping clip text. */
  TEXT_STYLE,

  /** Clip volume and direct fade-in/fade-out durations. */
  AUDIO_MIX,
}

data class VideoTransformAttributes(
  val scale: Float,
  val offsetX: Float,
  val offsetY: Float,
  val rotationDegrees: Float,
  val opacity: Float,
)

data class TextStyleAttributes(
  val fontFamily: String,
  val fontSizeSp: Float,
  val textColorHex: String,
  val backgroundColorHex: String,
  val alignment: SubtitleAlignment,
  val outlineColorHex: String = "#CC000000",
  val outlineWidthDp: Float = 2.0f,
  val verticalOffsetNormalized: Float = 0.0f,
  val animation: SubtitleAnimation = SubtitleAnimation.NONE,
)

data class AudioMixAttributes(
  val volume: Float,
  val fadeInMs: Long,
  val fadeOutMs: Long,
)

data class CopiedClipAttributes(
  val sourceClipId: String,
  val sourceLabel: String,
  val availableCategories: Set<AttributeCategory>,
  val colorAndLut: AdjustmentClipConfig? = null,
  val transform: VideoTransformAttributes? = null,
  val textStyle: TextStyleAttributes? = null,
  val audioMix: AudioMixAttributes? = null,
)

/**
 * Copies and selectively pastes clip attributes (Color/LUT only, Scale/Position only,
 * Text Style only, or Audio Mix only) across compatible target clips.
 */
class AttributeTransferEngine {

  fun availableCategoriesFor(clip: TimelineClip): Set<AttributeCategory> =
    buildSet {
      if (clip.kind == ClipKind.ADJUSTMENT && clip.adjustmentConfig != null) {
        add(AttributeCategory.COLOR_LUT)
      }
      if (clip.kind == ClipKind.VIDEO) {
        add(AttributeCategory.COLOR_LUT)
        add(AttributeCategory.SCALE_POSITION)
        add(AttributeCategory.AUDIO_MIX)
      }
      if (clip.kind == ClipKind.AUDIO) {
        add(AttributeCategory.AUDIO_MIX)
      }
      if (clip.kind == ClipKind.SUBTITLE && clip.subtitleConfig != null) {
        add(AttributeCategory.TEXT_STYLE)
      }
    }

  fun copyFromClip(
    clip: TimelineClip,
    selectedCategories: Set<AttributeCategory> = availableCategoriesFor(clip),
  ): CopiedClipAttributes {
    val colorLut =
      if (AttributeCategory.COLOR_LUT in selectedCategories) {
        when (clip.kind) {
          ClipKind.ADJUSTMENT -> clip.adjustmentConfig
          ClipKind.VIDEO -> {
            val cfg = clip.videoConfig ?: VideoClipConfig()
            AdjustmentClipConfig(
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
            )
          }
          else -> null
        }
      } else {
        null
      }

    val transform =
      if (AttributeCategory.SCALE_POSITION in selectedCategories && clip.kind == ClipKind.VIDEO) {
        val cfg = clip.videoConfig ?: VideoClipConfig()
        VideoTransformAttributes(
          scale = cfg.scale,
          offsetX = cfg.offsetX,
          offsetY = cfg.offsetY,
          rotationDegrees = cfg.rotationDegrees,
          opacity = cfg.opacity,
        )
      } else {
        null
      }

    val textStyle =
      if (AttributeCategory.TEXT_STYLE in selectedCategories && clip.subtitleConfig != null) {
        TextStyleAttributes(
          fontFamily = clip.subtitleConfig.fontFamily,
          fontSizeSp = clip.subtitleConfig.fontSizeSp,
          textColorHex = clip.subtitleConfig.textColorHex,
          backgroundColorHex = clip.subtitleConfig.backgroundColorHex,
          alignment = clip.subtitleConfig.alignment,
          outlineColorHex = clip.subtitleConfig.outlineColorHex,
          outlineWidthDp = clip.subtitleConfig.outlineWidthDp,
          verticalOffsetNormalized = clip.subtitleConfig.verticalOffsetNormalized,
          animation = clip.subtitleConfig.animation,
        )
      } else {
        null
      }

    val audioMix =
      if (AttributeCategory.AUDIO_MIX in selectedCategories) {
        when (clip.kind) {
          ClipKind.VIDEO -> {
            val cfg = clip.videoConfig ?: VideoClipConfig()
            AudioMixAttributes(cfg.volume, cfg.fadeInMs, cfg.fadeOutMs)
          }
          ClipKind.AUDIO -> {
            val cfg = clip.audioConfig ?: AudioClipConfig()
            AudioMixAttributes(cfg.volume, cfg.fadeInMs, cfg.fadeOutMs)
          }
          else -> null
        }
      } else {
        null
      }

    val captured =
      buildSet {
        if (colorLut != null) add(AttributeCategory.COLOR_LUT)
        if (transform != null) add(AttributeCategory.SCALE_POSITION)
        if (textStyle != null) add(AttributeCategory.TEXT_STYLE)
        if (audioMix != null) add(AttributeCategory.AUDIO_MIX)
      }

    return CopiedClipAttributes(
      sourceClipId = clip.clipId,
      sourceLabel = clip.label,
      availableCategories = captured,
      colorAndLut = colorLut,
      transform = transform,
      textStyle = textStyle,
      audioMix = audioMix,
    )
  }

  fun pasteOntoClips(
    state: TimelineState,
    targetClipIds: List<String>,
    clipboard: CopiedClipAttributes,
    categoriesToApply: Set<AttributeCategory> = clipboard.availableCategories,
  ): TimelineOperationResult {
    val targetSet = targetClipIds.toSet()
    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }
    val affectedIds = mutableListOf<String>()

    val updatedClips =
      state.clips.map { clip ->
        if (clip.clipId !in targetSet || clip.isLocked || clip.trackId in lockedTrackIds) {
          clip
        } else {
          val patched = applyToSingleClip(clip, clipboard, categoriesToApply)
          if (patched != clip) affectedIds.add(clip.clipId)
          patched
        }
      }

    if (affectedIds.isEmpty()) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    }

    return TimelineOperationResult.Success(
      newState = state.copy(clips = updatedClips),
      affectedClipIds = affectedIds,
    )
  }

  private fun applyToSingleClip(
    clip: TimelineClip,
    clipboard: CopiedClipAttributes,
    categories: Set<AttributeCategory>,
  ): TimelineClip {
    var result = clip

    if (AttributeCategory.COLOR_LUT in categories && clipboard.colorAndLut != null) {
      val srcColor = clipboard.colorAndLut
      when (result.kind) {
        ClipKind.ADJUSTMENT -> {
          result = result.copy(adjustmentConfig = srcColor)
        }
        ClipKind.VIDEO -> {
          val base = result.videoConfig ?: VideoClipConfig()
          result =
            result.copy(
              videoConfig =
                base.copy(
                  brightness = srcColor.brightness,
                  contrast = srcColor.contrast,
                  saturation = srcColor.saturation,
                  exposure = srcColor.exposure,
                  temperature = srcColor.temperature,
                  vignette = srcColor.vignette,
                  lutPresetId = srcColor.lutPresetId,
                  lutStrength = srcColor.lutStrength,
                  tint = srcColor.tint,
                  shadows = srcColor.shadows,
                  midtones = srcColor.midtones,
                  highlights = srcColor.highlights,
                  depthBlur = srcColor.depthBlur,
                  whites = srcColor.whites,
                  blacks = srcColor.blacks,
                  vibrance = srcColor.vibrance,
                  liftX = srcColor.liftX,
                  liftY = srcColor.liftY,
                  gammaX = srcColor.gammaX,
                  gammaY = srcColor.gammaY,
                  gainX = srcColor.gainX,
                  gainY = srcColor.gainY,
                  depthBlurRadius = srcColor.depthBlurRadius,
                  depthBlurFeather = srcColor.depthBlurFeather,
                  depthBlurMode = srcColor.depthBlurMode,
                  depthFocusX = srcColor.depthFocusX,
                  depthFocusY = srcColor.depthFocusY,
                  depthFocusRange = srcColor.depthFocusRange,
                ),
            )
        }
        else -> Unit
      }
    }

    if (AttributeCategory.SCALE_POSITION in categories &&
      clipboard.transform != null &&
      result.kind == ClipKind.VIDEO
    ) {
      val base = result.videoConfig ?: VideoClipConfig()
      result =
        result.copy(
          videoConfig =
            base.copy(
              scale = clipboard.transform.scale,
              offsetX = clipboard.transform.offsetX,
              offsetY = clipboard.transform.offsetY,
              rotationDegrees = clipboard.transform.rotationDegrees,
              opacity = clipboard.transform.opacity,
            ),
        )
    }

    if (AttributeCategory.TEXT_STYLE in categories &&
      clipboard.textStyle != null &&
      result.kind == ClipKind.SUBTITLE
    ) {
      val base = result.subtitleConfig ?: SubtitleClipConfig(text = result.label)
      result =
        result.copy(
          subtitleConfig =
            base.copy(
              fontFamily = clipboard.textStyle.fontFamily,
              fontSizeSp = clipboard.textStyle.fontSizeSp,
              textColorHex = clipboard.textStyle.textColorHex,
              backgroundColorHex = clipboard.textStyle.backgroundColorHex,
              alignment = clipboard.textStyle.alignment,
              outlineColorHex = clipboard.textStyle.outlineColorHex,
              outlineWidthDp = clipboard.textStyle.outlineWidthDp,
              verticalOffsetNormalized = clipboard.textStyle.verticalOffsetNormalized,
              animation = clipboard.textStyle.animation,
            ),
        )
    }

    if (AttributeCategory.AUDIO_MIX in categories && clipboard.audioMix != null) {
      when (result.kind) {
        ClipKind.VIDEO -> {
          val base = result.videoConfig ?: VideoClipConfig()
          result =
            result.copy(
              videoConfig =
                base.copy(
                  volume = clipboard.audioMix.volume,
                  fadeInMs = clipboard.audioMix.fadeInMs,
                  fadeOutMs = clipboard.audioMix.fadeOutMs,
                ),
            )
        }
        ClipKind.AUDIO -> {
          val base = result.audioConfig ?: AudioClipConfig()
          result =
            result.copy(
              audioConfig =
                base.copy(
                  volume = clipboard.audioMix.volume,
                  fadeInMs = clipboard.audioMix.fadeInMs,
                  fadeOutMs = clipboard.audioMix.fadeOutMs,
                ),
            )
        }
        else -> Unit
      }
    }

    return result
  }
}
