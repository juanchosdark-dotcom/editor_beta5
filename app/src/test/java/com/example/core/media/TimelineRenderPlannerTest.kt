package com.example.core.media

import com.example.core.media.engine.PlannedMediaSource
import com.example.core.media.engine.SkippedSegmentReason
import com.example.core.media.engine.TimelineRenderPlanner
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.SubtitleAlignment
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRenderPlannerTest {

  private fun resolverFor(assets: List<MediaAsset>): (MediaAsset) -> PlannedMediaSource? {
    val ids = assets.map { it.assetId }.toSet()
    return { asset ->
      if (asset.assetId in ids) {
        PlannedMediaSource(externalUri = "file:///fake/${asset.relativePath}", isProxy = false)
      } else {
        null
      }
    }
  }

  @Test
  fun buildPlan_splitsVideoAtPartialAdjustmentsMergesStackedLayersAndSlicesKeyframes() {
    val asset =
      MediaAsset(
        assetId = "v_asset",
        type = MediaType.VIDEO,
        displayName = "scene.mp4",
        relativePath = "media/scene.mp4",
        fingerprint = MediaFingerprint(1_024L, 10_000L, "h1", "video/mp4", 1920, 1080),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("adj_top", TrackType.ADJUSTMENT, 0, "LUT Top"),
            Track("adj_base", TrackType.ADJUSTMENT, 1, "Color Base"),
            Track("v1", TrackType.VIDEO, 2, "Video V1"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "clip_v1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Scene",
              timelineStartMs = 0L,
              timelineEndMs = 6_000L,
              sourceInMs = 1_000L,
              sourceOutMs = 7_000L,
              assetId = "v_asset",
              videoConfig =
                VideoClipConfig(
                  fadeInMs = 500L,
                  fadeOutMs = 500L,
                  keyframes =
                    listOf(
                      ClipKeyframe("k0", offsetMs = 0L, scale = 1.0f),
                      ClipKeyframe("k1", offsetMs = 6_000L, scale = 1.6f),
                    ),
                ),
            ),
            // Partial adjustment covering [1500..4500]
            TimelineClip(
              clipId = "adj_1",
              trackId = "adj_base",
              kind = ClipKind.ADJUSTMENT,
              label = "Warmth",
              timelineStartMs = 1_500L,
              timelineEndMs = 4_500L,
              adjustmentConfig = AdjustmentClipConfig(brightness = 0.1f, contrast = 1.2f, blendOpacity = 1.0f),
            ),
            // Stacked partial adjustment covering [2500..4500]
            TimelineClip(
              clipId = "adj_2",
              trackId = "adj_top",
              kind = ClipKind.ADJUSTMENT,
              label = "Saturation Boost",
              timelineStartMs = 2_500L,
              timelineEndMs = 4_500L,
              adjustmentConfig = AdjustmentClipConfig(brightness = 0.05f, saturation = 1.4f, blendOpacity = 0.5f),
            ),
          ),
      )

    val plan = TimelineRenderPlanner.buildPlan(timeline, listOf(asset), resolverFor(listOf(asset)))

    // Video clip splits into 4 contiguous segments: [0..1500], [1500..2500], [2500..4500], [4500..6000]
    assertEquals(4, plan.primaryVideoSegments.size)

    val seg0 = plan.primaryVideoSegments[0]
    val seg1 = plan.primaryVideoSegments[1]
    val seg2 = plan.primaryVideoSegments[2]
    val seg3 = plan.primaryVideoSegments[3]

    assertEquals(0L to 1_500L, seg0.targetTimelineStartMs to seg0.targetTimelineEndMs)
    assertEquals(1_000L to 2_500L, seg0.sourceInMs to seg0.sourceOutMs)
    assertNull(seg0.effectiveAdjustment)
    assertEquals(500L, seg0.clip.videoConfig?.fadeInMs)
    assertEquals(0L, seg0.clip.videoConfig?.fadeOutMs)

    assertEquals(1_500L to 2_500L, seg1.targetTimelineStartMs to seg1.targetTimelineEndMs)
    assertEquals(2_500L to 3_500L, seg1.sourceInMs to seg1.sourceOutMs)
    assertNotNull(seg1.effectiveAdjustment)
    val seg1Adj = seg1.effectiveAdjustment!!
    assertEquals(0.1f, seg1Adj.brightness, 0.001f)
    assertEquals(1.2f, seg1Adj.contrast, 0.001f)

    // Stacked segment [2500..4500] merges adj_2 (blendOpacity 0.5 -> +0.025 brightness, 1.2 saturation) + adj_1
    assertEquals(2_500L to 4_500L, seg2.targetTimelineStartMs to seg2.targetTimelineEndMs)
    assertNotNull(seg2.effectiveAdjustment)
    val seg2Adj = seg2.effectiveAdjustment!!
    assertEquals(0.125f, seg2Adj.brightness, 0.001f)
    assertEquals(1.2f, seg2Adj.saturation, 0.001f)

    assertEquals(4_500L to 6_000L, seg3.targetTimelineStartMs to seg3.targetTimelineEndMs)
    assertNull(seg3.effectiveAdjustment)
    assertEquals(0L, seg3.clip.videoConfig?.fadeInMs)
    assertEquals(500L, seg3.clip.videoConfig?.fadeOutMs)

    // Keyframe scale stays continuous across adjustment split boundary at 1500ms (1.0 -> 1.6 => 1.15 at 1500ms)
    val seg0EndScale = seg0.clip.evaluatePropertiesAt(seg0.clip.durationMs).scale
    val seg1StartScale = seg1.clip.evaluatePropertiesAt(0L).scale
    assertEquals(1.15f, seg0EndScale, 0.01f)
    assertEquals(seg0EndScale, seg1StartScale, 0.01f)
  }

  @Test
  fun buildPlan_clampsSourceEofWithSpeedAndCompensatesSubsequentLeadingGapForAvSync() {
    // Source media only has 3000ms available even though clip slot expects 5000ms @ 1.0x (or 2500ms @ 2.0x -> clipped!)
    val shortVideo =
      MediaAsset(
        assetId = "v_short",
        type = MediaType.VIDEO,
        displayName = "short.mp4",
        relativePath = "media/short.mp4",
        fingerprint = MediaFingerprint(512L, 3_000L, "h_short", "video/mp4"),
      )
    val nextVideo =
      MediaAsset(
        assetId = "v_next",
        type = MediaType.VIDEO,
        displayName = "next.mp4",
        relativePath = "media/next.mp4",
        fingerprint = MediaFingerprint(512L, 10_000L, "h_next", "video/mp4"),
      )
    val audioAsset =
      MediaAsset(
        assetId = "a_music",
        type = MediaType.AUDIO,
        displayName = "music.m4a",
        relativePath = "media/music.m4a",
        fingerprint = MediaFingerprint(512L, 12_000L, "h_aud", "audio/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video"),
            Track("a1", TrackType.AUDIO, 1, "Audio"),
          ),
        clips =
          listOf(
            // Speed 2.0x across 0..2500ms would want 5000ms of source, but v_short only has 3000ms -> renders 1500ms!
            TimelineClip(
              clipId = "c_fast",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Fast",
              timelineStartMs = 0L,
              timelineEndMs = 2_500L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              speed = 2.0f,
              assetId = "v_short",
            ),
            // Next chained cut starts at 2500ms
            TimelineClip(
              clipId = "c_second",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Second",
              timelineStartMs = 2_500L,
              timelineEndMs = 5_500L,
              sourceInMs = 1_000L,
              sourceOutMs = 4_000L,
              speed = 1.0f,
              assetId = "v_next",
            ),
            // Out-of-bounds clip whose sourceIn exceeds asset duration
            TimelineClip(
              clipId = "c_oob",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "OOB",
              timelineStartMs = 5_500L,
              timelineEndMs = 7_500L,
              sourceInMs = 4_000L,
              sourceOutMs = 6_000L,
              assetId = "v_short",
            ),
            TimelineClip(
              clipId = "c_aud",
              trackId = "a1",
              kind = ClipKind.AUDIO,
              label = "Music",
              timelineStartMs = 2_500L,
              timelineEndMs = 5_500L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
              assetId = "a_music",
              audioConfig = AudioClipConfig(volume = 0.9f),
            ),
          ),
      )

    val plan =
      TimelineRenderPlanner.buildPlan(
        timeline,
        listOf(shortVideo, nextVideo, audioAsset),
        resolverFor(listOf(shortVideo, nextVideo, audioAsset)),
      )

    assertEquals(2, plan.primaryVideoSegments.size)
    // First segment clamped to 3000ms source @ 2.0x = 1500ms rendered
    val first = plan.primaryVideoSegments[0]
    assertEquals(0L, first.sourceInMs)
    assertEquals(3_000L, first.sourceOutMs)
    assertEquals(1_500L, first.expectedRenderedDurationMs)

    // Second segment starts at 2500ms on timeline -> receives 1000ms compensating gap so it stays synced with Audio A1!
    val second = plan.primaryVideoSegments[1]
    assertEquals(1_000L, second.leadingGapMs)
    assertEquals(2_500L, second.targetTimelineStartMs)
    assertEquals(5_500L, second.targetTimelineEndMs)

    // Audio A1 starts cleanly at 2500ms
    assertEquals(1, plan.audioTracks.size)
    assertEquals(2_500L, plan.audioTracks.first().segments.first().leadingGapMs)
    assertEquals(5_500L, plan.totalPlannedDurationMs)

    // OOB clip recorded cleanly in skippedClips instead of crashing ClippingConfiguration
    assertTrue(
      plan.skippedClips.any {
        it.clipId == "c_oob" && it.reason == SkippedSegmentReason.SOURCE_OUT_OF_BOUNDS
      },
    )
  }

  @Test
  fun buildPlan_trimsSameTrackOverlapsCoalescesMicroSegmentsAndFiltersSubtitlesAndAudioTracks() {
    val vAsset =
      MediaAsset(
        assetId = "v1_asset",
        type = MediaType.VIDEO,
        displayName = "clip.mp4",
        relativePath = "media/clip.mp4",
        fingerprint = MediaFingerprint(512L, 10_000L, "hv", "video/mp4"),
      )
    val aAsset =
      MediaAsset(
        assetId = "a1_asset",
        type = MediaType.AUDIO,
        displayName = "vo.m4a",
        relativePath = "media/vo.m4a",
        fingerprint = MediaFingerprint(512L, 10_000L, "ha", "audio/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("adj", TrackType.ADJUSTMENT, 0, "Adj"),
            Track("sub_vis", TrackType.SUBTITLE, 1, "Sub Visible", isVisible = true),
            Track("sub_hid", TrackType.SUBTITLE, 2, "Sub Hidden", isVisible = false),
            Track("v1", TrackType.VIDEO, 3, "V1"),
            Track("a_vo", TrackType.AUDIO, 4, "VO"),
            Track("a_muted", TrackType.AUDIO, 5, "Muted", isMuted = true),
          ),
        duckingConfig = AudioDuckingConfig(enabled = true, triggerTrackId = "a_vo", targetTrackId = "v1", attenuationFactor = 0.25f),
        clips =
          listOf(
            // Overlapping video clips: c1 [0..2000], c2 [1500..4000] -> c2 must be left-trimmed to [2000..4000]
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 2_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "v1_asset",
            ),
            TimelineClip(
              clipId = "c2",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "V2",
              timelineStartMs = 1_500L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_500L,
              assetId = "v1_asset",
            ),
            // Tiny 15ms adjustment sliver near the end [3985..4000] must NOT produce a <40ms MediaCodec segment
            TimelineClip(
              clipId = "adj_tiny",
              trackId = "adj",
              kind = ClipKind.ADJUSTMENT,
              label = "Tiny",
              timelineStartMs = 3_985L,
              timelineEndMs = 4_000L,
              adjustmentConfig = AdjustmentClipConfig(contrast = 1.2f),
            ),
            // Subtitles: one valid, one blank, one on hidden track
            TimelineClip(
              clipId = "sub_ok",
              trackId = "sub_vis",
              kind = ClipKind.SUBTITLE,
              label = "OK",
              timelineStartMs = 500L,
              timelineEndMs = 2_500L,
              subtitleConfig = SubtitleClipConfig(text = "Hola", alignment = SubtitleAlignment.BOTTOM_CENTER),
            ),
            TimelineClip(
              clipId = "sub_blank",
              trackId = "sub_vis",
              kind = ClipKind.SUBTITLE,
              label = "Blank",
              timelineStartMs = 2_600L,
              timelineEndMs = 3_500L,
              subtitleConfig = SubtitleClipConfig(text = "   "),
            ),
            TimelineClip(
              clipId = "sub_hidden",
              trackId = "sub_hid",
              kind = ClipKind.SUBTITLE,
              label = "Hidden",
              timelineStartMs = 500L,
              timelineEndMs = 1_500L,
              subtitleConfig = SubtitleClipConfig(text = "No mostrar"),
            ),
            // Audible VO + muted track
            TimelineClip(
              clipId = "vo_1",
              trackId = "a_vo",
              kind = ClipKind.AUDIO,
              label = "VO",
              timelineStartMs = 1_000L,
              timelineEndMs = 3_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1_asset",
            ),
            TimelineClip(
              clipId = "muted_1",
              trackId = "a_muted",
              kind = ClipKind.AUDIO,
              label = "Muted",
              timelineStartMs = 0L,
              timelineEndMs = 2_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1_asset",
            ),
          ),
      )

    val plan =
      TimelineRenderPlanner.buildPlan(
        timeline,
        listOf(vAsset, aAsset),
        resolverFor(listOf(vAsset, aAsset)),
      )

    // No video segment may be shorter than MIN_RENDER_SEGMENT_MS (40ms)
    assertTrue(plan.primaryVideoSegments.all { it.expectedRenderedDurationMs >= TimelineRenderPlanner.MIN_RENDER_SEGMENT_MS })
    // c2 left-trimmed from 1500..4000 -> 2000..4000 with sourceIn shifted by 500ms
    val c2Seg = plan.primaryVideoSegments.first { it.sourceClipId == "c2" }
    assertEquals(2_000L, c2Seg.targetTimelineStartMs)
    assertEquals(4_000L, c2Seg.targetTimelineEndMs)
    assertEquals(500L, c2Seg.sourceInMs)
    assertEquals(2_500L, c2Seg.sourceOutMs)
    assertEquals(0L, c2Seg.leadingGapMs)
    assertTrue(c2Seg.duckingWindows.isNotEmpty())

    // Only visible non-blank subtitle included
    assertEquals(1, plan.subtitleOverlays.size)
    assertEquals("sub_ok", plan.subtitleOverlays.first().clipId)

    // Only audible audio track included
    assertEquals(1, plan.audioTracks.size)
    assertEquals("a_vo", plan.audioTracks.first().trackId)
  }
}
