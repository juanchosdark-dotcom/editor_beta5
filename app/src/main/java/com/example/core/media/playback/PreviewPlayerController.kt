package com.example.core.media.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.audio.AudioFadeCurve
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.DynamicPreviewEnvelopeAudioProcessor
import com.example.core.media.audio.PreviewAudioSegmentContext
import com.example.core.media.engine.KeyframeMedia3Effects
import com.example.core.media.model.MediaUiError
import com.example.core.media.model.MediaUiErrorKind
import com.example.core.media.model.PlaybackDiagnosticNotice
import com.example.core.media.model.RenderSettings
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.EvaluatedClipProperties
import com.example.core.model.LutPreset
import com.example.core.model.MediaAsset
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

data class PreviewPlayerState(
  val isPlaying: Boolean = false,
  val isScrubbing: Boolean = false,
  val isLoopEnabled: Boolean = false,
  val hasPlayableMedia: Boolean = false,
  val activeProxyCount: Int = 0,
  val videoRotationDegrees: Int = 0,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val statusNote: String? = null,
  val diagnosticNotice: PlaybackDiagnosticNotice? = null,
  val uiError: MediaUiError? = null,
) {
  val isPortraitVideo: Boolean
    get() = videoHeight > videoWidth && videoWidth > 0
}

internal data class TimelinePlaybackSegment(
  val clipId: String,
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long,
  val speed: Float,
  val isHdrOrHighResProtected: Boolean,
  val clip: TimelineClip,
  val audioContext: PreviewAudioSegmentContext,
  val cumulativeStreamStartUs: Long = 0L,
  val preserveAudioPitch: Boolean = true,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
  val sourceSpanMs: Long = 0L,
)

internal data class AudioPlaybackSegment(
  val clipId: String,
  val trackId: String,
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long,
  val speed: Float,
  val clip: TimelineClip,
  val audioContext: PreviewAudioSegmentContext,
  val preserveAudioPitch: Boolean = true,
  val speedCurve: List<SpeedCurvePoint> = emptyList(),
  val sourceSpanMs: Long = 0L,
)

private data class TimelineGapWindow(
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
)

/**
 * Stability-hardened Media3 [ExoPlayer] controller for multitrack timeline preview.
 *
 * - Plays primary Video V1 + synchronized auxiliary Audio A1 using gap-aligned [MediaSource]
 *   timelines (`SilenceMediaSource` for leading & inter-clip gaps) so A/V tracks stay locked.
 * - Shares [AudioTrackMixer.computeClipEnvelopeGainAt] via [DynamicPreviewEnvelopeAudioProcessor]
 *   across preview and export (track volume, Mute/Solo, keyframed volume, pan, smooth S-curve
 *   fades, and ducking).
 * - Binds to [Lifecycle] via [LifecycleEventObserver] to pause on `ON_PAUSE` and release on `ON_DESTROY`.
 */
@OptIn(UnstableApi::class)
class PreviewPlayerController(
  private val context: Context,
) {

  private class AuxAudioTrackPlayer(
    val trackId: String,
    val processor: DynamicPreviewEnvelopeAudioProcessor = DynamicPreviewEnvelopeAudioProcessor(),
    var player: ExoPlayer? = null,
    var segments: List<AudioPlaybackSegment> = emptyList(),
    var gaps: List<TimelineGapWindow> = emptyList(),
    var totalTimelineMs: Long = 0L,
  )

  private var exoPlayer: ExoPlayer? = null
  private val auxTrackPlayers: MutableMap<String, AuxAudioTrackPlayer> = linkedMapOf()
  private var currentSegments: List<TimelinePlaybackSegment> = emptyList()
  private var currentVideoGaps: List<TimelineGapWindow> = emptyList()
  private var currentAudioSegments: List<AudioPlaybackSegment> = emptyList()
  private var currentAudioGaps: List<TimelineGapWindow> = emptyList()
  private var auxTotalTimelineMs: Long = 0L

  @Volatile
  private var activeSegmentSnapshot: TimelinePlaybackSegment? = null
  private val previewAudioProcessor = DynamicPreviewEnvelopeAudioProcessor()
  private var lastProfileTier: DeviceMemoryTier = DeviceMemoryTier.BALANCED
  private var boundLifecycle: Lifecycle? = null
  private var lifecycleObserver: LifecycleEventObserver? = null
  private var lastCompiledParams: LastCompileRequest? = null
  private var resumePositionMs: Long = 0L
  private var loopEnabledPreference: Boolean = false
  private var effectsDisabledFallback: Boolean = false
  private var abBypassEnabled: Boolean = false
  private var lastScrubDispatchMs: Long = Long.MIN_VALUE
  private var lastScrubWallClockMs: Long = 0L

  private data class LastCompileRequest(
    val timeline: TimelineState,
    val assets: List<MediaAsset>,
    val projectRootDir: File,
    val renderSettings: RenderSettings,
    val deviceProfile: DeviceProfile,
    val customLutPresets: List<LutPreset> = emptyList(),
  )

  private val _state = MutableStateFlow(PreviewPlayerState())
  val state: StateFlow<PreviewPlayerState> = _state.asStateFlow()

  fun getOrCreatePlayer(profile: DeviceProfile = DeviceProfile()): ExoPlayer {
    val existing = exoPlayer
    if (existing != null) return existing

    val loadControl = buildLoadControlForProfile(profile)
    val renderersFactory =
      object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
          DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(previewAudioProcessor))
            .build()
      }.setEnableDecoderFallback(true)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    return ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
      .setHandleAudioBecomingNoisy(true)
      .build()
      .also { player ->
        lastProfileTier = profile.tier
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.repeatMode =
          if (loopEnabledPreference) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              if (currentSegments.isNotEmpty()) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (!isPlaying) {
                  pauseAllAuxPlayers()
                }
              }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
              val windowIdx = player.currentMediaItemIndex
              val seg = currentSegments.firstOrNull { it.windowIndex == windowIdx }
              if (seg != null) {
                activeSegmentSnapshot = seg
                val sourcePos = player.currentPosition.coerceAtLeast(0L)
                val activeSpeed = resolveVideoPlaybackSpeed(seg, sourcePos)
                val offsetInClip = sourceOffsetToTimelineOffset(sourcePos, seg)
                previewAudioProcessor.updateContext(seg.audioContext.copy(anchorClipOffsetMs = offsetInClip))
                runCatching {
                  player.setPlaybackParameters(buildPlaybackParameters(activeSpeed, seg.preserveAudioPitch))
                }
              } else {
                activeSegmentSnapshot = null
                previewAudioProcessor.updateContext(null)
                runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
              }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
              if (videoSize.width > 0 && videoSize.height > 0) {
                val unappliedRot =
                  if (videoSize.unappliedRotationDegrees != 0) {
                    ((videoSize.unappliedRotationDegrees % 360) + 360) % 360
                  } else {
                    _state.value.videoRotationDegrees
                  }
                _state.value =
                  _state.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    videoRotationDegrees = unappliedRot,
                    uiError = null,
                  )
              }
            }

            override fun onPlayerError(error: PlaybackException) {
              handlePlaybackException(error)
            }
          },
        )
        exoPlayer = player
      }
  }

  private fun getOrCreateAuxAudioPlayerForTrack(
    holder: AuxAudioTrackPlayer,
    profile: DeviceProfile = DeviceProfile(),
  ): ExoPlayer {
    val existing = holder.player
    if (existing != null) return existing

    val loadControl = buildLoadControlForProfile(profile)
    val renderersFactory =
      object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
          DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(holder.processor))
            .build()
      }.setEnableDecoderFallback(true)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    return ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
      .build()
      .also { player ->
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.repeatMode =
          if (loopEnabledPreference) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              if (currentSegments.isEmpty() && currentAudioSegments.isNotEmpty()) {
                _state.value = _state.value.copy(isPlaying = hasAnyAuxPlaying())
              }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
              val windowIdx = player.currentMediaItemIndex
              val seg = holder.segments.firstOrNull { it.windowIndex == windowIdx }
              if (seg != null) {
                val sourcePos = player.currentPosition.coerceAtLeast(0L)
                val activeSpeed = resolveAudioPlaybackSpeed(seg, sourcePos)
                val offsetInClip = audioSourceOffsetToTimelineOffset(sourcePos, seg)
                holder.processor.updateContext(seg.audioContext.copy(anchorClipOffsetMs = offsetInClip))
                runCatching {
                  player.setPlaybackParameters(buildPlaybackParameters(activeSpeed, seg.preserveAudioPitch))
                }
              } else {
                holder.processor.updateContext(null)
                runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
              }
            }
          },
        )
        holder.player = player
      }
  }

  private fun hasAnyActiveAuxPlayer(): Boolean =
    auxTrackPlayers.values.any { it.player != null && it.segments.isNotEmpty() }

  private fun hasAnyAuxPlaying(): Boolean =
    auxTrackPlayers.values.any { it.player?.isPlaying == true }

  private fun primaryAuxPlayer(): ExoPlayer? =
    auxTrackPlayers.values.firstOrNull { it.player != null && it.segments.isNotEmpty() }?.player

  private fun pauseAllAuxPlayers() {
    auxTrackPlayers.values.forEach { holder ->
      runCatching { holder.player?.pause() }
    }
  }

  /**
   * Attaches a [LifecycleEventObserver] that pauses audio/video immediately on `ON_PAUSE`
   * and frees the hardware decoder on `ON_DESTROY`.
   */
  fun createLifecycleObserver(): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP, -> pausePlayback()
        Lifecycle.Event.ON_DESTROY -> release()
        else -> Unit
      }
    }

  fun attachToLifecycle(lifecycle: Lifecycle) {
    if (boundLifecycle === lifecycle && lifecycleObserver != null) return
    detachLifecycle()
    val observer = createLifecycleObserver()
    lifecycle.addObserver(observer)
    boundLifecycle = lifecycle
    lifecycleObserver = observer
  }

  fun detachLifecycle() {
    val observer = lifecycleObserver
    if (observer != null) {
      runCatching { boundLifecycle?.removeObserver(observer) }
    }
    lifecycleObserver = null
    boundLifecycle = null
  }

  /**
   * Restores the compiled Media3 preview after a configuration change / rotation
   * if [exoPlayer] was released during `ON_DESTROY` while the ViewModel survived.
   */
  fun restoreIfNeeded(currentPlayheadMs: Long? = null) {
    if (exoPlayer != null || hasAnyActiveAuxPlayer()) return
    val saved = lastCompiledParams ?: return
    val restoredTimeline =
      currentPlayheadMs?.let { pos ->
        saved.timeline.copy(playheadMs = pos.coerceAtLeast(0L))
      } ?: saved.timeline.copy(playheadMs = resumePositionMs.coerceAtLeast(0L))

    loadTimelinePreview(
      timeline = restoredTimeline,
      assets = saved.assets,
      projectRootDir = saved.projectRootDir,
      renderSettings = saved.renderSettings,
      deviceProfile = saved.deviceProfile,
      customLutPresets = saved.customLutPresets,
    )
  }

  fun isAbBypassEnabled(): Boolean = abBypassEnabled

  fun setAbBypassEnabled(enabled: Boolean) {
    if (abBypassEnabled == enabled) return
    abBypassEnabled = enabled
    val saved = lastCompiledParams ?: return
    if (exoPlayer == null && !hasAnyActiveAuxPlayer()) return
    val wasPlaying = exoPlayer?.isPlaying == true || hasAnyAuxPlaying()
    val posMs = currentPositionMs().coerceAtLeast(0L)
    loadTimelinePreview(
      timeline = saved.timeline.copy(playheadMs = posMs),
      assets = saved.assets,
      projectRootDir = saved.projectRootDir,
      renderSettings = saved.renderSettings,
      deviceProfile = saved.deviceProfile,
      customLutPresets = saved.customLutPresets,
    )
    if (wasPlaying) {
      runCatching {
        exoPlayer?.play()
        auxTrackPlayers.values.forEach { holder ->
          if (posMs < holder.totalTimelineMs) holder.player?.play()
        }
      }
    }
  }

  fun loadTimelinePreview(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile = DeviceProfile(),
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ) {
    val isSameMediaRequest =
      lastCompiledParams?.assets == assets &&
        lastCompiledParams?.renderSettings?.useProxiesForPreview == renderSettings.useProxiesForPreview
    if (!isSameMediaRequest) {
      effectsDisabledFallback = false
    }

    lastCompiledParams =
      LastCompileRequest(
        timeline = timeline,
        assets = assets,
        projectRootDir = projectRootDir,
        renderSettings = renderSettings,
        deviceProfile = deviceProfile,
        customLutPresets = customLutPresets,
      )
    resumePositionMs = timeline.playheadMs.coerceAtLeast(0L)

    if ((exoPlayer != null || auxTrackPlayers.isNotEmpty()) && lastProfileTier != deviceProfile.tier) {
      // Recreate ExoPlayer with updated memory buffer budget when DeviceProfile tier shifts.
      releasePlayerOnly()
    }

    val assetById = assets.associateBy { it.assetId }
    val trackAudibility = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
    val trackById = timeline.tracks.associateBy { it.trackId }
    val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)
    val mediaSourceFactory = DefaultMediaSourceFactory(context)

    val videoTrack =
      timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && !it.isOverlay && it.isVisible }
        ?: timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && it.isVisible }
    val videoClips =
      videoTrack?.let { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.VIDEO }
      }.orEmpty()

    val videoMediaSources = mutableListOf<MediaSource>()
    val videoSegments = mutableListOf<TimelinePlaybackSegment>()
    val videoGaps = mutableListOf<TimelineGapWindow>()
    var proxyCount = 0
    var hdrOrHighResCount = 0
    var missingFiles = 0
    var primaryRotation = 0
    var primaryWidth = 0
    var primaryHeight = 0
    var cumulativeStreamUs = 0L
    var videoCursorMs = 0L

    for (clip in videoClips.sortedBy { it.timelineStartMs }) {
      val asset = clip.assetId?.let { assetById[it] }
      if (asset == null) {
        missingFiles++
        continue
      }

      val resolved = resolveSafePreviewUri(asset, projectRootDir, renderSettings, deviceProfile)
      if (resolved == null) {
        missingFiles++
        continue
      }
      if (resolved.isProxy) proxyCount++
      if (resolved.isHdrOrHighResProtected) hdrOrHighResCount++
      if (videoSegments.isEmpty()) {
        primaryRotation = asset.fingerprint.rotationDegrees
        val (orientedW, orientedH) = asset.fingerprint.orientedResolution()
        primaryWidth = orientedW ?: 0
        primaryHeight = orientedH ?: 0
      }

      val gapMs = (clip.timelineStartMs - videoCursorMs).coerceAtLeast(0L)
      if (gapMs > 0L) {
        val gapIdx = videoMediaSources.size
        videoMediaSources += SilenceMediaSource(gapMs * 1_000L)
        videoGaps += TimelineGapWindow(gapIdx, videoCursorMs, clip.timelineStartMs)
        cumulativeStreamUs += gapMs * 1_000L
      }

      val startMs = clip.sourceInMs.coerceAtLeast(0L)
      val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 100L)
      val item =
        MediaItem.Builder()
          .setMediaId(clip.clipId)
          .setUri(resolved.uri)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
              .setStartPositionMs(startMs)
              .setEndPositionMs(endMs)
              .build(),
          )
          .build()

      val isTrackAudible = trackAudibility[clip.trackId] == true
      val trackVolume = trackById[clip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
      val clipDuckingWindows: List<DuckingWindow> =
        if (timeline.duckingConfig.targetTrackId == clip.trackId) {
          AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
        } else {
          emptyList()
        }
      val videoSourceSpan = (endMs - startMs).coerceAtLeast(0L)
      val audioCtx =
        PreviewAudioSegmentContext(
          isTrackAudible = isTrackAudible,
          clipStartOnTimelineMs = clip.timelineStartMs,
          clipDurationMs = clip.durationMs,
          clipSpeed = clip.speed,
          baseVolume = (clip.videoConfig?.volume ?: 1f).coerceIn(0f, 2f),
          trackVolume = trackVolume,
          fadeInMs = (clip.videoConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs),
          fadeOutMs = (clip.videoConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs),
          volumeKeyframes = clip.keyframes(),
          duckingWindows = clipDuckingWindows,
          anchorClipOffsetMs = 0L,
          pan = 0.0f,
          fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
          sourceSpanMs = videoSourceSpan,
          speedCurve = clip.speedCurve,
        )

      val windowIdx = videoMediaSources.size
      videoMediaSources += mediaSourceFactory.createMediaSource(item)
      videoSegments +=
        TimelinePlaybackSegment(
          clipId = clip.clipId,
          windowIndex = windowIdx,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineEndMs,
          sourceInMs = startMs,
          speed = clip.speed,
          isHdrOrHighResProtected = resolved.isHdrOrHighResProtected,
          clip = clip,
          audioContext = audioCtx,
          cumulativeStreamStartUs = cumulativeStreamUs,
          preserveAudioPitch = clip.videoConfig?.preserveAudioPitch ?: true,
          speedCurve = clip.speedCurve,
          sourceSpanMs = videoSourceSpan,
        )
      cumulativeStreamUs += (clip.durationMs.coerceAtLeast(100L)) * 1_000L
      videoCursorMs = maxOf(videoCursorMs, clip.timelineEndMs)
    }

    // Build synchronized multi-track Audio sequences (A1, A2, ...) with SilenceMediaSource gaps
    val audioTracksWithClips =
      timeline.tracksSorted().filter { track ->
        track.type == TrackType.AUDIO &&
          timeline.clipsForTrack(track.trackId).any { it.isEnabled && it.kind == ClipKind.AUDIO }
      }

    data class BuiltAuxTrackPlan(
      val trackId: String,
      val mediaSources: List<MediaSource>,
      val segments: List<AudioPlaybackSegment>,
      val gaps: List<TimelineGapWindow>,
      val totalTimelineMs: Long,
    )

    val builtAuxPlans = mutableListOf<BuiltAuxTrackPlan>()
    val allAuxSegments = mutableListOf<AudioPlaybackSegment>()
    val allAuxGaps = mutableListOf<TimelineGapWindow>()
    var maxAuxCursorMs = 0L

    for (audioTrack in audioTracksWithClips) {
      val audioClips =
        timeline.clipsForTrack(audioTrack.trackId)
          .filter { it.isEnabled && it.kind == ClipKind.AUDIO }
          .sortedBy { it.timelineStartMs }

      val trackSources = mutableListOf<MediaSource>()
      val trackSegments = mutableListOf<AudioPlaybackSegment>()
      val trackGaps = mutableListOf<TimelineGapWindow>()
      var trackCursorMs = 0L

      for (clip in audioClips) {
        val asset = clip.assetId?.let { assetById[it] } ?: continue
        val resolved =
          resolveSafePreviewUri(asset, projectRootDir, renderSettings, deviceProfile) ?: continue

        val gapMs = (clip.timelineStartMs - trackCursorMs).coerceAtLeast(0L)
        if (gapMs > 0L) {
          val gapIdx = trackSources.size
          trackSources += SilenceMediaSource(gapMs * 1_000L)
          val gw = TimelineGapWindow(gapIdx, trackCursorMs, clip.timelineStartMs)
          trackGaps += gw
          allAuxGaps += gw
        }

        val startMs = clip.sourceInMs.coerceAtLeast(0L)
        val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 100L)
        val item =
          MediaItem.Builder()
            .setMediaId(clip.clipId)
            .setUri(resolved.uri)
            .setClippingConfiguration(
              MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build(),
            )
            .build()

        val isTrackAudible = trackAudibility[clip.trackId] == true
        val trackVolume = trackById[clip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == clip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
          } else {
            emptyList()
          }
        val audioSourceSpan = (endMs - startMs).coerceAtLeast(0L)
        val audioCtx =
          PreviewAudioSegmentContext(
            isTrackAudible = isTrackAudible,
            clipStartOnTimelineMs = clip.timelineStartMs,
            clipDurationMs = clip.durationMs,
            clipSpeed = clip.speed,
            baseVolume = (clip.audioConfig?.volume ?: 1f).coerceIn(0f, 2f),
            trackVolume = trackVolume,
            fadeInMs = (clip.audioConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs),
            fadeOutMs = (clip.audioConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs),
            volumeKeyframes = clip.keyframes(),
            duckingWindows = clipDuckingWindows,
            anchorClipOffsetMs = 0L,
            pan = (clip.audioConfig?.pan ?: 0f).coerceIn(-1f, 1f),
            fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
            sourceSpanMs = audioSourceSpan,
            speedCurve = clip.speedCurve,
          )

        val windowIdx = trackSources.size
        trackSources += mediaSourceFactory.createMediaSource(item)
        val seg =
          AudioPlaybackSegment(
            clipId = clip.clipId,
            trackId = clip.trackId,
            windowIndex = windowIdx,
            timelineStartMs = clip.timelineStartMs,
            timelineEndMs = clip.timelineEndMs,
            sourceInMs = startMs,
            speed = clip.speed,
            clip = clip,
            audioContext = audioCtx,
            preserveAudioPitch = clip.audioConfig?.preserveAudioPitch ?: true,
            speedCurve = clip.speedCurve,
            sourceSpanMs = audioSourceSpan,
          )
        trackSegments += seg
        allAuxSegments += seg
        trackCursorMs = maxOf(trackCursorMs, clip.timelineEndMs)
      }

      if (trackSources.isNotEmpty()) {
        builtAuxPlans +=
          BuiltAuxTrackPlan(
            trackId = audioTrack.trackId,
            mediaSources = trackSources,
            segments = trackSegments,
            gaps = trackGaps,
            totalTimelineMs = trackCursorMs,
          )
        maxAuxCursorMs = maxOf(maxAuxCursorMs, trackCursorMs)
      }
    }

    if (videoMediaSources.isEmpty() && builtAuxPlans.isEmpty()) {
      runCatching {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        auxTrackPlayers.values.forEach { holder ->
          holder.processor.updateContext(null)
          holder.player?.stop()
          holder.player?.clearMediaItems()
          holder.segments = emptyList()
          holder.gaps = emptyList()
          holder.totalTimelineMs = 0L
        }
      }
      currentSegments = emptyList()
      currentVideoGaps = emptyList()
      currentAudioSegments = emptyList()
      currentAudioGaps = emptyList()
      auxTotalTimelineMs = 0L
      activeSegmentSnapshot = null
      previewAudioProcessor.updateContext(null)
      val error =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia no disponible",
            detail = "Uno o más clips vinculados no se encontraron en la carpeta portable.",
          )
        } else {
          null
        }
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = error?.detail ?: "No hay medios reproducibles en este rango.",
          uiError = error,
        )
      return
    }

    try {
      currentSegments = videoSegments
      currentVideoGaps = videoGaps
      currentAudioSegments = allAuxSegments
      currentAudioGaps = allAuxGaps
      auxTotalTimelineMs = maxAuxCursorMs

      var primaryPlayer: ExoPlayer? = null
      var effectsFailed = false

      if (videoMediaSources.isNotEmpty()) {
        val player = getOrCreatePlayer(deviceProfile)
        primaryPlayer = player
        activeSegmentSnapshot =
          videoSegments.firstOrNull { timeline.playheadMs in it.timelineStartMs until it.timelineEndMs }
            ?: videoSegments.firstOrNull()
        val previewEffects =
          if (effectsDisabledFallback || abBypassEnabled) {
            emptyList()
          } else {
            buildLivePreviewEffects(timeline, videoClips, customLutPresets)
          }
        effectsFailed =
          runCatching { player.setVideoEffects(previewEffects) }
            .onFailure {
              effectsDisabledFallback = true
              runCatching { player.setVideoEffects(emptyList()) }
            }
            .isFailure
        player.setMediaSources(videoMediaSources, /* resetPosition = */ true)
        player.prepare()
      } else {
        activeSegmentSnapshot = null
        previewAudioProcessor.updateContext(null)
        runCatching {
          exoPlayer?.stop()
          exoPlayer?.clearMediaItems()
        }
      }

      val activeAuxTrackIds = builtAuxPlans.mapTo(mutableSetOf()) { it.trackId }
      val obsoleteTracks = auxTrackPlayers.keys.filter { it !in activeAuxTrackIds }
      for (obsoleteId in obsoleteTracks) {
        auxTrackPlayers.remove(obsoleteId)?.let { oldHolder ->
          oldHolder.processor.updateContext(null)
          runCatching {
            oldHolder.player?.stop()
            oldHolder.player?.release()
          }
        }
      }
      for (plan in builtAuxPlans) {
        val holder = auxTrackPlayers.getOrPut(plan.trackId) { AuxAudioTrackPlayer(trackId = plan.trackId) }
        holder.segments = plan.segments
        holder.gaps = plan.gaps
        holder.totalTimelineMs = plan.totalTimelineMs
        val auxPlayer = getOrCreateAuxAudioPlayerForTrack(holder, deviceProfile)
        auxPlayer.setMediaSources(plan.mediaSources, /* resetPosition = */ true)
        auxPlayer.prepare()
      }

      if (primaryPlayer != null) {
        seekInternal(primaryPlayer, timeline.playheadMs, useClosestSync = false)
      } else {
        seekAuxAudioTo(timeline.playheadMs, useClosestSync = false)
      }

      val notice =
        when {
          effectsDisabledFallback || effectsFailed ->
            PlaybackDiagnosticNotice(
              message = "Compatibilidad HDR/alta res: filtros GL desactivados en vista previa para proteger el decodificador.",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 && proxyCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "HDR/alta res protegido con proxy ligero (${deviceProfile.tier.label}).",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "Clip HDR/4K detectado: reproducción protegida con fallback de decodificador.",
              isHdrProtected = true,
            )
          deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE ->
            PlaybackDiagnosticNotice(
              message = "Búfer ajustado por memoria (${deviceProfile.availableRamMb} MB libres).",
              isMemoryAdjusted = true,
            )
          else -> null
        }

      val partialMissingError =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "$missingFiles clip(s) sin archivo local",
            detail = "Se omitieron los clips ausentes para evitar bloqueos.",
          )
        } else {
          null
        }

      val activePlaying = primaryPlayer?.isPlaying ?: hasAnyAuxPlaying()
      _state.value =
        PreviewPlayerState(
          isPlaying = activePlaying,
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = true,
          activeProxyCount = proxyCount,
          videoRotationDegrees = primaryRotation,
          videoWidth = primaryWidth,
          videoHeight = primaryHeight,
          statusNote = null,
          diagnosticNotice = notice,
          uiError = partialMissingError,
        )
    } catch (t: Throwable) {
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = "No se pudo iniciar la vista previa para este clip.",
          uiError = mapThrowableToUiError(t),
        )
    }
  }

  /**
   * Deterministic evaluation of visual keyframe properties at [timelinePositionMs], shared
   * across live preview, 540p/proxy preview, standby monitor, and final export.
   */
  fun evaluateActiveClipPropertiesAt(timelinePositionMs: Long): EvaluatedClipProperties? {
    val segments = currentSegments
    if (segments.isEmpty()) return null
    val match =
      segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { timelinePositionMs >= it.timelineStartMs }
        ?: segments.first()
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return match.clip.evaluatePropertiesAt(offsetInClipMs)
  }

  /**
   * Deterministic evaluation of primary video audio gain (including keyframed volume, track
   * volume, fade-in/out, and ducking) at [timelinePositionMs].
   */
  fun evaluatePreviewAudioGainAt(timelinePositionMs: Long): Float? {
    val segments = currentSegments
    if (segments.isEmpty()) return null
    val inGap = currentVideoGaps.any { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
    val match =
      segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
        ?: if (inGap) {
          return 0f
        } else {
          segments.lastOrNull { timelinePositionMs == it.timelineEndMs } ?: return null
        }
    val ctx = match.audioContext
    if (!ctx.isTrackAudible) return 0f
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return AudioTrackMixer.computeClipEnvelopeGainAt(
      clipStartOnTimelineMs = ctx.clipStartOnTimelineMs,
      clipOffsetMs = offsetInClipMs,
      clipDurationMs = ctx.clipDurationMs,
      baseVolume = ctx.baseVolume,
      fadeInMs = ctx.fadeInMs,
      fadeOutMs = ctx.fadeOutMs,
      duckingWindows = ctx.duckingWindows,
      volumeKeyframes = ctx.volumeKeyframes,
      trackVolume = ctx.trackVolume,
      fadeCurve = ctx.fadeCurve,
    )
  }

  /**
   * Deterministic evaluation of auxiliary Track Audio A1 gain at [timelinePositionMs],
   * returning `0f` inside silent gaps or when muted/solo-suppressed, and `null` if no A1 clips exist.
   */
  fun evaluateAuxiliaryAudioGainAt(
    timelinePositionMs: Long,
    trackId: String? = null,
  ): Float? {
    val segments =
      if (trackId == null) currentAudioSegments else currentAudioSegments.filter { it.trackId == trackId }
    if (segments.isEmpty()) return null

    val match = segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
      ?: return 0f
    val ctx = match.audioContext
    if (!ctx.isTrackAudible) return 0f
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return AudioTrackMixer.computeClipEnvelopeGainAt(
      clipStartOnTimelineMs = ctx.clipStartOnTimelineMs,
      clipOffsetMs = offsetInClipMs,
      clipDurationMs = ctx.clipDurationMs,
      baseVolume = ctx.baseVolume,
      fadeInMs = ctx.fadeInMs,
      fadeOutMs = ctx.fadeOutMs,
      duckingWindows = ctx.duckingWindows,
      volumeKeyframes = ctx.volumeKeyframes,
      trackVolume = ctx.trackVolume,
      fadeCurve = ctx.fadeCurve,
    )
  }

  fun activeAudioSegmentCount(): Int = currentAudioSegments.size

  fun activeAudioGapWindowCount(): Int = currentAudioGaps.size

  internal fun buildLivePreviewEffects(
    timeline: TimelineState,
    videoClips: List<TimelineClip>,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): List<Effect> {
    if (abBypassEnabled) return emptyList()
    val activeClip =
      videoClips.firstOrNull { timeline.playheadMs in it.timelineStartMs until it.timelineEndMs }
        ?: videoClips.firstOrNull()
        ?: return emptyList()

    val adjustment =
      timeline.clips
        .firstOrNull { adj ->
          adj.isEnabled &&
            adj.kind == ClipKind.ADJUSTMENT &&
            adj.timelineStartMs < activeClip.timelineEndMs &&
            adj.timelineEndMs > activeClip.timelineStartMs
        }
        ?.adjustmentConfig

    val anyHasKeyframes = videoClips.any { it.videoConfig?.keyframes?.isNotEmpty() == true }
    val anyHasAnimatedOpacity =
      videoClips.any { clip ->
        val cfg = clip.videoConfig
        cfg != null && (cfg.opacity < 0.99f || cfg.keyframes.any { it.opacity < 0.99f })
      }

    val anyHasTransition =
      videoClips.any { clip ->
        clip.videoConfig?.transitionIn != null || clip.videoConfig?.transitionOut != null
      }

    return runCatching {
      KeyframeMedia3Effects.buildEffectsWithEvaluator(
        videoCfg = activeClip.videoConfig,
        adjustment = adjustment,
        hasKeyframesOverride = anyHasKeyframes || activeClip.videoConfig?.keyframes?.isNotEmpty() == true,
        hasAnimatedOpacityOverride = anyHasAnimatedOpacity,
        customLutPresets = customLutPresets,
        hasTransitionOverride = anyHasTransition,
        transitionEvaluator =
          if (anyHasTransition) {
            { presentationTimeUs ->
              val seg = activeSegmentSnapshot
              if (seg != null) {
                val localPresentationUs =
                  if (seg.cumulativeStreamStartUs > 0L && presentationTimeUs >= seg.cumulativeStreamStartUs) {
                    presentationTimeUs - seg.cumulativeStreamStartUs
                  } else {
                    presentationTimeUs
                  }
                KeyframeMedia3Effects.evaluateTransitionAtPresentationUs(seg.clip, localPresentationUs)
              } else {
                KeyframeMedia3Effects.evaluateTransitionAtPresentationUs(activeClip, presentationTimeUs)
              }
            }
          } else {
            null
          },
      ) { presentationTimeUs ->
        val seg = activeSegmentSnapshot
        if (seg != null) {
          val localPresentationUs =
            if (seg.cumulativeStreamStartUs > 0L && presentationTimeUs >= seg.cumulativeStreamStartUs) {
              presentationTimeUs - seg.cumulativeStreamStartUs
            } else {
              presentationTimeUs
            }
          KeyframeMedia3Effects.evaluateClipAtPresentationUs(seg.clip, localPresentationUs)
        } else {
          KeyframeMedia3Effects.evaluateClipAtPresentationUs(activeClip, presentationTimeUs)
        }
      }
    }.getOrElse { emptyList() }
  }

  fun beginScrubbing() {
    val activePlayer = exoPlayer ?: primaryAuxPlayer() ?: return
    if (_state.value.isScrubbing) return
    runCatching {
      if (activePlayer.isPlaying) activePlayer.pause()
      exoPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      auxTrackPlayers.values.forEach { holder ->
        holder.player?.let { aux ->
          if (aux.isPlaying) aux.pause()
          aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
      return
    }
    lastScrubDispatchMs = Long.MIN_VALUE
    lastScrubWallClockMs = 0L
    _state.value = _state.value.copy(isScrubbing = true, isPlaying = false)
  }

  fun scrubTo(timelinePositionMs: Long) {
    if (exoPlayer == null && !hasAnyActiveAuxPlayer()) return
    if (!_state.value.isScrubbing) {
      beginScrubbing()
    }
    val profile = lastCompiledParams?.deviceProfile ?: DeviceProfile()
    val minIntervalMs =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 48L
        DeviceMemoryTier.BALANCED -> 24L
        DeviceMemoryTier.PERFORMANCE -> 12L
      }
    val now = System.currentTimeMillis()
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    if (
      lastScrubDispatchMs != Long.MIN_VALUE &&
      abs(clamped - lastScrubDispatchMs) < 350L &&
      (now - lastScrubWallClockMs) < minIntervalMs
    ) {
      return
    }
    lastScrubDispatchMs = clamped
    lastScrubWallClockMs = now
    resumePositionMs = clamped
    runCatching {
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = true)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = true)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun endScrubbing(finalTimelinePositionMs: Long) {
    if (exoPlayer == null && !hasAnyActiveAuxPlayer()) return
    val clamped = finalTimelinePositionMs.coerceAtLeast(0L)
    lastScrubDispatchMs = Long.MIN_VALUE
    resumePositionMs = clamped
    runCatching {
      exoPlayer?.setSeekParameters(SeekParameters.EXACT)
      auxTrackPlayers.values.forEach { it.player?.setSeekParameters(SeekParameters.EXACT) }
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = false)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = false)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
    _state.value = _state.value.copy(isScrubbing = false)
  }

  fun seekTo(timelinePositionMs: Long) {
    if (exoPlayer == null && !hasAnyActiveAuxPlayer()) return
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    resumePositionMs = clamped
    val closest = _state.value.isScrubbing
    runCatching {
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = closest)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = closest)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun togglePlayPause() {
    if (!_state.value.hasPlayableMedia) return
    val primary = exoPlayer?.takeIf { currentSegments.isNotEmpty() }
    val firstAux = primaryAuxPlayer()
    val master = primary ?: firstAux ?: return

    runCatching {
      if (master.playbackState == Player.STATE_ENDED) {
        seekTo(0L)
      }
      if (master.isPlaying || hasAnyAuxPlaying()) {
        primary?.pause()
        pauseAllAuxPlayers()
      } else {
        val posMs = currentPositionMs()
        primary?.setSeekParameters(SeekParameters.DEFAULT)
        auxTrackPlayers.values.forEach { holder ->
          val aux = holder.player
          if (aux != null && holder.segments.isNotEmpty() && posMs < holder.totalTimelineMs) {
            aux.setSeekParameters(SeekParameters.DEFAULT)
            seekHolderTo(holder, posMs, useClosestSync = false)
            aux.play()
          }
        }
        primary?.play()
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(isPlaying = false, uiError = mapThrowableToUiError(err))
    }
  }

  fun setLoopEnabled(enabled: Boolean) {
    loopEnabledPreference = enabled
    val mode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    runCatching {
      exoPlayer?.repeatMode = mode
      auxTrackPlayers.values.forEach { it.player?.repeatMode = mode }
    }
    _state.value = _state.value.copy(isLoopEnabled = enabled)
  }

  fun toggleLoop(): Boolean {
    val next = !loopEnabledPreference
    setLoopEnabled(next)
    return next
  }

  fun pausePlayback() {
    val currentPos = currentPositionMs()
    resumePositionMs = currentPos
    runCatching {
      exoPlayer?.let { player ->
        if (player.isPlaying) player.pause()
      }
      pauseAllAuxPlayers()
    }
    _state.value = _state.value.copy(isPlaying = false, isScrubbing = false)
  }

  fun retryLastPreview() {
    val request = lastCompiledParams ?: return
    effectsDisabledFallback = false
    _state.value = _state.value.copy(uiError = null)
    loadTimelinePreview(
      timeline = request.timeline,
      assets = request.assets,
      projectRootDir = request.projectRootDir,
      renderSettings = request.renderSettings,
      deviceProfile = request.deviceProfile,
    )
  }

  fun clearError() {
    _state.value = _state.value.copy(uiError = null)
  }

  fun currentPositionMs(): Long {
    val primary = exoPlayer
    if (primary != null && currentSegments.isNotEmpty()) {
      return runCatching {
        val windowIdx = primary.currentMediaItemIndex
        val seg = currentSegments.firstOrNull { it.windowIndex == windowIdx }
        if (seg != null) {
          val sourcePos = primary.currentPosition.coerceAtLeast(0L)
          val timelineOffset = sourceOffsetToTimelineOffset(sourcePos, seg)
          val masterTimelineMs = (seg.timelineStartMs + timelineOffset).coerceAtMost(seg.timelineEndMs)
          if (seg.speedCurve.size >= 2 && primary.isPlaying) {
            val targetSpeed = resolveVideoPlaybackSpeed(seg, sourcePos)
            val currentParamSpeed = primary.playbackParameters.speed
            if (abs(currentParamSpeed - targetSpeed) > 0.04f) {
              runCatching {
                primary.setPlaybackParameters(buildPlaybackParameters(targetSpeed, seg.preserveAudioPitch))
              }
            }
          }
          syncAuxPlayerDriftIfNeeded(masterTimelineMs, isPrimaryPlaying = primary.isPlaying)
          masterTimelineMs
        } else {
          val gap = currentVideoGaps.firstOrNull { it.windowIndex == windowIdx }
          if (gap != null) {
            val masterTimelineMs =
              (gap.timelineStartMs + primary.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
            syncAuxPlayerDriftIfNeeded(masterTimelineMs, isPrimaryPlaying = primary.isPlaying)
            masterTimelineMs
          } else {
            primary.currentPosition.coerceAtLeast(0L)
          }
        }
      }.getOrDefault(resumePositionMs)
    }

    val firstHolder = auxTrackPlayers.values.firstOrNull { it.player != null && it.segments.isNotEmpty() }
    val aux = firstHolder?.player
    if (aux != null && firstHolder != null) {
      return runCatching {
        val windowIdx = aux.currentMediaItemIndex
        val seg = firstHolder.segments.firstOrNull { it.windowIndex == windowIdx }
        val masterMs =
          if (seg != null) {
            val sourcePos = aux.currentPosition.coerceAtLeast(0L)
            val timelineOffset = audioSourceOffsetToTimelineOffset(sourcePos, seg)
            (seg.timelineStartMs + timelineOffset).coerceAtMost(seg.timelineEndMs)
          } else {
            val gap = firstHolder.gaps.firstOrNull { it.windowIndex == windowIdx }
            if (gap != null) {
              (gap.timelineStartMs + aux.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
            } else {
              aux.currentPosition.coerceAtLeast(0L)
            }
          }
        if (aux.isPlaying) {
          syncAuxPlayerDriftIfNeeded(masterMs, isPrimaryPlaying = true, masterAuxTrackId = firstHolder.trackId)
        }
        masterMs
      }.getOrDefault(resumePositionMs)
    }

    return resumePositionMs
  }

  private fun syncAuxPlayerDriftIfNeeded(
    masterTimelineMs: Long,
    isPrimaryPlaying: Boolean,
    masterAuxTrackId: String? = null,
  ) {
    for (holder in auxTrackPlayers.values) {
      if (holder.trackId == masterAuxTrackId) continue
      val aux = holder.player ?: continue
      if (holder.segments.isEmpty()) continue
      if (!isPrimaryPlaying || masterTimelineMs >= holder.totalTimelineMs) {
        if (aux.isPlaying && masterTimelineMs >= holder.totalTimelineMs) {
          runCatching { aux.pause() }
        }
        continue
      }
      if (!aux.isPlaying) {
        seekHolderTo(holder, masterTimelineMs, useClosestSync = false)
        runCatching { aux.play() }
        continue
      }
      val auxWindowIdx = aux.currentMediaItemIndex
      val auxSeg = holder.segments.firstOrNull { it.windowIndex == auxWindowIdx }
      if (auxSeg != null && auxSeg.speedCurve.size >= 2) {
        val auxSourcePos = aux.currentPosition.coerceAtLeast(0L)
        val targetSpeed = resolveAudioPlaybackSpeed(auxSeg, auxSourcePos)
        if (abs(aux.playbackParameters.speed - targetSpeed) > 0.04f) {
          runCatching {
            aux.setPlaybackParameters(buildPlaybackParameters(targetSpeed, auxSeg.preserveAudioPitch))
          }
        }
      }
      val auxTimelineMs =
        auxSeg?.let { seg ->
          val offset = audioSourceOffsetToTimelineOffset(aux.currentPosition.coerceAtLeast(0L), seg)
          (seg.timelineStartMs + offset).coerceAtMost(seg.timelineEndMs)
        } ?: holder.gaps.firstOrNull { it.windowIndex == auxWindowIdx }?.let { gap ->
          (gap.timelineStartMs + aux.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
        } ?: continue

      if (abs(auxTimelineMs - masterTimelineMs) > A_V_SYNC_DRIFT_THRESHOLD_MS) {
        seekHolderTo(holder, masterTimelineMs, useClosestSync = false)
      }
    }
  }

  /**
   * Releases ExoPlayer and hardware decoders on `ON_DESTROY` while keeping compiled metadata
   * so configuration changes / rotation can restore preview position seamlessly.
   */
  fun release() {
    resumePositionMs = currentPositionMs()
    detachLifecycle()
    releasePlayerOnly()
    currentSegments = emptyList()
    currentVideoGaps = emptyList()
    currentAudioSegments = emptyList()
    currentAudioGaps = emptyList()
    auxTotalTimelineMs = 0L
    _state.value = PreviewPlayerState(isLoopEnabled = loopEnabledPreference)
  }

  /**
   * Permanent cleanup invoked when the owning [androidx.lifecycle.ViewModel] is cleared.
   */
  fun disposePermanently() {
    lastCompiledParams = null
    resumePositionMs = 0L
    loopEnabledPreference = false
    effectsDisabledFallback = false
    release()
  }

  private fun releasePlayerOnly() {
    runCatching {
      exoPlayer?.stop()
      exoPlayer?.clearVideoSurface()
      exoPlayer?.release()
    }
    auxTrackPlayers.values.forEach { holder ->
      runCatching {
        holder.processor.updateContext(null)
        holder.player?.stop()
        holder.player?.release()
      }
    }
    auxTrackPlayers.clear()
    exoPlayer = null
  }

  private fun seekInternal(
    player: ExoPlayer,
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    seekAuxAudioTo(clamped, useClosestSync = useClosestSync)

    val segments = currentSegments
    val gaps = currentVideoGaps
    if (segments.isEmpty() && gaps.isEmpty()) {
      player.seekTo(clamped)
      return
    }

    val matchGap = gaps.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
    if (matchGap != null) {
      activeSegmentSnapshot = null
      previewAudioProcessor.updateContext(null)
      runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
      if (useClosestSync) player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      player.seekTo(matchGap.windowIndex, (clamped - matchGap.timelineStartMs).coerceAtLeast(0L))
      return
    }

    val match =
      segments.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { clamped >= it.timelineStartMs }
        ?: segments.first()

    val offsetInClipMs =
      (clamped - match.timelineStartMs).coerceIn(0L, (match.timelineEndMs - match.timelineStartMs).coerceAtLeast(0L))
    val targetWindowPos = timelineOffsetToSourceOffset(offsetInClipMs, match)
    val activeSpeed = resolveVideoPlaybackSpeed(match, targetWindowPos)
    activeSegmentSnapshot = match
    previewAudioProcessor.updateContext(match.audioContext.copy(anchorClipOffsetMs = offsetInClipMs))
    runCatching {
      player.setPlaybackParameters(buildPlaybackParameters(activeSpeed, match.preserveAudioPitch))
    }
    if (useClosestSync) {
      player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    player.seekTo(match.windowIndex, targetWindowPos)
  }

  private fun seekAuxAudioTo(
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    auxTrackPlayers.values.forEach { holder ->
      seekHolderTo(holder, timelinePositionMs, useClosestSync)
    }
  }

  private fun seekHolderTo(
    holder: AuxAudioTrackPlayer,
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    val aux = holder.player ?: return
    val segments = holder.segments
    val gaps = holder.gaps
    if (segments.isEmpty() && gaps.isEmpty()) return

    val clamped = timelinePositionMs.coerceAtLeast(0L)
    if (clamped >= holder.totalTimelineMs && holder.totalTimelineMs > 0L) {
      holder.processor.updateContext(null)
      if (aux.isPlaying) runCatching { aux.pause() }
      return
    }

    val matchGap = gaps.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
    if (matchGap != null) {
      holder.processor.updateContext(null)
      runCatching { aux.setPlaybackParameters(PlaybackParameters.DEFAULT) }
      if (useClosestSync) aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      aux.seekTo(matchGap.windowIndex, (clamped - matchGap.timelineStartMs).coerceAtLeast(0L))
      return
    }

    val match =
      segments.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { clamped >= it.timelineStartMs }
        ?: segments.first()

    val offsetInClipMs =
      (clamped - match.timelineStartMs).coerceIn(0L, (match.timelineEndMs - match.timelineStartMs).coerceAtLeast(0L))
    val targetWindowPos = audioTimelineOffsetToSourceOffset(offsetInClipMs, match)
    val activeSpeed = resolveAudioPlaybackSpeed(match, targetWindowPos)
    holder.processor.updateContext(match.audioContext.copy(anchorClipOffsetMs = offsetInClipMs))
    runCatching {
      aux.setPlaybackParameters(buildPlaybackParameters(activeSpeed, match.preserveAudioPitch))
    }
    if (useClosestSync) {
      aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    aux.seekTo(match.windowIndex, targetWindowPos)
  }

  private fun buildPlaybackParameters(speed: Float, preservePitch: Boolean): PlaybackParameters {
    val safeSpeed = speed.coerceIn(0.25f, 4.0f)
    val pitch = if (preservePitch) 1.0f else safeSpeed
    return PlaybackParameters(safeSpeed, pitch)
  }

  private fun resolveVideoPlaybackSpeed(seg: TimelinePlaybackSegment, sourceOffsetMs: Long): Float =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.instantaneousSpeedAtSourceMs(sourceOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
    } else {
      seg.speed.coerceIn(0.25f, 4.0f)
    }

  private fun resolveAudioPlaybackSpeed(seg: AudioPlaybackSegment, sourceOffsetMs: Long): Float =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.instantaneousSpeedAtSourceMs(sourceOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
    } else {
      seg.speed.coerceIn(0.25f, 4.0f)
    }

  private fun sourceOffsetToTimelineOffset(sourceOffsetMs: Long, seg: TimelinePlaybackSegment): Long =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.sourceOffsetMsToTimelineMs(sourceOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
        .coerceIn(0L, seg.clip.durationMs)
    } else {
      (sourceOffsetMs.coerceAtLeast(0L) / seg.speed.coerceIn(0.25f, 4.0f).toDouble())
        .roundToLong()
        .coerceIn(0L, seg.clip.durationMs)
    }

  private fun timelineOffsetToSourceOffset(timelineOffsetMs: Long, seg: TimelinePlaybackSegment): Long =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.timelineOffsetMsToSourceMs(timelineOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
        .coerceAtLeast(0L)
    } else {
      (timelineOffsetMs * seg.speed.coerceIn(0.25f, 4.0f)).roundToLong().coerceAtLeast(0L)
    }

  private fun audioSourceOffsetToTimelineOffset(sourceOffsetMs: Long, seg: AudioPlaybackSegment): Long =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.sourceOffsetMsToTimelineMs(sourceOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
        .coerceIn(0L, seg.clip.durationMs)
    } else {
      (sourceOffsetMs.coerceAtLeast(0L) / seg.speed.coerceIn(0.25f, 4.0f).toDouble())
        .roundToLong()
        .coerceIn(0L, seg.clip.durationMs)
    }

  private fun audioTimelineOffsetToSourceOffset(timelineOffsetMs: Long, seg: AudioPlaybackSegment): Long =
    if (seg.speedCurve.size >= 2 && seg.sourceSpanMs > 0L) {
      SpeedCurveEngine.timelineOffsetMsToSourceMs(timelineOffsetMs, seg.sourceSpanMs, seg.speed, seg.speedCurve)
        .coerceAtLeast(0L)
    } else {
      (timelineOffsetMs * seg.speed.coerceIn(0.25f, 4.0f)).roundToLong().coerceAtLeast(0L)
    }

  private fun handlePlaybackException(error: PlaybackException) {
    val uiError = mapPlaybackExceptionToUiError(error)
    val currentReq = lastCompiledParams

    // Fallback 1: If GL VideoFrameProcessor failed (common on HDR / 10-bit surfaces), retry without GL effects
    if (
      uiError.kind == MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK &&
      !effectsDisabledFallback &&
      currentReq != null
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings,
        deviceProfile = currentReq.deviceProfile,
      )
      return
    }

    // Fallback 2: If HDR/high-res or decoder failed and proxies aren't forced yet, retry with proxy fallback
    if (
      uiError.kind == MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER &&
      currentReq != null &&
      !currentReq.renderSettings.useProxiesForPreview
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings.copy(useProxiesForPreview = true),
        deviceProfile = currentReq.deviceProfile,
      )
      _state.value =
        _state.value.copy(
          diagnosticNotice =
            PlaybackDiagnosticNotice(
              message = "Decodificador saturado: cambiado automáticamente a modo proxy compatible.",
              isMemoryAdjusted = true,
              isHdrProtected = true,
            ),
        )
      return
    }

    _state.value =
      _state.value.copy(
        isPlaying = false,
        isScrubbing = false,
        statusNote = uiError.detail,
        uiError = uiError,
      )
  }

  private data class ResolvedPreviewSource(
    val uri: Uri,
    val isProxy: Boolean,
    val isHdrOrHighResProtected: Boolean,
  )

  private fun resolveSafePreviewUri(
    asset: MediaAsset,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile,
  ): ResolvedPreviewSource? {
    val shouldPreferProxy =
      renderSettings.useProxiesForPreview ||
        (asset.fingerprint.isHdr && deviceProfile.tier != DeviceMemoryTier.PERFORMANCE) ||
        (asset.fingerprint.isHighResolution && deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE)

    val isProtected = asset.fingerprint.isHdr || asset.fingerprint.isHighResolution

    if (shouldPreferProxy && !asset.proxyRelativePath.isNullOrBlank()) {
      val proxyFile = File(projectRootDir, asset.proxyRelativePath)
      if (proxyFile.exists() && proxyFile.length() > 0L) {
        return ResolvedPreviewSource(
          uri = Uri.fromFile(proxyFile),
          isProxy = true,
          isHdrOrHighResProtected = isProtected,
        )
      }
    }

    val localFile = File(projectRootDir, asset.relativePath)
    if (localFile.exists() && localFile.length() > 0L) {
      return ResolvedPreviewSource(
        uri = Uri.fromFile(localFile),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }

    val extUri = asset.externalSourceUri
    if (!extUri.isNullOrBlank()) {
      return ResolvedPreviewSource(
        uri = Uri.parse(extUri),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }
    return null
  }

  companion object {
    private const val A_V_SYNC_DRIFT_THRESHOLD_MS = 60L

    fun buildLoadControlForProfile(profile: DeviceProfile): DefaultLoadControl {
      val (minBufferMs, maxBufferMs) =
        when (profile.tier) {
          DeviceMemoryTier.CONSERVATIVE -> 1_500 to 4_000
          DeviceMemoryTier.BALANCED -> 2_500 to 8_000
          DeviceMemoryTier.PERFORMANCE -> 5_000 to 15_000
        }
      return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          minBufferMs,
          maxBufferMs,
          /* bufferForPlaybackMs = */ 500,
          /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    }

    fun mapPlaybackExceptionToUiError(error: PlaybackException): MediaUiError =
      mapPlaybackErrorCodeToUiError(
        errorCode = error.errorCode,
        errorCodeName = error.errorCodeName,
        cause = error.cause,
      )

    fun mapPlaybackErrorCodeToUiError(
      errorCode: Int,
      errorCodeName: String = "",
      cause: Throwable? = null,
    ): MediaUiError {
      if (cause is OutOfMemoryError) {
        return MediaUiError(
          kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
          title = "Límite de memoria alcanzado",
          detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
        )
      }
      return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia inaccesible",
            detail =
              if (errorCodeName.isNotBlank()) {
                "No se puede leer el clip desde almacenamiento ($errorCodeName)."
              } else {
                "No se puede leer el clip desde almacenamiento."
              },
          )

        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, ->
          MediaUiError(
            kind = MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK,
            title = "Compatibilidad HDR / GPU",
            detail = "El procesador de video no toleró los efectos sobre este formato; usa modo proxy si persiste.",
          )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, ->
          MediaUiError(
            kind = MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER,
            title = "Formato o decodificador no soportado",
            detail = "El decodificador de hardware rechazó este clip (prueba generar proxies 540p).",
          )

        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Error de reproducción",
            detail = "No se pudo reproducir el video seleccionado.",
          )
      }
    }

    fun mapThrowableToUiError(t: Throwable): MediaUiError =
      when (t) {
        is OutOfMemoryError ->
          MediaUiError(
            kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
            title = "Límite de memoria alcanzado",
            detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
          )
        is SecurityException ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Acceso al archivo denegado",
            detail = "Vuelve a seleccionar el video desde Medios para renovar el acceso.",
          )
        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Fallo al preparar vista previa",
            detail = "No se pudo preparar la vista previa del video seleccionado.",
          )
      }
  }
}
