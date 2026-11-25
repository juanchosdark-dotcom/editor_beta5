package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Reusable saved subtitle style preset that can be applied across subtitle blocks in batch.
 */
@JsonClass(generateAdapter = true)
data class SubtitleStylePreset(
  val styleId: String,
  val name: String,
  val fontFamily: String = "sans-serif",
  val fontSizeSp: Float = 20f,
  val textColorHex: String = "#FFFFFF",
  val backgroundColorHex: String = "#80000000",
  val alignment: SubtitleAlignment = SubtitleAlignment.BOTTOM_CENTER,
  val outlineColorHex: String = "#CC000000",
  val outlineWidthDp: Float = 2.0f,
  val verticalOffsetNormalized: Float = 0.0f,
  val animation: SubtitleAnimation = SubtitleAnimation.NONE,
  val isBuiltIn: Boolean = false,
) {
  fun toSubtitleConfig(text: String): SubtitleClipConfig =
    SubtitleClipConfig(
      text = text,
      fontFamily = fontFamily,
      fontSizeSp = fontSizeSp,
      textColorHex = textColorHex,
      backgroundColorHex = backgroundColorHex,
      alignment = alignment,
      outlineColorHex = outlineColorHex,
      outlineWidthDp = outlineWidthDp,
      verticalOffsetNormalized = verticalOffsetNormalized,
      animation = animation,
    )
}

data class SubtitleAnimationFrame(
  val alpha: Float,
  val scale: Float,
  val translationYFraction: Float,
)

/**
 * Deterministic subtitle animation evaluator shared identically between the Compose preview
 * monitor and Media3 export [CompositionCompiler] bitmap overlay settings.
 */
object SubtitleAnimationMath {
  private const val MAX_TRANSITION_WINDOW_MS = 220f

  fun evaluate(
    animation: SubtitleAnimation,
    elapsedMs: Long,
    totalDurationMs: Long,
  ): SubtitleAnimationFrame {
    if (animation == SubtitleAnimation.NONE || totalDurationMs <= 0L) {
      return SubtitleAnimationFrame(alpha = 1.0f, scale = 1.0f, translationYFraction = 0.0f)
    }

    val safeDuration = totalDurationMs.coerceAtLeast(1L).toFloat()
    val window = minOf(MAX_TRANSITION_WINDOW_MS, safeDuration * 0.28f).coerceAtLeast(1f)
    val safeElapsed = elapsedMs.coerceIn(0L, totalDurationMs).toFloat()
    val remaining = (safeDuration - safeElapsed).coerceAtLeast(0f)

    val enterT = (safeElapsed / window).coerceIn(0f, 1f)
    val exitT = (remaining / window).coerceIn(0f, 1f)
    val smoothEnter = smoothstep(enterT)
    val smoothExit = smoothstep(exitT)
    val envelopeAlpha = minOf(smoothEnter, smoothExit).coerceIn(0f, 1f)

    return when (animation) {
      SubtitleAnimation.NONE -> SubtitleAnimationFrame(1.0f, 1.0f, 0.0f)
      SubtitleAnimation.FADE ->
        SubtitleAnimationFrame(
          alpha = envelopeAlpha,
          scale = 1.0f,
          translationYFraction = 0.0f,
        )
      SubtitleAnimation.POP -> {
        val overshoot =
          if (enterT < 1f) {
            val s = smoothEnter
            0.80f + 0.24f * s - 0.04f * (s * s)
          } else if (exitT < 1f) {
            0.88f + 0.12f * smoothExit
          } else {
            1.0f
          }
        SubtitleAnimationFrame(
          alpha = envelopeAlpha,
          scale = overshoot.coerceIn(0.75f, 1.08f),
          translationYFraction = 0.0f,
        )
      }
      SubtitleAnimation.SLIDE_UP -> {
        val enterSlide = (1f - smoothEnter) * 0.14f
        val exitSlide = if (exitT < 1f) -(1f - smoothExit) * 0.06f else 0f
        SubtitleAnimationFrame(
          alpha = envelopeAlpha,
          scale = 1.0f,
          translationYFraction = enterSlide + exitSlide,
        )
      }
    }
  }

  private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
  }
}

object BuiltInSubtitleStyles {
  val CLASSIC_CINEMA =
    SubtitleStylePreset(
      styleId = "style_classic",
      name = "Cine Clásico",
      fontFamily = "sans-serif",
      fontSizeSp = 20f,
      textColorHex = "#FFFFFF",
      backgroundColorHex = "#99000000",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      outlineColorHex = "#CC000000",
      outlineWidthDp = 2.0f,
      animation = SubtitleAnimation.FADE,
      isBuiltIn = true,
    )

  val HIGH_CONTRAST_AMBER =
    SubtitleStylePreset(
      styleId = "style_amber",
      name = "Impacto Ámbar",
      fontFamily = "sans-serif-medium",
      fontSizeSp = 24f,
      textColorHex = "#FBBF24",
      backgroundColorHex = "#CC0B0D12",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      outlineColorHex = "#FF000000",
      outlineWidthDp = 3.0f,
      animation = SubtitleAnimation.POP,
      isBuiltIn = true,
    )

  val MINIMAL_CLEAN =
    SubtitleStylePreset(
      styleId = "style_minimal",
      name = "Minimalista",
      fontFamily = "sans-serif-light",
      fontSizeSp = 18f,
      textColorHex = "#F1F5F9",
      backgroundColorHex = "#00000000",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      outlineColorHex = "#B3000000",
      outlineWidthDp = 1.5f,
      animation = SubtitleAnimation.FADE,
      isBuiltIn = true,
    )

  val TOP_HEADER_CAPTION =
    SubtitleStylePreset(
      styleId = "style_top",
      name = "Título Superior",
      fontFamily = "sans-serif-condensed",
      fontSizeSp = 22f,
      textColorHex = "#0B0D12",
      backgroundColorHex = "#E638BDF8",
      alignment = SubtitleAlignment.TOP_CENTER,
      outlineColorHex = "#00000000",
      outlineWidthDp = 0.0f,
      animation = SubtitleAnimation.SLIDE_UP,
      isBuiltIn = true,
    )

  val ALL: List<SubtitleStylePreset> =
    listOf(CLASSIC_CINEMA, HIGH_CONTRAST_AMBER, MINIMAL_CLEAN, TOP_HEADER_CAPTION)
}
