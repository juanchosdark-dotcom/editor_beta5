package com.example.core.timeline

import com.example.core.ai.api.LocalEditAnalysisReport
import com.example.core.model.AppliedAiAdjustment
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState

/**
 * Immutable project snapshot captured on every manual edit or AI Assistant fix
 * so Undo and Redo restore timeline, selection, sequences, and AI state atomically.
 */
data class EditorHistorySnapshot(
  val timeline: TimelineState,
  val selectedClipId: String?,
  val appliedAiAdjustments: List<AppliedAiAdjustment>,
  val editAnalysisReport: LocalEditAnalysisReport?,
  val snapshots: List<TimelineSnapshot>,
  val sequences: List<TimelineSequence>,
  val activeSequenceId: String,
)

/**
 * Bounded Undo / Redo history manager holding immutable [EditorHistorySnapshot] stacks
 * (default 20 steps) for both manual timeline edits and AiAssistant fixes.
 */
class EditorHistoryManager(
  val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
  init {
    require(maxSteps >= 1) { "maxSteps must be >= 1" }
  }

  private val undoStack = ArrayDeque<EditorHistorySnapshot>()
  private val redoStack = ArrayDeque<EditorHistorySnapshot>()

  val canUndo: Boolean
    get() = undoStack.isNotEmpty()

  val canRedo: Boolean
    get() = redoStack.isNotEmpty()

  val undoCount: Int
    get() = undoStack.size

  val redoCount: Int
    get() = redoStack.size

  fun record(beforeState: EditorHistorySnapshot) {
    if (undoStack.size >= maxSteps) {
      undoStack.removeFirst()
    }
    undoStack.addLast(beforeState)
    redoStack.clear()
  }

  fun undo(currentState: EditorHistorySnapshot): EditorHistorySnapshot? {
    val previous = undoStack.removeLastOrNull() ?: return null
    if (redoStack.size >= maxSteps) {
      redoStack.removeFirst()
    }
    redoStack.addLast(currentState)
    return previous
  }

  fun redo(currentState: EditorHistorySnapshot): EditorHistorySnapshot? {
    val next = redoStack.removeLastOrNull() ?: return null
    if (undoStack.size >= maxSteps) {
      undoStack.removeFirst()
    }
    undoStack.addLast(currentState)
    return next
  }

  fun clear() {
    undoStack.clear()
    redoStack.clear()
  }

  companion object {
    const val DEFAULT_MAX_STEPS = 20
  }
}
