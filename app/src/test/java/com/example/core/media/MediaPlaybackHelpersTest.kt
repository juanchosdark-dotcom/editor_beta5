package com.example.core.media

import android.media.MediaFormat
import androidx.media3.common.PlaybackException
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.model.MediaUiErrorKind
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.media.probe.MediaProbe
import com.example.core.model.MediaFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackHelpersTest {

  @Test
  fun `oriented dimensions and portrait detection handle rotation and aspect`() {
    assertEquals(90, MediaProbe.normalizeRotation(-270))
    assertEquals(270, MediaProbe.normalizeRotation(270))

    val rotatedLandscape =
      MediaFingerprint(
        byteSize = 2_000L,
        durationMs = 4_000L,
        partialSha256 = "hash",
        mimeType = "video/mp4",
        width = 1920,
        height = 1080,
        rotationDegrees = 90,
      )
    assertEquals(1080 to 1920, rotatedLandscape.orientedResolution())

    val portraitPreview = PreviewPlayerState(videoWidth = 1080, videoHeight = 1920)
    assertTrue(portraitPreview.isPortraitVideo)

    val landscapePreview = PreviewPlayerState(videoWidth = 1920, videoHeight = 1080)
    assertFalse(landscapePreview.isPortraitVideo)
  }

  @Test
  fun `hdr and 4k detection plus thumbnail profile budgets stay bounded`() {
    assertTrue(
      MediaProbe.isHdrColorMetadata(
        colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
        colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
      ),
    )
    assertTrue(MediaProbe.isHighResolutionVideo(3840, 2160))
    assertFalse(MediaProbe.isHighResolutionVideo(1280, 720))

    val conservative = DeviceProfile(tier = DeviceMemoryTier.CONSERVATIVE, allowFrameSampling = true)
    val balanced = DeviceProfile(tier = DeviceMemoryTier.BALANCED, allowFrameSampling = true)
    val performance = DeviceProfile(tier = DeviceMemoryTier.PERFORMANCE, allowFrameSampling = true)
    val disabled = DeviceProfile(tier = DeviceMemoryTier.CONSERVATIVE, allowFrameSampling = false)

    assertEquals(0, VideoThumbnailCache.maxThumbnailsPerClip(disabled))
    assertEquals(1, VideoThumbnailCache.maxThumbnailsPerClip(conservative))
    assertEquals(3, VideoThumbnailCache.maxThumbnailsPerClip(balanced))
    assertEquals(5, VideoThumbnailCache.maxThumbnailsPerClip(performance))

    val (w, h) =
      VideoThumbnailCache.computeThumbnailDimensions(
        profile = conservative,
        sourceWidth = 3840,
        sourceHeight = 2160,
        rotationDegrees = 270,
      )
    assertEquals(96, h)
    assertTrue(w < h)
  }

  @Test
  fun `playback exception mapping surfaces user-friendly HDR, decoder, memory, and IO errors`() {
    val hdrError =
      PreviewPlayerController.mapPlaybackErrorCodeToUiError(
        errorCode = PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        errorCodeName = "ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED",
      )
    assertEquals(MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK, hdrError.kind)

    val decoderError =
      PreviewPlayerController.mapPlaybackErrorCodeToUiError(
        errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        errorCodeName = "ERROR_CODE_DECODER_INIT_FAILED",
      )
    assertEquals(MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER, decoderError.kind)

    val oom = PreviewPlayerController.mapThrowableToUiError(OutOfMemoryError("oom"))
    assertEquals(MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED, oom.kind)

    val permission = PreviewPlayerController.mapThrowableToUiError(SecurityException("denied"))
    assertEquals(MediaUiErrorKind.FILE_UNAVAILABLE, permission.kind)
  }
}
