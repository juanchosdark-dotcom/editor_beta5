package com.example.core.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes locally exported MP4 files to the system Gallery via [MediaStore] on Android 10+
 * without requiring legacy storage permissions, or to the Movies directory on older API levels.
 */
class GallerySaveHelper(
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun publishMp4ToGallery(
    exportedFile: File,
    displayTitle: String,
  ): Result<Uri> =
    withContext(ioDispatcher) {
      runCatching {
        require(exportedFile.exists() && exportedFile.length() > 0L) {
          "El archivo .mp4 exportado no existe o está vacío"
        }

        val safeTitle =
          displayTitle
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "FrameLine_Export" }
        val fileName = "${safeTitle}_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
              put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
              put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/FrameLine Studio",
              )
              put(MediaStore.Video.Media.IS_PENDING, 1)
            }

          val resolver = context.contentResolver
          val itemUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
              ?: error("No se pudo crear la entrada en MediaStore")

          try {
            val stream =
              resolver.openOutputStream(itemUri)
                ?: error("No se pudo abrir flujo de escritura en MediaStore")
            stream.use { out ->
              exportedFile.inputStream().use { input -> input.copyTo(out) }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
          } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
          }
        } else {
          val targetDir =
            File(
              context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
              "FrameLine Studio",
            ).apply { mkdirs() }
          val targetFile = File(targetDir, fileName)
          exportedFile.copyTo(targetFile, overwrite = true)
          Uri.fromFile(targetFile)
        }
      }
    }
}
