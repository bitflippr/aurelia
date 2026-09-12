package com.aurelia.app.ui

import uniffi.aurelia_core.Song
import java.util.Locale

/** Describe the actual queue, independently of the artist/song used to request it. */
internal fun createHomeMix(
  seedId: String,
  mixSongs: List<Song>,
  librarySongs: List<Song> = emptyList(),
): HomeMix {
  val libraryById = librarySongs.associateBy { it.id }
  val genres =
    mixSongs.flatMap { song ->
      song.genres.cleaned().ifEmpty { libraryById[song.id]?.genres.cleaned() }
    }
  val dominantGenre =
    genres
      .groupingBy { it.lowercase(Locale.ROOT) }
      .eachCount()
      .entries
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .firstOrNull()
      ?.takeIf { it.value * 2 >= mixSongs.size }
      ?.let { winner -> genres.first { it.lowercase(Locale.ROOT) == winner.key } }
  return HomeMix(
    seedId = seedId,
    title = dominantGenre?.let { "${it.replaceFirstChar(Char::titlecase)} mix" } ?: "Discovery mix",
    artistNames =
      mixSongs
        .flatMap { it.artists.cleaned().ifEmpty { libraryById[it.id]?.artists.cleaned() } }
        .cleaned()
        .take(3),
    artworkUrls =
      mixSongs
        .mapNotNull { song ->
          song.albumArtUrl?.takeIf(String::isNotBlank)
            ?: libraryById[song.id]?.albumArtUrl?.takeIf(String::isNotBlank)
        }.distinct()
        .take(4),
    songs = mixSongs,
  )
}

private fun List<String>?.cleaned(): List<String> =
  orEmpty().map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }
