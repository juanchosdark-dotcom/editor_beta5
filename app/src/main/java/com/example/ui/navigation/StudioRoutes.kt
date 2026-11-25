package com.example.ui.navigation

import androidx.annotation.StringRes
import com.example.R

/**
 * Top-level destinations for FrameLine Studio's multi-route architecture.
 */
enum class StudioRoute(
  val route: String,
  @StringRes val labelRes: Int,
) {
  HOME("home", R.string.nav_home),
  MEDIA_PICKER("media_picker", R.string.nav_media),
  EDITOR("editor", R.string.nav_editor),
  AI_ASSISTANT("ai_assistant", R.string.nav_ai),
  EXPORT("export", R.string.nav_export),
}
