package com.example.core.media

import androidx.media3.effect.OverlayEffect
import com.example.core.media.engine.CompiledCompositionResult
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.media.model.PreviewQualityProfile
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class CompositionCompilerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun compile_usesProxyOnlyForPreviewAndAlwaysOriginalForExport() {
    val rootDir = tempFolder.newFolder("portable_proj")
    val mediaFile = File(rootDir, "media/clip1.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val proxyFile = File(rootDir, "proxies/clip1_proxy.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2))
    }

    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip1.mp4",
        relativePath = "media/${mediaFile.name}",
        proxyRelativePath = "proxies/${proxyFile.name}",
        fingerprint = MediaFingerprint(4L, 3_000L, "abc", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "clip1",
              timelineStartMs = 0L,
              timelineEndMs = 3_000L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
              assetId = "a1",
              isEnabled = true,
            ),
            TimelineClip(
              clipId = "c_off",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "disabled",
              timelineStartMs = 3_000L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1",
              isEnabled = false,
            ),
          ),
      )

    val compiler = CompositionCompiler()

    val previewResult =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget =
          MediaRenderTarget.Preview(
            profile = PreviewQualityProfile.DRAFT_360P,
            useProxies = true,
          ),
      ) as CompiledCompositionResult.Ready

    assertEquals(1, previewResult.totalVideoItems)
    assertEquals(1, previewResult.usedProxyCount)

    val exportResult =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    assertEquals(1, exportResult.totalVideoItems)
    assertEquals(0, exportResult.usedProxyCount)
    assertTrue(exportResult.composition.sequences.isNotEmpty())
  }

  @Test
  fun compile_appliesClippingConfigurationKeyframesAndAdjustmentEffects() {
    val rootDir = tempFolder.newFolder("export_fx_proj")
    val mediaFile = File(rootDir, "media/clip_trimmed.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    }

    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip_trimmed.mp4",
        relativePath = "media/${mediaFile.name}",
        fingerprint = MediaFingerprint(5L, 8_000L, "def", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video"),
            Track("adj1", TrackType.ADJUSTMENT, 1, "Color"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_trim",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "trimmed",
              timelineStartMs = 0L,
              timelineEndMs = 3_500L,
              sourceInMs = 1_200L,
              sourceOutMs = 4_700L,
              assetId = "a1",
              videoConfig =
                VideoClipConfig(
                  scale = 1.2f,
                  offsetX = 0.1f,
                  opacity = 0.85f,
                  keyframes =
                    listOf(
                      ClipKeyframe("kf1", offsetMs = 0L, scale = 1.0f, opacity = 0.8f),
                      ClipKeyframe("kf2", offsetMs = 2_000L, scale = 1.25f, offsetX = 0.15f, opacity = 0.9f),
                    ),
                ),
            ),
            TimelineClip(
              clipId = "c_adj",
              trackId = "adj1",
              kind = ClipKind.ADJUSTMENT,
              label = "IA Color",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              adjustmentConfig =
                AdjustmentClipConfig(
                  brightness = 0.12f,
                  contrast = 1.15f,
                  saturation = 1.20f,
                  temperature = 0.25f,
                ),
            ),
          ),
      )

    val exportResult =
      CompositionCompiler().compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    val videoItem = exportResult.composition.sequences.first().editedMediaItems.first()
    assertEquals(1_200L, videoItem.mediaItem.clippingConfiguration.startPositionMs)
    assertEquals(4_700L, videoItem.mediaItem.clippingConfiguration.endPositionMs)
    // MatrixTransformation + RgbAdjustment(opacity) + Brightness + Contrast + HslAdjustment + RgbAdjustment(temp) = 6
    assertEquals(6, exportResult.appliedVideoEffectCount)
  }

  @Test
  fun compile_includesSubtitleOverlayEffectAndSplitsPartialAdjustments() {
    val rootDir = tempFolder.newFolder("sub_adj_proj")
    val mediaFile = File(rootDir, "media/clip.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip.mp4",
        relativePath = "media/${mediaFile.name}",
        fingerprint = MediaFingerprint(4L, 6_000L, "ghi", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("adj", TrackType.ADJUSTMENT, 0, "Color"),
            Track("sub", TrackType.SUBTITLE, 1, "Subs"),
            Track("v1", TrackType.VIDEO, 2, "Video"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Clip",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              assetId = "a1",
            ),
            TimelineClip(
              clipId = "adj_mid",
              trackId = "adj",
              kind = ClipKind.ADJUSTMENT,
              label = "Mid Color",
              timelineStartMs = 1_000L,
              timelineEndMs = 3_500L,
              adjustmentConfig = AdjustmentClipConfig(contrast = 1.2f),
            ),
            TimelineClip(
              clipId = "sub_1",
              trackId = "sub",
              kind = ClipKind.SUBTITLE,
              label = "Title",
              timelineStartMs = 500L,
              timelineEndMs = 3_000L,
              subtitleConfig = SubtitleClipConfig(text = "Hola FrameLine"),
            ),
          ),
      )

    val exportResult =
      CompositionCompiler().compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    // Video split into 3 segments: [0..1000], [1000..3500], [3500..5000]
    assertEquals(3, exportResult.totalVideoItems)
    assertNotNull(exportResult.renderPlan)
    assertEquals(1, exportResult.renderPlan!!.subtitleOverlays.size)
    assertTrue(exportResult.composition.effects.videoEffects.any { it is OverlayEffect })
  }
}
