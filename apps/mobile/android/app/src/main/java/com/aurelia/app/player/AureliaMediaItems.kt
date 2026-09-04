package com.aurelia.app.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import uniffi.aurelia_core.Song

internal object AureliaMediaItems {
  const val EXTRA_ALBUM_ID = "album_id"
  const val EXTRA_ARTIST_ID = "artist_id"
  const val EXTRA_ALBUM_NAME = "album_name"
  const val EXTRA_CONTAINER = "container"
  const val EXTRA_CODEC = "codec"
  const val EXTRA_BIT_RATE = "bit_rate"
  const val EXTRA_SAMPLE_RATE = "sample_rate"
  const val EXTRA_BROWSE_PARENT_ID = "browse_parent_id"
  private const val EXTRA_ARTISTS = "artists"
  private const val EXTRA_ARTIST_IDS = "artist_ids"

  private val directlySeekableContainers = setOf("flac", "mp3", "aac", "ogg")

  fun playableSong(
    song: Song,
    uri: String,
  ): MediaItem =
    MediaItem
      .Builder()
      .setMediaId(song.id)
      .setUri(uri)
      .setMediaMetadata(songMetadata(song, isBrowsable = false, isPlayable = true))
      .build()

  fun browsableSong(
    song: Song,
    mediaId: String,
    parentId: String,
  ): MediaItem =
    MediaItem
      .Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(
        songMetadata(song, isBrowsable = false, isPlayable = true, parentId = parentId),
      ).build()

  fun folder(
    mediaId: String,
    title: String,
    subtitle: String? = null,
    artworkUri: String? = null,
    mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    isPlayable: Boolean = false,
  ): MediaItem {
    val metadata =
      MediaMetadata
        .Builder()
        .setTitle(title)
        .setIsBrowsable(true)
        .setIsPlayable(isPlayable)
        .setMediaType(mediaType)
    subtitle?.takeIf { it.isNotBlank() }?.let(metadata::setSubtitle)
    artworkUri?.takeIf { it.isNotBlank() }?.let { metadata.setArtworkUri(Uri.parse(it)) }
    return MediaItem
      .Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(metadata.build())
      .build()
  }

  fun isDirectlySeekable(mediaItem: MediaItem?): Boolean {
    val container = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_CONTAINER)
    return container?.lowercase() in directlySeekableContainers
  }

  fun browseParentId(mediaItem: MediaItem): String? = mediaItem.mediaMetadata.extras?.getString(EXTRA_BROWSE_PARENT_ID)

  fun songFrom(mediaItem: MediaItem): Song? {
    val metadata = mediaItem.mediaMetadata
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: return null
    val extras = metadata.extras
    return Song(
      id = mediaId,
      name = title,
      itemType = "Audio",
      album = metadata.albumTitle?.toString() ?: extras?.getString(EXTRA_ALBUM_NAME),
      albumId = extras?.getString(EXTRA_ALBUM_ID),
      artists = extras?.getStringArrayList(EXTRA_ARTISTS)?.toList(),
      artistIds = extras?.getStringArrayList(EXTRA_ARTIST_IDS)?.toList(),
      path = null,
      duration = metadata.durationMs?.let { it / 1_000.0 },
      albumArtUrl = metadata.artworkUri?.toString(),
      year = metadata.releaseYear,
      playCount = null,
      isFavorite = null,
      discNumber = metadata.discNumber,
      trackNumber = metadata.trackNumber,
      container = extras?.getString(EXTRA_CONTAINER),
      bitRate = extras?.getInt(EXTRA_BIT_RATE)?.takeIf { it != 0 },
      sampleRate = extras?.getInt(EXTRA_SAMPLE_RATE)?.takeIf { it != 0 },
      codec = extras?.getString(EXTRA_CODEC),
      genres = null,
      premiereDate = null,
      datePlayed = null,
      dateCreated = null,
      dateModified = null,
      albumArtists = null,
      lyrics = null,
      imageTags = null,
    )
  }

  private fun songMetadata(
    song: Song,
    isBrowsable: Boolean,
    isPlayable: Boolean,
    parentId: String? = null,
  ): MediaMetadata {
    val artist = song.artists?.joinToString(", ").orEmpty()
    val extras =
      Bundle().apply {
        song.albumId?.let { putString(EXTRA_ALBUM_ID, it) }
        song.artistIds?.firstOrNull()?.let { putString(EXTRA_ARTIST_ID, it) }
        song.album?.let { putString(EXTRA_ALBUM_NAME, it) }
        song.container?.let { putString(EXTRA_CONTAINER, it) }
        song.codec?.let { putString(EXTRA_CODEC, it) }
        song.bitRate?.let { putInt(EXTRA_BIT_RATE, it) }
        song.sampleRate?.let { putInt(EXTRA_SAMPLE_RATE, it) }
        song.artists?.let { putStringArrayList(EXTRA_ARTISTS, ArrayList(it)) }
        song.artistIds?.let { putStringArrayList(EXTRA_ARTIST_IDS, ArrayList(it)) }
        parentId?.let { putString(EXTRA_BROWSE_PARENT_ID, it) }
      }
    val metadata =
      MediaMetadata
        .Builder()
        .setTitle(song.name)
        .setArtist(artist)
        .setAlbumTitle(song.album)
        .setTrackNumber(song.trackNumber)
        .setDiscNumber(song.discNumber)
        .setReleaseYear(song.year)
        .setIsBrowsable(isBrowsable)
        .setIsPlayable(isPlayable)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
    song.duration
      ?.takeIf { it > 0.0 }
      ?.let { metadata.setDurationMs((it * 1_000).toLong()) }
    song.albumArtUrl?.takeIf { it.isNotBlank() }?.let { metadata.setArtworkUri(Uri.parse(it)) }
    return metadata.build()
  }
}
