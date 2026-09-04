package com.aurelia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import uniffi.aurelia_core.Song

@Composable
fun LibraryScreen(
  libraryViewModel: LibraryViewModel,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onOpenPlayer: () -> Unit,
  onNavigateToAlbum: ((Screen.AlbumDetail) -> Unit)? = null,
  onNavigateToArtist: ((Screen.ArtistDetail) -> Unit)? = null,
  hasPlayerBar: Boolean = false,
) {
  val state by libraryViewModel.state.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  val contextMenu = rememberContextMenuState()

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .statusBarsPadding(),
  ) {
    // Song list
    when {
      state.isLoading -> {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(color = colors.primary)
        }
      }

      state.error != null -> {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = state.error ?: "",
            color = colors.error,
            style = MaterialTheme.typography.bodyLarge,
          )
        }
      }

      else -> {
        val currentSongId = state.currentSongId
        val isPlaying = state.nowPlaying?.isPlaying == true

        LazyColumn(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f),
          contentPadding =
            PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 8.dp,
              bottom = bottomPadding,
            ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(key = "header") {
            LibraryScreenHeader(
              title = "Songs",
              subtitle = "${state.songs.size} songs",
              modifier = Modifier.padding(horizontal = 8.dp),
            )
          }

          items(
            items = state.songs,
            key = { song -> song.id },
          ) { song ->
            val isCurrentSong = song.id == currentSongId

            MediaListItem(
              title = song.name,
              subtitle = song.artists?.joinToString(", ") ?: "Unknown Artist",
              imageUrl = song.albumArtUrl,
              artworkStyle = AlbumArtStyle.Song,
              isPlaying = isPlaying && isCurrentSong,
              isCurrent = isCurrentSong,
              showMore = true,
              onClick = {
                libraryViewModel.playFromList(song.id)
                onOpenPlayer()
              },
              onLongClick = { contextMenu.openContextMenu(song) },
              onMoreClick = { contextMenu.openContextMenu(song) },
            )
          }
        }
      }
    }
  }

  // Context menu
  val selectedSong = contextMenu.selectedSong
  if (contextMenu.showContextMenu && selectedSong != null) {
    SongContextMenu(
      song = selectedSong,
      expanded = true,
      onDismiss = { contextMenu.dismissContextMenu() },
      onAddToQueue = {
        val serverUrl = sessionStore.getServerUrl()
        val token = sessionStore.getToken()
        if (serverUrl != null && token != null) {
          playerController.addToQueue(selectedSong, serverUrl, token)
        }
      },
      onPlayNext = {
        val serverUrl = sessionStore.getServerUrl()
        val token = sessionStore.getToken()
        if (serverUrl != null && token != null) {
          playerController.playNext(selectedSong, serverUrl, token)
        }
      },
      onAddToPlaylist = {
        contextMenu.showPlaylistPicker = true
      },
      onGoToAlbum =
        if (onNavigateToAlbum != null) {
          selectedSong.safeAlbumId()?.let { albumId ->
            {
              onNavigateToAlbum(
                Screen.AlbumDetail(
                  albumId = albumId,
                  albumName = selectedSong.album ?: "Unknown Album",
                ),
              )
            }
          }
        } else {
          null
        },
      onGoToArtist =
        if (onNavigateToArtist != null) {
          selectedSong.safePrimaryArtistId()?.let { artistId ->
            {
              onNavigateToArtist(
                Screen.ArtistDetail(
                  artistId = artistId,
                  artistName = selectedSong.artists?.firstOrNull() ?: "Unknown Artist",
                ),
              )
            }
          }
        } else {
          null
        },
    )
  }

  // Playlist picker dialog
  if (contextMenu.showPlaylistPicker && contextMenu.selectedSong != null) {
    PlaylistPickerDialog(
      playlists = playlistState.playlists,
      isLoading = playlistState.isLoading,
      onDismiss = { contextMenu.dismissPlaylistPicker() },
      onSelectPlaylist = { playlist ->
        contextMenu.selectedSong?.let { song ->
          playlistViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
        }
        contextMenu.dismissPlaylistPicker()
      },
      onCreatePlaylist = { name ->
        contextMenu.selectedSong?.let { song ->
          playlistViewModel.createPlaylist(name, listOf(song.id))
        }
        contextMenu.dismissPlaylistPicker()
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EnhancedSongItem(
  song: Song,
  isPlaying: Boolean,
  isCurrentSong: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onMoreClick: () -> Unit,
  onAddToQueue: () -> Unit,
  onPlayNext: () -> Unit,
  onAddToPlaylist: () -> Unit,
  onGoToAlbum: (() -> Unit)?,
  onGoToArtist: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme

  val cornerRadius = if (isCurrentSong) 50.dp else 22.dp
  val albumCornerRadius = if (isCurrentSong) 50.dp else 12.dp

  val containerColor = if (isCurrentSong) colors.primaryContainer else colors.surfaceContainerLow
  val contentColor = if (isCurrentSong) colors.onPrimaryContainer else colors.onSurface
  val secondaryContentColor =
    if (isCurrentSong) colors.onPrimaryContainer.copy(alpha = 0.7f) else colors.onSurfaceVariant

  Box {
    Surface(
      modifier =
        modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(cornerRadius))
          .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
          ),
      shape = RoundedCornerShape(cornerRadius),
      color = containerColor,
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Album art with play indicator overlay
        Box(modifier = Modifier.size(56.dp)) {
          AlbumArt(
            imageUrl = song.albumArtUrl,
            size = 56.dp,
            cornerRadius = albumCornerRadius,
            style = AlbumArtStyle.Song,
            containerColor = if (isCurrentSong) colors.primary.copy(alpha = 0.2f) else colors.surfaceVariant,
          )
          if (isPlaying) {
            Surface(
              modifier = Modifier.size(56.dp),
              shape = RoundedCornerShape(albumCornerRadius),
              color = colors.primary.copy(alpha = 0.7f),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Filled.PlayArrow,
                  contentDescription = null,
                  tint = colors.onPrimary,
                  modifier = Modifier.size(24.dp),
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Song info
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = song.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = song.artists?.joinToString(", ") ?: "Unknown Artist",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.clickable(
                enabled = onGoToArtist != null,
                onClick = { onGoToArtist?.invoke() },
              ),
          )
        }

        // More options button
        Box {
          IconButton(onClick = onMoreClick) {
            Icon(
              imageVector = Icons.Filled.MoreVert,
              contentDescription = "More options",
              tint = secondaryContentColor,
            )
          }
        }
      }
    }
  }
}
