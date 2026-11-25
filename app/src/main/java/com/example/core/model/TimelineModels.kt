package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Complete state of the multitrack timeline, including tracks, clips, markers, and editing modes.
 */
@JsonClass(generateAdapter = true)
data class TimelineState(
  val playheadMs: Long = 0L,
  val tracks: List<Track> = emptyList(),
  val clips: List<TimelineClip> = emptyList(),
  val markers: List<TimelineMarker> = emptyList(),
  val editOptions: EditModeOptions = EditModeOptions(),
  val duckingConfig: AudioDuckingConfig = AudioDuckingConfig(),
) {
  val totalDurationMs: Long
    get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L

  fun tracksSorted(): List<Track> = tracks.sortedBy { it.order }

  fun clipsForTrack(trackId: String): List<TimelineClip> =
    clips.filter { it.trackId == trackId }.sortedBy { it.timelineStartMs }

  fun activeClipsAt(positionMs: Long): List<TimelineClip> =
    clips.filter { it.isEnabled && positionMs in it.timelineStartMs until it.timelineEndMs }
}

/**
 * Supported track categories in the multitrack timeline.
 */
enum class TrackType {
  VIDEO,
  AUDIO,
  SUBTITLE,
  ADJUSTMENT,
}

enum class OverlayContentKind {
  NONE,
  IMAGE,
  STICKER,
  LOGO,
  VIDEO_PIP,
}

@JsonClass(generateAdapter = true)
data class Track(
  val trackId: String,
  val type: TrackType,
  val order: Int,
  val name: String,
  val isMuted: Boolean = false,
  val isSolo: Boolean = false,
  val isLocked: Boolean = false,
  val isVisible: Boolean = true,
  val volume: Float = 1.0f,
  val isOverlay: Boolean = false,
)

enum class DepthBlurMaskMode {
  RADIAL,
  LINEAR,
  FOREGROUND_SUBJECT,
}

enum class ClipKind {
  VIDEO,
  AUDIO,
  SUBTITLE,
  ADJUSTMENT,
}

@JsonClass(generateAdapter = true)
data class SpeedCurvePoint(
  val normalizedTime: Float,
  val speed: Float,
)

/**
 * Non-destructive timeline clip supporting Video, Audio, Subtitles, and Adjustment Layers.
 *
 * Disabled clips (`isEnabled == false`) stay in place on the timeline with all edits intact
 * but are bypassed during preview playback and final export.
 */
@JsonClass(generateAdapter = true)
data class TimelineClip(
  val clipId: String,
  val trackId: String,
  val kind: ClipKind,
  val label: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long = 0L,
  val sourceOutMs: Long = 0L,
  val speed: Float = 1.0f,
  val isEnabled: Boolean = true,
  val isLocked: Boolean = false,
  val assetId: String? = null,
  val videoConfig: VideoClipConfig? = null,
  val audioConfig: AudioClipConfig? = null,
  val subtitleConfig: SubtitleClipConfig? = null,
  val adjustmentConfig: AdjustmentClipConfig? = null,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
) {
  init {
    require(timelineStartMs >= 0L) { "timelineStartMs must be >= 0" }
    require(timelineEndMs > timelineStartMs) { "timelineEndMs must be > timelineStartMs" }
    require(speed > 0f) { "speed must be > 0" }
  }

  val hasVariableSpeed: Boolean
    get() = speedCurve.size >= 2

  val durationMs: Long
    get() = timelineEndMs - timelineStartMs

  val sourceSpanMs: Long
    get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)

  fun containsTimelinePosition(positionMs: Long): Boolean =
    positionMs in timelineStartMs until timelineEndMs

  fun keyframes(): List<ClipKeyframe> =
    when (kind) {
      ClipKind.VIDEO -> videoConfig?.keyframes.orEmpty()
      ClipKind.AUDIO -> audioConfig?.keyframes.orEmpty()
      else -> emptyList()
    }

  fun evaluatePropertiesAt(offsetMs: Long): EvaluatedClipProperties =
    when (kind) {
      ClipKind.VIDEO -> {
        val cfg = videoConfig ?: VideoClipConfig()
        ClipKeyframeEvaluator.evaluateAt(
          offsetMs = offsetMs,
          keyframes = cfg.keyframes,
          baseScale = cfg.scale,
          baseOpacity = cfg.opacity,
          baseOffsetX = cfg.offsetX,
          baseOffsetY = cfg.offsetY,
          baseRotationDegrees = cfg.rotationDegrees,
          baseVolume = cfg.volume,
          baseDepthBlur = cfg.depthBlur,
          baseDepthFocusX = cfg.depthFocusX,
          baseDepthFocusY = cfg.depthFocusY,
          baseDepthFocusRange = cfg.depthFocusRange,
        )
      }
      ClipKind.AUDIO -> {
        val cfg = audioConfig ?: AudioClipConfig()
        ClipKeyframeEvaluator.evaluateAt(
          offsetMs = offsetMs,
          keyframes = cfg.keyframes,
          baseVolume = cfg.volume,
        )
      }
      else ->
        EvaluatedClipProperties(
          offsetMs = offsetMs.coerceAtLeast(0L),
          scale = 1.0f,
          opacity = 1.0f,
          offsetX = 0.0f,
          offsetY = 0.0f,
          rotationDegrees = 0.0f,
          volume = 1.0f,
        )
    }
}

@JsonClass(generateAdapter = true)
data class ClipKeyframe(
  val keyframeId: String,
  val offsetMs: Long,
  val scale: Float = 1.0f,
  val opacity: Float = 1.0f,
  val offsetX: Float = 0.0f,
  val offsetY: Float = 0.0f,
  val rotationDegrees: Float = 0.0f,
  val volume: Float? = null,
  val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
  val depthBlur: Float? = null,
  val depthFocusX: Float? = null,
  val depthFocusY: Float? = null,
  val depthFocusRange: Float? = null,
)

@JsonClass(generateAdapter = true)
data class VideoClipConfig(
  val volume: Float = 1.0f,
  val fadeInMs: Long = 0L,
  val fadeOutMs: Long = 0L,
  val opacity: Float = 1.0f,
  val scale: Float = 1.0f,
  val offsetX: Float = 0.0f,
  val offsetY: Float = 0.0f,
  val rotationDegrees: Float = 0.0f,
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val lutPresetId: String? = null,
  val lutStrength: Float = 1.0f,
  val transitionIn: ClipTransition? = null,
  val transitionOut: ClipTransition? = null,
  val keyframes: List<ClipKeyframe> = emptyList(),
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
  val depthBlur: Float = 0.0f,
  val whites: Float = 0.0f,
  val blacks: Float = 0.0f,
  val vibrance: Float = 0.0f,
  val liftX: Float = 0.0f,
  val liftY: Float = 0.0f,
  val gammaX: Float = 0.0f,
  val gammaY: Float = 0.0f,
  val gainX: Float = 0.0f,
  val gainY: Float = 0.0f,
  val depthBlurRadius: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
  val depthBlurFeather: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
  val depthBlurMode: DepthBlurMaskMode = DepthBlurMaskMode.RADIAL,
  val depthFocusX: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusY: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusRange: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
  val preserveAudioPitch: Boolean = true,
  val overlayKind: OverlayContentKind = OverlayContentKind.NONE,
  val stickerId: String? = null,
  val chromaticAberration: Float = 0.0f,
  val glitchIntensity: Float = 0.0f,
  val cropLeft: Float = 0.0f,
  val cropTop: Float = 0.0f,
  val cropRight: Float = 1.0f,
  val cropBottom: Float = 1.0f,
) {
  val isOverlayContent: Boolean
    get() = overlayKind != OverlayContentKind.NONE || !stickerId.isNullOrBlank()

  val hasCrop: Boolean
    get() = cropLeft > 0.005f || cropTop > 0.005f || cropRight < 0.995f || cropBottom < 0.995f

  fun evaluatePropertiesAt(offsetMs: Long): EvaluatedClipProperties =
    ClipKeyframeEvaluator.evaluateAt(
      offsetMs = offsetMs,
      keyframes = keyframes,
      baseScale = scale,
      baseOpacity = opacity,
      baseOffsetX = offsetX,
      baseOffsetY = offsetY,
      baseRotationDegrees = rotationDegrees,
      baseVolume = volume,
      baseDepthBlur = depthBlur,
      baseDepthFocusX = depthFocusX,
      baseDepthFocusY = depthFocusY,
      baseDepthFocusRange = depthFocusRange,
    )

  fun evaluateTransformAt(offsetMs: Long): ClipKeyframe {
    val evaluated = evaluatePropertiesAt(offsetMs)
    val id = if (keyframes.isEmpty()) "base" else "interp_${evaluated.offsetMs}"
    val hasDepth =
      keyframes.any {
        it.depthBlur != null ||
          it.depthFocusX != null ||
          it.depthFocusY != null ||
          it.depthFocusRange != null
      }
    return evaluated.toClipKeyframe(
      keyframeId = id,
      includeVolume = keyframes.any { it.volume != null },
      includeDepth = hasDepth,
    )
  }
}

@JsonClass(generateAdapter = true)
data class AudioClipConfig(
  val volume: Float = 1.0f,
  val pan: Float = 0.0f,
  val fadeInMs: Long = 0L,
  val fadeOutMs: Long = 0L,
  val keyframes: List<ClipKeyframe> = emptyList(),
  val preserveAudioPitch: Boolean = true,
) {
  fun evaluateAudioAt(offsetMs: Long): EvaluatedClipProperties =
    ClipKeyframeEvaluator.evaluateAt(
      offsetMs = offsetMs,
      keyframes = keyframes,
      baseVolume = volume,
    )
}

@JsonClass(generateAdapter = true)
data class SubtitleClipConfig(
  val text: String,
  val fontFamily: String = "sans-serif",
  val fontSizeSp: Float = 20f,
  val textColorHex: String = "#FFFFFF",
  val backgroundColorHex: String = "#80000000",
  val alignment: SubtitleAlignment = SubtitleAlignment.BOTTOM_CENTER,
  val outlineColorHex: String = "#CC000000",
  val outlineWidthDp: Float = 2.0f,
  val verticalOffsetNormalized: Float = 0.0f,
  val animation: SubtitleAnimation = SubtitleAnimation.NONE,
)

enum class SubtitleAlignment {
  BOTTOM_CENTER,
  MIDDLE_CENTER,
  TOP_CENTER,
}

enum class SubtitleAnimation {
  NONE,
  FADE,
  POP,
  SLIDE_UP,
}

@JsonClass(generateAdapter = true)
data class AdjustmentClipConfig(
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val lutRelativePath: String? = null,
  val lutPresetId: String? = null,
  val lutStrength: Float = 1.0f,
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
  val blendOpacity: Float = 1.0f,
  val depthBlur: Float = 0.0f,
  val whites: Float = 0.0f,
  val blacks: Float = 0.0f,
  val vibrance: Float = 0.0f,
  val liftX: Float = 0.0f,
  val liftY: Float = 0.0f,
  val gammaX: Float = 0.0f,
  val gammaY: Float = 0.0f,
  val gainX: Float = 0.0f,
  val gainY: Float = 0.0f,
  val depthBlurRadius: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
  val depthBlurFeather: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
  val depthBlurMode: DepthBlurMaskMode = DepthBlurMaskMode.RADIAL,
  val depthFocusX: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusY: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val depthFocusRange: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
  val chromaticAberration: Float = 0.0f,
  val glitchIntensity: Float = 0.0f,
)

@JsonClass(generateAdapter = true)
data class ClipTransition(
  val type: TransitionType,
  val durationMs: Long,
)

enum class TransitionType {
  CROSS_DISSOLVE,
  FADE_BLACK,
  FADE_WHITE,
  SLIDE_LEFT,
  SLIDE_RIGHT,
  WIPE_LEFT,
  WIPE_RIGHT,
}

/**
 * Editorial timeline marker anchored to a timestamp in milliseconds.
 */
@JsonClass(generateAdapter = true)
data class TimelineMarker(
  val markerId: String,
  val positionMs: Long,
  val label: String,
  val color: MarkerColor = MarkerColor.AMBER,
  val notes: String = "",
)

enum class MarkerColor {
  AMBER,
  CYAN,
  CORAL,
  EMERALD,
  VIOLET,
}

/**
 * Editing behavior toggles including Ripple Edit mode and scope.
 */
@JsonClass(generateAdapter = true)
data class EditModeOptions(
  val rippleEnabled: Boolean = false,
  val rippleScope: RippleScope = RippleScope.SAME_TRACK,
  val snapToMarkers: Boolean = true,
)

enum class RippleScope {
  /** Ripple shifts only downstream clips on the track being edited. */
  SAME_TRACK,

  /** Ripple shifts downstream clips across all unlocked tracks and moves markers accordingly. */
  ALL_TRACKS,
}
