package com.aurelia.app.ui

import uniffi.aurelia_core.Song

/**
 * An instant mix seeded from an artist or song the user listens to a lot.
 */
data class HomeMix(
  val seedId: String,
  val seedTitle: String,
  val artworkUrl: String?,
  val songs: List<Song>,
)

/**
 * State for the Home screen
 */
data class HomeState(
  val isLoading: Boolean = false,
  val error: String? = null,
  // Dense grid of recent + frequent plays
  val quickPicks: List<Song> = emptyList(),
  // Recently played songs (sorted by datePlayed)
  val recentlyPlayed: List<Song> = emptyList(),
  // Albums derived from recently played songs, in recency order
  val recentAlbums: List<AlbumItem> = emptyList(),
  // Favorited songs not played for the longest time
  val forgottenFavorites: List<Song> = emptyList(),
  // Instant mixes seeded from top artists/songs (loaded lazily)
  val mixes: List<HomeMix> = emptyList(),
  // Recently added albums (sorted by dateCreated)
  val recentlyAddedAlbums: List<AlbumItem> = emptyList(),
  // Random albums from library
  val randomAlbums: List<AlbumItem> = emptyList(),
  // Most common genres in the library
  val topGenres: List<String> = emptyList(),
  // Player state
  val nowPlaying: NowPlayingState? = null,
  val currentSongId: String? = null,
)
