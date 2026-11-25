package com.example.di

import android.content.Context
import com.example.core.ai.api.LocalAudioAssistEngine
import com.example.core.ai.api.LocalEditAssistEngine
import com.example.core.ai.api.LocalTranscriptionBackend
import com.example.core.ai.api.ResourceGuard
import com.example.core.ai.local.DeviceResourceGuard
import com.example.core.ai.local.LocalEditAssistant
import com.example.core.ai.local.LocalRhythmAndSilenceAssistant
import com.example.core.ai.local.LocalSpeechTranscriptionService
import com.example.core.export.ExportQueueManager
import com.example.core.export.GallerySaveHelper
import com.example.core.media.audio.AudioWaveformCache
import com.example.core.media.audio.SilenceDetectorEngine
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.engine.Media3EngineBackend
import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.media.probe.MediaProbe
import com.example.core.media.proxy.ProxyManager
import com.example.core.project.ProjectJsonSerializer
import com.example.core.project.ProjectRepository
import com.example.core.storage.TempCacheStorageManager
import com.example.core.subtitles.SubtitleStyleRepository
import com.example.core.timeline.AttributeTransferEngine
import com.example.core.timeline.TimelineEngine
import com.example.core.timeline.TimelineSnapshotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Lightweight dependency container wiring timeline logic, JSON project repository (`filesDir`),
 * temporary storage manager (`cacheDir`), audio analysis, waveform cache, batch subtitles,
 * snapshots, optional Local AI module, native Media3 engine, and export queue.
 */
class AppContainer(context: Context) {
  private val appContext = context.applicationContext
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val projectsJsonDir: File by lazy {
    File(appContext.filesDir, "projects").apply { mkdirs() }
  }
  val presetsDir: File by lazy {
    File(appContext.filesDir, "presets").apply { mkdirs() }
  }
  val tempCacheRootDir: File by lazy {
    appContext.cacheDir.apply { mkdirs() }
  }

  val timelineEngine: TimelineEngine by lazy { TimelineEngine() }
  val projectJsonSerializer: ProjectJsonSerializer by lazy { ProjectJsonSerializer() }
  val projectRepository: ProjectRepository by lazy {
    ProjectRepository(projectsDir = projectsJsonDir, serializer = projectJsonSerializer)
  }
  val tempCacheStorageManager: TempCacheStorageManager by lazy {
    TempCacheStorageManager(
      cacheRootDir = tempCacheRootDir,
      projectsRootDir = projectsJsonDir,
    )
  }
  val attributeTransferEngine: AttributeTransferEngine by lazy { AttributeTransferEngine() }
  val timelineSnapshotManager: TimelineSnapshotManager by lazy { TimelineSnapshotManager() }
  val subtitleStyleRepository: SubtitleStyleRepository by lazy {
    SubtitleStyleRepository(presetsDir)
  }
  val silenceDetectorEngine: SilenceDetectorEngine by lazy { SilenceDetectorEngine() }

  // Optional, disableable Local AI components
  val resourceGuard: ResourceGuard by lazy { DeviceResourceGuard(appContext) }
  val localAudioAssistEngine: LocalAudioAssistEngine by lazy {
    LocalRhythmAndSilenceAssistant(resourceGuard)
  }
  val localEditAssistant: LocalEditAssistEngine by lazy {
    LocalEditAssistant(resourceGuard)
  }
  val localTranscriptionBackend: LocalTranscriptionBackend by lazy {
    LocalSpeechTranscriptionService(appContext)
  }

  val mediaProbe: MediaProbe by lazy { MediaProbe(appContext) }
  val compositionCompiler: CompositionCompiler by lazy { CompositionCompiler() }
  val mediaBackend: MediaEngineBackend by lazy {
    Media3EngineBackend(appContext, compositionCompiler)
  }
  val proxyManager: ProxyManager by lazy { ProxyManager(mediaBackend) }
  val gallerySaveHelper: GallerySaveHelper by lazy { GallerySaveHelper(appContext) }
  val exportQueueManager: ExportQueueManager by lazy {
    ExportQueueManager(
      backend = mediaBackend,
      gallerySaveHelper = gallerySaveHelper,
      scope = applicationScope,
    )
  }
  val previewPlayerController: PreviewPlayerController by lazy {
    PreviewPlayerController(appContext)
  }
  val videoThumbnailCache: VideoThumbnailCache by lazy {
    VideoThumbnailCache()
  }
  val audioWaveformCache: AudioWaveformCache by lazy {
    AudioWaveformCache(waveformsCacheDir = tempCacheStorageManager.waveformsDir)
  }
}
