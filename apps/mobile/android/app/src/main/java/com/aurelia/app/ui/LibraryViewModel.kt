package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.jellyfinPrimaryImageUrl
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.LibrarySearchIndex
import uniffi.aurelia_core.LibrarySearchKind

@OptIn(FlowPreview::class)
class LibraryViewModel(
  private val sessionStore: SessionStore,
  private val playerController: PlayerController,
) : ViewModel() {
  private val mutableState = MutableStateFlow(LibraryState())
  val state: StateFlow<LibraryState> = mutableState

  private val searchQuery = MutableStateFlow("")
  private val mutableSearchState = MutableStateFlow(LibrarySearchState())
  val searchState: StateFlow<LibrarySearchState> = mutableSearchState

  private val nowPlayingMapper = NowPlayingMapper()

  init {
    viewModelScope.launch {
      sessionStore.library.snapshots.collect { library ->
        library.error?.let { AuthInterceptor.handlePotentialAuthError(it) }
        mutableState.update {
          it.copy(songs = library.songs.orEmpty(), isLoading = library.isLoading, error = library.error?.message)
        }
      }
    }
    viewModelScope.launch {
      playerController.snapshots.collect { snapshot ->
        if (!nowPlayingMapper.shouldUpdate(snapshot)) return@collect
        val (nowPlaying, songId) =
          nowPlayingMapper.mapToNowPlaying(
            snapshot,
            includeNavigation = true,
          )
        mutableState.update { it.copy(nowPlaying = nowPlaying, currentSongId = songId) }
      }
    }

    viewModelScope.launch {
      sessionStore.library.snapshots
        .map { it.songs.orEmpty() }
        .distinctUntilChanged()
        .collectLatest { songs ->
          var index: LibrarySearchIndex? = null
          val songsById by lazy { songs.associateBy { it.id } }
          mutableSearchState.update {
            it.copy(results = emptyList(), isSearching = it.query.trim().length >= UiConstants.MIN_SEARCH_LENGTH)
          }
          try {
            searchQuery
              .debounce { if (it.trim().length < UiConstants.MIN_SEARCH_LENGTH) 0L else SEARCH_DEBOUNCE_MS }
              .collectLatest { query ->
                val results =
                  if (query.trim().length < UiConstants.MIN_SEARCH_LENGTH) {
                    emptyList()
                  } else {
                    withContext(Dispatchers.Default) {
                      val searchIndex = index ?: LibrarySearchIndex(songs).also { index = it }
                      searchIndex.search(query, UiConstants.SEARCH_RESULTS_LIMIT.toUInt()).mapNotNull { hit ->
                        when (hit.kind) {
                          LibrarySearchKind.SONG -> songsById[hit.id]?.let { SearchResult.SongResult(it) }
                          LibrarySearchKind.ALBUM ->
                            SearchResult.Album(
                              hit.id,
                              hit.name,
                              hit.artistNames.joinToString(", ").ifBlank { "Unknown Artist" },
                              hit.artworkUrl,
                            )
                          LibrarySearchKind.ARTIST ->
                            SearchResult.Artist(
                              hit.id,
                              hit.name,
                              hit.songCount.toInt(),
                              jellyfinPrimaryImageUrl(sessionStore.getServerUrl(), hit.id, sessionStore.getToken()),
                            )
                        }
                      }
                    }
                  }
                mutableSearchState.update {
                  if (it.query == query) it.copy(results = results, isSearching = false) else it
                }
              }
          } finally {
            index?.close()
          }
        }
    }
  }

  fun ensureLoaded(force: Boolean = false) {
    val session = validateSession(sessionStore)
    if (session == null) {
      mutableState.update { it.copy(isLoading = false, error = "Missing session data") }
      return
    }
    sessionStore.library.ensureLoaded(session, force)
  }

  fun loadLibrary() {
    ensureLoaded(force = false)
  }

  fun updateSearchQuery(query: String) {
    if (query == searchQuery.value) return
    mutableSearchState.value =
      LibrarySearchState(query = query, isSearching = query.trim().length >= UiConstants.MIN_SEARCH_LENGTH)
    searchQuery.value = query
  }

  fun play(songId: String) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val song = mutableState.value.songs.firstOrNull { it.id == songId } ?: return

    // Update current song ID immediately for responsive UI
    mutableState.update { it.copy(currentSongId = songId) }

    playerController.play(song, serverUrl, token)
  }

  /**
   * Plays a song from the current song list, setting up the full queue for next/previous navigation.
   * @param songId The ID of the song to start playing
   */
  fun playFromList(songId: String) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val songs = mutableState.value.songs

    val startIndex = songs.indexOfFirst { it.id == songId }
    if (startIndex < 0) return

    // Update current song ID immediately for responsive UI
    mutableState.update { it.copy(currentSongId = songId) }

    playerController.setQueue(songs, serverUrl, token, startIndex)
  }

  fun togglePlayPause() {
    val nowPlaying = mutableState.value.nowPlaying ?: return
    if (nowPlaying.isPlaying) {
      playerController.pause()
    } else {
      playerController.resume()
    }
  }

  fun skipPrevious() {
    playerController.skipPrevious()
  }

  fun skipNext() {
    playerController.skipNext()
  }
}

private const val SEARCH_DEBOUNCE_MS = 120L
