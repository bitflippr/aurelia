package com.aurelia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.ActionButtonRow
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.DetailHeroGradient
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.SquircleShape
import com.aurelia.app.ui.theme.rememberGoogleSansFlexWideFont
import com.aurelia.app.utils.formatDuration
import com.aurelia.app.utils.optimizedArtworkUrl
import uniffi.aurelia_core.Song

@Composable
fun AlbumDetailScreen(
  libraryViewModel: LibraryViewModel,
  albumId: String,
  albumName: String,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit,
  onNavigateToArtist: ((Screen.ArtistDetail) -> Unit)? = null,
  hasPlayerBar: Boolean = false,
) {
  val state by libraryViewModel.state.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val wideFont = rememberGoogleSansFlexWideFont()

  val contextMenu = rememberContextMenuState()

  // Calculate bottom padding for miniplayer
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  // Filter songs for this album and sort by disc then track
  val albumSongs =
    remember(state.songs, albumId) {
      state.songs
        .filter { it.albumId == albumId }
        .sortedWith(
          compareBy(
            { it.discNumber ?: 1 }, // Sort by disc number first
            { it.trackNumber ?: Int.MAX_VALUE }, // Then by track number
          ),
        )
    }

  // Check if album has multiple discs
  val hasMultipleDiscs = albumSongs.map { it.discNumber ?: 1 }.distinct().size > 1

  // Build list items with disc headers
  val listItems =
    remember(albumSongs, hasMultipleDiscs) {
      if (!hasMultipleDiscs) {
        // No disc headers needed
        albumSongs.map { ListItem.SongItem(it) }
      } else {
        // Insert disc headers
        val items = mutableListOf<ListItem>()
        var currentDisc: Int? = null
        var songIndex = 0

        for (song in albumSongs) {
          val songDisc = song.discNumber ?: 1
          if (songDisc != currentDisc) {
            items.add(ListItem.DiscHeader(songDisc))
            currentDisc = songDisc
          }
          items.add(ListItem.SongItem(song, songIndex))
          songIndex++
        }
        items
      }
    }

  val albumArtUrl = albumSongs.firstOrNull()?.albumArtUrl
  val artistName = albumSongs.firstOrNull()?.artists?.joinToString(", ") ?: "Unknown Artist"
  val artistId = albumSongs.firstOrNull()?.artistIds?.firstOrNull()

  val gradient = DetailHeroGradient()

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(gradient)
        .statusBarsPadding(),
  ) {
    // Header with back button
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back",
          tint = colors.onPrimaryContainer,
        )
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
      // Album header with art
      item {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          // Album art
          Surface(
            modifier =
              Modifier
                .size(240.dp)
                .clip(SquircleShape),
            shape = SquircleShape,
            color = colors.surfaceVariant,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
          ) {
            if (albumArtUrl.isNullOrBlank()) {
              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.Album,
                  contentDescription = null,
                  modifier = Modifier.size(80.dp),
                  tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                )
              }
            } else {
              val context = LocalContext.current
              // Album art is displayed at 240dp, 300px is plenty
              val artworkSize = with(LocalDensity.current) { 300.dp.toPx().toInt() }
              AsyncImage(
                model =
                  ImageRequest
                    .Builder(context)
                    .data(optimizedArtworkUrl(albumArtUrl, artworkSize))
                    .crossfade(true)
                    .size(artworkSize)
                    .build(),
                contentDescription = albumName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
              )
            }
          }

          Spacer(modifier = Modifier.height(24.dp))

          // Album name with display font for impact
          Text(
            text = albumName,
            style =
              MaterialTheme.typography.headlineLarge.copy(
                fontFamily = wideFont,
                fontSize = 32.sp,
                lineHeight = 40.sp,
              ),
            fontWeight = FontWeight.Black,
            color = colors.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
          )

          Spacer(modifier = Modifier.height(8.dp))

          // Artist name
          Text(
            text = artistName,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onPrimaryContainer.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier =
              Modifier.clickable(
                enabled = artistId != null && onNavigateToArtist != null,
                onClick = {
                  artistId?.let { id ->
                    onNavigateToArtist?.invoke(Screen.ArtistDetail(id, artistName))
                  }
                },
              ),
          )

          // Song count
          Text(
            text = "${albumSongs.size} songs • ${calculateTotalDuration(albumSongs)}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onPrimaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
          )

          Spacer(modifier = Modifier.height(24.dp))

          ActionButtonRow(
            enabled = albumSongs.isNotEmpty(),
            onPlay = {
              val serverUrl = sessionStore.getServerUrl() ?: return@ActionButtonRow
              val token = sessionStore.getToken() ?: return@ActionButtonRow

              playerController.setQueue(albumSongs, serverUrl, token, 0)
              onOpenPlayer()
            },
            onShuffle = {
              val serverUrl = sessionStore.getServerUrl() ?: return@ActionButtonRow
              val token = sessionStore.getToken() ?: return@ActionButtonRow

              playerController.setQueue(albumSongs.shuffled(), serverUrl, token, 0)
              onOpenPlayer()
            },
          )

          Spacer(modifier = Modifier.height(32.dp))
        }
      }

      // Song list with disc headers
      items(
        listItems,
        key = { item ->
          when (item) {
            is ListItem.DiscHeader -> "disc-${item.discNumber}"
            is ListItem.SongItem -> item.song.id
          }
        },
      ) { item ->
        when (item) {
          is ListItem.DiscHeader -> {
            // Disc header
            Text(
              text = "Disc ${item.discNumber}",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = colors.onSurfaceVariant,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          is ListItem.SongItem -> {
            val song = item.song
            val index = if (item.index >= 0) item.index else albumSongs.indexOf(song)
            val isCurrentSong = song.id == state.currentSongId
            val isPlaying = state.nowPlaying?.isPlaying == true && isCurrentSong

            AlbumSongItem(
              song = song,
              trackNumber = song.trackNumber ?: (index + 1),
              duration = song.duration?.let { formatDuration((it * 1000).toLong()) },
              isPlaying = isPlaying,
              isCurrentSong = isCurrentSong,
              onClick = {
                val serverUrl = sessionStore.getServerUrl() ?: return@AlbumSongItem
                val token = sessionStore.getToken() ?: return@AlbumSongItem

                playerController.setQueue(albumSongs, serverUrl, token, index)
                onOpenPlayer()
              },
              onLongClick = { contextMenu.openContextMenu(song) },
              onMoreClick = { contextMenu.openContextMenu(song) },
              showContextMenu = contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
              onDismissMenu = { contextMenu.dismissContextMenu() },
              onAddToQueue = {
                val serverUrl = sessionStore.getServerUrl() ?: return@AlbumSongItem
                val token = sessionStore.getToken() ?: return@AlbumSongItem
                playerController.addToQueue(song, serverUrl, token)
              },
              onPlayNext = {
                val serverUrl = sessionStore.getServerUrl() ?: return@AlbumSongItem
                val token = sessionStore.getToken() ?: return@AlbumSongItem
                playerController.playNext(song, serverUrl, token)
              },
              onAddToPlaylist = { contextMenu.openPlaylistPicker(song) },
              onGoToArtist =
                if (onNavigateToArtist != null) {
                  song.safePrimaryArtistId()?.let { artistId ->
                    {
                      onNavigateToArtist(
                        Screen.ArtistDetail(
                          artistId = artistId,
                          artistName = song.artists?.firstOrNull() ?: "Unknown Artist",
                        ),
                      )
                    }
                  }
                } else {
                  null
                },
            )
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
private fun AlbumSongItem(
  song: Song,
  trackNumber: Int,
  duration: String?,
  isPlaying: Boolean,
  isCurrentSong: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onMoreClick: () -> Unit,
  showContextMenu: Boolean,
  onDismissMenu: () -> Unit,
  onAddToQueue: () -> Unit,
  onPlayNext: () -> Unit,
  onAddToPlaylist: () -> Unit,
  onGoToArtist: (() -> Unit)?,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor =
    if (isCurrentSong) colors.primaryContainer.copy(alpha = 0.5f) else colors.surface.copy(alpha = 0f)
  val shape = if (isCurrentSong) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp)

  Box(
    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Surface(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(shape)
          .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
          ),
      color = containerColor,
      shape = shape,
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Track number or playing indicator
        Box(
          modifier = Modifier.width(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (isPlaying) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = colors.primary,
              modifier = Modifier.size(20.dp),
            )
          } else {
            Text(
              text = trackNumber.toString(),
              style = MaterialTheme.typography.bodyMedium,
              color = if (isCurrentSong) colors.primary else colors.onSurfaceVariant,
            )
          }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Song title
        Text(
          text = song.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (isCurrentSong) FontWeight.SemiBold else FontWeight.Normal,
          color = if (isCurrentSong) colors.primary else colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )

        // Duration
        duration?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
          )
        }

        // More options button
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
            onGoToAlbum = null, // Already on album
            onGoToArtist = onGoToArtist,
            onToggleFavorite = null,
          )
        }
      }
    }
  }
}

private fun calculateTotalDuration(songs: List<Song>): String {
  val totalSeconds = songs.sumOf { (it.duration ?: 0.0) }.toLong()
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60

  return if (hours > 0) {
    "${hours}h ${minutes}m"
  } else {
    "$minutes min"
  }
}
