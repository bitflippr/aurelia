package com.aurelia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.ActionButtonRow
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.ArtistAvatar
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.DetailHeroGradient
import com.aurelia.app.ui.components.LibrarySectionHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.rememberGoogleSansFlexWideFont
import com.aurelia.app.utils.formatDuration
import com.aurelia.app.utils.jellyfinPrimaryImageUrl
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.Artist
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.getCachedArtist

private data class ArtistAlbumSummary(
  val id: String,
  val name: String,
  val artUrl: String?,
  val songCount: Int,
  val duration: String,
  val songs: List<Song>,
)

@Composable
fun ArtistDetailScreen(
  libraryViewModel: LibraryViewModel,
  artistId: String,
  artistName: String,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit,
  onNavigateToAlbum: ((Screen.AlbumDetail) -> Unit)? = null,
  hasPlayerBar: Boolean = false,
) {
  val state by libraryViewModel.state.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val wideFont = rememberGoogleSansFlexWideFont()
  var artistDetails by remember(artistId) { mutableStateOf<Artist?>(null) }

  val contextMenu = rememberContextMenuState()

  // Calculate bottom padding for miniplayer
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  // Filter songs for this artist
  val artistSongs =
    remember(state.songs, artistId) {
      state.songs
        .filter { song -> song.artistIds?.contains(artistId) == true }
        .sortedWith(compareBy({ it.album ?: "" }, { it.trackNumber ?: Int.MAX_VALUE }))
    }

  val songCount = artistSongs.size
  val albumCount = artistSongs.mapNotNull { it.albumId }.distinct().size
  val totalDuration = remember(artistSongs) { calculateArtistDuration(artistSongs) }
  val artistImageUrl =
    artistDetails?.imageUrl
      ?: jellyfinPrimaryImageUrl(sessionStore.getServerUrl(), artistId, sessionStore.getToken())
  val artistOverview = artistDetails?.overview?.takeIf { it.isNotBlank() }
  val albumSummaries =
    remember(artistSongs) {
      artistSongs
        .filter { !it.albumId.isNullOrBlank() }
        .groupBy { it.albumId.orEmpty() }
        .map { (albumId, songs) ->
          val sortedSongs = songs.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
          val firstSong = sortedSongs.first()
          ArtistAlbumSummary(
            id = albumId,
            name = firstSong.album ?: "Unknown Album",
            artUrl = firstSong.albumArtUrl,
            songCount = sortedSongs.size,
            duration = calculateArtistDuration(sortedSongs),
            songs = sortedSongs,
          )
        }.sortedBy { it.name.lowercase() }
    }
  val featuredSongs = remember(artistSongs) { artistSongs.take(8) }

  val gradient = DetailHeroGradient()

  LaunchedEffect(artistId, sessionStore.getAppDataDir()) {
    val appDataDir = sessionStore.getAppDataDir()
    val session = validateSession(sessionStore, requireAppDataDir = true)

    if (!appDataDir.isNullOrBlank()) {
      artistDetails =
        withContext(Dispatchers.IO) {
          runCatching { getCachedArtist(appDataDir, artistId) }.getOrNull()
        }
    }

    if (session != null) {
      val fetched =
        try {
          sessionStore.reads.artist(session, artistId)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      if (fetched != null) {
        artistDetails = fetched
      }
    }
  }

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
      // Artist header
      item {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          ArtistAvatar(
            size = 180.dp,
            imageUrl = artistImageUrl,
            containerColor = colors.surfaceContainerHigh,
          )

          Spacer(modifier = Modifier.height(24.dp))

          // Artist name with display font
          Text(
            text = artistName,
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

          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ArtistStatPill(label = "$albumCount albums")
            ArtistStatPill(label = "$songCount songs")
            ArtistStatPill(label = totalDuration)
          }

          Spacer(modifier = Modifier.height(24.dp))

          ActionButtonRow(
            enabled = artistSongs.isNotEmpty(),
            onPlay = {
              val serverUrl = sessionStore.getServerUrl() ?: return@ActionButtonRow
              val token = sessionStore.getToken() ?: return@ActionButtonRow

              playerController.setQueue(artistSongs, serverUrl, token, 0)
              onOpenPlayer()
            },
            onShuffle = {
              val serverUrl = sessionStore.getServerUrl() ?: return@ActionButtonRow
              val token = sessionStore.getToken() ?: return@ActionButtonRow

              playerController.setQueue(artistSongs.shuffled(), serverUrl, token, 0)
              onOpenPlayer()
            },
          )

          if (!artistOverview.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
              text = artistOverview,
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onPrimaryContainer.copy(alpha = 0.78f),
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 8.dp),
            )
          }

          Spacer(modifier = Modifier.height(32.dp))
        }
      }

      if (albumSummaries.isNotEmpty()) {
        item(key = "albums-header") {
          LibrarySectionHeader(
            title = "Albums",
            subtitle = "Grouped from this artist's tracks",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
          )
        }

        items(
          items = albumSummaries,
          key = { album -> album.id },
        ) { album ->
          MediaListItem(
            title = album.name,
            subtitle = "${album.songCount} songs - ${album.duration}",
            imageUrl = album.artUrl,
            artworkStyle = AlbumArtStyle.Album,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = {
              onNavigateToAlbum?.invoke(Screen.AlbumDetail(album.id, album.name))
            },
          )
        }
      }

      if (featuredSongs.isNotEmpty()) {
        item(key = "featured-header") {
          LibrarySectionHeader(
            title = "Songs to Start With",
            subtitle = "A quick entry point into this artist",
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
          )
        }
      }

      itemsIndexed(
        items = featuredSongs,
        key = { _, song -> "featured-${song.id}" },
      ) { index, song ->
        val isCurrentSong = song.id == state.currentSongId
        val isPlaying = state.nowPlaying?.isPlaying == true && isCurrentSong
        val queueIndex = artistSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        ArtistSongItem(
          song = song,
          albumName = song.album ?: "Unknown Album",
          albumArtUrl = song.albumArtUrl,
          duration = song.duration?.let { formatDuration((it * 1000).toLong()) },
          isPlaying = isPlaying,
          isCurrentSong = isCurrentSong,
          onClick = {
            val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
            val token = sessionStore.getToken() ?: return@ArtistSongItem

            playerController.setQueue(artistSongs, serverUrl, token, queueIndex)
            onOpenPlayer()
          },
          onLongClick = { contextMenu.openContextMenu(song) },
          onMoreClick = { contextMenu.openContextMenu(song) },
          showContextMenu = contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
          onDismissMenu = { contextMenu.dismissContextMenu() },
          onAddToQueue = {
            val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
            val token = sessionStore.getToken() ?: return@ArtistSongItem
            playerController.addToQueue(song, serverUrl, token)
          },
          onPlayNext = {
            val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
            val token = sessionStore.getToken() ?: return@ArtistSongItem
            playerController.playNext(song, serverUrl, token)
          },
          onAddToPlaylist = { contextMenu.openPlaylistPicker(song) },
          onGoToAlbum =
            if (onNavigateToAlbum != null) {
              song.safeAlbumId()?.let { albumId ->
                {
                  onNavigateToAlbum(
                    Screen.AlbumDetail(
                      albumId = albumId,
                      albumName = song.album ?: "Unknown Album",
                    ),
                  )
                }
              }
            } else {
              null
            },
        )
      }

      if (albumSummaries.isNotEmpty()) {
        item(key = "catalog-header") {
          LibrarySectionHeader(
            title = "Full Catalog",
            subtitle = "All songs by album",
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
          )
        }

        albumSummaries.forEach { album ->
          item(key = "album-${album.id}-header") {
            Text(
              text = album.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = colors.onSurface,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable(enabled = onNavigateToAlbum != null) {
                    onNavigateToAlbum?.invoke(Screen.AlbumDetail(album.id, album.name))
                  }.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
            )
          }

          itemsIndexed(
            items = album.songs,
            key = { _, song -> "catalog-${album.id}-${song.id}" },
          ) { _, song ->
            val isCurrentSong = song.id == state.currentSongId
            val isPlaying = state.nowPlaying?.isPlaying == true && isCurrentSong
            val queueIndex = artistSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

            ArtistSongItem(
              song = song,
              albumName = song.album ?: "Unknown Album",
              albumArtUrl = song.albumArtUrl,
              duration = song.duration?.let { formatDuration((it * 1000).toLong()) },
              isPlaying = isPlaying,
              isCurrentSong = isCurrentSong,
              onClick = {
                val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
                val token = sessionStore.getToken() ?: return@ArtistSongItem

                playerController.setQueue(artistSongs, serverUrl, token, queueIndex)
                onOpenPlayer()
              },
              onLongClick = { contextMenu.openContextMenu(song) },
              onMoreClick = { contextMenu.openContextMenu(song) },
              showContextMenu = contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
              onDismissMenu = { contextMenu.dismissContextMenu() },
              onAddToQueue = {
                val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
                val token = sessionStore.getToken() ?: return@ArtistSongItem
                playerController.addToQueue(song, serverUrl, token)
              },
              onPlayNext = {
                val serverUrl = sessionStore.getServerUrl() ?: return@ArtistSongItem
                val token = sessionStore.getToken() ?: return@ArtistSongItem
                playerController.playNext(song, serverUrl, token)
              },
              onAddToPlaylist = { contextMenu.openPlaylistPicker(song) },
              onGoToAlbum =
                if (onNavigateToAlbum != null) {
                  {
                    onNavigateToAlbum(Screen.AlbumDetail(album.id, album.name))
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

@Composable
private fun ArtistStatPill(label: String) {
  val colors = MaterialTheme.colorScheme

  Surface(
    shape = RoundedCornerShape(50),
    color = colors.surfaceContainerHigh.copy(alpha = 0.78f),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = colors.onSurface,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistSongItem(
  song: Song,
  albumName: String,
  albumArtUrl: String?,
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
  onGoToAlbum: (() -> Unit)?,
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Album art
        AlbumArt(
          imageUrl = albumArtUrl,
          size = 48.dp,
          cornerRadius = 8.dp,
        )

        // Song info
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = song.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrentSong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrentSong) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = albumName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.clickable(
                enabled = onGoToAlbum != null,
                onClick = { onGoToAlbum?.invoke() },
              ),
          )
        }

        // Duration
        duration?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
          )
        }

        // Playing indicator or more options button
        Box {
          if (isPlaying) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = colors.primary,
              modifier =
                Modifier
                  .size(36.dp)
                  .padding(8.dp),
            )
          } else {
            IconButton(onClick = onMoreClick) {
              Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = colors.onSurfaceVariant,
              )
            }
          }

          SongContextMenu(
            song = song,
            expanded = showContextMenu,
            onDismiss = onDismissMenu,
            onAddToQueue = onAddToQueue,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = null, // Already on artist
            onToggleFavorite = null,
          )
        }
      }
    }
  }
}

private fun calculateArtistDuration(songs: List<Song>): String {
  val totalSeconds = songs.sumOf { (it.duration ?: 0.0) }.toLong()
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60

  return if (hours > 0) {
    "${hours}h ${minutes}m"
  } else {
    "$minutes min"
  }
}
