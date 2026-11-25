package com.example.core.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

data class StudioSoundPreset(
  val id: String,
  val title: String,
  val category: String,
  val durationMs: Long,
  val tag: String,
)

data class CropAspectPreset(
  val id: String,
  val label: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

data class StickerLogoPreset(
  val id: String,
  val title: String,
  val isLogo: Boolean,
  val badgeText: String,
  val primaryColorArgb: Int,
  val accentColorArgb: Int,
)

data class AdjustmentEffectPreset(
  val id: String,
  val title: String,
  val subtitle: String,
  val tag: String,
  val config: AdjustmentClipConfig,
  val defaultDurationMs: Long = 3_000L,
)

object OverlayCatalog {

  val soundPresets: List<StudioSoundPreset> =
    listOf(
      StudioSoundPreset(
        id = "snd_whoosh",
        title = "Transición Whoosh",
        category = "Transición",
        durationMs = 1_200L,
        tag = "SFX",
      ),
      StudioSoundPreset(
        id = "snd_pop",
        title = "Pop UI / Sticker",
        category = "Interfaz",
        durationMs = 600L,
        tag = "POP",
      ),
      StudioSoundPreset(
        id = "snd_boom",
        title = "Impacto Cine",
        category = "Impacto",
        durationMs = 1_800L,
        tag = "BASS",
      ),
      StudioSoundPreset(
        id = "snd_chime",
        title = "Campana Acento",
        category = "Notificación",
        durationMs = 1_400L,
        tag = "TONE",
      ),
      StudioSoundPreset(
        id = "snd_lofi_pulse",
        title = "Pulso Lo-Fi",
        category = "Ambiente",
        durationMs = 3_000L,
        tag = "LOOP",
      ),
    )

  val cropPresets: List<CropAspectPreset> =
    listOf(
      CropAspectPreset("crop_free", "Libre", 0.0f, 0.0f, 1.0f, 1.0f),
      CropAspectPreset("crop_square", "1:1", 0.14f, 0.05f, 0.86f, 0.95f),
      CropAspectPreset("crop_wide", "16:9", 0.04f, 0.22f, 0.96f, 0.78f),
      CropAspectPreset("crop_portrait", "9:16", 0.25f, 0.04f, 0.75f, 0.96f),
    )

  val stickerPresets: List<StickerLogoPreset> =
    listOf(
      StickerLogoPreset(
        id = "rec_badge",
        title = "REC HUD",
        isLogo = false,
        badgeText = "REC",
        primaryColorArgb = 0xFFEF4444.toInt(),
        accentColorArgb = 0xFFFFFFFF.toInt(),
      ),
      StickerLogoPreset(
        id = "badge_4k",
        title = "4K HDR",
        isLogo = false,
        badgeText = "4K UHD",
        primaryColorArgb = 0xFF00E5FF.toInt(),
        accentColorArgb = 0xFF0B0D11.toInt(),
      ),
      StickerLogoPreset(
        id = "frameline_logo",
        title = "FrameLine",
        isLogo = true,
        badgeText = "FRAMELINE",
        primaryColorArgb = 0xFF00E5FF.toInt(),
        accentColorArgb = 0xFFFF9F1C.toInt(),
      ),
      StickerLogoPreset(
        id = "subscribe_pill",
        title = "Suscribirse",
        isLogo = false,
        badgeText = "SUSCRÍBETE",
        primaryColorArgb = 0xFFFF9F1C.toInt(),
        accentColorArgb = 0xFF0B0D11.toInt(),
      ),
      StickerLogoPreset(
        id = "neon_frame",
        title = "Marco Neón",
        isLogo = true,
        badgeText = "CINEMA 2.39:1",
        primaryColorArgb = 0xFF00E5FF.toInt(),
        accentColorArgb = 0xFFFF9F1C.toInt(),
      ),
      StickerLogoPreset(
        id = "arrow_call",
        title = "Flecha",
        isLogo = false,
        badgeText = "AQUÍ",
        primaryColorArgb = 0xFFFF9F1C.toInt(),
        accentColorArgb = 0xFFFFFFFF.toInt(),
      ),
    )

  fun findSticker(stickerId: String?): StickerLogoPreset? =
    stickerId?.let { id -> stickerPresets.firstOrNull { it.id == id } }

  fun adjustmentPresets(
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): List<AdjustmentEffectPreset> {
    val coreEffects =
      listOf(
        AdjustmentEffectPreset(
          id = "fx_depth_blur",
          title = "Desenfoque de profundidad",
          subtitle = "Bokeh óptico progresivo con centro enfocado",
          tag = "BOKEH",
          config =
            AdjustmentClipConfig(
              depthBlur = 0.68f,
              depthBlurRadius = 18f,
              depthBlurFeather = 0.35f,
              depthBlurMode = DepthBlurMaskMode.RADIAL,
            ),
        ),
        AdjustmentEffectPreset(
          id = "fx_brightness_contrast",
          title = "Brillo / Contraste",
          subtitle = "Luz limpia y contraste cinematográfico",
          tag = "LUZ",
          config =
            AdjustmentClipConfig(
              brightness = 0.12f,
              exposure = 0.15f,
              contrast = 1.24f,
              saturation = 1.08f,
            ),
        ),
        AdjustmentEffectPreset(
          id = "fx_chromatic",
          title = "Aberración cromática",
          subtitle = "Separación óptica de canales RGB",
          tag = "RGB",
          config =
            AdjustmentClipConfig(
              chromaticAberration = 0.65f,
            ),
        ),
        AdjustmentEffectPreset(
          id = "fx_vignette",
          title = "Viñeta",
          subtitle = "Oscurecimiento suave en bordes",
          tag = "LENTE",
          config =
            AdjustmentClipConfig(
              vignette = 0.72f,
              contrast = 1.08f,
            ),
        ),
        AdjustmentEffectPreset(
          id = "fx_glitch",
          title = "Glitch",
          subtitle = "Distorsión por líneas y desfase RGB",
          tag = "FX",
          config =
            AdjustmentClipConfig(
              glitchIntensity = 0.70f,
              chromaticAberration = 0.35f,
            ),
        ),
      )

    val lutEffects =
      (BuiltInLutPresets.ALL + customLutPresets)
        .filter { it.id != BuiltInLutPresets.ID_NONE }
        .map { lut ->
          AdjustmentEffectPreset(
            id = "fx_lut_${lut.id}",
            title = "LUT: ${lut.name}",
            subtitle = "Perfil ${lut.category} · ${lut.shortCode}",
            tag = if (!lut.isCustom) "LUT" else "CUBE",
            config =
              AdjustmentClipConfig(
                lutPresetId = lut.id,
                lutStrength = 0.85f,
              ),
          )
        }

    return coreEffects + lutEffects
  }
}

object OverlayBitmapRasterizer {

  fun renderStickerBitmap(
    stickerId: String?,
    widthPx: Int = 360,
    heightPx: Int = 140,
  ): Bitmap {
    val preset = OverlayCatalog.findSticker(stickerId) ?: OverlayCatalog.stickerPresets.first()
    val w = widthPx.coerceAtLeast(64)
    val h = heightPx.coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val rect = RectF(6f, 6f, w - 6f, h - 6f)
    val radius = h * 0.24f

    when (preset.id) {
      "rec_badge" -> {
        val bgPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 11, 13, 17)
          }
        val borderPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = preset.primaryColorArgb
          }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val dotPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.primaryColorArgb
          }
        canvas.drawCircle(h * 0.42f, h * 0.5f, h * 0.14f, dotPaint)

        val textPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
          }
        canvas.drawText("REC", h * 0.72f, h * 0.65f, textPaint)
      }

      "frameline_logo" -> {
        val bgPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
              LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                Color.argb(230, 19, 23, 31),
                Color.argb(230, 27, 32, 43),
                Shader.TileMode.CLAMP,
              )
          }
        val strokePaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = preset.primaryColorArgb
          }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.accentColorArgb }
        canvas.drawRect(18f, 18f, 30f, h - 18f, accentPaint)

        val textPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
            textAlign = Paint.Align.CENTER
          }
        canvas.drawText(preset.badgeText, w * 0.54f, h * 0.62f, textPaint)
      }

      "arrow_call" -> {
        val path =
          Path().apply {
            moveTo(w * 0.12f, h * 0.34f)
            lineTo(w * 0.58f, h * 0.34f)
            lineTo(w * 0.58f, h * 0.14f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.58f, h * 0.86f)
            lineTo(w * 0.58f, h * 0.66f)
            lineTo(w * 0.12f, h * 0.66f)
            close()
          }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.primaryColorArgb }
        val strokePaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
          }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
      }

      "neon_frame" -> {
        val strokePaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = preset.primaryColorArgb
          }
        canvas.drawRoundRect(rect, 18f, 18f, strokePaint)
        val innerPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = preset.accentColorArgb
          }
        canvas.drawRoundRect(RectF(16f, 16f, w - 16f, h - 16f), 12f, 12f, innerPaint)
      }

      else -> {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.primaryColorArgb }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        val textPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.accentColorArgb
            textSize = h * 0.36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
          }
        canvas.drawText(preset.badgeText, w * 0.5f, h * 0.63f, textPaint)
      }
    }
    return bitmap
  }
}
