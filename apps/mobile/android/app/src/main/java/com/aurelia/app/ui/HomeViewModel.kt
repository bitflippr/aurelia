package com.aurelia.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.deriveMobileHomeData
import uniffi.aurelia_core.getInstantMix

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
            session.appDataDir == library.profilePath
          ) {
            loadMixes(session.serverUrl, session.token, songs)
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

  /**
   * Build instant mixes from the user's top artists and top song.
   * Runs lazily after fresh data arrives; failures simply omit that mix.
   */
  private fun loadMixes(
    serverUrl: String,
    token: String,
    songs: List<Song>,
  ) {
    if (mixesJob?.isActive == true) return
    if (mutableState.value.mixes.isNotEmpty()) return
    val profilePath = sessionStore.getAppDataDir()
    mixesJob =
      viewModelScope.launch(Dispatchers.IO) {
        val played = songs.filter { (it.playCount ?: 0) > 0 }
        if (played.isEmpty()) return@launch

        // Top artists by cumulative play count
        val topArtists =
          played
            .flatMap { song ->
              val plays = song.playCount ?: 0
              song.artistIds.orEmpty().zip(song.artists.orEmpty()).map { (id, name) ->
                Triple(id, name, plays)
              }
            }.groupBy { it.first }
            .map { (_, entries) -> entries.first().first to entries.sumOf { it.third } }
            .sortedByDescending { it.second }
            .map { it.first }

        val topSongId = played.maxByOrNull { it.playCount ?: 0 }?.id

        val seedIds = (topArtists + listOfNotNull(topSongId)).distinct().take(UiConstants.MIX_SEEDS_LIMIT)

        // Build all mixes in parallel, then swap them in atomically so the row doesn't jump
        val mixes =
          seedIds
            .map { seedId ->
              async {
                try {
                  val mixSongs = getInstantMix(serverUrl, token, seedId).take(UiConstants.MIX_SIZE_LIMIT)
                  if (mixSongs.isEmpty()) return@async null
                  HomeMix(
                    seedId = seedId,
                    seedTitle = mixSeedTitle(seedId, songs, mixSongs),
                    artworkUrl = mixSongs.firstOrNull { !it.albumArtUrl.isNullOrBlank() }?.albumArtUrl,
                    songs = mixSongs,
                  )
                } catch (e: Exception) {
                  if (e is kotlinx.coroutines.CancellationException) throw e
                  Log.w("HomeViewModel", "Failed to load instant mix for $seedId", e)
                  null
                }
              }
            }.awaitAll()
            .filterNotNull()

        if (mixes.isNotEmpty() && profilePath == sessionStore.getAppDataDir()) {
          mutableState.update { it.copy(mixes = mixes) }
        }
      }
  }

  private fun mixSeedTitle(
    seedId: String,
    songs: List<Song>,
    mixSongs: List<Song>,
  ): String {
    val seedSong = songs.firstOrNull { it.id == seedId }
    if (seedSong != null) return seedSong.name
    val artistSong =
      (mixSongs + songs).firstOrNull { song -> song.artistIds.orEmpty().contains(seedId) }
    return artistSong?.artists.orEmpty().firstOrNull() ?: "Your mix"
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
