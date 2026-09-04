package com.aurelia.app.ui

import uniffi.aurelia_core.Song

/**
 * Sealed class representing items in the album song list.
 * Can be either a disc header or a song item.
 */
sealed class ListItem {
  data class DiscHeader(
    val discNumber: Int,
  ) : ListItem()

  data class SongItem(
    val song: Song,
    val index: Int = -1,
  ) : ListItem()
}
