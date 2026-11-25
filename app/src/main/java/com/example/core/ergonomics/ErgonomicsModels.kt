package com.example.core.ergonomics

import androidx.compose.ui.input.pointer.PointerType

/**
 * Active physical input modality detected dynamically from pointer/keyboard events.
 */
enum class InputDeviceMode(
  val label: String,
  val scrubSensitivity: Float,
  val snapThresholdMs: Long,
) {
  TOUCH(label = "Táctil", scrubSensitivity = 1.0f, snapThresholdMs = 160L),
  STYLUS(label = "Stylus · Fino", scrubSensitivity = 0.25f, snapThresholdMs = 45L),
  KEYBOARD_MOUSE(label = "Teclado / Ratón", scrubSensitivity = 0.6f, snapThresholdMs = 80L),
  ;

  companion object {
    fun fromPointerType(pointerType: PointerType): InputDeviceMode =
      when (pointerType) {
        PointerType.Stylus, PointerType.Eraser -> STYLUS
        PointerType.Mouse -> KEYBOARD_MOUSE
        else -> TOUCH
      }
  }
}

/**
 * Timeline horizontal zoom and magnetic snapping state.
 */
data class TimelineViewportErgonomics(
  val zoomScale: Float = 1.0f,
  val snapEnabled: Boolean = true,
  val inputMode: InputDeviceMode = InputDeviceMode.TOUCH,
  val hoverTimelineMs: Long? = null,
) {
  companion object {
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 5.0f
    const val FRAME_STEP_30FPS_MS = 33L
    const val FINE_STEP_MS = 100L
    const val COARSE_STEP_MS = 1_000L
  }
}

/**
 * Optional preview monitor zoom/pan and precision framing guide state.
 * Normalized pan values preserve framing cleanly across portrait/landscape rotation.
 */
data class ViewerViewportState(
  val isZoomPanEnabled: Boolean = false,
  val zoomScale: Float = 1.0f,
  val panNormalizedX: Float = 0f,
  val panNormalizedY: Float = 0f,
  val showRuleOfThirds: Boolean = false,
  val showSafeMargins: Boolean = false,
  val magneticCenterSnap: Boolean = true,
  val isSnappedToCenterX: Boolean = false,
  val isSnappedToCenterY: Boolean = false,
) {
  val isTransformed: Boolean
    get() = zoomScale > 1.01f || kotlin.math.abs(panNormalizedX) > 0.005f || kotlin.math.abs(panNormalizedY) > 0.005f

  companion object {
    const val MIN_ZOOM = 1.0f
    const val MAX_ZOOM = 4.0f
    const val CENTER_SNAP_THRESHOLD = 0.035f
  }
}

enum class ShortcutAction {
  PLAY_PAUSE,
  SPLIT_AT_PLAYHEAD,
  TOGGLE_RIPPLE,
  ADD_MARKER,
  TOGGLE_CLIP_ENABLED,
  DELETE_CLIP,
  UNDO,
  REDO,
  STEP_BACK_FRAME,
  STEP_FORWARD_FRAME,
  STEP_BACK_COARSE,
  STEP_FORWARD_COARSE,
  ZOOM_IN,
  ZOOM_OUT,
  PREV_MARKER,
  NEXT_MARKER,
}

data class ShortcutDescriptor(
  val keysLabel: String,
  val description: String,
  val action: ShortcutAction,
)
