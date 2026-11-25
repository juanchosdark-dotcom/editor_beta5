package com.example.core.media.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

sealed interface ThumbnailLoadResult {
  data class Ready(val bitmap: Bitmap) : ThumbnailLoadResult
  data class Skipped(val reason: String) : ThumbnailLoadResult
}

/**
 * Memory-bounded thumbnail decoder & [LruCache] running on [Dispatchers.IO].
 * Respects active [DeviceProfile] RAM limits and guards against HDR / OOM crashes.
 */
class VideoThumbnailCache(
  initialProfile: DeviceProfile = DeviceProfile(),
) {

  private var activeProfile: DeviceProfile = initialProfile
  private var decodeSemaphore = Semaphore(permitsForProfile(initialProfile))

  private val cache =
    object : LruCache<String, Bitmap>(computeCacheMaxKb(initialProfile)) {
      override fun sizeOf(key: String, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

  fun trimToProfile(profile: DeviceProfile) {
    activeProfile = profile
    decodeSemaphore = Semaphore(permitsForProfile(profile))
    val targetKb = computeCacheMaxKb(profile)
    if (!profile.allowFrameSampling) {
      cache.evictAll()
    } else {
      cache.trimToSize(targetKb)
    }
  }

  fun getCached(key: String): Bitmap? = cache.get(key)

  suspend fun loadThumbnail(
    asset: MediaAsset,
    projectRootDir: File,
    sourceTimeMs: Long,
    profile: DeviceProfile = activeProfile,
  ): ThumbnailLoadResult =
    withContext(Dispatchers.IO) {
      if (!profile.allowFrameSampling) {
        return@withContext ThumbnailLoadResult.Skipped(
          "Miniaturas pausadas para proteger RAM (${profile.availableRamMb} MB libres)",
        )
      }

      if (
        asset.fingerprint.isHdr &&
        profile.tier == DeviceMemoryTier.CONSERVATIVE &&
        asset.proxyRelativePath.isNullOrBlank()
      ) {
        return@withContext ThumbnailLoadResult.Skipped(
          "Miniatura HDR omitida en modo conservador (genera proxy)",
        )
      }

      val (targetW, targetH) =
        computeThumbnailDimensions(
          profile = profile,
          sourceWidth = asset.fingerprint.width,
          sourceHeight = asset.fingerprint.height,
          rotationDegrees = asset.fingerprint.rotationDegrees,
        )
      val quantizedMs = (sourceTimeMs / 250L) * 250L
      val key = "${asset.assetId}_${quantizedMs}_${targetW}x${targetH}_${asset.fingerprint.rotationDegrees}"

      cache.get(key)?.let { cached ->
        if (!cached.isRecycled) {
          return@withContext ThumbnailLoadResult.Ready(cached)
        }
        cache.remove(key)
      }

      val sourceFile = resolveThumbSourceFile(asset, projectRootDir)
        ?: return@withContext ThumbnailLoadResult.Skipped("Archivo multimedia no disponible")

      decodeSemaphore.withPermit {
        cache.get(key)?.let { cached ->
          if (!cached.isRecycled) {
            return@withPermit ThumbnailLoadResult.Ready(cached)
          }
        }
        val decoded =
          if (asset.type == MediaType.IMAGE) {
            decodeImageBitmapSafe(
              file = sourceFile,
              targetW = targetW,
              targetH = targetH,
            )
          } else {
            decodeScaledBitmapSafe(
              file = sourceFile,
              timeUs = sourceTimeMs.coerceAtLeast(0L) * 1_000L,
              targetW = targetW,
              targetH = targetH,
            )
          }
        decoded?.let { bmp ->
          cache.put(key, bmp)
          ThumbnailLoadResult.Ready(bmp)
        } ?: ThumbnailLoadResult.Skipped(
          if (asset.fingerprint.isHdr || asset.fingerprint.isHighResolution) {
            "Miniatura omitida por protección HDR/alta resolución"
          } else {
            "No se pudo decodificar el fotograma"
          },
        )
      }
    }

  private fun resolveThumbSourceFile(asset: MediaAsset, projectRootDir: File): File? {
    val proxy =
      asset.proxyRelativePath
        ?.let { File(projectRootDir, it) }
        ?.takeIf { it.exists() && it.length() > 0L }
    if (proxy != null) return proxy

    return File(projectRootDir, asset.relativePath).takeIf { it.exists() && it.length() > 0L }
  }

  private fun decodeScaledBitmapSafe(
    file: File,
    timeUs: Long,
    targetW: Int,
    targetH: Int,
  ): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val rawOrScaled =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
          runCatching {
            retriever.getScaledFrameAtTime(
              timeUs,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              targetW,
              targetH,
            )
          }.getOrNull()
        } else {
          null
        } ?: runCatching {
          retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null

      if (rawOrScaled.width <= targetW && rawOrScaled.height <= targetH) {
        rawOrScaled
      } else {
        val scaled = Bitmap.createScaledBitmap(rawOrScaled, targetW, targetH, true)
        if (scaled !== rawOrScaled) rawOrScaled.recycle()
        scaled
      }
    } catch (_: OutOfMemoryError) {
      cache.trimToSize((cache.maxSize() / 2).coerceAtLeast(512))
      null
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun decodeImageBitmapSafe(
    file: File,
    targetW: Int,
    targetH: Int,
  ): Bitmap? =
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        while ((bounds.outWidth / sample) > targetW * 2 && (bounds.outHeight / sample) > targetH * 2) {
          sample *= 2
        }
      }
      val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
      val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
      if (bmp.width <= targetW && bmp.height <= targetH) {
        bmp
      } else {
        val scaled = Bitmap.createScaledBitmap(bmp, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        scaled
      }
    } catch (_: OutOfMemoryError) {
      cache.trimToSize((cache.maxSize() / 2).coerceAtLeast(512))
      null
    } catch (_: Throwable) {
      null
    }

  fun clear() {
    cache.evictAll()
  }

  companion object {
    fun permitsForProfile(profile: DeviceProfile): Int =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 1
        DeviceMemoryTier.BALANCED -> 2
        DeviceMemoryTier.PERFORMANCE -> 3
      }

    fun maxThumbnailsPerClip(profile: DeviceProfile): Int {
      if (!profile.allowFrameSampling) return 0
      return when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 1
        DeviceMemoryTier.BALANCED -> 3
        DeviceMemoryTier.PERFORMANCE -> 5
      }
    }

    fun computeCacheMaxKb(profile: DeviceProfile): Int =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 4 * 1024
        DeviceMemoryTier.BALANCED -> 8 * 1024
        DeviceMemoryTier.PERFORMANCE -> 16 * 1024
      }

    fun computeThumbnailDimensions(
      profile: DeviceProfile,
      sourceWidth: Int?,
      sourceHeight: Int?,
      rotationDegrees: Int,
    ): Pair<Int, Int> {
      val maxDim =
        when (profile.tier) {
          DeviceMemoryTier.CONSERVATIVE -> 96
          DeviceMemoryTier.BALANCED -> 128
          DeviceMemoryTier.PERFORMANCE -> 160
        }.coerceAtMost(profile.maxAnalysisWidthPx.coerceAtLeast(64))

      val normalizedRot = ((rotationDegrees % 360) + 360) % 360
      val orientedW = if (normalizedRot == 90 || normalizedRot == 270) sourceHeight else sourceWidth
      val orientedH = if (normalizedRot == 90 || normalizedRot == 270) sourceWidth else sourceHeight

      val w = (orientedW ?: 16).coerceAtLeast(1)
      val h = (orientedH ?: 9).coerceAtLeast(1)
      return if (w >= h) {
        val outH = ((maxDim.toFloat() * h) / w).roundToInt().coerceIn(36, maxDim)
        maxDim to outH
      } else {
        val outW = ((maxDim.toFloat() * w) / h).roundToInt().coerceIn(36, maxDim)
        outW to maxDim
      }
    }
  }
}
