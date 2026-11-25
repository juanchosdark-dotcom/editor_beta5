package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.ai.AiAssistantScreen
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.TimelineSkeletonViewModel
import com.example.ui.export.ExportScreen
import com.example.ui.home.HomeScreen
import com.example.ui.mediapicker.MediaPickerScreen

@Composable
fun FrameLineApp(
  viewModel: TimelineSkeletonViewModel,
  modifier: Modifier = Modifier,
) {
  val navController = rememberNavController()
  val appLifecycleOwner = LocalLifecycleOwner.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val previewState by viewModel.previewState.collectAsStateWithLifecycle()
  val exportQueue by viewModel.exportQueue.collectAsStateWithLifecycle()
  val activeExportCount by viewModel.activeExportCount.collectAsStateWithLifecycle()

  DisposableEffect(appLifecycleOwner, viewModel.previewController) {
    viewModel.previewController?.attachToLifecycle(appLifecycleOwner.lifecycle)
    viewModel.previewController?.restoreIfNeeded(uiState.timeline.playheadMs)
    onDispose {
      viewModel.previewController?.pausePlayback()
    }
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route ?: StudioRoute.HOME.route

  val navigateTopLevel: (StudioRoute) -> Unit = { target ->
    navController.navigate(target.route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    val isLandscapeViewport = maxWidth > maxHeight
    val useNavigationRail = maxWidth >= 600.dp || isLandscapeViewport
    val showRailLabels = maxHeight >= 480.dp

    if (useNavigationRail) {
      Row(
        modifier =
          Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        val railItemColors =
          NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        NavigationRail(
          containerColor = MaterialTheme.colorScheme.surface,
        ) {
          Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
          ) {
            StudioRoute.entries.forEach { destination ->
              val isSelected = currentRoute == destination.route
              NavigationRailItem(
                selected = isSelected,
                onClick = { navigateTopLevel(destination) },
                alwaysShowLabel = showRailLabels,
                colors = railItemColors,
                icon = {
                  RouteNavIcon(
                    route = destination,
                    selected = isSelected,
                    activeExportCount = activeExportCount,
                  )
                },
                label =
                  if (showRailLabels) {
                    {
                      NavDestinationLabel(
                        text = stringResource(destination.labelRes),
                        selected = isSelected,
                      )
                    }
                  } else {
                    null
                  },
                modifier = Modifier.testTag("nav_rail_${destination.route}"),
              )
            }
          }
        }
        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        StudioNavHost(
          navController = navController,
          viewModel = viewModel,
          uiState = uiState,
          previewState = previewState,
          exportQueue = exportQueue,
          activeExportCount = activeExportCount,
          onNavigate = navigateTopLevel,
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
      }
    } else {
      val navBarItemColors =
        NavigationBarItemDefaults.colors(
          selectedIconColor = MaterialTheme.colorScheme.primary,
          selectedTextColor = MaterialTheme.colorScheme.primary,
          indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
          unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
          unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
          Column {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              tonalElevation = 0.dp,
            ) {
              StudioRoute.entries.forEach { destination ->
                val isSelected = currentRoute == destination.route
                NavigationBarItem(
                  selected = isSelected,
                  onClick = { navigateTopLevel(destination) },
                  alwaysShowLabel = true,
                  colors = navBarItemColors,
                  icon = {
                    RouteNavIcon(
                      route = destination,
                      selected = isSelected,
                      activeExportCount = activeExportCount,
                    )
                  },
                  label = {
                    NavDestinationLabel(
                      text = stringResource(destination.labelRes),
                      selected = isSelected,
                    )
                  },
                  modifier = Modifier.testTag("nav_bar_${destination.route}"),
                )
              }
            }
          }
        },
      ) { innerPadding ->
        StudioNavHost(
          navController = navController,
          viewModel = viewModel,
          uiState = uiState,
          previewState = previewState,
          exportQueue = exportQueue,
          activeExportCount = activeExportCount,
          onNavigate = navigateTopLevel,
          modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
      }
    }
  }
}

@Composable
private fun NavDestinationLabel(
  text: String,
  selected: Boolean,
) {
  Text(
    text = text,
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Ellipsis,
    style =
      MaterialTheme.typography.bodyMedium.copy(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      ),
  )
}

@Composable
private fun StudioNavHost(
  navController: NavHostController,
  viewModel: TimelineSkeletonViewModel,
  uiState: com.example.ui.editor.TimelineSkeletonUiState,
  previewState: com.example.core.media.playback.PreviewPlayerState,
  exportQueue: List<com.example.core.export.ExportQueueItem>,
  activeExportCount: Int,
  onNavigate: (StudioRoute) -> Unit,
  modifier: Modifier = Modifier,
) {
  NavHost(
    navController = navController,
    startDestination = StudioRoute.HOME.route,
    modifier = modifier,
  ) {
    composable(StudioRoute.HOME.route) {
      HomeScreen(
        uiState = uiState,
        activeExportCount = activeExportCount,
        onNavigateTo = onNavigate,
        onOpenProject = viewModel::openRecentProject,
        onCreateProject = viewModel::createNewProject,
        onSaveProject = viewModel::saveProjectNow,
      )
    }

    composable(StudioRoute.MEDIA_PICKER.route) {
      MediaPickerScreen(
        uiState = uiState,
        validateAsset = viewModel::validateAsset,
        onImportVideoUri = { uri -> viewModel.importLocalVideo(uri, addToTimelineImmediately = false) },
        onSendAssetToEditor = viewModel::appendAssetToTimeline,
        onSendAssetsToEditor = viewModel::appendAssetsToTimeline,
        onLoadThumbnail = { assetId -> viewModel.loadClipThumbnail(assetId, 0L) },
        onOpenEditor = { onNavigate(StudioRoute.EDITOR) },
      )
    }

    composable(StudioRoute.EDITOR.route) {
      EditorScreen(
        uiState = uiState,
        previewState = previewState,
        viewModel = viewModel,
        onOpenMediaPicker = { onNavigate(StudioRoute.MEDIA_PICKER) },
      )
    }

    composable(StudioRoute.AI_ASSISTANT.route) {
      AiAssistantScreen(
        uiState = uiState,
        isOfflineSpeechSupported = viewModel.isOfflineSpeechSupported(),
        onRefreshResources = viewModel::refreshResourceSnapshot,
        onToggleEnabled = viewModel::setLocalAiEnabled,
        onSelectProfile = viewModel::selectLocalModelProfile,
        onToggleBatterySaverAllowed = viewModel::setAllowOnBatterySaver,
        onAnalyzeClipEdits = viewModel::analyzeCurrentClipWithAssistant,
        onApplySuggestion = viewModel::applyEditSuggestion,
        onRevertSuggestion = viewModel::revertEditSuggestion,
        onDismissSuggestion = viewModel::dismissEditSuggestion,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onAnalyzeRhythmAndSilences = viewModel::analyzeRhythmAndSilences,
        onCreateBeatMarkers = viewModel::createMarkersFromBeats,
        onRemoveSilences = viewModel::removeDetectedSilences,
        onStartOfflineDictation = viewModel::startOfflineSubtitleDictation,
      )
    }

    composable(StudioRoute.EXPORT.route) {
      ExportScreen(
        uiState = uiState,
        exportQueue = exportQueue,
        onSelectPreset = viewModel::selectExportPreset,
        onEnqueueRange = viewModel::enqueueExportRange,
        onEnqueueChapters = viewModel::enqueueAllMarkerChapters,
        onGenerateProxies = viewModel::generateProxiesForAllAssets,
        onCancelJob = viewModel::cancelExportJob,
        onRetryJob = viewModel::retryExportJob,
        onClearFinished = viewModel::clearFinishedExports,
        onRefreshStorageStats = viewModel::refreshCacheStorageStats,
        onClearTemporaryCache = viewModel::clearTemporaryCache,
      )
    }
  }
}

@Composable
private fun RouteNavIcon(
  route: StudioRoute,
  selected: Boolean,
  activeExportCount: Int,
) {
  val icon: ImageVector =
    when (route) {
      StudioRoute.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
      StudioRoute.MEDIA_PICKER -> if (selected) Icons.Filled.AddPhotoAlternate else Icons.Outlined.AddPhotoAlternate
      StudioRoute.EDITOR -> if (selected) Icons.Filled.Tune else Icons.Outlined.Tune
      StudioRoute.AI_ASSISTANT -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
      StudioRoute.EXPORT -> if (selected) Icons.Filled.MovieCreation else Icons.Outlined.MovieCreation
    }

  if (route == StudioRoute.EXPORT && activeExportCount > 0) {
    BadgedBox(
      badge = {
        Badge(
          containerColor = MaterialTheme.colorScheme.tertiary,
          contentColor = MaterialTheme.colorScheme.onTertiary,
        ) {
          Text(activeExportCount.toString())
        }
      },
    ) {
      Icon(imageVector = icon, contentDescription = stringResource(route.labelRes))
    }
  } else {
    Icon(imageVector = icon, contentDescription = stringResource(route.labelRes))
  }
}
