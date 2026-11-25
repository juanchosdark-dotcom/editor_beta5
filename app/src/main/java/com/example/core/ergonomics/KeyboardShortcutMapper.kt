package com.example.core.ergonomics

import androidx.compose.ui.input.key.Key

/**
 * Maps physical keyboard events (tablets, foldables, ChromeOS, Bluetooth keyboards)
 * to non-destructive NLE timeline actions.
 */
object KeyboardShortcutMapper {

  val cheatSheet: List<ShortcutDescriptor> =
    listOf(
      ShortcutDescriptor("Espacio / K", "Reproducir / Pausar vista previa", ShortcutAction.PLAY_PAUSE),
      ShortcutDescriptor("← / →", "Mover cabezal ±100 ms", ShortcutAction.STEP_BACK_COARSE),
      ShortcutDescriptor("Shift + ← / →", "Paso por frame (±33 ms)", ShortcutAction.STEP_BACK_FRAME),
      ShortcutDescriptor("J / L", "Saltar ±1 s en línea de tiempo", ShortcutAction.STEP_FORWARD_COARSE),
      ShortcutDescriptor("Ctrl + B / S", "Cortar clip en cabezal", ShortcutAction.SPLIT_AT_PLAYHEAD),
      ShortcutDescriptor("R", "Alternar modo Ripple", ShortcutAction.TOGGLE_RIPPLE),
      ShortcutDescriptor("M", "Agregar marcador en cabezal", ShortcutAction.ADD_MARKER),
      ShortcutDescriptor("D", "Habilitar / Deshabilitar clip", ShortcutAction.TOGGLE_CLIP_ENABLED),
      ShortcutDescriptor("Ctrl + Z", "Deshacer acción", ShortcutAction.UNDO),
      ShortcutDescriptor("Ctrl + Y / Shift+Z", "Rehacer acción", ShortcutAction.REDO),
      ShortcutDescriptor("Supr / Backspace", "Eliminar clip seleccionado", ShortcutAction.DELETE_CLIP),
      ShortcutDescriptor("+ / -", "Zoom In / Zoom Out", ShortcutAction.ZOOM_IN),
      ShortcutDescriptor("[ / ]", "Marcador anterior / siguiente", ShortcutAction.PREV_MARKER),
    )

  fun mapKey(
    key: Key,
    isCtrlPressed: Boolean,
    isShiftPressed: Boolean,
  ): ShortcutAction? {
    return when {
      key == Key.Spacebar || key == Key.K -> ShortcutAction.PLAY_PAUSE
      (key == Key.Y && isCtrlPressed) || (key == Key.Z && isCtrlPressed && isShiftPressed) ->
        ShortcutAction.REDO
      key == Key.Z && isCtrlPressed -> ShortcutAction.UNDO
      (key == Key.B && isCtrlPressed) || (key == Key.S && !isCtrlPressed) ->
        ShortcutAction.SPLIT_AT_PLAYHEAD
      key == Key.R -> ShortcutAction.TOGGLE_RIPPLE
      key == Key.M -> ShortcutAction.ADD_MARKER
      key == Key.D -> ShortcutAction.TOGGLE_CLIP_ENABLED
      key == Key.Delete || key == Key.Backspace -> ShortcutAction.DELETE_CLIP
      key == Key.DirectionLeft && isShiftPressed -> ShortcutAction.STEP_BACK_FRAME
      key == Key.DirectionRight && isShiftPressed -> ShortcutAction.STEP_FORWARD_FRAME
      key == Key.DirectionLeft -> ShortcutAction.STEP_BACK_FRAME
      key == Key.DirectionRight -> ShortcutAction.STEP_FORWARD_FRAME
      key == Key.J -> ShortcutAction.STEP_BACK_COARSE
      key == Key.L -> ShortcutAction.STEP_FORWARD_COARSE
      key == Key.Equals || key == Key.Plus || key == Key.NumPadAdd -> ShortcutAction.ZOOM_IN
      key == Key.Minus || key == Key.NumPadSubtract -> ShortcutAction.ZOOM_OUT
      key == Key.LeftBracket -> ShortcutAction.PREV_MARKER
      key == Key.RightBracket -> ShortcutAction.NEXT_MARKER
      else -> null
    }
  }
}
