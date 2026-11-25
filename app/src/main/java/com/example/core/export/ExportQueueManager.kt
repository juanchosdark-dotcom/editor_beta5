package com.example.core.export

import com.example.core.media.engine.Media3EngineBackend
import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.engine.RenderProgress
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.ExportConfig
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sequential local export queue supporting full-timeline exports, arbitrary time ranges,
 * and marker-delimited chapter batch exports.
 *
 * Uses a single-worker sequential loop so hardware encoders on mobile devices are never
 * oversubscribed, while guaranteeing clean cancellation, atomic intermediate file cleanup,
 * and post-cleanup storage metric notifications.
 */
class ExportQueueManager(
  private val backend: MediaEngineBackend,
  private val gallerySaveHelper: GallerySaveHelper,
  private val scope: CoroutineScope,
) {

  private data class JobPayload(
    val timelineSnapshot: TimelineState,
    val assetsSnapshot: List<MediaAsset>,
    val projectRootDir: File,
  )

  private val payloads = ConcurrentHashMap<String, JobPayload>()
  private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()

  private val _queue = MutableStateFlow<List<ExportQueueItem>>(emptyList())
  val queue: StateFlow<List<ExportQueueItem>> = _queue.asStateFlow()

  private val _storageUpdateVersion = MutableStateFlow(0L)
  val storageUpdateVersion: StateFlow<Long> = _storageUpdateVersion.asStateFlow()

  private var workerJob: Job? = null
  @Volatile private var activeJobId: String? = null

  fun enqueue(
    title: String,
    range: ExportRange,
    exportConfig: ExportConfig,
    timelineSnapshot: TimelineState,
    assetsSnapshot: List<MediaAsset>,
    projectRootDir: File,
  ): String {
    val jobId = "exp_${UUID.randomUUID().toString().take(8)}"
    val item =
      ExportQueueItem(
        jobId = jobId,
        title = title,
        range = range,
        exportConfig = exportConfig,
      )
    payloads[jobId] = JobPayload(timelineSnapshot, assetsSnapshot, projectRootDir)
    _queue.update { it + item }
    ensureWorkerRunning()
    return jobId
  }

  /**
   * Expands the timeline markers into consecutive chapter segments and enqueues one
   * export job per segment.
   */
  fun enqueueMarkerChapters(
    projectName: String,
    exportConfig: ExportConfig,
    timelineSnapshot: TimelineState,
    assetsSnapshot: List<MediaAsset>,
    projectRootDir: File,
  ): List<String> {
    val spans =
      RangeSliceHelper.buildMarkerChapterSpans(
        markers = timelineSnapshot.markers,
        totalDurationMs = timelineSnapshot.totalDurationMs,
      )
    return spans.mapIndexed { index, span ->
      enqueue(
        title = "${projectName}_Cap${index + 1}_${span.label}",
        range = span,
        exportConfig = exportConfig,
        timelineSnapshot = timelineSnapshot,
        assetsSnapshot = assetsSnapshot,
        projectRootDir = projectRootDir,
      )
    }
  }

  fun cancelJob(jobId: String) {
    val item = _queue.value.find { it.jobId == jobId } ?: return
    val currentStatus = item.status
    if (currentStatus == ExportJobStatus.QUEUED) {
      cancelledJobIds.add(jobId)
      updateItem(jobId) {
        it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
      }
      payloads[jobId]?.let { payload ->
        cleanupJobArtifacts(outputFileFor(item, payload.projectRootDir), deleteOutputFile = true)
      }
      notifyStorageUpdated()
    } else if (currentStatus == ExportJobStatus.RUNNING) {
      cancelledJobIds.add(jobId)
      updateItem(jobId) {
        it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
      }
      if (activeJobId == jobId) {
        backend.cancelActiveRender()
      }
      payloads[jobId]?.let { payload ->
        cleanupJobArtifacts(outputFileFor(item, payload.projectRootDir), deleteOutputFile = true)
      }
      notifyStorageUpdated()
    }
  }

  fun retryJob(jobId: String) {
    val existing = _queue.value.find { it.jobId == jobId } ?: return
    if (existing.status == ExportJobStatus.FAILED || existing.status == ExportJobStatus.CANCELLED) {
      cancelledJobIds.remove(jobId)
      payloads[jobId]?.let { payload ->
        cleanupJobArtifacts(outputFileFor(existing, payload.projectRootDir), deleteOutputFile = true)
      }
      updateItem(jobId) {
        it.copy(
          status = ExportJobStatus.QUEUED,
          progressPercent = 0,
          outputFilePath = null,
          galleryUri = null,
          outputSizeBytes = null,
          errorMessage = null,
        )
      }
      notifyStorageUpdated()
      ensureWorkerRunning()
    }
  }

  fun clearFinished() {
    val finishedItems =
      _queue.value.filter {
        it.status == ExportJobStatus.COMPLETED ||
          it.status == ExportJobStatus.CANCELLED ||
          it.status == ExportJobStatus.FAILED
      }
    val finishedIds = finishedItems.mapTo(mutableSetOf()) { it.jobId }

    for (item in finishedItems) {
      item.outputFilePath?.let { path ->
        cleanupJobArtifacts(File(path), deleteOutputFile = true)
      }
      payloads[item.jobId]?.let { payload ->
        cleanupJobArtifacts(outputFileFor(item, payload.projectRootDir), deleteOutputFile = true)
      }
      payloads.remove(item.jobId)
      cancelledJobIds.remove(item.jobId)
    }

    _queue.update { items -> items.filterNot { it.jobId in finishedIds } }
    notifyStorageUpdated()
  }

  /**
   * Returns all output and staging file paths currently belonging to active (`QUEUED` or `RUNNING`)
   * jobs so selective `cacheDir` cleanups can preserve in-flight exports without leaving orphans.
   */
  fun activeExportFilePaths(): Set<String> {
    val paths = mutableSetOf<String>()
    val activeItems =
      _queue.value.filter {
        it.status == ExportJobStatus.QUEUED || it.status == ExportJobStatus.RUNNING
      }
    for (item in activeItems) {
      val payload = payloads[item.jobId] ?: continue
      val outFile = outputFileFor(item, payload.projectRootDir)
      paths += outFile.absolutePath
      findIntermediateFilesFor(outFile).forEach { paths += it.absolutePath }
    }
    return paths
  }

  private fun ensureWorkerRunning() {
    if (workerJob?.isActive == true) return
    workerJob =
      scope.launch {
        while (true) {
          val nextItem =
            _queue.value.firstOrNull { it.status == ExportJobStatus.QUEUED } ?: break
          processJob(nextItem)
        }
      }
  }

  private suspend fun processJob(item: ExportQueueItem) {
    val payload = payloads[item.jobId]
    if (payload == null) {
      updateItem(item.jobId) {
        it.copy(status = ExportJobStatus.FAILED, errorMessage = "Snapshot de proyecto ausente")
      }
      notifyStorageUpdated()
      return
    }

    if (item.jobId in cancelledJobIds || isJobCancelled(item.jobId)) {
      cleanupJobArtifacts(outputFileFor(item, payload.projectRootDir), deleteOutputFile = true)
      notifyStorageUpdated()
      return
    }

    activeJobId = item.jobId
    updateItem(item.jobId) {
      it.copy(status = ExportJobStatus.RUNNING, progressPercent = 0, errorMessage = null)
    }

    val outputFile = outputFileFor(item, payload.projectRootDir)
    var completedCleanly = false

    try {
      val slicedTimeline =
        when (val range = item.range) {
          is ExportRange.FullTimeline -> payload.timelineSnapshot
          is ExportRange.TimeRange ->
            RangeSliceHelper.sliceTimelineForRange(
              payload.timelineSnapshot,
              range.startMs,
              range.endMs,
            )
          is ExportRange.MarkerSpan ->
            RangeSliceHelper.sliceTimelineForRange(
              payload.timelineSnapshot,
              range.startMs,
              range.endMs,
            )
        }

      backend
        .renderTimeline(
          timeline = slicedTimeline,
          assets = payload.assetsSnapshot,
          projectRootDir = payload.projectRootDir,
          // Final export ALWAYS enforces full-resolution ExportConfig and ignores preview proxies.
          renderTarget = MediaRenderTarget.Export(item.exportConfig),
          outputFile = outputFile,
        )
        .collect { progress ->
          when (progress) {
            is RenderProgress.Preparing -> {
              if (!isJobCancelled(item.jobId)) {
                updateItem(item.jobId) { it.copy(progressPercent = 0) }
              }
            }
            is RenderProgress.Running -> {
              if (!isJobCancelled(item.jobId)) {
                updateItem(item.jobId) { it.copy(progressPercent = progress.percent) }
              }
            }
            is RenderProgress.Completed -> {
              // Guard against race where cancelJob() ran right as Transformer emitted onCompleted.
              if (isJobCancelled(item.jobId)) {
                cleanupJobArtifacts(progress.outputFile, deleteOutputFile = true)
                cleanupJobArtifacts(outputFile, deleteOutputFile = true)
                updateItem(item.jobId) {
                  it.copy(
                    status = ExportJobStatus.CANCELLED,
                    outputFilePath = null,
                    galleryUri = null,
                    outputSizeBytes = null,
                    errorMessage = null,
                  )
                }
                notifyStorageUpdated()
                return@collect
              }

              cleanupJobArtifacts(progress.outputFile, deleteOutputFile = false)
              val publishResult =
                gallerySaveHelper.publishMp4ToGallery(progress.outputFile, item.title)
              val galleryUri = publishResult.getOrNull()?.toString()
              val galleryWarning =
                publishResult.exceptionOrNull()?.let { err ->
                  "Exportado localmente, pero falló al registrar en Galería: ${err.localizedMessage ?: "error de MediaStore"}"
                }
              completedCleanly = true
              updateItem(item.jobId) {
                it.copy(
                  status = ExportJobStatus.COMPLETED,
                  progressPercent = 100,
                  outputFilePath = progress.outputFile.absolutePath,
                  galleryUri = galleryUri,
                  outputSizeBytes = progress.fileSizeBytes,
                  errorMessage = galleryWarning,
                )
              }
              notifyStorageUpdated()
            }
            is RenderProgress.Cancelled -> {
              cleanupJobArtifacts(outputFile, deleteOutputFile = true)
              updateItem(item.jobId) {
                it.copy(
                  status = ExportJobStatus.CANCELLED,
                  outputFilePath = null,
                  galleryUri = null,
                  outputSizeBytes = null,
                  errorMessage = null,
                )
              }
              notifyStorageUpdated()
            }
            is RenderProgress.Failed -> {
              cleanupJobArtifacts(outputFile, deleteOutputFile = true)
              if (isJobCancelled(item.jobId)) {
                updateItem(item.jobId) {
                  it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
                }
              } else {
                updateItem(item.jobId) {
                  it.copy(
                    status = ExportJobStatus.FAILED,
                    outputFilePath = null,
                    outputSizeBytes = null,
                    errorMessage = progress.message,
                  )
                }
              }
              notifyStorageUpdated()
            }
          }
        }
    } catch (e: CancellationException) {
      cleanupJobArtifacts(outputFile, deleteOutputFile = true)
      notifyStorageUpdated()
      throw e
    } catch (t: Throwable) {
      cleanupJobArtifacts(outputFile, deleteOutputFile = true)
      if (isJobCancelled(item.jobId)) {
        updateItem(item.jobId) {
          it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
        }
      } else {
        updateItem(item.jobId) {
          it.copy(
            status = ExportJobStatus.FAILED,
            outputFilePath = null,
            outputSizeBytes = null,
            errorMessage = t.localizedMessage ?: "Error inesperado durante la exportación",
          )
        }
      }
      notifyStorageUpdated()
    } finally {
      if (!completedCleanly) {
        cleanupJobArtifacts(outputFile, deleteOutputFile = true)
        notifyStorageUpdated()
      }
      if (activeJobId == item.jobId) {
        activeJobId = null
      }
    }
  }

  private fun isJobCancelled(jobId: String): Boolean =
    jobId in cancelledJobIds || _queue.value.find { it.jobId == jobId }?.status == ExportJobStatus.CANCELLED

  private fun outputFileFor(item: ExportQueueItem, projectRootDir: File): File {
    val safeName = item.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(projectRootDir, "export_staging/${safeName}_${item.jobId}.mp4")
  }

  private fun findIntermediateFilesFor(outputFile: File): List<File> {
    val parent = outputFile.parentFile ?: return emptyList()
    if (!parent.exists()) return emptyList()
    val tmpPrefix = ".${outputFile.nameWithoutExtension}_"
    return parent
      .listFiles { file ->
        file.isFile &&
          file.name.startsWith(tmpPrefix) &&
          file.name.endsWith(Media3EngineBackend.STAGING_TEMP_SUFFIX)
      }
      .orEmpty()
      .toList()
  }

  private fun cleanupJobArtifacts(
    outputFile: File,
    deleteOutputFile: Boolean,
  ) {
    findIntermediateFilesFor(outputFile).forEach { tmpFile ->
      runCatching { if (tmpFile.exists()) tmpFile.delete() }
    }
    if (deleteOutputFile) {
      runCatching { if (outputFile.exists()) outputFile.delete() }
    }
  }

  private fun notifyStorageUpdated() {
    _storageUpdateVersion.update { it + 1L }
  }

  private fun updateItem(jobId: String, transform: (ExportQueueItem) -> ExportQueueItem) {
    _queue.update { items ->
      items.map { item -> if (item.jobId == jobId) transform(item) else item }
    }
  }
}

