package com.example.core.export

import androidx.test.core.app.ApplicationProvider
import com.example.core.media.engine.Media3EngineBackend
import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.engine.RenderProgress
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.project.ProjectRepository
import com.example.core.storage.TempCacheStorageManager
import com.example.ui.editor.TimelineSkeletonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportPipelineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class FakeMediaBackend : MediaEngineBackend {
    var failNextRender: Boolean = false
    var streamChannel: Channel<RenderProgress>? = null
    var cancelCalls: Int = 0
    var renderCalls: Int = 0
    var lastOutputFile: File? = null
    var createTmpFileOnStart: Boolean = false

    override fun renderTimeline(
      timeline: TimelineState,
      assets: List<MediaAsset>,
      projectRootDir: File,
      renderTarget: MediaRenderTarget,
      outputFile: File,
    ): Flow<RenderProgress> {
      renderCalls++
      lastOutputFile = outputFile
      if (createTmpFileOnStart) {
        outputFile.parentFile?.mkdirs()
        File(
          outputFile.parentFile,
          ".${outputFile.nameWithoutExtension}_s1${Media3EngineBackend.STAGING_TEMP_SUFFIX}",
        ).writeBytes(ByteArray(1_024))
      }
      val ch = streamChannel
      if (ch != null) {
        return ch.receiveAsFlow()
      }
      return flow {
        emit(RenderProgress.Preparing)
        emit(RenderProgress.Running(35))
        if (failNextRender) {
          emit(RenderProgress.Failed("Codec H.265 no soportado"))
        } else {
          outputFile.parentFile?.mkdirs()
          outputFile.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
          emit(RenderProgress.Running(100))
          emit(
            RenderProgress.Completed(
              outputFile = outputFile,
              durationMs = 2_000L,
              fileSizeBytes = outputFile.length(),
            ),
          )
        }
      }
    }

    override fun transcodeProxy(
      sourceFile: File,
      proxyOutputFile: File,
      targetHeight: Int,
    ): Flow<RenderProgress> = flow { emit(RenderProgress.Cancelled) }

    override fun cancelActiveRender() {
      cancelCalls++
      streamChannel?.trySend(RenderProgress.Cancelled)
      streamChannel?.close()
    }
  }

  private fun sampleTimelineAndAssets(): Pair<TimelineState, List<MediaAsset>> {
    val timeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Clip 1",
              timelineStartMs = 0L,
              timelineEndMs = 2_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1",
            ),
          ),
      )
    val assets =
      listOf(
        MediaAsset(
          assetId = "a1",
          type = MediaType.VIDEO,
          displayName = "clip1.mp4",
          relativePath = "media/clip1.mp4",
          fingerprint = MediaFingerprint(128L, 2_000L, "hash", "video/mp4"),
        ),
      )
    return timeline to assets
  }

  @Test
  fun enqueueExport_completesAndPublishesMp4PathAndGalleryUri() = runTest {
    val rootDir = tempFolder.newFolder("export_success")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend()
    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val jobId =
      queueManager.enqueue(
        title = "Reel_Final",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val item = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.COMPLETED, item.status)
    assertEquals(100, item.progressPercent)
    assertNotNull(item.outputFilePath)
    assertNotNull(item.galleryUri)
    assertTrue((item.outputSizeBytes ?: 0L) > 0L)
    assertNull(item.errorMessage)
  }

  @Test
  fun cancelRunningJob_transitionsSafelyToCancelledAndUnblocksNextJob() = runTest {
    val rootDir = tempFolder.newFolder("export_cancel")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend().apply { createTmpFileOnStart = true }
    val channel = Channel<RenderProgress>(Channel.UNLIMITED)
    backend.streamChannel = channel

    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val firstId =
      queueManager.enqueue(
        title = "First_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    channel.trySend(RenderProgress.Preparing)
    channel.trySend(RenderProgress.Running(42))
    advanceUntilIdle()

    val firstOutput = backend.lastOutputFile!!
    val firstTmp =
      File(
        firstOutput.parentFile,
        ".${firstOutput.nameWithoutExtension}_s1${Media3EngineBackend.STAGING_TEMP_SUFFIX}",
      )
    assertTrue(firstTmp.exists())

    val runningItem = queueManager.queue.value.first { it.jobId == firstId }
    assertEquals(ExportJobStatus.RUNNING, runningItem.status)
    assertEquals(42, runningItem.progressPercent)

    // Switch backend to normal completion for the second job once the first is cancelled.
    queueManager.cancelJob(firstId)
    backend.streamChannel = null

    val secondId =
      queueManager.enqueue(
        title = "Second_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val firstAfterCancel = queueManager.queue.value.first { it.jobId == firstId }
    val secondAfterCancel = queueManager.queue.value.first { it.jobId == secondId }
    assertEquals(1, backend.cancelCalls)
    assertEquals(ExportJobStatus.CANCELLED, firstAfterCancel.status)
    assertFalse(firstOutput.exists())
    assertFalse(firstTmp.exists())
    assertEquals(ExportJobStatus.COMPLETED, secondAfterCancel.status)
    assertTrue(queueManager.storageUpdateVersion.value > 0L)
  }

  @Test
  fun cancelRaceWithCompletedEvent_preservesCancelledStateAndDeletesStagingFiles() = runTest {
    val rootDir = tempFolder.newFolder("export_cancel_race")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val channel = Channel<RenderProgress>(Channel.UNLIMITED)
    val backend =
      FakeMediaBackend().apply {
        streamChannel = channel
        createTmpFileOnStart = true
      }

    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val jobId =
      queueManager.enqueue(
        title = "Race_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    channel.trySend(RenderProgress.Preparing)
    channel.trySend(RenderProgress.Running(95))
    advanceUntilIdle()

    val outFile = backend.lastOutputFile!!
    outFile.writeBytes(ByteArray(256))

    // Cancel and simulate an in-flight Completed callback arriving after cancellation.
    queueManager.cancelJob(jobId)
    channel.trySend(RenderProgress.Completed(outFile, durationMs = 2_000L, fileSizeBytes = 256L))
    channel.close()
    advanceUntilIdle()

    val item = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.CANCELLED, item.status)
    assertNull(item.galleryUri)
    assertNull(item.outputFilePath)
    assertFalse(outFile.exists())
  }

  @Test
  fun clearFinished_deletesFinishedArtifactsWhilePreservingActiveExports() = runTest {
    val rootDir = tempFolder.newFolder("export_clear_finished")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend()

    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val finishedId =
      queueManager.enqueue(
        title = "Finished_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )
    advanceUntilIdle()

    val finishedFile = File(queueManager.queue.value.first { it.jobId == finishedId }.outputFilePath!!)
    assertTrue(finishedFile.exists())

    // Start a second long-running job that stays active
    val activeChannel = Channel<RenderProgress>(Channel.UNLIMITED)
    backend.streamChannel = activeChannel
    backend.createTmpFileOnStart = true
    val activeId =
      queueManager.enqueue(
        title = "Active_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )
    activeChannel.trySend(RenderProgress.Running(50))
    advanceUntilIdle()

    val activeTmp =
      File(
        backend.lastOutputFile!!.parentFile,
        ".${backend.lastOutputFile!!.nameWithoutExtension}_s1${Media3EngineBackend.STAGING_TEMP_SUFFIX}",
      )
    assertTrue(activeTmp.exists())

    queueManager.clearFinished()

    assertFalse(finishedFile.exists())
    assertTrue(activeTmp.exists())
    assertEquals(listOf(activeId), queueManager.queue.value.map { it.jobId })
  }

  @Test
  fun failedExport_surfacesErrorMessageAndRecoversOnRetry() = runTest {
    val rootDir = tempFolder.newFolder("export_retry")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend().apply { failNextRender = true }
    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val jobId =
      queueManager.enqueue(
        title = "Retry_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val failedItem = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.FAILED, failedItem.status)
    assertEquals("Codec H.265 no soportado", failedItem.errorMessage)

    backend.failNextRender = false
    queueManager.retryJob(jobId)
    advanceUntilIdle()

    val retriedItem = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.COMPLETED, retriedItem.status)
    assertNull(retriedItem.errorMessage)
    assertEquals(100, retriedItem.progressPercent)
  }

  @Test
  fun viewModel_updatesRealCacheDirMetricsOnFinishAndCancelAndPreservesActiveExport() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    val rootDir = tempFolder.newFolder("vm_export_cache")
    val filesDir = File(rootDir, "files").apply { mkdirs() }
    val cacheDir = File(rootDir, "cache").apply { mkdirs() }
    try {
      val context = ApplicationProvider.getApplicationContext<android.content.Context>()
      val backend = FakeMediaBackend()
      val queueManager =
        ExportQueueManager(
          backend = backend,
          gallerySaveHelper = GallerySaveHelper(context = context, ioDispatcher = testDispatcher),
          scope = TestScope(testScheduler),
        )
      val projectRepo = ProjectRepository(File(filesDir, "projects"), ioDispatcher = testDispatcher)
      val cacheManager =
        TempCacheStorageManager(cacheDir, projectRepo.projectsDir, ioDispatcher = testDispatcher)

      val vm =
        TimelineSkeletonViewModel(
          exportQueueManager = queueManager,
          projectRepository = projectRepo,
          tempCacheStorageManager = cacheManager,
          projectRootDir = cacheDir,
        )
      advanceUntilIdle()

      assertEquals(0L, vm.uiState.value.cacheStorageStats.totalCacheBytes)

      // 1. Finish export -> real cacheDir exportScratchBytes reported cleanly
      val (timeline, assets) = sampleTimelineAndAssets()
      val completedId =
        queueManager.enqueue(
          title = "VM_Done",
          range = ExportRange.FullTimeline,
          exportConfig = ExportPreset.HD_1080P_H264.config,
          timelineSnapshot = timeline,
          assetsSnapshot = assets,
          projectRootDir = cacheDir,
        )
      advanceUntilIdle()

      val completedItem = queueManager.queue.value.first { it.jobId == completedId }
      assertEquals(ExportJobStatus.COMPLETED, completedItem.status)
      assertTrue(vm.uiState.value.cacheStorageStats.exportScratchBytes > 0L)
      val bytesAfterFinish = vm.uiState.value.cacheStorageStats.exportScratchBytes

      // 2. Cancel running export -> .tmp.mp4 deleted and cacheStorageStats drops back down
      val cancelChannel = Channel<RenderProgress>(Channel.UNLIMITED)
      backend.streamChannel = cancelChannel
      backend.createTmpFileOnStart = true
      val cancelId =
        queueManager.enqueue(
          title = "VM_Cancel",
          range = ExportRange.FullTimeline,
          exportConfig = ExportPreset.HD_1080P_H264.config,
          timelineSnapshot = timeline,
          assetsSnapshot = assets,
          projectRootDir = cacheDir,
        )
      cancelChannel.trySend(RenderProgress.Running(30))
      advanceUntilIdle()
      assertTrue(cacheManager.measureStorage().exportScratchBytes > bytesAfterFinish)

      queueManager.cancelJob(cancelId)
      advanceUntilIdle()
      assertEquals(bytesAfterFinish, vm.uiState.value.cacheStorageStats.exportScratchBytes)

      // 3. Active export + stale proxy/temp_media -> clearTemporaryCache removes stale files & keeps active export + .json
      cacheManager.proxiesDir.apply { mkdirs() }.resolve("stale_proxy.mp4").writeBytes(ByteArray(2_000))
      cacheManager.tempMediaDir.apply { mkdirs() }.resolve("stale_clip.mp4").writeBytes(ByteArray(3_000))
      val activeChannel = Channel<RenderProgress>(Channel.UNLIMITED)
      backend.streamChannel = activeChannel
      backend.createTmpFileOnStart = true
      val activeId =
        queueManager.enqueue(
          title = "VM_Active",
          range = ExportRange.FullTimeline,
          exportConfig = ExportPreset.HD_1080P_H264.config,
          timelineSnapshot = timeline,
          assetsSnapshot = assets,
          projectRootDir = cacheDir,
        )
      activeChannel.trySend(RenderProgress.Running(65))
      advanceUntilIdle()

      val activeTmp =
        File(
          backend.lastOutputFile!!.parentFile,
          ".${backend.lastOutputFile!!.nameWithoutExtension}_s1${Media3EngineBackend.STAGING_TEMP_SUFFIX}",
        )
      assertTrue(activeTmp.exists())

      vm.clearTemporaryCache()
      advanceUntilIdle()

      assertTrue(activeTmp.exists())
      assertEquals(0L, vm.uiState.value.cacheStorageStats.proxiesBytes)
      assertEquals(0L, vm.uiState.value.cacheStorageStats.tempMediaBytes)
      assertEquals(activeTmp.length(), vm.uiState.value.cacheStorageStats.exportScratchBytes)
      assertTrue(projectRepo.projectFileFor(vm.uiState.value.recentProjects.first { it.isActive }.projectId).exists())
      assertEquals(1, vm.activeExportCount.value)
      assertEquals(ExportJobStatus.RUNNING, queueManager.queue.value.first { it.jobId == activeId }.status)

      queueManager.cancelJob(activeId)
      advanceUntilIdle()
    } finally {
      Dispatchers.resetMain()
    }
  }
}
