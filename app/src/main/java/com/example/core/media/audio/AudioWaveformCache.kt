package com.example.core.media.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.model.MediaAsset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface WaveformPeaksResult {
  data class Ready(
    val peaks: FloatArray,
    val sourceDurationMs: Long,
    val fromDiskCache: Boolean = false,
  ) : WaveformPeaksResult

  data class Unavailable(val reason: String) : WaveformPeaksResult
}

internal data class CachedWaveformEntry(
  val peaks: FloatArray,
  val sourceDurationMs: Long,
)

/**
 * Non-blocking audio waveform peak generator & two-tier cache (in-memory [LruCache] +
 * compact binary `.wf` files stored strictly inside `cacheDir/waveforms/`).
 *
 * - Decodes PCM audio off the main thread on [ioDispatcher] using streaming bucket accumulation
 *   (constant O(bucketCount) memory footprint).
 * - Slices cached full-source waveforms deterministically for any trimmed or split
 *   `[sourceInMs, sourceOutMs]` clip range without re-decoding the underlying media file.
 */
class AudioWaveformCache(
  private val waveformsCacheDir: File,
  initialProfile: DeviceProfile = DeviceProfile(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private var activeProfile: DeviceProfile = initialProfile
  private var decodeSemaphore = Semaphore(permitsForProfile(initialProfile))

  private val memoryCache =
    object : LruCache<String, CachedWaveformEntry>(MAX_MEMORY_ENTRIES) {}

  fun trimToProfile(profile: DeviceProfile) {
    activeProfile = profile
    decodeSemaphore = Semaphore(permitsForProfile(profile))
    if (profile.tier == DeviceMemoryTier.CONSERVATIVE) {
      memoryCache.trimToSize(MAX_MEMORY_ENTRIES / 2)
    }
  }

  fun clearMemoryCache() {
    memoryCache.evictAll()
  }

  suspend fun loadWaveformSlice(
    asset: MediaAsset,
    projectRootDir: File,
    sourceInMs: Long,
    sourceOutMs: Long,
    targetBuckets: Int = DEFAULT_RENDER_BUCKETS,
  ): WaveformPeaksResult {
    val fullResult = loadOrGenerateSourceWaveform(asset, projectRootDir)
    return when (fullResult) {
      is WaveformPeaksResult.Unavailable -> fullResult
      is WaveformPeaksResult.Ready -> {
        val safeDurationMs = max(fullResult.sourceDurationMs, max(asset.fingerprint.durationMs, sourceOutMs))
        val sliced =
          slicePeaksForSourceRange(
            fullPeaks = fullResult.peaks,
            sourceDurationMs = safeDurationMs,
            sourceInMs = sourceInMs,
            sourceOutMs = sourceOutMs,
            targetBuckets = targetBuckets,
          )
        WaveformPeaksResult.Ready(
          peaks = sliced,
          sourceDurationMs = safeDurationMs,
          fromDiskCache = fullResult.fromDiskCache,
        )
      }
    }
  }

  suspend fun loadOrGenerateSourceWaveform(
    asset: MediaAsset,
    projectRootDir: File,
    sourceBuckets: Int = DEFAULT_SOURCE_BUCKETS,
  ): WaveformPeaksResult =
    withContext(ioDispatcher) {
      val safeBuckets = sourceBuckets.coerceIn(16, 1024)
      val cacheKey = buildCacheKey(asset, safeBuckets)

      memoryCache.get(cacheKey)?.let { cached ->
        return@withContext WaveformPeaksResult.Ready(
          peaks = cached.peaks,
          sourceDurationMs = cached.sourceDurationMs,
          fromDiskCache = false,
        )
      }

      val cacheFile = File(waveformsCacheDir, "$cacheKey$WAVEFORM_EXTENSION")
      readWaveformFile(cacheFile)?.let { diskEntry ->
        memoryCache.put(cacheKey, diskEntry)
        return@withContext WaveformPeaksResult.Ready(
          peaks = diskEntry.peaks,
          sourceDurationMs = diskEntry.sourceDurationMs,
          fromDiskCache = true,
        )
      }

      val mediaFile = resolveAudioSourceFile(asset, projectRootDir)
        ?: return@withContext WaveformPeaksResult.Unavailable("Archivo multimedia no disponible")

      decodeSemaphore.withPermit {
        memoryCache.get(cacheKey)?.let { cached ->
          return@withPermit WaveformPeaksResult.Ready(
            peaks = cached.peaks,
            sourceDurationMs = cached.sourceDurationMs,
            fromDiskCache = false,
          )
        }
        readWaveformFile(cacheFile)?.let { diskEntry ->
          memoryCache.put(cacheKey, diskEntry)
          return@withPermit WaveformPeaksResult.Ready(
            peaks = diskEntry.peaks,
            sourceDurationMs = diskEntry.sourceDurationMs,
            fromDiskCache = true,
          )
        }

        val extracted =
          extractNormalizedPeaksFromFile(
            mediaFile = mediaFile,
            fallbackDurationMs = asset.fingerprint.durationMs,
            bucketCount = safeBuckets,
          ) ?: return@withPermit WaveformPeaksResult.Unavailable("Sin pista de audio decodificable")

        writeWaveformFile(cacheFile, extracted)
        memoryCache.put(cacheKey, extracted)
        WaveformPeaksResult.Ready(
          peaks = extracted.peaks,
          sourceDurationMs = extracted.sourceDurationMs,
          fromDiskCache = false,
        )
      }
    }

  private fun resolveAudioSourceFile(asset: MediaAsset, projectRootDir: File): File? {
    val primary = File(projectRootDir, asset.relativePath).takeIf { it.exists() && it.length() > 0L }
    if (primary != null) return primary
    return asset.proxyRelativePath
      ?.let { File(projectRootDir, it) }
      ?.takeIf { it.exists() && it.length() > 0L }
  }

  private fun extractNormalizedPeaksFromFile(
    mediaFile: File,
    fallbackDurationMs: Long,
    bucketCount: Int,
  ): CachedWaveformEntry? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
      extractor.setDataSource(mediaFile.absolutePath)
      val audioTrackIdx = findAudioTrackIndex(extractor) ?: return null
      extractor.selectTrack(audioTrackIdx)
      val format = extractor.getTrackFormat(audioTrackIdx)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
      val durationUs =
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
          format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1_000L)
        } else {
          fallbackDurationMs.coerceAtLeast(1_000L) * 1_000L
        }

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(format, null, null, 0)
      codec.start()

      val peaks =
        decodeStreamIntoBuckets(
          extractor = extractor,
          codec = codec,
          initialFormat = format,
          durationUs = durationUs,
          bucketCount = bucketCount,
        )
      CachedWaveformEntry(
        peaks = peaks,
        sourceDurationMs = (durationUs / 1_000L).coerceAtLeast(1L),
      )
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
    for (i in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
      if (mime.startsWith("audio/")) return i
    }
    return null
  }

  private fun decodeStreamIntoBuckets(
    extractor: MediaExtractor,
    codec: MediaCodec,
    initialFormat: MediaFormat,
    durationUs: Long,
    bucketCount: Int,
  ): FloatArray {
    val bufferInfo = MediaCodec.BufferInfo()
    var sampleRate =
      if (initialFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
        initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
      } else {
        44_100
      }
    var channelCount =
      if (initialFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
        initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
      } else {
        1
      }

    val bucketPeaks = FloatArray(bucketCount)
    val bucketSumSquares = DoubleArray(bucketCount)
    val bucketCounts = IntArray(bucketCount)
    val safeDurationUs = durationUs.coerceAtLeast(1_000L)
    var decodedFramesTotal = 0L

    var inputDone = false
    var outputDone = false

    while (!outputDone) {
      if (!inputDone) {
        val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inIdx >= 0) {
          val inBuf = codec.getInputBuffer(inIdx)!!
          val size = extractor.readSampleData(inBuf, 0)
          if (size < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }

      val outIdx = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
      when {
        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val outFmt = codec.outputFormat
          if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
          }
          if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
          }
        }
        outIdx >= 0 -> {
          val outBuf = codec.getOutputBuffer(outIdx)
          if (outBuf != null && bufferInfo.size > 0) {
            outBuf.position(bufferInfo.offset)
            outBuf.limit(bufferInfo.offset + bufferInfo.size)
            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frameCount = shortBuf.remaining() / channelCount
            val baseUs =
              if (bufferInfo.presentationTimeUs > 0L) {
                bufferInfo.presentationTimeUs
              } else {
                (decodedFramesTotal * 1_000_000L) / sampleRate.toLong()
              }

            for (frame in 0 until frameCount) {
              var frameMaxAbs = 0f
              for (ch in 0 until channelCount) {
                val sampleAbs = abs(shortBuf.get().toInt()) / 32768f
                if (sampleAbs > frameMaxAbs) frameMaxAbs = sampleAbs
              }
              val frameTimeUs = baseUs + ((frame * 1_000_000L) / sampleRate.toLong())
              val ratio = (frameTimeUs.toDouble() / safeDurationUs.toDouble()).coerceIn(0.0, 0.999999)
              val bucketIdx = (ratio * bucketCount).toInt().coerceIn(0, bucketCount - 1)
              if (frameMaxAbs > bucketPeaks[bucketIdx]) {
                bucketPeaks[bucketIdx] = frameMaxAbs
              }
              bucketSumSquares[bucketIdx] += (frameMaxAbs * frameMaxAbs).toDouble()
              bucketCounts[bucketIdx]++
            }
            decodedFramesTotal += frameCount
          }
          codec.releaseOutputBuffer(outIdx, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true
          }
        }
      }
    }

    return finalizeBucketEnvelope(bucketPeaks, bucketSumSquares, bucketCounts)
  }

  companion object {
    const val DEFAULT_SOURCE_BUCKETS = 240
    const val DEFAULT_RENDER_BUCKETS = 48
    private const val MAX_MEMORY_ENTRIES = 64
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val WAVEFORM_MAGIC = 0x57463031 // "WF01"
    private const val WAVEFORM_VERSION = 1
    private const val WAVEFORM_EXTENSION = ".wf"

    fun permitsForProfile(profile: DeviceProfile): Int =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 1
        DeviceMemoryTier.BALANCED -> 2
        DeviceMemoryTier.PERFORMANCE -> 3
      }

    fun buildCacheKey(
      asset: MediaAsset,
      bucketCount: Int = DEFAULT_SOURCE_BUCKETS,
    ): String {
      val safeId = asset.assetId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
      val safeHash =
        asset.fingerprint.partialSha256
          .replace(Regex("[^a-zA-Z0-9_-]"), "_")
          .take(16)
          .ifBlank { "nohash" }
      return "${safeId}_${safeHash}_${asset.fingerprint.byteSize}_$bucketCount"
    }

    /**
     * Pure deterministic PCM-16 peak & RMS envelope extractor for any sample buffer.
     * Returns normalized amplitudes in `[0f..1f]`.
     */
    fun computeNormalizedPeaksFromPcm16(
      samples: ShortArray,
      channelCount: Int = 1,
      bucketCount: Int = DEFAULT_SOURCE_BUCKETS,
    ): FloatArray {
      val safeBuckets = bucketCount.coerceAtLeast(1)
      if (samples.isEmpty()) return FloatArray(safeBuckets)
      val safeChannels = channelCount.coerceAtLeast(1)
      val totalFrames = (samples.size / safeChannels).coerceAtLeast(1)

      val bucketPeaks = FloatArray(safeBuckets)
      val bucketSumSq = DoubleArray(safeBuckets)
      val bucketCounts = IntArray(safeBuckets)

      for (frame in 0 until totalFrames) {
        var frameMaxAbs = 0f
        val baseIdx = frame * safeChannels
        for (ch in 0 until safeChannels) {
          val idx = baseIdx + ch
          if (idx < samples.size) {
            val mag = abs(samples[idx].toInt()) / 32768f
            if (mag > frameMaxAbs) frameMaxAbs = mag
          }
        }
        val bucketIdx =
          ((frame.toLong() * safeBuckets) / totalFrames.toLong()).toInt().coerceIn(0, safeBuckets - 1)
        if (frameMaxAbs > bucketPeaks[bucketIdx]) {
          bucketPeaks[bucketIdx] = frameMaxAbs
        }
        bucketSumSq[bucketIdx] += (frameMaxAbs * frameMaxAbs).toDouble()
        bucketCounts[bucketIdx]++
      }

      return finalizeBucketEnvelope(bucketPeaks, bucketSumSq, bucketCounts)
    }

    /**
     * Slices a cached full-source waveform array to match a trimmed or split clip's
     * `[sourceInMs..sourceOutMs]` window deterministically without re-decoding audio.
     */
    fun slicePeaksForSourceRange(
      fullPeaks: FloatArray,
      sourceDurationMs: Long,
      sourceInMs: Long,
      sourceOutMs: Long,
      targetBuckets: Int,
    ): FloatArray {
      val safeTarget = targetBuckets.coerceAtLeast(1)
      if (fullPeaks.isEmpty()) return FloatArray(safeTarget)

      val safeTotalMs = max(sourceDurationMs, max(sourceOutMs, 1L))
      val startFrac = (sourceInMs.coerceIn(0L, safeTotalMs).toDouble() / safeTotalMs.toDouble())
      val endFrac =
        (sourceOutMs.coerceIn(sourceInMs + 1L, safeTotalMs).toDouble() / safeTotalMs.toDouble())
          .coerceAtLeast(startFrac + (1.0 / max(fullPeaks.size * 4, 1)))

      val startFloatIdx = startFrac * fullPeaks.size
      val endFloatIdx = endFrac * fullPeaks.size
      val spanIdx = (endFloatIdx - startFloatIdx).coerceAtLeast(0.001)

      return FloatArray(safeTarget) { outIdx ->
        val segStart = startFloatIdx + (spanIdx * outIdx / safeTarget.toDouble())
        val segEnd = startFloatIdx + (spanIdx * (outIdx + 1) / safeTarget.toDouble())
        val firstIdx = segStart.toInt().coerceIn(0, fullPeaks.lastIndex)
        val lastIdx = max(firstIdx, (segEnd - 1e-6).toInt().coerceIn(0, fullPeaks.lastIndex))
        var maxPeak = 0f
        for (i in firstIdx..lastIdx) {
          val v = fullPeaks[i]
          if (v > maxPeak) maxPeak = v
        }
        maxPeak.coerceIn(0f, 1f)
      }
    }

    private fun finalizeBucketEnvelope(
      bucketPeaks: FloatArray,
      bucketSumSquares: DoubleArray,
      bucketCounts: IntArray,
    ): FloatArray {
      val raw =
        FloatArray(bucketPeaks.size) { i ->
          val count = bucketCounts[i]
          if (count <= 0) {
            0f
          } else {
            val peak = bucketPeaks[i]
            val rms = sqrt(bucketSumSquares[i] / count.toDouble()).toFloat()
            (peak * 0.65f + rms * 0.35f).coerceIn(0f, 1f)
          }
        }
      val globalMax = raw.maxOrNull() ?: 0f
      if (globalMax <= 0.0001f) return raw
      // Mild perceptual normalization only if audio is very quiet while preserving relative dynamics
      val boost = if (globalMax < 0.75f) min(1f / globalMax, 2.5f) else 1f
      return if (boost <= 1.01f) {
        raw
      } else {
        FloatArray(raw.size) { i -> (raw[i] * boost).coerceIn(0f, 1f) }
      }
    }

    internal fun writeWaveformFile(file: File, entry: CachedWaveformEntry) {
      runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { out ->
          out.writeInt(WAVEFORM_MAGIC)
          out.writeInt(WAVEFORM_VERSION)
          out.writeLong(entry.sourceDurationMs.coerceAtLeast(1L))
          out.writeInt(entry.peaks.size)
          entry.peaks.forEach { peak ->
            out.writeFloat(peak.coerceIn(0f, 1f))
          }
        }
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
          temp.copyTo(file, overwrite = true)
          temp.delete()
        }
      }
    }

    internal fun readWaveformFile(file: File): CachedWaveformEntry? {
      if (!file.exists() || file.length() < 20L) return null
      return runCatching {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
          val magic = input.readInt()
          val version = input.readInt()
          if (magic != WAVEFORM_MAGIC || version != WAVEFORM_VERSION) return null
          val durationMs = input.readLong().coerceAtLeast(1L)
          val count = input.readInt()
          if (count <= 0 || count > 8_192) return null
          val peaks =
            FloatArray(count) {
              val value = input.readFloat()
              if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            }
          CachedWaveformEntry(peaks = peaks, sourceDurationMs = durationMs)
        }
      }.getOrNull()
    }
  }
}
