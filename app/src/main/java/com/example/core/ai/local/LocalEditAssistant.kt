package com.example.core.ai.local

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.core.ai.api.ClipLumaMetrics
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.ai.api.EditFixCategory
import com.example.core.ai.api.EditFixPayload
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.LocalEditAnalysisReport
import com.example.core.ai.api.LocalEditAssistEngine
import com.example.core.ai.api.LocalEditSuggestion
import com.example.core.ai.api.ProjectEditMetrics
import com.example.core.ai.api.ResourceGuard
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Local personal editing assistant that inspects the active clip and project metrics
 * strictly within the memory/thermal budget defined by [DeviceProfile].
 */
class LocalEditAssistant(
  private val resourceGuard: ResourceGuard,
  private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : LocalEditAssistEngine {

  override suspend fun analyzeForEdits(
    timeline: TimelineState,
    selectedClip: TimelineClip?,
    asset: MediaAsset?,
    projectRootDir: File,
    settings: LocalAiSettings,
  ): Result<LocalEditAnalysisReport> =
    withContext(workDispatcher) {
      runCatching {
        val snapshot = resourceGuard.refreshNow(settings)
        val profile = resourceGuard.resolveDeviceProfile(settings)
        if (!snapshot.canRunInference) {
          error(snapshot.blockReason ?: "Análisis local bloqueado por DeviceProfile")
        }

        val targetClip =
          selectedClip
            ?: timeline.clips.firstOrNull { it.kind == ClipKind.VIDEO && it.isEnabled }
            ?: timeline.clips.firstOrNull()
            ?: error("Agrega al menos un clip en la línea de tiempo para analizar")

        val projectMetrics = collectProjectMetrics(timeline = timeline, targetClip = targetClip)
        val proxyFile =
          asset?.proxyRelativePath
            ?.let { File(projectRootDir, it) }
            ?.takeIf { it.exists() && it.length() > 0L }
        val originalFile =
          asset?.let { File(projectRootDir, it.relativePath) }
            ?.takeIf { it.exists() && it.length() > 0L }

        val preferProxyForAnalysis =
          asset != null &&
            proxyFile != null &&
            (asset.fingerprint.isHdr || asset.fingerprint.isHighResolution || profile.tier != DeviceMemoryTier.PERFORMANCE)
        val mediaFile = if (preferProxyForAnalysis) proxyFile else (originalFile ?: proxyFile)

        val blockedByConservativeHdr =
          asset?.fingerprint?.isHdr == true &&
            profile.tier == DeviceMemoryTier.CONSERVATIVE &&
            proxyFile == null

        val clipMetrics =
          if (profile.allowFrameSampling && !blockedByConservativeHdr && targetClip.kind == ClipKind.VIDEO && mediaFile != null) {
            sampleClipLumaMetrics(
              file = mediaFile,
              clip = targetClip,
              maxFrames = profile.maxSampledFrames,
              maxWidthPx = profile.maxAnalysisWidthPx,
            )
          } else {
            null
          }

        val memoryNote =
          when {
            clipMetrics != null && preferProxyForAnalysis ->
              "Muestreo real vía proxy ligero (${clipMetrics.sampledFrames} frames a ${profile.maxAnalysisWidthPx}px) bajo perfil ${profile.tier.label}."
            clipMetrics != null ->
              "Muestreo real (${clipMetrics.sampledFrames} frames a ${profile.maxAnalysisWidthPx}px) bajo perfil ${profile.tier.label}."
            blockedByConservativeHdr ->
              "Muestreo HDR omitido en perfil conservador para proteger el decodificador; sugerencias basadas en métricas del proyecto."
            !profile.allowFrameSampling ->
              "Muestreo de frames omitido para proteger RAM (${profile.availableRamMb} MB libres); análisis basado en métricas de línea de tiempo."
            else ->
              "Análisis ligero de métricas del proyecto y estructura temporal (${profile.tier.label})."
          }

        val suggestions =
          generateSuggestions(
            timeline = timeline,
            targetClip = targetClip,
            projectMetrics = projectMetrics,
            clipMetrics = clipMetrics,
            deviceProfile = profile,
          )

        LocalEditAnalysisReport(
          deviceProfile = profile,
          clipMetrics = clipMetrics,
          projectMetrics = projectMetrics,
          suggestions = suggestions,
          memoryGuardNote = memoryNote,
        )
      }
    }

  private fun sampleClipLumaMetrics(
    file: File,
    clip: TimelineClip,
    maxFrames: Int,
    maxWidthPx: Int,
  ): ClipLumaMetrics? {
    if (maxFrames <= 0) return null
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val sourceSpanMs = (clip.sourceOutMs - clip.sourceInMs).coerceAtLeast(clip.durationMs).coerceAtLeast(300L)
      val stepMs = (sourceSpanMs / (maxFrames + 1).toLong()).coerceAtLeast(100L)

      var totalLuma = 0f
      var totalSpread = 0f
      var darkFrames = 0
      var clippedFrames = 0
      var sampledCount = 0

      for (idx in 1..maxFrames) {
        val sampleUs = (clip.sourceInMs + stepMs * idx) * 1_000L
        val bitmap = extractScaledFrame(retriever, sampleUs, maxWidthPx) ?: continue
        try {
          val stats = computeBitmapLumaStats(bitmap)
          totalLuma += stats.avgLuma
          totalSpread += stats.spread
          if (stats.avgLuma < 0.34f) darkFrames++
          if (stats.brightPixelRatio > 0.22f) clippedFrames++
          sampledCount++
        } finally {
          bitmap.recycle()
        }
      }

      if (sampledCount == 0) {
        null
      } else {
        ClipLumaMetrics(
          sampledFrames = sampledCount,
          averageLuma = totalLuma / sampledCount.toFloat(),
          contrastSpread = totalSpread / sampledCount.toFloat(),
          darkFrameRatio = darkFrames.toFloat() / sampledCount.toFloat(),
          highlightsClippedRatio = clippedFrames.toFloat() / sampledCount.toFloat(),
        )
      }
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun extractScaledFrame(
    retriever: MediaMetadataRetriever,
    timeUs: Long,
    maxWidthPx: Int,
  ): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      val targetHeight = ((maxWidthPx * 9f) / 16f).roundToInt().coerceAtLeast(90)
      val scaled =
        runCatching {
          retriever.getScaledFrameAtTime(
            timeUs,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            maxWidthPx,
            targetHeight,
          )
        }.getOrNull()
      if (scaled != null) return scaled
    }
    val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
    if (raw.width <= maxWidthPx) return raw
    val targetH = ((raw.height.toFloat() / raw.width.toFloat()) * maxWidthPx).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(raw, maxWidthPx, targetH, true).also {
      if (it !== raw) raw.recycle()
    }
  }

  private data class FrameLumaStats(
    val avgLuma: Float,
    val spread: Float,
    val brightPixelRatio: Float,
  )

  private fun computeBitmapLumaStats(bitmap: Bitmap): FrameLumaStats {
    val width = bitmap.width
    val height = bitmap.height
    val stride = maxOf(1, (width * height) / 1_600)
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var count = 0
    var sum = 0.0
    var sumSq = 0.0
    var brightPixels = 0

    var idx = 0
    while (idx < pixels.size) {
      val color = pixels[idx]
      val r = (color shr 16) and 0xFF
      val g = (color shr 8) and 0xFF
      val b = color and 0xFF
      val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
      sum += luma
      sumSq += luma * luma
      if (luma > 0.92) brightPixels++
      count++
      idx += stride
    }

    if (count == 0) return FrameLumaStats(0.5f, 0.2f, 0f)
    val mean = sum / count
    val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
    val stdDev = kotlin.math.sqrt(variance).toFloat()
    return FrameLumaStats(
      avgLuma = mean.toFloat(),
      spread = stdDev,
      brightPixelRatio = brightPixels.toFloat() / count.toFloat(),
    )
  }

  companion object {
    fun collectProjectMetrics(
      timeline: TimelineState,
      targetClip: TimelineClip,
    ): ProjectEditMetrics {
      val hasAdj =
        timeline.clips.any {
          it.isEnabled &&
            it.kind == ClipKind.ADJUSTMENT &&
            it.timelineStartMs < targetClip.timelineEndMs &&
            it.timelineEndMs > targetClip.timelineStartMs
        }
      val hasAudio = targetClip.kind == ClipKind.VIDEO || targetClip.kind == ClipKind.AUDIO
      val hasFades =
        when (targetClip.kind) {
          ClipKind.VIDEO ->
            (targetClip.videoConfig?.fadeInMs ?: 0L) > 0L ||
              (targetClip.videoConfig?.fadeOutMs ?: 0L) > 0L
          ClipKind.AUDIO ->
            (targetClip.audioConfig?.fadeInMs ?: 0L) > 0L ||
              (targetClip.audioConfig?.fadeOutMs ?: 0L) > 0L
          else -> false
        }
      return ProjectEditMetrics(
        totalClips = timeline.clips.size,
        videoClipCount = timeline.clips.count { it.kind == ClipKind.VIDEO },
        timelineDurationMs = timeline.totalDurationMs,
        markerCount = timeline.markers.size,
        hasAdjustmentLayer = hasAdj,
        selectedClipLabel = targetClip.label,
        selectedClipDurationMs = targetClip.durationMs,
        selectedClipKeyframeCount = targetClip.videoConfig?.keyframes?.size ?: 0,
        selectedClipHasFades = hasFades,
        selectedClipHasAudio = hasAudio,
      )
    }

    /**
     * Generates deterministic, actionable editing fixes respecting [DeviceProfile] limits.
     */
    fun generateSuggestions(
      timeline: TimelineState,
      targetClip: TimelineClip,
      projectMetrics: ProjectEditMetrics,
      clipMetrics: ClipLumaMetrics?,
      deviceProfile: DeviceProfile,
    ): List<LocalEditSuggestion> {
      val list = mutableListOf<LocalEditSuggestion>()

      // 1. Light & Color correction suggestion
      if (targetClip.kind == ClipKind.VIDEO || targetClip.kind == ClipKind.ADJUSTMENT) {
        when {
          clipMetrics != null && (clipMetrics.averageLuma < 0.40f || clipMetrics.darkFrameRatio >= 0.45f) -> {
            list +=
              LocalEditSuggestion(
                suggestionId = "fix_light_lift_${targetClip.clipId}",
                category = EditFixCategory.COLOR_LIGHT,
                title = "Realzar exposición y contraste en ${targetClip.label}",
                rationale = "Se detectó luminancia media baja; eleva brillo y microcontraste sin quemar altas luces.",
                metricsEvidence =
                  "Luma media ${(clipMetrics.averageLuma * 100).roundToInt()}% · ${(clipMetrics.darkFrameRatio * 100).roundToInt()}% frames oscuros",
                targetClipId = targetClip.clipId,
                payload =
                  EditFixPayload.ColorLightFix(
                    targetClipId = targetClip.clipId,
                    brightness = 0.12f,
                    contrast = 1.12f,
                    saturation = 1.07f,
                    spanStartMs = targetClip.timelineStartMs,
                    spanEndMs = targetClip.timelineEndMs,
                  ),
              )
          }
          clipMetrics != null && clipMetrics.highlightsClippedRatio > 0.20f -> {
            list +=
              LocalEditSuggestion(
                suggestionId = "fix_light_recover_${targetClip.clipId}",
                category = EditFixCategory.COLOR_LIGHT,
                title = "Recuperar altas luces en ${targetClip.label}",
                rationale = "Frames con zonas sobreexpuestas; atenúa brillo y suaviza contraste.",
                metricsEvidence =
                  "Sobreexposición ${(clipMetrics.highlightsClippedRatio * 100).roundToInt()}% · Luma ${(clipMetrics.averageLuma * 100).roundToInt()}%",
                targetClipId = targetClip.clipId,
                payload =
                  EditFixPayload.ColorLightFix(
                    targetClipId = targetClip.clipId,
                    brightness = -0.08f,
                    contrast = 0.94f,
                    saturation = 1.04f,
                    spanStartMs = targetClip.timelineStartMs,
                    spanEndMs = targetClip.timelineEndMs,
                  ),
              )
          }
          !projectMetrics.hasAdjustmentLayer -> {
            list +=
              LocalEditSuggestion(
                suggestionId = "fix_color_balance_${targetClip.clipId}",
                category = EditFixCategory.COLOR_LIGHT,
                title = "Añadir capa base de color para ${targetClip.label}",
                rationale = "El clip no tiene capa de ajuste activa; aplica contraste balanceado y saturación limpia.",
                metricsEvidence =
                  if (clipMetrics != null) {
                    "Contraste ${(clipMetrics.contrastSpread * 100).roundToInt()}% · ${projectMetrics.videoClipCount} clips video"
                  } else {
                    "Sin capa LUT sobre ${targetClip.durationMs / 1000.0}s (${deviceProfile.tier.label})"
                  },
                targetClipId = targetClip.clipId,
                payload =
                  EditFixPayload.ColorLightFix(
                    targetClipId = targetClip.clipId,
                    brightness = 0.04f,
                    contrast = 1.10f,
                    saturation = 1.08f,
                    spanStartMs = targetClip.timelineStartMs,
                    spanEndMs = targetClip.timelineEndMs,
                  ),
              )
          }
        }
      }

      // 2. Cut markers suggestion
      val markersInsideClip =
        timeline.markers.count { it.positionMs in (targetClip.timelineStartMs + 250L) until (targetClip.timelineEndMs - 250L) }
      if (targetClip.durationMs >= 2_200L && markersInsideClip == 0) {
        val cutCount = if (targetClip.durationMs >= 6_000L && deviceProfile.tier != DeviceMemoryTier.CONSERVATIVE) 3 else 2
        val step = targetClip.durationMs / (cutCount + 1)
        val positions =
          (1..cutCount).mapNotNull { idx ->
            val pos = targetClip.timelineStartMs + step * idx
            val tooCloseToExisting = timeline.markers.any { abs(it.positionMs - pos) < 350L }
            if (tooCloseToExisting) null else pos
          }
        if (positions.isNotEmpty()) {
          list +=
            LocalEditSuggestion(
              suggestionId = "fix_cuts_${targetClip.clipId}",
              category = EditFixCategory.CUT_MARKERS,
              title = "Proponer ${positions.size} marcas de corte rítmico",
              rationale = "Divide tramos largos (${targetClip.durationMs / 1000}s) en puntos limpios para insertar B-roll o cortes.",
              metricsEvidence = "$markersInsideClip marcadores dentro del clip · Duración ${targetClip.durationMs} ms",
              targetClipId = targetClip.clipId,
              payload =
                EditFixPayload.CutMarkersFix(
                  positionsMs = positions,
                  labels = positions.indices.map { "Corte IA ${it + 1}" },
                ),
            )
        }
      }

      // 3. Initial keyframes (scale & subtle vertical framing)
      if (targetClip.kind == ClipKind.VIDEO && targetClip.durationMs >= 1_200L && projectMetrics.selectedClipKeyframeCount == 0) {
        val endOffset = (targetClip.durationMs * 0.85f).roundToInt().toLong().coerceAtLeast(600L)
        list +=
          LocalEditSuggestion(
            suggestionId = "fix_keyframes_${targetClip.clipId}",
            category = EditFixCategory.KEYFRAME_MOTION,
            title = "Keyframes iniciales de zoom suave (1.00x → 1.10x)",
            rationale = "Añade movimiento de escala y recentrado progresivo para dar dinamismo al plano.",
            metricsEvidence = "0 keyframes actuales · Clip de ${targetClip.durationMs} ms",
            targetClipId = targetClip.clipId,
            payload =
              EditFixPayload.InitialKeyframesFix(
                targetClipId = targetClip.clipId,
                keyframes =
                  listOf(
                    ClipKeyframe(
                      keyframeId = "kf_ai_in_${targetClip.clipId}",
                      offsetMs = 0L,
                      scale = 1.0f,
                      opacity = 1.0f,
                      offsetX = 0.0f,
                      offsetY = 0.02f,
                      rotationDegrees = 0.0f,
                    ),
                    ClipKeyframe(
                      keyframeId = "kf_ai_out_${targetClip.clipId}",
                      offsetMs = endOffset,
                      scale = 1.10f,
                      opacity = 1.0f,
                      offsetX = 0.0f,
                      offsetY = 0.0f,
                      rotationDegrees = 0.0f,
                    ),
                  ),
              ),
          )
      }

      // 4. Audio fade-in / fade-out smoothness
      if (projectMetrics.selectedClipHasAudio && !projectMetrics.selectedClipHasFades && targetClip.durationMs >= 1_500L) {
        val currentVol = targetClip.videoConfig?.volume ?: targetClip.audioConfig?.volume ?: 1.0f
        list +=
          LocalEditSuggestion(
            suggestionId = "fix_audio_fade_${targetClip.clipId}",
            category = EditFixCategory.AUDIO_FADE,
            title = "Suavizar entrada y salida de audio (Fade In/Out)",
            rationale = "Evita clics en los extremos del corte con rampas cortas de 250 ms y 350 ms.",
            metricsEvidence = "Fade actual: 0 ms · Volumen ${(currentVol * 100).roundToInt()}%",
            targetClipId = targetClip.clipId,
            payload =
              EditFixPayload.AudioFadeFix(
                targetClipId = targetClip.clipId,
                fadeInMs = 250L,
                fadeOutMs = 350L,
                volume = currentVol.coerceIn(0.65f, 1.0f),
              ),
          )
      }

      return list.take(deviceProfile.maxSuggestedFixes)
    }
  }
}
