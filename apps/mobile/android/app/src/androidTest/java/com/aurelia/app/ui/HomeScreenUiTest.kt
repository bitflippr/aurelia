package com.aurelia.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.aurelia_core.Song

class HomeScreenUiTest {
  @get:Rule
  val compose = createComposeRule()

  private val album = AlbumItem("album", "An album", "An artist", null, 4)
  private val songs = (1..4).map { song("$it") }

  @Test
  fun emptyLibraryOffersRefreshWithoutDeadPlaybackControls() {
    var refreshed = false
    show(HomeState(), onRetry = { refreshed = true })
    compose.onNodeWithText("Your music starts here").assertIsDisplayed()
    compose.onNodeWithText("Shuffle library").assertDoesNotExist()
    compose.onNodeWithText("Refresh").performClick()
    assertTrue(refreshed)
  }

  @Test
  fun libraryWithoutMixesHasWorkingFeaturedShuffleAndIndependentSurprise() {
    var shuffles = 0
    var surprises = 0
    show(
      HomeState(recentlyAddedAlbums = listOf(album)),
      onShuffle = { shuffles++ },
      onSurprise = { surprises++ },
    )
    compose.onNodeWithText("Your library, on shuffle").performClick()
    compose.onNodeWithText("Shuffle library").performClick()
    compose.onNodeWithText("Surprise me").performClick()
    assertEquals(2, shuffles)
    assertEquals(1, surprises)
  }

  @Test
  fun featuredMixLeadsActionsAndOtherMixesRemainPlayable() {
    val first = HomeMix("mix1", "First artist", null, songs.take(2))
    val second = HomeMix("mix2", "Second artist", null, songs.takeLast(2))
    var played: HomeMix? = null
    show(HomeState(mixes = listOf(first, second)), onMix = { played = it })
    val mix = compose.onNodeWithText("First artist")
    val shuffle = compose.onNodeWithText("Shuffle library")
    assertTrue(mix.getUnclippedBoundsInRoot().bottom < shuffle.getUnclippedBoundsInRoot().top)
    mix.performClick()
    assertEquals(first, played)
    mix.performTouchInput { swipeLeft() }
    compose.onNodeWithText("Second artist").performClick()
    assertEquals(second, played)
  }

  @Test
  fun rediscoveryExpandsAndPlaysTheDeduplicatedQueueAndAlbumOpens() {
    var queued: List<Song>? = null
    var selected: Song? = null
    var opened: AlbumItem? = null
    show(
      HomeState(
        forgottenFavorites = songs.take(2),
        quickPicks = songs,
        recentlyAddedAlbums = listOf(album),
      ),
      onSong = { song, queue ->
        selected = song
        queued = queue
      },
      onAlbum = { opened = it },
    )
    compose.onNodeWithText("An album").performScrollTo().performClick()
    assertEquals(album, opened)
    compose.onNodeWithText("See all").performScrollTo().performClick()
    compose.onNodeWithText("Song 4").performScrollTo().performClick()
    assertEquals(songs.last(), selected)
    assertEquals(songs, queued)
    compose.onNodeWithText("Show less").performScrollTo().performClick()
    compose.onNodeWithText("Song 4").assertDoesNotExist()
  }

  private fun show(
    state: HomeState,
    onRetry: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onSurprise: () -> Unit = {},
    onMix: (HomeMix) -> Unit = {},
    onSong: (Song, List<Song>) -> Unit = { _, _ -> },
    onAlbum: (AlbumItem) -> Unit = {},
  ) {
    compose.setContent {
      MaterialTheme {
        HomeContent(
          state = state,
          username = "Listener",
          hasPlayerBar = true,
          onRetry = onRetry,
          onOpenSettings = {},
          onShuffleAll = onShuffle,
          onSurpriseMe = onSurprise,
          onPlayMix = onMix,
          onPlaySong = onSong,
          onSongLongClick = {},
          onOpenAlbum = onAlbum,
          onPlayGenre = {},
          songMenu = {},
        )
      }
    }
  }

  private fun song(id: String) =
    Song(
      id = id,
      name = "Song $id",
      itemType = "Audio",
      album = "An album",
      albumId = "album",
      artists = listOf("An artist"),
      artistIds = listOf("artist"),
      path = null,
      duration = 180.0,
      albumArtUrl = null,
      year = null,
      playCount = 1,
      isFavorite = true,
      discNumber = null,
      trackNumber = null,
      container = null,
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
