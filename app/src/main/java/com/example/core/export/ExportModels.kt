package com.example.core.export

import com.example.core.model.ExportConfig

/**
 * Defines which temporal span of the timeline should be rendered during export.
 */
sealed interface ExportRange {
  val label: String

  data object FullTimeline : ExportRange {
    override val label: String = "Línea completa"
  }

  data class TimeRange(
    val startMs: Long,
    val endMs: Long,
    override val label: String = "Rango personalizado",
  ) : ExportRange {
    init {
      require(endMs > startMs) { "Export range endMs must be > startMs" }
    }
  }

  data class MarkerSpan(
    val startMarkerId: String?,
    val endMarkerId: String?,
    val startMs: Long,
    val endMs: Long,
    override val label: String,
  ) : ExportRange {
    init {
      require(endMs > startMs) { "Marker span endMs must be > startMs" }
    }
  }
}

enum class ExportJobStatus {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED,
}

/**
 * Represents one item inside the sequential local export queue.
 */
data class ExportQueueItem(
  val jobId: String,
  val title: String,
  val range: ExportRange,
  val exportConfig: ExportConfig,
  val status: ExportJobStatus = ExportJobStatus.QUEUED,
  val progressPercent: Int = 0,
  val outputFilePath: String? = null,
  val galleryUri: String? = null,
  val outputSizeBytes: Long? = null,
  val errorMessage: String? = null,
  val createdAtMs: Long = System.currentTimeMillis(),
)
