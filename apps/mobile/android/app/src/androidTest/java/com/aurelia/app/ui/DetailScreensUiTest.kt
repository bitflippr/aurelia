package com.aurelia.app.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.AureliaTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DetailScreensUiTest {
  @get:Rule
  val compose = createComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val id = UUID.randomUUID().toString()
  private val dataDir = File(context.cacheDir, "detail-screens-$id")
  private val server = MockWebServer()
  private val addedIds = AtomicReference<String?>()
  private val collaboration = AtomicBoolean(false)
  private lateinit var session: SessionStore
  private lateinit var player: PlayerController
  private lateinit var library: LibraryViewModel
  private lateinit var playlists: PlaylistViewModel
  private var openedAlbum: Screen.AlbumDetail? = null
  private var openedArtist: Screen.ArtistDetail? = null
  private var openedPlayerAt: Int? = null

  @Before
  fun setUp() {
    val songs =
      (8 downTo 1).joinToString(",") { index ->
        val disc = if (index <= 4) 1 else 2
        val track = (index - 1) % 4 + 1
        """
        {"Id":"song-$index","Name":"Disc $disc Track $track","Type":"Audio",
        "Artists":["Sora Vale"],"ArtistItems":[{"Id":"artist","Name":"Sora Vale"}],
        "Album":"Blue Hour","AlbumId":"album","ParentIndexNumber":$disc,"IndexNumber":$track,
        "RunTimeTicks":1800000000}
        """.trimIndent()
      }
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.requestUrl!!.encodedPath
          if (path == "/Playlists/playlist/Items" && request.method == "POST") {
            addedIds.set(request.requestUrl!!.queryParameter("Ids"))
            return MockResponse().setResponseCode(204)
          }
          val body =
            when {
              path.contains("/Images/") || path.startsWith("/Audio/") -> return MockResponse().setResponseCode(404)
              path == "/Items/artist" ->
                """{"Id":"artist","Name":"Sora Vale","Type":"MusicArtist","Overview":"Artist biography."}"""
              request.requestUrl!!.queryParameter("IncludeItemTypes") == "Playlist" ->
                """{"Items":[{"Id":"playlist","Name":"Night drive","Type":"Playlist","ChildCount":0}],"TotalRecordCount":1}"""
              else -> """{"Items":[$songs],"TotalRecordCount":8}"""
            }
          val responseBody =
            if (collaboration.get()) {
              body.replace(
                "\"Artists\":[\"Sora Vale\"],\"ArtistItems\":[{\"Id\":\"artist\",\"Name\":\"Sora Vale\"}]",
                "\"Artists\":[\"glaive\",\"ericdoa\"],\"ArtistItems\":[{\"Id\":\"glaive-id\",\"Name\":\"glaive\"},{\"Id\":\"ericdoa-id\",\"Name\":\"ericdoa\"}]",
              )
            } else {
              body
            }
          return MockResponse().setHeader("Content-Type", "application/json").setBody(responseBody)
        }
      }
    server.start()
    val isolatedContext =
      object : ContextWrapper(context) {
        override fun getSharedPreferences(
          name: String,
          mode: Int,
        ): SharedPreferences = context.getSharedPreferences("$name-$id", mode)
      }
    session = SessionStore(isolatedContext)
    dataDir.mkdirs()
    session.setAppDataDir(dataDir.absolutePath)
    session.save(server.url("/").toString().trimEnd('/'), "user", "test-token", "Listener")
    instrumentation.runOnMainSync {
      player = PlayerController(context)
      library = LibraryViewModel(session, player)
      playlists = PlaylistViewModel(session, player)
      library.ensureLoaded()
      playlists.ensureLoaded()
    }
    runBlocking {
      withTimeout(10_000) {
        library.state.first { it.songs.size == 8 && !it.isLoading }
        playlists.state.first { it.playlists.isNotEmpty() && !it.isLoading }
        player.awaitConnection()
      }
    }
    instrumentation.runOnMainSync { player.stop() }
  }

  @After
  fun tearDown() {
    instrumentation.runOnMainSync {
      library.viewModelScope.cancel()
      playlists.viewModelScope.cancel()
      player.stop()
      player.release()
    }
    session.library.clear()
    session.reads.clear()
    server.close()
    context.deleteSharedPreferences("aurelia_session-$id")
    dataDir.deleteRecursively()
  }

  @Test
  fun artistShelfNavigatesAndExpandedSongsUseOneOrderedQueue() {
    showArtist()
    compose.onNodeWithText("Sora Vale").assertIsDisplayed()
    compose.onNodeWithText("In your library").assertDoesNotExist()
    compose.onNodeWithTag("artist-detail-list").performScrollToNode(hasContentDescription("Open album Blue Hour"))
    compose.onNodeWithContentDescription("Open album Blue Hour").performClick()
    assertEquals(Screen.AlbumDetail("album", "Blue Hour"), openedAlbum)
    compose.onNodeWithTag("artist-detail-list").performScrollToNode(hasContentDescription("View all Songs"))
    compose.onNodeWithContentDescription("View all Songs").performClick()
    compose.onNodeWithTag("artist-detail-list").performScrollToNode(hasText("Disc 2 Track 1"))
    compose.onNodeWithText("Disc 2 Track 1").performClick()
    assertEquals(4, openedPlayerAt)
    assertOrderedQueue()
    compose.onNodeWithContentDescription("More options for Disc 2 Track 1").performClick()
    compose.onNodeWithText("Add to Queue").assertIsDisplayed()
  }

  @Test
  fun albumKeepsDiscOrderAndArtistNavigation() {
    showAlbum()
    compose.onNodeWithText("Album · 8 songs · 24 min").assertIsDisplayed()
    compose.onNodeWithText("Sora Vale").performClick()
    assertEquals(Screen.ArtistDetail("artist", "Sora Vale"), openedArtist)
    compose.onNodeWithTag("album-detail-list").performScrollToNode(hasText("Disc 2"))
    compose.onNodeWithText("Disc 2").assertIsDisplayed()
    compose.onNodeWithTag("album-detail-list").performScrollToNode(hasText("Disc 2 Track 1"))
    compose.onNodeWithText("Disc 2 Track 1").performClick()
    assertEquals(4, openedPlayerAt)
    assertOrderedQueue()
    compose.onNodeWithContentDescription("More options for Disc 2 Track 1").performClick()
    compose.onNodeWithText("Play Next").assertIsDisplayed()
  }

  @Test
  fun collectionPlaylistActionAddsEveryTrackExactlyOnce() {
    showAlbum()
    compose.onNodeWithContentDescription("More actions").performClick()
    compose.onNodeWithText("Add to playlist").performClick()
    compose.onNodeWithText("Night drive").performClick()
    compose.waitUntil(5_000) { addedIds.get() != null }
    assertEquals((1..8).joinToString(",") { "song-$it" }, addedIds.get())
  }

  @Test
  fun emptyAlbumDisablesPlaybackAndShowsAnEmptyState() {
    showAlbum(albumId = "missing")
    compose.onNodeWithText("Play").assertIsNotEnabled()
    compose.onNodeWithText("Shuffle").assertIsNotEnabled()
    compose.onNodeWithContentDescription("More actions").assertIsNotEnabled()
    compose.onNodeWithText("No songs in this album").performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText("Disc 1").assertCountEquals(0)
  }

  @Test
  fun collaborationLetsTheUserChooseTheSecondArtist() {
    loadCollaboration()
    showAlbum()
    compose.onNodeWithText("glaive, ericdoa").performClick()
    compose.onNodeWithText("Choose artist").assertIsDisplayed()
    assertNull(openedArtist)
    compose.onNodeWithText("ericdoa").performClick()
    assertEquals(Screen.ArtistDetail("ericdoa-id", "ericdoa"), openedArtist)
    compose.onNodeWithText("Choose artist").assertDoesNotExist()
  }

  @Test
  fun collaborationCanBeDismissedAndThenOpenTheFirstArtist() {
    loadCollaboration()
    showAlbum()
    compose.onNodeWithText("glaive, ericdoa").performClick()
    compose.onNodeWithText("Choose artist").assertIsDisplayed()
    pressBack()
    compose.onNodeWithText("Choose artist").assertDoesNotExist()
    assertNull(openedArtist)
    compose.onNodeWithText("glaive, ericdoa").performClick()
    compose.onNodeWithText("glaive").performClick()
    assertEquals(Screen.ArtistDetail("glaive-id", "glaive"), openedArtist)
  }

  private fun loadCollaboration() {
    collaboration.set(true)
    session.library.clear()
    session.reads.clear()
    instrumentation.runOnMainSync { library.ensureLoaded(force = true) }
    runBlocking {
      withTimeout(10_000) {
        library.state.first { it.songs.firstOrNull()?.artists == listOf("glaive", "ericdoa") && !it.isLoading }
      }
    }
  }

  private fun showArtist() {
    compose.setContent {
      AureliaTheme(darkTheme = true, useDynamicColor = false) {
        ArtistDetailScreen(
          libraryViewModel = library,
          artistId = "artist",
          artistName = "Sora Vale",
          sessionStore = session,
          playerController = player,
          playlistViewModel = playlists,
          onBack = {},
          onOpenPlayer = ::capturePlayback,
          onNavigateToAlbum = { openedAlbum = it },
        )
      }
    }
  }

  private fun showAlbum(albumId: String = "album") {
    compose.setContent {
      AureliaTheme(darkTheme = true, useDynamicColor = false) {
        AlbumDetailScreen(
          libraryViewModel = library,
          albumId = albumId,
          albumName = "Blue Hour",
          sessionStore = session,
          playerController = player,
          playlistViewModel = playlists,
          onBack = {},
          onOpenPlayer = ::capturePlayback,
          onNavigateToArtist = { openedArtist = it },
        )
      }
    }
  }

  private fun capturePlayback() {
    openedPlayerAt = player.getCurrentQueueIndex()
    player.pause()
  }

  private fun assertOrderedQueue() {
    compose.runOnIdle {
      assertEquals((1..8).map { "song-$it" }, player.getQueue().map { it.id })
    }
  }
}
