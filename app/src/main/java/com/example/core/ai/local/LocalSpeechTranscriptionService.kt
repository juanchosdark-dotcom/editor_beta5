package com.example.core.ai.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.core.ai.api.LocalTranscriptSegment
import com.example.core.ai.api.LocalTranscriptionBackend
import com.example.core.ai.api.LocalTranscriptionEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * On-device speech-to-subtitle dictation backed by Android's [SpeechRecognizer]
 * requesting offline recognition (`EXTRA_PREFER_OFFLINE`) when available on the device.
 */
class LocalSpeechTranscriptionService(
  private val context: Context,
) : LocalTranscriptionBackend {

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun isOfflineRecognitionSupported(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    } else {
      SpeechRecognizer.isRecognitionAvailable(appContext)
    }
  }

  override fun startOfflineDictation(languageTag: String): Flow<LocalTranscriptionEvent> =
    callbackFlow {
      if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
        trySend(
          LocalTranscriptionEvent.UnavailableOrError(
            "El reconocimiento de voz del sistema no está disponible en este dispositivo.",
          ),
        )
        close()
        return@callbackFlow
      }

      var recognizer: SpeechRecognizer? = null
      val startTimestampMs = System.currentTimeMillis()

      mainHandler.post {
        val instance =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
          ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
          } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
          }

        recognizer = instance
        instance.setRecognitionListener(
          object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
              trySend(LocalTranscriptionEvent.Listening)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
              val text =
                partialResults
                  ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  ?.firstOrNull()
                  .orEmpty()
              if (text.isNotBlank()) {
                trySend(LocalTranscriptionEvent.Partial(text))
              }
            }

            override fun onResults(results: Bundle?) {
              val text =
                results
                  ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  ?.firstOrNull()
                  ?.trim()
                  .orEmpty()
              val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
              val confidence = scores?.firstOrNull()?.coerceIn(0f, 1f) ?: 0.85f
              val elapsed = (System.currentTimeMillis() - startTimestampMs).coerceIn(1_200L, 12_000L)

              if (text.isNotBlank()) {
                trySend(
                  LocalTranscriptionEvent.Completed(
                    LocalTranscriptSegment(
                      startMs = 0L,
                      endMs = elapsed,
                      text = text,
                      confidence = confidence,
                    ),
                  ),
                )
              } else {
                trySend(
                  LocalTranscriptionEvent.UnavailableOrError("No se detectó voz para transcribir."),
                )
              }
              close()
            }

            override fun onError(error: Int) {
              trySend(
                LocalTranscriptionEvent.UnavailableOrError(
                  "Error de dictado local (código $error).",
                ),
              )
              close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
          },
        )

        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          }
        instance.startListening(intent)
      }

      awaitClose {
        mainHandler.post {
          recognizer?.stopListening()
          recognizer?.destroy()
          recognizer = null
        }
      }
    }
}
