package com.example.core.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import com.example.core.media.audio.AudioFadeCurve
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.AudioWaveformCache
import com.example.core.media.audio.CachedWaveformEntry
import com.example.core.media.audio.DynamicPreviewEnvelopeAudioProcessor
import com.example.core.media.audio.EnvelopeAudioProcessor
import com.example.core.media.audio.PreviewAudioSegmentContext
import com.example.core.media.engine.CompiledCompositionResult
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.media.model.RenderSettings
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.model.AudioClipConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import com.example.core.storage.TempCacheStorageManager
import com.example.core.timeline.TimelineEngine
import com.example.core.timeline.TimelineOperationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaveformAndSyncTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun waveformGenerator_normalizesPcm16AndSlicesCleanlyAcrossCuts() {
    // 40 stereo frames: first half loud (~16384), second half quiet (~4096)
    val frameCount = 40
    val stereoPcm = ShortArray(frameCount * 2)
    for (f in 0 until frameCount) {
      val amp = if (f < 20) 16_384 else 4_096
      stereoPcm[f * 2] = amp.toShort()
      stereoPcm[f * 2 + 1] = (-amp).toShort()
    }

    val fullPeaks =
      AudioWaveformCache.computeNormalizedPeaksFromPcm16(
        samples = stereoPcm,
        channelCount = 2,
        bucketCount = 8,
      )
    assertEquals(8, fullPeaks.size)
    assertTrue(fullPeaks.all { it in 0f..1f })
    // First half buckets must stay noticeably stronger than second half buckets
    assertTrue(fullPeaks[1] > fullPeaks[6] * 2f)

    // Slicing trimmed source ranges [0..2000ms] vs [2000..4000ms] from 4000ms total
    val leftSlice =
      AudioWaveformCache.slicePeaksForSourceRange(
        fullPeaks = fullPeaks,
        sourceDurationMs = 4_000L,
        sourceInMs = 0L,
        sourceOutMs = 2_000L,
        targetBuckets = 4,
      )
    val rightSlice =
      AudioWaveformCache.slicePeaksForSourceRange(
        fullPeaks = fullPeaks,
        sourceDurationMs = 4_000L,
        sourceInMs = 2_000L,
        sourceOutMs = 4_000L,
        targetBuckets = 4,
      )
    assertEquals(4, leftSlice.size)
    assertEquals(4, rightSlice.size)
    assertTrue(leftSlice.average() > rightSlice.average() * 2.0)

    // Verify silence returns exact zeroes
    val silence = AudioWaveformCache.computeNormalizedPeaksFromPcm16(ShortArray(64), 1, 8)
    assertArrayEquals(FloatArray(8), silence, 0.0001f)
  }

  @Test
  fun waveformCache_persistsBinaryCacheAndRespectsTempCacheCleanupWithoutTouchingProjectsJson() =
    runTest {
      val cacheRoot = tempFolder.newFolder("cacheDir")
      val filesRoot = tempFolder.newFolder("filesDir")
      val projectsDir = File(filesRoot, "projects").apply { mkdirs() }
      val savedProjectJson = File(projectsDir, "proj_demo.json").apply {
        writeText("""{"projectId":"proj_demo","name":"FrameLine Demo"}""")
      }

      val storageManager = TempCacheStorageManager(cacheRoot, projectsDir)
      val wfFile = File(storageManager.waveformsDir, "asset_music_v1.wf")
      val expected = CachedWaveformEntry(floatArrayOf(0.15f, 0.85f, 0.45f, 0.92f), sourceDurationMs = 12_500L)
      AudioWaveformCache.writeWaveformFile(wfFile, expected)

      val loaded = AudioWaveformCache.readWaveformFile(wfFile)
      assertNotNull(loaded)
      assertEquals(12_500L, loaded!!.sourceDurationMs)
      assertArrayEquals(expected.peaks, loaded.peaks, 0.0001f)

      val statsBefore = storageManager.measureStorage()
      assertTrue(statsBefore.waveformsBytes > 0L)
      assertTrue(statsBefore.savedProjectsBytes > 0L)

      val statsAfter = storageManager.clearTemporaryCache()
      assertEquals(0L, statsAfter.waveformsBytes)
      assertTrue(!wfFile.exists())
      // Persistent .json in filesDir/projects MUST remain intact!
      assertTrue(savedProjectJson.exists())
      assertEquals(statsBefore.savedProjectsBytes, statsAfter.savedProjectsBytes)
    }

  @Test
  fun audioGainCurvesStereoPanAndProcessors_matchExactlyBetweenPreviewAndExport() {
    // Raised-cosine S-curve checks: 0 at start, 0.5 at midpoint, gentle shoulder easing, 1 at end
    assertEquals(0f, AudioTrackMixer.evaluateFadeCurve(0f, AudioFadeCurve.SMOOTH_S_CURVE), 0.0001f)
    assertEquals(0.5f, AudioTrackMixer.evaluateFadeCurve(0.5f, AudioFadeCurve.SMOOTH_S_CURVE), 0.001f)
    assertEquals(1f, AudioTrackMixer.evaluateFadeCurve(1f, AudioFadeCurve.SMOOTH_S_CURVE), 0.0001f)
    assertTrue(AudioTrackMixer.evaluateFadeCurve(0.25f, AudioFadeCurve.SMOOTH_S_CURVE) < 0.25f)
    assertTrue(AudioTrackMixer.evaluateFadeCurve(0.75f, AudioFadeCurve.SMOOTH_S_CURVE) > 0.75f)

    // Stereo balance checks
    val (lCenter, rCenter) = AudioTrackMixer.computeStereoChannelGains(0.8f, 0f)
    assertEquals(0.8f, lCenter, 0.0001f)
    assertEquals(0.8f, rCenter, 0.0001f)
    val (lRight, rRight) = AudioTrackMixer.computeStereoChannelGains(0.8f, 1.0f)
    assertEquals(0f, lRight, 0.0001f)
    assertEquals(0.8f, rRight, 0.0001f)

    // Compare EnvelopeAudioProcessor (export) vs DynamicPreviewEnvelopeAudioProcessor (live preview)
    val keyframes =
      listOf(
        ClipKeyframe("k0", offsetMs = 0L, volume = 0.5f, interpolation = KeyframeInterpolation.LINEAR),
        ClipKeyframe("k1", offsetMs = 1_000L, volume = 1.5f, interpolation = KeyframeInterpolation.LINEAR),
      )
    val trackGain = 0.8f
    val pan = 0.5f // right-leaning: L attenuated by 0.5x, R 1.0x

    val exportProc =
      EnvelopeAudioProcessor(
        baseVolume = 1.0f,
        fadeInMs = 500L,
        fadeOutMs = 0L,
        clipStartOnTimelineMs = 0L,
        clipDurationMs = 1_000L,
        volumeKeyframes = keyframes,
        trackVolume = trackGain,
        clipSpeed = 1.0f,
        pan = pan,
        fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
      )
    val previewProc = DynamicPreviewEnvelopeAudioProcessor()
    previewProc.updateContext(
      PreviewAudioSegmentContext(
        isTrackAudible = true,
        clipStartOnTimelineMs = 0L,
        clipDurationMs = 1_000L,
        clipSpeed = 1.0f,
        baseVolume = 1.0f,
        trackVolume = trackGain,
        fadeInMs = 500L,
        fadeOutMs = 0L,
        volumeKeyframes = keyframes,
        pan = pan,
        fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
      ),
    )

    val fmt = AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT)
    exportProc.configure(fmt)
    exportProc.flush()
    previewProc.configure(fmt)
    previewProc.flush()

    // 500 frames @ 1000 Hz = 500ms of stereo PCM-16 constant 10,000 samples
    val frames = 500
    fun makeInputBuffer(): ByteBuffer =
      ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
        repeat(frames) {
          putShort(10_000)
          putShort(10_000)
        }
        flip()
      }

    exportProc.queueInput(makeInputBuffer())
    previewProc.queueInput(makeInputBuffer())

    val exportOut = exportProc.output.order(ByteOrder.nativeOrder())
    val previewOut = previewProc.output.order(ByteOrder.nativeOrder())
    assertEquals(exportOut.remaining(), previewOut.remaining())

    // Sample-by-sample parity between Export and Preview audio processors
    while (exportOut.hasRemaining()) {
      assertEquals(exportOut.short, previewOut.short)
    }

    // Also verify mid-clip frame 250ms: fadeIn(250/500)=0.5, kfVol(250)=0.75, trackGain=0.8 -> monoGain = 0.30
    // With pan=+0.5: L = 0.15*10000 = 1500, R = 0.30*10000 = 3000
    previewProc.flush()
    previewProc.updateAnchorOffsetMs(250L)
    val checkBuf =
      ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
        putShort(10_000)
        putShort(10_000)
        flip()
      }
    previewProc.queueInput(checkBuf)
    val singleOut = previewProc.output.order(ByteOrder.nativeOrder())
    assertEquals(1_500, singleOut.short.toInt())
    assertEquals(3_000, singleOut.short.toInt())
  }

  @Test
  fun timelineEngine_protectsAvSyncSourceContinuityAndTrackVolumeAcrossMultiCutsAndTrims() {
    val engine = TimelineEngine()
    var state =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video V1", volume = 1.0f),
            Track("a1", TrackType.AUDIO, 1, "Audio A1", volume = 1.0f),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "vid_1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Escena",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L, // 6000ms source @ 1.5x = 4000ms timeline
              sourceInMs = 0L,
              sourceOutMs = 6_000L,
              speed = 1.5f,
              videoConfig =
                VideoClipConfig(
                  volume = 1.1f,
                  fadeInMs = 400L,
                  fadeOutMs = 500L,
                  keyframes =
                    listOf(
                      ClipKeyframe("kv1", 0L, volume = 0.6f),
                      ClipKeyframe("kv2", 4_000L, volume = 1.4f),
                    ),
                ),
            ),
            TimelineClip(
              clipId = "aud_1",
              trackId = "a1",
              kind = ClipKind.AUDIO,
              label = "Musica",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              speed = 1.0f,
              audioConfig = AudioClipConfig(volume = 0.8f, fadeInMs = 600L, fadeOutMs = 600L),
            ),
          ),
      )

    // Cut 1 at 1200ms (ALL tracks stay synced at 1200ms)
    state = (engine.splitAtPlayhead(state.copy(playheadMs = 1_200L)) as TimelineOperationResult.Success).newState
    // Cut 2 at 2400ms
    state = (engine.splitAtPlayhead(state.copy(playheadMs = 2_400L)) as TimelineOperationResult.Success).newState

    val videoSegs = state.clipsForTrack("v1")
    val audioSegs = state.clipsForTrack("a1")
    assertEquals(3, videoSegs.size)
    assertEquals(3, audioSegs.size)

    // Verify source bounds & timeline bounds match seamlessly without gaps or drift
    for (i in 0 until 2) {
      assertEquals(videoSegs[i].timelineEndMs, videoSegs[i + 1].timelineStartMs)
      assertEquals(videoSegs[i].sourceOutMs, videoSegs[i + 1].sourceInMs)
      assertEquals(audioSegs[i].timelineEndMs, audioSegs[i + 1].timelineStartMs)
      assertEquals(audioSegs[i].sourceOutMs, audioSegs[i + 1].sourceInMs)
    }
    // Check speed-aware source splits: 1200ms * 1.5 = 1800ms, 2400ms * 1.5 = 3600ms
    assertEquals(1_800L, videoSegs[0].sourceOutMs)
    assertEquals(3_600L, videoSegs[1].sourceOutMs)
    assertEquals(6_000L, videoSegs[2].sourceOutMs)

    // Fades at cut boundary must NOT introduce mid-cut dips: left keeps outer fadeIn, middle has 0/0, right keeps outer fadeOut
    assertEquals(400L, videoSegs[0].videoConfig?.fadeInMs)
    assertEquals(0L, videoSegs[0].videoConfig?.fadeOutMs)
    assertEquals(0L, videoSegs[1].videoConfig?.fadeInMs)
    assertEquals(0L, videoSegs[1].videoConfig?.fadeOutMs)
    assertEquals(0L, videoSegs[2].videoConfig?.fadeInMs)
    assertEquals(500L, videoSegs[2].videoConfig?.fadeOutMs)

    // Continuous keyframed volume across cut boundary 1200ms
    val leftEndVol = videoSegs[0].evaluatePropertiesAt(videoSegs[0].durationMs).volume
    val rightStartVol = videoSegs[1].evaluatePropertiesAt(0L).volume
    assertEquals(leftEndVol, rightStartVol, 0.01f)

    // Track gain clamping 0f..2f
    val boosted = engine.updateTrackVolume(state, "a1", 3.5f)
    assertEquals(2.0f, boosted.tracks.first { it.trackId == "a1" }.volume, 0.0001f)
    val clampedZero = engine.updateTrackVolume(state, "a1", -0.5f)
    assertEquals(0.0f, clampedZero.tracks.first { it.trackId == "a1" }.volume, 0.0001f)

    // Trim end clamps oversized fade
    val trimmedAudio =
      (engine.trimClip(
        state = state,
        clipId = audioSegs[2].clipId,
        newStartMs = 2_400L,
        newEndMs = 2_700L, // 300ms remaining, smaller than 600ms fadeOut
      ) as TimelineOperationResult.Success).newState.clips.first { it.clipId == audioSegs[2].clipId }
    assertTrue((trimmedAudio.audioConfig?.fadeOutMs ?: 0L) <= 300L)
  }

  @Test
  fun compositionCompilerAndPreviewController_keepAudioA1GapSyncedWithVideoV1() {
    val rootDir = tempFolder.newFolder("av_sync_proj")
    File(rootDir, "media/video.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    File(rootDir, "media/music.m4a").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(5, 6, 7, 8))
    }

    val vAsset =
      MediaAsset(
        assetId = "vid_asset",
        type = MediaType.VIDEO,
        displayName = "video.mp4",
        relativePath = "media/video.mp4",
        fingerprint = MediaFingerprint(4L, 6_000L, "vhash", "video/mp4"),
      )
    val aAsset =
      MediaAsset(
        assetId = "aud_asset",
        type = MediaType.AUDIO,
        displayName = "music.m4a",
        relativePath = "media/music.m4a",
        fingerprint = MediaFingerprint(4L, 5_000L, "ahash", "audio/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video V1", volume = 1.0f),
            Track("a1", TrackType.AUDIO, 1, "Audio A1", volume = 0.8f),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v_clip",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 6_000L,
              sourceInMs = 0L,
              sourceOutMs = 6_000L,
              assetId = "vid_asset",
            ),
            TimelineClip(
              clipId = "a_clip",
              trackId = "a1",
              kind = ClipKind.AUDIO,
              label = "A1 Late",
              timelineStartMs = 4_500L,
              timelineEndMs = 7_500L,
              sourceInMs = 500L,
              sourceOutMs = 3_500L,
              assetId = "aud_asset",
              audioConfig = AudioClipConfig(volume = 1.0f, fadeInMs = 500L, fadeOutMs = 500L),
            ),
          ),
      )

    val compiled =
      CompositionCompiler().compile(
        timeline = timeline,
        assets = listOf(vAsset, aAsset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    assertEquals(1, compiled.totalVideoItems)
    assertEquals(1, compiled.totalAudioItems)
    // Audio A1 sequence starts at 4500ms => must contain [Gap(4500ms), AudioItem] = 2 entries!
    assertEquals(2, compiled.composition.sequences.size)
    val audioSeq = compiled.composition.sequences[1]
    assertEquals(2, audioSeq.editedMediaItems.size)
    assertTrue(abs(audioSeq.editedMediaItems[0].durationUs - 4_500_000L) < 1_000L)

    // Verify PreviewPlayerController builds synchronized Video + Audio A1 windows and evaluates gain
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = PreviewPlayerController(context)
    controller.loadTimelinePreview(
      timeline = timeline,
      assets = listOf(vAsset, aAsset),
      projectRootDir = rootDir,
      renderSettings = RenderSettings(),
    )

    assertEquals(1, controller.activeAudioSegmentCount())
    assertEquals(1, controller.activeAudioGapWindowCount())
    // Inside leading 0..4500ms gap -> Audio A1 gain must be 0f
    assertEquals(0f, controller.evaluateAuxiliaryAudioGainAt(2_000L)!!, 0.0001f)
    // At 4750ms (250ms into 500ms fade-in -> 0.5f * trackVolume 0.8f = 0.4f)
    assertEquals(0.4f, controller.evaluateAuxiliaryAudioGainAt(4_750L)!!, 0.01f)

    // Muting A1 immediately silences preview gain evaluation & removes audible A1 export sequence
    val mutedTimeline = TimelineEngine().toggleTrackMute(timeline, "a1")
    controller.loadTimelinePreview(
      timeline = mutedTimeline,
      assets = listOf(vAsset, aAsset),
      projectRootDir = rootDir,
      renderSettings = RenderSettings(),
    )
    assertEquals(0f, controller.evaluateAuxiliaryAudioGainAt(4_750L)!!, 0.0001f)

    val mutedExport =
      CompositionCompiler().compile(
        timeline = mutedTimeline,
        assets = listOf(vAsset, aAsset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready
    assertEquals(0, mutedExport.totalAudioItems)

    controller.disposePermanently()
  }
}
