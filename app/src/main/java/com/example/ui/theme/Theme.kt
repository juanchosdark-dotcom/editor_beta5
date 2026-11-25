package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class TrackSemanticColors(
  val videoTrack: Color,
  val audioTrack: Color,
  val subtitleTrack: Color,
  val adjustmentTrack: Color,
  val playhead: Color,
  val trimAmber: Color,
  val renderAmber: Color,
  val markerAmber: Color,
  val markerCyan: Color,
  val markerCoral: Color,
  val markerEmerald: Color,
  val markerViolet: Color,
)

val LocalTrackColors =
  staticCompositionLocalOf {
    TrackSemanticColors(
      videoTrack = StudioElectricCyan,
      audioTrack = StudioEmeraldSignal,
      subtitleTrack = StudioWarmAmber,
      adjustmentTrack = StudioIrisViolet,
      playhead = StudioElectricCyan,
      trimAmber = StudioWarmAmber,
      renderAmber = StudioWarmAmber,
      markerAmber = StudioWarmAmber,
      markerCyan = StudioElectricCyan,
      markerCoral = StudioCrimsonAlert,
      markerEmerald = StudioEmeraldSignal,
      markerViolet = StudioIrisViolet,
    )
  }

private val StudioShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
  )

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = StudioElectricCyan,
    onPrimary = StudioOnElectricCyan,
    primaryContainer = StudioElectricCyanMuted,
    onPrimaryContainer = StudioElectricCyanSoft,
    secondary = StudioElectricCyanSoft,
    onSecondary = StudioOnElectricCyan,
    secondaryContainer = StudioElectricCyanMuted,
    onSecondaryContainer = StudioElectricCyanSoft,
    tertiary = StudioWarmAmber,
    onTertiary = StudioOnWarmAmber,
    tertiaryContainer = StudioWarmAmberMuted,
    onTertiaryContainer = StudioWarmAmberSoft,
    background = StudioBaseBackground,
    onBackground = StudioTextPrimary,
    surface = StudioPanelSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioElevatedCard,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = StudioSlateVoid,
    surfaceContainerLow = StudioBaseBackground,
    surfaceContainer = StudioPanelSurface,
    surfaceContainerHigh = StudioElevatedCard,
    surfaceContainerHighest = StudioElevatedHighlight,
    outline = StudioGlassBorderStrong,
    outlineVariant = StudioGlassBorderSubtle,
    error = StudioCrimsonAlert,
    onError = Color.White,
  )

private val StudioLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF007B8C),
    onPrimary = Color.White,
    secondary = Color(0xFF0099AD),
    onSecondary = Color.White,
    tertiary = StudioWarmAmber,
    onTertiary = StudioOnWarmAmber,
    background = StudioLightBackground,
    onBackground = StudioLightTextPrimary,
    surface = StudioLightSurface,
    onSurface = StudioLightTextPrimary,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightTextSecondary,
    error = StudioCrimsonAlert,
    onError = Color.White,
  )

@Composable
fun FrameLineTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme
  val trackColors =
    TrackSemanticColors(
      videoTrack = StudioElectricCyan,
      audioTrack = StudioEmeraldSignal,
      subtitleTrack = StudioWarmAmber,
      adjustmentTrack = StudioIrisViolet,
      playhead = StudioElectricCyan,
      trimAmber = StudioWarmAmber,
      renderAmber = StudioWarmAmber,
      markerAmber = StudioWarmAmber,
      markerCyan = StudioElectricCyan,
      markerCoral = StudioCrimsonAlert,
      markerEmerald = StudioEmeraldSignal,
      markerViolet = StudioIrisViolet,
    )

  CompositionLocalProvider(LocalTrackColors provides trackColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      shapes = StudioShapes,
      content = content,
    )
  }
}
