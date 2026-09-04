package com.aurelia.app.player.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import uniffi.aurelia_core.Song

class AutoCatalogIndexTest {
  @Test
  fun albums_groupTracksByServerAlbumIdAndSortByName() {
    val index =
      AutoCatalogIndex(
        listOf(
          song(id = "track-2", name = "Second", album = "Beta", albumId = "album-b"),
          song(id = "track-1", name = "First", album = "Alpha", albumId = "album-a"),
          song(id = "track-3", name = "Third", album = "Alpha", albumId = "album-a"),
        ),
      )

    assertEquals(listOf("Alpha", "Beta"), index.albums.map { it.name })
    assertEquals(
      listOf("track-1", "track-3"),
      index.albums
        .first()
        .songs
        .map { it.id },
    )
  }

  @Test
  fun artists_alignNamesWithTheirCorrespondingServerIds() {
    val duet =
      song(
        id = "duet",
        name = "Duet",
        artists = listOf("Alice", "Bob"),
        artistIds = listOf("artist-a", "artist-b"),
      )

    val index = AutoCatalogIndex(listOf(duet))

    assertEquals(listOf("Alice", "Bob"), index.artists.map { it.name })
    assertEquals(listOf("artist-a", "artist-b"), index.artists.map { it.id })
    assertEquals(
      listOf("duet"),
      index.artists
        .last()
        .songs
        .map { it.id },
    )
  }

  @Test
  fun missingServerIdsProduceStableDistinctCatalogIds() {
    val first = song(id = "one", name = "One", album = "Shared", artists = listOf("Alice"))
    val second = song(id = "two", name = "Two", album = "Shared", artists = listOf("Bob"))
    val firstIndex = AutoCatalogIndex(listOf(first, second))
    val secondIndex = AutoCatalogIndex(listOf(second, first))

    assertEquals(firstIndex.albums.map { it.id }.sorted(), secondIndex.albums.map { it.id }.sorted())
    assertNotEquals(firstIndex.albums[0].id, firstIndex.albums[1].id)
    assertEquals("one", AutoMediaIds.songValue(AutoMediaIds.song("one")))
  }

  private fun song(
    id: String,
    name: String,
    album: String? = null,
    albumId: String? = null,
    artists: List<String> = listOf("Artist"),
    artistIds: List<String>? = null,
  ): Song =
    Song(
      id = id,
      name = name,
      itemType = "Audio",
      album = album,
      albumId = albumId,
      artists = artists,
      artistIds = artistIds,
      path = null,
      duration = 180.0,
      albumArtUrl = null,
      year = null,
      playCount = null,
      isFavorite = null,
      discNumber = null,
      trackNumber = null,
      container = "flac",
      bitRate = null,
      sampleRate = null,
      codec = null,
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
