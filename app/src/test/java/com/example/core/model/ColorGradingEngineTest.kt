package com.example.core.model

import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.SingleColorLut
import androidx.test.core.app.ApplicationProvider
import com.example.core.media.engine.AnimatedOpacityRgbEffect
import com.example.core.media.engine.KeyframeMedia3Effects
import com.example.core.media.playback.PreviewPlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorGradingEngineTest {

  @Test
  fun colorGradeBounds_clampsOutOfRangeAndNonFiniteValuesForVideoAndAdjustment() {
    val dirtyVideo =
      VideoClipConfig(
        brightness = 9.0f,
        contrast = Float.NaN,
        saturation = -4.0f,
        exposure = Float.POSITIVE_INFINITY,
        temperature = -5.0f,
        vignette = 2.5f,
        lutPresetId = BuiltInLutPresets.ID_NONE,
        lutStrength = Float.NEGATIVE_INFINITY,
        tint = 3.0f,
        shadows = -9.0f,
        midtones = Float.NaN,
        highlights = 4.2f,
      )
    val clampedVideo = ColorGradeBounds.clampVideoColor(dirtyVideo)

    assertEquals(ColorGradeBounds.MAX_BRIGHTNESS, clampedVideo.brightness, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_CONTRAST, clampedVideo.contrast, 0.001f)
    assertEquals(ColorGradeBounds.MIN_SATURATION, clampedVideo.saturation, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_EXPOSURE, clampedVideo.exposure, 0.001f)
    assertEquals(ColorGradeBounds.MIN_TEMPERATURE, clampedVideo.temperature, 0.001f)
    assertEquals(ColorGradeBounds.MAX_VIGNETTE, clampedVideo.vignette, 0.001f)
    assertNull(clampedVideo.lutPresetId)
    assertEquals(ColorGradeBounds.DEFAULT_LUT_STRENGTH, clampedVideo.lutStrength, 0.001f)
    assertEquals(ColorGradeBounds.MAX_TINT, clampedVideo.tint, 0.001f)
    assertEquals(ColorGradeBounds.MIN_SHADOWS, clampedVideo.shadows, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_MIDTONES, clampedVideo.midtones, 0.001f)
    assertEquals(ColorGradeBounds.MAX_HIGHLIGHTS, clampedVideo.highlights, 0.001f)

    val dirtyAdj =
      AdjustmentClipConfig(
        brightness = -2.0f,
        contrast = 10.0f,
        saturation = 8.0f,
        exposure = -3.0f,
        temperature = Float.POSITIVE_INFINITY,
        vignette = -1.0f,
        lutPresetId = "  ",
        lutStrength = 2.0f,
        tint = -2.0f,
        shadows = 5.0f,
        midtones = -5.0f,
        highlights = Float.NEGATIVE_INFINITY,
        blendOpacity = Float.NaN,
      )
    val clampedAdj = ColorGradeBounds.clampAdjustmentColor(dirtyAdj)

    assertEquals(ColorGradeBounds.MIN_BRIGHTNESS, clampedAdj.brightness, 0.001f)
    assertEquals(ColorGradeBounds.MAX_CONTRAST, clampedAdj.contrast, 0.001f)
    assertEquals(ColorGradeBounds.MAX_SATURATION, clampedAdj.saturation, 0.001f)
    assertEquals(ColorGradeBounds.MIN_EXPOSURE, clampedAdj.exposure, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_TEMPERATURE, clampedAdj.temperature, 0.001f)
    assertEquals(ColorGradeBounds.MIN_VIGNETTE, clampedAdj.vignette, 0.001f)
    assertNull(clampedAdj.lutPresetId)
    assertEquals(ColorGradeBounds.MAX_LUT_STRENGTH, clampedAdj.lutStrength, 0.001f)
    assertEquals(ColorGradeBounds.MIN_TINT, clampedAdj.tint, 0.001f)
    assertEquals(ColorGradeBounds.MAX_SHADOWS, clampedAdj.shadows, 0.001f)
    assertEquals(ColorGradeBounds.MIN_MIDTONES, clampedAdj.midtones, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_HIGHLIGHTS, clampedAdj.highlights, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_BLEND_OPACITY, clampedAdj.blendOpacity, 0.001f)
  }

  @Test
  fun parametricMath_liftGammaGainTempTintAndSaturationBehaveDeterministically() {
    val neutralPipeline = ColorGradingEngine.buildPipeline(VideoClipConfig(), AdjustmentClipConfig())
    val neutralOut = ColorGradingEngine.evaluatePixelThroughPipeline(0.4f, 0.5f, 0.6f, neutralPipeline)
    assertEquals(0.4f, neutralOut[0], 0.001f)
    assertEquals(0.5f, neutralOut[1], 0.001f)
    assertEquals(0.6f, neutralOut[2], 0.001f)
    assertEquals(1.0f, neutralOut[3], 0.001f)

    // Saturation = 0 -> exact Rec.709 grayscale
    val bwPipeline = ColorGradingEngine.buildPipeline(VideoClipConfig(saturation = 0.0f), null)
    val bwOut = ColorGradingEngine.evaluatePixelThroughPipeline(0.8f, 0.3f, 0.1f, bwPipeline)
    assertEquals(bwOut[0], bwOut[1], 0.001f)
    assertEquals(bwOut[1], bwOut[2], 0.001f)

    // Temperature > 0 boosts Red over Blue; Tint > 0 shifts Magenta (suppresses Green relative to R/B)
    val warmMagenta =
      ColorGradingEngine.buildPipeline(VideoClipConfig(temperature = 0.6f, tint = 0.5f), null)
    val wmOut = ColorGradingEngine.evaluatePixelThroughPipeline(0.5f, 0.5f, 0.5f, warmMagenta)
    assertTrue(wmOut[0] > wmOut[2])
    assertTrue(wmOut[1] < wmOut[0])
    assertTrue(wmOut[1] < wmOut[2])

    // Lift/Shadows lifts dark pixels while keeping mid/white monotonic; Gamma boosts midtones without breaking endpoints
    val liftHigh = ColorGradingEngine.applyLiftGammaGain(0.05, 0.5, 0.9, shadows = 0.8, midtones = 0.0, highlights = 0.0)
    assertTrue(liftHigh.first > 0.05)
    assertTrue(liftHigh.first < liftHigh.second)
    assertTrue(liftHigh.second < liftHigh.third)

    val gammaBoost = ColorGradingEngine.applyLiftGammaGain(0.0, 0.5, 1.0, shadows = 0.0, midtones = 0.7, highlights = 0.0)
    assertEquals(0.0, gammaBoost.first, 0.0001)
    assertTrue(gammaBoost.second > 0.5)
    assertEquals(1.0, gammaBoost.third, 0.0001)

    // Adjustment layer blendOpacity = 0 disables adjustment effect completely
    val zeroBlendPipeline =
      ColorGradingEngine.buildPipeline(
        VideoClipConfig(),
        AdjustmentClipConfig(
          brightness = 0.4f,
          contrast = 1.6f,
          tint = 0.8f,
          shadows = -0.6f,
          lutPresetId = BuiltInLutPresets.TEAL_ORANGE.id,
          lutStrength = 1.0f,
          blendOpacity = 0.0f,
        ),
      )
    val zeroBlendOut = ColorGradingEngine.evaluatePixelThroughPipeline(0.35f, 0.45f, 0.55f, zeroBlendPipeline)
    assertEquals(0.35f, zeroBlendOut[0], 0.001f)
    assertEquals(0.45f, zeroBlendOut[1], 0.001f)
    assertEquals(0.55f, zeroBlendOut[2], 0.001f)
  }

  @Test
  fun pipelineStageOrdering_executesBaseThenLutThenOpacityDeterministically() {
    val pipeline =
      ColorGradingEngine.buildPipeline(
        videoCfg =
          VideoClipConfig(
            exposure = 0.4f,
            contrast = 1.3f,
            shadows = 0.25f,
            lutPresetId = BuiltInLutPresets.FILM_2383.id,
            lutStrength = 0.8f,
            opacity = 0.5f,
          ),
        adjustmentCfg = null,
      )
    assertEquals(
      listOf(
        ColorPipelineStage.BASE_ADJUSTMENTS,
        ColorPipelineStage.LUT_APPLICATION,
        ColorPipelineStage.OPACITY_BLEND,
      ),
      pipeline.orderedStages,
    )

    val fullOpacityPipeline =
      ColorGradingEngine.buildPipeline(
        videoCfg =
          VideoClipConfig(
            exposure = 0.4f,
            contrast = 1.3f,
            shadows = 0.25f,
            lutPresetId = BuiltInLutPresets.FILM_2383.id,
            lutStrength = 0.8f,
            opacity = 1.0f,
          ),
        adjustmentCfg = null,
      )

    val halfSample = ColorGradingEngine.evaluatePixelThroughPipeline(0.6f, 0.5f, 0.4f, pipeline)
    val fullSample = ColorGradingEngine.evaluatePixelThroughPipeline(0.6f, 0.5f, 0.4f, fullOpacityPipeline)

    // Stage 3 Opacity/Blend scales final graded RGB linearly AFTER Stage 1 (Base) and Stage 2 (LUT)
    assertEquals(fullSample[0] * 0.5f, halfSample[0], 0.001f)
    assertEquals(fullSample[1] * 0.5f, halfSample[1], 0.001f)
    assertEquals(fullSample[2] * 0.5f, halfSample[2], 0.001f)
    assertEquals(0.5f, halfSample[3], 0.001f)

    // Applying opacity BEFORE base/LUT would yield a noticeably different non-linear result
    val preDarkenedSample = ColorGradingEngine.evaluatePixelThroughPipeline(0.3f, 0.25f, 0.2f, fullOpacityPipeline)
    assertNotEquals(preDarkenedSample[0], halfSample[0], 0.01f)
  }

  @Test
  fun media3PreviewAndExportEffects_produceIdenticalDeterministicOrderWith3dLutAndOpacityLast() {
    val videoClip =
      TimelineClip(
        clipId = "v1",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "Scene 1",
        timelineStartMs = 0L,
        timelineEndMs = 4_000L,
        sourceInMs = 0L,
        sourceOutMs = 4_000L,
        videoConfig =
          VideoClipConfig(
            offsetX = 0.1f,
            opacity = 0.75f,
            exposure = 0.25f,
            contrast = 1.15f,
            saturation = 1.10f,
            temperature = 0.2f,
            tint = -0.15f,
            shadows = 0.2f,
            midtones = 0.1f,
            highlights = -0.1f,
            lutPresetId = BuiltInLutPresets.TEAL_ORANGE.id,
            lutStrength = 0.9f,
          ),
      )
    val adjClip =
      TimelineClip(
        clipId = "adj1",
        trackId = "t_adj",
        kind = ClipKind.ADJUSTMENT,
        label = "Master LUT",
        timelineStartMs = 0L,
        timelineEndMs = 4_000L,
        adjustmentConfig =
          AdjustmentClipConfig(
            brightness = 0.08f,
            contrast = 1.1f,
            tint = 0.2f,
            highlights = 0.15f,
            lutPresetId = BuiltInLutPresets.REC709_CLEAN.id,
            lutStrength = 0.6f,
            blendOpacity = 0.8f,
          ),
      )

    val timeline =
      TimelineState(
        playheadMs = 1_000L,
        tracks =
          listOf(
            Track("t_vid", TrackType.VIDEO, 0, "Video"),
            Track("t_adj", TrackType.ADJUSTMENT, 1, "LUT"),
          ),
        clips = listOf(videoClip, adjClip),
      )

    val exportEffects = KeyframeMedia3Effects.buildEffectsForClip(videoClip, adjClip.adjustmentConfig)
    val previewController = PreviewPlayerController(ApplicationProvider.getApplicationContext())
    val previewEffects = previewController.buildLivePreviewEffects(timeline, listOf(videoClip))

    assertTrue(exportEffects.first() is MatrixTransformation)
    assertTrue(previewEffects.first() is MatrixTransformation)

    // Exact parity between live preview and final export effect chains
    assertEquals(exportEffects.drop(1).map { it::class }, previewEffects.drop(1).map { it::class })

    val expectedPostTransformOrder =
      listOf(
        Brightness::class,
        Contrast::class,
        HslAdjustment::class,
        RgbAdjustment::class,
        SingleColorLut::class,
        Brightness::class,
        Contrast::class,
        RgbAdjustment::class,
        SingleColorLut::class,
        AnimatedOpacityRgbEffect::class,
      )
    assertEquals(expectedPostTransformOrder, exportEffects.drop(1).map { it::class })
  }

  @Test
  fun extendedControls_whitesBlacksVibranceWheelsAndCustomLutsBehaveDeterministically() {
    val dirty =
      VideoClipConfig(
        whites = 4.0f,
        blacks = -4.0f,
        vibrance = Float.NaN,
        liftX = 2.5f,
        liftY = -2.5f,
        gammaX = Float.POSITIVE_INFINITY,
        gammaY = 0.4f,
        gainX = -0.6f,
        gainY = 1.9f,
        depthBlur = 2.0f,
        depthBlurRadius = 99f,
        depthBlurFeather = 0.0f,
        depthFocusX = -0.5f,
        depthFocusY = 1.8f,
        depthFocusRange = 0.0f,
      )
    val clamped = ColorGradeBounds.clampVideoColor(dirty)
    assertEquals(ColorGradeBounds.MAX_WHITES, clamped.whites, 0.001f)
    assertEquals(ColorGradeBounds.MIN_BLACKS, clamped.blacks, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_VIBRANCE, clamped.vibrance, 0.001f)
    assertEquals(ColorGradeBounds.MAX_WHEEL_COORD, clamped.liftX, 0.001f)
    assertEquals(ColorGradeBounds.MIN_WHEEL_COORD, clamped.liftY, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_WHEEL_COORD, clamped.gammaX, 0.001f)
    assertEquals(0.4f, clamped.gammaY, 0.001f)
    assertEquals(-0.6f, clamped.gainX, 0.001f)
    assertEquals(ColorGradeBounds.MAX_WHEEL_COORD, clamped.gainY, 0.001f)
    assertEquals(ColorGradeBounds.MAX_DEPTH_BLUR, clamped.depthBlur, 0.001f)
    assertEquals(ColorGradeBounds.MAX_DEPTH_BLUR_RADIUS, clamped.depthBlurRadius, 0.001f)
    assertEquals(ColorGradeBounds.MIN_DEPTH_BLUR_FEATHER, clamped.depthBlurFeather, 0.001f)
    assertEquals(ColorGradeBounds.MIN_DEPTH_FOCUS_COORD, clamped.depthFocusX, 0.001f)
    assertEquals(ColorGradeBounds.MAX_DEPTH_FOCUS_COORD, clamped.depthFocusY, 0.001f)
    assertEquals(ColorGradeBounds.MIN_DEPTH_FOCUS_RANGE, clamped.depthFocusRange, 0.001f)

    // Warm red Lift wheel (+x) shifts dark shadow pixel toward warm red
    val warmLift = ColorGradingEngine.applyLiftGammaGain(0.15, 0.15, 0.15, 0.0, 0.0, 0.0, liftX = 0.8, liftY = 0.0)
    assertTrue(warmLift.first > warmLift.third)

    // Vibrance boosts muted tones proportionally more strongly than already highly-saturated colors
    val mutedIn = Triple(0.65, 0.45, 0.40) // initial chroma = 0.25
    val vividIn = Triple(0.95, 0.10, 0.10) // initial chroma = 0.85
    val mutedBoost = ColorGradingEngine.applyWhitesBlacksAndVibrance(mutedIn.first, mutedIn.second, mutedIn.third, whites = 0.0, blacks = 0.0, vibrance = 0.8)
    val vividBoost = ColorGradingEngine.applyWhitesBlacksAndVibrance(vividIn.first, vividIn.second, vividIn.third, whites = 0.0, blacks = 0.0, vibrance = 0.8)
    val mutedInitialChroma = mutedIn.first - mutedIn.third
    val vividInitialChroma = vividIn.first - vividIn.third
    val mutedRelativeGain = ((mutedBoost.first - mutedBoost.third) - mutedInitialChroma) / mutedInitialChroma
    val vividRelativeGain = ((vividBoost.first - vividBoost.third) - vividInitialChroma) / vividInitialChroma
    assertTrue(mutedRelativeGain > vividRelativeGain)
    assertTrue((mutedBoost.first - mutedBoost.third) - mutedInitialChroma > (vividBoost.first - vividBoost.third) - vividInitialChroma)

    // Custom LUT preset resolves in pipeline & 3D LUT cube generation
    val customPreset =
      LutPreset(
        id = "custom_warm_matte",
        name = "Mate Cálido",
        shortCode = "MATE",
        category = "Personal",
        brightness = 0.05f,
        contrast = 1.12f,
        temperature = 0.3f,
        shadowColorArgb = 0xFF282018L,
        midtoneColorArgb = 0xFF8C7258L,
        highlightColorArgb = 0xFFE6D4B8L,
        whites = -0.2f,
        blacks = 0.18f,
        liftX = 0.25f,
        isCustom = true,
      )
    val customPipeline =
      ColorGradingEngine.buildPipeline(
        videoCfg = VideoClipConfig(lutPresetId = customPreset.id, lutStrength = 1.0f),
        adjustmentCfg = null,
        customPresets = listOf(customPreset),
      )
    assertEquals(customPreset.id, customPipeline.clipLutStage.activeLut?.id)
    val cube = ColorGradingEngine.buildStage3dLutCube(size = 8, toneStage = customPipeline.clipBaseStage, lutStage = customPipeline.clipLutStage)
    assertEquals(8, cube.size)
  }

  @Test
  fun depthBlurMaskModes_resolutionScaling_scopesAndAbBypassWorkDeterministically() {
    // Radial: center is 0 (sharp), outer corner is 1 (blurred)
    val radialCenter =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.5f, 0.5f, 0.5f, 0.5f, 0.25f, 0.3f, DepthBlurMaskMode.RADIAL)
    val radialCorner =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.95f, 0.95f, 0.5f, 0.5f, 0.25f, 0.3f, DepthBlurMaskMode.RADIAL)
    assertEquals(0.0f, radialCenter, 0.001f)
    assertEquals(1.0f, radialCorner, 0.001f)

    // Linear tilt-shift: horizontal strip at focusY stays sharp even at left/right edges
    val linearSide =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.05f, 0.5f, 0.5f, 0.5f, 0.25f, 0.3f, DepthBlurMaskMode.LINEAR)
    val linearTop =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.5f, 0.02f, 0.5f, 0.5f, 0.25f, 0.3f, DepthBlurMaskMode.LINEAR)
    assertEquals(0.0f, linearSide, 0.001f)
    assertEquals(1.0f, linearTop, 0.001f)

    // Foreground subject similarity protects foreground subject from blur along mask edge
    val edgeNoSubject =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.76f, 0.5f, 0.5f, 0.5f, 0.2f, 0.2f, DepthBlurMaskMode.FOREGROUND_SUBJECT, subjectSimilarity = 0.0f)
    val edgeWithSubject =
      ColorGradingEngine.computeDepthBlurMaskWeight(0.76f, 0.5f, 0.5f, 0.5f, 0.2f, 0.2f, DepthBlurMaskMode.FOREGROUND_SUBJECT, subjectSimilarity = 0.95f)
    assertTrue(edgeWithSubject < edgeNoSubject)

    // 1080p radius scales 2.0x vs 540p proxy radius for optical parity
    val r540 = KeyframeMedia3Effects.resolveDepthBlurRadiusForHeight(15f, 0.8f, 540)
    val r1080 = KeyframeMedia3Effects.resolveDepthBlurRadiusForHeight(15f, 0.8f, 1080)
    assertEquals(r540 * 2.0f, r1080, 0.001f)

    // Real-time scopes reflect exposure boost & bypass flag
    val brightPipeline = ColorGradingEngine.buildPipeline(VideoClipConfig(exposure = 0.7f, contrast = 1.2f), null)
    val bypassScope = ColorGradingEngine.computeColorScopes(brightPipeline, bypass = true)
    val gradedScope = ColorGradingEngine.computeColorScopes(brightPipeline, bypass = false)
    assertEquals(24, gradedScope.lumaBins.size)
    assertEquals(100, gradedScope.shadowsPct + gradedScope.midtonesPct + gradedScope.highlightsPct)
    assertTrue(gradedScope.highlightsPct >= bypassScope.highlightsPct)

    // PreviewPlayerController A/B bypass removes grading & depth blur effects cleanly
    val videoClip =
      TimelineClip(
        clipId = "v_ab",
        trackId = "t_vid",
        kind = ClipKind.VIDEO,
        label = "AB",
        timelineStartMs = 0L,
        timelineEndMs = 3_000L,
        sourceInMs = 0L,
        sourceOutMs = 3_000L,
        videoConfig = VideoClipConfig(exposure = 0.4f, depthBlur = 0.7f, lutPresetId = BuiltInLutPresets.TEAL_ORANGE.id),
      )
    val timeline =
      TimelineState(
        playheadMs = 500L,
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips = listOf(videoClip),
      )
    val controller = PreviewPlayerController(ApplicationProvider.getApplicationContext())
    try {
      val activeFx = controller.buildLivePreviewEffects(timeline, listOf(videoClip))
      assertTrue(activeFx.any { it is com.example.core.media.engine.SelectiveDepthBlurEffect })
      assertTrue(activeFx.any { it is Brightness })

      controller.setAbBypassEnabled(true)
      val bypassedFx = controller.buildLivePreviewEffects(timeline, listOf(videoClip))
      assertTrue(bypassedFx.none { it is com.example.core.media.engine.SelectiveDepthBlurEffect })
      assertTrue(bypassedFx.none { it is Brightness })
      assertTrue(bypassedFx.none { it is SingleColorLut })
    } finally {
      controller.release()
    }
  }
}
