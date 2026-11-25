package com.example.core.project

import com.example.core.model.ProjectDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists and loads [ProjectDocument] files as `<projectId>.json` strictly under `filesDir/projects/`.
 * Temporary proxies, thumbnails, and staging renders are kept isolated in `cacheDir`.
 */
class ProjectRepository(
  val projectsDir: File,
  private val serializer: ProjectJsonSerializer = ProjectJsonSerializer(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private val ioMutex = Mutex()

  fun projectFileFor(projectId: String): File {
    val safeStem =
      projectId
        .trim()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .ifBlank { "project_default" }
    return File(projectsDir, "$safeStem.json")
  }

  suspend fun saveProject(project: ProjectDocument): Result<File> =
    withContext(ioDispatcher) {
      ioMutex.withLock {
        runCatching {
          projectsDir.mkdirs()
          val targetFile = projectFileFor(project.projectId)
          val payload = serializer.encode(project)
          targetFile.writeText(payload, Charsets.UTF_8)
          targetFile
        }
      }
    }

  suspend fun loadProject(projectId: String): Result<ProjectDocument> =
    withContext(ioDispatcher) {
      ioMutex.withLock {
        runCatching {
          val file = projectFileFor(projectId)
          require(file.exists() && file.isFile) {
            "No existe el proyecto guardado: ${file.name}"
          }
          serializer.decode(file.readText(Charsets.UTF_8)).getOrThrow()
        }
      }
    }

  suspend fun listRecentProjects(): List<ProjectDocument> =
    withContext(ioDispatcher) {
      ioMutex.withLock {
        if (!projectsDir.exists()) return@withLock emptyList()
        projectsDir
          .listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
          }
          .orEmpty()
          .mapNotNull { file ->
            runCatching {
              serializer.decode(file.readText(Charsets.UTF_8)).getOrNull()
            }.getOrNull()
          }
          .sortedByDescending { it.updatedAtIso }
      }
    }

  suspend fun deleteProject(projectId: String): Boolean =
    withContext(ioDispatcher) {
      ioMutex.withLock {
        val file = projectFileFor(projectId)
        file.exists() && file.delete()
      }
    }
}
