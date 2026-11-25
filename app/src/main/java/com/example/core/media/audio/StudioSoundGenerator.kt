package com.example.core.media.audio

import com.example.core.model.StudioSoundPreset
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates clean, valid 16-bit PCM `.wav` studio sound effects and jingles inside the app's
 * safe temporary media directory (`cacheDir/temp_media/`) so quick-added sounds are immediately
 * playable, inspectable via waveform/MediaProbe, and persisted cleanly in `.json` project references.
 */
object StudioSoundGenerator {

  private const val SAMPLE_RATE = 44_100

  fun ensureSoundFile(
    preset: StudioSoundPreset,
    mediaDir: File,
  ): File {
    mediaDir.mkdirs()
    val outFile = File(mediaDir, "${preset.id}.wav")
    if (outFile.exists() && outFile.length() > 44L) {
      return outFile
    }
    val sampleCount = ((preset.durationMs.coerceIn(250L, 6_000L) / 1000.0) * SAMPLE_RATE).toInt()
    val samples = ShortArray(sampleCount)
    val rng = Random(preset.id.hashCode())

    for (i in 0 until sampleCount) {
      val t = i.toDouble() / SAMPLE_RATE.toDouble()
      val totalSec = sampleCount.toDouble() / SAMPLE_RATE.toDouble()
      val norm = (t / totalSec.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
      val raw =
        when (preset.id) {
          "snd_whoosh" -> {
            val env = sin(PI * norm) * exp(-1.1 * (norm - 0.35) * (norm - 0.35))
            val sweep = sin(2.0 * PI * (120.0 + 520.0 * norm) * t) * 0.35
            val air = (rng.nextDouble() * 2.0 - 1.0) * 0.45
            (sweep + air) * env * 0.72
          }
          "snd_pop" -> {
            val env = exp(-t * 14.0)
            val freq = 240.0 + 620.0 * exp(-t * 24.0)
            sin(2.0 * PI * freq * t) * env * 0.85
          }
          "snd_boom" -> {
            val env = (t / 0.025).coerceIn(0.0, 1.0) * exp(-t * 2.2)
            val freq = 115.0 * exp(-t * 1.4) + 38.0
            (sin(2.0 * PI * freq * t) * 0.85 + sin(4.0 * PI * freq * t) * 0.20) * env
          }
          "snd_chime" -> {
            val env = (t / 0.015).coerceIn(0.0, 1.0) * exp(-t * 2.8)
            val noteA = sin(2.0 * PI * 587.33 * t) * 0.45
            val noteB = sin(2.0 * PI * 880.00 * t) * 0.35
            val noteC = sin(2.0 * PI * 1174.66 * t) * 0.20
            (noteA + noteB + noteC) * env
          }
          else -> {
            // Warm studio chord pulse
            val fadeIn = (t / 0.06).coerceIn(0.0, 1.0)
            val fadeOut = ((totalSec - t) / 0.18).coerceIn(0.0, 1.0)
            val pulse = 0.65 + 0.35 * sin(2.0 * PI * 2.0 * t)
            val chord =
              sin(2.0 * PI * 220.0 * t) * 0.38 +
                sin(2.0 * PI * 277.18 * t) * 0.28 +
                sin(2.0 * PI * 329.63 * t) * 0.25
            chord * fadeIn * fadeOut * pulse
          }
        }
      samples[i] = (raw.coerceIn(-0.98, 0.98) * Short.MAX_VALUE).toInt().toShort()
    }

    writePcm16MonoWav(outFile, samples, SAMPLE_RATE)
    return outFile
  }

  private fun writePcm16MonoWav(
    targetFile: File,
    samples: ShortArray,
    sampleRate: Int,
  ) {
    val dataSize = samples.size * 2
    val totalChunkSize = 36 + dataSize
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(totalChunkSize)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM fmt chunk size
        putShort(1) // PCM format
        putShort(1) // mono
        putInt(sampleRate)
        putInt(sampleRate * 2) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
      }.array()

    val payload = ByteArrayOutputStream(44 + dataSize)
    payload.write(header)
    val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    for (s in samples) {
      pcmBytes.putShort(s)
    }
    payload.write(pcmBytes.array())
    targetFile.writeBytes(payload.toByteArray())
  }
}
