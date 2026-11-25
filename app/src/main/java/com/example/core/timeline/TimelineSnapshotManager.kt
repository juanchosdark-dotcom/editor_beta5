package com.example.core.timeline

import com.example.core.model.ProjectDocument
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState
import java.time.Instant
import java.util.UUID

/**
 * Manages immutable timeline snapshots (checkpoints) and independent sequence duplicates
 * so editors can A/B test alternate cuts without risking their main edit.
 */
class TimelineSnapshotManager(
  private val idGenerator: () -> String = { UUID.randomUUID().toString().take(8) },
  private val nowIso: () -> String = { Instant.now().toString() },
) {

  /**
   * Captures a named snapshot of [currentTimeline] inside [project].
   */
  fun createSnapshot(
    project: ProjectDocument,
    currentTimeline: TimelineState,
    name: String,
    note: String = "",
  ): ProjectDocument {
    val snapshot =
      TimelineSnapshot(
        snapshotId = "snap_${idGenerator()}",
        name = name.ifBlank { "Instantánea ${project.snapshots.size + 1}" },
        createdAtIso = nowIso(),
        note = note,
        timeline = currentTimeline,
      )
    return project.copy(
      timeline = currentTimeline,
      snapshots = listOf(snapshot) + project.snapshots,
      updatedAtIso = nowIso(),
    )
  }

  /**
   * Restores the [TimelineState] stored inside [snapshotId] and returns it along with the updated project.
   */
  fun restoreSnapshot(
    project: ProjectDocument,
    snapshotId: String,
  ): Pair<ProjectDocument, TimelineState>? {
    val target = project.snapshots.find { it.snapshotId == snapshotId } ?: return null
    val updatedProject = syncActiveSequence(project, target.timeline)
    return updatedProject to target.timeline
  }

  fun deleteSnapshot(project: ProjectDocument, snapshotId: String): ProjectDocument =
    project.copy(
      snapshots = project.snapshots.filterNot { it.snapshotId == snapshotId },
      updatedAtIso = nowIso(),
    )

  /**
   * Duplicates [currentTimeline] into an independent named sequence and switches to it.
   */
  fun duplicateAsSequence(
    project: ProjectDocument,
    currentTimeline: TimelineState,
    newSequenceName: String,
  ): Pair<ProjectDocument, TimelineSequence> {
    val synced = ensureMainSequencePresent(syncActiveSequence(project, currentTimeline))
    val newSequence =
      TimelineSequence(
        sequenceId = "seq_${idGenerator()}",
        name = newSequenceName.ifBlank { "Copia ${synced.sequences.size + 1}" },
        createdAtIso = nowIso(),
        timeline = currentTimeline,
      )
    val nextProject =
      synced.copy(
        activeSequenceId = newSequence.sequenceId,
        timeline = newSequence.timeline,
        sequences = synced.sequences + newSequence,
        updatedAtIso = nowIso(),
      )
    return nextProject to newSequence
  }

  /**
   * Saves [currentTimeline] into the currently active sequence and switches to [targetSequenceId].
   */
  fun switchSequence(
    project: ProjectDocument,
    currentTimeline: TimelineState,
    targetSequenceId: String,
  ): Pair<ProjectDocument, TimelineState>? {
    val synced = ensureMainSequencePresent(syncActiveSequence(project, currentTimeline))
    val target = synced.sequences.find { it.sequenceId == targetSequenceId } ?: return null
    val nextProject =
      synced.copy(
        activeSequenceId = target.sequenceId,
        timeline = target.timeline,
        updatedAtIso = nowIso(),
      )
    return nextProject to target.timeline
  }

  private fun ensureMainSequencePresent(project: ProjectDocument): ProjectDocument {
    if (project.sequences.any { it.sequenceId == project.activeSequenceId }) return project
    val mainSeq =
      TimelineSequence(
        sequenceId = project.activeSequenceId,
        name = "Principal",
        createdAtIso = project.createdAtIso,
        timeline = project.timeline,
      )
    return project.copy(sequences = listOf(mainSeq) + project.sequences)
  }

  private fun syncActiveSequence(
    project: ProjectDocument,
    currentTimeline: TimelineState,
  ): ProjectDocument {
    val updatedSequences =
      project.sequences.map { seq ->
        if (seq.sequenceId == project.activeSequenceId) {
          seq.copy(timeline = currentTimeline)
        } else {
          seq
        }
      }
    return project.copy(
      timeline = currentTimeline,
      sequences = updatedSequences,
      updatedAtIso = nowIso(),
    )
  }
}
