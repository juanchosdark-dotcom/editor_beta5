package com.example.core.subtitles

import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.SubtitleClipConfig
import com.example.core.model.SubtitleStylePreset
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@JsonClass(generateAdapter = true)
data class SavedSubtitleStylesDocument(
  val styles: List<SubtitleStylePreset> = emptyList(),
)

/**
 * Persists custom user-created subtitle style presets inside `<projectRoot>/subtitle_styles.json`
 * alongside the built-in presets.
 */
class SubtitleStyleRepository(
  private val projectRootDir: File,
  moshi: Moshi = Moshi.Builder().build(),
) {
  private val adapter = moshi.adapter(SavedSubtitleStylesDocument::class.java).indent("  ")
  private val stylesFile: File
    get() = File(projectRootDir, "subtitle_styles.json")

  suspend fun loadAllStyles(): List<SubtitleStylePreset> =
    withContext(Dispatchers.IO) {
      val custom = readCustomStylesInternal()
      BuiltInSubtitleStyles.ALL + custom
    }

  suspend fun saveStyleFromConfig(
    name: String,
    config: SubtitleClipConfig,
  ): SubtitleStylePreset =
    withContext(Dispatchers.IO) {
      val existing = readCustomStylesInternal()
      val newPreset =
        SubtitleStylePreset(
          styleId = "custom_${UUID.randomUUID().toString().take(8)}",
          name = name.ifBlank { "Estilo personalizado" },
          fontFamily = config.fontFamily,
          fontSizeSp = config.fontSizeSp,
          textColorHex = config.textColorHex,
          backgroundColorHex = config.backgroundColorHex,
          alignment = config.alignment,
          outlineColorHex = config.outlineColorHex,
          outlineWidthDp = config.outlineWidthDp,
          verticalOffsetNormalized = config.verticalOffsetNormalized,
          animation = config.animation,
          isBuiltIn = false,
        )
      writeCustomStylesInternal(existing + newPreset)
      newPreset
    }

  suspend fun deleteCustomStyle(styleId: String): List<SubtitleStylePreset> =
    withContext(Dispatchers.IO) {
      val filtered = readCustomStylesInternal().filterNot { it.styleId == styleId }
      writeCustomStylesInternal(filtered)
      BuiltInSubtitleStyles.ALL + filtered
    }

  private fun readCustomStylesInternal(): List<SubtitleStylePreset> {
    if (!stylesFile.exists()) return emptyList()
    return runCatching {
      adapter.fromJson(stylesFile.readText())?.styles.orEmpty()
    }.getOrDefault(emptyList())
  }

  private fun writeCustomStylesInternal(styles: List<SubtitleStylePreset>) {
    stylesFile.parentFile?.mkdirs()
    stylesFile.writeText(adapter.toJson(SavedSubtitleStylesDocument(styles)))
  }
}
