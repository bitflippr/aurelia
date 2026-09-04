package com.aurelia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.ArtistAvatar
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import uniffi.aurelia_core.Song

sealed class SearchResult {
  data class SongResult(
    val song: Song,
  ) : SearchResult()

  data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val albumArtUrl: String?,
  ) : SearchResult()

  data class Artist(
    val id: String?,
    val name: String,
    val songCount: Int,
    val imageUrl: String?,
  ) : SearchResult()
}

@Composable
fun SearchScreen(
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
  val results by libraryViewModel.searchResults.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val keyboardController = LocalSoftwareKeyboardController.current
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  var searchQuery by remember { mutableStateOf("") }

  val contextMenu = rememberContextMenuState()

  LaunchedEffect(Unit) {
    libraryViewModel.updateSearchQuery(searchQuery)
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .statusBarsPadding(),
  ) {
    // Search header
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      LibraryScreenHeader(
        title = "Search",
        subtitle = "Find songs, albums, and artists",
        modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
      )

      OutlinedTextField(
        value = searchQuery,
        onValueChange = {
          searchQuery = it
          libraryViewModel.updateSearchQuery(it)
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Songs, albums, artists...") },
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
          )
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = "Clear",
                tint = colors.onSurfaceVariant,
              )
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline.copy(alpha = 0.5f),
            focusedContainerColor = colors.surfaceContainerLow,
            unfocusedContainerColor = colors.surfaceContainerLow,
          ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
      )
    }

    // Results
    when {
      searchQuery.isEmpty() -> {
        LibraryMessageState(
          icon = Icons.Filled.Search,
          title = "Search your library",
          subtitle = "Type at least ${UiConstants.MIN_SEARCH_LENGTH} characters to start.",
          modifier = Modifier.fillMaxSize(),
        )
      }

      results.isEmpty() && searchQuery.length >= UiConstants.MIN_SEARCH_LENGTH -> {
        LibraryMessageState(
          icon = Icons.Filled.Search,
          title = "No results",
          subtitle = "Nothing matched \"$searchQuery\".",
          modifier = Modifier.fillMaxSize(),
        )
      }

      else -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 8.dp,
              bottom = bottomPadding,
            ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(results, key = { result ->
            when (result) {
              is SearchResult.SongResult -> "song_${result.song.id}"
              is SearchResult.Album -> "album_${result.id}"
              is SearchResult.Artist -> "artist_${result.name}"
            }
          }) { result ->
            when (result) {
              is SearchResult.SongResult -> {
                SongSearchResult(
                  song = result.song,
                  onClick = {
                    libraryViewModel.play(result.song.id)
                    onOpenPlayer()
                  },
                  onLongClick = { contextMenu.openContextMenu(result.song) },
                  onMoreClick = { contextMenu.openContextMenu(result.song) },
                  showContextMenu = contextMenu.showContextMenu && contextMenu.selectedSong?.id == result.song.id,
                  onDismissMenu = { contextMenu.dismissContextMenu() },
                  onAddToQueue = {
                    val serverUrl = sessionStore.getServerUrl() ?: return@SongSearchResult
                    val token = sessionStore.getToken() ?: return@SongSearchResult
                    playerController.addToQueue(result.song, serverUrl, token)
                  },
                  onPlayNext = {
                    val serverUrl = sessionStore.getServerUrl() ?: return@SongSearchResult
                    val token = sessionStore.getToken() ?: return@SongSearchResult
                    playerController.playNext(result.song, serverUrl, token)
                  },
                  onAddToPlaylist = { contextMenu.openPlaylistPicker(result.song) },
                  onGoToAlbum =
                    if (onNavigateToAlbum != null) {
                      result.song.safeAlbumId()?.let { albumId ->
                        {
                          onNavigateToAlbum(
                            Screen.AlbumDetail(
                              albumId = albumId,
                              albumName = result.song.album ?: "Unknown Album",
                            ),
                          )
                        }
                      }
                    } else {
                      null
                    },
                  onGoToArtist =
                    if (onNavigateToArtist != null) {
                      result.song.safePrimaryArtistId()?.let { artistId ->
                        {
                          onNavigateToArtist(
                            Screen.ArtistDetail(
                              artistId = artistId,
                              artistName = result.song.artists?.firstOrNull() ?: "Unknown Artist",
                            ),
                          )
                        }
                      }
                    } else {
                      null
                    },
                )
              }

              is SearchResult.Album -> {
                MediaListItem(
                  title = result.name,
                  subtitle = result.artist,
                  metadata = "Album",
                  imageUrl = result.albumArtUrl,
                  artworkStyle = AlbumArtStyle.Album,
                  onClick = {
                    onNavigateToAlbum?.invoke(
                      Screen.AlbumDetail(
                        albumId = result.id,
                        albumName = result.name,
                      ),
                    )
                  },
                )
              }

              is SearchResult.Artist -> {
                MediaListItem(
                  title = result.name,
                  subtitle = "${result.songCount} songs",
                  metadata = "Artist",
                  imageUrl = null,
                  leadingContent = { ArtistAvatar(size = 44.dp, imageUrl = result.imageUrl) },
                  onClick = {
                    result.id?.let { artistId ->
                      onNavigateToArtist?.invoke(
                        Screen.ArtistDetail(
                          artistId = artistId,
                          artistName = result.name,
                        ),
                      )
                    }
                  },
                )
              }
            }
          }
        }
      }
    }
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
private fun SongSearchResult(
  song: Song,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onMoreClick: () -> Unit,
  showContextMenu: Boolean,
  onDismissMenu: () -> Unit,
  onAddToQueue: () -> Unit,
  onPlayNext: () -> Unit,
  onAddToPlaylist: () -> Unit,
  onGoToAlbum: (() -> Unit)?,
  onGoToArtist: (() -> Unit)?,
) {
  val colors = MaterialTheme.colorScheme

  Box {
    Surface(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
          ),
      shape = RoundedCornerShape(12.dp),
      color = colors.surfaceContainerLow,
    ) {
      Row(
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AlbumArt(
          imageUrl = song.albumArtUrl,
          size = 44.dp,
          cornerRadius = 8.dp,
          style = AlbumArtStyle.Song,
        )
        Column(
          modifier =
            Modifier
              .weight(1f)
              .padding(horizontal = 12.dp),
        ) {
          Text(
            text = song.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = song.artists?.joinToString(", ") ?: "Unknown Artist",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.clickable(
                enabled = onGoToArtist != null,
                onClick = { onGoToArtist?.invoke() },
              ),
          )
        }
        Text(
          text = "Song",
          style = MaterialTheme.typography.labelSmall,
          color = colors.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Box {
          IconButton(onClick = onMoreClick) {
            Icon(
              imageVector = Icons.Filled.MoreVert,
              contentDescription = "More options",
              tint = colors.onSurfaceVariant,
            )
          }

          SongContextMenu(
            song = song,
            expanded = showContextMenu,
            onDismiss = onDismissMenu,
            onAddToQueue = onAddToQueue,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = onGoToArtist,
            onToggleFavorite = null,
          )
        }
      }
    }
  }
}

@Composable
private fun AlbumSearchResult(
  name: String,
  albumArtUrl: String?,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = colors.surfaceContainerLow,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AlbumArt(
        imageUrl = albumArtUrl,
        size = 44.dp,
        cornerRadius = 8.dp,
        style = AlbumArtStyle.Album,
      )
      Text(
        text = name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "Album",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
      )
    }
  }
}

@Composable
private fun ArtistSearchResult(
  name: String,
  songCount: Int,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = colors.surfaceContainerLow,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = colors.surfaceVariant,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
          )
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = name,
          style = MaterialTheme.typography.bodyLarge,
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
      Text(
        text = "Artist",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
      )
    }
  }
}
