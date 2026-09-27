package com.aurelia.app.ui

import com.aurelia.app.data.model.Lyrics
import com.aurelia.app.player.RepeatMode
import uniffi.aurelia_core.Song

data class PlayerState(
  val title: String = "",
  val artist: String = "",
  val albumArtUrl: String? = null,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val queue: List<Song> = emptyList(),
  val currentQueueIndex: Int = -1,
  val lyrics: Lyrics? = null,
  val showLyrics: Boolean = false,
  val isShuffled: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.NONE,
  val currentSongId: String? = null,
  val currentAlbumId: String? = null,
  val currentArtistId: String? = null,
  val currentAlbumName: String? = null,
  val isFavorite: Boolean = false,
  val isFavoriteLoading: Boolean = false,
  val playbackSpeed: Float = 1f,
  val updateTimeMs: Long = 0L,
  val positionDiscontinuitySequence: Long = 0L,
  val codec: String? = null,
  val bitRate: Int? = null,
  val sampleRate: Int? = null,
  val formatInfo: String? = null,
) {
  val hasPrevious: Boolean get() = currentQueueIndex > 0
  val hasNext: Boolean get() = currentQueueIndex >= 0 && currentQueueIndex < queue.size - 1
}
