package com.aurelia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.ArtistAvatar
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryLoadingState
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.components.PlaylistPickerDialog
import com.aurelia.app.ui.components.SongContextMenu
import com.aurelia.app.ui.components.rememberContextMenuState
import com.aurelia.app.ui.navigation.Screen
import uniffi.aurelia_core.Song

@Composable
fun SearchScreen(
  libraryViewModel: LibraryViewModel,
  sessionStore: SessionStore,
  playerController: PlayerController,
  playlistViewModel: PlaylistViewModel,
  onOpenPlayer: () -> Unit,
  onNavigateToAlbum: (Screen.AlbumDetail) -> Unit,
  onNavigateToArtist: (Screen.ArtistDetail) -> Unit,
  hasPlayerBar: Boolean = false,
) {
  val library by libraryViewModel.state.collectAsStateWithLifecycle()
  val search by libraryViewModel.searchState.collectAsStateWithLifecycle()
  val playlists by playlistViewModel.state.collectAsStateWithLifecycle()
  val contextMenu = rememberContextMenuState()

  LaunchedEffect(libraryViewModel) { libraryViewModel.ensureLoaded() }

  SearchContent(
    search = search,
    libraryIsLoading = library.isLoading,
    libraryIsEmpty = library.songs.isEmpty(),
    libraryError = library.error,
    currentSongId = library.currentSongId,
    isPlaying = library.nowPlaying?.isPlaying == true,
    hasPlayerBar = hasPlayerBar,
    onQueryChange = libraryViewModel::updateSearchQuery,
    onRetry = { libraryViewModel.ensureLoaded(force = true) },
    onPlaySong = {
      libraryViewModel.play(it.id)
      onOpenPlayer()
    },
    onOpenAlbum = onNavigateToAlbum,
    onOpenArtist = onNavigateToArtist,
    onSongMenu = { contextMenu.openContextMenu(it) },
    songMenu = { song ->
      SongContextMenu(
        song = song,
        expanded = contextMenu.showContextMenu && contextMenu.selectedSong?.id == song.id,
        onDismiss = { contextMenu.dismissContextMenu() },
        onAddToQueue = {
          val server = sessionStore.getServerUrl() ?: return@SongContextMenu
          val token = sessionStore.getToken() ?: return@SongContextMenu
          playerController.addToQueue(song, server, token)
        },
        onPlayNext = {
          val server = sessionStore.getServerUrl() ?: return@SongContextMenu
          val token = sessionStore.getToken() ?: return@SongContextMenu
          playerController.playNext(song, server, token)
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
      playlists = playlists.playlists,
      isLoading = playlists.isLoading,
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

private enum class SearchCategory(
  val label: String,
) {
  All("All"),
  Songs("Songs"),
  Albums("Albums"),
  Artists("Artists"),
}

@Composable
internal fun SearchContent(
  search: LibrarySearchState,
  libraryIsLoading: Boolean,
  libraryIsEmpty: Boolean,
  libraryError: String?,
  currentSongId: String?,
  isPlaying: Boolean,
  hasPlayerBar: Boolean,
  onQueryChange: (String) -> Unit,
  onRetry: () -> Unit,
  onPlaySong: (Song) -> Unit,
  onOpenAlbum: (Screen.AlbumDetail) -> Unit,
  onOpenArtist: (Screen.ArtistDetail) -> Unit,
  onSongMenu: (Song) -> Unit,
  songMenu: @Composable (Song) -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val keyboard = LocalSoftwareKeyboardController.current
  var category by rememberSaveable { mutableStateOf(SearchCategory.All) }
  var lastQuery by rememberSaveable { mutableStateOf(search.query) }
  var lastCategory by rememberSaveable { mutableStateOf(category) }
  val listState = rememberLazyListState()
  val visibleResults =
    remember(search.results, category) {
      search.results.filter { result ->
        when (category) {
          SearchCategory.All -> true
          SearchCategory.Songs -> result is SearchResult.SongResult
          SearchCategory.Albums -> result is SearchResult.Album
          SearchCategory.Artists -> result is SearchResult.Artist
        }
      }
    }
  LaunchedEffect(search.query, category) {
    if (lastQuery != search.query || lastCategory != category) listState.scrollToItem(0)
    lastQuery = search.query
    lastCategory = category
  }

  Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
    LibraryScreenHeader(title = "Search", modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    OutlinedTextField(
      value = search.query,
      onValueChange = onQueryChange,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("search-query"),
      placeholder = { Text("Songs, albums, artists") },
      leadingIcon = { Icon(Icons.Filled.Search, null) },
      trailingIcon = {
        if (search.query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, "Clear search") }
        }
      },
      singleLine = true,
      shape = RoundedCornerShape(24.dp),
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedBorderColor = colors.primary,
          unfocusedBorderColor = colors.outlineVariant,
          focusedContainerColor = colors.surfaceContainerLow,
          unfocusedContainerColor = colors.surfaceContainerLow,
        ),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(SearchCategory.entries) { item ->
        FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item.label) })
      }
    }
    when {
      libraryIsEmpty && libraryError != null ->
        LibraryMessageState(
          icon = Icons.Filled.Search,
          title = "Couldn't load your library",
          subtitle = "Try loading your music again.",
          isError = true,
          actionLabel = "Retry",
          onAction = onRetry,
          modifier = Modifier.fillMaxSize(),
        )
      libraryIsEmpty && libraryIsLoading -> LibraryLoadingState(Modifier.fillMaxSize())
      search.query.trim().length < UiConstants.MIN_SEARCH_LENGTH ->
        LibraryMessageState(
          icon = Icons.Filled.Search,
          title = if (search.query.isBlank()) "Search your library" else "Keep typing",
          subtitle =
            if (search.query.isBlank()) {
              "Find a song, album, or artist."
            } else {
              "Enter at least ${UiConstants.MIN_SEARCH_LENGTH} characters."
            },
          modifier = Modifier.fillMaxSize(),
        )
      search.isSearching ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
          LibraryLoadingState(Modifier.padding(top = 32.dp).size(48.dp).testTag("search-loading"))
        }
      visibleResults.isEmpty() ->
        LibraryMessageState(
          icon = Icons.Filled.Search,
          title = if (category == SearchCategory.All) "No results" else "No ${category.label.lowercase()} found",
          subtitle = "Try another name or a shorter search.",
          modifier = Modifier.fillMaxSize(),
        )
      else ->
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().testTag("search-results"),
          contentPadding =
            PaddingValues(
              start = 20.dp,
              end = 20.dp,
              bottom = BottomBarDimensions.calculateBottomPadding(hasPlayerBar),
            ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(visibleResults, key = { result ->
            when (result) {
              is SearchResult.SongResult -> "song-${result.song.id}"
              is SearchResult.Album -> "album-${result.id}"
              is SearchResult.Artist -> "artist-${result.id}"
            }
          }) { result ->
            when (result) {
              is SearchResult.SongResult ->
                Box {
                  MediaListItem(
                    title = result.song.name,
                    subtitle = result.song.artists?.joinToString(", ") ?: "Unknown Artist",
                    metadata = "Song",
                    imageUrl = result.song.albumArtUrl,
                    artworkStyle = AlbumArtStyle.Song,
                    isCurrent = currentSongId == result.song.id,
                    isPlaying = isPlaying && currentSongId == result.song.id,
                    onClick = {
                      keyboard?.hide()
                      onPlaySong(result.song)
                    },
                    onLongClick = { onSongMenu(result.song) },
                  )
                  songMenu(result.song)
                }
              is SearchResult.Album ->
                MediaListItem(
                  title = result.name,
                  subtitle = result.artist,
                  metadata = "Album",
                  imageUrl = result.albumArtUrl,
                  artworkStyle = AlbumArtStyle.Album,
                  onClick = {
                    keyboard?.hide()
                    onOpenAlbum(Screen.AlbumDetail(result.id, result.name))
                  },
                )
              is SearchResult.Artist ->
                MediaListItem(
                  title = result.name,
                  subtitle = "${result.songCount} ${if (result.songCount == 1) "song" else "songs"}",
                  metadata = "Artist",
                  imageUrl = null,
                  leadingContent = { ArtistAvatar(size = 54.dp, imageUrl = result.imageUrl) },
                  onClick = {
                    keyboard?.hide()
                    onOpenArtist(Screen.ArtistDetail(result.id, result.name))
                  },
                )
            }
          }
        }
    }
  }
}
