package com.aurelia.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.aurelia_core.Song

class HomeMixPresentationTest {
  @Test
  fun artistSeedUsesTheMixGenreAndItsActualArtistsAndCovers() {
    val songs =
      listOf(
        song("one", "First", artists = listOf("ericdoa"), genres = listOf("hyperpop"), art = "eric-cover"),
        song(
          "two",
          "Second",
          artists = listOf("glaive"),
          artistIds = listOf("glaive-id"),
          genres = listOf("Hyperpop"),
          art = "glaive-cover",
        ),
      )
    val mix = createHomeMix("glaive-id", songs)
    assertEquals("Hyperpop mix", mix.title)
    assertEquals(listOf("ericdoa", "glaive"), mix.artistNames)
    assertEquals(listOf("eric-cover", "glaive-cover"), mix.artworkUrls)
    assertEquals(songs, mix.songs)
  }

  @Test
  fun missingMixMetadataCanUseTheMatchingLibrarySongWithoutChangingTheQueue() {
    val queue = listOf(song("one", "First"))
    val library = listOf(song("one", "First", genres = listOf("R&B")))
    assertEquals("R&B mix", createHomeMix("one", queue, library).title)
    assertEquals(queue, createHomeMix("one", queue, library).songs)
  }

  @Test
  fun missingOrMixedGenresDoNotInventASpecificGenre() {
    assertEquals("Discovery mix", createHomeMix("seed", listOf(song("one", "First"))).title)
    val songs =
      listOf("rock", "pop", "jazz").mapIndexed { index, genre ->
        song("$index", "Track", genres = listOf(genre))
      }
    assertEquals("Discovery mix", createHomeMix("seed", songs).title)
  }

  @Test
  fun repeatedTagsWithinOneTrackCannotOutvoteTheRestOfTheMix() {
    val songs =
      listOf(
        song("one", "First", genres = listOf(" rock ", "Rock", "ROCK")),
        song("two", "Second", genres = listOf("hyperpop")),
        song("three", "Third", genres = listOf("hyperpop")),
      )
    assertEquals("Hyperpop mix", createHomeMix("seed", songs).title)
  }

  private fun song(
    id: String,
    name: String,
    album: String? = null,
    albumId: String? = null,
    artists: List<String> = listOf("Artist"),
    artistIds: List<String>? = null,
    genres: List<String>? = null,
    art: String? = null,
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
      albumArtUrl = art,
      year = null,
      playCount = null,
      isFavorite = null,
      discNumber = null,
      trackNumber = null,
      container = "flac",
      bitRate = null,
      sampleRate = null,
      codec = null,
      genres = genres,
      premiereDate = null,
      datePlayed = null,
      dateCreated = null,
      dateModified = null,
      albumArtists = null,
      lyrics = null,
      imageTags = null,
    )
}
