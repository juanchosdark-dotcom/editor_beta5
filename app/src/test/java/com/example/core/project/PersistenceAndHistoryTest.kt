package com.example.core.project

import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.ai.api.EditFixStatus
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.LocalEditAssistEngine
import com.example.core.ai.api.ResourceGuard
import com.example.core.ai.api.ResourceSnapshot
import com.example.core.ai.api.ThermalTier
import com.example.core.ai.local.DeviceResourceGuard
import com.example.core.ai.local.LocalEditAssistant
import com.example.core.model.AppliedAiAdjustment
import com.example.core.model.ClipKeyframe
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.ProjectDocument
import com.example.core.storage.TempCacheStorageManager
import com.example.core.timeline.EditorHistoryManager
import com.example.core.timeline.EditorHistorySnapshot
import com.example.ui.editor.TimelineSkeletonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceAndHistoryTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `project repository saves and reloads source uri, cuts, keyframes and applied ai adjustments`() =
    runTest(testDispatcher) {
      val filesDir = Files.createTempDirectory("frameline_files_").toFile()
      val projectsDir = File(filesDir, "projects")
      try {
        val repo = ProjectRepository(projectsDir = projectsDir, ioDispatcher = testDispatcher)
        val baseDoc = TimelineSkeletonViewModel.createInitialProjectDocument()
        val videoClip =
          baseDoc.timeline.clips.first { it.clipId == "clip_v1" }.let { clip ->
            clip.copy(
              timelineEndMs = 2_500L,
              sourceOutMs = 2_500L,
              videoConfig =
                clip.videoConfig?.copy(
                  keyframes =
                    listOf(
                      ClipKeyframe(keyframeId = "kf_1", offsetMs = 500L, scale = 1.25f, opacity = 0.9f),
                    ),
                ),
            )
          }
        val editedTimeline =
          baseDoc.timeline.copy(
            clips = baseDoc.timeline.clips.map { if (it.clipId == "clip_v1") videoClip else it },
          )
        val docToSave =
          baseDoc.copy(
            projectId = "proj_persist_1",
            name = "Edición Persistente",
            updatedAtIso = "2026-11-24T10:00:00Z",
            assets =
              listOf(
                MediaAsset(
                  assetId = "asset_1",
                  type = MediaType.VIDEO,
                  displayName = "clip.mp4",
                  relativePath = "temp_media/clip.mp4",
                  externalSourceUri = "content://media/external/video/media/42",
                  proxyRelativePath = "proxies/asset_1_proxy_540p.mp4",
                  fingerprint =
                    MediaFingerprint(
                      byteSize = 1_024L,
                      durationMs = 5_000L,
                      partialSha256 = "abc",
                      mimeType = "video/mp4",
                      width = 1920,
                      height = 1080,
                    ),
                ),
              ),
            timeline = editedTimeline,
            appliedAiAdjustments =
              listOf(
                AppliedAiAdjustment(
                  adjustmentId = "ai_1",
                  suggestionId = "sug_1",
                  category = "COLOR_LIGHT",
                  title = "Corrección luz IA",
                  rationale = "Subir exposición",
                  targetClipId = "clip_v1",
                  parametersSummary = "Brillo 0.12, Contraste 1.1",
                  appliedAtIso = "2026-11-24T09:59:00Z",
                ),
              ),
          )

        val savedResult = repo.saveProject(docToSave)
        assertTrue(savedResult.isSuccess)
        val savedFile = savedResult.getOrThrow()
        assertTrue(savedFile.exists())
        assertEquals("proj_persist_1.json", savedFile.name)

        val loaded = repo.loadProject("proj_persist_1").getOrThrow()
        assertEquals("Edición Persistente", loaded.name)
        assertEquals("content://media/external/video/media/42", loaded.assets.first().externalSourceUri)
        val reloadedClip = loaded.timeline.clips.first { it.clipId == "clip_v1" }
        assertEquals(2_500L, reloadedClip.timelineEndMs)
        assertEquals(1, reloadedClip.videoConfig?.keyframes?.size)
        assertEquals(1.25f, reloadedClip.videoConfig?.keyframes?.first()?.scale ?: 0f, 0.001f)
        assertEquals(1, loaded.appliedAiAdjustments.size)
        assertEquals("Corrección luz IA", loaded.appliedAiAdjustments.first().title)

        val recent = repo.listRecentProjects()
        assertEquals(1, recent.size)
        assertEquals("proj_persist_1", recent.first().projectId)
      } finally {
        filesDir.deleteRecursively()
      }
    }

  @Test
  fun `history manager enforces 20 step cap for undo and redo`() {
    val manager = EditorHistoryManager(maxSteps = 20)
    val baseTimeline = TimelineSkeletonViewModel.createInitialSampleTimeline()

    fun snapshotAt(step: Int): EditorHistorySnapshot =
      EditorHistorySnapshot(
        timeline = baseTimeline.copy(playheadMs = step * 100L),
        selectedClipId = "clip_v1",
        appliedAiAdjustments = emptyList(),
        editAnalysisReport = null,
        snapshots = emptyList(),
        sequences = emptyList(),
        activeSequenceId = "seq_main",
      )

    for (step in 0 until 25) {
      manager.record(snapshotAt(step))
    }

    assertEquals(20, manager.undoCount)
    assertFalse(manager.canRedo)

    var current = snapshotAt(25)
    var undoneSteps = 0
    while (manager.canUndo) {
      val prev = manager.undo(current)!!
      current = prev
      undoneSteps++
    }
    assertEquals(20, undoneSteps)
    assertEquals(500L, current.timeline.playheadMs)
    assertEquals(20, manager.redoCount)

    val redone = manager.redo(current)!!
    assertEquals(600L, redone.timeline.playheadMs)
  }

  @Test
  fun `viewmodel undo and redo revert and reapply both manual edits and ai assistant fixes`() =
    runTest(testDispatcher) {
      val tempRoot = Files.createTempDirectory("frameline_vm_hist_").toFile()
      val filesDir = File(tempRoot, "files")
      val cacheDir = File(tempRoot, "cache").apply { mkdirs() }
      try {
        val repo = ProjectRepository(File(filesDir, "projects"), ioDispatcher = testDispatcher)
        val cacheMgr = TempCacheStorageManager(cacheDir, repo.projectsDir, ioDispatcher = testDispatcher)
        val fakeGuard = FakeResourceGuard()
        val assistant: LocalEditAssistEngine = LocalEditAssistant(fakeGuard, workDispatcher = testDispatcher)

        val vm =
          TimelineSkeletonViewModel(
            resourceGuard = fakeGuard,
            localEditAssistant = assistant,
            projectRepository = repo,
            tempCacheStorageManager = cacheMgr,
            projectRootDir = cacheDir,
          )
        advanceUntilIdle()

        // 1. Manual edit: split clip_v1 at playhead (2500ms)
        vm.selectClip("clip_v1")
        val initialClipCount = vm.uiState.value.timeline.clips.size
        vm.splitAtPlayhead()
        assertEquals(initialClipCount + 1, vm.uiState.value.timeline.clips.size)
        assertTrue(vm.uiState.value.canUndo)

        // 2. AiAssistant fix on clip_v2
        vm.setLocalAiEnabled(true)
        vm.selectClip("clip_v2")
        vm.analyzeCurrentClipWithAssistant()
        advanceUntilIdle()
        val kfFix = vm.uiState.value.editAnalysisReport!!.suggestions.first { it.targetClipId == "clip_v2" }
        vm.applyEditSuggestion(kfFix.suggestionId)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.appliedAiAdjustments.size)
        assertEquals(
          EditFixStatus.APPLIED,
          vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == kfFix.suggestionId }?.status,
        )

        // Undo AI fix -> restores PENDING status & empty appliedAiAdjustments
        vm.undo()
        assertTrue(vm.uiState.value.appliedAiAdjustments.isEmpty())
        assertEquals(
          EditFixStatus.PENDING,
          vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == kfFix.suggestionId }?.status,
        )
        assertTrue(vm.uiState.value.canRedo)

        // Undo manual split -> restores original clip count
        vm.undo()
        assertEquals(initialClipCount, vm.uiState.value.timeline.clips.size)

        // Redo manual split & redo AI fix
        vm.redo()
        assertEquals(initialClipCount + 1, vm.uiState.value.timeline.clips.size)
        vm.redo()
        assertEquals(1, vm.uiState.value.appliedAiAdjustments.size)
        assertEquals(
          EditFixStatus.APPLIED,
          vm.uiState.value.editAnalysisReport?.suggestions?.first { it.suggestionId == kfFix.suggestionId }?.status,
        )
        advanceUntilIdle()

        // Saved JSON in filesDir reflects persisted edits
        val savedJson = repo.loadProject(vm.uiState.value.recentProjects.first { it.isActive }.projectId).getOrThrow()
        assertEquals(initialClipCount + 1, savedJson.timeline.clips.size)
        assertEquals(1, savedJson.appliedAiAdjustments.size)
      } finally {
        tempRoot.deleteRecursively()
      }
    }

  @Test
  fun `clearing temporary cache removes files in cacheDir while preserving json projects in filesDir`() =
    runTest(testDispatcher) {
      val tempRoot = Files.createTempDirectory("frameline_cache_test_").toFile()
      val filesDir = File(tempRoot, "files")
      val cacheDir = File(tempRoot, "cache").apply { mkdirs() }
      try {
        val repo = ProjectRepository(File(filesDir, "projects"), ioDispatcher = testDispatcher)
        val cacheMgr = TempCacheStorageManager(cacheDir, repo.projectsDir, ioDispatcher = testDispatcher)

        // Populate cacheDir files
        cacheMgr.proxiesDir.apply { mkdirs() }.resolve("p1.mp4").writeBytes(ByteArray(2_048))
        cacheMgr.tempMediaDir.apply { mkdirs() }.resolve("tmp.mp4").writeBytes(ByteArray(4_096))
        cacheMgr.exportStagingDir.apply { mkdirs() }.resolve("stage.mp4").writeBytes(ByteArray(1_024))

        val vm =
          TimelineSkeletonViewModel(
            projectRepository = repo,
            tempCacheStorageManager = cacheMgr,
            projectRootDir = cacheDir,
          )
        advanceUntilIdle()

        val beforeStats = vm.uiState.value.cacheStorageStats
        assertEquals(7_168L, beforeStats.totalCacheBytes)
        assertEquals(1, beforeStats.savedProjectCount)

        vm.clearTemporaryCache()
        advanceUntilIdle()

        val afterStats = vm.uiState.value.cacheStorageStats
        assertEquals(0L, afterStats.totalCacheBytes)
        assertEquals(1, afterStats.savedProjectCount)
        assertTrue(repo.projectFileFor("proj_demo").exists())
      } finally {
        tempRoot.deleteRecursively()
      }
    }

  @Test
  fun `cache manager preserves active export staging when requested while preserving saved projects`() =
    runTest(testDispatcher) {
      val tempRoot = Files.createTempDirectory("frameline_cache_preserve_").toFile()
      val filesDir = File(tempRoot, "files")
      val cacheDir = File(tempRoot, "cache").apply { mkdirs() }
      try {
        val repo = ProjectRepository(File(filesDir, "projects"), ioDispatcher = testDispatcher)
        repo.saveProject(TimelineSkeletonViewModel.createInitialProjectDocument())
        val cacheMgr = TempCacheStorageManager(cacheDir, repo.projectsDir, ioDispatcher = testDispatcher)

        cacheMgr.proxiesDir.apply { mkdirs() }.resolve("p1.mp4").writeBytes(ByteArray(1_500))
        val stagingFile =
          cacheMgr.exportStagingDir.apply { mkdirs() }.resolve("active.mp4").apply {
            writeBytes(ByteArray(2_048))
          }

        val statsAfterSelectiveClear =
          cacheMgr.clearTemporaryCache(preserveDirectories = setOf(TempCacheStorageManager.DIR_EXPORT_STAGING))

        assertTrue(stagingFile.exists())
        assertEquals(0L, statsAfterSelectiveClear.proxiesBytes)
        assertEquals(2_048L, statsAfterSelectiveClear.exportScratchBytes)
        assertEquals(1, statsAfterSelectiveClear.savedProjectCount)
        assertTrue(repo.projectFileFor("proj_demo").exists())
      } finally {
        tempRoot.deleteRecursively()
      }
    }

  @Test
  fun `selective cache cleanup preserves active export files while removing stale staging, orphans and proxies`() =
    runTest(testDispatcher) {
      val tempRoot = Files.createTempDirectory("frameline_selective_cache_").toFile()
      val filesDir = File(tempRoot, "files")
      val cacheDir = File(tempRoot, "cache").apply { mkdirs() }
      try {
        val repo = ProjectRepository(File(filesDir, "projects"), ioDispatcher = testDispatcher)
        repo.saveProject(TimelineSkeletonViewModel.createInitialProjectDocument())
        val cacheMgr = TempCacheStorageManager(cacheDir, repo.projectsDir, ioDispatcher = testDispatcher)

        cacheMgr.proxiesDir.apply { mkdirs() }.resolve("p1.mp4").writeBytes(ByteArray(1_500))
        cacheMgr.tempMediaDir.apply { mkdirs() }.resolve("tmp_clip.mp4").writeBytes(ByteArray(3_000))
        val activeFile =
          cacheMgr.exportStagingDir.apply { mkdirs() }.resolve("active_job.mp4").apply {
            writeBytes(ByteArray(2_048))
          }
        val activeTmp =
          cacheMgr.exportStagingDir.resolve(".active_job_s1.tmp.mp4").apply {
            writeBytes(ByteArray(512))
          }
        val staleFinished =
          cacheMgr.exportStagingDir.resolve("old_finished.mp4").apply {
            writeBytes(ByteArray(4_096))
          }
        val orphanTmp =
          cacheMgr.exportStagingDir.resolve(".crashed_s99.tmp.mp4").apply {
            writeBytes(ByteArray(1_024))
          }

        val initialStats = cacheMgr.measureStorage()
        assertEquals(12_180L, initialStats.totalCacheBytes)
        assertEquals(7_680L, initialStats.exportScratchBytes)

        val afterOrphanCleanup =
          cacheMgr.cleanupOrphanStagingFiles(
            activeFilePaths = setOf(activeFile.absolutePath, activeTmp.absolutePath),
          )
        assertFalse(orphanTmp.exists())
        assertTrue(activeTmp.exists())
        assertTrue(staleFinished.exists())
        assertEquals(6_656L, afterOrphanCleanup.exportScratchBytes)

        val afterSelectiveClear =
          cacheMgr.clearTemporaryCachePreservingFiles(
            preserveFilePaths = setOf(activeFile.absolutePath, activeTmp.absolutePath),
          )
        assertTrue(activeFile.exists())
        assertTrue(activeTmp.exists())
        assertFalse(staleFinished.exists())
        assertEquals(0L, afterSelectiveClear.proxiesBytes)
        assertEquals(0L, afterSelectiveClear.tempMediaBytes)
        assertEquals(2_560L, afterSelectiveClear.exportScratchBytes)
        assertEquals(2_560L, afterSelectiveClear.totalCacheBytes)
        assertEquals(1, afterSelectiveClear.savedProjectCount)
        assertTrue(repo.projectFileFor("proj_demo").exists())
      } finally {
        tempRoot.deleteRecursively()
      }
    }

  private class FakeResourceGuard : ResourceGuard {
    private val _snapshot =
      MutableStateFlow(
        ResourceSnapshot(
          thermalTier = ThermalTier.NORMAL,
          availableRamMb = 1_800L,
          isLowMemory = false,
          canRunInference = true,
        ),
      )
    override val snapshot: StateFlow<ResourceSnapshot> = _snapshot

    private val _deviceProfile = MutableStateFlow(DeviceProfile(tier = DeviceMemoryTier.BALANCED))
    override val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile

    override fun refreshNow(settings: LocalAiSettings): ResourceSnapshot = _snapshot.value

    override fun resolveDeviceProfile(settings: LocalAiSettings): DeviceProfile =
      DeviceResourceGuard.buildDeviceProfile(
        snapshot = _snapshot.value,
        totalRamMb = 4_096L,
        isLowRamDevice = false,
        settings = settings,
      )

    override fun startMonitoring(settings: LocalAiSettings) = Unit

    override fun stopMonitoring() = Unit
  }
}
