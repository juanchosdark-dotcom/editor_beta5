package com.example.core.timeline

import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.test.core.app.ApplicationProvider
import com.example.core.export.RangeSliceHelper
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.engine.AnimatedOpacityRgbEffect
import com.example.core.media.engine.CompiledCompositionResult
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.engine.KeyframeMedia3Effects
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.media.model.PreviewQualityProfile
import com.example.core.media.model.RenderSettings
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.ClipKind
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.KeyframeValidationErrorCode
import com.example.core.model.KeyframeValidator
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertArrayEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyframeSystemAndParityTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `validator catches out-of-range offsets, non-finite values, and duplicates while sanitize clamps safely`() {
    val invalidList =
      listOf(
        ClipKeyframe("k1", offsetMs = -50L, scale = 10f, opacity = 1.5f, offsetX = -3f, offsetY = 3f, rotationDegrees = 400f, volume = 5f),
        ClipKeyframe("k1", offsetMs = 6_000L, scale = Float.NaN, opacity = 0.5f),
      )

    val validation = KeyframeValidator.validateKeyframes(invalidList, clipDurationMs = 4_000L)
    assertFalse(validation.isValid)
    val codes = validation.issues.map { it.code }.toSet()
    assertTrue(KeyframeValidationErrorCode.NEGATIVE_OFFSET in codes)
    assertTrue(KeyframeValidationErrorCode.OFFSET_EXCEEDS_CLIP_DURATION in codes)
    assertTrue(KeyframeValidationErrorCode.SCALE_OUT_OF_RANGE in codes)
    assertTrue(KeyframeValidationErrorCode.OPACITY_OUT_OF_RANGE in codes)
    assertTrue(KeyframeValidationErrorCode.POSITION_OUT_OF_RANGE in codes)
    assertTrue(KeyframeValidationErrorCode.ROTATION_OUT_OF_RANGE in codes)
    assertTrue(KeyframeValidationErrorCode.VOLUME_OUT_OF_RANGE in codes)
    assertTrue(KeyframeValidationErrorCode.NON_FINITE_VALUE in codes)
    assertTrue(KeyframeValidationErrorCode.DUPLICATE_KEYFRAME_ID in codes)

    val normalized = KeyframeValidator.normalizeKeyframes(invalidList, clipDurationMs = 4_000L)
    assertEquals(2, normalized.size)
    assertEquals(0L, normalized[0].offsetMs)
    assertEquals(KeyframeValidator.MAX_SCALE, normalized[0].scale, 0.0001f)
    assertEquals(KeyframeValidator.MAX_OPACITY, normalized[0].opacity, 0.0001f)
    assertEquals(KeyframeValidator.MIN_OFFSET, normalized[0].offsetX, 0.0001f)
    assertEquals(KeyframeValidator.MAX_OFFSET, normalized[0].offsetY, 0.0001f)
    assertEquals(KeyframeValidator.MAX_ROTATION_DEGREES, normalized[0].rotationDegrees, 0.0001f)
    assertEquals(KeyframeValidator.MAX_VOLUME, normalized[0].volume ?: -1f, 0.0001f)
    assertEquals(4_000L, normalized[1].offsetMs)
    assertEquals(1.0f, normalized[1].scale, 0.0001f)
  }

  @Test
  fun `evaluator produces exact deterministic values for linear, ease-in, ease-out, and ease-in-out curves`() {
    val startKf =
      ClipKeyframe(
        keyframeId = "k0",
        offsetMs = 0L,
        scale = 1.0f,
        opacity = 0.0f,
        offsetX = -1.0f,
        offsetY = 0.0f,
        rotationDegrees = 0f,
        volume = 0.2f,
      )
    val endKf =
      ClipKeyframe(
        keyframeId = "k1",
        offsetMs = 1_000L,
        scale = 2.0f,
        opacity = 1.0f,
        offsetX = 1.0f,
        offsetY = 1.0f,
        rotationDegrees = 180f,
        volume = 1.2f,
      )

    // Ease In at t = 0.5 -> 0.5^3 = 0.125
    val easeInEval =
      ClipKeyframeEvaluator.evaluateAt(
        offsetMs = 500L,
        keyframes = listOf(startKf.copy(interpolation = KeyframeInterpolation.EASE_IN), endKf),
      )
    assertEquals(1.125f, easeInEval.scale, 0.0001f)
    assertEquals(0.125f, easeInEval.opacity, 0.0001f)
    assertEquals(-0.75f, easeInEval.offsetX, 0.0001f)
    assertEquals(22.5f, easeInEval.rotationDegrees, 0.001f)
    assertEquals(0.325f, easeInEval.volume, 0.0001f)

    // Ease Out at t = 0.5 -> 1 - 0.5^3 = 0.875
    val easeOutEval =
      ClipKeyframeEvaluator.evaluateAt(
        offsetMs = 500L,
        keyframes = listOf(startKf.copy(interpolation = KeyframeInterpolation.EASE_OUT), endKf),
      )
    assertEquals(1.875f, easeOutEval.scale, 0.0001f)
    assertEquals(0.875f, easeOutEval.opacity, 0.0001f)
    assertEquals(0.75f, easeOutEval.offsetX, 0.0001f)
    assertEquals(157.5f, easeOutEval.rotationDegrees, 0.001f)
    assertEquals(1.075f, easeOutEval.volume, 0.0001f)

    // Ease In-Out at t = 0.25 -> 4 * 0.25^3 = 0.0625; at t = 0.5 -> 0.5
    val easeInOutQuarter =
      ClipKeyframeEvaluator.evaluateAt(
        offsetMs = 250L,
        keyframes = listOf(startKf.copy(interpolation = KeyframeInterpolation.EASE_IN_OUT), endKf),
      )
    assertEquals(1.0625f, easeInOutQuarter.scale, 0.0001f)
    val easeInOutMid =
      ClipKeyframeEvaluator.evaluateAt(
        offsetMs = 500L,
        keyframes = listOf(startKf.copy(interpolation = KeyframeInterpolation.EASE_IN_OUT), endKf),
      )
    assertEquals(1.5f, easeInOutMid.scale, 0.0001f)
  }

  @Test
  fun `timeline engine CRUD, navigation, split, trim, and speed preserve and remap keyframes accurately`() {
    val engine = TimelineEngine()
    var state =
      TimelineState(
        playheadMs = 1_000L,
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "V1"), Track("a1", TrackType.AUDIO, 1, "A1")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_v",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              videoConfig = VideoClipConfig(),
            ),
            TimelineClip(
              clipId = "c_a",
              trackId = "a1",
              kind = ClipKind.AUDIO,
              label = "A1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              audioConfig = AudioClipConfig(),
            ),
          ),
      )

    state =
      (engine.upsertKeyframe(
        state = state,
        clipId = "c_v",
        offsetMs = 0L,
        scale = 1.0f,
        offsetX = -0.4f,
        volume = 0.5f,
        interpolation = KeyframeInterpolation.EASE_IN_OUT,
      ) as TimelineOperationResult.Success).newState

    state =
      (engine.upsertKeyframe(
        state = state,
        clipId = "c_v",
        offsetMs = 4_000L,
        scale = 2.0f,
        offsetX = 0.4f,
        volume = 1.5f,
        interpolation = KeyframeInterpolation.LINEAR,
      ) as TimelineOperationResult.Success).newState

    val videoClip = state.clips.first { it.clipId == "c_v" }
    val firstKfId = videoClip.keyframes().first().keyframeId
    val secondKfId = videoClip.keyframes().last().keyframeId

    // Previous / Next keyframe navigation relative to playhead 1000ms
    assertEquals(firstKfId, engine.findPreviousKeyframe(state, "c_v")?.keyframeId)
    assertEquals(secondKfId, engine.findNextKeyframe(state, "c_v")?.keyframeId)

    // Move first keyframe to 500ms and change interpolation to EASE_OUT
    state =
      (engine.moveKeyframe(state, "c_v", firstKfId, 500L) as TimelineOperationResult.Success).newState
    state =
      (engine.updateKeyframeInterpolation(state, "c_v", firstKfId, KeyframeInterpolation.EASE_OUT)
        as TimelineOperationResult.Success).newState
    assertEquals(500L, state.clips.first { it.clipId == "c_v" }.keyframes().first().offsetMs)
    assertEquals(KeyframeInterpolation.EASE_OUT, state.clips.first { it.clipId == "c_v" }.keyframes().first().interpolation)

    // Add audio keyframe on AUDIO clip
    state =
      (engine.upsertKeyframe(state, "c_a", offsetMs = 1_500L, volume = 0.25f) as TimelineOperationResult.Success).newState
    assertEquals(1, state.clips.first { it.clipId == "c_a" }.keyframes().size)
    assertEquals(0.25f, state.clips.first { it.clipId == "c_a" }.evaluatePropertiesAt(1_500L).volume, 0.001f)

    // Split c_v at 2000ms -> boundary anchor keyframe inserted cleanly so both halves match continuous animation!
    val preSplitMid = state.clips.first { it.clipId == "c_v" }.evaluatePropertiesAt(2_000L)
    val splitRes = engine.splitAtPlayhead(state.copy(playheadMs = 2_000L), targetClipId = "c_v") as TimelineOperationResult.Success
    val leftHalf = splitRes.newState.clips.first { it.clipId == "c_v" }
    val rightHalf = splitRes.newState.clips.first { it.clipId != "c_v" && it.kind == ClipKind.VIDEO }
    assertEquals(preSplitMid.scale, leftHalf.evaluatePropertiesAt(2_000L).scale, 0.01f)
    assertEquals(preSplitMid.scale, rightHalf.evaluatePropertiesAt(0L).scale, 0.01f)
    assertEquals(preSplitMid.offsetX, rightHalf.evaluatePropertiesAt(0L).offsetX, 0.01f)

    // Speed 2.0x rescales keyframe timestamps proportionally
    val speedRes = engine.updateClipSpeed(state, "c_v", 2.0f) as TimelineOperationResult.Success
    val spedClip = speedRes.newState.clips.first { it.clipId == "c_v" }
    assertEquals(2_000L, spedClip.durationMs)
    assertEquals(250L, spedClip.keyframes().first().offsetMs)
    assertEquals(2_000L, spedClip.keyframes().last().offsetMs)

    // RangeSliceHelper preserves boundary interpolated keyframes for partial export ranges
    val sliced = RangeSliceHelper.sliceTimelineForRange(state, 1_000L, 3_000L)
    val slicedV = sliced.clips.first { it.clipId == "c_v" }
    val expectedAt1000 = state.clips.first { it.clipId == "c_v" }.evaluatePropertiesAt(1_000L)
    val expectedAt3000 = state.clips.first { it.clipId == "c_v" }.evaluatePropertiesAt(3_000L)
    assertEquals(expectedAt1000.scale, slicedV.evaluatePropertiesAt(0L).scale, 0.01f)
    assertEquals(expectedAt3000.scale, slicedV.evaluatePropertiesAt(2_000L).scale, 0.01f)

    // Clear keyframes
    val cleared = (engine.clearKeyframes(state, "c_v") as TimelineOperationResult.Success).newState
    assertTrue(cleared.clips.first { it.clipId == "c_v" }.keyframes().isEmpty())
    assertNull(engine.findNextKeyframe(cleared, "c_v"))
  }

  @Test
  fun `preview 540p with proxy and final 1080p export evaluate identical transform opacity and audio gain`() {
    val rootDir = tempFolder.newFolder("parity_proj")
    val mediaFile = File(rootDir, "media/orig.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
    }
    val proxyFile = File(rootDir, "proxies/proxy_540p.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3))
    }

    val asset =
      MediaAsset(
        assetId = "asset_1",
        type = MediaType.VIDEO,
        displayName = "orig.mp4",
        relativePath = "media/${mediaFile.name}",
        proxyRelativePath = "proxies/${proxyFile.name}",
        fingerprint = MediaFingerprint(6L, 6_000L, "sha", "video/mp4", width = 1920, height = 1080, hasAudio = true),
      )

    val keyframes =
      listOf(
        ClipKeyframe(
          keyframeId = "k_start",
          offsetMs = 0L,
          scale = 1.0f,
          opacity = 0.4f,
          offsetX = -0.5f,
          offsetY = 0.25f,
          rotationDegrees = -20f,
          volume = 0.3f,
          interpolation = KeyframeInterpolation.EASE_IN_OUT,
        ),
        ClipKeyframe(
          keyframeId = "k_end",
          offsetMs = 4_000L,
          scale = 1.8f,
          opacity = 0.95f,
          offsetX = 0.5f,
          offsetY = -0.25f,
          rotationDegrees = 40f,
          volume = 1.6f,
          interpolation = KeyframeInterpolation.LINEAR,
        ),
      )

    val videoClip =
      TimelineClip(
        clipId = "clip_main",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Clip Paridad",
        timelineStartMs = 0L,
        timelineEndMs = 4_000L,
        sourceInMs = 0L,
        sourceOutMs = 4_000L,
        assetId = asset.assetId,
        speed = 1.0f,
        videoConfig = VideoClipConfig(fadeInMs = 400L, fadeOutMs = 400L, keyframes = keyframes),
      )

    val timeline =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video", volume = 0.85f)),
        clips = listOf(videoClip),
        duckingConfig = AudioDuckingConfig(enabled = false),
      )

    val compiler = CompositionCompiler()
    val previewCompiled =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Preview(PreviewQualityProfile.BALANCED_540P, useProxies = true),
      ) as CompiledCompositionResult.Ready

    val exportCompiled =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    // Preview used 540p proxy, export used 1080p original
    assertEquals(1, previewCompiled.usedProxyCount)
    assertEquals(0, exportCompiled.usedProxyCount)

    val previewEffects = previewCompiled.composition.sequences.first().editedMediaItems.first().effects.videoEffects
    val exportEffects = exportCompiled.composition.sequences.first().editedMediaItems.first().effects.videoEffects

    val previewMatrixFx = previewEffects.filterIsInstance<MatrixTransformation>().firstOrNull()
    val exportMatrixFx = exportEffects.filterIsInstance<MatrixTransformation>().firstOrNull()
    val previewOpacityFx = previewEffects.filterIsInstance<AnimatedOpacityRgbEffect>().firstOrNull()
    val exportOpacityFx = exportEffects.filterIsInstance<AnimatedOpacityRgbEffect>().firstOrNull()

    assertNotNull(previewMatrixFx)
    assertNotNull(exportMatrixFx)
    assertNotNull(previewOpacityFx)
    assertNotNull(exportOpacityFx)

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val previewController = PreviewPlayerController(context)
    try {
      previewController.loadTimelinePreview(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderSettings = RenderSettings(previewQuality = PreviewQualityProfile.BALANCED_540P, useProxiesForPreview = true),
      )

      val sampleTimesMs = listOf(0L, 500L, 1_250L, 2_000L, 3_200L, 4_000L)
      val previewMatrixVals = FloatArray(9)
      val exportMatrixVals = FloatArray(9)

      for (tMs in sampleTimesMs) {
        val ptsUs = tMs * 1_000L
        previewMatrixFx!!.getMatrix(ptsUs).getValues(previewMatrixVals)
        exportMatrixFx!!.getMatrix(ptsUs).getValues(exportMatrixVals)
        assertArrayEquals("Transform matrix differs at ${tMs}ms", exportMatrixVals, previewMatrixVals, 0.0001f)

        val previewRgb: FloatArray = (previewOpacityFx as RgbMatrix).getMatrix(ptsUs, false)
        val exportRgb: FloatArray = (exportOpacityFx as RgbMatrix).getMatrix(ptsUs, false)
        assertArrayEquals("Opacity RGB matrix differs at ${tMs}ms", exportRgb, previewRgb, 0.0001f)

        // Compare PreviewPlayerController's evaluated properties & audio gain against export AudioTrackMixer
        val controllerEval = previewController.evaluateActiveClipPropertiesAt(tMs)
        val exportDirectEval = KeyframeMedia3Effects.evaluateClipAtPresentationUs(videoClip, ptsUs)
        assertNotNull(controllerEval)
        assertEquals(exportDirectEval.scale, controllerEval!!.scale, 0.0001f)
        assertEquals(exportDirectEval.offsetX, controllerEval.offsetX, 0.0001f)
        assertEquals(exportDirectEval.offsetY, controllerEval.offsetY, 0.0001f)
        assertEquals(exportDirectEval.rotationDegrees, controllerEval.rotationDegrees, 0.0001f)
        assertEquals(exportDirectEval.opacity, controllerEval.opacity, 0.0001f)
        assertEquals(exportDirectEval.volume, controllerEval.volume, 0.0001f)

        val previewGain = previewController.evaluatePreviewAudioGainAt(tMs)
        assertNotNull(previewGain)
        val exportGain =
          AudioTrackMixer.computeClipEnvelopeGainAt(
            clipStartOnTimelineMs = videoClip.timelineStartMs,
            clipOffsetMs = tMs,
            clipDurationMs = videoClip.durationMs,
            baseVolume = videoClip.videoConfig!!.volume,
            fadeInMs = videoClip.videoConfig.fadeInMs,
            fadeOutMs = videoClip.videoConfig.fadeOutMs,
            duckingWindows = emptyList<com.example.core.model.DuckingWindow>(),
            volumeKeyframes = keyframes,
            trackVolume = 0.85f,
          )
        assertEquals("Audio gain differs between preview and export at ${tMs}ms", exportGain, previewGain!!, 0.0001f)
      }
    } finally {
      previewController.release()
    }
  }

  @Test
  fun `rack focus depth blur keyframes interpolate smoothly across split range slice and 540p to 1080p parity`() {
    val engine = TimelineEngine()
    val rootDir = tempFolder.newFolder("rack_focus_parity")
    val mediaFile = File(rootDir, "media/orig.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val proxyFile = File(rootDir, "proxies/proxy_540p.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2))
    }
    val asset =
      MediaAsset(
        assetId = "asset_rf",
        type = MediaType.VIDEO,
        displayName = "orig.mp4",
        relativePath = "media/${mediaFile.name}",
        proxyRelativePath = "proxies/${proxyFile.name}",
        fingerprint = MediaFingerprint(4L, 4_000L, "sha", "video/mp4", width = 1920, height = 1080, hasAudio = false),
      )

    var state =
      TimelineState(
        playheadMs = 0L,
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "V1")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_rf",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Rack Focus",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              assetId = asset.assetId,
              videoConfig = VideoClipConfig(depthBlur = 0.8f, depthBlurRadius = 15f),
            ),
          ),
      )

    state =
      (engine.upsertKeyframe(
        state = state,
        clipId = "c_rf",
        offsetMs = 0L,
        depthBlur = 0.4f,
        depthFocusX = 0.2f,
        depthFocusY = 0.3f,
        depthFocusRange = 0.2f,
        interpolation = KeyframeInterpolation.LINEAR,
      ) as TimelineOperationResult.Success).newState
    state =
      (engine.upsertKeyframe(
        state = state,
        clipId = "c_rf",
        offsetMs = 4_000L,
        depthBlur = 0.9f,
        depthFocusX = 0.8f,
        depthFocusY = 0.7f,
        depthFocusRange = 0.5f,
        interpolation = KeyframeInterpolation.LINEAR,
      ) as TimelineOperationResult.Success).newState

    val clip = state.clips.first()
    val midEval = clip.evaluatePropertiesAt(2_000L)
    assertEquals(0.65f, midEval.depthBlur, 0.001f)
    assertEquals(0.50f, midEval.depthFocusX, 0.001f)
    assertEquals(0.50f, midEval.depthFocusY, 0.001f)
    assertEquals(0.35f, midEval.depthFocusRange, 0.001f)

    // Split preserves continuous rack-focus position on both halves
    val splitRes = engine.splitAtPlayhead(state.copy(playheadMs = 2_000L), "c_rf") as TimelineOperationResult.Success
    val rightHalf = splitRes.newState.clips.first { it.clipId != "c_rf" }
    assertEquals(0.50f, rightHalf.evaluatePropertiesAt(0L).depthFocusX, 0.01f)
    assertEquals(0.65f, rightHalf.evaluatePropertiesAt(0L).depthBlur, 0.01f)

    // Range slice preserves interpolated boundary values
    val sliced = RangeSliceHelper.sliceTimelineForRange(state, 1_000L, 3_000L).clips.first()
    assertEquals(0.35f, sliced.evaluatePropertiesAt(0L).depthFocusX, 0.01f)
    assertEquals(0.65f, sliced.evaluatePropertiesAt(2_000L).depthFocusX, 0.01f)

    // Both 540p preview & 1080p export emit SelectiveDepthBlurEffect
    val compiler = CompositionCompiler()
    val previewReady =
      compiler.compile(
        timeline = state,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Preview(PreviewQualityProfile.BALANCED_540P, useProxies = true),
      ) as CompiledCompositionResult.Ready
    val exportReady =
      compiler.compile(
        timeline = state,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    val previewBlurFx =
      previewReady.composition.sequences.first().editedMediaItems.first().effects.videoEffects
        .filterIsInstance<com.example.core.media.engine.SelectiveDepthBlurEffect>()
    val exportBlurFx =
      exportReady.composition.sequences.first().editedMediaItems.first().effects.videoEffects
        .filterIsInstance<com.example.core.media.engine.SelectiveDepthBlurEffect>()
    assertEquals(1, previewBlurFx.size)
    assertEquals(1, exportBlurFx.size)
  }
}
