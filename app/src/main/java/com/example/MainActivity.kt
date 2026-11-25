package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainer
import com.example.ui.editor.TimelineSkeletonViewModel
import com.example.ui.navigation.FrameLineApp
import com.example.ui.theme.FrameLineTheme

class MainActivity : ComponentActivity() {
  private val appContainer by lazy { AppContainer(applicationContext) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      FrameLineTheme {
        val timelineViewModel: TimelineSkeletonViewModel =
          viewModel(
            factory =
              TimelineSkeletonViewModel.factory(
                engine = appContainer.timelineEngine,
                attributeTransferEngine = appContainer.attributeTransferEngine,
                snapshotManager = appContainer.timelineSnapshotManager,
                silenceDetector = appContainer.silenceDetectorEngine,
                subtitleStyleRepository = appContainer.subtitleStyleRepository,
                mediaProbe = appContainer.mediaProbe,
                proxyManager = appContainer.proxyManager,
                exportQueueManager = appContainer.exportQueueManager,
                previewController = appContainer.previewPlayerController,
                thumbnailCache = appContainer.videoThumbnailCache,
                waveformCache = appContainer.audioWaveformCache,
                resourceGuard = appContainer.resourceGuard,
                localAudioAssistEngine = appContainer.localAudioAssistEngine,
                localEditAssistant = appContainer.localEditAssistant,
                localTranscriptionBackend = appContainer.localTranscriptionBackend,
                projectRepository = appContainer.projectRepository,
                tempCacheStorageManager = appContainer.tempCacheStorageManager,
                projectRootDir = appContainer.tempCacheRootDir,
              ),
          )
        FrameLineApp(viewModel = timelineViewModel)
      }
    }
  }
}
