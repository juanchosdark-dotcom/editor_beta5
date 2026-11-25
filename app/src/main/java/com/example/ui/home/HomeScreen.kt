package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.editor.RecentProjectSummary
import com.example.ui.editor.TimelineSkeletonUiState
import com.example.ui.editor.formatTimecode
import com.example.ui.navigation.StudioRoute
import com.example.ui.theme.LocalTrackColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
  uiState: TimelineSkeletonUiState,
  activeExportCount: Int,
  onNavigateTo: (StudioRoute) -> Unit,
  onOpenProject: (String) -> Unit,
  onCreateProject: (String) -> Unit,
  onSaveProject: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val trackColors = LocalTrackColors.current
  var showCreateDialog by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(
                Brush.horizontalGradient(
                  listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f),
                  ),
                ),
              )
              .padding(16.dp),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.app_name),
                  style = MaterialTheme.typography.headlineSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = stringResource(R.string.home_tagline),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              FilledTonalButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("home_new_project_button"),
              ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_new_project))
              }
            }

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
              ) {
                Text(
                  text = "Activo: ${uiState.projectName}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }
              Text(
                text = "${uiState.timeline.clips.size} clips · ${formatTimecode(uiState.timeline.totalDurationMs)} · ${uiState.appliedAiAdjustments.size} IA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
              )
              AssistChip(
                onClick = onSaveProject,
                leadingIcon = {
                  Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(stringResource(R.string.action_save_json)) },
                modifier = Modifier.testTag("home_save_project_button"),
              )
            }
          }
        }
      }
    }

    item {
      Text(
        text = stringResource(R.string.home_quick_actions),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(modifier = Modifier.height(10.dp))

      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
      ) {
        HomeDestinationCard(
          title = stringResource(R.string.nav_media),
          subtitle = "${uiState.assets.size} clips importados y validados",
          badgeText = "MediaProbe",
          icon = Icons.Default.AddPhotoAlternate,
          accentColor = trackColors.audioTrack,
          onClick = { onNavigateTo(StudioRoute.MEDIA_PICKER) },
          tag = "home_card_media_picker",
          modifier = Modifier.weight(1f),
        )
        HomeDestinationCard(
          title = stringResource(R.string.nav_editor),
          subtitle = "${uiState.timeline.tracks.size} pistas · ${uiState.timeline.markers.size} marcadores",
          badgeText = "Media3",
          icon = Icons.Default.Tune,
          accentColor = trackColors.playhead,
          onClick = { onNavigateTo(StudioRoute.EDITOR) },
          tag = "home_card_editor",
          modifier = Modifier.weight(1f),
        )
        HomeDestinationCard(
          title = stringResource(R.string.nav_ai),
          subtitle =
            if (uiState.localAiSettings.enabled) {
              "Activo · ${uiState.resourceSnapshot.thermalTier.name}"
            } else {
              "Desactivado (0% uso)"
            },
          badgeText = "Offline",
          icon = Icons.Default.AutoAwesome,
          accentColor = trackColors.adjustmentTrack,
          onClick = { onNavigateTo(StudioRoute.AI_ASSISTANT) },
          tag = "home_card_ai_assistant",
          modifier = Modifier.weight(1f),
        )
        HomeDestinationCard(
          title = stringResource(R.string.nav_export),
          subtitle = uiState.selectedExportPreset.displayName,
          badgeText = if (activeExportCount > 0) "$activeExportCount en cola" else "MP4 Local",
          icon = Icons.Default.MovieCreation,
          accentColor = trackColors.renderAmber,
          onClick = { onNavigateTo(StudioRoute.EXPORT) },
          tag = "home_card_export",
          modifier = Modifier.weight(1f),
        )
      }
    }

    item {
      Text(
        text = stringResource(R.string.home_recent_projects),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }

    items(uiState.recentProjects, key = { it.projectId }) { project ->
      RecentProjectCard(
        project = project,
        onOpen = {
          onOpenProject(project.projectId)
          onNavigateTo(StudioRoute.EDITOR)
        },
      )
    }
  }

  if (showCreateDialog) {
    CreateProjectDialog(
      onCreate = { name ->
        onCreateProject(name)
        showCreateDialog = false
        onNavigateTo(StudioRoute.MEDIA_PICKER)
      },
      onDismiss = { showCreateDialog = false },
    )
  }
}

@Composable
private fun HomeDestinationCard(
  title: String,
  subtitle: String,
  badgeText: String,
  icon: ImageVector,
  accentColor: Color,
  onClick: () -> Unit,
  tag: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .testTag(tag),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          color = accentColor.copy(alpha = 0.18f),
          shape = RoundedCornerShape(10.dp),
        ) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.padding(8.dp).size(22.dp),
          )
        }
        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
          Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
          )
        }
      }

      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun RecentProjectCard(
  project: RecentProjectSummary,
  onOpen: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (project.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
          } else {
            MaterialTheme.colorScheme.outlineVariant
          },
      ),
    modifier = Modifier.fillMaxWidth().testTag("recent_project_${project.projectId}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f),
      ) {
        Icon(
          imageVector = Icons.Default.FolderOpen,
          contentDescription = null,
          tint = if (project.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Column {
          Text(
            text = project.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val jsonTag = if (project.isPersistedJson) " · JSON" else ""
          val aiTag = if (project.aiAdjustmentCount > 0) " · ${project.aiAdjustmentCount} ajustes IA" else ""
          Text(
            text = "${project.clipCount} clips · ${formatTimecode(project.durationMs)}$aiTag$jsonTag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      FilledTonalButton(
        onClick = onOpen,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Text(if (project.isActive) "Continuar" else "Abrir")
      }
    }
  }
}

@Composable
private fun CreateProjectDialog(
  onCreate: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var projectName by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.home_new_project)) },
    text = {
      OutlinedTextField(
        value = projectName,
        onValueChange = { projectName = it },
        label = { Text("Nombre del proyecto") },
        placeholder = { Text("Reel Urbano 1080p") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onCreate(projectName) }) {
        Text("Crear e importar")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancelar")
      }
    },
  )
}
