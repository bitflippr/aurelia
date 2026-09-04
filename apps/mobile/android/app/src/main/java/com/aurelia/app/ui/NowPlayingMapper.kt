package com.aurelia.app.ui

import com.aurelia.app.player.PlayerSnapshot

/** Compares the state shown by the mini-player, including stable song identity. */
class NowPlayingMapper {
  private var previous: Pair<NowPlayingState?, String?>? = null

  fun shouldUpdate(snapshot: PlayerSnapshot): Boolean {
    val projected = mapToNowPlaying(snapshot, includeNavigation = true)
    if (projected == previous) return false
    previous = projected
    return true
  }

  fun mapToNowPlaying(
    snapshot: PlayerSnapshot,
    includeNavigation: Boolean = false,
  ): Pair<NowPlayingState?, String?> {
    if (snapshot.currentSongId.isNullOrBlank()) return null to null
    return NowPlayingState(
      title = snapshot.title,
      artist = snapshot.artist,
      albumArtUrl = snapshot.albumArtUrl,
      isPlaying = snapshot.isPlaying,
      isBuffering = snapshot.isBuffering,
      hasPrevious = includeNavigation && snapshot.hasPrevious,
      hasNext = includeNavigation && snapshot.hasNext,
      albumId = snapshot.currentAlbumId,
      artistId = snapshot.currentArtistId,
      albumName = snapshot.currentAlbumName,
    ) to snapshot.currentSongId
  }
}
