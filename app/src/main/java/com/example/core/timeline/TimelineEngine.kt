package com.example.core.timeline

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.ClipTransition
import com.example.core.model.EditModeOptions
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.MarkerColor
import com.example.core.model.RippleScope
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import com.example.core.subtitles.SubtitleBatchEngine
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Outcome of an immutable timeline mutation.
 */
sealed interface TimelineOperationResult {
  data class Success(
    val newState: TimelineState,
    val affectedClipIds: List<String> = emptyList(),
  ) : TimelineOperationResult

  data class Rejected(val reason: RejectionReason) : TimelineOperationResult
}

enum class RejectionReason {
  CLIP_NOT_FOUND,
  TRACK_LOCKED,
  CLIP_LOCKED,
  NO_CLIP_UNDER_PLAYHEAD,
  SEGMENT_TOO_SHORT,
  INVALID_BOUNDS,
  MARKER_NOT_FOUND,
}

/**
 * Pure, deterministic multitrack timeline engine.
 *
 * All operations take an immutable [TimelineState] and return a [TimelineOperationResult],
 * making Undo/Redo stacks and unit testing straightforward.
 */
class TimelineEngine(
  private val minClipDurationMs: Long = DEFAULT_MIN_CLIP_DURATION_MS,
  private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

  fun seekPlayhead(state: TimelineState, positionMs: Long): TimelineState {
    val clamped = positionMs.coerceAtLeast(0L)
    return state.copy(playheadMs = clamped)
  }

  fun setRippleEnabled(state: TimelineState, enabled: Boolean): TimelineState =
    state.copy(editOptions = state.editOptions.copy(rippleEnabled = enabled))

  fun setRippleScope(state: TimelineState, scope: RippleScope): TimelineState =
    state.copy(editOptions = state.editOptions.copy(rippleScope = scope))

  fun updateEditOptions(
    state: TimelineState,
    transform: (EditModeOptions) -> EditModeOptions,
  ): TimelineState = state.copy(editOptions = transform(state.editOptions))

  /**
   * Splits one clip (if [targetClipId] is given) or every unlocked clip intersecting
   * `state.playheadMs` (optionally filtered by [targetTrackId]).
   */
  fun splitAtPlayhead(
    state: TimelineState,
    targetClipId: String? = null,
    targetTrackId: String? = null,
  ): TimelineOperationResult {
    val playhead = state.playheadMs
    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }

    val candidates =
      state.clips.filter { clip ->
        val matchesTarget =
          (targetClipId == null || clip.clipId == targetClipId) &&
            (targetTrackId == null || clip.trackId == targetTrackId)
        matchesTarget && playhead > clip.timelineStartMs && playhead < clip.timelineEndMs
      }

    if (candidates.isEmpty()) {
      return TimelineOperationResult.Rejected(RejectionReason.NO_CLIP_UNDER_PLAYHEAD)
    }

    val updatedClips = state.clips.toMutableList()
    val affectedIds = mutableListOf<String>()

    for (clip in candidates) {
      if (clip.trackId in lockedTrackIds) {
        if (targetClipId != null) {
          return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
        }
        continue
      }
      if (clip.isLocked) {
        if (targetClipId != null) {
          return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
        }
        continue
      }

      val leftDuration = playhead - clip.timelineStartMs
      val rightDuration = clip.timelineEndMs - playhead
      if (leftDuration < minClipDurationMs || rightDuration < minClipDurationMs) {
        if (targetClipId != null) {
          return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
        }
        continue
      }

      val splitResult = splitSingleClip(clip, playhead)
      val index = updatedClips.indexOfFirst { it.clipId == clip.clipId }
      if (index >= 0) {
        updatedClips[index] = splitResult.first
        updatedClips.add(index + 1, splitResult.second)
        affectedIds.add(splitResult.first.clipId)
        affectedIds.add(splitResult.second.clipId)
      }
    }

    if (affectedIds.isEmpty()) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }

    return TimelineOperationResult.Success(
      newState = state.copy(clips = updatedClips.sortedBy { it.timelineStartMs }),
      affectedClipIds = affectedIds,
    )
  }

  private fun splitSingleClip(clip: TimelineClip, splitMs: Long): Pair<TimelineClip, TimelineClip> {
    val leftTimelineDuration = splitMs - clip.timelineStartMs
    val rightTimelineDuration = clip.timelineEndMs - splitMs
    val hasMediaSource = clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO
    val oldSourceSpan = clip.sourceSpanMs
    val sourceSplitMs =
      if (hasMediaSource) {
        if (clip.hasVariableSpeed && oldSourceSpan > 0L) {
          val relSource =
            SpeedCurveEngine.timelineOffsetMsToSourceMs(
              timelineOffsetMs = leftTimelineDuration,
              sourceSpanMs = oldSourceSpan,
              baseSpeed = clip.speed,
              speedCurve = clip.speedCurve,
            )
          (clip.sourceInMs + relSource).coerceIn(clip.sourceInMs, clip.sourceOutMs)
        } else {
          (clip.sourceInMs + (leftTimelineDuration * clip.speed).roundToLong()).coerceIn(
            clip.sourceInMs,
            clip.sourceOutMs,
          )
        }
      } else {
        0L
      }

    val leftCurve =
      if (hasMediaSource && clip.hasVariableSpeed) {
        SpeedCurveEngine.sliceCurveForSourceRange(
          speedCurve = clip.speedCurve,
          baseSpeed = clip.speed,
          oldSourceSpanMs = oldSourceSpan,
          subSourceStartMs = 0L,
          subSourceEndMs = (sourceSplitMs - clip.sourceInMs).coerceAtLeast(1L),
        )
      } else {
        emptyList()
      }
    val rightCurve =
      if (hasMediaSource && clip.hasVariableSpeed) {
        SpeedCurveEngine.sliceCurveForSourceRange(
          speedCurve = clip.speedCurve,
          baseSpeed = clip.speed,
          oldSourceSpanMs = oldSourceSpan,
          subSourceStartMs = (sourceSplitMs - clip.sourceInMs).coerceIn(0L, oldSourceSpan),
          subSourceEndMs = oldSourceSpan,
        )
      } else {
        emptyList()
      }

    val (leftSubText, rightSubText) =
      if (clip.kind == ClipKind.SUBTITLE) {
        val ratio = (leftTimelineDuration.toFloat() / clip.durationMs.toFloat().coerceAtLeast(1f))
        SubtitleBatchEngine.splitTextProportionally(
          text = clip.subtitleConfig?.text ?: clip.label,
          ratio = ratio,
        )
      } else {
        "" to ""
      }

    val leftRaw =
      clip.copy(
        label =
          if (clip.kind == ClipKind.SUBTITLE) {
            leftSubText.lines().firstOrNull()?.take(28)?.ifBlank { clip.label } ?: clip.label
          } else {
            clip.label
          },
        timelineEndMs = splitMs,
        sourceOutMs = if (hasMediaSource) sourceSplitMs else clip.sourceOutMs,
        speed =
          if (leftCurve.size >= 2) {
            SpeedCurveEngine.averageEffectiveSpeed((sourceSplitMs - clip.sourceInMs).coerceAtLeast(1L), clip.speed, leftCurve)
          } else {
            clip.speed
          },
        speedCurve = leftCurve,
        videoConfig =
          clip.videoConfig?.let { cfg ->
            cfg.copy(
              transitionOut = null,
              fadeInMs = cfg.fadeInMs.coerceIn(0L, leftTimelineDuration),
              fadeOutMs = 0L,
            )
          },
        audioConfig =
          clip.audioConfig?.let { cfg ->
            cfg.copy(
              fadeInMs = cfg.fadeInMs.coerceIn(0L, leftTimelineDuration),
              fadeOutMs = 0L,
            )
          },
        subtitleConfig =
          if (clip.kind == ClipKind.SUBTITLE) {
            (clip.subtitleConfig ?: SubtitleClipConfig(text = leftSubText)).copy(text = leftSubText)
          } else {
            clip.subtitleConfig
          },
      )
    val leftClip =
      KeyframeTimelineOps.sliceClipKeyframes(
        clip = leftRaw,
        rangeOffsetStartMs = 0L,
        rangeOffsetEndMs = leftTimelineDuration,
        idPrefix = "${clip.clipId}_splitL",
      )

    val rightRaw =
      clip.copy(
        clipId = idGenerator(),
        label =
          if (clip.kind == ClipKind.SUBTITLE) {
            rightSubText.lines().firstOrNull()?.take(28)?.ifBlank { clip.label } ?: clip.label
          } else {
            clip.label
          },
        timelineStartMs = splitMs,
        sourceInMs = if (hasMediaSource) sourceSplitMs else clip.sourceInMs,
        speed =
          if (rightCurve.size >= 2) {
            SpeedCurveEngine.averageEffectiveSpeed((clip.sourceOutMs - sourceSplitMs).coerceAtLeast(1L), clip.speed, rightCurve)
          } else {
            clip.speed
          },
        speedCurve = rightCurve,
        videoConfig =
          clip.videoConfig?.let { cfg ->
            cfg.copy(
              transitionIn = null,
              fadeInMs = 0L,
              fadeOutMs = cfg.fadeOutMs.coerceIn(0L, rightTimelineDuration),
            )
          },
        audioConfig =
          clip.audioConfig?.let { cfg ->
            cfg.copy(
              fadeInMs = 0L,
              fadeOutMs = cfg.fadeOutMs.coerceIn(0L, rightTimelineDuration),
            )
          },
        subtitleConfig =
          if (clip.kind == ClipKind.SUBTITLE) {
            (clip.subtitleConfig ?: SubtitleClipConfig(text = rightSubText)).copy(text = rightSubText)
          } else {
            clip.subtitleConfig
          },
      )
    val rightClip =
      KeyframeTimelineOps.sliceClipKeyframes(
        clip = rightRaw,
        rangeOffsetStartMs = leftTimelineDuration,
        rangeOffsetEndMs = clip.durationMs,
        idPrefix = "${rightRaw.clipId}_splitR",
      )

    return leftClip to rightClip
  }

  /**
   * Inserts a clip on its track. When [ripple] is enabled, downstream clips are pushed forward
   * to avoid overlap and preserve sequence pacing.
   */
  fun insertClip(
    state: TimelineState,
    newClip: TimelineClip,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
  ): TimelineOperationResult {
    val targetTrack = state.tracks.find { it.trackId == newClip.trackId }
    if (targetTrack?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (newClip.durationMs < minClipDurationMs) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }

    val deltaMs = newClip.durationMs
    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }

    val shiftedClips =
      if (ripple) {
        state.clips.map { existing ->
          val shouldShiftTrack =
            when (scope) {
              RippleScope.SAME_TRACK -> existing.trackId == newClip.trackId
              RippleScope.ALL_TRACKS -> existing.trackId !in lockedTrackIds
            }
          if (shouldShiftTrack && !existing.isLocked && existing.timelineStartMs >= newClip.timelineStartMs) {
            existing.copy(
              timelineStartMs = existing.timelineStartMs + deltaMs,
              timelineEndMs = existing.timelineEndMs + deltaMs,
            )
          } else {
            existing
          }
        }
      } else {
        state.clips
      }

    val shiftedMarkers =
      if (ripple && scope == RippleScope.ALL_TRACKS) {
        state.markers.map { marker ->
          if (marker.positionMs >= newClip.timelineStartMs) {
            marker.copy(positionMs = marker.positionMs + deltaMs)
          } else {
            marker
          }
        }
      } else {
        state.markers
      }

    val nextClips = (shiftedClips + newClip).sortedBy { it.timelineStartMs }
    return TimelineOperationResult.Success(
      newState = state.copy(clips = nextClips, markers = shiftedMarkers),
      affectedClipIds = listOf(newClip.clipId),
    )
  }

  /**
   * Deletes a clip. When [ripple] is enabled, closes the resulting gap by shifting downstream
   * clips left according to [scope].
   */
  fun deleteClip(
    state: TimelineState,
    clipId: String,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }

    val remaining = state.clips.filterNot { it.clipId == clipId }
    if (!ripple) {
      return TimelineOperationResult.Success(
        newState = state.copy(clips = remaining),
        affectedClipIds = listOf(clipId),
      )
    }

    val gapMs = target.durationMs
    val pivotMs = target.timelineEndMs
    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }

    val rippledClips =
      shiftDownstreamClipsLeft(
        clips = remaining,
        pivotMs = pivotMs,
        shiftLeftMs = gapMs,
        targetTrackId = target.trackId,
        scope = scope,
        lockedTrackIds = lockedTrackIds,
      )

    val rippledMarkers =
      if (scope == RippleScope.ALL_TRACKS) {
        state.markers.map { marker ->
          if (marker.positionMs >= pivotMs) {
            marker.copy(positionMs = (marker.positionMs - gapMs).coerceAtLeast(target.timelineStartMs))
          } else {
            marker
          }
        }
      } else {
        state.markers
      }

    val adjustedPlayhead =
      if (state.playheadMs >= pivotMs) {
        (state.playheadMs - gapMs).coerceAtLeast(target.timelineStartMs)
      } else {
        state.playheadMs
      }

    return TimelineOperationResult.Success(
      newState =
        state.copy(
          playheadMs = adjustedPlayhead,
          clips = rippledClips,
          markers = rippledMarkers,
        ),
      affectedClipIds = listOf(clipId),
    )
  }

  /**
   * Trims a clip's start and/or end on the timeline while keeping `sourceInMs` / `sourceOutMs`
   * consistent. When [ripple] is enabled, shifts downstream clips by the net duration delta.
   */
  fun trimClip(
    state: TimelineState,
    clipId: String,
    newStartMs: Long? = null,
    newEndMs: Long? = null,
    maxSourceDurationMs: Long? = null,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }

    val proposedStart = (newStartMs ?: target.timelineStartMs).coerceAtLeast(0L)
    val proposedEnd = (newEndMs ?: target.timelineEndMs).coerceAtLeast(0L)
    if (proposedEnd - proposedStart < minClipDurationMs) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }

    val hasMediaSource = target.kind == ClipKind.VIDEO || target.kind == ClipKind.AUDIO
    val startDeltaTimeline = proposedStart - target.timelineStartMs
    val endDeltaTimeline = proposedEnd - target.timelineEndMs
    val oldSourceSpan = target.sourceSpanMs

    val (newSourceIn, newSourceOut, trimmedCurve) =
      if (hasMediaSource && target.hasVariableSpeed && oldSourceSpan > 0L) {
        val trimLeftSource =
          if (startDeltaTimeline >= 0L) {
            SpeedCurveEngine.timelineOffsetMsToSourceMs(
              timelineOffsetMs = startDeltaTimeline,
              sourceSpanMs = oldSourceSpan,
              baseSpeed = target.speed,
              speedCurve = target.speedCurve,
            )
          } else {
            (startDeltaTimeline * target.speed).roundToLong()
          }
        val trimEndSource =
          if (endDeltaTimeline <= 0L) {
            val endRelTimeline = (target.durationMs + endDeltaTimeline).coerceAtLeast(0L)
            SpeedCurveEngine.timelineOffsetMsToSourceMs(
              timelineOffsetMs = endRelTimeline,
              sourceSpanMs = oldSourceSpan,
              baseSpeed = target.speed,
              speedCurve = target.speedCurve,
            ) - oldSourceSpan
          } else {
            (endDeltaTimeline * target.speed).roundToLong()
          }
        val computedIn = target.sourceInMs + trimLeftSource
        val computedOut = target.sourceOutMs + trimEndSource
        val slicedCurve =
          if (computedIn >= target.sourceInMs && computedOut <= target.sourceOutMs && computedOut > computedIn) {
            SpeedCurveEngine.sliceCurveForSourceRange(
              speedCurve = target.speedCurve,
              baseSpeed = target.speed,
              oldSourceSpanMs = oldSourceSpan,
              subSourceStartMs = computedIn - target.sourceInMs,
              subSourceEndMs = computedOut - target.sourceInMs,
            )
          } else {
            target.speedCurve
          }
        Triple(computedIn, computedOut, slicedCurve)
      } else {
        val computedIn =
          if (hasMediaSource) {
            target.sourceInMs + (startDeltaTimeline * target.speed).roundToLong()
          } else {
            target.sourceInMs
          }
        val computedOut =
          if (hasMediaSource) {
            target.sourceOutMs + (endDeltaTimeline * target.speed).roundToLong()
          } else {
            target.sourceOutMs
          }
        Triple(computedIn, computedOut, target.speedCurve)
      }

    if (hasMediaSource) {
      if (newSourceIn < 0L || newSourceOut <= newSourceIn) {
        return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
      }
      if (maxSourceDurationMs != null && newSourceOut > maxSourceDurationMs) {
        return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
      }
    }

    val newDurationMs = proposedEnd - proposedStart
    val netDurationDelta = newDurationMs - target.durationMs
    val remappedTarget =
      KeyframeTimelineOps.remapClipKeyframesOnTrim(
        clip = target,
        oldStartMs = target.timelineStartMs,
        oldEndMs = target.timelineEndMs,
        newStartMs = proposedStart,
        newEndMs = proposedEnd,
      ).let { clamped ->
        clamped.copy(
          videoConfig =
            clamped.videoConfig?.let { cfg ->
              cfg.copy(
                fadeInMs = cfg.fadeInMs.coerceIn(0L, newDurationMs),
                fadeOutMs = cfg.fadeOutMs.coerceIn(0L, newDurationMs),
              )
            },
          audioConfig =
            clamped.audioConfig?.let { cfg ->
              cfg.copy(
                fadeInMs = cfg.fadeInMs.coerceIn(0L, newDurationMs),
                fadeOutMs = cfg.fadeOutMs.coerceIn(0L, newDurationMs),
              )
            },
        )
      }
    val nextEffectiveSpeed =
      if (hasMediaSource && trimmedCurve.size >= 2) {
        SpeedCurveEngine.averageEffectiveSpeed((newSourceOut - newSourceIn).coerceAtLeast(1L), target.speed, trimmedCurve)
      } else {
        target.speed
      }
    val effectiveClip =
      if (ripple && newStartMs != null && newEndMs == null) {
        // Ripple trim-in keeps clip anchored at original timelineStartMs and closes the left gap.
        remappedTarget.copy(
          timelineEndMs = target.timelineStartMs + newDurationMs,
          sourceInMs = newSourceIn,
          sourceOutMs = newSourceOut,
          speed = nextEffectiveSpeed,
          speedCurve = trimmedCurve,
        )
      } else {
        remappedTarget.copy(
          timelineStartMs = proposedStart,
          timelineEndMs = proposedEnd,
          sourceInMs = newSourceIn,
          sourceOutMs = newSourceOut,
          speed = nextEffectiveSpeed,
          speedCurve = trimmedCurve,
        )
      }

    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }
    val others = state.clips.filterNot { it.clipId == clipId }

    val updatedOthers =
      if (ripple && netDurationDelta != 0L) {
        val pivotMs = target.timelineEndMs
        if (netDurationDelta < 0L) {
          shiftDownstreamClipsLeft(
            clips = others,
            pivotMs = pivotMs,
            shiftLeftMs = -netDurationDelta,
            targetTrackId = target.trackId,
            scope = scope,
            lockedTrackIds = lockedTrackIds,
          )
        } else {
          others.map { existing ->
            val shouldShiftTrack =
              when (scope) {
                RippleScope.SAME_TRACK -> existing.trackId == target.trackId
                RippleScope.ALL_TRACKS -> existing.trackId !in lockedTrackIds
              }
            if (shouldShiftTrack && !existing.isLocked && existing.timelineStartMs >= pivotMs) {
              existing.copy(
                timelineStartMs = existing.timelineStartMs + netDurationDelta,
                timelineEndMs = existing.timelineEndMs + netDurationDelta,
              )
            } else {
              existing
            }
          }
        }
      } else {
        others
      }

    val updatedMarkers =
      if (ripple && netDurationDelta != 0L && scope == RippleScope.ALL_TRACKS) {
        state.markers.map { marker ->
          if (marker.positionMs >= target.timelineEndMs) {
            marker.copy(positionMs = (marker.positionMs + netDurationDelta).coerceAtLeast(0L))
          } else {
            marker
          }
        }
      } else {
        state.markers
      }

    return TimelineOperationResult.Success(
      newState =
        state.copy(
          clips = (updatedOthers + effectiveClip).sortedBy { it.timelineStartMs },
          markers = updatedMarkers,
        ),
      affectedClipIds = listOf(clipId),
    )
  }

  /**
   * Sets a clip's explicit source in/out range (for media clips) or timeline start/end (for
   * non-media clips) while preserving timeline continuity and optional ripple shifting.
   */
  fun trimClipSourceRange(
    state: TimelineState,
    clipId: String,
    newSourceInMs: Long,
    newSourceOutMs: Long,
    maxSourceDurationMs: Long? = null,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }

    val hasMediaSource = target.kind == ClipKind.VIDEO || target.kind == ClipKind.AUDIO
    if (!hasMediaSource) {
      val clampedStart = newSourceInMs.coerceAtLeast(0L)
      val clampedEnd = newSourceOutMs.coerceAtLeast(clampedStart + minClipDurationMs)
      return trimClip(
        state = state,
        clipId = clipId,
        newStartMs = clampedStart,
        newEndMs = clampedEnd,
        ripple = ripple,
        scope = scope,
      )
    }

    val safeIn = newSourceInMs.coerceAtLeast(0L)
    val upperBound = maxSourceDurationMs?.coerceAtLeast(minClipDurationMs) ?: Long.MAX_VALUE
    val safeOut = newSourceOutMs.coerceAtMost(upperBound)
    if (safeOut <= safeIn) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val slicedCurve =
      if (target.hasVariableSpeed && target.sourceSpanMs > 0L) {
        SpeedCurveEngine.sliceCurveForSourceRange(
          speedCurve = target.speedCurve,
          baseSpeed = target.speed,
          oldSourceSpanMs = target.sourceSpanMs,
          subSourceStartMs = (safeIn - target.sourceInMs).coerceIn(0L, target.sourceSpanMs),
          subSourceEndMs = (safeOut - target.sourceInMs).coerceIn(0L, target.sourceSpanMs),
        )
      } else {
        emptyList()
      }
    val newTimelineDuration =
      SpeedCurveEngine.renderedDurationMs(
        sourceSpanMs = safeOut - safeIn,
        baseSpeed = target.speed,
        speedCurve = slicedCurve,
      )
    if (newTimelineDuration < minClipDurationMs) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }

    val newTimelineEnd = target.timelineStartMs + newTimelineDuration
    val netDurationDelta = newTimelineDuration - target.durationMs
    val shiftStartRelMs = ((safeIn - target.sourceInMs).toDouble() / target.speed.toDouble()).roundToLong()
    val simulatedNewStart = target.timelineStartMs + shiftStartRelMs
    val simulatedNewEnd = simulatedNewStart + newTimelineDuration
    val remappedTarget =
      KeyframeTimelineOps.remapClipKeyframesOnTrim(
        clip = target,
        oldStartMs = target.timelineStartMs,
        oldEndMs = target.timelineEndMs,
        newStartMs = simulatedNewStart,
        newEndMs = simulatedNewEnd,
      )
    val effectiveClip =
      remappedTarget.copy(
        timelineEndMs = newTimelineEnd,
        sourceInMs = safeIn,
        sourceOutMs = safeOut,
        speed =
          if (slicedCurve.size >= 2) {
            SpeedCurveEngine.averageEffectiveSpeed(safeOut - safeIn, target.speed, slicedCurve)
          } else {
            target.speed
          },
        speedCurve = slicedCurve,
        videoConfig =
          remappedTarget.videoConfig?.let { cfg ->
            cfg.copy(
              fadeInMs = cfg.fadeInMs.coerceIn(0L, newTimelineDuration),
              fadeOutMs = cfg.fadeOutMs.coerceIn(0L, newTimelineDuration),
            )
          },
        audioConfig =
          remappedTarget.audioConfig?.let { cfg ->
            cfg.copy(
              fadeInMs = cfg.fadeInMs.coerceIn(0L, newTimelineDuration),
              fadeOutMs = cfg.fadeOutMs.coerceIn(0L, newTimelineDuration),
            )
          },
      )

    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }
    val others = state.clips.filterNot { it.clipId == clipId }
    val updatedOthers =
      if (ripple && netDurationDelta != 0L) {
        val pivotMs = target.timelineEndMs
        if (netDurationDelta < 0L) {
          shiftDownstreamClipsLeft(
            clips = others,
            pivotMs = pivotMs,
            shiftLeftMs = -netDurationDelta,
            targetTrackId = target.trackId,
            scope = scope,
            lockedTrackIds = lockedTrackIds,
          )
        } else {
          others.map { existing ->
            val shouldShiftTrack =
              when (scope) {
                RippleScope.SAME_TRACK -> existing.trackId == target.trackId
                RippleScope.ALL_TRACKS -> existing.trackId !in lockedTrackIds
              }
            if (shouldShiftTrack && !existing.isLocked && existing.timelineStartMs >= pivotMs) {
              existing.copy(
                timelineStartMs = existing.timelineStartMs + netDurationDelta,
                timelineEndMs = existing.timelineEndMs + netDurationDelta,
              )
            } else {
              existing
            }
          }
        }
      } else {
        others
      }

    val updatedMarkers =
      if (ripple && netDurationDelta != 0L && scope == RippleScope.ALL_TRACKS) {
        state.markers.map { marker ->
          if (marker.positionMs >= target.timelineEndMs) {
            marker.copy(positionMs = (marker.positionMs + netDurationDelta).coerceAtLeast(0L))
          } else {
            marker
          }
        }
      } else {
        state.markers
      }

    return TimelineOperationResult.Success(
      newState =
        state.copy(
          clips = (updatedOthers + effectiveClip).sortedBy { it.timelineStartMs },
          markers = updatedMarkers,
        ),
      affectedClipIds = listOf(clipId),
    )
  }

  /**
   * Non-destructively enables or disables a clip on the timeline.
   */
  fun setClipEnabled(
    state: TimelineState,
    clipId: String,
    enabled: Boolean,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }

    val updated =
      state.clips.map { clip ->
        if (clip.clipId == clipId) clip.copy(isEnabled = enabled) else clip
      }
    return TimelineOperationResult.Success(
      newState = state.copy(clips = updated),
      affectedClipIds = listOf(clipId),
    )
  }

  fun toggleClipEnabled(state: TimelineState, clipId: String): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    return setClipEnabled(state, clipId, !target.isEnabled)
  }

  fun addMarker(
    state: TimelineState,
    positionMs: Long = state.playheadMs,
    label: String,
    color: MarkerColor = MarkerColor.AMBER,
    notes: String = "",
  ): TimelineState {
    val marker =
      TimelineMarker(
        markerId = idGenerator(),
        positionMs = positionMs.coerceAtLeast(0L),
        label = label.ifBlank { "Marker" },
        color = color,
        notes = notes,
      )
    return state.copy(
      markers = (state.markers + marker).sortedBy { it.positionMs },
    )
  }

  fun updateMarker(
    state: TimelineState,
    markerId: String,
    label: String? = null,
    color: MarkerColor? = null,
    notes: String? = null,
    positionMs: Long? = null,
  ): TimelineOperationResult {
    val existing = state.markers.find { it.markerId == markerId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.MARKER_NOT_FOUND)
    val updated =
      existing.copy(
        label = label ?: existing.label,
        color = color ?: existing.color,
        notes = notes ?: existing.notes,
        positionMs = (positionMs ?: existing.positionMs).coerceAtLeast(0L),
      )
    val nextMarkers =
      state.markers.map { if (it.markerId == markerId) updated else it }.sortedBy { it.positionMs }
    return TimelineOperationResult.Success(state.copy(markers = nextMarkers))
  }

  fun removeMarker(state: TimelineState, markerId: String): TimelineOperationResult {
    if (state.markers.none { it.markerId == markerId }) {
      return TimelineOperationResult.Rejected(RejectionReason.MARKER_NOT_FOUND)
    }
    return TimelineOperationResult.Success(
      state.copy(markers = state.markers.filterNot { it.markerId == markerId }),
    )
  }

  fun findPreviousMarker(state: TimelineState): TimelineMarker? =
    state.markers.filter { it.positionMs < state.playheadMs }.maxByOrNull { it.positionMs }

  fun findNextMarker(state: TimelineState): TimelineMarker? =
    state.markers.filter { it.positionMs > state.playheadMs }.minByOrNull { it.positionMs }

  fun toggleTrackMute(state: TimelineState, trackId: String): TimelineState =
    state.copy(
      tracks =
        state.tracks.map { track ->
          if (track.trackId == trackId) track.copy(isMuted = !track.isMuted) else track
        },
    )

  fun toggleTrackSolo(state: TimelineState, trackId: String): TimelineState =
    state.copy(
      tracks =
        state.tracks.map { track ->
          if (track.trackId == trackId) track.copy(isSolo = !track.isSolo) else track
        },
    )

  fun toggleTrackLocked(state: TimelineState, trackId: String): TimelineState =
    state.copy(
      tracks =
        state.tracks.map { track ->
          if (track.trackId == trackId) track.copy(isLocked = !track.isLocked) else track
        },
    )

  fun toggleTrackVisible(state: TimelineState, trackId: String): TimelineState =
    state.copy(
      tracks =
        state.tracks.map { track ->
          if (track.trackId == trackId) track.copy(isVisible = !track.isVisible) else track
        },
    )

  fun updateTrackVolume(state: TimelineState, trackId: String, volume: Float): TimelineState {
    val safeVol = volume.coerceIn(0f, 2f)
    return state.copy(
      tracks =
        state.tracks.map { track ->
          if (track.trackId == trackId) track.copy(volume = safeVol) else track
        },
    )
  }

  /**
   * Appends a new track of [type] (or overlay [TrackType.VIDEO] track when [isOverlay] is true)
   * with a clean sequential label and normalized visual order.
   */
  fun addTrack(
    state: TimelineState,
    type: TrackType,
    isOverlay: Boolean = false,
    customName: String? = null,
  ): Pair<TimelineState, Track> {
    val existingSorted = state.tracksSorted()
    val effectiveOverlay = isOverlay && type == TrackType.VIDEO
    val countOfKind =
      existingSorted.count { track ->
        when {
          effectiveOverlay -> track.type == TrackType.VIDEO && track.isOverlay
          type == TrackType.VIDEO -> track.type == TrackType.VIDEO && !track.isOverlay
          else -> track.type == type
        }
      }
    val nextNumber = countOfKind + 1
    val defaultName =
      when {
        effectiveOverlay -> "Overlay V${nextNumber + 1}"
        type == TrackType.VIDEO -> "Video V$nextNumber"
        type == TrackType.AUDIO -> "Audio A$nextNumber"
        type == TrackType.ADJUSTMENT -> "Ajuste FX $nextNumber"
        type == TrackType.SUBTITLE -> "Subtítulos $nextNumber"
        else -> "Pista $nextNumber"
      }
    val trackIdPrefix =
      when {
        effectiveOverlay -> "t_ovl"
        type == TrackType.VIDEO -> "t_vid"
        type == TrackType.AUDIO -> "t_aud"
        type == TrackType.ADJUSTMENT -> "t_adj"
        type == TrackType.SUBTITLE -> "t_sub"
        else -> "t_trk"
      }
    val newTrackId = "${trackIdPrefix}_${idGenerator().take(6)}"
    val newTrackRaw =
      Track(
        trackId = newTrackId,
        type = type,
        name = customName?.takeIf { it.isNotBlank() } ?: defaultName,
        order = 0,
        isOverlay = effectiveOverlay,
      )

    // Visual stacking order: ADJUSTMENT -> SUBTITLE -> Overlay VIDEO -> Primary VIDEO -> AUDIO
    val bucket: (Track) -> Int = { t ->
      when {
        t.type == TrackType.ADJUSTMENT -> 0
        t.type == TrackType.SUBTITLE -> 1
        t.type == TrackType.VIDEO && t.isOverlay -> 2
        t.type == TrackType.VIDEO -> 3
        else -> 4
      }
    }

    val insertBucket = bucket(newTrackRaw)
    val reordered = mutableListOf<Track>()
    var inserted = false
    for (track in existingSorted) {
      val b = bucket(track)
      if (!inserted && b > insertBucket) {
        reordered += newTrackRaw
        inserted = true
      }
      reordered += track
    }
    if (!inserted) {
      reordered += newTrackRaw
    }

    val normalizedTracks = reordered.mapIndexed { index, track -> track.copy(order = index) }
    val finalCreated = normalizedTracks.first { it.trackId == newTrackId }
    return state.copy(tracks = normalizedTracks) to finalCreated
  }

  fun hasClipOverlapOnTrack(
    state: TimelineState,
    trackId: String,
    startMs: Long,
    endMs: Long,
    excludeClipId: String? = null,
  ): Boolean =
    state.clipsForTrack(trackId).any { existing ->
      existing.clipId != excludeClipId &&
        existing.timelineStartMs < endMs &&
        existing.timelineEndMs > startMs
    }

  fun duplicateClip(
    state: TimelineState,
    clipId: String,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }

    val durationMs = target.durationMs
    val trackClips = state.clipsForTrack(target.trackId)
    val candidateStart = target.timelineEndMs
    val newStartMs =
      if (ripple) {
        candidateStart
      } else {
        val hasOverlap =
          trackClips.any { existing ->
            existing.clipId != clipId &&
              existing.timelineStartMs < candidateStart + durationMs &&
              existing.timelineEndMs > candidateStart
          }
        if (!hasOverlap) {
          candidateStart
        } else {
          trackClips.maxOfOrNull { it.timelineEndMs } ?: candidateStart
        }
      }

    val duplicated =
      target.copy(
        clipId = idGenerator(),
        label = "${target.label} (copia)",
        timelineStartMs = newStartMs,
        timelineEndMs = newStartMs + durationMs,
        isLocked = false,
      )
    return insertClip(
      state = state,
      newClip = duplicated,
      ripple = ripple,
      scope = scope,
    )
  }

  fun setDuckingConfig(state: TimelineState, config: AudioDuckingConfig): TimelineState =
    state.copy(duckingConfig = config)

  fun updateClipAudioMix(
    state: TimelineState,
    clipId: String,
    volume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val clampedVol = volume.coerceIn(0f, 2f)
    val maxFade = target.durationMs
    val safeFadeIn = fadeInMs.coerceIn(0L, maxFade)
    val safeFadeOut = fadeOutMs.coerceIn(0L, maxFade)

    val updated =
      state.clips.map { clip ->
        if (clip.clipId != clipId) {
          clip
        } else {
          when (clip.kind) {
            ClipKind.VIDEO ->
              clip.copy(
                videoConfig =
                  (clip.videoConfig ?: VideoClipConfig()).copy(
                    volume = clampedVol,
                    fadeInMs = safeFadeIn,
                    fadeOutMs = safeFadeOut,
                  ),
              )
            ClipKind.AUDIO ->
              clip.copy(
                audioConfig =
                  (clip.audioConfig ?: AudioClipConfig()).copy(
                    volume = clampedVol,
                    fadeInMs = safeFadeIn,
                    fadeOutMs = safeFadeOut,
                  ),
              )
            else -> clip
          }
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  fun updateVideoTransform(
    state: TimelineState,
    clipId: String,
    transform: (VideoClipConfig) -> VideoClipConfig,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (target.kind != ClipKind.VIDEO) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val updated =
      state.clips.map { clip ->
        if (clip.clipId == clipId) {
          clip.copy(videoConfig = transform(clip.videoConfig ?: VideoClipConfig()))
        } else {
          clip
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  fun updateAdjustmentConfig(
    state: TimelineState,
    clipId: String,
    transform: (AdjustmentClipConfig) -> AdjustmentClipConfig,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (target.kind != ClipKind.ADJUSTMENT) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val updated =
      state.clips.map { clip ->
        if (clip.clipId == clipId) {
          clip.copy(adjustmentConfig = transform(clip.adjustmentConfig ?: AdjustmentClipConfig()))
        } else {
          clip
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  fun updateClipAudioPanAndMix(
    state: TimelineState,
    clipId: String,
    volume: Float,
    pan: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val clampedVol = volume.coerceIn(0f, 2f)
    val clampedPan = pan.coerceIn(-1f, 1f)
    val maxFade = target.durationMs
    val safeFadeIn = fadeInMs.coerceIn(0L, maxFade)
    val safeFadeOut = fadeOutMs.coerceIn(0L, maxFade)

    val updated =
      state.clips.map { clip ->
        if (clip.clipId != clipId) {
          clip
        } else {
          when (clip.kind) {
            ClipKind.VIDEO ->
              clip.copy(
                videoConfig =
                  (clip.videoConfig ?: VideoClipConfig()).copy(
                    volume = clampedVol,
                    fadeInMs = safeFadeIn,
                    fadeOutMs = safeFadeOut,
                  ),
              )
            ClipKind.AUDIO ->
              clip.copy(
                audioConfig =
                  (clip.audioConfig ?: AudioClipConfig()).copy(
                    volume = clampedVol,
                    pan = clampedPan,
                    fadeInMs = safeFadeIn,
                    fadeOutMs = safeFadeOut,
                  ),
              )
            else -> clip
          }
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  fun updateClipSpeed(
    state: TimelineState,
    clipId: String,
    newSpeed: Float,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
    preserveAudioPitch: Boolean? = null,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    if (target.kind != ClipKind.VIDEO && target.kind != ClipKind.AUDIO) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }

    val safeSpeed = newSpeed.coerceIn(0.25f, 4.0f)
    val effectiveSourceSpan =
      if (target.sourceSpanMs > 0L) {
        target.sourceSpanMs
      } else {
        (target.durationMs * target.speed).roundToLong().coerceAtLeast(minClipDurationMs)
      }
    val newDurationMs =
      (effectiveSourceSpan.toDouble() / safeSpeed.toDouble())
        .roundToLong()
        .coerceAtLeast(minClipDurationMs)
    val newEndMs = target.timelineStartMs + newDurationMs
    val netDurationDelta = newDurationMs - target.durationMs

    val rescaledTarget =
      KeyframeTimelineOps.rescaleClipKeyframesForDuration(
        clip = target,
        oldDurationMs = target.durationMs,
        newDurationMs = newDurationMs,
      )
    val updatedTarget =
      rescaledTarget.copy(
        speed = safeSpeed,
        speedCurve = emptyList(),
        timelineEndMs = newEndMs,
        videoConfig =
          if (preserveAudioPitch != null && rescaledTarget.kind == ClipKind.VIDEO) {
            (rescaledTarget.videoConfig ?: VideoClipConfig()).copy(preserveAudioPitch = preserveAudioPitch)
          } else {
            rescaledTarget.videoConfig
          },
        audioConfig =
          if (preserveAudioPitch != null && rescaledTarget.kind == ClipKind.AUDIO) {
            (rescaledTarget.audioConfig ?: AudioClipConfig()).copy(preserveAudioPitch = preserveAudioPitch)
          } else {
            rescaledTarget.audioConfig
          },
      )

    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }
    val others = state.clips.filterNot { it.clipId == clipId }
    val updatedOthers =
      if (ripple && netDurationDelta != 0L) {
        val pivotMs = target.timelineEndMs
        if (netDurationDelta < 0L) {
          shiftDownstreamClipsLeft(
            clips = others,
            pivotMs = pivotMs,
            shiftLeftMs = -netDurationDelta,
            targetTrackId = target.trackId,
            scope = scope,
            lockedTrackIds = lockedTrackIds,
          )
        } else {
          others.map { existing ->
            val shouldShiftTrack =
              when (scope) {
                RippleScope.SAME_TRACK -> existing.trackId == target.trackId
                RippleScope.ALL_TRACKS -> existing.trackId !in lockedTrackIds
              }
            if (shouldShiftTrack && !existing.isLocked && existing.timelineStartMs >= pivotMs) {
              existing.copy(
                timelineStartMs = existing.timelineStartMs + netDurationDelta,
                timelineEndMs = existing.timelineEndMs + netDurationDelta,
              )
            } else {
              existing
            }
          }
        }
      } else {
        others
      }

    return TimelineOperationResult.Success(
      newState = state.copy(clips = (updatedOthers + updatedTarget).sortedBy { it.timelineStartMs }),
      affectedClipIds = listOf(clipId),
    )
  }

  /**
   * Applies a piecewise-linear variable [speedCurve] to [clipId], analytically recomputing
   * rendered duration, rescaling keyframes, and ripple-shifting downstream clips when enabled.
   */
  fun updateClipSpeedCurve(
    state: TimelineState,
    clipId: String,
    speedCurve: List<SpeedCurvePoint>,
    ripple: Boolean = state.editOptions.rippleEnabled,
    scope: RippleScope = state.editOptions.rippleScope,
    preserveAudioPitch: Boolean? = null,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    if (target.kind != ClipKind.VIDEO && target.kind != ClipKind.AUDIO) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }

    if (speedCurve.size < 2) {
      return updateClipSpeed(
        state = state,
        clipId = clipId,
        newSpeed = target.speed,
        ripple = ripple,
        scope = scope,
        preserveAudioPitch = preserveAudioPitch,
      )
    }

    val normalizedCurve = SpeedCurveEngine.normalizeCurve(speedCurve, target.speed)
    val effectiveSourceSpan =
      if (target.sourceSpanMs > 0L) {
        target.sourceSpanMs
      } else {
        (target.durationMs * target.speed).roundToLong().coerceAtLeast(minClipDurationMs)
      }
    val newDurationMs =
      SpeedCurveEngine.renderedDurationMs(
        sourceSpanMs = effectiveSourceSpan,
        baseSpeed = target.speed,
        speedCurve = normalizedCurve,
      ).coerceAtLeast(minClipDurationMs)
    val avgSpeed =
      SpeedCurveEngine.averageEffectiveSpeed(
        sourceSpanMs = effectiveSourceSpan,
        baseSpeed = target.speed,
        speedCurve = normalizedCurve,
      )
    val newEndMs = target.timelineStartMs + newDurationMs
    val netDurationDelta = newDurationMs - target.durationMs

    val rescaledTarget =
      KeyframeTimelineOps.rescaleClipKeyframesForDuration(
        clip = target,
        oldDurationMs = target.durationMs,
        newDurationMs = newDurationMs,
      )
    val updatedTarget =
      rescaledTarget.copy(
        speed = avgSpeed,
        speedCurve = normalizedCurve,
        timelineEndMs = newEndMs,
        videoConfig =
          if (preserveAudioPitch != null && rescaledTarget.kind == ClipKind.VIDEO) {
            (rescaledTarget.videoConfig ?: VideoClipConfig()).copy(preserveAudioPitch = preserveAudioPitch)
          } else {
            rescaledTarget.videoConfig
          },
        audioConfig =
          if (preserveAudioPitch != null && rescaledTarget.kind == ClipKind.AUDIO) {
            (rescaledTarget.audioConfig ?: AudioClipConfig()).copy(preserveAudioPitch = preserveAudioPitch)
          } else {
            rescaledTarget.audioConfig
          },
      )

    val lockedTrackIds = state.tracks.filter { it.isLocked }.mapTo(mutableSetOf()) { it.trackId }
    val others = state.clips.filterNot { it.clipId == clipId }
    val updatedOthers =
      if (ripple && netDurationDelta != 0L) {
        val pivotMs = target.timelineEndMs
        if (netDurationDelta < 0L) {
          shiftDownstreamClipsLeft(
            clips = others,
            pivotMs = pivotMs,
            shiftLeftMs = -netDurationDelta,
            targetTrackId = target.trackId,
            scope = scope,
            lockedTrackIds = lockedTrackIds,
          )
        } else {
          others.map { existing ->
            val shouldShiftTrack =
              when (scope) {
                RippleScope.SAME_TRACK -> existing.trackId == target.trackId
                RippleScope.ALL_TRACKS -> existing.trackId !in lockedTrackIds
              }
            if (shouldShiftTrack && !existing.isLocked && existing.timelineStartMs >= pivotMs) {
              existing.copy(
                timelineStartMs = existing.timelineStartMs + netDurationDelta,
                timelineEndMs = existing.timelineEndMs + netDurationDelta,
              )
            } else {
              existing
            }
          }
        }
      } else {
        others
      }

    return TimelineOperationResult.Success(
      newState = state.copy(clips = (updatedOthers + updatedTarget).sortedBy { it.timelineStartMs }),
      affectedClipIds = listOf(clipId),
    )
  }

  fun updateSubtitleClip(
    state: TimelineState,
    clipId: String,
    transform: (SubtitleClipConfig) -> SubtitleClipConfig,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    if (target.kind != ClipKind.SUBTITLE) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val nextConfig = transform(target.subtitleConfig ?: SubtitleClipConfig(text = target.label))
    val nextLabel = nextConfig.text.trim().ifBlank { target.label }
    val updated =
      state.clips.map { clip ->
        if (clip.clipId == clipId) {
          clip.copy(label = nextLabel, subtitleConfig = nextConfig)
        } else {
          clip
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  /**
   * Toggles audio pitch preservation ([preservePitch]) on a video or audio clip without altering
   * clip duration or speed curve.
   */
  fun setClipPreserveAudioPitch(
    state: TimelineState,
    clipId: String,
    preservePitch: Boolean,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    if (target.kind != ClipKind.VIDEO && target.kind != ClipKind.AUDIO) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }

    val updated =
      state.clips.map { clip ->
        if (clip.clipId != clipId) {
          clip
        } else {
          when (clip.kind) {
            ClipKind.VIDEO ->
              clip.copy(
                videoConfig = (clip.videoConfig ?: VideoClipConfig()).copy(preserveAudioPitch = preservePitch),
              )
            ClipKind.AUDIO ->
              clip.copy(
                audioConfig = (clip.audioConfig ?: AudioClipConfig()).copy(preserveAudioPitch = preservePitch),
              )
            else -> clip
          }
        }
      }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  /**
   * Splits a subtitle block proportionally by word count either at [splitAtMs], at the current
   * playhead (if inside the block), or at its midpoint.
   */
  fun splitSubtitleClip(
    state: TimelineState,
    clipId: String,
    splitAtMs: Long? = null,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (target.kind != ClipKind.SUBTITLE) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    if (target.durationMs < minClipDurationMs * 2L) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }

    val minSplit = target.timelineStartMs + minClipDurationMs
    val maxSplit = target.timelineEndMs - minClipDurationMs
    val splitPoint =
      when {
        splitAtMs != null && splitAtMs in minSplit..maxSplit -> splitAtMs
        state.playheadMs in minSplit..maxSplit -> state.playheadMs
        else -> (target.timelineStartMs + target.timelineEndMs) / 2L
      }
    val (leftClip, rightClip) = splitSingleClip(target, splitPoint)
    val updated =
      state.clips.flatMap { clip ->
        if (clip.clipId == clipId) listOf(leftClip, rightClip) else listOf(clip)
      }.sortedBy { it.timelineStartMs }
    return TimelineOperationResult.Success(
      newState = state.copy(clips = updated),
      affectedClipIds = listOf(leftClip.clipId, rightClip.clipId),
    )
  }

  /**
   * Merges [firstClipId] with [secondClipId] (or the immediately following subtitle block on the
   * same track) into one contiguous subtitle clip combining both lines of text.
   */
  fun mergeAdjacentSubtitles(
    state: TimelineState,
    firstClipId: String,
    secondClipId: String? = null,
  ): TimelineOperationResult {
    val first = state.clips.find { it.clipId == firstClipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (first.kind != ClipKind.SUBTITLE) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val track = state.tracks.find { it.trackId == first.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }

    val trackSubs =
      state.clips
        .filter { it.trackId == first.trackId && it.kind == ClipKind.SUBTITLE }
        .sortedBy { it.timelineStartMs }
    val second =
      if (secondClipId != null) {
        trackSubs.find { it.clipId == secondClipId }
      } else {
        trackSubs.firstOrNull { it.clipId != first.clipId && it.timelineStartMs >= first.timelineStartMs }
      } ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)

    if (first.isLocked || second.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }

    val firstText = (first.subtitleConfig?.text ?: first.label).trim()
    val secondText = (second.subtitleConfig?.text ?: second.label).trim()
    val mergedText = listOf(firstText, secondText).filter { it.isNotEmpty() }.joinToString(" ")
    val mergedStart = minOf(first.timelineStartMs, second.timelineStartMs)
    val mergedEnd = maxOf(first.timelineEndMs, second.timelineEndMs)
    val mergedCfg = (first.subtitleConfig ?: SubtitleClipConfig(text = mergedText)).copy(text = mergedText)
    val mergedClip =
      first.copy(
        label = mergedText.lines().firstOrNull()?.take(28)?.ifBlank { first.label } ?: first.label,
        timelineStartMs = mergedStart,
        timelineEndMs = mergedEnd,
        subtitleConfig = mergedCfg,
      )
    val updated =
      state.clips
        .filterNot { it.clipId == second.clipId }
        .map { if (it.clipId == first.clipId) mergedClip else it }
        .sortedBy { it.timelineStartMs }
    return TimelineOperationResult.Success(
      newState = state.copy(clips = updated),
      affectedClipIds = listOf(first.clipId),
    )
  }

  /**
   * Adjusts start and end timestamps directly on a subtitle block without touching other tracks.
   */
  fun updateSubtitleTiming(
    state: TimelineState,
    clipId: String,
    newStartMs: Long,
    newEndMs: Long,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (target.kind != ClipKind.SUBTITLE) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val safeStart = newStartMs.coerceAtLeast(0L)
    val safeEnd = newEndMs.coerceAtLeast(safeStart + minClipDurationMs)
    if (safeEnd - safeStart < minClipDurationMs) {
      return TimelineOperationResult.Rejected(RejectionReason.SEGMENT_TOO_SHORT)
    }
    val updated =
      state.clips.map { clip ->
        if (clip.clipId == clipId) {
          clip.copy(timelineStartMs = safeStart, timelineEndMs = safeEnd)
        } else {
          clip
        }
      }.sortedBy { it.timelineStartMs }
    return TimelineOperationResult.Success(state.copy(clips = updated), listOf(clipId))
  }

  /**
   * Sets or clears a synchronized cut transition (`transitionOut` on [outgoingClipId] and
   * `transitionIn` on [incomingClipId]) clamped safely to both adjacent video clips' durations.
   */
  fun setTransitionAtCut(
    state: TimelineState,
    outgoingClipId: String,
    incomingClipId: String,
    transition: ClipTransition?,
  ): TimelineOperationResult {
    val outgoing = state.clips.find { it.clipId == outgoingClipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val incoming = state.clips.find { it.clipId == incomingClipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    if (outgoing.kind != ClipKind.VIDEO || incoming.kind != ClipKind.VIDEO) {
      return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    }
    val trackOut = state.tracks.find { it.trackId == outgoing.trackId }
    val trackIn = state.tracks.find { it.trackId == incoming.trackId }
    if (trackOut?.isLocked == true || trackIn?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (outgoing.isLocked || incoming.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }

    val maxSupportedMs = minOf(outgoing.durationMs, incoming.durationMs, 2_500L).coerceAtLeast(100L)
    val clampedTransition =
      transition?.let {
        it.copy(durationMs = it.durationMs.coerceIn(100L, maxSupportedMs))
      }

    val updated =
      state.clips.map { clip ->
        when (clip.clipId) {
          outgoingClipId ->
            clip.copy(
              videoConfig = (clip.videoConfig ?: VideoClipConfig()).copy(transitionOut = clampedTransition),
            )
          incomingClipId ->
            clip.copy(
              videoConfig = (clip.videoConfig ?: VideoClipConfig()).copy(transitionIn = clampedTransition),
            )
          else -> clip
        }
      }
    return TimelineOperationResult.Success(
      newState = state.copy(clips = updated),
      affectedClipIds = listOf(outgoingClipId, incomingClipId),
    )
  }

  /**
   * Adds or updates a keyframe at [offsetMs] relative to clip start (`0..clip.durationMs`).
   * Omitted parameters inherit the deterministically evaluated property value at [offsetMs].
   */
  fun upsertKeyframe(
    state: TimelineState,
    clipId: String,
    offsetMs: Long,
    scale: Float? = null,
    opacity: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    rotationDegrees: Float? = null,
    volume: Float? = null,
    interpolation: KeyframeInterpolation? = null,
    mergeToleranceMs: Long = 40L,
    depthBlur: Float? = null,
    depthFocusX: Float? = null,
    depthFocusY: Float? = null,
    depthFocusRange: Float? = null,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val updatedClip =
      KeyframeTimelineOps.upsertKeyframe(
        clip = target,
        offsetMs = offsetMs,
        scale = scale,
        opacity = opacity,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDegrees = rotationDegrees,
        volume = volume,
        interpolation = interpolation,
        mergeToleranceMs = mergeToleranceMs,
        idGenerator = { "kf_${idGenerator().take(8)}" },
        depthBlur = depthBlur,
        depthFocusX = depthFocusX,
        depthFocusY = depthFocusY,
        depthFocusRange = depthFocusRange,
      ) ?: return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)

    val updatedClips =
      state.clips.map { if (it.clipId == clipId) updatedClip else it }
    return TimelineOperationResult.Success(state.copy(clips = updatedClips), listOf(clipId))
  }

  /**
   * Moves an existing keyframe [keyframeId] to [newOffsetMs] within `0..clip.durationMs`.
   */
  fun moveKeyframe(
    state: TimelineState,
    clipId: String,
    keyframeId: String,
    newOffsetMs: Long,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val updatedClip =
      KeyframeTimelineOps.moveKeyframe(target, keyframeId, newOffsetMs)
        ?: return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    val updatedClips =
      state.clips.map { if (it.clipId == clipId) updatedClip else it }
    return TimelineOperationResult.Success(state.copy(clips = updatedClips), listOf(clipId))
  }

  /**
   * Removes keyframe [keyframeId] from clip [clipId].
   */
  fun removeKeyframe(
    state: TimelineState,
    clipId: String,
    keyframeId: String,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val updatedClip =
      KeyframeTimelineOps.removeKeyframe(target, keyframeId)
        ?: return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    val updatedClips =
      state.clips.map { if (it.clipId == clipId) updatedClip else it }
    return TimelineOperationResult.Success(state.copy(clips = updatedClips), listOf(clipId))
  }

  /**
   * Updates the interpolation curve for [keyframeId] inside [clipId].
   */
  fun updateKeyframeInterpolation(
    state: TimelineState,
    clipId: String,
    keyframeId: String,
    interpolation: KeyframeInterpolation,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val updatedClip =
      KeyframeTimelineOps.updateKeyframeInterpolation(target, keyframeId, interpolation)
        ?: return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    val updatedClips =
      state.clips.map { if (it.clipId == clipId) updatedClip else it }
    return TimelineOperationResult.Success(state.copy(clips = updatedClips), listOf(clipId))
  }

  /**
   * Clears all keyframes from [clipId].
   */
  fun clearKeyframes(
    state: TimelineState,
    clipId: String,
  ): TimelineOperationResult {
    val target = state.clips.find { it.clipId == clipId }
      ?: return TimelineOperationResult.Rejected(RejectionReason.CLIP_NOT_FOUND)
    val track = state.tracks.find { it.trackId == target.trackId }
    if (track?.isLocked == true) {
      return TimelineOperationResult.Rejected(RejectionReason.TRACK_LOCKED)
    }
    if (target.isLocked) {
      return TimelineOperationResult.Rejected(RejectionReason.CLIP_LOCKED)
    }
    val updatedClip =
      KeyframeTimelineOps.clearKeyframes(target)
        ?: return TimelineOperationResult.Rejected(RejectionReason.INVALID_BOUNDS)
    val updatedClips =
      state.clips.map { if (it.clipId == clipId) updatedClip else it }
    return TimelineOperationResult.Success(state.copy(clips = updatedClips), listOf(clipId))
  }

  /**
   * Finds the nearest previous keyframe of [clipId] strictly before the current playhead.
   */
  fun findPreviousKeyframe(state: TimelineState, clipId: String): ClipKeyframe? {
    val target = state.clips.find { it.clipId == clipId } ?: return null
    val playheadOffset = state.playheadMs - target.timelineStartMs
    return target.keyframes()
      .filter { it.offsetMs < playheadOffset }
      .maxByOrNull { it.offsetMs }
  }

  /**
   * Finds the nearest next keyframe of [clipId] strictly after the current playhead.
   */
  fun findNextKeyframe(state: TimelineState, clipId: String): ClipKeyframe? {
    val target = state.clips.find { it.clipId == clipId } ?: return null
    val playheadOffset = state.playheadMs - target.timelineStartMs
    return target.keyframes()
      .filter { it.offsetMs > playheadOffset }
      .minByOrNull { it.offsetMs }
  }

  private fun shiftDownstreamClipsLeft(
    clips: List<TimelineClip>,
    pivotMs: Long,
    shiftLeftMs: Long,
    targetTrackId: String,
    scope: RippleScope,
    lockedTrackIds: Set<String>,
  ): List<TimelineClip> {
    val clipsByTrack = clips.groupBy { it.trackId }
    val result = mutableListOf<TimelineClip>()

    for ((trackId, trackClips) in clipsByTrack) {
      val shouldShiftTrack =
        when (scope) {
          RippleScope.SAME_TRACK -> trackId == targetTrackId
          RippleScope.ALL_TRACKS -> trackId !in lockedTrackIds
        }
      if (!shouldShiftTrack) {
        result.addAll(trackClips)
        continue
      }

      val sorted = trackClips.sortedBy { it.timelineStartMs }
      var previousEndMs = 0L
      for (clip in sorted) {
        if (clip.isLocked || clip.timelineStartMs < pivotMs) {
          result.add(clip)
          previousEndMs = max(previousEndMs, clip.timelineEndMs)
        } else {
          val shiftedStart = (clip.timelineStartMs - shiftLeftMs).coerceAtLeast(previousEndMs)
          val shiftedEnd = shiftedStart + clip.durationMs
          val shifted = clip.copy(timelineStartMs = shiftedStart, timelineEndMs = shiftedEnd)
          result.add(shifted)
          previousEndMs = shiftedEnd
        }
      }
    }

    return result.sortedBy { it.timelineStartMs }
  }

  companion object {
    const val DEFAULT_MIN_CLIP_DURATION_MS = 100L
  }
}
