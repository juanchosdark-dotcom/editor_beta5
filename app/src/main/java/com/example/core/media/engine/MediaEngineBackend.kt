package com.example.core.media.engine

import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineState
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Progress events emitted while transcoding a proxy or exporting a timeline composition.
 */
sealed interface RenderProgress {
  data object Preparing : RenderProgress

  data class Running(val percent: Int) : RenderProgress

  data class Completed(
    val outputFile: File,
    val durationMs: Long,
    val fileSizeBytes: Long,
  ) : RenderProgress

  data object Cancelled : RenderProgress

  data class Failed(val message: String, val cause: Throwable? = null) : RenderProgress
}

/**
 * Abstraction over the local video/audio processing backend (Media3 Transformer or FFmpeg).
 * Keeping this interface decoupled allows swapping or combining engines without touching UI/Queue.
 */
interface MediaEngineBackend {

  /**
   * Renders the given [timeline] using [assets] into [outputFile] according to [renderTarget].
   */
  fun renderTimeline(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
    outputFile: File,
  ): Flow<RenderProgress>

  /**
   * Transcodes a single source file into a lightweight editing proxy MP4.
   */
  fun transcodeProxy(
    sourceFile: File,
    proxyOutputFile: File,
    targetHeight: Int,
  ): Flow<RenderProgress>

  /**
   * Cancels the currently active render operation, if any.
   */
  fun cancelActiveRender()
}
