package com.aurelia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.ai.AiDebugLog
import com.aurelia.app.ai.AiGenerationState
import com.aurelia.app.ai.AiModelDownloadState
import com.aurelia.app.ai.DEFAULT_SMART_PLAYLIST_SIZE
import com.aurelia.app.ai.MAX_SMART_PLAYLIST_SIZE
import com.aurelia.app.ai.MIN_SMART_PLAYLIST_SIZE
import com.aurelia.app.ai.OnDeviceAiModels
import com.aurelia.app.ai.SmartPlaylistPreview
import com.aurelia.app.ai.SmartPlaylistRequest
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.navigation.Screen
import uniffi.aurelia_core.Playlist
import uniffi.aurelia_core.Song

private val MiniPlayerHeight = 64.dp
private val NavBarContentHeight = 90.dp

@Composable
fun PlaylistsScreen(
  viewModel: PlaylistViewModel,
  librarySongs: List<Song>,
  onOpenPlayer: () -> Unit,
  onNavigateToPlaylist: (Screen.PlaylistDetail) -> Unit,
  hasPlayerBar: Boolean = false,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val smartPlaylistState by viewModel.smartPlaylistState.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme

  var showCreateDialog by remember { mutableStateOf(false) }
  var showSmartPlaylistDialog by remember { mutableStateOf(false) }
  var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

  LaunchedEffect(Unit) {
    viewModel.ensureLoaded()
    viewModel.refreshAiModelState()
  }

  val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val fabBottomPadding =
    NavBarContentHeight + systemNavBarInset + 24.dp +
      (if (hasPlayerBar) MiniPlayerHeight + 12.dp else 0.dp)

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .statusBarsPadding(),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      when {
        state.isLoading -> {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator()
          }
        }

        state.error != null -> {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = state.error ?: "An error occurred",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.error,
                textAlign = TextAlign.Center,
              )
              Spacer(modifier = Modifier.height(16.dp))
              TextButton(onClick = { viewModel.ensureLoaded(force = true) }) {
                Text("Retry")
              }
            }
          }
        }

        state.playlists.isEmpty() -> {
          LibraryMessageState(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "No playlists yet",
            subtitle = "Create a playlist to organize your favorite music.",
            modifier = Modifier.fillMaxSize(),
          )
        }

        else -> {
          val bottomPadding =
            NavBarContentHeight + systemNavBarInset +
              (if (hasPlayerBar) MiniPlayerHeight + 12.dp else 0.dp)

          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
              PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = bottomPadding + 80.dp,
              ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            item(key = "header") {
              Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                LibraryScreenHeader(
                  title = "Playlists",
                  subtitle = "Your music collections",
                )
                Button(
                  onClick = { showSmartPlaylistDialog = true },
                  enabled = librarySongs.isNotEmpty(),
                ) {
                  Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Smart playlist")
                }
              }
            }

            items(state.playlists, key = { it.id }) { playlist ->
              MediaListItem(
                title = playlist.name,
                subtitle = "${playlist.childCount ?: 0} songs",
                imageUrl = null,
                artworkStyle = AlbumArtStyle.Playlist,
                showMore = playlist.canDelete == true,
                onClick = {
                  onNavigateToPlaylist(
                    Screen.PlaylistDetail(
                      playlistId = playlist.id,
                      playlistName = playlist.name,
                    ),
                  )
                },
                onMoreClick = { playlistToDelete = playlist },
              )
            }
          }
        }
      }
    }

    // FAB
    FloatingActionButton(
      onClick = { showCreateDialog = true },
      modifier =
        Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 16.dp, bottom = fabBottomPadding),
      containerColor = colors.primary,
      contentColor = colors.onPrimary,
    ) {
      Icon(
        imageVector = Icons.Filled.Add,
        contentDescription = "Create playlist",
      )
    }
  }

  // Create playlist dialog
  if (showCreateDialog) {
    CreatePlaylistDialog(
      isCreating = state.isCreating,
      onDismiss = { showCreateDialog = false },
      onCreate = { name ->
        viewModel.createPlaylist(name)
        showCreateDialog = false
      },
    )
  }

  if (showSmartPlaylistDialog) {
    SmartPlaylistDialog(
      generation = smartPlaylistState.generation,
      modelDownload = smartPlaylistState.modelDownload,
      songCount = librarySongs.size,
      isCreating = state.isCreating,
      onDismiss = {
        showSmartPlaylistDialog = false
        viewModel.clearSmartPlaylistGeneration()
      },
      onGenerate = { request ->
        viewModel.generateSmartPlaylist(request, librarySongs)
      },
      onDownloadModel = viewModel::downloadAiModel,
      onPlay = viewModel::playSmartPlaylist,
      onSave = { preview ->
        viewModel.saveSmartPlaylist(preview)
        showSmartPlaylistDialog = false
        viewModel.clearSmartPlaylistGeneration()
      },
    )
  }

  // Delete confirmation dialog
  playlistToDelete?.let { playlist ->
    AlertDialog(
      onDismissRequest = { playlistToDelete = null },
      title = { Text("Delete Playlist") },
      text = { Text("Are you sure you want to delete \"${playlist.name}\"?") },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deletePlaylist(playlist.id)
            playlistToDelete = null
          },
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { playlistToDelete = null }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun EmptyPlaylistsView() {
  val colors = MaterialTheme.colorScheme

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        modifier = Modifier.size(80.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceVariant,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp),
          )
        }
      }
      Text(
        text = "No playlists yet",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
      )
      Text(
        text = "Create a playlist to organize your favorite music",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun PlaylistItem(
  playlist: Playlist,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val songCount = playlist.childCount ?: 0

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick),
    color = colors.surface,
    tonalElevation = 1.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Playlist artwork placeholder
      AlbumArt(
        imageUrl = null, // Playlists don't have images by default
        size = 56.dp,
        cornerRadius = 8.dp,
        style = AlbumArtStyle.Playlist,
        containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
      )

      Spacer(modifier = Modifier.width(16.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = playlist.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "$songCount songs",
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }

      if (playlist.canDelete == true) {
        IconButton(onClick = onDelete) {
          Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Delete playlist",
            tint = colors.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun CreatePlaylistDialog(
  isCreating: Boolean,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create Playlist") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Playlist name") },
        singleLine = true,
        enabled = !isCreating,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions =
          KeyboardActions(
            onDone = {
              if (name.isNotBlank()) {
                onCreate(name.trim())
              }
            },
          ),
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onCreate(name.trim()) },
        enabled = name.isNotBlank() && !isCreating,
      ) {
        if (isCreating) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Text("Create")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isCreating) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun SmartPlaylistDialog(
  generation: AiGenerationState,
  modelDownload: AiModelDownloadState,
  songCount: Int,
  isCreating: Boolean,
  onDismiss: () -> Unit,
  onGenerate: (SmartPlaylistRequest) -> Unit,
  onDownloadModel: () -> Unit,
  onPlay: (SmartPlaylistPreview) -> Unit,
  onSave: (SmartPlaylistPreview) -> Unit,
) {
  var prompt by remember { mutableStateOf("") }
  var targetCount by remember { mutableStateOf(DEFAULT_SMART_PLAYLIST_SIZE) }
  val context = LocalContext.current
  val diagnostics by AiDebugLog.entries.collectAsStateWithLifecycle()
  val preview = (generation as? AiGenerationState.Preview)?.preview
  val isLoading = generation is AiGenerationState.Loading
  val isDownloading = modelDownload is AiModelDownloadState.Downloading
  val isModelReady = modelDownload is AiModelDownloadState.Ready

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Filled.AutoAwesome,
        contentDescription = null,
      )
    },
    title = { Text("Smart playlist") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = { Text("Describe the mix") },
          placeholder = { Text("Late-night synths with a few favorites") },
          enabled = !isLoading,
          minLines = 2,
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(15, DEFAULT_SMART_PLAYLIST_SIZE, MAX_SMART_PLAYLIST_SIZE).forEach { count ->
            FilterChip(
              selected = targetCount == count,
              onClick = { targetCount = count },
              enabled = !isLoading,
              label = { Text("$count") },
            )
          }
        }

        Text(
          text = "$songCount synced songs available",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AiModelDownloadBlock(
          modelDownload = modelDownload,
          onDownloadModel = onDownloadModel,
        )

        when (generation) {
          is AiGenerationState.Idle -> Unit
          is AiGenerationState.Loading -> {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              Text(generation.message)
            }
          }
          is AiGenerationState.Error -> {
            Text(
              text = generation.message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          is AiGenerationState.Preview -> {
            SmartPlaylistPreviewBlock(generation.preview)
          }
        }

        if (diagnostics.isNotEmpty()) {
          AiDiagnosticsBlock(
            diagnostics = diagnostics,
            onCopy = { copyAiDiagnostics(context) },
          )
        }
      }
    },
    confirmButton = {
      if (preview == null) {
        Button(
          onClick = {
            onGenerate(
              SmartPlaylistRequest(
                prompt = prompt.trim(),
                targetCount = targetCount.coerceIn(MIN_SMART_PLAYLIST_SIZE, MAX_SMART_PLAYLIST_SIZE),
              ),
            )
          },
          enabled = prompt.isNotBlank() && !isLoading && !isDownloading && isModelReady && songCount > 0,
        ) {
          Text("Generate")
        }
      } else {
        Button(
          onClick = { onSave(preview) },
          enabled = !isCreating,
        ) {
          Text(if (isCreating) "Saving" else "Save")
        }
      }
    },
    dismissButton = {
      Row {
        preview?.let {
          TextButton(onClick = { onPlay(it) }) {
            Text("Play")
          }
        }
        TextButton(onClick = onDismiss) {
          Text("Cancel")
        }
      }
    },
  )
}

@Composable
private fun AiModelDownloadBlock(
  modelDownload: AiModelDownloadState,
  onDownloadModel: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      when (modelDownload) {
        is AiModelDownloadState.Ready -> {
          Text(
            text = "Gemma model ready",
            style = MaterialTheme.typography.labelLarge,
          )
          Text(
            text = modelDownload.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        is AiModelDownloadState.Downloading -> {
          val progress =
            if (modelDownload.totalBytes != null && modelDownload.totalBytes > 0L) {
              modelDownload.bytesRead.toFloat() / modelDownload.totalBytes.toFloat()
            } else {
              null
            }
          Text(
            text = "Downloading ${modelDownload.modelName}",
            style = MaterialTheme.typography.labelLarge,
          )
          if (progress != null) {
            LinearProgressIndicator(
              progress = { progress.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          Text(
            text = "${formatBytes(
              modelDownload.bytesRead,
            )} / ${modelDownload.totalBytes?.let(::formatBytes) ?: "unknown"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is AiModelDownloadState.Error -> {
          Text(
            text = modelDownload.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          DownloadModelButton(onDownloadModel)
        }
        AiModelDownloadState.Idle,
        is AiModelDownloadState.Missing,
        -> {
          Text(
            text = "${OnDeviceAiModels.default.name} is required for on-device playlist generation.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "Download size: ${OnDeviceAiModels.default.sizeLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          DownloadModelButton(onDownloadModel)
        }
      }
    }
  }
}

@Composable
private fun DownloadModelButton(onDownloadModel: () -> Unit) {
  TextButton(onClick = onDownloadModel) {
    Icon(
      imageVector = Icons.Filled.Download,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text("Download model")
  }
}

private fun formatBytes(bytes: Long): String {
  val gib = bytes / (1024.0 * 1024.0 * 1024.0)
  if (gib >= 1.0) return String.format("%.1f GB", gib)
  val mib = bytes / (1024.0 * 1024.0)
  return String.format("%.0f MB", mib)
}

private fun copyAiDiagnostics(context: Context) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("Aurelia AI diagnostics", AiDebugLog.text()))
}

@Composable
private fun AiDiagnosticsBlock(
  diagnostics: List<String>,
  onCopy: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Diagnostics",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onCopy) {
        Text("Copy")
      }
    }
    Surface(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(96.dp),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
      LazyColumn(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(diagnostics.takeLast(10)) { line ->
          Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun SmartPlaylistPreviewBlock(preview: SmartPlaylistPreview) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = preview.name,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = preview.description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    preview.fallbackReason?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    LazyColumn(
      modifier = Modifier.height(180.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      items(preview.songs.take(12)) { song ->
        Text(
          text = "${runCatching { song.name }.getOrNull().orEmpty()} - ${song.artists?.joinToString(", ").orEmpty()}",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
