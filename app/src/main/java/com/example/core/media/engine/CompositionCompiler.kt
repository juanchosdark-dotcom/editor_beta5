package com.example.core.media.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.example.core.media.audio.EnvelopeAudioProcessor
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.DuckingWindow
import com.example.core.model.LutPreset
import com.example.core.model.MediaAsset
import com.example.core.model.OverlayBitmapRasterizer
import com.example.core.model.OverlayContentKind
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleAnimation
import com.example.core.model.SubtitleAnimationMath
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface CompiledCompositionResult {
  data class Ready(
    val composition: Composition,
    val totalVideoItems: Int,
    val totalAudioItems: Int,
    val usedProxyCount: Int,
    val appliedVideoEffectCount: Int = 0,
    val renderPlan: TimelineRenderPlan? = null,
  ) : CompiledCompositionResult

  data class EmptyOrMissingMedia(val reason: String) : CompiledCompositionResult
}

/**
 * Translates our portable [TimelineState] and [MediaAsset] registry into a Media3 [Composition]
 * backed by an explicit [TimelineRenderPlan].
 *
 * Includes:
 * - Pre-encode [TimelineRenderPlan] resolution with overlapping-clip trimming, source EOF/speed
 *   clamping, micro-segment coalescing, and automatic gap compensation for strict A/V sync.
 * - Multi-layer / partial-overlap [ClipKind.ADJUSTMENT] splitting and deterministic grading merge.
 * - Styled timeline [ClipKind.SUBTITLE] rendering via Media3 [OverlayEffect].
 * - Mute/Solo resolution, track gain, pan, smooth fades, and ducking via [EnvelopeAudioProcessor].
 */
@OptIn(UnstableApi::class)
class CompositionCompiler {

  private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    private val safeSpeed =
      speed.coerceIn(TimelineRenderPlanner.MIN_SPEED, TimelineRenderPlanner.MAX_SPEED)

    override fun getSpeed(timeUs: Long): Float = safeSpeed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }

  private class VariableCurveSpeedProvider(
    private val sourceSpanMs: Long,
    private val baseSpeed: Float,
    private val speedCurve: List<SpeedCurvePoint>,
  ) : SpeedProvider {
    private val safeSpanUs = sourceSpanMs.coerceAtLeast(1L) * 1_000L

    override fun getSpeed(timeUs: Long): Float =
      SpeedCurveEngine.instantaneousSpeedAtSourceMs(
        sourceOffsetMs = timeUs.coerceAtLeast(0L) / 1_000L,
        sourceSpanMs = sourceSpanMs.coerceAtLeast(1L),
        baseSpeed = baseSpeed,
        speedCurve = speedCurve,
      )

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
      val next = timeUs.coerceAtLeast(0L) + 50_000L
      return if (next < safeSpanUs) next else C.TIME_UNSET
    }
  }

  /**
   * Renders active [PlannedSubtitleOverlay] items onto a cached frame-sized transparent bitmap
   * without allocating per frame when the active subtitle set hasn't changed, and drives
   * smooth alpha/scale/slide-up animation via [getOverlaySettings].
   */
  private class TimelineSubtitleBitmapOverlay(
    private val subtitles: List<PlannedSubtitleOverlay>,
    private val frameWidth: Int,
    private val frameHeight: Int,
  ) : BitmapOverlay() {

    private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap = emptyBitmap

    private val hiddenSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(0f)
        .build()

    private val visibleSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(1f)
        .build()

    private fun activeSubtitlesAt(presentationTimeUs: Long): List<PlannedSubtitleOverlay> {
      val posMs = (presentationTimeUs.coerceAtLeast(0L) / 1_000L)
      return subtitles.filter { posMs in it.timelineStartMs until it.timelineEndMs }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
      val active = activeSubtitlesAt(presentationTimeUs)
      if (active.isEmpty()) return hiddenSettings
      val primary = active.first()
      if (primary.config.animation == SubtitleAnimation.NONE) return visibleSettings

      val posMs = (presentationTimeUs.coerceAtLeast(0L) / 1_000L)
      val anim =
        SubtitleAnimationMath.evaluate(
          animation = primary.config.animation,
          elapsedMs = posMs - primary.timelineStartMs,
          totalDurationMs = primary.timelineEndMs - primary.timelineStartMs,
        )
      return StaticOverlaySettings.Builder()
        .setAlphaScale(anim.alpha)
        .setScale(anim.scale, anim.scale)
        .setOverlayFrameAnchor(0f, (-anim.translationYFraction * 2f).coerceIn(-1f, 1f))
        .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      val active = activeSubtitlesAt(presentationTimeUs)
      if (active.isEmpty()) return emptyBitmap

      val key =
        active.joinToString("|") {
          val c = it.config
          "${it.clipId}:${c.text}:${c.fontFamily}:${c.alignment}:${c.fontSizeSp}:${c.textColorHex}:${c.backgroundColorHex}:${c.outlineColorHex}:${c.outlineWidthDp}:${c.verticalOffsetNormalized}"
        }
      if (key == cachedKey) return cachedBitmap

      val rendered = renderSubtitlesBitmap(active)
      if (cachedBitmap !== emptyBitmap && !cachedBitmap.isRecycled) {
        cachedBitmap.recycle()
      }
      cachedBitmap = rendered
      cachedKey = key
      return rendered
    }

    private fun renderSubtitlesBitmap(active: List<PlannedSubtitleOverlay>): Bitmap {
      val w = frameWidth.coerceIn(320, 1920)
      val h = frameHeight.coerceIn(240, 1080)
      val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val scaleFactor = h / 720f

      for (sub in active) {
        val cfg = sub.config
        val resolvedSize = (cfg.fontSizeSp.coerceIn(12f, 64f) * 1.45f * scaleFactor).coerceAtLeast(18f)
        val resolvedTypeface = resolveTypeface(cfg.fontFamily)

        val textPaint =
          TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColorOrDefault(cfg.textColorHex, Color.WHITE)
            textSize = resolvedSize
            typeface = resolvedTypeface
            setShadowLayer(4f * scaleFactor, 0f, 2f * scaleFactor, Color.argb(180, 0, 0, 0))
          }

        val maxTextWidth = (w * 0.84f).roundToInt().coerceAtLeast(160)
        val layout =
          StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val textHeight = layout.height.toFloat()
        val maxLineWidth =
          (0 until layout.lineCount)
            .maxOfOrNull { layout.getLineWidth(it) }
            ?.coerceAtLeast(64f)
            ?: maxTextWidth.toFloat()

        val padH = 22f * scaleFactor
        val padV = 12f * scaleFactor
        val boxWidth = (maxLineWidth + padH * 2f).coerceAtMost(w * 0.92f)
        val boxHeight = textHeight + padV * 2f
        val boxLeft = (w - boxWidth) / 2f
        val marginV = h * 0.08f
        val baseTop =
          when (cfg.alignment) {
            SubtitleAlignment.TOP_CENTER -> marginV
            SubtitleAlignment.MIDDLE_CENTER -> (h - boxHeight) / 2f
            SubtitleAlignment.BOTTOM_CENTER -> h - marginV - boxHeight
          }
        val boxTop =
          (baseTop + cfg.verticalOffsetNormalized.coerceIn(-0.45f, 0.45f) * h)
            .coerceIn(8f, (h - boxHeight - 8f).coerceAtLeast(8f))

        val bgColor = parseColorOrDefault(cfg.backgroundColorHex, Color.argb(140, 0, 0, 0))
        if (Color.alpha(bgColor) > 0) {
          val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
          val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
          val radius = 12f * scaleFactor
          canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }

        canvas.save()
        val textLeft = (w - maxTextWidth) / 2f
        canvas.translate(textLeft, boxTop + padV)

        val outlineColor = parseColorOrDefault(cfg.outlineColorHex, Color.argb(204, 0, 0, 0))
        if (cfg.outlineWidthDp > 0.05f && Color.alpha(outlineColor) > 0) {
          val strokePaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = outlineColor
              textSize = resolvedSize
              typeface = resolvedTypeface
              style = Paint.Style.STROKE
              strokeJoin = Paint.Join.ROUND
              strokeCap = Paint.Cap.ROUND
              strokeWidth = (cfg.outlineWidthDp.coerceIn(0f, 8f) * 2.1f * scaleFactor)
            }
          val strokeLayout =
            StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, strokePaint, maxTextWidth)
              .setAlignment(Layout.Alignment.ALIGN_CENTER)
              .setIncludePad(false)
              .build()
          strokeLayout.draw(canvas)
        }

        layout.draw(canvas)
        canvas.restore()
      }

      return bitmap
    }

    private fun parseColorOrDefault(hex: String, fallback: Int): Int =
      runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private fun resolveTypeface(family: String): Typeface =
      when {
        family.contains("medium", ignoreCase = true) || family.contains("bold", ignoreCase = true) ->
          Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        family.contains("condensed", ignoreCase = true) ->
          Typeface.create("sans-serif-condensed", Typeface.BOLD)
        family.contains("serif", ignoreCase = true) && !family.contains("sans", ignoreCase = true) ->
          Typeface.SERIF
        family.contains("mono", ignoreCase = true) -> Typeface.MONOSPACE
      else -> Typeface.SANS_SERIF
    }
  }

  /**
   * Composites active [PlannedOverlaySegment]s (transparent PNG/JPG images, stickers, logos, or
   * poster frames) onto the exported video with clip transform, scale, rotation, and opacity.
   */
  private class TimelineGraphicOverlayBitmapOverlay(
    private val overlays: List<PlannedOverlaySegment>,
    private val frameWidth: Int,
    private val frameHeight: Int,
  ) : BitmapOverlay() {

    private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val decodedSourceCache = mutableMapOf<String, Bitmap?>()
    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap = emptyBitmap

    private val hiddenSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(0f)
        .build()

    private val visibleSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(1f)
        .build()

    private fun activeAt(presentationTimeUs: Long): List<PlannedOverlaySegment> {
      val posMs = (presentationTimeUs.coerceAtLeast(0L) / 1_000L)
      return overlays.filter { posMs in it.timelineStartMs until it.timelineEndMs }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
      if (activeAt(presentationTimeUs).isEmpty()) hiddenSettings else visibleSettings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      val posMs = (presentationTimeUs.coerceAtLeast(0L) / 1_000L)
      val active = activeAt(presentationTimeUs)
      if (active.isEmpty()) return emptyBitmap

      val key =
        active.joinToString("|") { seg ->
          val rel = (posMs - seg.timelineStartMs).coerceIn(0L, seg.clip.durationMs)
          val eval = seg.clip.evaluatePropertiesAt(rel)
          val v = seg.clip.videoConfig
          "${seg.clip.clipId}:${eval.scale}:${eval.offsetX}:${eval.offsetY}:${eval.rotationDegrees}:${eval.opacity}:${v?.cropLeft}:${v?.cropTop}:${v?.cropRight}:${v?.cropBottom}"
        }
      if (key == cachedKey) return cachedBitmap

      val frameBitmap = Bitmap.createBitmap(frameWidth.coerceAtLeast(16), frameHeight.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
      val canvas = Canvas(frameBitmap)

      for (seg in active) {
        val rel = (posMs - seg.timelineStartMs).coerceIn(0L, seg.clip.durationMs)
        val eval = seg.clip.evaluatePropertiesAt(rel)
        val rawBmp = resolveOverlayBitmap(seg) ?: continue
        val bmp = cropOverlayBitmap(rawBmp, seg.clip.videoConfig)
        val baseFraction =
          when (seg.overlayKind) {
            OverlayContentKind.STICKER, OverlayContentKind.LOGO -> 0.28f
            OverlayContentKind.IMAGE, OverlayContentKind.VIDEO_PIP -> 0.36f
            OverlayContentKind.NONE -> 0.32f
          }
        val targetW = frameWidth * baseFraction * eval.scale.coerceIn(0.15f, 3.0f)
        val aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
        val targetH = (targetW / aspect.coerceAtLeast(0.2f)).coerceAtLeast(16f)

        val cx = frameWidth * (0.5f + eval.offsetX.coerceIn(-1f, 1f) * 0.5f)
        val cy = frameHeight * (0.5f + eval.offsetY.coerceIn(-1f, 1f) * 0.5f)

        val paint =
          Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (eval.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
          }

        val matrix = AndroidMatrix()
        matrix.postScale(targetW / bmp.width.coerceAtLeast(1), targetH / bmp.height.coerceAtLeast(1))
        matrix.postTranslate(-targetW / 2f, -targetH / 2f)
        matrix.postRotate(eval.rotationDegrees)
        matrix.postTranslate(cx, cy)
        canvas.drawBitmap(bmp, matrix, paint)
        if (bmp !== rawBmp) {
          runCatching { bmp.recycle() }
        }
      }

      if (cachedBitmap !== emptyBitmap) {
        runCatching { cachedBitmap.recycle() }
      }
      cachedKey = key
      cachedBitmap = frameBitmap
      return frameBitmap
    }

    private fun cropOverlayBitmap(
      source: Bitmap,
      cfg: com.example.core.model.VideoClipConfig?,
    ): Bitmap {
      if (cfg == null || !cfg.hasCrop) return source
      val sw = source.width.coerceAtLeast(2)
      val sh = source.height.coerceAtLeast(2)
      val leftPx = (cfg.cropLeft.coerceIn(0f, 0.85f) * sw).roundToInt().coerceIn(0, sw - 2)
      val topPx = (cfg.cropTop.coerceIn(0f, 0.85f) * sh).roundToInt().coerceIn(0, sh - 2)
      val rightPx = (cfg.cropRight.coerceIn(cfg.cropLeft + 0.1f, 1f) * sw).roundToInt().coerceIn(leftPx + 1, sw)
      val bottomPx = (cfg.cropBottom.coerceIn(cfg.cropTop + 0.1f, 1f) * sh).roundToInt().coerceIn(topPx + 1, sh)
      val cropW = (rightPx - leftPx).coerceAtLeast(1)
      val cropH = (bottomPx - topPx).coerceAtLeast(1)
      return runCatching { Bitmap.createBitmap(source, leftPx, topPx, cropW, cropH) }.getOrDefault(source)
    }

    private fun resolveOverlayBitmap(seg: PlannedOverlaySegment): Bitmap? {
      if (seg.overlayKind == OverlayContentKind.STICKER || seg.overlayKind == OverlayContentKind.LOGO) {
        val cacheKey = "sticker:${seg.stickerId}"
        return decodedSourceCache.getOrPut(cacheKey) {
          OverlayBitmapRasterizer.renderStickerBitmap(seg.stickerId)
        }
      }
      val file = seg.mediaSource?.sourceFile ?: return null
      return decodedSourceCache.getOrPut(file.absolutePath) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
      }
    }
  }

  fun plan(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): TimelineRenderPlan {
    val useProxies = renderTarget is MediaRenderTarget.Preview && renderTarget.useProxies
    return TimelineRenderPlanner.buildPlan(
      timeline = timeline,
      assets = assets,
      projectRootDir = projectRootDir,
      useProxies = useProxies,
      customLutPresets = customLutPresets,
    )
  }

  fun compile(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): CompiledCompositionResult {
    val renderPlan = plan(timeline, assets, projectRootDir, renderTarget, customLutPresets)
    if (renderPlan.isEmpty) {
      return CompiledCompositionResult.EmptyOrMissingMedia(
        "No hay clips multimedia locales en línea para procesar en este rango.",
      )
    }

    var appliedVideoEffectCount = 0

    // 1. Primary video track sequence built directly from validated segments & compensated gaps
    val sequences = mutableListOf<EditedMediaItemSequence>()
    if (renderPlan.primaryVideoSegments.isNotEmpty()) {
      val videoSeqBuilder =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
      for (segment in renderPlan.primaryVideoSegments) {
        if (segment.leadingGapMs > 0L) {
          videoSeqBuilder.addGap(segment.leadingGapMs * 1_000L)
        }
        val (item, effectCount) = buildVideoEditedItem(segment, renderTarget, customLutPresets)
        appliedVideoEffectCount += effectCount
        videoSeqBuilder.addItem(item)
      }
      sequences.add(videoSeqBuilder.build())
    }

    // 2. Auxiliary audio track sequences (one gap-aligned sequence per audible audio track)
    for (audioTrack in renderPlan.audioTracks) {
      val audioSeqBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
      for (segment in audioTrack.segments) {
        if (segment.leadingGapMs > 0L) {
          audioSeqBuilder.addGap(segment.leadingGapMs * 1_000L)
        }
        audioSeqBuilder.addItem(buildAudioEditedItem(segment))
      }
      sequences.add(audioSeqBuilder.build())
    }

    val compositionEffects =
      buildCompositionWideEffects(
        renderTarget = renderTarget,
        subtitleOverlays = renderPlan.subtitleOverlays,
        overlaySegments = renderPlan.overlaySegments,
      )
    val composition =
      Composition.Builder(sequences)
        .setEffects(compositionEffects)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()

    return CompiledCompositionResult.Ready(
      composition = composition,
      totalVideoItems = renderPlan.primaryVideoSegments.size,
      totalAudioItems = renderPlan.totalAudioSegmentCount,
      usedProxyCount = renderPlan.usedProxyCount,
      appliedVideoEffectCount = appliedVideoEffectCount,
      renderPlan = renderPlan,
    )
  }

  private fun buildVideoEditedItem(
    segment: PlannedVideoSegment,
    renderTarget: MediaRenderTarget,
    customLutPresets: List<LutPreset>,
  ): Pair<EditedMediaItem, Int> {
    val clip = segment.clip
    val sourceUri = toAndroidUri(segment.mediaSource)

    val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
    if (segment.isImage) {
      mediaItemBuilder.setImageDurationMs(
        segment.expectedRenderedDurationMs.coerceAtLeast(TimelineRenderPlanner.MIN_RENDER_SEGMENT_MS),
      )
    } else {
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(segment.sourceInMs)
          .setEndPositionMs(segment.sourceOutMs)
          .build(),
      )
    }
    val mediaItem = mediaItemBuilder.build()

    val videoEffects =
      KeyframeMedia3Effects.buildEffectsForClip(clip, segment.effectiveAdjustment, customLutPresets).let { baseEffects ->
        if (renderTarget is MediaRenderTarget.Preview) {
          baseEffects + Presentation.createForHeight(renderTarget.profile.targetHeight)
        } else {
          baseEffects
        }
      }
    val effectCount = videoEffects.size

    val clipBaseVolume = (clip.videoConfig?.volume ?: 1f).coerceIn(0f, 2f)
    val volumeKeyframes = clip.videoConfig?.keyframes.orEmpty()
    val hasAudibleKeyframeVolume =
      volumeKeyframes.any { ((it.volume ?: clipBaseVolume) * segment.trackVolume) > 0.001f }
    val effectiveStaticVolume = (clipBaseVolume * segment.trackVolume).coerceIn(0f, 2f)
    val fadeInMs = (clip.videoConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs)
    val fadeOutMs = (clip.videoConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs)
    val sourceSpanMs = (segment.sourceOutMs - segment.sourceInMs).coerceAtLeast(1L)
    val audioProcessors =
      buildAudioProcessors(
        clip = clip,
        baseVolume = clipBaseVolume,
        trackVolume = segment.trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = 0.0f,
        duckingWindows = segment.duckingWindows,
        effectiveSpeed = segment.effectiveSpeed,
        preserveAudioPitch = segment.preserveAudioPitch,
        sourceSpanMs = sourceSpanMs,
        speedCurve = segment.speedCurve,
      )

    val editedBuilder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(
          segment.isImage ||
            !segment.isTrackAudible ||
            segment.trackVolume <= 0.001f ||
            (effectiveStaticVolume <= 0.001f && !hasAudibleKeyframeVolume),
        )
        .setEffects(Effects(audioProcessors, videoEffects))

    if (segment.isImage) {
      editedBuilder.setFrameRate(30)
    } else if (segment.speedCurve.size >= 2) {
      editedBuilder.setSpeed(
        VariableCurveSpeedProvider(
          sourceSpanMs = sourceSpanMs,
          baseSpeed = segment.effectiveSpeed,
          speedCurve = segment.speedCurve,
        ),
      )
    } else if (abs(segment.effectiveSpeed - 1.0f) > 0.005f) {
      editedBuilder.setSpeed(ConstantSpeedProvider(segment.effectiveSpeed))
    }

    return editedBuilder.build() to effectCount
  }

  private fun buildAudioEditedItem(segment: PlannedAudioSegment): EditedMediaItem {
    val clip = segment.clip
    val sourceUri = toAndroidUri(segment.mediaSource)
    val mediaItem =
      MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.sourceInMs)
            .setEndPositionMs(segment.sourceOutMs)
            .build(),
        )
        .build()

    val clipBaseVolume = (clip.audioConfig?.volume ?: 1f).coerceIn(0f, 2f)
    val pan = (clip.audioConfig?.pan ?: 0f).coerceIn(-1f, 1f)
    val fadeInMs = (clip.audioConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs)
    val fadeOutMs = (clip.audioConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs)
    val sourceSpanMs = (segment.sourceOutMs - segment.sourceInMs).coerceAtLeast(1L)
    val audioProcessors =
      buildAudioProcessors(
        clip = clip,
        baseVolume = clipBaseVolume,
        trackVolume = segment.trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = pan,
        duckingWindows = segment.duckingWindows,
        effectiveSpeed = segment.effectiveSpeed,
        preserveAudioPitch = segment.preserveAudioPitch,
        sourceSpanMs = sourceSpanMs,
        speedCurve = segment.speedCurve,
      )

    val builder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveVideo(true)
        .setEffects(Effects(audioProcessors, emptyList()))

    if (segment.speedCurve.size >= 2) {
      builder.setSpeed(
        VariableCurveSpeedProvider(
          sourceSpanMs = sourceSpanMs,
          baseSpeed = segment.effectiveSpeed,
          speedCurve = segment.speedCurve,
        ),
      )
    } else if (abs(segment.effectiveSpeed - 1.0f) > 0.005f) {
      builder.setSpeed(ConstantSpeedProvider(segment.effectiveSpeed))
    }

    return builder.build()
  }

  private fun buildCompositionWideEffects(
    renderTarget: MediaRenderTarget,
    subtitleOverlays: List<PlannedSubtitleOverlay>,
    overlaySegments: List<PlannedOverlaySegment> = emptyList(),
  ): Effects {
    val (targetWidth, targetHeight) =
      when (renderTarget) {
        is MediaRenderTarget.Preview -> {
          val h = renderTarget.profile.targetHeight.coerceAtLeast(240)
          ((h * 16f / 9f).roundToInt()) to h
        }
        is MediaRenderTarget.Export ->
          renderTarget.config.width to renderTarget.config.height
      }

    val presentation =
      when (renderTarget) {
        is MediaRenderTarget.Preview ->
          Presentation.createForHeight(renderTarget.profile.targetHeight)
        is MediaRenderTarget.Export ->
          Presentation.createForWidthAndHeight(
            renderTarget.config.width,
            renderTarget.config.height,
            Presentation.LAYOUT_SCALE_TO_FIT,
          )
      }

    val effects = mutableListOf<Effect>(presentation)
    val bitmapOverlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
    if (overlaySegments.isNotEmpty()) {
      bitmapOverlays +=
        TimelineGraphicOverlayBitmapOverlay(
          overlays = overlaySegments,
          frameWidth = targetWidth,
          frameHeight = targetHeight,
        )
    }
    if (subtitleOverlays.isNotEmpty()) {
      bitmapOverlays +=
        TimelineSubtitleBitmapOverlay(
          subtitles = subtitleOverlays,
          frameWidth = targetWidth,
          frameHeight = targetHeight,
        )
    }
    if (bitmapOverlays.isNotEmpty()) {
      effects.add(OverlayEffect(bitmapOverlays))
    }

    return Effects(emptyList(), effects)
  }

  private fun buildAudioProcessors(
    clip: TimelineClip,
    baseVolume: Float,
    trackVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    pan: Float,
    duckingWindows: List<DuckingWindow>,
    effectiveSpeed: Float,
    preserveAudioPitch: Boolean,
    sourceSpanMs: Long,
    speedCurve: List<SpeedCurvePoint>,
  ): List<AudioProcessor> {
    val processors = mutableListOf<AudioProcessor>()

    if (!preserveAudioPitch && abs(effectiveSpeed - 1.0f) > 0.005f) {
      processors +=
        SonicAudioProcessor().apply {
          setSpeed(1.0f)
          setPitch(effectiveSpeed.coerceIn(TimelineRenderPlanner.MIN_SPEED, TimelineRenderPlanner.MAX_SPEED))
        }
    }

    val volumeKeyframes = clip.keyframes()
    val hasVolumeKeyframes = volumeKeyframes.any { it.volume != null }
    val combinedVolume = (baseVolume * trackVolume).coerceIn(0f, 2f)
    val needsEnvelope =
      combinedVolume !in 0.99f..1.01f ||
        abs(pan) > 0.005f ||
        fadeInMs > 0L ||
        fadeOutMs > 0L ||
        duckingWindows.isNotEmpty() ||
        hasVolumeKeyframes
    if (needsEnvelope) {
      processors +=
        EnvelopeAudioProcessor(
          baseVolume = baseVolume,
          fadeInMs = fadeInMs,
          fadeOutMs = fadeOutMs,
          clipStartOnTimelineMs = clip.timelineStartMs,
          clipDurationMs = clip.durationMs,
          duckingWindows = duckingWindows,
          volumeKeyframes = volumeKeyframes,
          trackVolume = trackVolume,
          clipSpeed = effectiveSpeed,
          pan = pan,
          sourceSpanMs = sourceSpanMs,
          speedCurve = speedCurve,
        )
    }

    return processors
  }

  private fun toAndroidUri(source: PlannedMediaSource): Uri =
    when {
      source.sourceFile != null -> Uri.fromFile(source.sourceFile)
      !source.externalUri.isNullOrBlank() -> Uri.parse(source.externalUri)
      else -> Uri.EMPTY
    }
}

