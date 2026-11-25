package com.example.core.media.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.AudioCodec
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineState
import com.example.core.model.VideoCodec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Android hardware-accelerated implementation of [MediaEngineBackend] powered by
 * Jetpack Media3 [Transformer].
 *
 * Guarantees:
 * - Renders into a per-session intermediate `.tmp.mp4` staging file and atomically promotes it to
 *   `outputFile` only upon clean `onCompleted`.
 * - Registers the cancellation handle synchronously *before* posting onto the main Looper so a
 *   rapid `cancelActiveRender()` before `mainHandler.post` executes never starts orphaned encoders.
 * - Releases listeners, stops progress pollers, cancels [Transformer], and deletes intermediate
 *   `.tmp.mp4` / partial files deterministically on completion, failure, cancellation, or flow close.
 */
@OptIn(UnstableApi::class)
class Media3EngineBackend(
  private val context: Context,
  private val compiler: CompositionCompiler = CompositionCompiler(),
) : MediaEngineBackend {

  private class ActiveRenderSession(
    val sessionId: Long,
    val outputFile: File,
    val stagingFile: File,
    val onCancel: () -> Unit,
  ) {
    val terminated = AtomicBoolean(false)
    val completedCleanly = AtomicBoolean(false)

    @Volatile var transformer: Transformer? = null

    @Volatile var listener: Transformer.Listener? = null

    @Volatile var pollRunnable: Runnable? = null
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val sessionLock = Any()
  private val sessionSeq = AtomicLong(0L)

  @Volatile
  private var activeSession: ActiveRenderSession? = null

  override fun renderTimeline(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
    outputFile: File,
  ): Flow<RenderProgress> =
    callbackFlow {
      trySend(RenderProgress.Preparing)

      val compiled = compiler.compile(timeline, assets, projectRootDir, renderTarget)
      if (compiled is CompiledCompositionResult.EmptyOrMissingMedia) {
        trySend(RenderProgress.Failed(compiled.reason))
        close()
        return@callbackFlow
      }

      val ready = compiled as CompiledCompositionResult.Ready
      val parentDir = outputFile.parentFile
      parentDir?.mkdirs()
      if (outputFile.exists()) outputFile.delete()

      val sessionId = sessionSeq.incrementAndGet()
      val stagingFile = createStagingFile(outputFile, sessionId)
      if (stagingFile.exists()) stagingFile.delete()

      val videoMimeType =
        when (renderTarget) {
          is MediaRenderTarget.Preview -> MimeTypes.VIDEO_H264
          is MediaRenderTarget.Export ->
            when (renderTarget.config.videoCodec) {
              VideoCodec.H264 -> MimeTypes.VIDEO_H264
              VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            }
        }

      val audioMimeType =
        when (renderTarget) {
          is MediaRenderTarget.Preview -> MimeTypes.AUDIO_AAC
          is MediaRenderTarget.Export ->
            when (renderTarget.config.audioCodec) {
              AudioCodec.AAC -> MimeTypes.AUDIO_AAC
              AudioCodec.OPUS -> MimeTypes.AUDIO_OPUS
            }
        }

      val session =
        ActiveRenderSession(
          sessionId = sessionId,
          outputFile = outputFile,
          stagingFile = stagingFile,
          onCancel = {
            trySend(RenderProgress.Cancelled)
            close()
          },
        )
      registerNewActiveSession(session)

      mainHandler.post {
        if (session.terminated.get()) {
          cleanupSessionFiles(session, deleteOutputFile = true)
          return@post
        }

        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              if (!session.terminated.compareAndSet(false, true)) return
              detachAndClearSession(session)

              val promoted = promoteStagingFile(session.stagingFile, session.outputFile)
              if (!promoted) {
                cleanupSessionFiles(session, deleteOutputFile = true)
                trySend(
                  RenderProgress.Failed(
                    message = "El archivo renderizado quedó vacío o incompleto al finalizar.",
                  ),
                )
                close()
                return
              }

              session.completedCleanly.set(true)
              trySend(RenderProgress.Running(100))
              trySend(
                RenderProgress.Completed(
                  outputFile = session.outputFile,
                  durationMs = exportResult.durationMs.coerceAtLeast(0L),
                  fileSizeBytes = session.outputFile.length(),
                ),
              )
              close()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (!session.terminated.compareAndSet(false, true)) return
              detachAndClearSession(session)
              cleanupSessionFiles(session, deleteOutputFile = true)
              trySend(
                RenderProgress.Failed(
                  message = exportException.localizedMessage ?: "Error en Media3 Transformer",
                  cause = exportException,
                ),
              )
              close()
            }
          }

        try {
          val transformerBuilder =
            Transformer.Builder(context)
              .setVideoMimeType(videoMimeType)
              .setAudioMimeType(audioMimeType)
              .addListener(listener)

          if (renderTarget is MediaRenderTarget.Export) {
            val encoderFactory =
              DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(
                  VideoEncoderSettings.Builder()
                    .setBitrate((renderTarget.config.videoBitrateKbps * 1_000).coerceAtLeast(500_000))
                    .build(),
                )
                .build()
            transformerBuilder.setEncoderFactory(encoderFactory)
          }

          val transformer = transformerBuilder.build()
          session.listener = listener
          session.transformer = transformer

          if (session.terminated.get()) {
            detachAndClearSession(session)
            cleanupSessionFiles(session, deleteOutputFile = true)
            return@post
          }

          transformer.start(ready.composition, stagingFile.absolutePath)

          val progressHolder = ProgressHolder()
          val poller =
            object : Runnable {
              override fun run() {
                if (session.terminated.get()) return
                val state =
                  runCatching { session.transformer?.getProgress(progressHolder) }
                    .getOrNull() ?: Transformer.PROGRESS_STATE_NOT_STARTED
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                  trySend(RenderProgress.Running(progressHolder.progress.coerceIn(0, 99)))
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !session.terminated.get()) {
                  mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                }
              }
            }
          session.pollRunnable = poller
          mainHandler.postDelayed(poller, PROGRESS_POLL_INTERVAL_MS)
        } catch (t: Throwable) {
          if (session.terminated.compareAndSet(false, true)) {
            detachAndClearSession(session)
            cleanupSessionFiles(session, deleteOutputFile = true)
            trySend(
              RenderProgress.Failed(
                message = t.localizedMessage ?: "No se pudo iniciar Media3 Transformer",
                cause = t,
              ),
            )
            close()
          }
        }
      }

      awaitClose {
        abortSession(session, notifyCancel = false)
      }
    }

  override fun transcodeProxy(
    sourceFile: File,
    proxyOutputFile: File,
    targetHeight: Int,
  ): Flow<RenderProgress> =
    callbackFlow {
      trySend(RenderProgress.Preparing)
      proxyOutputFile.parentFile?.mkdirs()
      if (proxyOutputFile.exists()) proxyOutputFile.delete()

      val sessionId = sessionSeq.incrementAndGet()
      val stagingFile = createStagingFile(proxyOutputFile, sessionId)
      if (stagingFile.exists()) stagingFile.delete()

      val editedItem =
        EditedMediaItem.Builder(MediaItem.fromUri(sourceFile.toURI().toString()))
          .setEffects(
            Effects(
              emptyList(),
              listOf(Presentation.createForHeight(targetHeight)),
            ),
          )
          .build()

      val session =
        ActiveRenderSession(
          sessionId = sessionId,
          outputFile = proxyOutputFile,
          stagingFile = stagingFile,
          onCancel = {
            trySend(RenderProgress.Cancelled)
            close()
          },
        )
      registerNewActiveSession(session)

      mainHandler.post {
        if (session.terminated.get()) {
          cleanupSessionFiles(session, deleteOutputFile = true)
          return@post
        }

        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              if (!session.terminated.compareAndSet(false, true)) return
              detachAndClearSession(session)

              val promoted = promoteStagingFile(session.stagingFile, session.outputFile)
              if (!promoted) {
                cleanupSessionFiles(session, deleteOutputFile = true)
                trySend(RenderProgress.Failed("El archivo proxy generado quedó vacío."))
                close()
                return
              }

              session.completedCleanly.set(true)
              trySend(RenderProgress.Running(100))
              trySend(
                RenderProgress.Completed(
                  outputFile = session.outputFile,
                  durationMs = exportResult.durationMs.coerceAtLeast(0L),
                  fileSizeBytes = session.outputFile.length(),
                ),
              )
              close()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (!session.terminated.compareAndSet(false, true)) return
              detachAndClearSession(session)
              cleanupSessionFiles(session, deleteOutputFile = true)
              trySend(
                RenderProgress.Failed(
                  message = exportException.localizedMessage ?: "Error al generar proxy",
                  cause = exportException,
                ),
              )
              close()
            }
          }

        try {
          val transformer =
            Transformer.Builder(context)
              .setVideoMimeType(MimeTypes.VIDEO_H264)
              .setAudioMimeType(MimeTypes.AUDIO_AAC)
              .addListener(listener)
              .build()
          session.listener = listener
          session.transformer = transformer

          if (session.terminated.get()) {
            detachAndClearSession(session)
            cleanupSessionFiles(session, deleteOutputFile = true)
            return@post
          }

          transformer.start(editedItem, stagingFile.absolutePath)

          val progressHolder = ProgressHolder()
          val poller =
            object : Runnable {
              override fun run() {
                if (session.terminated.get()) return
                val state =
                  runCatching { session.transformer?.getProgress(progressHolder) }
                    .getOrNull() ?: Transformer.PROGRESS_STATE_NOT_STARTED
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                  trySend(RenderProgress.Running(progressHolder.progress.coerceIn(0, 99)))
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !session.terminated.get()) {
                  mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                }
              }
            }
          session.pollRunnable = poller
          mainHandler.postDelayed(poller, PROGRESS_POLL_INTERVAL_MS)
        } catch (t: Throwable) {
          if (session.terminated.compareAndSet(false, true)) {
            detachAndClearSession(session)
            cleanupSessionFiles(session, deleteOutputFile = true)
            trySend(
              RenderProgress.Failed(
                message = t.localizedMessage ?: "No se pudo iniciar generación de proxy",
                cause = t,
              ),
            )
            close()
          }
        }
      }

      awaitClose {
        abortSession(session, notifyCancel = false)
      }
    }

  override fun cancelActiveRender() {
    val current = synchronized(sessionLock) { activeSession }
    if (current != null) {
      abortSession(current, notifyCancel = true)
    }
  }

  private fun registerNewActiveSession(newSession: ActiveRenderSession) {
    val previous: ActiveRenderSession?
    synchronized(sessionLock) {
      previous = activeSession
      activeSession = newSession
    }
    if (previous != null && previous !== newSession) {
      abortSession(previous, notifyCancel = true)
    }
  }

  private fun abortSession(
    session: ActiveRenderSession,
    notifyCancel: Boolean,
  ) {
    if (!session.terminated.compareAndSet(false, true)) {
      if (!session.completedCleanly.get()) {
        cleanupSessionFiles(session, deleteOutputFile = true)
      }
      return
    }

    val releaseBlock = {
      detachAndClearSession(session)
      runCatching { session.transformer?.cancel() }
      session.transformer = null
      cleanupSessionFiles(session, deleteOutputFile = true)
    }

    if (Looper.myLooper() == Looper.getMainLooper()) {
      releaseBlock()
    } else {
      mainHandler.post(releaseBlock)
    }

    if (notifyCancel) {
      session.onCancel()
    }
  }

  private fun detachAndClearSession(session: ActiveRenderSession) {
    session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
    session.pollRunnable = null
    val transformer = session.transformer
    val listener = session.listener
    if (transformer != null && listener != null) {
      runCatching { transformer.removeListener(listener) }
    }
    session.listener = null
    synchronized(sessionLock) {
      if (activeSession === session) {
        activeSession = null
      }
    }
  }

  private fun createStagingFile(outputFile: File, sessionId: Long): File {
    val parent = outputFile.parentFile ?: File(".")
    return File(parent, ".${outputFile.nameWithoutExtension}_s${sessionId}${STAGING_TEMP_SUFFIX}")
  }

  private fun promoteStagingFile(stagingFile: File, outputFile: File): Boolean {
    if (!stagingFile.exists() || stagingFile.length() <= 0L) return false
    outputFile.parentFile?.mkdirs()
    if (outputFile.exists() && !outputFile.delete()) {
      return false
    }
    if (stagingFile.renameTo(outputFile)) {
      return outputFile.exists() && outputFile.length() > 0L
    }
    return runCatching {
      stagingFile.inputStream().use { input ->
        outputFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      stagingFile.delete()
      outputFile.exists() && outputFile.length() > 0L
    }.getOrElse {
      if (outputFile.exists()) outputFile.delete()
      false
    }
  }

  private fun cleanupSessionFiles(
    session: ActiveRenderSession,
    deleteOutputFile: Boolean,
  ) {
    runCatching {
      if (session.stagingFile.exists()) session.stagingFile.delete()
    }
    if (deleteOutputFile) {
      runCatching {
        if (session.outputFile.exists()) session.outputFile.delete()
      }
    }
  }

  companion object {
    private const val PROGRESS_POLL_INTERVAL_MS = 200L
    const val STAGING_TEMP_SUFFIX = ".tmp.mp4"
  }
}

