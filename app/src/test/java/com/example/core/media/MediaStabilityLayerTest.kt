package com.example.core.media

import android.content.Context
import android.media.MediaFormat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.model.MediaUiErrorKind
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.ThumbnailLoadResult
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.media.probe.MediaProbe
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaStabilityLayerTest {

  @Test
  fun `media probe helpers detect rotation, HDR transfer standards, and high resolution`() {
    assertEquals(90, MediaProbe.normalizeRotation(-270))
    assertEquals(270, MediaProbe.normalizeRotation(270))

    val portraitFingerprint =
      MediaFingerprint(
        byteSize = 1_000L,
        durationMs = 5_000L,
        partialSha256 = "hash",
        mimeType = "video/mp4",
        width = 1920,
        height = 1080,
        rotationDegrees = 90,
      )
    assertEquals(1080 to 1920, portraitFingerprint.orientedResolution())

    assertTrue(
      MediaProbe.isHdrColorMetadata(
        colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
      ),
    )
    assertTrue(
      MediaProbe.isHdrColorMetadata(
        colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
        colorStandard = null,
      ),
    )
    assertFalse(
      MediaProbe.isHdrColorMetadata(
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
      ),
    )

    assertTrue(MediaProbe.isHighResolutionVideo(3840, 2160))
    assertFalse(MediaProbe.isHighResolutionVideo(1920, 1080))
  }

  @Test
  fun `thumbnail cache scales cache budget and thumbnail dimensions by DeviceProfile`() =
    runTest {
      val conservative =
        DeviceProfile(
          tier = DeviceMemoryTier.CONSERVATIVE,
          availableRamMb = 500L,
          maxAnalysisWidthPx = 240,
          allowFrameSampling = true,
        )
      val performance =
        DeviceProfile(
          tier = DeviceMemoryTier.PERFORMANCE,
          availableRamMb = 3_200L,
          maxAnalysisWidthPx = 480,
          allowFrameSampling = true,
        )

      assertEquals(4 * 1024, VideoThumbnailCache.computeCacheMaxKb(conservative))
      assertEquals(16 * 1024, VideoThumbnailCache.computeCacheMaxKb(performance))
      assertEquals(1, VideoThumbnailCache.maxThumbnailsPerClip(conservative))
      assertEquals(5, VideoThumbnailCache.maxThumbnailsPerClip(performance))

      val (cW, cH) =
        VideoThumbnailCache.computeThumbnailDimensions(
          profile = conservative,
          sourceWidth = 1920,
          sourceHeight = 1080,
          rotationDegrees = 90,
        )
      assertEquals(96, cH)
      assertTrue(cW < cH)

      val blockedProfile = conservative.copy(allowFrameSampling = false)
      assertEquals(0, VideoThumbnailCache.maxThumbnailsPerClip(blockedProfile))
      val cache = VideoThumbnailCache(blockedProfile)
      val asset =
        MediaAsset(
          assetId = "a_test",
          type = MediaType.VIDEO,
          displayName = "clip.mp4",
          relativePath = "media/nonexistent.mp4",
          fingerprint =
            MediaFingerprint(
              byteSize = 100L,
              durationMs = 2_000L,
              partialSha256 = "abc",
              mimeType = "video/mp4",
            ),
        )
      val skipped = cache.loadThumbnail(asset, File("."), 500L, blockedProfile)
      assertTrue(skipped is ThumbnailLoadResult.Skipped)

      val hdrAsset =
        asset.copy(
          fingerprint = asset.fingerprint.copy(isHdr = true),
          proxyRelativePath = null,
        )
      val hdrSkipped = cache.loadThumbnail(hdrAsset, File("."), 500L, conservative)
      assertTrue(hdrSkipped is ThumbnailLoadResult.Skipped)
    }

  @Test
  fun `playback exception mapper and lifecycle observer handle pause, destroy, and visible UI errors`() {
    val fileError =
      PreviewPlayerController.mapPlaybackExceptionToUiError(
        PlaybackException("Missing", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
      )
    assertEquals(MediaUiErrorKind.FILE_UNAVAILABLE, fileError.kind)

    val hdrGpuError =
      PreviewPlayerController.mapPlaybackExceptionToUiError(
        PlaybackException("GPU FX failed", null, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),
      )
    assertEquals(MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK, hdrGpuError.kind)

    val codecError =
      PreviewPlayerController.mapPlaybackExceptionToUiError(
        PlaybackException("Unsupported", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
      )
    assertEquals(MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER, codecError.kind)

    val oomError = PreviewPlayerController.mapThrowableToUiError(OutOfMemoryError("oom"))
    assertEquals(MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED, oomError.kind)

    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = PreviewPlayerController(context)
    val fakeOwner =
      object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
      }

    controller.attachToLifecycle(fakeOwner.lifecycle)
    fakeOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fakeOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fakeOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    assertFalse(controller.state.value.isPlaying)

    fakeOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    assertFalse(controller.state.value.hasPlayableMedia)
    controller.restoreIfNeeded(1_200L)
    controller.disposePermanently()
  }
}
