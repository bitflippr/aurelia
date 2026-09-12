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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.DetailArtworkBackdrop
import com.aurelia.app.ui.components.DetailEmptyState
import com.aurelia.app.ui.components.DetailPageHeader
import com.aurelia.app.ui.components.DetailPlaybackActions
import com.aurelia.app.ui.components.DetailSectionHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.rememberGoogleSansFlexWideFont
import com.aurelia.app.utils.formatDuration
import com.aurelia.app.utils.jellyfinPrimaryImageUrl
import com.aurelia.app.utils.optimizedArtworkUrl
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
  var artistDetails by remember(artistId) { mutableStateOf<Artist?>(null) }

  val contextMenu = rememberContextMenuState()
  var playlistSongs by remember(artistId) { mutableStateOf<List<Song>?>(null) }
  var showAllSongs by rememberSaveable(artistId) { mutableStateOf(false) }
  var showAllAlbums by rememberSaveable(artistId) { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val collapseOffset = with(LocalDensity.current) { 120.dp.roundToPx() }
  val collapsedHeader by remember(collapseOffset) {
    derivedStateOf {
      val hero = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }
      listState.firstVisibleItemIndex > 0 || (hero != null && hero.offset + hero.size < collapseOffset)
    }
  }

  // Calculate bottom padding for miniplayer
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  // Filter songs for this artist
  val artistSongs =
    remember(state.songs, artistId) {
      state.songs
        .filter { song -> song.artistIds?.contains(artistId) == true }
        .sortedWith(compareBy({ it.album ?: "" }, { it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }))
    }

  val songCount = artistSongs.size
  val totalDuration = remember(artistSongs) { calculateArtistDuration(artistSongs) }
  val artistImageUrl =
    artistDetails?.imageUrl
      ?: jellyfinPrimaryImageUrl(sessionStore.getServerUrl(), artistId, sessionStore.getToken())
  val artistOverview = artistDetails?.overview?.takeIf { it.isNotBlank() }
  val displayArtistName = artistDetails?.name?.takeIf { it.isNotBlank() } ?: artistName
  val albumSummaries =
    remember(artistSongs) {
      artistSongs
        .filter { !it.albumId.isNullOrBlank() }
        .groupBy { it.albumId.orEmpty() }
        .map { (albumId, songs) ->
          val sortedSongs = songs.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }))
          val firstSong = sortedSongs.first()
          ArtistAlbumSummary(
            id = albumId,
            name = firstSong.album ?: "Unknown Album",
            artUrl = firstSong.albumArtUrl,
            songCount = sortedSongs.size,
            duration = calculateArtistDuration(sortedSongs),
          )
        }.sortedBy { it.name.lowercase() }
    }
  val visibleSongs = remember(artistSongs, showAllSongs) { if (showAllSongs) artistSongs else artistSongs.take(4) }

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

  Box(
    Modifier.fillMaxSize().background(colors.background),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().testTag("artist-detail-list"),
      contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
      item(key = "hero") {
        ArtistDetailHero(
          artistName = displayArtistName,
          imageUrl = artistImageUrl,
          metadata = "${albumSummaries.size} albums · $songCount songs · $totalDuration",
        )
      }
      item(key = "actions") {
        DetailPlaybackActions(
          enabled = artistSongs.isNotEmpty(),
          onPlay = {
            val serverUrl = sessionStore.getServerUrl() ?: return@DetailPlaybackActions
            val token = sessionStore.getToken() ?: return@DetailPlaybackActions
            playerController.setQueue(artistSongs, serverUrl, token, 0)
            onOpenPlayer()
          },
          onShuffle = {
            val serverUrl = sessionStore.getServerUrl() ?: return@DetailPlaybackActions
            val token = sessionStore.getToken() ?: return@DetailPlaybackActions
            playerController.setQueue(artistSongs.shuffled(), serverUrl, token, 0)
            onOpenPlayer()
          },
          onAddToQueue = {
            val serverUrl = sessionStore.getServerUrl() ?: return@DetailPlaybackActions
            val token = sessionStore.getToken() ?: return@DetailPlaybackActions
            artistSongs.forEach { playerController.addToQueue(it, serverUrl, token) }
          },
          onAddToPlaylist = { playlistSongs = artistSongs },
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
      }
      if (albumSummaries.isNotEmpty()) {
        item(key = "albums-header") {
          DetailSectionHeader(
            title = "Albums",
            actionLabel = if (showAllAlbums) "Show less" else "View all",
            onAction = { showAllAlbums = !showAllAlbums },
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
        if (showAllAlbums) {
          items(albumSummaries, key = { "album-" + it.id }) { album ->
            MediaListItem(
              title = album.name,
              subtitle = "${album.songCount} songs · ${album.duration}",
              imageUrl = album.artUrl,
              artworkStyle = AlbumArtStyle.Album,
              modifier = Modifier.padding(horizontal = 8.dp),
              onClick = { onNavigateToAlbum?.invoke(Screen.AlbumDetail(album.id, album.name)) },
            )
          }
        } else {
          item(key = "albums") {
            LazyRow(
              contentPadding = PaddingValues(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              items(albumSummaries, key = { it.id }) { album ->
                ArtistAlbumCard(
                  title = album.name,
                  songCount = album.songCount,
                  imageUrl = album.artUrl,
                  onClick = { onNavigateToAlbum?.invoke(Screen.AlbumDetail(album.id, album.name)) },
                )
              }
            }
          }
        }
      }
      if (artistSongs.isNotEmpty()) {
        item(key = "songs-header") {
          DetailSectionHeader(
            title = "Songs",
            actionLabel =
              if (artistSongs.size > 4) {
                if (showAllSongs) "Show less" else "View all"
              } else {
                null
              },
            onAction = { showAllSongs = !showAllSongs },
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
      } else {
        item(key = "empty") {
          DetailEmptyState(
            isLoading = state.isLoading,
            error = state.error,
            emptyMessage = "No songs for this artist",
            onRetry = { libraryViewModel.ensureLoaded(force = true) },
          )
        }
      }
      itemsIndexed(
        items = visibleSongs,
        key = { _, song -> "song-${song.id}" },
      ) { index, song ->
        val isCurrentSong = song.id == state.currentSongId
        val isPlaying = state.nowPlaying?.isPlaying == true && isCurrentSong
        val queueIndex = index

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

      if (artistOverview != null) {
        item(key = "about") {
          Column(Modifier.padding(horizontal = 20.dp)) {
            DetailSectionHeader(title = "About $displayArtistName")
            Text(
              artistOverview,
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
            )
          }
        }
      }
    }
    DetailPageHeader(
      title = if (collapsedHeader) displayArtistName else "",
      onBack = onBack,
      modifier =
        Modifier
          .background(
            Brush.verticalGradient(
              listOf(
                colors.background.copy(alpha = if (collapsedHeader) 1f else 0f),
                colors.background.copy(alpha = if (collapsedHeader) 1f else 0f),
              ),
            ),
          ).statusBarsPadding(),
    )
  }

  val songsForPlaylist =
    playlistSongs ?: contextMenu.selectedSong?.takeIf { contextMenu.showPlaylistPicker }?.let { listOf(it) }
  if (songsForPlaylist != null) {
    PlaylistPickerDialog(
      playlists = playlistState.playlists,
      isLoading = playlistState.isLoading,
      onDismiss = {
        playlistSongs = null
        contextMenu.dismissPlaylistPicker()
      },
      onSelectPlaylist = { playlist ->
        playlistViewModel.addSongsToPlaylist(playlist.id, songsForPlaylist.map { it.id })
        playlistSongs = null
        contextMenu.dismissPlaylistPicker()
      },
      onCreatePlaylist = { name ->
        playlistViewModel.createPlaylist(name, songsForPlaylist.map { it.id })
        playlistSongs = null
        contextMenu.dismissPlaylistPicker()
      },
    )
  }
}

@Composable
internal fun ArtistDetailHero(
  artistName: String,
  imageUrl: String?,
  metadata: String,
) {
  val colors = MaterialTheme.colorScheme
  val context = LocalContext.current
  val imageSize = with(LocalDensity.current) { 420.dp.toPx().toInt() }
  var artworkAspectRatio by remember(imageUrl) { mutableStateOf(1f) }
  Box(Modifier.fillMaxWidth()) {
    DetailArtworkBackdrop(imageUrl, Modifier.matchParentSize())
    Column(Modifier.fillMaxWidth()) {
      Box(Modifier.fillMaxWidth().aspectRatio(artworkAspectRatio)) {
        Icon(
          Icons.Filled.Person,
          contentDescription = null,
          tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
          modifier = Modifier.align(Alignment.Center).size(88.dp),
        )
        if (!imageUrl.isNullOrBlank()) {
          AsyncImage(
            model =
              ImageRequest
                .Builder(context)
                .data(optimizedArtworkUrl(imageUrl, imageSize))
                .size(imageSize)
                .crossfade(false)
                .build(),
            contentDescription = null,
            onSuccess = { result ->
              val drawable = result.result.drawable
              if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                artworkAspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
              }
            },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        }
        Box(
          Modifier.matchParentSize().background(
            Brush.verticalGradient(
              0f to colors.background.copy(alpha = 0f),
              0.2f to colors.background.copy(alpha = 0f),
              0.55f to colors.background.copy(alpha = 0f),
              0.8f to colors.background.copy(alpha = 0.45f),
              1f to colors.background,
            ),
          ),
        )
      }
      Column(
        Modifier
          .fillMaxWidth()
          .background(colors.background)
          .padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          artistName,
          style =
            MaterialTheme.typography.headlineLarge.copy(
              fontFamily = rememberGoogleSansFlexWideFont(),
              fontSize = 34.sp,
              lineHeight = 40.sp,
            ),
          fontWeight = FontWeight.Black,
          color = colors.onBackground,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { heading() },
        )
        Text(metadata, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
  }
}

@Composable
internal fun ArtistAlbumCard(
  title: String,
  songCount: Int,
  imageUrl: String?,
  onClick: () -> Unit,
) {
  Column(Modifier.width(140.dp).clickable(onClick = onClick).semantics { contentDescription = "Open album $title" }) {
    AlbumArt(imageUrl, size = 140.dp, cornerRadius = 20.dp, style = AlbumArtStyle.Album)
    Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "$songCount songs",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
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
            .padding(start = 12.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box {
          AlbumArt(imageUrl = albumArtUrl, size = 44.dp, cornerRadius = 12.dp)
          if (isPlaying) {
            Box(
              Modifier.size(44.dp).background(colors.surface.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Filled.PlayArrow, "Playing", tint = colors.primary, modifier = Modifier.size(22.dp))
            }
          }
        }

        // Song info
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = song.name,
            style = MaterialTheme.typography.bodyMedium,
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
          IconButton(onClick = onMoreClick) {
            Icon(
              imageVector = Icons.Filled.MoreVert,
              contentDescription = "More options for ${song.name}",
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
