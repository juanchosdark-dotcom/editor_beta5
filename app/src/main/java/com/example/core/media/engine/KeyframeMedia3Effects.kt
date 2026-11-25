package com.example.core.media.engine

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.Matrix as GlMatrix
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipTransition
import com.example.core.model.ColorGradeBounds
import com.example.core.model.ColorGradeStageParams
import com.example.core.model.ColorGradingEngine
import com.example.core.model.DepthBlurMaskMode
import com.example.core.model.EvaluatedClipProperties
import com.example.core.model.LutPreset
import com.example.core.model.SpeedCurveEngine
import com.example.core.model.SpeedCurvePoint
import com.example.core.model.TimelineClip
import com.example.core.model.TransitionType
import com.example.core.model.VideoClipConfig
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Time-varying Media3 [RgbMatrix] shader effect applying keyframed clip opacity
 * deterministically at each frame's `presentationTimeUs` across preview and export.
 */
@OptIn(UnstableApi::class)
class AnimatedOpacityRgbEffect(
  private val opacityProvider: (presentationTimeUs: Long) -> Float,
) : RgbMatrix {

  override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
    val alpha = opacityProvider(presentationTimeUs).coerceIn(0.05f, 1.0f)
    return FloatArray(16).apply {
      GlMatrix.setIdentityM(this, 0)
      GlMatrix.scaleM(this, 0, alpha, alpha, alpha)
    }
  }

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Resolved frame parameters for [SelectiveDepthBlurEffect] supporting rack-focus keyframes and
 * consistent optical bokeh across 540p proxy preview and 1080p export.
 */
data class SelectiveDepthBlurParams(
  val strength: Float = 0.0f,
  val radiusPx540p: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS,
  val feather: Float = ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER,
  val mode: DepthBlurMaskMode = DepthBlurMaskMode.RADIAL,
  val focusX: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val focusY: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_COORD,
  val focusRange: Float = ColorGradeBounds.DEFAULT_DEPTH_FOCUS_RANGE,
)

/**
 * OpenGL ES Media3 [GlEffect] implementing optical lens blur with radial, linear (tilt-shift),
 * and foreground-subject coherence masking plus per-frame rack-focus keyframe interpolation.
 */
@OptIn(UnstableApi::class)
class SelectiveDepthBlurEffect(
  private val paramsProvider: (presentationTimeUs: Long) -> SelectiveDepthBlurParams,
) : GlEffect {

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
    SelectiveDepthBlurShaderProgram(useHdr = useHdr, paramsProvider = paramsProvider)

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class SelectiveDepthBlurShaderProgram(
  useHdr: Boolean,
  private val paramsProvider: (presentationTimeUs: Long) -> SelectiveDepthBlurParams,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

  private var width: Int = 540
  private var height: Int = 540

  private val glProgram: GlProgram =
    try {
      GlProgram(VERTEX_SHADER_GLSL, FRAGMENT_SHADER_GLSL).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    width = inputWidth.coerceAtLeast(1)
    height = inputHeight.coerceAtLeast(1)
    return Size(width, height)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    val p = paramsProvider(presentationTimeUs)
    val scaledRadiusPx =
      KeyframeMedia3Effects.resolveDepthBlurRadiusForHeight(
        baseRadiusPx540p = p.radiusPx540p,
        blurStrength = p.strength,
        frameHeight = height,
      )
    val modeIndex =
      when (p.mode) {
        DepthBlurMaskMode.RADIAL -> 0
        DepthBlurMaskMode.LINEAR -> 1
        DepthBlurMaskMode.FOREGROUND_SUBJECT -> 2
      }
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / width.toFloat(), 1f / height.toFloat()))
      glProgram.setFloatUniform("uBlurStrength", p.strength.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uBlurRadiusPx", scaledRadiusPx)
      glProgram.setFloatUniform("uFeather", ColorGradeBounds.clampDepthBlurFeather(p.feather))
      glProgram.setFloatsUniform(
        "uFocusPoint",
        floatArrayOf(
          ColorGradeBounds.clampDepthFocusCoord(p.focusX),
          ColorGradeBounds.clampDepthFocusCoord(p.focusY),
        ),
      )
      glProgram.setFloatUniform("uFocusRange", ColorGradeBounds.clampDepthFocusRange(p.focusRange))
      glProgram.setIntUniform("uMaskMode", modeIndex)
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      glProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  companion object {
    private const val VERTEX_SHADER_GLSL = """
      #version 100
      attribute vec4 aFramePosition;
      varying vec2 vTexSamplingCoord;
      void main() {
        gl_Position = aFramePosition;
        vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
      }
    """

    private const val FRAGMENT_SHADER_GLSL = """
      #version 100
      precision mediump float;
      uniform sampler2D uTexSampler;
      uniform vec2 uTexelSize;
      uniform float uBlurStrength;
      uniform float uBlurRadiusPx;
      uniform float uFeather;
      uniform vec2 uFocusPoint;
      uniform float uFocusRange;
      uniform int uMaskMode;
      varying vec2 vTexSamplingCoord;

      float computeMaskWeight(vec2 uv, vec4 centerColor, vec4 focusColor) {
        if (uMaskMode == 1) {
          float band = uFocusRange * 0.8;
          float d = abs(uv.y - uFocusPoint.y);
          return smoothstep(band, band + uFeather * 0.5, d);
        }
        float d = distance(uv, uFocusPoint);
        if (uMaskMode == 2) {
          float spatial = smoothstep(uFocusRange * 0.85, uFocusRange * 0.85 + uFeather * 0.55, d);
          float colorDiff = length(centerColor.rgb - focusColor.rgb);
          float similarity = clamp(1.0 - colorDiff * 1.45, 0.0, 1.0);
          return clamp(spatial * (1.0 - similarity * 0.72), 0.0, 1.0);
        }
        return smoothstep(uFocusRange, uFocusRange + uFeather * 0.55, d);
      }

      void main() {
        vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
        if (uBlurStrength <= 0.005 || uBlurRadiusPx <= 0.1) {
          gl_FragColor = center;
          return;
        }
        vec4 focusSample = texture2D(uTexSampler, uFocusPoint);
        float maskWeight = computeMaskWeight(vTexSamplingCoord, center, focusSample);
        float effRadius = uBlurRadiusPx * maskWeight;
        if (effRadius <= 0.15) {
          gl_FragColor = center;
          return;
        }

        vec2 stepSize = uTexelSize * effRadius;
        vec4 accum = center * 1.5;
        float totalW = 1.5;

        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.866,  0.500) * stepSize, 0.001, 0.999));
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-0.866,  0.500) * stepSize, 0.001, 0.999));
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.866, -0.500) * stepSize, 0.001, 0.999));
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-0.866, -0.500) * stepSize, 0.001, 0.999));
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.000,  1.000) * stepSize, 0.001, 0.999));
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.000, -1.000) * stepSize, 0.001, 0.999));
        totalW += 6.0;

        vec2 innerStep = stepSize * 0.52;
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.707,  0.707) * innerStep, 0.001, 0.999)) * 0.85;
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-0.707,  0.707) * innerStep, 0.001, 0.999)) * 0.85;
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.707, -0.707) * innerStep, 0.001, 0.999)) * 0.85;
        accum += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-0.707, -0.707) * innerStep, 0.001, 0.999)) * 0.85;
        totalW += 3.4;

        gl_FragColor = accum / totalW;
      }
    """
  }
}

data class ChromaticGlitchParams(
  val chromaticAberration: Float = 0.0f,
  val glitchIntensity: Float = 0.0f,
  val vignette: Float = 0.0f,
)

/**
 * OpenGL ES Media3 [GlEffect] implementing radial/horizontal RGB chromatic aberration,
 * scanline glitch displacement, and edge vignette.
 */
@OptIn(UnstableApi::class)
class ChromaticGlitchGlEffect(
  private val paramsProvider: (presentationTimeUs: Long) -> ChromaticGlitchParams,
) : GlEffect {

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
    ChromaticGlitchShaderProgram(useHdr = useHdr, paramsProvider = paramsProvider)

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class ChromaticGlitchShaderProgram(
  useHdr: Boolean,
  private val paramsProvider: (presentationTimeUs: Long) -> ChromaticGlitchParams,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

  private var width: Int = 540
  private var height: Int = 540

  private val glProgram: GlProgram =
    try {
      GlProgram(VERTEX_SHADER_GLSL, FRAGMENT_SHADER_GLSL).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    width = inputWidth.coerceAtLeast(1)
    height = inputHeight.coerceAtLeast(1)
    return Size(width, height)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    val p = paramsProvider(presentationTimeUs)
    val timeSec = (presentationTimeUs.coerceAtLeast(0L) / 1_000_000.0f)
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      glProgram.setFloatUniform("uChromatic", p.chromaticAberration.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uGlitch", p.glitchIntensity.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uVignette", p.vignette.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uTimeSec", timeSec)
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      glProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  companion object {
    private const val VERTEX_SHADER_GLSL = """
      #version 100
      attribute vec4 aFramePosition;
      varying vec2 vTexSamplingCoord;
      void main() {
        gl_Position = aFramePosition;
        vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
      }
    """

    private const val FRAGMENT_SHADER_GLSL = """
      #version 100
      precision mediump float;
      uniform sampler2D uTexSampler;
      uniform float uChromatic;
      uniform float uGlitch;
      uniform float uVignette;
      uniform float uTimeSec;
      varying vec2 vTexSamplingCoord;

      float hash12(vec2 p) {
        return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
      }

      void main() {
        vec2 uv = vTexSamplingCoord;
        if (uGlitch > 0.005) {
          float slice = floor(uv.y * 26.0);
          float pulse = hash12(vec2(slice, floor(uTimeSec * 14.0)));
          if (pulse > 1.0 - (0.35 * uGlitch)) {
            float jitter = (hash12(vec2(slice + 5.0, floor(uTimeSec * 18.0))) - 0.5) * 0.075 * uGlitch;
            uv.x = fract(uv.x + jitter);
          }
        }

        float totalShift = uChromatic * 0.014 + uGlitch * 0.010;
        vec2 centerDir = (uv - vec2(0.5, 0.5)) * 1.3;
        vec2 caOffset = vec2(totalShift, 0.0) + centerDir * (uChromatic * 0.008);

        float r = texture2D(uTexSampler, clamp(uv + caOffset, 0.001, 0.999)).r;
        float g = texture2D(uTexSampler, clamp(uv, 0.001, 0.999)).g;
        float b = texture2D(uTexSampler, clamp(uv - caOffset, 0.001, 0.999)).b;
        float a = texture2D(uTexSampler, clamp(uv, 0.001, 0.999)).a;

        vec3 rgb = vec3(r, g, b);
        if (uGlitch > 0.005) {
          float scan = 1.0 - (0.14 * uGlitch * abs(sin(uv.y * 220.0 + uTimeSec * 20.0)));
          rgb *= scan;
        }
        if (uVignette > 0.005) {
          float dist = distance(uv, vec2(0.5, 0.5));
          float vig = smoothstep(0.88 - (0.28 * uVignette), 0.25, dist);
          rgb *= mix(1.0, vig, uVignette * 0.85);
        }
        gl_FragColor = vec4(rgb, a);
      }
    """
  }
}

/**
 * Deterministic transition visual state shared across Compose preview standby and Media3 GL shaders.
 */
data class ClipTransitionVisualState(
  val type: TransitionType? = null,
  val alpha: Float = 1.0f,
  val flashColorWhiteFraction: Float = 0.0f,
  val slideOffsetXFraction: Float = 0.0f,
  val wipeVisibleFraction: Float = 1.0f,
  val wipeFromRight: Boolean = false,
) {
  val isActive: Boolean
    get() = type != null
}

object TransitionVisualMath {
  private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
  }

  fun evaluate(
    transitionIn: ClipTransition?,
    transitionOut: ClipTransition?,
    offsetMs: Long,
    clipDurationMs: Long,
  ): ClipTransitionVisualState {
    val safeDuration = clipDurationMs.coerceAtLeast(1L)
    val safeOffset = offsetMs.coerceIn(0L, safeDuration)

    if (transitionIn != null && transitionIn.durationMs > 0L && safeOffset < transitionIn.durationMs) {
      val dur = minOf(transitionIn.durationMs, safeDuration).coerceAtLeast(1L).toFloat()
      val t = smoothstep(safeOffset.toFloat() / dur)
      return when (transitionIn.type) {
        TransitionType.CROSS_DISSOLVE ->
          ClipTransitionVisualState(type = transitionIn.type, alpha = 0.25f + 0.75f * t)
        TransitionType.FADE_BLACK ->
          ClipTransitionVisualState(type = transitionIn.type, alpha = t, flashColorWhiteFraction = 0.0f)
        TransitionType.FADE_WHITE ->
          ClipTransitionVisualState(type = transitionIn.type, alpha = t, flashColorWhiteFraction = 1.0f)
        TransitionType.SLIDE_LEFT ->
          ClipTransitionVisualState(type = transitionIn.type, slideOffsetXFraction = 1.0f - t)
        TransitionType.SLIDE_RIGHT ->
          ClipTransitionVisualState(type = transitionIn.type, slideOffsetXFraction = -(1.0f - t))
        TransitionType.WIPE_LEFT ->
          ClipTransitionVisualState(type = transitionIn.type, wipeVisibleFraction = t, wipeFromRight = false)
        TransitionType.WIPE_RIGHT ->
          ClipTransitionVisualState(type = transitionIn.type, wipeVisibleFraction = t, wipeFromRight = true)
      }
    }

    if (transitionOut != null && transitionOut.durationMs > 0L) {
      val durLong = minOf(transitionOut.durationMs, safeDuration).coerceAtLeast(1L)
      val outStart = safeDuration - durLong
      if (safeOffset >= outStart) {
        val elapsedOut = (safeOffset - outStart).toFloat() / durLong.toFloat()
        val t = smoothstep(elapsedOut)
        return when (transitionOut.type) {
          TransitionType.CROSS_DISSOLVE ->
            ClipTransitionVisualState(type = transitionOut.type, alpha = 1.0f - 0.75f * t)
          TransitionType.FADE_BLACK ->
            ClipTransitionVisualState(type = transitionOut.type, alpha = 1.0f - t, flashColorWhiteFraction = 0.0f)
          TransitionType.FADE_WHITE ->
            ClipTransitionVisualState(type = transitionOut.type, alpha = 1.0f - t, flashColorWhiteFraction = 1.0f)
          TransitionType.SLIDE_LEFT ->
            ClipTransitionVisualState(type = transitionOut.type, slideOffsetXFraction = -t)
          TransitionType.SLIDE_RIGHT ->
            ClipTransitionVisualState(type = transitionOut.type, slideOffsetXFraction = t)
          TransitionType.WIPE_LEFT ->
            ClipTransitionVisualState(type = transitionOut.type, wipeVisibleFraction = 1.0f - t, wipeFromRight = true)
          TransitionType.WIPE_RIGHT ->
            ClipTransitionVisualState(type = transitionOut.type, wipeVisibleFraction = 1.0f - t, wipeFromRight = false)
        }
      }
    }

    return ClipTransitionVisualState()
  }
}

@OptIn(UnstableApi::class)
class ClipTransitionGlEffect(
  private val stateProvider: (presentationTimeUs: Long) -> ClipTransitionVisualState,
) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
    ClipTransitionShaderProgram(useHdr = useHdr, stateProvider = stateProvider)

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class ClipTransitionShaderProgram(
  useHdr: Boolean,
  private val stateProvider: (presentationTimeUs: Long) -> ClipTransitionVisualState,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

  private var width: Int = 540
  private var height: Int = 540

  private val glProgram: GlProgram =
    try {
      GlProgram(VERTEX_SHADER_GLSL, FRAGMENT_SHADER_GLSL).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    width = inputWidth.coerceAtLeast(1)
    height = inputHeight.coerceAtLeast(1)
    return Size(width, height)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    val st = stateProvider(presentationTimeUs)
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      glProgram.setFloatUniform("uAlpha", st.alpha.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uFlashWhite", st.flashColorWhiteFraction.coerceIn(0f, 1f))
      glProgram.setFloatUniform("uSlideX", st.slideOffsetXFraction.coerceIn(-1f, 1f))
      glProgram.setFloatUniform("uWipeVisible", st.wipeVisibleFraction.coerceIn(0f, 1f))
      glProgram.setIntUniform("uWipeFromRight", if (st.wipeFromRight) 1 else 0)
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      glProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  companion object {
    private const val VERTEX_SHADER_GLSL = """
      #version 100
      attribute vec4 aFramePosition;
      varying vec2 vTexSamplingCoord;
      void main() {
        gl_Position = aFramePosition;
        vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
      }
    """

    private const val FRAGMENT_SHADER_GLSL = """
      #version 100
      precision mediump float;
      uniform sampler2D uTexSampler;
      uniform float uAlpha;
      uniform float uFlashWhite;
      uniform float uSlideX;
      uniform float uWipeVisible;
      uniform int uWipeFromRight;
      varying vec2 vTexSamplingCoord;

      void main() {
        vec2 shiftedUv = vec2(vTexSamplingCoord.x - uSlideX, vTexSamplingCoord.y);
        if (shiftedUv.x < 0.0 || shiftedUv.x > 1.0) {
          gl_FragColor = vec4(vec3(uFlashWhite), 1.0);
          return;
        }
        float wipeMask = 1.0;
        if (uWipeVisible < 0.999) {
          if (uWipeFromRight == 1) {
            wipeMask = smoothstep(1.0 - uWipeVisible - 0.015, 1.0 - uWipeVisible + 0.015, shiftedUv.x);
          } else {
            wipeMask = 1.0 - smoothstep(uWipeVisible - 0.015, uWipeVisible + 0.015, shiftedUv.x);
          }
        }
        vec4 src = texture2D(uTexSampler, clamp(shiftedUv, 0.001, 0.999));
        vec3 bg = vec3(uFlashWhite);
        vec3 mixed = mix(bg, src.rgb, clamp(uAlpha * wipeMask, 0.0, 1.0));
        gl_FragColor = vec4(mixed, src.a);
      }
    """
  }
}

/**
 * Shared deterministic Media3 visual-effect pipeline used by both [CompositionCompiler]
 * (final export / 540p proxy compilation) and [com.example.core.media.playback.PreviewPlayerController]
 * (real-time preview playback) to guarantee preview/render parity.
 *
 * Processing order is strictly:
 * 1. Geometric Transform
 * 2. Selective Optical Depth / Lens Blur (when active or rack-focus keyframed)
 * 3. Base Adjustments (Exposure/Brightness, Contrast, Temp/Tint, Saturation, Vibrance/Whites/Blacks, Lift/Gamma/Gain)
 * 4. LUT Application (3D LUT cube via [SingleColorLut])
 * 5. Opacity / Blend
 */
@OptIn(UnstableApi::class)
object KeyframeMedia3Effects {

  /**
   * Scales an optical bokeh radius calibrated at 540p preview resolution to any render height
   * (e.g. 540p proxy -> `1.0x`, 1080p export -> `2.0x`) for exact preview/export parity.
   */
  fun resolveDepthBlurRadiusForHeight(
    baseRadiusPx540p: Float,
    blurStrength: Float,
    frameHeight: Int,
  ): Float {
    val safeRadius = ColorGradeBounds.clampDepthBlurRadius(baseRadiusPx540p)
    val safeStrength = ColorGradeBounds.clampDepthBlur(blurStrength)
    val heightScale = frameHeight.coerceAtLeast(1).toFloat() / 540.0f
    return (safeRadius * safeStrength * heightScale).coerceIn(0f, 96f)
  }

  /**
   * Maps a frame timestamp `presentationTimeUs` (in clip-source time starting at `0`)
   * onto the clip's timeline-relative keyframe offset in milliseconds, accounting for [speed].
   */
  fun presentationTimeUsToClipOffsetMs(
    presentationTimeUs: Long,
    clipDurationMs: Long,
    speed: Float = 1.0f,
    sourceSpanMs: Long = 0L,
    speedCurve: List<SpeedCurvePoint> = emptyList(),
  ): Long {
    val safeSpeed = speed.coerceIn(0.25f, 4.0f)
    val sourceElapsedMs = presentationTimeUs.coerceAtLeast(0L).toDouble() / 1_000.0
    val clipOffsetMs =
      if (SpeedCurveEngine.hasVariableCurve(speedCurve) && sourceSpanMs > 0L) {
        SpeedCurveEngine.sourceOffsetMsToTimelineMsDouble(
          sourceOffsetMs = sourceElapsedMs,
          sourceSpanMs = sourceSpanMs,
          baseSpeed = safeSpeed,
          speedCurve = speedCurve,
        ).roundToLong()
      } else {
        (sourceElapsedMs / safeSpeed.toDouble()).roundToLong()
      }
    return clipOffsetMs.coerceIn(0L, clipDurationMs.coerceAtLeast(0L))
  }

  /**
   * Evaluates a [TimelineClip] deterministically at [presentationTimeUs] inside the clip.
   */
  fun evaluateClipAtPresentationUs(
    clip: TimelineClip,
    presentationTimeUs: Long,
  ): EvaluatedClipProperties {
    val offsetMs =
      presentationTimeUsToClipOffsetMs(
        presentationTimeUs = presentationTimeUs,
        clipDurationMs = clip.durationMs,
        speed = clip.speed,
        sourceSpanMs = clip.sourceSpanMs,
        speedCurve = clip.speedCurve,
      )
    return clip.evaluatePropertiesAt(offsetMs)
  }

  fun evaluateTransitionAtPresentationUs(
    clip: TimelineClip,
    presentationTimeUs: Long,
  ): ClipTransitionVisualState {
    val cfg = clip.videoConfig ?: return ClipTransitionVisualState()
    if (cfg.transitionIn == null && cfg.transitionOut == null) return ClipTransitionVisualState()
    val offsetMs =
      presentationTimeUsToClipOffsetMs(
        presentationTimeUs = presentationTimeUs,
        clipDurationMs = clip.durationMs,
        speed = clip.speed,
        sourceSpanMs = clip.sourceSpanMs,
        speedCurve = clip.speedCurve,
      )
    return TransitionVisualMath.evaluate(
      transitionIn = cfg.transitionIn,
      transitionOut = cfg.transitionOut,
      offsetMs = offsetMs,
      clipDurationMs = clip.durationMs,
    )
  }

  /**
   * Builds the deterministic Android 2D transform [Matrix] matching preview and final export.
   */
  fun buildTransformMatrix(evaluated: EvaluatedClipProperties): Matrix =
    Matrix().apply {
      if (evaluated.rotationDegrees != 0.0f) {
        postRotate(evaluated.rotationDegrees)
      }
      if (evaluated.scale != 1.0f) {
        postScale(evaluated.scale, evaluated.scale)
      }
      if (evaluated.offsetX != 0.0f || evaluated.offsetY != 0.0f) {
        postTranslate(
          evaluated.offsetX.coerceIn(-1.5f, 1.5f),
          evaluated.offsetY.coerceIn(-1.5f, 1.5f),
        )
      }
    }

  /**
   * Builds per-clip Media3 GL [Effect]s for a single [clip] (used directly per [androidx.media3.transformer.EditedMediaItem]).
   */
  fun buildEffectsForClip(
    clip: TimelineClip,
    adjustment: AdjustmentClipConfig?,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
  ): List<Effect> =
    buildEffectsWithEvaluator(
      videoCfg = clip.videoConfig,
      adjustment = adjustment,
      customLutPresets = customLutPresets,
      transitionEvaluator =
        if (clip.videoConfig?.transitionIn != null || clip.videoConfig?.transitionOut != null) {
          { presentationTimeUs -> evaluateTransitionAtPresentationUs(clip, presentationTimeUs) }
        } else {
          null
        },
      evaluator = { presentationTimeUs ->
        evaluateClipAtPresentationUs(clip, presentationTimeUs)
      },
    )

  /**
   * Builds Media3 GL [Effect]s given a [videoCfg], optional [adjustment], and custom
   * timestamp [evaluator] in strict deterministic order:
   * Transform -> Clip Base Adjustments -> Clip Lift/Gamma/Gain & LUT -> Adjustment Base -> Adjustment Lift/Gamma/Gain & LUT -> Opacity/Blend -> Depth Blur (if active) -> Transition (if active).
   */
  fun buildEffectsWithEvaluator(
    videoCfg: VideoClipConfig?,
    adjustment: AdjustmentClipConfig?,
    hasKeyframesOverride: Boolean? = null,
    hasAnimatedOpacityOverride: Boolean? = null,
    customLutPresets: List<LutPreset> = BuiltInLutPresets.registeredCustomPresets(),
    hasTransitionOverride: Boolean? = null,
    transitionEvaluator: ((presentationTimeUs: Long) -> ClipTransitionVisualState)? = null,
    evaluator: (presentationTimeUs: Long) -> EvaluatedClipProperties,
  ): List<Effect> {
    val effects = mutableListOf<Effect>()
    val pipeline =
      ColorGradingEngine.buildPipeline(
        videoCfg = videoCfg,
        adjustmentCfg = adjustment,
        customPresets = customLutPresets,
      )

    // 0. Geometric transform
    var shouldEmitAnimatedOpacity = false
    videoCfg?.let { cfg ->
      val hasKeyframes = hasKeyframesOverride ?: cfg.keyframes.isNotEmpty()
      val hasTranslation = cfg.offsetX != 0.0f || cfg.offsetY != 0.0f
      val hasScaleOrRotate = cfg.scale != 1.0f || cfg.rotationDegrees != 0.0f

      if (hasKeyframes || hasTranslation) {
        effects.add(
          MatrixTransformation { presentationTimeUs ->
            buildTransformMatrix(evaluator(presentationTimeUs))
          },
        )
      } else if (hasScaleOrRotate) {
        effects.add(
          ScaleAndRotateTransformation.Builder()
            .setScale(cfg.scale.coerceIn(0.1f, 4.0f), cfg.scale.coerceIn(0.1f, 4.0f))
            .setRotationDegrees(cfg.rotationDegrees)
            .build(),
        )
      }

      shouldEmitAnimatedOpacity =
        hasAnimatedOpacityOverride
          ?: (cfg.opacity < 0.99f || cfg.keyframes.any { it.opacity < 0.99f })

      // 1 & 2. Clip Stage 1 (Base Adjustments) -> Clip Stage 2 (Lift/Gamma/Gain & LUT)
      appendScalarBaseEffects(effects, pipeline.clipBaseStage)
      appendToneAndLutCubeEffect(effects, pipeline.clipBaseStage, pipeline.clipLutStage)
    }

    // Adjustment layer (Base Adjustments -> Lift/Gamma/Gain & LUT, scaled by layer blendOpacity)
    pipeline.adjustmentBaseStage?.let { adjBase ->
      appendScalarBaseEffects(effects, adjBase)
      appendToneAndLutCubeEffect(effects, adjBase, pipeline.adjustmentLutStage)
    } ?: pipeline.adjustmentLutStage?.let { adjLut ->
      appendToneAndLutCubeEffect(effects, null, adjLut)
    }

    // 3. Stage 3: Opacity / Blend
    if (shouldEmitAnimatedOpacity) {
      effects.add(
        AnimatedOpacityRgbEffect { presentationTimeUs ->
          evaluator(presentationTimeUs).opacity
        },
      )
    }

    // 4. Selective Optical Depth / Lens Blur (emitted only when enabled or keyframed)
    val adjDepth = pipeline.adjustmentBaseStage?.depthBlur ?: 0f
    val hasDepth =
      pipeline.combinedGrade.depthBlur > 0.005f ||
        (videoCfg?.keyframes?.any { (it.depthBlur ?: 0f) > 0.005f } == true)
    if (hasDepth) {
      val adjStage = pipeline.adjustmentBaseStage
      val useAdjOverlay = adjDepth > 0.005f && adjStage != null
      val staticRadius =
        if (useAdjOverlay) adjStage!!.depthBlurRadius
        else videoCfg?.depthBlurRadius ?: ColorGradeBounds.DEFAULT_DEPTH_BLUR_RADIUS
      val staticFeather =
        if (useAdjOverlay) adjStage!!.depthBlurFeather
        else videoCfg?.depthBlurFeather ?: ColorGradeBounds.DEFAULT_DEPTH_BLUR_FEATHER
      val staticMode =
        if (useAdjOverlay) adjStage!!.depthBlurMode
        else videoCfg?.depthBlurMode ?: DepthBlurMaskMode.RADIAL

      effects.add(
        SelectiveDepthBlurEffect { presentationTimeUs ->
          val evaluated = evaluator(presentationTimeUs)
          val totalStrength = (evaluated.depthBlur + adjDepth).coerceIn(0f, 1f)
          val fx = if (useAdjOverlay) adjStage!!.depthFocusX else evaluated.depthFocusX
          val fy = if (useAdjOverlay) adjStage!!.depthFocusY else evaluated.depthFocusY
          val range = if (useAdjOverlay) adjStage!!.depthFocusRange else evaluated.depthFocusRange
          SelectiveDepthBlurParams(
            strength = totalStrength,
            radiusPx540p = staticRadius,
            feather = staticFeather,
            mode = staticMode,
            focusX = fx,
            focusY = fy,
            focusRange = range,
          )
        },
      )
    }

    // 5. Cut transitions (emitted strictly when transitionIn or transitionOut is configured)
    val hasTransitions =
      hasTransitionOverride ?: (videoCfg?.transitionIn != null || videoCfg?.transitionOut != null)
    if (hasTransitions && transitionEvaluator != null) {
      effects.add(ClipTransitionGlEffect(transitionEvaluator))
    }

    // 6. Chromatic aberration, Glitch, and explicit Vignette shader pass
    val ca = pipeline.combinedGrade.chromaticAberration
    val glitch = pipeline.combinedGrade.glitchIntensity
    val explicitVignette =
      (videoCfg?.vignette ?: 0f) + (adjustment?.let { it.vignette * it.blendOpacity } ?: 0f)
    if (ca > 0.005f || glitch > 0.005f || explicitVignette > 0.005f) {
      val vignette = pipeline.combinedGrade.vignette.coerceAtLeast(explicitVignette)
      effects.add(
        ChromaticGlitchGlEffect {
          ChromaticGlitchParams(
            chromaticAberration = ca,
            glitchIntensity = glitch,
            vignette = vignette,
          )
        },
      )
    }

    return effects
  }

  private fun appendScalarBaseEffects(
    effects: MutableList<Effect>,
    stage: ColorGradeStageParams,
  ) {
    val netBrightness = stage.netBrightnessAndExposure
    if (abs(netBrightness) > 0.005f) {
      effects.add(Brightness(netBrightness))
    }
    if (abs(stage.contrast - 1.0f) > 0.005f) {
      effects.add(Contrast((stage.contrast - 1.0f).coerceIn(-1f, 1f)))
    }
    if (abs(stage.saturation - 1.0f) > 0.005f) {
      val satShift = ((stage.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
      effects.add(HslAdjustment.Builder().adjustSaturation(satShift).build())
    }
    if (abs(stage.temperature) > 0.005f || abs(stage.tint) > 0.005f) {
      val tempShift = (stage.temperature * 0.18f).coerceIn(-0.35f, 0.35f)
      val tintShift = (stage.tint * 0.16f).coerceIn(-0.32f, 0.32f)
      effects.add(
        RgbAdjustment.Builder()
          .setRedScale((1.0f + tempShift + tintShift * 0.5f).coerceIn(0.2f, 2.0f))
          .setGreenScale((1.0f - tintShift).coerceIn(0.2f, 2.0f))
          .setBlueScale((1.0f - tempShift + tintShift * 0.5f).coerceIn(0.2f, 2.0f))
          .build(),
      )
    }
  }

  private fun appendToneAndLutCubeEffect(
    effects: MutableList<Effect>,
    toneStage: ColorGradeStageParams?,
    lutStage: ColorGradeStageParams?,
  ) {
    val needsToneCube = toneStage?.hasToneWheelAdjustments == true
    val needsLutCube = lutStage?.hasLutApplication == true
    if (!needsToneCube && !needsLutCube) return

    val cube =
      ColorGradingEngine.buildStage3dLutCube(
        size = 17,
        toneStage = toneStage,
        lutStage = lutStage,
      )
    effects.add(SingleColorLut.createFromCube(cube))
  }
}
