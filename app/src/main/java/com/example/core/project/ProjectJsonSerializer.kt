package com.example.core.project

import com.example.core.model.ProjectDocument
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

/**
 * Handles versioned serialization and deserialization of `project.json` using Moshi KSP adapters.
 */
class ProjectJsonSerializer(
  moshi: Moshi = Moshi.Builder().build(),
) {
  private val adapter: JsonAdapter<ProjectDocument> =
    moshi.adapter(ProjectDocument::class.java).indent("  ")

  fun encode(project: ProjectDocument): String = adapter.toJson(project)

  fun decode(json: String): Result<ProjectDocument> =
    runCatching {
      requireNotNull(adapter.fromJson(json)) { "Invalid project.json payload" }
    }
}
