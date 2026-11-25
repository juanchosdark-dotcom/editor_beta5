package com.example.core.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ExtractedAudioResult(
  val outputFile: File,
  val durationMs: Long,
  val mimeType: String,
)

/**
 * Extracts the embedded audio track from a local video [File] or Android [Uri] into a standalone
 * `.m4a` audio file inside [mediaDir] using [MediaExtractor] + [MediaMuxer].
 */
object VideoAudioExtractor {

  suspend fun extractFromFile(
    sourceVideoFile: File,
    mediaDir: File,
    outputPrefix: String = "ext",
  ): Result<ExtractedAudioResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        require(sourceVideoFile.exists() && sourceVideoFile.length() > 0L) {
          "El archivo de video no está disponible."
        }
        extractInternal(
          configureExtractor = { extractor -> extractor.setDataSource(sourceVideoFile.absolutePath) },
          mediaDir = mediaDir,
          outputPrefix = outputPrefix,
        )
      }
    }

  suspend fun extractFromUri(
    context: Context,
    uri: Uri,
    mediaDir: File,
    outputPrefix: String = "ext",
  ): Result<ExtractedAudioResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        extractInternal(
          configureExtractor = { extractor ->
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
              pfd.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
              }
            } else {
              extractor.setDataSource(context, uri, null)
            }
          },
          mediaDir = mediaDir,
          outputPrefix = outputPrefix,
        )
      }
    }

  private fun extractInternal(
    configureExtractor: (MediaExtractor) -> Unit,
    mediaDir: File,
    outputPrefix: String,
  ): ExtractedAudioResult {
    mediaDir.mkdirs()
    val outFile = File(mediaDir, "${outputPrefix}_${UUID.randomUUID().toString().take(8)}.m4a")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var muxerStarted = false

    try {
      configureExtractor(extractor)
      var audioTrackIndex = -1
      var audioFormat: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          audioFormat = fmt
          break
        }
      }
      if (audioTrackIndex < 0 || audioFormat == null) {
        throw IllegalStateException("El video seleccionado no contiene pista de audio.")
      }

      extractor.selectTrack(audioTrackIndex)
      val maxInputSize =
        if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
          audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 1024 * 1024)
        } else {
          256 * 1024
        }

      muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val muxerTrackIndex = muxer.addTrack(audioFormat)
      muxer.start()
      muxerStarted = true

      val buffer = ByteBuffer.allocateDirect(maxInputSize)
      val bufferInfo = MediaCodec.BufferInfo()
      var maxPtsUs = 0L

      while (true) {
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break

        bufferInfo.offset = 0
        bufferInfo.size = sampleSize
        val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
        bufferInfo.presentationTimeUs = ptsUs
        @Suppress("WrongConstant")
        bufferInfo.flags = extractor.sampleFlags
        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
        if (ptsUs > maxPtsUs) maxPtsUs = ptsUs
        if (!extractor.advance()) break
      }

      val formatDurationMs =
        if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
          audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L
        } else {
          0L
        }
      val computedDurationMs = maxOf(formatDurationMs, (maxPtsUs / 1_000L) + 40L).coerceAtLeast(250L)
      val mimeType = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"

      return ExtractedAudioResult(
        outputFile = outFile,
        durationMs = computedDurationMs,
        mimeType = mimeType,
      )
    } catch (t: Throwable) {
      runCatching { outFile.delete() }
      throw t
    } finally {
      if (muxer != null) {
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
      }
      runCatching { extractor.release() }
    }
  }
}
