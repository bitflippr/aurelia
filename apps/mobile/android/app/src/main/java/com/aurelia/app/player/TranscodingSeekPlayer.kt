package com.aurelia.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.IdentityHashMap

/**
 * Makes Jellyfin's offset-based transcoded streams look like a normally seekable item.
 *
 * The wrapper is owned by the playback service so every controller, including Android Auto,
 * observes the same absolute position and seek behavior.
 */
internal class TranscodingSeekPlayer(
  player: Player,
) : ForwardingPlayer(player) {
  private var seekOffsetMs = 0L
  private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()

  init {
    player.addListener(
      object : Player.Listener {
        override fun onMediaItemTransition(
          mediaItem: MediaItem?,
          reason: Int,
        ) {
          if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            seekOffsetMs = 0L
          }
        }
      },
    )
  }

  override fun getAvailableCommands(): Player.Commands =
    super
      .getAvailableCommands()
      .buildUpon()
      .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
      .build()

  override fun isCommandAvailable(command: Int): Boolean =
    command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ||
      command == Player.COMMAND_SEEK_TO_MEDIA_ITEM ||
      super.isCommandAvailable(command)

  override fun addListener(listener: Player.Listener) {
    super.addListener(listener)
    val commandListener =
      listeners.getOrPut(listener) {
        object : Player.Listener {
          override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            // Keep the original listener registered so every Media3 callback is preserved.
            // Immediately correct its command update for EAC3 stereo streams: native FLAC
            // seeking is unavailable, but this wrapper can seek with startTimeTicks.
            val uri = currentMediaItem?.localConfiguration?.uri
            val isStereoFlacTranscode =
              uri?.getQueryParameter("transcodingContainer") == "flac" &&
                uri.getQueryParameter("transcodingAudioChannels") == "2"
            if (isStereoFlacTranscode) {
              listener.onAvailableCommandsChanged(this@TranscodingSeekPlayer.availableCommands)
            }
          }
        }
      }
    super.addListener(commandListener)
  }

  override fun removeListener(listener: Player.Listener) {
    super.removeListener(listener)
    listeners.remove(listener)?.let { super.removeListener(it) }
  }

  override fun setMediaItems(mediaItems: List<MediaItem>) {
    seekOffsetMs = 0L
    super.setMediaItems(mediaItems)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    resetPosition: Boolean,
  ) {
    seekOffsetMs = 0L
    super.setMediaItems(mediaItems, resetPosition)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    startMediaItemIndex: Int,
    startPositionMs: Long,
  ) {
    seekOffsetMs = 0L
    super.setMediaItems(mediaItems, startMediaItemIndex, startPositionMs)
  }

  override fun setMediaItem(mediaItem: MediaItem) {
    seekOffsetMs = 0L
    super.setMediaItem(mediaItem)
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    startPositionMs: Long,
  ) {
    seekOffsetMs = 0L
    super.setMediaItem(mediaItem, startPositionMs)
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    resetPosition: Boolean,
  ) {
    seekOffsetMs = 0L
    super.setMediaItem(mediaItem, resetPosition)
  }

  override fun clearMediaItems() {
    seekOffsetMs = 0L
    super.clearMediaItems()
  }

  override fun seekTo(positionMs: Long) {
    seekTo(currentMediaItemIndex, positionMs)
  }

  override fun seekTo(
    mediaItemIndex: Int,
    positionMs: Long,
  ) {
    if (mediaItemIndex != currentMediaItemIndex || AureliaMediaItems.isDirectlySeekable(currentMediaItem)) {
      seekOffsetMs = 0L
      super.seekTo(mediaItemIndex, positionMs)
      return
    }

    val item = currentMediaItem
    val currentUri = item?.localConfiguration?.uri
    if (item == null || currentUri == null) {
      seekOffsetMs = 0L
      super.seekTo(mediaItemIndex, positionMs)
      return
    }

    val fullDuration = item.mediaMetadata.durationMs ?: C.TIME_UNSET
    val targetPosition =
      if (fullDuration == C.TIME_UNSET || fullDuration <= 0L) {
        positionMs.coerceAtLeast(0L)
      } else {
        positionMs.coerceIn(0L, fullDuration)
      }
    val updatedItem = item.buildUpon().setUri(currentUri.withStartTime(targetPosition)).build()
    val queue = List(mediaItemCount) { getMediaItemAt(it) }.toMutableList()
    queue[mediaItemIndex] = updatedItem
    val shouldPlay = playWhenReady

    seekOffsetMs = targetPosition
    wrappedPlayer.setMediaItems(queue, mediaItemIndex, 0L)
    wrappedPlayer.prepare()
    wrappedPlayer.playWhenReady = shouldPlay
  }

  override fun seekToDefaultPosition() {
    seekOffsetMs = 0L
    super.seekToDefaultPosition()
  }

  override fun seekToDefaultPosition(mediaItemIndex: Int) {
    seekOffsetMs = 0L
    super.seekToDefaultPosition(mediaItemIndex)
  }

  override fun seekToNextMediaItem() {
    seekOffsetMs = 0L
    super.seekToNextMediaItem()
  }

  override fun seekToPreviousMediaItem() {
    seekOffsetMs = 0L
    super.seekToPreviousMediaItem()
  }

  override fun getCurrentPosition(): Long = super.getCurrentPosition() + seekOffsetMs

  override fun getContentPosition(): Long = super.getContentPosition() + seekOffsetMs

  override fun getBufferedPosition(): Long = super.getBufferedPosition() + seekOffsetMs

  override fun getContentBufferedPosition(): Long = super.getContentBufferedPosition() + seekOffsetMs

  override fun getDuration(): Long =
    currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L } ?: super.getDuration()

  override fun getContentDuration(): Long = duration

  private fun Uri.withStartTime(positionMs: Long): Uri {
    val builder = buildUpon().clearQuery()
    queryParameterNames
      .filterNot { it == START_TIME_TICKS }
      .forEach { name ->
        getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
      }
    return builder.appendQueryParameter(START_TIME_TICKS, (positionMs * TICKS_PER_MS).toString()).build()
  }

  private companion object {
    const val START_TIME_TICKS = "startTimeTicks"
    const val TICKS_PER_MS = 10_000L
  }
}
