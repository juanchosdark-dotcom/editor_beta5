package com.example.core.media.engine

import com.example.core.media.audio.AudioTrackMixer
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.ClipKind
import com.example.core.model.ColorGradeBounds
import com.example.core.model.ColorGradingEngine
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.DuckingWindow
import com.example.core.model.LutPreset
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import com.example.core.model.OverlayContentKind
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

enum class SkippedSegmentReason {
  MISSING_ASSET,
  UNAVAILABLE_SOURCE,
  OVERLAPPED_BY_PREVIOUS,
  SOURCE_OUT_OF_BOUNDS,
  BELOW_MIN_DURATION,
  EMPTY_SUBTITLE,
}

data class SkippedRenderClip(
  val clipId: String,
  val kind: ClipKind,
  val reason: SkippedSegmentReason,
)

data class PlannedMediaSource(
  val sourceFile: File? = null,
  val externalUri: String? = null,
  val isProxy: Boolean = false,
) {
  val uriString: String?
    get() = sourceFile?.toURI()?.toString() ?: externalUri
}

/**
 * Planned primary-track video/image segment with reconciled timeline offsets, source bounds,
 * effective speed, pre-split adjustment layers, and continuous audio-envelope anchor metadata.
 */
data class PlannedVideoSegment(
  val sourceClipId: String,
  val segmentId: String,
  val clip: TimelineClip,
  val asset: MediaAsset,
  val mediaSource: PlannedMediaSource,
  val leadingGapMs: Long,
  val targetTimelineStartMs: Long,
  val targetTimelineEndMs: Long,
  val expectedRenderedDurationMs: Long,
  val sourceInMs: Long,
  val sourceOutMs: Long,
  val effectiveSpeed: Float,
  val isImage: Boolean,
  val isTrackAudible: Boolean,
  val trackVolume: Float,
  val duckingWindows: List<DuckingWindow>,
  val effectiveAdjustment: AdjustmentClipConfig?,
  val envelopeAnchorShiftMs: Long = 0L,
  val envelopeParentDurationMs: Long = clip.durationMs,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
  val resolvedDepthBlur: Float = 0.0f,
  val audioFadeInMs: Long = clip.videoConfig?.fadeInMs ?: 0L,
  val audioFadeOutMs: Long = clip.videoConfig?.fadeOutMs ?: 0L,
  val preserveAudioPitch: Boolean = clip.videoConfig?.preserveAudioPitch ?: true,
)

data class PlannedAudioSegment(
  val sourceClipId: String,
  val segmentId: String,
  val clip: TimelineClip,
  val asset: MediaAsset,
  val mediaSource: PlannedMediaSource,
  val leadingGapMs: Long,
  val targetTimelineStartMs: Long,
  val targetTimelineEndMs: Long,
  val expectedRenderedDurationMs: Long,
  val sourceInMs: Long,
  val sourceOutMs: Long,
  val effectiveSpeed: Float,
  val trackVolume: Float,
  val duckingWindows: List<DuckingWindow>,
  val envelopeAnchorShiftMs: Long = 0L,
  val envelopeParentDurationMs: Long = clip.durationMs,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
  val preserveAudioPitch: Boolean = clip.audioConfig?.preserveAudioPitch ?: true,
)

data class PlannedAudioTrackSequence(
  val trackId: String,
  val trackName: String,
  val trackOrder: Int,
  val segments: List<PlannedAudioSegment>,
  val totalPlannedDurationMs: Long,
)

data class PlannedSubtitleOverlay(
  val clipId: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val config: SubtitleClipConfig,
)

data class PlannedOverlaySegment(
  val clip: TimelineClip,
  val trackId: String,
  val trackOrder: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val asset: MediaAsset?,
  val mediaSource: PlannedMediaSource?,
  val overlayKind: OverlayContentKind,
  val stickerId: String?,
)

/**
 * Deterministic, explicit render plan constructed before instantiating Media3 encoders.
 * Ensures chained cuts, speed/duration edits, overlapping clips, adjustment boundaries,
 * subtitles, overlays, and auxiliary audio tracks stay gap-aligned without A/V drift or codec crashes.
 */
data class TimelineRenderPlan(
  val primaryVideoSegments: List<PlannedVideoSegment>,
  val audioTracks: List<PlannedAudioTrackSequence>,
  val subtitleOverlays: List<PlannedSubtitleOverlay>,
  val totalPlannedDurationMs: Long,
  val usedProxyCount: Int,
  val skippedClips: List<SkippedRenderClip> = emptyList(),
  val overlaySegments: List<PlannedOverlaySegment> = emptyList(),
) {
  val isEmpty: Boolean
    get() =
      primaryVideoSegments.isEmpty() &&
        audioTracks.all { it.segments.isEmpty() } &&
        overlaySegments.isEmpty()

  val totalAudioSegmentCount: Int
    get() = audioTracks.sumOf { it.segments.size }
}

object TimelineRenderPlanner {

  const val MIN_RENDER_SEGMENT_MS = 40L
  const val MIN_SPEED = 0.25f
  const val MAX_SPEED = 4.0f

  fun buildPlan(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    useProxies: Boolean,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): TimelineRenderPlan =
    buildPlan(
      timeline = timeline,
      assets = assets,
      sourceResolver = { asset -> resolveMediaSource(asset, projectRootDir, useProxies) },
      customLutPresets = customLutPresets,
    )

  /**
   * Pure planning entrypoint accepting a custom [sourceResolver] so JVM unit tests can verify
   * complex timeline plans without requiring real files on disk or Android [android.net.Uri].
   */
  fun buildPlan(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    sourceResolver: (MediaAsset) -> PlannedMediaSource?,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): TimelineRenderPlan {
    val assetById = assets.associateBy { it.assetId }
    val trackById = timeline.tracks.associateBy { it.trackId }
    val trackAudibility = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
    val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)
    val skipped = mutableListOf<SkippedRenderClip>()
    var usedProxyCount = 0

    // Adjustment layers ordered deterministically by track order -> start time -> clipId
    val activeAdjustments =
      timeline.clips
        .filter { clip ->
          clip.isEnabled &&
            clip.kind == ClipKind.ADJUSTMENT &&
            trackById[clip.trackId]?.isVisible != false &&
            clip.adjustmentConfig?.let { !isNeutralAdjustment(it, customLutPresets) } == true
        }
        .sortedWith(
          compareBy<TimelineClip>(
            { trackById[it.trackId]?.order ?: Int.MAX_VALUE },
            { it.timelineStartMs },
            { it.clipId },
          ),
        )

    // 1. Plan primary visible video track (non-overlay first)
    val videoTrack =
      timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && !it.isOverlay && it.isVisible }
        ?: timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && it.isVisible }
    val rawVideoClips =
      videoTrack?.let { track ->
        timeline.clipsForTrack(track.trackId)
          .filter { it.isEnabled && it.kind == ClipKind.VIDEO }
          .sortedWith(compareBy<TimelineClip>({ it.timelineStartMs }, { -it.timelineEndMs }, { it.clipId }))
      }.orEmpty()

    val videoSegments = mutableListOf<PlannedVideoSegment>()
    var videoCursorMs = 0L

    for (rawClip in rawVideoClips) {
      val asset = rawClip.assetId?.let { assetById[it] }
      if (asset == null) {
        skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.MISSING_ASSET)
        continue
      }
      val resolvedSource = sourceResolver(asset)
      if (resolvedSource == null) {
        skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.UNAVAILABLE_SOURCE)
        continue
      }

      // Trim any left overlap against previously scheduled video segments on the same track
      val nonOverlappingClip =
        if (rawClip.timelineStartMs < videoCursorMs) {
          if (rawClip.timelineEndMs <= videoCursorMs + MIN_RENDER_SEGMENT_MS) {
            skipped +=
              SkippedRenderClip(
                rawClip.clipId,
                rawClip.kind,
                SkippedSegmentReason.OVERLAPPED_BY_PREVIOUS,
              )
            continue
          }
          sliceVideoClipSubRange(
            clip = rawClip,
            subStartMs = videoCursorMs,
            subEndMs = rawClip.timelineEndMs,
            isImage = asset.type == MediaType.IMAGE,
            suffix = "trim",
          )
        } else {
          rawClip
        }

      if (nonOverlappingClip.durationMs < MIN_RENDER_SEGMENT_MS) {
        skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.BELOW_MIN_DURATION)
        continue
      }

      val splitWindows = splitClipByAdjustmentBoundaries(nonOverlappingClip, activeAdjustments)
      var emittedForClip = false

      for ((subIndex, window) in splitWindows.withIndex()) {
        val (subStartMs, subEndMs) = window
        val subClip =
          if (splitWindows.size == 1) {
            nonOverlappingClip
          } else {
            sliceVideoClipSubRange(
              clip = nonOverlappingClip,
              subStartMs = subStartMs,
              subEndMs = subEndMs,
              isImage = asset.type == MediaType.IMAGE,
              suffix = "adj$subIndex",
            )
          }

        val effectiveSpeed =
          if (subClip.hasVariableSpeed && subClip.sourceSpanMs > 0L) {
            SpeedCurveEngine.averageEffectiveSpeed(subClip.sourceSpanMs, subClip.speed, subClip.speedCurve)
          } else {
            subClip.speed.coerceIn(MIN_SPEED, MAX_SPEED)
          }
        val targetSlotDurationMs = (subEndMs - subStartMs).coerceAtLeast(MIN_RENDER_SEGMENT_MS)
        val isImage = asset.type == MediaType.IMAGE

        val reconciledBounds =
          reconcileSourceAndDuration(
            isImage = isImage,
            rawSourceInMs = subClip.sourceInMs,
            rawSourceOutMs = subClip.sourceOutMs,
            targetSlotDurationMs = targetSlotDurationMs,
            effectiveSpeed = effectiveSpeed,
            speedCurve = subClip.speedCurve,
            assetDurationMs = asset.fingerprint.durationMs,
          )

        if (reconciledBounds == null) {
          skipped +=
            SkippedRenderClip(
              rawClip.clipId,
              rawClip.kind,
              if (!isImage && asset.fingerprint.durationMs > 0L && subClip.sourceInMs >= asset.fingerprint.durationMs - 1L) {
                SkippedSegmentReason.SOURCE_OUT_OF_BOUNDS
              } else {
                SkippedSegmentReason.BELOW_MIN_DURATION
              },
            )
          continue
        }

        val (clampedSourceIn, clampedSourceOut, expectedDurationMs, finalCurve) = reconciledBounds
        val resolvedSegmentSpeed =
          if (finalCurve.size >= 2) {
            SpeedCurveEngine.averageEffectiveSpeed((clampedSourceOut - clampedSourceIn).coerceAtLeast(1L), effectiveSpeed, finalCurve)
          } else {
            effectiveSpeed
          }
        val leadingGapMs = (subStartMs - videoCursorMs).coerceAtLeast(0L)
        val segmentId =
          if (splitWindows.size == 1) subClip.clipId else "${rawClip.clipId}__seg$subIndex"

        val coveringAdjustments =
          activeAdjustments
            .filter { adj -> adj.timelineStartMs < subEndMs && adj.timelineEndMs > subStartMs }
            .mapNotNull { it.adjustmentConfig }
        val mergedAdjustment = mergeAdjustmentConfigs(coveringAdjustments, customLutPresets)

        val isTrackAudible = trackAudibility[subClip.trackId] == true
        val trackVolume = trackById[subClip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == subClip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(subClip, duckingWindows)
          } else {
            emptyList()
          }

        val finalSegmentClip =
          subClip.copy(
            timelineEndMs = subStartMs + expectedDurationMs,
            sourceInMs = clampedSourceIn,
            sourceOutMs = clampedSourceOut,
            speed = resolvedSegmentSpeed,
            speedCurve = finalCurve,
          )
        val clipDepth = finalSegmentClip.videoConfig?.depthBlur ?: 0f
        val adjDepth = mergedAdjustment?.let { ColorGradingEngine.extractAdjustmentBaseStage(it).depthBlur } ?: 0f
        val resolvedDepthBlur = (clipDepth + adjDepth).coerceIn(0f, 1f)
        val videoCfg = finalSegmentClip.videoConfig
        val audioFadeInMs =
          max(videoCfg?.fadeInMs ?: 0L, videoCfg?.transitionIn?.durationMs ?: 0L)
            .coerceIn(0L, expectedDurationMs)
        val audioFadeOutMs =
          max(videoCfg?.fadeOutMs ?: 0L, videoCfg?.transitionOut?.durationMs ?: 0L)
            .coerceIn(0L, expectedDurationMs)

        videoSegments +=
          PlannedVideoSegment(
            sourceClipId = rawClip.clipId,
            segmentId = segmentId,
            clip = finalSegmentClip,
            asset = asset,
            mediaSource = resolvedSource,
            leadingGapMs = leadingGapMs,
            targetTimelineStartMs = subStartMs,
            targetTimelineEndMs = subStartMs + expectedDurationMs,
            expectedRenderedDurationMs = expectedDurationMs,
            sourceInMs = clampedSourceIn,
            sourceOutMs = clampedSourceOut,
            effectiveSpeed = resolvedSegmentSpeed,
            isImage = isImage,
            isTrackAudible = isTrackAudible,
            trackVolume = trackVolume,
            duckingWindows = clipDuckingWindows,
            effectiveAdjustment = mergedAdjustment,
            envelopeAnchorShiftMs = (subStartMs - rawClip.timelineStartMs).coerceAtLeast(0L),
            envelopeParentDurationMs = rawClip.durationMs,
            speedCurve = finalCurve,
            resolvedDepthBlur = resolvedDepthBlur,
            audioFadeInMs = audioFadeInMs,
            audioFadeOutMs = audioFadeOutMs,
            preserveAudioPitch = videoCfg?.preserveAudioPitch ?: true,
          )

        videoCursorMs = subStartMs + expectedDurationMs
        emittedForClip = true
      }

      if (emittedForClip && resolvedSource.isProxy) {
        usedProxyCount++
      }
    }

    // 2. Plan audible auxiliary audio tracks
    val audibleAudioTracks =
      timeline.tracksSorted().filter { it.type == TrackType.AUDIO && trackAudibility[it.trackId] == true }

    val plannedAudioSequences = mutableListOf<PlannedAudioTrackSequence>()

    for (track in audibleAudioTracks) {
      val trackClips =
        timeline.clipsForTrack(track.trackId)
          .filter { it.isEnabled && it.kind == ClipKind.AUDIO }
          .sortedWith(compareBy<TimelineClip>({ it.timelineStartMs }, { -it.timelineEndMs }, { it.clipId }))

      val trackVolume = track.volume.coerceIn(0f, 2f)
      val segments = mutableListOf<PlannedAudioSegment>()
      var audioCursorMs = 0L

      for (rawClip in trackClips) {
        val asset = rawClip.assetId?.let { assetById[it] }
        if (asset == null) {
          skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.MISSING_ASSET)
          continue
        }
        val resolvedSource = sourceResolver(asset)
        if (resolvedSource == null) {
          skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.UNAVAILABLE_SOURCE)
          continue
        }

        val nonOverlappingClip =
          if (rawClip.timelineStartMs < audioCursorMs) {
            if (rawClip.timelineEndMs <= audioCursorMs + MIN_RENDER_SEGMENT_MS) {
              skipped +=
                SkippedRenderClip(
                  rawClip.clipId,
                  rawClip.kind,
                  SkippedSegmentReason.OVERLAPPED_BY_PREVIOUS,
                )
              continue
            }
            sliceAudioClipSubRange(
              clip = rawClip,
              subStartMs = audioCursorMs,
              subEndMs = rawClip.timelineEndMs,
              suffix = "trim",
            )
          } else {
            rawClip
          }

        if (nonOverlappingClip.durationMs < MIN_RENDER_SEGMENT_MS) {
          skipped += SkippedRenderClip(rawClip.clipId, rawClip.kind, SkippedSegmentReason.BELOW_MIN_DURATION)
          continue
        }

        val effectiveSpeed =
          if (nonOverlappingClip.hasVariableSpeed && nonOverlappingClip.sourceSpanMs > 0L) {
            SpeedCurveEngine.averageEffectiveSpeed(
              nonOverlappingClip.sourceSpanMs,
              nonOverlappingClip.speed,
              nonOverlappingClip.speedCurve,
            )
          } else {
            nonOverlappingClip.speed.coerceIn(MIN_SPEED, MAX_SPEED)
          }
        val reconciledBounds =
          reconcileSourceAndDuration(
            isImage = false,
            rawSourceInMs = nonOverlappingClip.sourceInMs,
            rawSourceOutMs = nonOverlappingClip.sourceOutMs,
            targetSlotDurationMs = nonOverlappingClip.durationMs,
            effectiveSpeed = effectiveSpeed,
            speedCurve = nonOverlappingClip.speedCurve,
            assetDurationMs = asset.fingerprint.durationMs,
          )

        if (reconciledBounds == null) {
          skipped +=
            SkippedRenderClip(
              rawClip.clipId,
              rawClip.kind,
              if (asset.fingerprint.durationMs > 0L && nonOverlappingClip.sourceInMs >= asset.fingerprint.durationMs - 1L) {
                SkippedSegmentReason.SOURCE_OUT_OF_BOUNDS
              } else {
                SkippedSegmentReason.BELOW_MIN_DURATION
              },
            )
          continue
        }

        val (clampedSourceIn, clampedSourceOut, expectedDurationMs, finalCurve) = reconciledBounds
        val resolvedSegmentSpeed =
          if (finalCurve.size >= 2) {
            SpeedCurveEngine.averageEffectiveSpeed((clampedSourceOut - clampedSourceIn).coerceAtLeast(1L), effectiveSpeed, finalCurve)
          } else {
            effectiveSpeed
          }
        val leadingGapMs = (nonOverlappingClip.timelineStartMs - audioCursorMs).coerceAtLeast(0L)
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == nonOverlappingClip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(nonOverlappingClip, duckingWindows)
          } else {
            emptyList()
          }

        val finalClip =
          nonOverlappingClip.copy(
            timelineEndMs = nonOverlappingClip.timelineStartMs + expectedDurationMs,
            sourceInMs = clampedSourceIn,
            sourceOutMs = clampedSourceOut,
            speed = resolvedSegmentSpeed,
            speedCurve = finalCurve,
          )

        segments +=
          PlannedAudioSegment(
            sourceClipId = rawClip.clipId,
            segmentId = rawClip.clipId,
            clip = finalClip,
            asset = asset,
            mediaSource = resolvedSource,
            leadingGapMs = leadingGapMs,
            targetTimelineStartMs = nonOverlappingClip.timelineStartMs,
            targetTimelineEndMs = nonOverlappingClip.timelineStartMs + expectedDurationMs,
            expectedRenderedDurationMs = expectedDurationMs,
            sourceInMs = clampedSourceIn,
            sourceOutMs = clampedSourceOut,
            effectiveSpeed = resolvedSegmentSpeed,
            trackVolume = trackVolume,
            duckingWindows = clipDuckingWindows,
            envelopeAnchorShiftMs = (nonOverlappingClip.timelineStartMs - rawClip.timelineStartMs).coerceAtLeast(0L),
            envelopeParentDurationMs = rawClip.durationMs,
            speedCurve = finalCurve,
            preserveAudioPitch = finalClip.audioConfig?.preserveAudioPitch ?: true,
          )

        audioCursorMs = nonOverlappingClip.timelineStartMs + expectedDurationMs
      }

      if (segments.isNotEmpty()) {
        plannedAudioSequences +=
          PlannedAudioTrackSequence(
            trackId = track.trackId,
            trackName = track.name,
            trackOrder = track.order,
            segments = segments,
            totalPlannedDurationMs = audioCursorMs,
          )
      }
    }

    // 3. Plan visible non-blank subtitle overlays
    val subtitleOverlays =
      timeline.clips
        .asSequence()
        .filter { clip ->
          clip.isEnabled &&
            clip.kind == ClipKind.SUBTITLE &&
            trackById[clip.trackId]?.isVisible != false
        }
        .sortedWith(
          compareBy<TimelineClip>(
            { it.timelineStartMs },
            { trackById[it.trackId]?.order ?: Int.MAX_VALUE },
            { it.clipId },
          ),
        )
        .mapNotNull { clip ->
          val cfg = clip.subtitleConfig
          if (cfg == null || cfg.text.isBlank()) {
            skipped += SkippedRenderClip(clip.clipId, clip.kind, SkippedSegmentReason.EMPTY_SUBTITLE)
            null
          } else if (clip.durationMs < MIN_RENDER_SEGMENT_MS) {
            skipped += SkippedRenderClip(clip.clipId, clip.kind, SkippedSegmentReason.BELOW_MIN_DURATION)
            null
          } else {
            PlannedSubtitleOverlay(
              clipId = clip.clipId,
              timelineStartMs = clip.timelineStartMs,
              timelineEndMs = clip.timelineEndMs,
              config = cfg,
            )
          }
        }
        .toList()

    // 4. Plan visible overlay/PiP/sticker tracks (tracks marked isOverlay or secondary visible video tracks)
    val overlayTracks =
      timeline.tracksSorted().filter { track ->
        track.type == TrackType.VIDEO && track.isVisible && track.trackId != videoTrack?.trackId
      }
    val overlaySegments =
      overlayTracks.flatMap { track ->
        timeline.clipsForTrack(track.trackId)
          .asSequence()
          .filter { it.isEnabled && it.kind == ClipKind.VIDEO && it.durationMs >= MIN_RENDER_SEGMENT_MS }
          .mapNotNull { clip ->
            val asset = clip.assetId?.let { assetById[it] }
            val resolved = asset?.let { sourceResolver(it) }
            val cfg = clip.videoConfig
            val inferredKind =
              when {
                cfg != null && cfg.overlayKind != OverlayContentKind.NONE -> cfg.overlayKind
                !cfg?.stickerId.isNullOrBlank() -> OverlayContentKind.STICKER
                asset?.type == MediaType.IMAGE -> OverlayContentKind.IMAGE
                asset?.type == MediaType.VIDEO -> OverlayContentKind.VIDEO_PIP
                else -> OverlayContentKind.NONE
              }
            if (inferredKind == OverlayContentKind.NONE) {
              null
            } else {
              PlannedOverlaySegment(
                clip = clip,
                trackId = track.trackId,
                trackOrder = track.order,
                timelineStartMs = clip.timelineStartMs,
                timelineEndMs = clip.timelineEndMs,
                asset = asset,
                mediaSource = resolved,
                overlayKind = inferredKind,
                stickerId = cfg?.stickerId,
              )
            }
          }
          .toList()
      }.sortedWith(compareBy<PlannedOverlaySegment>({ -it.trackOrder }, { it.timelineStartMs }, { it.clip.clipId }))

    val maxAudioMs = plannedAudioSequences.maxOfOrNull { it.totalPlannedDurationMs } ?: 0L
    val maxSubtitleMs =
      if (videoSegments.isNotEmpty() || plannedAudioSequences.isNotEmpty() || overlaySegments.isNotEmpty()) {
        subtitleOverlays.maxOfOrNull { it.timelineEndMs } ?: 0L
      } else {
        0L
      }
    val maxOverlayMs = overlaySegments.maxOfOrNull { it.timelineEndMs } ?: 0L
    val totalPlannedDurationMs = maxOf(videoCursorMs, maxAudioMs, maxSubtitleMs, maxOverlayMs)

    return TimelineRenderPlan(
      primaryVideoSegments = videoSegments,
      audioTracks = plannedAudioSequences,
      subtitleOverlays = subtitleOverlays,
      totalPlannedDurationMs = totalPlannedDurationMs,
      usedProxyCount = usedProxyCount,
      skippedClips = skipped,
      overlaySegments = overlaySegments,
    )
  }

  internal fun resolveMediaSource(
    asset: MediaAsset,
    projectRootDir: File,
    useProxies: Boolean,
  ): PlannedMediaSource? {
    if (useProxies) {
      val proxyRel = asset.proxyRelativePath
      if (!proxyRel.isNullOrBlank()) {
        val proxyFile = File(projectRootDir, proxyRel)
        if (proxyFile.exists() && proxyFile.length() > 0L) {
          return PlannedMediaSource(sourceFile = proxyFile, isProxy = true)
        }
      }
    }

    val relativeFile = File(projectRootDir, asset.relativePath)
    if (relativeFile.exists() && relativeFile.length() > 0L) {
      return PlannedMediaSource(sourceFile = relativeFile, isProxy = false)
    }

    val externalUri = asset.externalSourceUri
    if (!externalUri.isNullOrBlank()) {
      return PlannedMediaSource(externalUri = externalUri, isProxy = false)
    }

    return null
  }

  private data class ReconciledBounds(
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val expectedRenderedDurationMs: Long,
    val slicedSpeedCurve: List<SpeedCurvePoint>,
  )

  private fun reconcileSourceAndDuration(
    isImage: Boolean,
    rawSourceInMs: Long,
    rawSourceOutMs: Long,
    targetSlotDurationMs: Long,
    effectiveSpeed: Float,
    speedCurve: List<SpeedCurvePoint>,
    assetDurationMs: Long,
  ): ReconciledBounds? {
    if (isImage) {
      val durationMs = targetSlotDurationMs.coerceAtLeast(MIN_RENDER_SEGMENT_MS)
      return ReconciledBounds(0L, durationMs, durationMs, emptyList())
    }

    val safeIn = rawSourceInMs.coerceAtLeast(0L)
    if (assetDurationMs > 0L && safeIn >= assetDurationMs - 1L) {
      return null
    }

    val hasVarCurve = SpeedCurveEngine.hasVariableCurve(speedCurve)
    val rawSpan = if (rawSourceOutMs > safeIn) rawSourceOutMs - safeIn else 0L
    val expectedSourceForSlot =
      if (hasVarCurve && rawSpan > 0L) {
        SpeedCurveEngine.timelineOffsetMsToSourceMs(
          timelineOffsetMs = targetSlotDurationMs,
          sourceSpanMs = rawSpan,
          baseSpeed = effectiveSpeed,
          speedCurve = speedCurve,
        ).coerceAtLeast(1L)
      } else {
        (targetSlotDurationMs.toDouble() * effectiveSpeed.toDouble()).roundToLong().coerceAtLeast(1L)
      }

    val desiredSourceOut =
      if (rawSourceOutMs > safeIn) {
        minOf(rawSourceOutMs, safeIn + expectedSourceForSlot)
      } else {
        safeIn + expectedSourceForSlot
      }

    val boundedOut =
      if (assetDurationMs > 0L) {
        desiredSourceOut.coerceAtMost(assetDurationMs)
      } else {
        desiredSourceOut
      }

    val actualSourceSpan = boundedOut - safeIn
    if (actualSourceSpan <= 0L) return null

    val slicedCurve =
      if (hasVarCurve && rawSpan > 0L && actualSourceSpan < rawSpan) {
        SpeedCurveEngine.sliceCurveForSourceRange(
          speedCurve = speedCurve,
          baseSpeed = effectiveSpeed,
          oldSourceSpanMs = rawSpan,
          subSourceStartMs = 0L,
          subSourceEndMs = actualSourceSpan,
        )
      } else if (hasVarCurve) {
        SpeedCurveEngine.normalizeCurve(speedCurve, effectiveSpeed)
      } else {
        emptyList()
      }

    val expectedRenderedDurationMs =
      if (slicedCurve.size >= 2) {
        SpeedCurveEngine.renderedDurationMs(
          sourceSpanMs = actualSourceSpan,
          baseSpeed = effectiveSpeed,
          speedCurve = slicedCurve,
        )
      } else {
        (actualSourceSpan.toDouble() / effectiveSpeed.toDouble()).roundToLong()
      }
    if (expectedRenderedDurationMs < MIN_RENDER_SEGMENT_MS) {
      return null
    }

    return ReconciledBounds(safeIn, boundedOut, expectedRenderedDurationMs, slicedCurve)
  }

  internal fun splitClipByAdjustmentBoundaries(
    clip: TimelineClip,
    adjustments: List<TimelineClip>,
  ): List<Pair<Long, Long>> {
    val start = clip.timelineStartMs
    val end = clip.timelineEndMs
    val cutPoints = sortedSetOf(start, end)
    for (adj in adjustments) {
      if (adj.timelineStartMs in (start + 1L) until end) {
        cutPoints.add(adj.timelineStartMs)
      }
      if (adj.timelineEndMs in (start + 1L) until end) {
        cutPoints.add(adj.timelineEndMs)
      }
    }

    val points = cutPoints.toList()
    if (points.size <= 2) return listOf(start to end)

    // Build windows and coalesce micro-segments < MIN_RENDER_SEGMENT_MS to avoid codec crashes.
    val rawWindows = mutableListOf<Pair<Long, Long>>()
    for (i in 0 until points.lastIndex) {
      val segStart = points[i]
      val segEnd = points[i + 1]
      if (segEnd - segStart < MIN_RENDER_SEGMENT_MS && rawWindows.isNotEmpty()) {
        val prev = rawWindows.removeAt(rawWindows.lastIndex)
        rawWindows.add(prev.first to segEnd)
      } else {
        rawWindows.add(segStart to segEnd)
      }
    }

    if (rawWindows.size > 1 && rawWindows.first().second - rawWindows.first().first < MIN_RENDER_SEGMENT_MS) {
      val first = rawWindows.removeAt(0)
      val second = rawWindows.removeAt(0)
      rawWindows.add(0, first.first to second.second)
    }

    return rawWindows
  }

  private fun sliceVideoClipSubRange(
    clip: TimelineClip,
    subStartMs: Long,
    subEndMs: Long,
    isImage: Boolean,
    suffix: String,
  ): TimelineClip {
    val effectiveSpeed = clip.speed.coerceIn(MIN_SPEED, MAX_SPEED)
    val leftTrimMs = (subStartMs - clip.timelineStartMs).coerceAtLeast(0L)
    val rightTrimMs = (clip.timelineEndMs - subEndMs).coerceAtLeast(0L)
    val subDurationMs = (subEndMs - subStartMs).coerceAtLeast(1L)
    val oldSourceSpan = clip.sourceSpanMs

    val (nextSourceIn, nextSourceOut, slicedCurve) =
      if (isImage) {
        Triple(0L, subDurationMs, emptyList<SpeedCurvePoint>())
      } else if (clip.hasVariableSpeed && oldSourceSpan > 0L) {
        val relEndTimeline = (clip.durationMs - rightTrimMs).coerceIn(leftTrimMs, clip.durationMs)
        val srcStartOff =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = leftTrimMs,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = effectiveSpeed,
            speedCurve = clip.speedCurve,
          )
        val srcEndOff =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = relEndTimeline,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = effectiveSpeed,
            speedCurve = clip.speedCurve,
          )
        val sIn = clip.sourceInMs + srcStartOff
        val sOut = (clip.sourceInMs + srcEndOff).coerceAtLeast(sIn + 1L)
        val curve =
          SpeedCurveEngine.sliceCurveForSourceRange(
            speedCurve = clip.speedCurve,
            baseSpeed = effectiveSpeed,
            oldSourceSpanMs = oldSourceSpan,
            subSourceStartMs = srcStartOff,
            subSourceEndMs = (sOut - clip.sourceInMs).coerceAtLeast(srcStartOff + 1L),
          )
        Triple(sIn, sOut, curve)
      } else {
        val sIn = clip.sourceInMs + (leftTrimMs * effectiveSpeed).roundToLong()
        val sOut = (clip.sourceOutMs - (rightTrimMs * effectiveSpeed).roundToLong()).coerceAtLeast(sIn + 1L)
        Triple(sIn, sOut, emptyList())
      }

    val relStartMs = (subStartMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val relEndMs = (subEndMs - clip.timelineStartMs).coerceIn(relStartMs, clip.durationMs)

    val nextVideoCfg =
      clip.videoConfig?.let { cfg ->
        val slicedKeyframes =
          if (cfg.keyframes.isEmpty()) {
            emptyList()
          } else {
            ClipKeyframeEvaluator.sliceKeyframesForRange(
              keyframes = cfg.keyframes,
              rangeOffsetStartMs = relStartMs,
              rangeOffsetEndMs = relEndMs,
              baseScale = cfg.scale,
              baseOpacity = cfg.opacity,
              baseOffsetX = cfg.offsetX,
              baseOffsetY = cfg.offsetY,
              baseRotationDegrees = cfg.rotationDegrees,
              baseVolume = cfg.volume,
              idPrefix = "${clip.clipId}_$suffix",
              baseDepthBlur = cfg.depthBlur,
              baseDepthFocusX = cfg.depthFocusX,
              baseDepthFocusY = cfg.depthFocusY,
              baseDepthFocusRange = cfg.depthFocusRange,
            )
          }
        cfg.copy(
          fadeInMs = (cfg.fadeInMs - leftTrimMs).coerceIn(0L, subDurationMs),
          fadeOutMs = (cfg.fadeOutMs - rightTrimMs).coerceIn(0L, subDurationMs),
          transitionIn = if (leftTrimMs > 0L) null else cfg.transitionIn,
          transitionOut = if (rightTrimMs > 0L) null else cfg.transitionOut,
          keyframes = slicedKeyframes,
        )
      }

    val nextSpeed =
      if (slicedCurve.size >= 2 && !isImage) {
        SpeedCurveEngine.averageEffectiveSpeed((nextSourceOut - nextSourceIn).coerceAtLeast(1L), effectiveSpeed, slicedCurve)
      } else {
        clip.speed
      }

    return clip.copy(
      timelineStartMs = subStartMs,
      timelineEndMs = subEndMs,
      sourceInMs = nextSourceIn,
      sourceOutMs = nextSourceOut,
      speed = nextSpeed,
      speedCurve = slicedCurve,
      videoConfig = nextVideoCfg,
    )
  }

  private fun sliceAudioClipSubRange(
    clip: TimelineClip,
    subStartMs: Long,
    subEndMs: Long,
    suffix: String,
  ): TimelineClip {
    val effectiveSpeed = clip.speed.coerceIn(MIN_SPEED, MAX_SPEED)
    val leftTrimMs = (subStartMs - clip.timelineStartMs).coerceAtLeast(0L)
    val rightTrimMs = (clip.timelineEndMs - subEndMs).coerceAtLeast(0L)
    val subDurationMs = (subEndMs - subStartMs).coerceAtLeast(1L)
    val oldSourceSpan = clip.sourceSpanMs

    val (nextSourceIn, nextSourceOut, slicedCurve) =
      if (clip.hasVariableSpeed && oldSourceSpan > 0L) {
        val relEndTimeline = (clip.durationMs - rightTrimMs).coerceIn(leftTrimMs, clip.durationMs)
        val srcStartOff =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = leftTrimMs,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = effectiveSpeed,
            speedCurve = clip.speedCurve,
          )
        val srcEndOff =
          SpeedCurveEngine.timelineOffsetMsToSourceMs(
            timelineOffsetMs = relEndTimeline,
            sourceSpanMs = oldSourceSpan,
            baseSpeed = effectiveSpeed,
            speedCurve = clip.speedCurve,
          )
        val sIn = clip.sourceInMs + srcStartOff
        val sOut = (clip.sourceInMs + srcEndOff).coerceAtLeast(sIn + 1L)
        val curve =
          SpeedCurveEngine.sliceCurveForSourceRange(
            speedCurve = clip.speedCurve,
            baseSpeed = effectiveSpeed,
            oldSourceSpanMs = oldSourceSpan,
            subSourceStartMs = srcStartOff,
            subSourceEndMs = (sOut - clip.sourceInMs).coerceAtLeast(srcStartOff + 1L),
          )
        Triple(sIn, sOut, curve)
      } else {
        val sIn = clip.sourceInMs + (leftTrimMs * effectiveSpeed).roundToLong()
        val sOut = (clip.sourceOutMs - (rightTrimMs * effectiveSpeed).roundToLong()).coerceAtLeast(sIn + 1L)
        Triple(sIn, sOut, emptyList())
      }

    val relStartMs = (subStartMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val relEndMs = (subEndMs - clip.timelineStartMs).coerceIn(relStartMs, clip.durationMs)

    val nextAudioCfg =
      clip.audioConfig?.let { cfg ->
        val slicedKeyframes =
          if (cfg.keyframes.isEmpty()) {
            emptyList()
          } else {
            ClipKeyframeEvaluator.sliceKeyframesForRange(
              keyframes = cfg.keyframes,
              rangeOffsetStartMs = relStartMs,
              rangeOffsetEndMs = relEndMs,
              baseVolume = cfg.volume,
              idPrefix = "${clip.clipId}_$suffix",
            )
          }
        cfg.copy(
          fadeInMs = (cfg.fadeInMs - leftTrimMs).coerceIn(0L, subDurationMs),
          fadeOutMs = (cfg.fadeOutMs - rightTrimMs).coerceIn(0L, subDurationMs),
          keyframes = slicedKeyframes,
        )
      }

    val nextSpeed =
      if (slicedCurve.size >= 2) {
        SpeedCurveEngine.averageEffectiveSpeed((nextSourceOut - nextSourceIn).coerceAtLeast(1L), effectiveSpeed, slicedCurve)
      } else {
        clip.speed
      }

    return clip.copy(
      timelineStartMs = subStartMs,
      timelineEndMs = subEndMs,
      sourceInMs = nextSourceIn,
      sourceOutMs = nextSourceOut,
      speed = nextSpeed,
      speedCurve = slicedCurve,
      audioConfig = nextAudioCfg,
    )
  }

  internal fun isNeutralAdjustment(
    cfg: AdjustmentClipConfig,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): Boolean {
    if (cfg.blendOpacity <= 0.005f) return true
    val resolvedLut = BuiltInLutPresets.findById(cfg.lutPresetId, customLutPresets)
    val hasLut =
      (resolvedLut != null && resolvedLut.id != BuiltInLutPresets.ID_NONE && cfg.lutStrength > 0.005f) ||
        !cfg.lutRelativePath.isNullOrBlank()
    if (hasLut) return false
    return abs(cfg.brightness) <= 0.005f &&
      abs(cfg.exposure) <= 0.005f &&
      abs(cfg.contrast - 1.0f) <= 0.005f &&
      abs(cfg.saturation - 1.0f) <= 0.005f &&
      abs(cfg.temperature) <= 0.005f &&
      abs(cfg.tint) <= 0.005f &&
      abs(cfg.shadows) <= 0.005f &&
      abs(cfg.midtones) <= 0.005f &&
      abs(cfg.highlights) <= 0.005f &&
      abs(cfg.whites) <= 0.005f &&
      abs(cfg.blacks) <= 0.005f &&
      abs(cfg.vibrance) <= 0.005f &&
      abs(cfg.liftX) <= 0.005f &&
      abs(cfg.liftY) <= 0.005f &&
      abs(cfg.gammaX) <= 0.005f &&
      abs(cfg.gammaY) <= 0.005f &&
      abs(cfg.gainX) <= 0.005f &&
      abs(cfg.gainY) <= 0.005f &&
      abs(cfg.vignette) <= 0.005f &&
      abs(cfg.depthBlur) <= 0.005f &&
      abs(cfg.chromaticAberration) <= 0.005f &&
      abs(cfg.glitchIntensity) <= 0.005f
  }

  internal fun mergeAdjustmentConfigs(
    configs: List<AdjustmentClipConfig>,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): AdjustmentClipConfig? {
    if (configs.isEmpty()) return null
    if (configs.size == 1) return configs.first()

    var brightness = 0.0f
    var exposure = 0.0f
    var contrast = 1.0f
    var saturation = 1.0f
    var temperature = 0.0f
    var tint = 0.0f
    var shadows = 0.0f
    var midtones = 0.0f
    var highlights = 0.0f
    var whites = 0.0f
    var blacks = 0.0f
    var vibrance = 0.0f
    var liftX = 0.0f
    var liftY = 0.0f
    var gammaX = 0.0f
    var gammaY = 0.0f
    var gainX = 0.0f
    var gainY = 0.0f
    var vignette = 0.0f
    var depthBlur = 0.0f
    var chromaticAberration = 0.0f
    var glitchIntensity = 0.0f
    var depthBlurRadius = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS
    var depthBlurFeather = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER
    var depthBlurMode = DepthBlurMaskMode.RADIAL
    var depthFocusX = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD
    var depthFocusY = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD
    var depthFocusRange = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE
    var strongestDepthWeight = 0.0f
    var activeLutId: String? = null
    var activeLutPath: String? = null
    var activeLutStrength = 0.0f

    for (raw in configs) {
      val base = ColorGradingEngine.extractAdjustmentBaseStage(raw)
      val lut = ColorGradingEngine.extractAdjustmentLutStage(raw, customLutPresets)
      brightness = (brightness + base.brightness).coerceIn(ColorGradeBounds.MIN_BRIGHTNESS, ColorGradeBounds.MAX_BRIGHTNESS)
      exposure = (exposure + base.exposure).coerceIn(ColorGradeBounds.MIN_EXPOSURE, ColorGradeBounds.MAX_EXPOSURE)
      contrast = (contrast * base.contrast).coerceIn(ColorGradeBounds.MIN_CONTRAST, ColorGradeBounds.MAX_CONTRAST)
      saturation = (saturation * base.saturation).coerceIn(ColorGradeBounds.MIN_SATURATION, ColorGradeBounds.MAX_SATURATION)
      temperature = (temperature + base.temperature).coerceIn(ColorGradeBounds.MIN_TEMPERATURE, ColorGradeBounds.MAX_TEMPERATURE)
      tint = (tint + base.tint).coerceIn(ColorGradeBounds.MIN_TINT, ColorGradeBounds.MAX_TINT)
      shadows = (shadows + base.shadows).coerceIn(ColorGradeBounds.MIN_SHADOWS, ColorGradeBounds.MAX_SHADOWS)
      midtones = (midtones + base.midtones).coerceIn(ColorGradeBounds.MIN_MIDTONES, ColorGradeBounds.MAX_MIDTONES)
      highlights = (highlights + base.highlights).coerceIn(ColorGradeBounds.MIN_HIGHLIGHTS, ColorGradeBounds.MAX_HIGHLIGHTS)
      whites = (whites + base.whites).coerceIn(ColorGradeBounds.MIN_WHITES, ColorGradeBounds.MAX_WHITES)
      blacks = (blacks + base.blacks).coerceIn(ColorGradeBounds.MIN_BLACKS, ColorGradeBounds.MAX_BLACKS)
      vibrance = (vibrance + base.vibrance).coerceIn(ColorGradeBounds.MIN_VIBRANCE, ColorGradeBounds.MAX_VIBRANCE)
      liftX = (liftX + base.liftX).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      liftY = (liftY + base.liftY).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      gammaX = (gammaX + base.gammaX).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      gammaY = (gammaY + base.gammaY).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      gainX = (gainX + base.gainX).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      gainY = (gainY + base.gainY).coerceIn(ColorGradeBounds.MIN_WHEEL_COORD, ColorGradeBounds.MAX_WHEEL_COORD)
      vignette = (vignette + base.vignette).coerceIn(ColorGradeBounds.MIN_VIGNETTE, ColorGradeBounds.MAX_VIGNETTE)
      depthBlur = (depthBlur + base.depthBlur).coerceIn(ColorGradeBounds.MIN_DEPTH_BLUR, ColorGradeBounds.MAX_DEPTH_BLUR)
      chromaticAberration =
        (chromaticAberration + base.chromaticAberration)
          .coerceIn(ColorGradeBounds.MIN_CHROMATIC_ABERRATION, ColorGradeBounds.MAX_CHROMATIC_ABERRATION)
      glitchIntensity =
        (glitchIntensity + base.glitchIntensity)
          .coerceIn(ColorGradeBounds.MIN_GLITCH_INTENSITY, ColorGradeBounds.MAX_GLITCH_INTENSITY)

      if (base.depthBlur >= strongestDepthWeight && base.depthBlur > 0.005f) {
        strongestDepthWeight = base.depthBlur
        depthBlurRadius = base.depthBlurRadius
        depthBlurFeather = base.depthBlurFeather
        depthBlurMode = base.depthBlurMode
        depthFocusX = base.depthFocusX
        depthFocusY = base.depthFocusY
        depthFocusRange = base.depthFocusRange
      }

      if (lut.hasLutApplication) {
        activeLutId = lut.activeLut?.id
        activeLutStrength = lut.lutStrength
      }
      if (!raw.lutRelativePath.isNullOrBlank() && raw.blendOpacity > 0.005f) {
        activeLutPath = raw.lutRelativePath
      }
    }

    return AdjustmentClipConfig(
      brightness = brightness,
      contrast = contrast,
      saturation = saturation,
      exposure = exposure,
      temperature = temperature,
      vignette = vignette,
      lutRelativePath = activeLutPath,
      lutPresetId = activeLutId,
      lutStrength = activeLutStrength,
      tint = tint,
      shadows = shadows,
      midtones = midtones,
      highlights = highlights,
      blendOpacity = 1.0f,
      depthBlur = depthBlur,
      whites = whites,
      blacks = blacks,
      vibrance = vibrance,
      liftX = liftX,
      liftY = liftY,
      gammaX = gammaX,
      gammaY = gammaY,
      gainX = gainX,
      gainY = gainY,
      depthBlurRadius = depthBlurRadius,
      depthBlurFeather = depthBlurFeather,
      depthBlurMode = depthBlurMode,
      depthFocusX = depthFocusX,
      depthFocusY = depthFocusY,
      depthFocusRange = depthFocusRange,
      chromaticAberration = chromaticAberration,
      glitchIntensity = glitchIntensity,
    )
  }
}
