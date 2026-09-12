package com.aurelia.app.ui

import com.aurelia.app.ui.navigation.Screen
import uniffi.aurelia_core.Song

internal fun Song.safeAlbumId(): String? = albumId?.takeIf { it.isNotBlank() }

internal fun Song.safePrimaryArtistId(): String? = artistIds?.firstOrNull()?.takeIf { it.isNotBlank() }

internal fun artistDestinations(
  artistIds: List<String>?,
  artistNames: List<String>?,
): List<Screen.ArtistDetail> =
  artistIds
    .orEmpty()
    .mapIndexedNotNull { index, id ->
      if (id.isBlank()) {
        null
      } else {
        Screen.ArtistDetail(id, artistNames?.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
      }
    }.distinctBy { it.artistId }
