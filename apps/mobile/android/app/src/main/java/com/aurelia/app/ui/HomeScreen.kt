package com.aurelia.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.rememberPressScale
import com.aurelia.app.utils.optimizedArtworkUrl
import uniffi.aurelia_core.Song
import java.util.Calendar

private val ArtShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
  viewModel: HomeViewModel,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onOpenPlayer: () -> Unit,
  onOpenSettings: () -> Unit,
  onNavigateToAlbum: (Screen.AlbumDetail) -> Unit = {},
  onNavigateToArtist: (Screen.ArtistDetail) -> Unit = {},
  hasPlayerBar: Boolean = false,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val playlistState by playlistViewModel.state.collectAsStateWithLifecycle()
  val profilePath = sessionStore.getAppDataDir()
  val username = remember(profilePath) { sessionStore.getCredentials()?.username.orEmpty() }
  val contextMenu = rememberContextMenuState()

  HomeContent(
    state = state,
    username = username,
    hasPlayerBar = hasPlayerBar,
    onRetry = { viewModel.ensureLoaded(force = true) },
    onOpenSettings = onOpenSettings,
    onShuffleAll = {
      viewModel.shuffleAll()
      onOpenPlayer()
    },
    onSurpriseMe = {
      viewModel.playSurprise()
      onOpenPlayer()
    },
    onPlayMix = {
      viewModel.playMix(it)
      onOpenPlayer()
    },
    onPlaySong = { song, songs ->
      viewModel.playSongFromList(song.id, songs)
      onOpenPlayer()
    },
    onSongLongClick = { contextMenu.openContextMenu(it) },
    onOpenAlbum = { onNavigateToAlbum(Screen.AlbumDetail(it.id, it.name)) },
    onPlayGenre = {
      viewModel.playGenreMix(it)
      onOpenPlayer()
    },
    songMenu = { song ->
      SongContextMenu(
        song = song,
        expanded = contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
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
          song.safeAlbumId()?.let { id ->
            { onNavigateToAlbum(Screen.AlbumDetail(id, song.album ?: "Unknown Album")) }
          },
        onGoToArtist =
          song.safePrimaryArtistId()?.let { id ->
            { onNavigateToArtist(Screen.ArtistDetail(id, song.artists?.firstOrNull() ?: "Unknown Artist")) }
          },
      )
    },
  )

  if (contextMenu.showPlaylistPicker && contextMenu.selectedSong != null) {
    PlaylistPickerDialog(
      playlists = playlistState.playlists,
      isLoading = playlistState.isLoading,
      onDismiss = { contextMenu.dismissPlaylistPicker() },
      onSelectPlaylist = { playlist ->
        contextMenu.selectedSong?.let { playlistViewModel.addSongsToPlaylist(playlist.id, listOf(it.id)) }
        contextMenu.dismissPlaylistPicker()
      },
      onCreatePlaylist = { name ->
        contextMenu.selectedSong?.let { playlistViewModel.createPlaylist(name, listOf(it.id)) }
        contextMenu.dismissPlaylistPicker()
      },
    )
  }
}

@Composable
internal fun HomeContent(
  state: HomeState,
  username: String,
  hasPlayerBar: Boolean,
  onRetry: () -> Unit,
  onOpenSettings: () -> Unit,
  onShuffleAll: () -> Unit,
  onSurpriseMe: () -> Unit,
  onPlayMix: (HomeMix) -> Unit,
  onPlaySong: (Song, List<Song>) -> Unit,
  onSongLongClick: (Song) -> Unit,
  onOpenAlbum: (AlbumItem) -> Unit,
  onPlayGenre: (String) -> Unit,
  songMenu: @Composable (Song) -> Unit,
) {
  val rediscover =
    remember(state.forgottenFavorites, state.quickPicks, state.recentlyPlayed) {
      (state.forgottenFavorites + state.quickPicks + state.recentlyPlayed).distinctBy { it.id }
    }
  val fallbackAlbum =
    state.recentlyAddedAlbums.firstOrNull() ?: state.recentAlbums.firstOrNull() ?: state.randomAlbums.firstOrNull()
  val isEmpty =
    rediscover.isEmpty() &&
      fallbackAlbum == null &&
      state.mixes.isEmpty()
  var showAllSongs by rememberSaveable { mutableStateOf(false) }

  when {
    state.isLoading && isEmpty -> LibraryLoadingState(Modifier.fillMaxSize())
    isEmpty ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LibraryMessageState(
          icon = Icons.Filled.MusicNote,
          title = if (state.error != null) "Couldn't load your library" else "Your music starts here",
          subtitle = state.error ?: "Add music to your server, then refresh your library.",
          isError = state.error != null,
          actionLabel = if (state.error != null) "Retry" else "Refresh",
          onAction = onRetry,
        )
      }
    else ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 16.dp, bottom = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)),
      ) {
        item(key = "header") {
          HomeHeader(username, onOpenSettings)
        }
        item(key = "mixes") {
          Column(Modifier.padding(horizontal = 20.dp)) {
            HomeSectionHeader("Let it play")
            if (state.mixes.isNotEmpty()) {
              val pager = rememberPagerState(pageCount = { state.mixes.size })
              HorizontalPager(state = pager, pageSpacing = 12.dp, key = { state.mixes[it].seedId }) { page ->
                val mix = state.mixes[page]
                FeaturedMixCard(
                  title = mix.seedTitle,
                  subtitle =
                    mix.songs
                      .flatMap { it.artists.orEmpty() }
                      .distinct()
                      .take(3)
                      .joinToString(", "),
                  artworkUrl = mix.artworkUrl,
                  label = "Based on your listening",
                  onClick = { onPlayMix(mix) },
                )
              }
              if (state.mixes.size > 1) {
                Row(
                  Modifier.fillMaxWidth().padding(top = 10.dp).semantics {
                    contentDescription = "Mix ${pager.currentPage + 1} of ${state.mixes.size}. Swipe for more mixes."
                  },
                  horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                ) {
                  repeat(state.mixes.size) { page ->
                    Box(
                      Modifier
                        .size(if (page == pager.currentPage) 6.dp else 5.dp)
                        .background(
                          if (page ==
                            pager.currentPage
                          ) {
                            MaterialTheme.colorScheme.primary
                          } else {
                            MaterialTheme.colorScheme.outlineVariant
                          },
                          CircleShape,
                        ),
                    )
                  }
                }
              }
            } else {
              // A new library may not have listening history or server-generated mixes yet.
              FeaturedMixCard(
                title = "Your library, on shuffle",
                subtitle = "Find your next favorite",
                artworkUrl = fallbackAlbum?.albumArtUrl,
                label = "From your collection",
                onClick = onShuffleAll,
              )
            }
            HomePlaybackActions(onShuffleAll, onSurpriseMe)
          }
        }
        if (state.recentlyAddedAlbums.isNotEmpty()) {
          item(key = "recently_added") {
            HomeAlbumShelf("Fresh on your shelf", state.recentlyAddedAlbums, onOpenAlbum)
          }
        }
        if (rediscover.isNotEmpty()) {
          item(key = "rediscover_header") {
            HomeSectionHeader(
              title = "Worth another listen",
              modifier = Modifier.padding(horizontal = 20.dp),
              actionLabel =
                if (rediscover.size > 3) {
                  if (showAllSongs) {
                    "Show less"
                  } else {
                    "See all"
                  }
                } else {
                  null
                },
              onAction = { showAllSongs = !showAllSongs },
            )
          }
          items(if (showAllSongs) rediscover else rediscover.take(3), key = { "rediscover_${it.id}" }) { song ->
            Box(Modifier.padding(horizontal = 20.dp)) {
              HomeSongRow(
                song,
                song.id == state.currentSongId,
                { onPlaySong(song, rediscover) },
                { onSongLongClick(song) },
              )
              songMenu(song)
            }
          }
        }
        if (state.topGenres.isNotEmpty()) {
          item(key = "genres") {
            Column(Modifier.padding(horizontal = 20.dp)) {
              HomeSectionHeader("A little of everything")
              GenreChips(state.topGenres, onPlayGenre)
            }
          }
        }
      }
  }
}

@Composable
private fun HomeHeader(
  username: String,
  onOpenSettings: () -> Unit,
) {
  val greeting = remember { timeOfDayGreeting() }
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        if (username.isBlank()) greeting else "$greeting, $username",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "What sounds good?",
        style =
          MaterialTheme.typography.headlineMedium.copy(
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
          ),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
      )
    }
    Surface(
      onClick = onOpenSettings,
      modifier = Modifier.size(48.dp).semantics { contentDescription = "Profile and settings" },
      shape = CircleShape,
      color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(username.firstOrNull()?.uppercase() ?: "A", color = MaterialTheme.colorScheme.onSecondaryContainer)
      }
    }
  }
}

private fun timeOfDayGreeting(): String =
  when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
  }

@Composable
private fun HomeSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  Row(
    modifier.fillMaxWidth().padding(top = 26.dp, bottom = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.weight(1f).semantics { heading() },
    )
    if (actionLabel != null) {
      TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(actionLabel, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
private fun FeaturedMixCard(
  title: String,
  subtitle: String,
  artworkUrl: String?,
  label: String,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 130.dp).padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Artwork(
        artworkUrl,
        null,
        Icons.Filled.AutoAwesome,
        modifier = Modifier.size(80.dp).rotate(-7f),
        shape = RoundedCornerShape(16.dp),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer.copy(alpha = 0.7f))
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = colors.onSecondaryContainer,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
          Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSecondaryContainer.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Box(Modifier.size(40.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, "Play $title", tint = colors.onPrimary, modifier = Modifier.size(22.dp))
      }
    }
  }
}

@Composable
private fun HomePlaybackActions(
  onShuffleAll: () -> Unit,
  onSurpriseMe: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(top = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Button(
      onClick = onShuffleAll,
      modifier = Modifier.weight(1f).heightIn(min = 48.dp),
      shape = RoundedCornerShape(18.dp),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Shuffle library", style = MaterialTheme.typography.labelMedium)
    }
    FilledTonalButton(
      onClick = onSurpriseMe,
      modifier = Modifier.weight(1f).heightIn(min = 48.dp),
      shape = RoundedCornerShape(18.dp),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Surprise me", style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun HomeAlbumShelf(
  title: String,
  albums: List<AlbumItem>,
  onOpenAlbum: (AlbumItem) -> Unit,
) {
  Column {
    HomeSectionHeader(title, Modifier.padding(horizontal = 20.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      items(albums, key = { it.id }) { album ->
        ArtworkCard(
          title = album.name,
          subtitle = album.artist,
          imageUrl = album.albumArtUrl,
          placeholder = Icons.Filled.Album,
          onClick = { onOpenAlbum(album) },
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSongRow(
  song: Song,
  isCurrent: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Artwork(song.albumArtUrl, null, Icons.Filled.MusicNote, Modifier.size(52.dp), RoundedCornerShape(12.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        song.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        song.artists?.firstOrNull() ?: "Unknown Artist",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      Icons.Filled.PlayArrow,
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** Album artwork opens the album; playback lives on the album screen. */
@Composable
private fun ArtworkCard(
  title: String,
  subtitle: String,
  imageUrl: String?,
  placeholder: androidx.compose.ui.graphics.vector.ImageVector,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale = rememberPressScale(isPressed, pressedScale = 0.97f)
  Column(
    Modifier.width(140.dp).scale(scale).clickable(
      interactionSource = interactionSource,
      indication = null,
      onClick = onClick,
    ),
  ) {
    Artwork(imageUrl, null, placeholder, Modifier.fillMaxWidth().aspectRatio(1f))
    Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        subtitle,
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
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    genres.forEach { genre ->
      Surface(
        onClick = { onPlayGenre(genre) },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      ) {
        Text(
          genre,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
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
