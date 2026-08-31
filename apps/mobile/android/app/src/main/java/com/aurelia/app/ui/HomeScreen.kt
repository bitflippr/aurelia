package com.aurelia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryLoadingState
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibrarySectionHeader
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.rememberPressScale
import com.aurelia.app.utils.optimizedArtworkUrl
import uniffi.aurelia_core.Song
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ArtShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
  viewModel: HomeViewModel,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onOpenPlayer: () -> Unit,
  onNavigateToAlbum: (Screen.AlbumDetail) -> Unit = {},
  onNavigateToArtist: (Screen.ArtistDetail) -> Unit = {},
  hasPlayerBar: Boolean = false,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  val contextMenu = rememberContextMenuState()

  val isEmpty =
    state.quickPicks.isEmpty() &&
      state.recentlyPlayed.isEmpty() &&
      state.recentlyAddedAlbums.isEmpty() &&
      state.randomAlbums.isEmpty()

  when {
    state.isLoading && isEmpty -> {
      LibraryLoadingState(modifier = Modifier.fillMaxSize())
    }

    state.error != null && isEmpty -> {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        LibraryMessageState(
          icon = Icons.Filled.MusicNote,
          title = "Couldn't load your library",
          subtitle = state.error,
          isError = true,
          actionLabel = "Retry",
          onAction = { viewModel.ensureLoaded(force = true) },
        )
      }
    }

    else -> {
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding =
          PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = bottomPadding,
          ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "header") {
          HomeHeader(
            onShuffleAll = {
              viewModel.shuffleAll()
              onOpenPlayer()
            },
            onSurpriseMe = {
              viewModel.playSurprise()
              onOpenPlayer()
            },
          )
        }

        // Listen again - dense quick picks grid
        if (state.quickPicks.isNotEmpty()) {
          item(key = "quick_picks_header") {
            LibrarySectionHeader(
              title = "Listen again",
              subtitle = "Your recent and frequent plays",
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          item(key = "quick_picks") {
            QuickPicksGrid(
              songs = state.quickPicks,
              currentSongId = state.currentSongId,
              onPlay = { song ->
                viewModel.playSongFromList(song.id, state.quickPicks)
                onOpenPlayer()
              },
              onLongClick = { contextMenu.openContextMenu(it) },
              contextMenuSongId =
                if (contextMenu.showContextMenu) contextMenu.selectedSong?.id else null,
              onDismissMenu = { contextMenu.dismissContextMenu() },
              onAddToQueue = { song ->
                val serverUrl = sessionStore.getServerUrl() ?: return@QuickPicksGrid
                val token = sessionStore.getToken() ?: return@QuickPicksGrid
                playerController.addToQueue(song, serverUrl, token)
              },
              onPlayNext = { song ->
                val serverUrl = sessionStore.getServerUrl() ?: return@QuickPicksGrid
                val token = sessionStore.getToken() ?: return@QuickPicksGrid
                playerController.playNext(song, serverUrl, token)
              },
              onAddToPlaylist = { contextMenu.openPlaylistPicker(it) },
              onGoToAlbum = { song ->
                song.safeAlbumId()?.let { albumId ->
                  onNavigateToAlbum(
                    Screen.AlbumDetail(
                      albumId = albumId,
                      albumName = song.album ?: "Unknown Album",
                    ),
                  )
                }
              },
              onGoToArtist = { song ->
                song.safePrimaryArtistId()?.let { artistId ->
                  onNavigateToArtist(
                    Screen.ArtistDetail(
                      artistId = artistId,
                      artistName = song.artists?.firstOrNull() ?: "Unknown Artist",
                    ),
                  )
                }
              },
            )
          }
        }

        // Jump back in - albums from recent plays
        if (state.recentAlbums.isNotEmpty()) {
          item(key = "recent_albums_header") {
            LibrarySectionHeader(
              title = "Jump back in",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "recent_albums") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(items = state.recentAlbums, key = { "recent_${it.id}" }) { album ->
                ArtworkCard(
                  title = album.name,
                  subtitle = album.artist,
                  imageUrl = album.albumArtUrl,
                  placeholder = Icons.Filled.Album,
                  onClick = { onNavigateToAlbum(Screen.AlbumDetail(album.id, album.name)) },
                  onPlay = {
                    viewModel.playAlbum(album.id)
                    onOpenPlayer()
                  },
                )
              }
            }
          }
        }

        // Made for you - instant mixes
        if (state.mixes.isNotEmpty()) {
          item(key = "mixes_header") {
            LibrarySectionHeader(
              title = "Made for you",
              subtitle = "Instant mixes from your listening",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "mixes") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(items = state.mixes, key = { "mix_${it.seedId}" }) { mix ->
                MixCard(
                  mix = mix,
                  onClick = {
                    viewModel.playMix(mix)
                    onOpenPlayer()
                  },
                )
              }
            }
          }
        }

        // Forgotten favorites
        if (state.forgottenFavorites.isNotEmpty()) {
          item(key = "forgotten_header") {
            LibrarySectionHeader(
              title = "Forgotten favorites",
              subtitle = "Loved songs you haven't played in a while",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "forgotten") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(items = state.forgottenFavorites, key = { "forgot_${it.id}" }) { song ->
                Box {
                  ArtworkCard(
                    title = song.name,
                    subtitle = song.artists?.firstOrNull() ?: "Unknown",
                    imageUrl = song.albumArtUrl,
                    placeholder = Icons.Filled.MusicNote,
                    isCurrent = song.id == state.currentSongId,
                    onClick = {
                      viewModel.playSongFromList(song.id, state.forgottenFavorites)
                      onOpenPlayer()
                    },
                    onLongClick = { contextMenu.openContextMenu(song) },
                    onPlay = {
                      viewModel.playSongFromList(song.id, state.forgottenFavorites)
                      onOpenPlayer()
                    },
                  )
                  SongContextMenu(
                    song = song,
                    expanded =
                      contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
                    onDismiss = { contextMenu.dismissContextMenu() },
                    onAddToQueue = {
                      val serverUrl = sessionStore.getServerUrl() ?: return@SongContextMenu
                      val token = sessionStore.getToken() ?: return@SongContextMenu
                      playerController.addToQueue(song, serverUrl, token)
                    },
                    onPlayNext = {
                      val serverUrl = sessionStore.getServerUrl() ?: return@SongContextMenu
                      val token = sessionStore.getToken() ?: return@SongContextMenu
                      playerController.playNext(song, serverUrl, token)
                    },
                    onAddToPlaylist = { contextMenu.openPlaylistPicker(song) },
                    onGoToAlbum =
                      song.safeAlbumId()?.let { albumId ->
                        {
                          onNavigateToAlbum(
                            Screen.AlbumDetail(
                              albumId = albumId,
                              albumName = song.album ?: "Unknown Album",
                            ),
                          )
                        }
                      },
                    onGoToArtist =
                      song.safePrimaryArtistId()?.let { artistId ->
                        {
                          onNavigateToArtist(
                            Screen.ArtistDetail(
                              artistId = artistId,
                              artistName = song.artists?.firstOrNull() ?: "Unknown Artist",
                            ),
                          )
                        }
                      },
                    onToggleFavorite = null,
                  )
                }
              }
            }
          }
        }

        // Recently added albums
        if (state.recentlyAddedAlbums.isNotEmpty()) {
          item(key = "recently_added_header") {
            LibrarySectionHeader(
              title = "Recently added",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "recently_added") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(items = state.recentlyAddedAlbums, key = { "added_${it.id}" }) { album ->
                ArtworkCard(
                  title = album.name,
                  subtitle = album.artist,
                  imageUrl = album.albumArtUrl,
                  placeholder = Icons.Filled.Album,
                  onClick = { onNavigateToAlbum(Screen.AlbumDetail(album.id, album.name)) },
                  onPlay = {
                    viewModel.playAlbum(album.id)
                    onOpenPlayer()
                  },
                )
              }
            }
          }
        }

        // From your library - random albums
        if (state.randomAlbums.isNotEmpty()) {
          item(key = "random_header") {
            LibrarySectionHeader(
              title = "From your library",
              subtitle = "Random picks to rediscover",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "random") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(items = state.randomAlbums, key = { "random_${it.id}" }) { album ->
                ArtworkCard(
                  title = album.name,
                  subtitle = album.artist,
                  imageUrl = album.albumArtUrl,
                  placeholder = Icons.Filled.Album,
                  onClick = { onNavigateToAlbum(Screen.AlbumDetail(album.id, album.name)) },
                  onPlay = {
                    viewModel.playAlbum(album.id)
                    onOpenPlayer()
                  },
                )
              }
            }
          }
        }

        // Top genres
        if (state.topGenres.isNotEmpty()) {
          item(key = "genres_header") {
            LibrarySectionHeader(
              title = "Your genres",
              subtitle = "Tap to shuffle",
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item(key = "genres") {
            GenreChips(
              genres = state.topGenres,
              onPlayGenre = {
                viewModel.playGenreMix(it)
                onOpenPlayer()
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
private fun HomeHeader(
  onShuffleAll: () -> Unit,
  onSurpriseMe: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val greeting = remember { timeOfDayGreeting() }
  val dateLine =
    remember {
      SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
  ) {
    Text(
      text = greeting,
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.Black,
      color = colors.onBackground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = dateLine,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      AssistChip(
        onClick = onShuffleAll,
        label = { Text("Shuffle all") },
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.Shuffle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        },
      )
      AssistChip(
        onClick = onSurpriseMe,
        label = { Text("Surprise me") },
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        },
      )
    }
  }
}

private fun timeOfDayGreeting(): String =
  when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
  }

/**
 * Dense 2-column grid of compact song cards rendered inside a LazyColumn item.
 */
@Composable
private fun QuickPicksGrid(
  songs: List<Song>,
  currentSongId: String?,
  onPlay: (Song) -> Unit,
  onLongClick: (Song) -> Unit,
  contextMenuSongId: String?,
  onDismissMenu: () -> Unit,
  onAddToQueue: (Song) -> Unit,
  onPlayNext: (Song) -> Unit,
  onAddToPlaylist: (Song) -> Unit,
  onGoToAlbum: (Song) -> Unit,
  onGoToArtist: (Song) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    songs.chunked(2).forEach { rowSongs ->
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        rowSongs.forEach { song ->
          Box(modifier = Modifier.weight(1f)) {
            QuickPickCard(
              song = song,
              isCurrentSong = song.id == currentSongId,
              onClick = { onPlay(song) },
              onLongClick = { onLongClick(song) },
            )
            SongContextMenu(
              song = song,
              expanded = contextMenuSongId == song.id,
              onDismiss = onDismissMenu,
              onAddToQueue = { onAddToQueue(song) },
              onPlayNext = { onPlayNext(song) },
              onAddToPlaylist = { onAddToPlaylist(song) },
              onGoToAlbum = song.safeAlbumId()?.let { { onGoToAlbum(song) } },
              onGoToArtist = song.safePrimaryArtistId()?.let { { onGoToArtist(song) } },
              onToggleFavorite = null,
            )
          }
        }
        if (rowSongs.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

/**
 * Compact song card for the quick picks grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPickCard(
  song: Song,
  isCurrentSong: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val cardShape = RoundedCornerShape(32.dp)

  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale = rememberPressScale(isPressed)

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .scale(scale)
        .combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    color = if (isCurrentSong) colors.primaryContainer else colors.surfaceContainerLow,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(64.dp)
          .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Artwork(
        imageUrl = song.albumArtUrl,
        contentDescription = song.name,
        placeholder = Icons.Filled.MusicNote,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
      )

      Column(
        modifier =
          Modifier
            .weight(1f)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = song.name,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Medium,
          color = if (isCurrentSong) colors.onPrimaryContainer else colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = song.artists?.firstOrNull() ?: "Unknown",
          style = MaterialTheme.typography.bodySmall,
          color =
            if (isCurrentSong) {
              colors.onPrimaryContainer.copy(alpha = 0.7f)
            } else {
              colors.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Square artwork card for horizontal rows (albums and songs).
 * Tap opens the item, play overlay starts playback directly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtworkCard(
  title: String,
  subtitle: String,
  imageUrl: String?,
  placeholder: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  isCurrent: Boolean = false,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  onPlay: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale = rememberPressScale(isPressed, pressedScale = 0.97f)

  Column(
    modifier =
      modifier
        .width(140.dp)
        .scale(scale)
        .combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(1f),
    ) {
      Artwork(
        imageUrl = imageUrl,
        contentDescription = title,
        placeholder = placeholder,
        modifier = Modifier.fillMaxSize(),
        shape = ArtShape,
      )
      Surface(
        onClick = onPlay,
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(8.dp),
        shape = CircleShape,
        color = colors.primary.copy(alpha = 0.92f),
      ) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = "Play $title",
          tint = colors.onPrimary,
          modifier =
            Modifier
              .padding(6.dp)
              .size(18.dp),
        )
      }
    }

    Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
        color = if (isCurrent) colors.primary else colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Instant mix card - wider card with label and play affordance over the artwork.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixCard(
  mix: HomeMix,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale = rememberPressScale(isPressed, pressedScale = 0.97f)

  Column(
    modifier =
      Modifier
        .width(160.dp)
        .scale(scale)
        .combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
        ),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(ArtShape),
    ) {
      Artwork(
        imageUrl = mix.artworkUrl,
        contentDescription = mix.seedTitle,
        placeholder = Icons.Filled.AutoAwesome,
        modifier = Modifier.fillMaxSize(),
        shape = ArtShape,
      )
      Surface(
        modifier =
          Modifier
            .align(Alignment.TopStart)
            .padding(10.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.primary.copy(alpha = 0.92f),
      ) {
        Text(
          text = "Mix",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = colors.onPrimary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
      }
      Surface(
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(8.dp),
        shape = CircleShape,
        color = colors.surface.copy(alpha = 0.92f),
      ) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = colors.onSurface,
          modifier =
            Modifier
              .padding(6.dp)
              .size(18.dp),
        )
      }
    }

    Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
      Text(
        text = mix.seedTitle,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "Instant mix",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Horizontally wrapping genre chips. Each chip shuffles songs of that genre.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(
  genres: List<String>,
  onPlayGenre: (String) -> Unit,
) {
  androidx.compose.foundation.layout.FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    genres.forEach { genre ->
      AssistChip(
        onClick = { onPlayGenre(genre) },
        label = { Text(genre) },
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
      )
    }
  }
}

@Composable
private fun Artwork(
  imageUrl: String?,
  contentDescription: String?,
  placeholder: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  shape: androidx.compose.ui.graphics.Shape = ArtShape,
) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      modifier
        .clip(shape)
        .background(colors.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = placeholder,
      contentDescription = null,
      tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
      modifier = Modifier.fillMaxSize(0.35f),
    )
    if (!imageUrl.isNullOrBlank()) {
      val context = LocalContext.current
      val pxSize = with(LocalDensity.current) { 160.dp.toPx().toInt() }
      AsyncImage(
        model =
          ImageRequest
            .Builder(context)
            .data(optimizedArtworkUrl(imageUrl, pxSize))
            .crossfade(false)
            .size(pxSize)
            .build(),
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
  }
}
