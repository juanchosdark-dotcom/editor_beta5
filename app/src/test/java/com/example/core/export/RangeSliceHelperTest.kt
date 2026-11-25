package com.example.core.export

import com.example.core.model.AudioClipConfig
import com.example.core.model.ClipKind
import com.example.core.model.ClipTransition
import com.example.core.model.MarkerColor
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.TransitionType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSliceHelperTest {

  @Test
  fun sliceTimelineForRange_clipsEnabledMediaAndAdjustsSourceBoundsBySpeed() {
    val timeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_active",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Clip A",
              timelineStartMs = 1_000L,
              timelineEndMs = 6_000L,
              sourceInMs = 500L,
              sourceOutMs = 10_500L,
              speed = 2.0f,
              isEnabled = true,
            ),
            TimelineClip(
              clipId = "c_disabled",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Disabled",
              timelineStartMs = 1_000L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              isEnabled = false,
            ),
          ),
        markers = listOf(TimelineMarker("m1", 3_000L, "Mid", MarkerColor.AMBER)),
      )

    val sliced = RangeSliceHelper.sliceTimelineForRange(timeline, 2_000L, 4_500L)
    assertEquals(1, sliced.clips.size)

    val clip = sliced.clips.first()
    // Range [2000..4500] normalized to [0..2500]
    assertEquals(0L, clip.timelineStartMs)
    assertEquals(2_500L, clip.timelineEndMs)
    // Trimmed 1000ms left at speed 2.0 -> sourceIn = 500 + 2000 = 2500ms
    // Trimmed 1500ms right at speed 2.0 -> sourceOut = 10500 - 3000 = 7500ms
    assertEquals(2_500L, clip.sourceInMs)
    assertEquals(7_500L, clip.sourceOutMs)

    // Marker at 3000ms shifts to 1000ms
    assertEquals(1_000L, sliced.markers.first().positionMs)
  }

  @Test
  fun sliceTimelineForRange_trimsFadesAndTransitionsOnSlicedBoundaries() {
    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video"),
            Track("a1", TrackType.AUDIO, 1, "Audio"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v_fade",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "V",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              videoConfig =
                VideoClipConfig(
                  fadeInMs = 800L,
                  fadeOutMs = 900L,
                  transitionIn = ClipTransition(TransitionType.FADE_BLACK, 500L),
                  transitionOut = ClipTransition(TransitionType.CROSS_DISSOLVE, 500L),
                ),
            ),
            TimelineClip(
              clipId = "a_fade",
              trackId = "a1",
              kind = ClipKind.AUDIO,
              label = "A",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              audioConfig = AudioClipConfig(fadeInMs = 1_200L, fadeOutMs = 600L),
            ),
          ),
      )

    // Mid-slice [1000..4400]: trims 1000ms left and 600ms right
    val midSliced = RangeSliceHelper.sliceTimelineForRange(timeline, 1_000L, 4_400L)
    val vMid = midSliced.clips.first { it.clipId == "v_fade" }.videoConfig!!
    val aMid = midSliced.clips.first { it.clipId == "a_fade" }.audioConfig!!
    assertEquals(0L, vMid.fadeInMs)
    assertEquals(300L, vMid.fadeOutMs)
    assertNull(vMid.transitionIn)
    assertNull(vMid.transitionOut)
    assertEquals(200L, aMid.fadeInMs)
    assertEquals(0L, aMid.fadeOutMs)
  }

  @Test
  fun buildMarkerChapterSpans_createsConsecutiveSegmentsAcrossMarkers() {
    val markers =
      listOf(
        TimelineMarker("m2", 5_000L, "Escena 2", MarkerColor.CYAN),
        TimelineMarker("m1", 2_500L, "Intro", MarkerColor.AMBER),
      )

    val spans = RangeSliceHelper.buildMarkerChapterSpans(markers, totalDurationMs = 9_500L)
    assertEquals(3, spans.size)
    assertEquals(0L to 2_500L, spans[0].startMs to spans[0].endMs)
    assertEquals(2_500L to 5_000L, spans[1].startMs to spans[1].endMs)
    assertEquals(5_000L to 9_500L, spans[2].startMs to spans[2].endMs)
    assertTrue(spans[1].label.contains("Intro"))
    assertEquals(9_500L, RangeSliceHelper.calculateTotalBatchDurationMs(spans, totalDurationMs = 9_500L))
  }

  @Test
  fun resolveExportRangeBoundsAndMarkerSelection_clampsAndComputesExactDurations() {
    val markers =
      listOf(
        TimelineMarker("m1", 2_500L, "Intro", MarkerColor.AMBER),
        TimelineMarker("m2", 5_000L, "Corte", MarkerColor.CYAN),
      )

    assertEquals(9_500L, RangeSliceHelper.calculateExportDurationMs(ExportRange.FullTimeline, 9_500L))
    assertEquals(
      3_000L,
      RangeSliceHelper.calculateExportDurationMs(ExportRange.TimeRange(2_000L, 5_000L), 9_500L),
    )
    // Out-of-bounds clamping
    val clamped = RangeSliceHelper.resolveExportRangeBounds(ExportRange.TimeRange(4_000L, 14_000L), 9_500L)
    assertEquals(ExportRangeBounds(4_000L, 9_500L), clamped)
    assertEquals(5_500L, clamped.durationMs)

    // Origin -> Marker 1
    assertEquals(
      ExportRangeBounds(0L, 2_500L),
      RangeSliceHelper.resolveMarkerSelectionBounds(markers, startBoundaryIndex = -1, endBoundaryIndex = 0, totalDurationMs = 9_500L),
    )
    // Marker 1 -> Marker 2
    assertEquals(
      ExportRangeBounds(2_500L, 5_000L),
      RangeSliceHelper.resolveMarkerSelectionBounds(markers, startBoundaryIndex = 0, endBoundaryIndex = 1, totalDurationMs = 9_500L),
    )
    // Marker 2 -> End
    assertEquals(
      ExportRangeBounds(5_000L, 9_500L),
      RangeSliceHelper.resolveMarkerSelectionBounds(markers, startBoundaryIndex = 1, endBoundaryIndex = 2, totalDurationMs = 9_500L),
    )
  }
}
