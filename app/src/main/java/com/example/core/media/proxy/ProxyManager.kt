package com.example.core.media.proxy

import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.engine.RenderProgress
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

data class ProxyBatchState(
  val assetId: String,
  val displayName: String,
  val percent: Int,
  val completed: Boolean = false,
  val error: String? = null,
)

/**
 * Generates and manages optional lightweight editing proxies stored inside
 * `<projectRoot>/proxies/`. Proxies accelerate timeline scrubbing and preview playback
 * while final exports always read original full-resolution assets.
 */
class ProxyManager(
  private val backend: MediaEngineBackend,
) {

  fun hasValidProxy(asset: MediaAsset, projectRootDir: File): Boolean {
    val rel = asset.proxyRelativePath ?: return false
    val file = File(projectRootDir, rel)
    return file.exists() && file.length() > 0L
  }

  /**
   * Generates a proxy for a single [MediaAsset] if it is a video and lacks a cached proxy.
   * Returns the updated [MediaAsset] with `proxyRelativePath` populated on success.
   */
  fun generateProxyForAsset(
    asset: MediaAsset,
    projectRootDir: File,
    proxyDirName: String = "proxies",
    targetHeight: Int = 540,
  ): Flow<Pair<ProxyBatchState, MediaAsset?>> =
    flow {
      if (asset.type != MediaType.VIDEO) {
        emit(
          ProxyBatchState(asset.assetId, asset.displayName, 100, completed = true) to asset,
        )
        return@flow
      }

      val sourceFile = File(projectRootDir, asset.relativePath)
      if (!sourceFile.exists()) {
        emit(
          ProxyBatchState(
            assetId = asset.assetId,
            displayName = asset.displayName,
            percent = 0,
            error = "Archivo original no encontrado: ${asset.relativePath}",
          ) to null,
        )
        return@flow
      }

      val proxyRelPath = "$proxyDirName/${asset.assetId}_proxy_${targetHeight}p.mp4"
      val proxyFile = File(projectRootDir, proxyRelPath)

      if (proxyFile.exists() && proxyFile.length() > 0L) {
        emit(
          ProxyBatchState(asset.assetId, asset.displayName, 100, completed = true) to
            asset.copy(proxyRelativePath = proxyRelPath),
        )
        return@flow
      }

      backend.transcodeProxy(sourceFile, proxyFile, targetHeight).collect { progress ->
        when (progress) {
          is RenderProgress.Preparing ->
            emit(ProxyBatchState(asset.assetId, asset.displayName, 0) to null)
          is RenderProgress.Running ->
            emit(ProxyBatchState(asset.assetId, asset.displayName, progress.percent) to null)
          is RenderProgress.Completed ->
            emit(
              ProxyBatchState(asset.assetId, asset.displayName, 100, completed = true) to
                asset.copy(proxyRelativePath = proxyRelPath),
            )
          is RenderProgress.Cancelled ->
            emit(
              ProxyBatchState(
                asset.assetId,
                asset.displayName,
                0,
                error = "Generación cancelada",
              ) to null,
            )
          is RenderProgress.Failed ->
            emit(
              ProxyBatchState(
                asset.assetId,
                asset.displayName,
                0,
                error = progress.message,
              ) to null,
            )
        }
      }
    }

  fun clearAllProxies(projectRootDir: File, proxyDirName: String = "proxies") {
    File(projectRootDir, proxyDirName).deleteRecursively()
  }
}
