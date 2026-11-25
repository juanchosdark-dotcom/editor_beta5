package com.example.core.media.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Manages microphone voiceover capture into AAC-LC `.m4a` files stored inside a portable
 * project's `media/` directory.
 */
class VoiceoverRecorderManager(
  private val context: Context,
) {
  private var recorder: MediaRecorder? = null
  private var currentOutputFile: File? = null
  private var startWallClockMs: Long = 0L

  val isRecording: Boolean
    get() = recorder != null

  fun elapsedDurationMs(): Long =
    if (recorder != null) (System.currentTimeMillis() - startWallClockMs).coerceAtLeast(0L) else 0L

  fun startRecording(mediaDir: File): Result<File> =
    runCatching {
      stopAndDiscardIfRunning()
      mediaDir.mkdirs()
      val outFile = File(mediaDir, "voice_${UUID.randomUUID().toString().take(8)}.m4a")
      val instance =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          MediaRecorder(context)
        } else {
          @Suppress("DEPRECATION")
          MediaRecorder()
        }

      try {
        instance.setAudioSource(MediaRecorder.AudioSource.MIC)
        instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        instance.setAudioSamplingRate(44_100)
        instance.setAudioChannels(1)
        instance.setAudioEncodingBitRate(128_000)
        instance.setOutputFile(outFile.absolutePath)
        instance.prepare()
        instance.start()
      } catch (t: Throwable) {
        runCatching { instance.release() }
        runCatching { outFile.delete() }
        throw t
      }

      recorder = instance
      currentOutputFile = outFile
      startWallClockMs = System.currentTimeMillis()
      outFile
    }

  fun stopRecording(): Result<File> =
    runCatching {
      val active = recorder ?: throw IllegalStateException("No hay grabación activa.")
      val outFile = currentOutputFile ?: throw IllegalStateException("Archivo de voz no encontrado.")
      recorder = null
      currentOutputFile = null
      try {
        active.stop()
      } catch (t: Throwable) {
        runCatching { outFile.delete() }
        throw t
      } finally {
        runCatching { active.release() }
      }
      require(outFile.exists() && outFile.length() > 0L) {
        "La grabación de voz resultó vacía."
      }
      outFile
    }

  fun cancelRecording() {
    stopAndDiscardIfRunning()
  }

  private fun stopAndDiscardIfRunning() {
    val active = recorder
    val file = currentOutputFile
    recorder = null
    currentOutputFile = null
    if (active != null) {
      runCatching { active.stop() }
      runCatching { active.release() }
    }
    if (file != null) {
      runCatching { file.delete() }
    }
  }
}
