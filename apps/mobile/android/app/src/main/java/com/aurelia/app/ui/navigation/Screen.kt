package com.aurelia.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Screen {
  @Serializable
  data object Home : Screen()

  @Serializable
  data object Songs : Screen()

  @Serializable
  data object Albums : Screen()

  @Serializable
  data object Artists : Screen()

  @Serializable
  data object Playlists : Screen()

  @Serializable
  data object Search : Screen()

  @Serializable
  data object Settings : Screen()

  // Detail screens with parameters
  @Serializable
  data class AlbumDetail(
    val albumId: String,
    val albumName: String,
  ) : Screen()

  @Serializable
  data class ArtistDetail(
    val artistId: String,
    val artistName: String,
  ) : Screen()

  @Serializable
  data class PlaylistDetail(
    val playlistId: String,
    val playlistName: String,
  ) : Screen()
}
