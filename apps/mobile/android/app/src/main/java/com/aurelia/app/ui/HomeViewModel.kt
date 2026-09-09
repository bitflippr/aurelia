package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.SessionData
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.deriveMobileHomeData

class HomeViewModel(
  private val sessionStore: SessionStore,
  private val playerController: PlayerController,
) : ViewModel() {
  private val mutableState = MutableStateFlow(HomeState())
  val state: StateFlow<HomeState> = mutableState

  // All songs cache for queue building
  private var allSongs: List<Song> = emptyList()

  private var mixesJob: Job? = null
  private var libraryProfile: String? = null

  private val nowPlayingMapper = NowPlayingMapper()

  init {
    viewModelScope.launch {
      sessionStore.library.snapshots.collect { library ->
        if (library.profilePath != libraryProfile) {
          libraryProfile = library.profilePath
          mixesJob?.cancel()
          mixesJob = null
          allSongs = emptyList()
          mutableState.value = HomeState(isLoading = library.isLoading)
        }
        library.songs?.let { songs ->
          if (songs != allSongs || mutableState.value.isLoading) {
            allSongs = songs
            processHomeData(songs)
          }
          val session = validateSession(sessionStore)
          if (session != null &&
            session.appDataDir == library.profilePath &&
            (songs.isNotEmpty() || !library.isLoading)
          ) {
            loadMixes(session, songs)
          }
        }
        library.error?.let { AuthInterceptor.handlePotentialAuthError(it) }
        mutableState.update { it.copy(isLoading = library.isLoading, error = library.error?.message) }
      }
    }
    viewModelScope.launch {
      playerController.snapshots.collect { snapshot ->
        if (!nowPlayingMapper.shouldUpdate(snapshot)) return@collect
        val (nowPlaying, songId) = nowPlayingMapper.mapToNowPlaying(snapshot)
        mutableState.update { it.copy(nowPlaying = nowPlaying, currentSongId = songId) }
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

  fun loadHomeData() {
    ensureLoaded(force = false)
  }

  private fun processHomeData(songs: List<Song>) {
    val derived =
      deriveMobileHomeData(
        songs = songs,
        mostPlayedLimit = UiConstants.MOST_PLAYED_LIMIT.toLong(),
        recentlyPlayedLimit = UiConstants.RECENTLY_PLAYED_LIMIT.toLong(),
        albumSectionLimit = UiConstants.ALBUM_SECTION_LIMIT.toLong(),
        featuredAlbumsLimit = UiConstants.FEATURED_ALBUMS_LIMIT.toLong(),
      )

    val mostPlayed = derived.mostPlayed
    val recentlyPlayed = derived.recentlyPlayed

    val quickPicks =
      (mostPlayed + recentlyPlayed)
        .distinctBy { it.id }
        .take(UiConstants.QUICK_PICKS_LIMIT)

    // Albums from recently played songs, in recency order
    val recentAlbums =
      recentlyPlayed
        .filter { !it.albumId.isNullOrBlank() }
        .distinctBy { it.albumId }
        .take(UiConstants.RECENT_ALBUMS_LIMIT)
        .map { song ->
          AlbumItem(
            id = song.albumId.orEmpty(),
            name = song.album ?: "Unknown Album",
            artist = song.artists?.firstOrNull() ?: "Unknown Artist",
            albumArtUrl = song.albumArtUrl,
            songCount = 0,
          )
        }

    // Favorites not played for the longest time (never played first)
    val forgottenFavorites =
      songs
        .filter { it.isFavorite == true }
        .sortedBy { it.datePlayed ?: "" }
        .take(UiConstants.FORGOTTEN_FAVORITES_LIMIT)

    // Keep the current random order if the selection itself hasn't changed
    val newRandomAlbums = derived.randomAlbums.map(::toAlbumItem)
    val currentRandomAlbums = mutableState.value.randomAlbums
    val randomAlbums =
      if (currentRandomAlbums.map { it.id }.toSet() == newRandomAlbums.map { it.id }.toSet()) {
        currentRandomAlbums
      } else {
        newRandomAlbums
      }

    val topGenres =
      songs
        .flatMap { it.genres.orEmpty() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }
        .take(UiConstants.TOP_GENRES_LIMIT)

    mutableState.update {
      it.copy(
        isLoading = false,
        quickPicks = quickPicks,
        recentlyPlayed = recentlyPlayed,
        recentAlbums = recentAlbums,
        forgottenFavorites = forgottenFavorites,
        recentlyAddedAlbums = derived.recentlyAdded.map(::toAlbumItem),
        randomAlbums = randomAlbums,
        topGenres = topGenres,
      )
    }
  }

  private fun loadMixes(
    session: SessionData,
    songs: List<Song>,
  ) {
    if (mixesJob != null) return
    mixesJob =
      viewModelScope.launch {
        val mixes = HomeMixStore.loadOnce(session, songs)
        if (session.appDataDir == libraryProfile && session.appDataDir == sessionStore.getAppDataDir()) {
          mutableState.update { it.copy(mixes = mixes) }
        }
      }
  }

  /**
   * Play a song from a specific list, setting up the queue from that list
   */
  fun playSongFromList(
    songId: String,
    songList: List<Song>,
  ) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return

    val startIndex = songList.indexOfFirst { it.id == songId }
    if (startIndex < 0) return

    mutableState.update { it.copy(currentSongId = songId) }
    playerController.setQueue(songList, serverUrl, token, startIndex)
  }

  /**
   * Play all songs from an album
   */
  fun playAlbum(albumId: String) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return

    val albumSongs =
      allSongs
        .filter { it.albumId == albumId }
        .sortedBy { it.trackNumber ?: 0 }

    if (albumSongs.isEmpty()) return

    mutableState.update { it.copy(currentSongId = albumSongs.first().id) }
    playerController.setQueue(albumSongs, serverUrl, token)
  }

  /**
   * Shuffle play an album
   */
  fun shuffleAlbum(albumId: String) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return

    val albumSongs =
      allSongs
        .filter { it.albumId == albumId }
        .shuffled()

    if (albumSongs.isEmpty()) return

    mutableState.update { it.copy(currentSongId = albumSongs.first().id) }
    playerController.setQueue(albumSongs, serverUrl, token)
  }

  fun shuffleAll() {
    if (allSongs.isEmpty()) return
    playShuffled(allSongs)
  }

  fun playSurprise() {
    if (allSongs.isEmpty()) return
    val favorites = allSongs.filter { it.isFavorite == true }
    playShuffled(if (favorites.isNotEmpty()) favorites else allSongs)
  }

  fun playGenreMix(genre: String) {
    val genreSongs = allSongs.filter { song -> song.genres.orEmpty().contains(genre) }
    if (genreSongs.isEmpty()) return
    playShuffled(genreSongs)
  }

  fun playMix(mix: HomeMix) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    if (mix.songs.isEmpty()) return
    mutableState.update { it.copy(currentSongId = mix.songs.first().id) }
    playerController.setQueue(mix.songs, serverUrl, token)
  }

  private fun playShuffled(songs: List<Song>) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val shuffled = songs.shuffled()
    mutableState.update { it.copy(currentSongId = shuffled.first().id) }
    playerController.setQueue(shuffled, serverUrl, token)
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

  private fun toAlbumItem(album: uniffi.aurelia_core.Album): AlbumItem =
    AlbumItem(
      id = album.id ?: "",
      name = album.name,
      artist = album.artist,
      albumArtUrl = album.albumArtUrl,
      songCount = album.songCount.toInt(),
    )
}
