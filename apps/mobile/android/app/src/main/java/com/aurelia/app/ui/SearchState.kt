package com.aurelia.app.ui

import uniffi.aurelia_core.Song

sealed class SearchResult {
  data class SongResult(
    val song: Song,
  ) : SearchResult()

  data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val albumArtUrl: String?,
  ) : SearchResult()

  data class Artist(
    val id: String,
    val name: String,
    val songCount: Int,
    val imageUrl: String?,
  ) : SearchResult()
}

data class LibrarySearchState(
  val query: String = "",
  val results: List<SearchResult> = emptyList(),
  val isSearching: Boolean = false,
)
