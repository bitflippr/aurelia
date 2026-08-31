package com.aurelia.app.player.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aurelia.app.player.AureliaMediaItems
import uniffi.aurelia_core.Playlist
import uniffi.aurelia_core.Song
import java.util.Locale

internal data class AutoMediaLabels(
  val root: String,
  val recentlyPlayed: String,
  val albums: String,
  val artists: String,
  val playlists: String,
  val songs: String,
  val songCount: (Int) -> String,
)

internal data class ResolvedPlayback(
  val mediaItems: List<MediaItem>,
  val startIndex: Int,
)

internal interface AutoMediaSource {
  suspend fun songs(): List<Song>

  suspend fun recentlyPlayed(): List<Song>

  suspend fun playlists(): List<Playlist>

  suspend fun playlistSongs(playlistId: String): List<Song>

  fun streamUrl(song: Song): String
}

internal class AutoMediaCatalog(
  private val source: AutoMediaSource,
  private val labels: AutoMediaLabels,
) {
  fun root(): MediaItem =
    AureliaMediaItems.folder(
      mediaId = AutoMediaIds.ROOT,
      title = labels.root,
      mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

  suspend fun children(
    parentId: String,
    page: Int,
    pageSize: Int,
  ): List<MediaItem>? {
    val children =
      when (parentId) {
        AutoMediaIds.ROOT -> rootChildren()
        AutoMediaIds.RECENT -> browseSongs(source.recentlyPlayed(), parentId)
        AutoMediaIds.ALBUMS -> index().albums.map(::albumItem)
        AutoMediaIds.ARTISTS -> index().artists.map(::artistItem)
        AutoMediaIds.PLAYLISTS -> source.playlists().sortedByName().map(::playlistItem)
        AutoMediaIds.SONGS -> browseSongs(index().songs.sortedBySongTitle(), parentId)
        else -> childrenOfEntity(parentId)
      } ?: return null
    return children.page(page, pageSize)
  }

  suspend fun item(mediaId: String): MediaItem? =
    when (mediaId) {
      AutoMediaIds.ROOT -> root()
      AutoMediaIds.RECENT,
      AutoMediaIds.ALBUMS,
      AutoMediaIds.ARTISTS,
      AutoMediaIds.PLAYLISTS,
      AutoMediaIds.SONGS,
      -> rootChildren().firstOrNull { it.mediaId == mediaId }
      else -> entityItem(mediaId)
    }

  suspend fun search(
    query: String,
    page: Int,
    pageSize: Int,
  ): List<MediaItem> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isBlank()) return emptyList()

    val index = index()
    val artists =
      index.artists
        .filter { it.name.lowercase(Locale.ROOT).contains(normalizedQuery) }
        .map(::artistItem)
    val albums =
      index.albums
        .filter {
          it.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            it.artist.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
        .map(::albumItem)
    val playlists =
      runCatching { source.playlists() }
        .getOrDefault(emptyList())
        .filter { it.name.lowercase(Locale.ROOT).contains(normalizedQuery) }
        .sortedByName()
        .map(::playlistItem)
    val songs =
      index.songs
        .filter { song ->
          song.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            song.album?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
            song.artists?.any { it.lowercase(Locale.ROOT).contains(normalizedQuery) } == true
        }
        .sortedBySongTitle()
        .map { browseSong(it, AutoMediaIds.SONGS) }

    return (artists + albums + playlists + songs).page(page, pageSize)
  }

  suspend fun resolveItems(mediaItems: List<MediaItem>): List<MediaItem> =
    mediaItems.flatMap { requested ->
      if (requested.localConfiguration != null) {
        listOf(requested)
      } else {
        val songId = AutoMediaIds.songValue(requested.mediaId) ?: requested.mediaId
        val song =
          songId
            .takeIf { it.isNotBlank() && !AutoMediaIds.isCatalogId(it) }
            ?.let { id -> index().songs.firstOrNull { it.id == id } }
        if (song != null) {
          listOf(AureliaMediaItems.playableSong(song, source.streamUrl(song)))
        } else {
          resolveSelection(requested)?.mediaItems.orEmpty()
        }
      }
    }

  suspend fun resolveSelection(requested: MediaItem): ResolvedPlayback? {
    if (requested.localConfiguration != null) {
      return ResolvedPlayback(listOf(requested), 0)
    }

    val query = requested.requestMetadata.searchQuery
    if (!query.isNullOrBlank()) {
      val matches = matchingSongs(query)
      return matches.toResolvedPlayback(startSongId = matches.firstOrNull()?.id)
    }

    val requestedId = requested.mediaId
    val parentId = AureliaMediaItems.browseParentId(requested)
    val requestedSongId = AutoMediaIds.songValue(requestedId) ?: requestedId.takeIf { it.isNotBlank() }
    val songs =
      when {
        requestedId == AutoMediaIds.RECENT -> source.recentlyPlayed()
        requestedId == AutoMediaIds.SONGS -> index().songs.sortedBySongTitle()
        AutoMediaIds.albumValue(requestedId) != null -> songsForAlbum(requestedId)
        AutoMediaIds.artistValue(requestedId) != null -> songsForArtist(requestedId)
        AutoMediaIds.playlistValue(requestedId) != null ->
          source.playlistSongs(requireNotNull(AutoMediaIds.playlistValue(requestedId)))
        AutoMediaIds.songValue(requestedId) != null -> songsForParent(parentId)
        else -> index().songs.sortedBySongTitle()
      }
    return songs.toResolvedPlayback(startSongId = requestedSongId)
  }

  private suspend fun entityItem(mediaId: String): MediaItem? {
    AutoMediaIds.songValue(mediaId)?.let { songId ->
      return index().songs.firstOrNull { it.id == songId }?.let { browseSong(it, AutoMediaIds.SONGS) }
    }
    AutoMediaIds.albumValue(mediaId)?.let { albumId ->
      return index().albums.firstOrNull { it.id == albumId }?.let(::albumItem)
    }
    AutoMediaIds.artistValue(mediaId)?.let { artistId ->
      return index().artists.firstOrNull { it.id == artistId }?.let(::artistItem)
    }
    AutoMediaIds.playlistValue(mediaId)?.let { playlistId ->
      return source.playlists().firstOrNull { it.id == playlistId }?.let(::playlistItem)
    }
    return null
  }

  private suspend fun childrenOfEntity(parentId: String): List<MediaItem>? {
    AutoMediaIds.albumValue(parentId)?.let {
      return browseSongs(songsForAlbum(parentId), parentId)
    }
    AutoMediaIds.artistValue(parentId)?.let {
      return browseSongs(songsForArtist(parentId), parentId)
    }
    AutoMediaIds.playlistValue(parentId)?.let { playlistId ->
      return browseSongs(source.playlistSongs(playlistId), parentId)
    }
    return null
  }

  private suspend fun songsForParent(parentId: String?): List<Song> =
    when {
      parentId == AutoMediaIds.RECENT -> source.recentlyPlayed()
      parentId == AutoMediaIds.SONGS || parentId.isNullOrBlank() -> index().songs.sortedBySongTitle()
      AutoMediaIds.albumValue(parentId) != null -> songsForAlbum(parentId)
      AutoMediaIds.artistValue(parentId) != null -> songsForArtist(parentId)
      AutoMediaIds.playlistValue(parentId) != null ->
        source.playlistSongs(requireNotNull(AutoMediaIds.playlistValue(parentId)))
      else -> index().songs.sortedBySongTitle()
    }

  private suspend fun songsForAlbum(albumMediaId: String): List<Song> {
    val albumId = AutoMediaIds.albumValue(albumMediaId) ?: return emptyList()
    return index().albums.firstOrNull { it.id == albumId }?.songs.orEmpty().sortedByTrack()
  }

  private suspend fun songsForArtist(artistMediaId: String): List<Song> {
    val artistId = AutoMediaIds.artistValue(artistMediaId) ?: return emptyList()
    return index().artists.firstOrNull { it.id == artistId }?.songs.orEmpty().sortedForArtist()
  }

  private suspend fun matchingSongs(query: String): List<Song> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return index().songs.filter { song ->
      song.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
        song.album?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
        song.artists?.any { it.lowercase(Locale.ROOT).contains(normalizedQuery) } == true
    }
  }

  private fun rootChildren(): List<MediaItem> =
    listOf(
      AureliaMediaItems.folder(
        AutoMediaIds.RECENT,
        labels.recentlyPlayed,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        isPlayable = true,
      ),
      AureliaMediaItems.folder(
        AutoMediaIds.ALBUMS,
        labels.albums,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
      ),
      AureliaMediaItems.folder(
        AutoMediaIds.ARTISTS,
        labels.artists,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
      ),
      AureliaMediaItems.folder(
        AutoMediaIds.PLAYLISTS,
        labels.playlists,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
      ),
      AureliaMediaItems.folder(
        AutoMediaIds.SONGS,
        labels.songs,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        isPlayable = true,
      ),
    )

  private fun albumItem(album: AutoAlbum): MediaItem =
    AureliaMediaItems.folder(
      mediaId = AutoMediaIds.album(album.id),
      title = album.name,
      subtitle = album.artist,
      artworkUri = album.artworkUri,
      mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
      isPlayable = true,
    )

  private fun artistItem(artist: AutoArtist): MediaItem =
    AureliaMediaItems.folder(
      mediaId = AutoMediaIds.artist(artist.id),
      title = artist.name,
      subtitle = labels.songCount(artist.songs.size),
      artworkUri = artist.artworkUri,
      mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
      isPlayable = true,
    )

  private fun playlistItem(playlist: Playlist): MediaItem =
    AureliaMediaItems.folder(
      mediaId = AutoMediaIds.playlist(playlist.id),
      title = playlist.name,
      subtitle = playlist.childCount?.let(labels.songCount),
      mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
      isPlayable = true,
    )

  private fun browseSongs(
    songs: List<Song>,
    parentId: String,
  ): List<MediaItem> = songs.map { browseSong(it, parentId) }

  private fun browseSong(
    song: Song,
    parentId: String,
  ): MediaItem = AureliaMediaItems.browsableSong(song, AutoMediaIds.song(song.id), parentId)

  private suspend fun index(): AutoCatalogIndex = AutoCatalogIndex(source.songs())

  private fun List<Song>.toResolvedPlayback(startSongId: String?): ResolvedPlayback? {
    if (isEmpty()) return null
    val playableItems = map { AureliaMediaItems.playableSong(it, source.streamUrl(it)) }
    val startIndex = indexOfFirst { it.id == startSongId }.coerceAtLeast(0)
    return ResolvedPlayback(playableItems, startIndex)
  }
}

internal data class AutoAlbum(
  val id: String,
  val name: String,
  val artist: String,
  val artworkUri: String?,
  val songs: List<Song>,
)

internal data class AutoArtist(
  val id: String,
  val name: String,
  val artworkUri: String?,
  val songs: List<Song>,
)

internal class AutoCatalogIndex(
  val songs: List<Song>,
) {
  val albums: List<AutoAlbum> =
    songs
      .filter { !it.album.isNullOrBlank() }
      .groupBy { song -> song.albumId?.takeIf { it.isNotBlank() } ?: AutoMediaIds.namedAlbum(song) }
      .map { (id, albumSongs) ->
        AutoAlbum(
          id = id,
          name = albumSongs.firstNotNullOfOrNull { it.album } ?: "Unknown album",
          artist = albumSongs.firstNotNullOfOrNull { it.albumArtists?.firstOrNull()?.name }
            ?: albumSongs.firstNotNullOfOrNull { it.artists?.firstOrNull() }.orEmpty(),
          artworkUri = albumSongs.firstNotNullOfOrNull { it.albumArtUrl?.takeIf(String::isNotBlank) },
          songs = albumSongs,
        )
      }
      .sortedWith(compareBy<AutoAlbum, String>(String.CASE_INSENSITIVE_ORDER) { it.name })

  val artists: List<AutoArtist> =
    buildMap<String, MutableList<Pair<String, Song>>> {
      songs.forEach { song ->
        song.artists.orEmpty().forEachIndexed { index, name ->
          val id = song.artistIds?.getOrNull(index)?.takeIf { it.isNotBlank() } ?: AutoMediaIds.namedArtist(name)
          getOrPut(id) { mutableListOf() }.add(name to song)
        }
      }
    }
      .map { (id, entries) ->
        AutoArtist(
          id = id,
          name = entries.first().first,
          artworkUri = entries.firstNotNullOfOrNull { it.second.albumArtUrl?.takeIf(String::isNotBlank) },
          songs = entries.map { it.second }.distinctBy { it.id },
        )
      }
      .sortedWith(compareBy<AutoArtist, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
}

internal object AutoMediaIds {
  const val ROOT = "aurelia:root"
  const val RECENT = "aurelia:category:recent"
  const val ALBUMS = "aurelia:category:albums"
  const val ARTISTS = "aurelia:category:artists"
  const val PLAYLISTS = "aurelia:category:playlists"
  const val SONGS = "aurelia:category:songs"

  private const val SONG_PREFIX = "aurelia:song:"
  private const val ALBUM_PREFIX = "aurelia:album:"
  private const val ARTIST_PREFIX = "aurelia:artist:"
  private const val PLAYLIST_PREFIX = "aurelia:playlist:"

  fun song(id: String): String = SONG_PREFIX + id

  fun album(id: String): String = ALBUM_PREFIX + id

  fun artist(id: String): String = ARTIST_PREFIX + id

  fun playlist(id: String): String = PLAYLIST_PREFIX + id

  fun songValue(mediaId: String): String? = mediaId.valueAfter(SONG_PREFIX)

  fun albumValue(mediaId: String): String? = mediaId.valueAfter(ALBUM_PREFIX)

  fun artistValue(mediaId: String): String? = mediaId.valueAfter(ARTIST_PREFIX)

  fun playlistValue(mediaId: String): String? = mediaId.valueAfter(PLAYLIST_PREFIX)

  fun isCatalogId(mediaId: String): Boolean = mediaId.startsWith("aurelia:")

  fun namedAlbum(song: Song): String =
    "name:${song.album.orEmpty().lowercase(Locale.ROOT)}:${song.artists?.firstOrNull().orEmpty().lowercase(Locale.ROOT)}"

  fun namedArtist(name: String): String = "name:${name.lowercase(Locale.ROOT)}"

  private fun String.valueAfter(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() }
}

private fun List<Song>.sortedByTrack(): List<Song> =
  sortedWith(
    compareBy<Song> { it.discNumber ?: Int.MAX_VALUE }
      .thenBy { it.trackNumber ?: Int.MAX_VALUE }
      .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
  )

private fun List<Song>.sortedForArtist(): List<Song> =
  sortedWith(
    compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album.orEmpty() }
      .thenBy { it.discNumber ?: Int.MAX_VALUE }
      .thenBy { it.trackNumber ?: Int.MAX_VALUE }
      .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
  )

private fun List<Song>.sortedBySongTitle(): List<Song> =
  sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.name })

private fun List<Playlist>.sortedByName(): List<Playlist> =
  sortedWith(compareBy<Playlist, String>(String.CASE_INSENSITIVE_ORDER) { it.name })

private fun <T> List<T>.page(
  page: Int,
  pageSize: Int,
): List<T> {
  if (page < 0 || pageSize <= 0) return emptyList()
  val fromIndex = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
  val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
  return subList(fromIndex, toIndex)
}
