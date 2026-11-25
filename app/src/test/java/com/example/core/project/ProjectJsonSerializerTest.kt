package com.example.core.project

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.ColorGradeBounds
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.LutPreset
import com.example.core.model.ProjectDocument
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonSerializerTest {

  @Test
  fun encodeDecode_preservesSnapshotsSequencesDuckingAndSubtitleStyles() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        playheadMs = 1_200L,
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("c1", "t_vid", ClipKind.VIDEO, "Intro", 0L, 3_000L, 0L, 3_000L),
          ),
        duckingConfig = AudioDuckingConfig(enabled = true, attenuationFactor = 0.3f),
      )

    val doc =
      ProjectDocument(
        projectId = "p_1",
        name = "Portable Test",
        createdAtIso = "2026-11-23T10:00:00Z",
        updatedAtIso = "2026-11-23T10:20:00Z",
        timeline = timeline,
        activeSequenceId = "seq_main",
        sequences =
          listOf(TimelineSequence("seq_main", "Principal", "2026-11-23T10:00:00Z", timeline)),
        snapshots =
          listOf(TimelineSnapshot("snap_1", "Checkpoint 1", "2026-11-23T10:15:00Z", "", timeline)),
        savedSubtitleStyles = listOf(BuiltInSubtitleStyles.HIGH_CONTRAST_AMBER),
      )

    val json = serializer.encode(doc)
    val decoded = serializer.decode(json)

    assertTrue(decoded.isSuccess)
    val value = decoded.getOrThrow()
    assertEquals(doc.projectId, value.projectId)
    assertEquals(1, value.snapshots.size)
    assertEquals(1, value.sequences.size)
    assertTrue(value.timeline.duckingConfig.enabled)
    assertEquals(0.3f, value.timeline.duckingConfig.attenuationFactor, 0.001f)
    assertEquals(1, value.savedSubtitleStyles.size)
  }

  @Test
  fun decode_legacyJsonWithoutKeyframeVolumeInterpolationOrAudioKeyframes_staysCompatible() {
    val legacyJson =
      """
      {
        "schemaVersion": 1,
        "projectId": "legacy_proj",
        "name": "Legacy Project",
        "createdAtIso": "2025-05-01T10:00:00Z",
        "updatedAtIso": "2025-05-01T10:05:00Z",
        "timeline": {
          "playheadMs": 500,
          "tracks": [
            {"trackId": "t_vid", "type": "VIDEO", "order": 0, "name": "Video"},
            {"trackId": "t_aud", "type": "AUDIO", "order": 1, "name": "Audio"}
          ],
          "clips": [
            {
              "clipId": "c_vid_legacy",
              "trackId": "t_vid",
              "kind": "VIDEO",
              "label": "Clip Antiguo",
              "timelineStartMs": 0,
              "timelineEndMs": 4000,
              "sourceInMs": 0,
              "sourceOutMs": 4000,
              "videoConfig": {
                "volume": 0.45,
                "keyframes": [
                  {"keyframeId": "k1", "offsetMs": 0, "scale": 1.0, "opacity": 0.8, "offsetX": 0.0, "offsetY": 0.0, "rotationDegrees": 0.0},
                  {"keyframeId": "k2", "offsetMs": 2000, "scale": 1.5, "opacity": 1.0, "offsetX": 0.2, "offsetY": -0.1, "rotationDegrees": 15.0}
                ]
              }
            },
            {
              "clipId": "c_aud_legacy",
              "trackId": "t_aud",
              "kind": "AUDIO",
              "label": "Audio Antiguo",
              "timelineStartMs": 0,
              "timelineEndMs": 4000,
              "sourceInMs": 0,
              "sourceOutMs": 4000,
              "audioConfig": {
                "volume": 0.75,
                "fadeInMs": 200
              }
            }
          ]
        }
      }
      """.trimIndent()

    val serializer = ProjectJsonSerializer()
    val decoded = serializer.decode(legacyJson).getOrThrow()
    val vidClip = decoded.timeline.clips.first { it.clipId == "c_vid_legacy" }
    val audClip = decoded.timeline.clips.first { it.clipId == "c_aud_legacy" }

    val kfs = vidClip.videoConfig!!.keyframes
    assertEquals(2, kfs.size)
    assertNull(kfs[0].volume)
    assertEquals(KeyframeInterpolation.LINEAR, kfs[0].interpolation)

    // Legacy keyframes without explicit volume must preserve base videoConfig.volume (0.45)
    val evalMid = vidClip.evaluatePropertiesAt(1_000L)
    assertEquals(1.25f, evalMid.scale, 0.001f)
    assertEquals(0.9f, evalMid.opacity, 0.001f)
    assertEquals(0.1f, evalMid.offsetX, 0.001f)
    assertEquals(-0.05f, evalMid.offsetY, 0.001f)
    assertEquals(7.5f, evalMid.rotationDegrees, 0.001f)
    assertEquals(0.45f, evalMid.volume, 0.001f)

    assertTrue(audClip.audioConfig!!.keyframes.isEmpty())
    assertEquals(0.75f, audClip.evaluatePropertiesAt(1_000L).volume, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsAllKeyframePropertiesAndInterpolations() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video"), Track("t_aud", TrackType.AUDIO, 1, "Audio")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_v",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              videoConfig =
                VideoClipConfig(
                  keyframes =
                    listOf(
                      ClipKeyframe(
                        keyframeId = "kv1",
                        offsetMs = 0L,
                        scale = 1.0f,
                        opacity = 0.5f,
                        offsetX = -0.25f,
                        offsetY = 0.1f,
                        rotationDegrees = -15f,
                        volume = 0.6f,
                        interpolation = KeyframeInterpolation.EASE_IN_OUT,
                      ),
                      ClipKeyframe(
                        keyframeId = "kv2",
                        offsetMs = 3_000L,
                        scale = 1.6f,
                        opacity = 1.0f,
                        offsetX = 0.35f,
                        offsetY = -0.2f,
                        rotationDegrees = 45f,
                        volume = 1.4f,
                        interpolation = KeyframeInterpolation.EASE_OUT,
                      ),
                    ),
                ),
            ),
            TimelineClip(
              clipId = "c_a",
              trackId = "t_aud",
              kind = ClipKind.AUDIO,
              label = "A1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              audioConfig =
                AudioClipConfig(
                  volume = 1.0f,
                  keyframes =
                    listOf(
                      ClipKeyframe("ka1", offsetMs = 500L, volume = 0.2f, interpolation = KeyframeInterpolation.EASE_IN),
                      ClipKeyframe("ka2", offsetMs = 2_500L, volume = 1.5f, interpolation = KeyframeInterpolation.LINEAR),
                    ),
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_kf",
        name = "Keyframes RoundTrip",
        createdAtIso = "2026-11-24T10:00:00Z",
        updatedAtIso = "2026-11-24T10:00:00Z",
        timeline = timeline,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val kv1 = decoded.timeline.clips.first { it.clipId == "c_v" }.keyframes().first()
    val ka1 = decoded.timeline.clips.first { it.clipId == "c_a" }.keyframes().first()

    assertEquals(KeyframeInterpolation.EASE_IN_OUT, kv1.interpolation)
    assertEquals(-0.25f, kv1.offsetX, 0.001f)
    assertEquals(0.1f, kv1.offsetY, 0.001f)
    assertEquals(-15f, kv1.rotationDegrees, 0.001f)
    assertEquals(0.6f, kv1.volume ?: -1f, 0.001f)
    assertEquals(KeyframeInterpolation.EASE_IN, ka1.interpolation)
    assertEquals(0.2f, ka1.volume ?: -1f, 0.001f)
  }

  @Test
  fun decode_legacyJsonWithoutTintLiftGammaGainOrBlendOpacity_usesNeutralDefaults() {
    val legacyColorJson =
      """
      {
        "schemaVersion": 1,
        "projectId": "legacy_color",
        "name": "Legacy Color",
        "createdAtIso": "2025-06-01T10:00:00Z",
        "updatedAtIso": "2025-06-01T10:05:00Z",
        "timeline": {
          "tracks": [
            {"trackId": "t_vid", "type": "VIDEO", "order": 0, "name": "Video"},
            {"trackId": "t_adj", "type": "ADJUSTMENT", "order": 1, "name": "LUT"}
          ],
          "clips": [
            {
              "clipId": "v_old",
              "trackId": "t_vid",
              "kind": "VIDEO",
              "label": "Video Old",
              "timelineStartMs": 0,
              "timelineEndMs": 3000,
              "sourceInMs": 0,
              "sourceOutMs": 3000,
              "videoConfig": {
                "brightness": 0.1,
                "contrast": 1.2,
                "saturation": 1.1,
                "temperature": -0.2
              }
            },
            {
              "clipId": "adj_old",
              "trackId": "t_adj",
              "kind": "ADJUSTMENT",
              "label": "LUT Old",
              "timelineStartMs": 0,
              "timelineEndMs": 3000,
              "adjustmentConfig": {
                "exposure": 0.25,
                "lutPresetId": "teal_orange",
                "lutStrength": 0.8
              }
            }
          ]
        }
      }
      """.trimIndent()

    val serializer = ProjectJsonSerializer()
    val decoded = serializer.decode(legacyColorJson).getOrThrow()
    val vCfg = decoded.timeline.clips.first { it.clipId == "v_old" }.videoConfig!!
    val aCfg = decoded.timeline.clips.first { it.clipId == "adj_old" }.adjustmentConfig!!

    assertEquals(0.1f, vCfg.brightness, 0.001f)
    assertEquals(0.0f, vCfg.tint, 0.001f)
    assertEquals(0.0f, vCfg.shadows, 0.001f)
    assertEquals(0.0f, vCfg.midtones, 0.001f)
    assertEquals(0.0f, vCfg.highlights, 0.001f)

    assertEquals(0.25f, aCfg.exposure, 0.001f)
    assertEquals("teal_orange", aCfg.lutPresetId)
    assertEquals(0.8f, aCfg.lutStrength, 0.001f)
    assertEquals(0.0f, aCfg.tint, 0.001f)
    assertEquals(0.0f, aCfg.shadows, 0.001f)
    assertEquals(0.0f, aCfg.midtones, 0.001f)
    assertEquals(0.0f, aCfg.highlights, 0.001f)
    assertEquals(1.0f, aCfg.blendOpacity, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsAllParametricColorAndLiftGammaGainFields() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("t_vid", TrackType.VIDEO, 0, "Video"),
            Track("t_adj", TrackType.ADJUSTMENT, 1, "LUT"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v_graded",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "Graded Clip",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              videoConfig =
                VideoClipConfig(
                  brightness = -0.15f,
                  contrast = 1.28f,
                  saturation = 1.18f,
                  exposure = 0.35f,
                  temperature = -0.22f,
                  vignette = 0.4f,
                  lutPresetId = BuiltInLutPresets.FILM_2383.id,
                  lutStrength = 0.65f,
                  tint = 0.31f,
                  shadows = -0.25f,
                  midtones = 0.19f,
                  highlights = 0.42f,
                ),
            ),
            TimelineClip(
              clipId = "adj_graded",
              trackId = "t_adj",
              kind = ClipKind.ADJUSTMENT,
              label = "Master Look",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              adjustmentConfig =
                AdjustmentClipConfig(
                  brightness = 0.08f,
                  contrast = 1.12f,
                  saturation = 0.92f,
                  exposure = -0.18f,
                  temperature = 0.27f,
                  vignette = 0.15f,
                  lutPresetId = BuiltInLutPresets.GOLDEN_HOUR.id,
                  lutStrength = 0.85f,
                  tint = -0.44f,
                  shadows = 0.33f,
                  midtones = -0.21f,
                  highlights = 0.16f,
                  blendOpacity = 0.72f,
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_color_rt",
        name = "Color RoundTrip",
        createdAtIso = "2026-11-24T12:00:00Z",
        updatedAtIso = "2026-11-24T12:00:00Z",
        timeline = timeline,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val v = decoded.timeline.clips.first { it.clipId == "v_graded" }.videoConfig!!
    val a = decoded.timeline.clips.first { it.clipId == "adj_graded" }.adjustmentConfig!!

    assertEquals(-0.15f, v.brightness, 0.001f)
    assertEquals(1.28f, v.contrast, 0.001f)
    assertEquals(1.18f, v.saturation, 0.001f)
    assertEquals(0.35f, v.exposure, 0.001f)
    assertEquals(-0.22f, v.temperature, 0.001f)
    assertEquals(BuiltInLutPresets.FILM_2383.id, v.lutPresetId)
    assertEquals(0.65f, v.lutStrength, 0.001f)
    assertEquals(0.31f, v.tint, 0.001f)
    assertEquals(-0.25f, v.shadows, 0.001f)
    assertEquals(0.19f, v.midtones, 0.001f)
    assertEquals(0.42f, v.highlights, 0.001f)

    assertEquals(0.08f, a.brightness, 0.001f)
    assertEquals(-0.44f, a.tint, 0.001f)
    assertEquals(0.33f, a.shadows, 0.001f)
    assertEquals(-0.21f, a.midtones, 0.001f)
    assertEquals(0.16f, a.highlights, 0.001f)
    assertEquals(0.72f, a.blendOpacity, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsCustomLutsWheelsDepthBlurAndLegacyDefaults() {
    val serializer = ProjectJsonSerializer()
    val customLut =
      LutPreset(
        id = "custom_lut_retro",
        name = "Retro 70s",
        shortCode = "RT70",
        category = "Personal",
        contrast = 1.18f,
        shadowColorArgb = 0xFF221C18L,
        midtoneColorArgb = 0xFF806852L,
        highlightColorArgb = 0xFFEAD4B4L,
        whites = -0.15f,
        blacks = 0.22f,
        vibrance = 0.35f,
        liftX = 0.14f,
        liftY = -0.12f,
        gammaX = -0.08f,
        gammaY = 0.16f,
        gainX = 0.25f,
        gainY = 0.05f,
        isCustom = true,
      )
    val timeline =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "v_depth",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "Depth Clip",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              videoConfig =
                VideoClipConfig(
                  whites = 0.24f,
                  blacks = -0.18f,
                  vibrance = 0.42f,
                  liftX = 0.21f,
                  liftY = -0.19f,
                  gammaX = -0.11f,
                  gammaY = 0.13f,
                  gainX = 0.32f,
                  gainY = 0.09f,
                  lutPresetId = customLut.id,
                  depthBlur = 0.65f,
                  depthBlurRadius = 18.5f,
                  depthBlurFeather = 0.48f,
                  depthBlurMode = DepthBlurMaskMode.FOREGROUND_SUBJECT,
                  depthFocusX = 0.28f,
                  depthFocusY = 0.64f,
                  depthFocusRange = 0.36f,
                  keyframes =
                    listOf(
                      ClipKeyframe("k_rf1", offsetMs = 0L, depthBlur = 0.5f, depthFocusX = 0.25f, depthFocusY = 0.5f, depthFocusRange = 0.25f),
                      ClipKeyframe("k_rf2", offsetMs = 3_000L, depthBlur = 0.85f, depthFocusX = 0.75f, depthFocusY = 0.35f, depthFocusRange = 0.40f),
                    ),
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_depth_lut",
        name = "Depth & LUTs",
        createdAtIso = "2026-11-25T10:00:00Z",
        updatedAtIso = "2026-11-25T10:00:00Z",
        timeline = timeline,
        savedLutPresets = listOf(customLut),
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    assertEquals(1, decoded.savedLutPresets.size)
    assertEquals("Retro 70s", decoded.savedLutPresets.first().name)
    assertEquals(0.14f, decoded.savedLutPresets.first().liftX, 0.001f)
    assertTrue(decoded.savedLutPresets.first().isCustom)

    val cfg = decoded.timeline.clips.first().videoConfig!!
    assertEquals(0.24f, cfg.whites, 0.001f)
    assertEquals(-0.18f, cfg.blacks, 0.001f)
    assertEquals(0.42f, cfg.vibrance, 0.001f)
    assertEquals(0.21f, cfg.liftX, 0.001f)
    assertEquals(-0.11f, cfg.gammaX, 0.001f)
    assertEquals(0.32f, cfg.gainX, 0.001f)
    assertEquals(DepthBlurMaskMode.FOREGROUND_SUBJECT, cfg.depthBlurMode)
    assertEquals(18.5f, cfg.depthBlurRadius, 0.001f)
    assertEquals(0.48f, cfg.depthBlurFeather, 0.001f)
    assertEquals(0.28f, cfg.depthFocusX, 0.001f)
    assertEquals(0.64f, cfg.depthFocusY, 0.001f)
    assertEquals(0.36f, cfg.depthFocusRange, 0.001f)
    assertEquals(2, cfg.keyframes.size)
    assertEquals(0.75f, cfg.keyframes.last().depthFocusX ?: -1f, 0.001f)

    // Verify legacy JSON without savedLutPresets, wheels, or depth fields decodes cleanly
    val minimalLegacy =
      """
      {
        "schemaVersion": 1,
        "projectId": "min_old",
        "name": "Min Old",
        "createdAtIso": "2025-01-01T00:00:00Z",
        "updatedAtIso": "2025-01-01T00:00:00Z",
        "timeline": {
          "tracks": [{"trackId": "t_vid", "type": "VIDEO", "order": 0, "name": "Video"}],
          "clips": [
            {
              "clipId": "v1",
              "trackId": "t_vid",
              "kind": "VIDEO",
              "label": "V1",
              "timelineStartMs": 0,
              "timelineEndMs": 2000,
              "sourceInMs": 0,
              "sourceOutMs": 2000,
              "videoConfig": {"exposure": 0.15}
            }
          ]
        }
      }
      """.trimIndent()
    val decodedLegacy = serializer.decode(minimalLegacy).getOrThrow()
    assertTrue(decodedLegacy.savedLutPresets.isEmpty())
    val legacyCfg = decodedLegacy.timeline.clips.first().videoConfig!!
    assertEquals(0.0f, legacyCfg.whites, 0.001f)
    assertEquals(0.0f, legacyCfg.blacks, 0.001f)
    assertEquals(0.0f, legacyCfg.vibrance, 0.001f)
    assertEquals(0.0f, legacyCfg.liftX, 0.001f)
    assertEquals(0.0f, legacyCfg.depthBlur, 0.001f)
    assertEquals(ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS, legacyCfg.depthBlurRadius, 0.001f)
    assertEquals(DepthBlurMaskMode.RADIAL, legacyCfg.depthBlurMode)
    assertEquals(ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD, legacyCfg.depthFocusX, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsOverlayTracksOverlayKindsChromaticAberrationAndGlitch() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        playheadMs = 2_250L,
        tracks =
          listOf(
            Track("t_adj", TrackType.ADJUSTMENT, 0, "Ajuste"),
            Track("t_ovl", TrackType.VIDEO, 1, "Overlay O1", isOverlay = true),
            Track("t_vid", TrackType.VIDEO, 2, "Video V1", isOverlay = false),
            Track("t_aud", TrackType.AUDIO, 3, "Audio A1"),
            Track("t_aud2", TrackType.AUDIO, 4, "Audio A2"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_ovl_stk",
              trackId = "t_ovl",
              kind = ClipKind.VIDEO,
              label = "FrameLine Logo",
              timelineStartMs = 1_000L,
              timelineEndMs = 4_500L,
              sourceInMs = 0L,
              sourceOutMs = 3_500L,
              videoConfig =
                VideoClipConfig(
                  scale = 0.48f,
                  offsetX = 0.25f,
                  offsetY = -0.30f,
                  overlayKind = com.example.core.model.OverlayContentKind.LOGO,
                  stickerId = "frameline_logo",
                  chromaticAberration = 0.22f,
                  glitchIntensity = 0.14f,
                ),
            ),
            TimelineClip(
              clipId = "c_fx_glitch",
              trackId = "t_adj",
              kind = ClipKind.ADJUSTMENT,
              label = "Glitch & RGB",
              timelineStartMs = 500L,
              timelineEndMs = 3_800L,
              adjustmentConfig =
                AdjustmentClipConfig(
                  chromaticAberration = 0.65f,
                  glitchIntensity = 0.72f,
                  vignette = 0.40f,
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_overlay_fx",
        name = "Overlay & FX RoundTrip",
        createdAtIso = "2026-11-25T09:00:00Z",
        updatedAtIso = "2026-11-25T09:00:00Z",
        timeline = timeline,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val decodedOverlayTrack = decoded.timeline.tracks.first { it.trackId == "t_ovl" }
    val decodedMainVideoTrack = decoded.timeline.tracks.first { it.trackId == "t_vid" }
    val decodedOverlayClip = decoded.timeline.clips.first { it.clipId == "c_ovl_stk" }
    val decodedFxClip = decoded.timeline.clips.first { it.clipId == "c_fx_glitch" }

    assertEquals(5, decoded.timeline.tracks.size)
    assertTrue(decodedOverlayTrack.isOverlay)
    assertTrue(!decodedMainVideoTrack.isOverlay)

    assertEquals(
      com.example.core.model.OverlayContentKind.LOGO,
      decodedOverlayClip.videoConfig?.overlayKind,
    )
    assertEquals("frameline_logo", decodedOverlayClip.videoConfig?.stickerId)
    assertEquals(0.48f, decodedOverlayClip.videoConfig?.scale ?: 0f, 0.001f)
    assertEquals(0.25f, decodedOverlayClip.videoConfig?.offsetX ?: 0f, 0.001f)
    assertEquals(-0.30f, decodedOverlayClip.videoConfig?.offsetY ?: 0f, 0.001f)
    assertEquals(0.22f, decodedOverlayClip.videoConfig?.chromaticAberration ?: 0f, 0.001f)
    assertEquals(0.14f, decodedOverlayClip.videoConfig?.glitchIntensity ?: 0f, 0.001f)

    assertEquals(0.65f, decodedFxClip.adjustmentConfig?.chromaticAberration ?: 0f, 0.001f)
    assertEquals(0.72f, decodedFxClip.adjustmentConfig?.glitchIntensity ?: 0f, 0.001f)
    assertEquals(0.40f, decodedFxClip.adjustmentConfig?.vignette ?: 0f, 0.001f)
  }
}
