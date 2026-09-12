package com.aurelia.app.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SearchScreenUiTest {
  @get:Rule val compose = createComposeRule()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val id = UUID.randomUUID().toString()
  private val dataDir = File(context.cacheDir, "search-$id")
  private val server = MockWebServer()
  private val firstTitle = AtomicReference("Cloak n Dagger")
  private lateinit var session: SessionStore
  private lateinit var player: PlayerController
  private lateinit var library: LibraryViewModel
  private lateinit var playlists: PlaylistViewModel
  private var openedAlbum: Screen.AlbumDetail? = null
  private var openedArtist: Screen.ArtistDetail? = null
  private var openedPlayer = false

  @Before
  fun setUp() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.requestUrl!!.encodedPath.contains("/Images/") ||
            request.requestUrl!!.encodedPath.startsWith("/Audio/")
          ) {
            return MockResponse().setResponseCode(404)
          }
          val body =
            if (request.requestUrl!!.queryParameter("IncludeItemTypes") == "Playlist") {
              """{"Items":[],"TotalRecordCount":0}"""
            } else {
              """
              {"Items":[
              {"Id":"collab-song","Name":"${firstTitle.get()}","Type":"Audio",
              "Artists":["glaive","ericdoa"],"ArtistItems":[{"Id":"glaive-id","Name":"glaive"},{"Id":"ericdoa-id","Name":"ericdoa"}],
              "Album":"Then I'll Be Happy","AlbumId":"collab-album","RunTimeTicks":1800000000},
              {"Id":"joga-song","Name":"Jóga","Type":"Audio","Artists":["Björk"],"ArtistItems":[{"Id":"bjork-id","Name":"Björk"}],
              "Album":"Homogenic","AlbumId":"homogenic-album","RunTimeTicks":1800000000},
              {"Id":"title-song","Name":"glaive","Type":"Audio","Artists":["Other"],"ArtistItems":[{"Id":"other-id","Name":"Other"}],
              "Album":"Unrelated","AlbumId":"unrelated-album","RunTimeTicks":1800000000}],"TotalRecordCount":3}
              """.trimIndent()
            }
          return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
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
    }
    runBlocking {
      withTimeout(10_000) {
        library.state.first { it.songs.size == 3 && !it.isLoading }
        player.awaitConnection()
      }
    }
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
  fun typoSearchNavigatesToTheCorrectArtist() {
    showSearch()
    compose.onNodeWithText("Artists").performClick()
    search("glavie")
    compose.onNodeWithText("glaive").performClick()
    assertEquals(Screen.ArtistDetail("glaive-id", "glaive"), openedArtist)
    search("ericdoa")
    compose.onNode(hasText("ericdoa") and !hasSetTextAction()).performClick()
    assertEquals(Screen.ArtistDetail("ericdoa-id", "ericdoa"), openedArtist)
  }

  @Test
  fun reorderedWordsFindAnAlbumWithItsCreditsAndDestination() {
    showSearch()
    compose.onNodeWithText("Albums").performClick()
    search("happy then")
    compose.onNodeWithText("glaive, ericdoa").assertIsDisplayed()
    compose.onNodeWithText("Then I'll Be Happy").performClick()
    assertEquals(Screen.AlbumDetail("collab-album", "Then I'll Be Happy"), openedAlbum)
  }

  @Test
  fun accentInsensitiveSongsStillPlayAndExposeTheirContextMenu() {
    showSearch()
    compose.onNodeWithText("Songs").performClick()
    search("joga")
    compose.onNodeWithText("Jóga").performClick()
    assertTrue(openedPlayer)
    compose.onNodeWithContentDescription("More options for Jóga").assertDoesNotExist()
    compose.onNodeWithText("Jóga").performTouchInput { longClick() }
    compose.onNodeWithText("Add to Queue").assertIsDisplayed()
  }

  @Test
  fun clearResetsBothTheFieldAndTheActualSearchThenAcceptsANewQuery() {
    showSearch()
    search("joga")
    compose.onNodeWithContentDescription("Clear search").performClick()
    assertEquals("", library.searchState.value.query)
    assertTrue(
      library.searchState.value.results
        .isEmpty(),
    )
    compose.onNodeWithText("Search your library").assertIsDisplayed()
    compose.onNodeWithText("Jóga").assertDoesNotExist()
    search("happy")
    assertTrue(
      library.searchState.value.results
        .any { it is SearchResult.Album && it.id == "collab-album" },
    )
  }

  @Test
  fun latestQueryWinsAndWhitespaceDoesNotLeaveOldResults() {
    showSearch()
    compose.onNodeWithTag("search-query").performTextReplacement("glavie")
    search("bjork")
    assertTrue(
      library.searchState.value.results
        .any { it is SearchResult.Artist && it.id == "bjork-id" },
    )
    assertTrue(
      library.searchState.value.results
        .none { it is SearchResult.Artist && it.id == "glaive-id" },
    )
    search("   ")
    assertTrue(
      library.searchState.value.results
        .isEmpty(),
    )
    compose.onNodeWithText("Search your library").assertIsDisplayed()
  }

  @Test
  fun filtersShowAnEmptyStateWithoutHidingMatchesInOtherCategories() {
    showSearch()
    search("cloak")
    compose.onNodeWithText("Artists").performClick()
    compose.onNodeWithText("No artists found").assertIsDisplayed()
    compose.onNodeWithText("All").performClick()
    compose.onNodeWithText("Cloak n Dagger").assertIsDisplayed()
    search("g")
    compose.onNodeWithText("Keep typing").assertIsDisplayed()
    compose.onNodeWithText("Cloak n Dagger").assertDoesNotExist()
  }

  @Test
  fun recreatingSearchPreservesTheQueryAndSelectedCategory() {
    val restoration = StateRestorationTester(compose)
    showSearch(restoration)
    compose.onNodeWithText("Albums").performClick()
    search("happy")
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag("search-query").assertTextContains("happy")
    compose.onNodeWithText("Then I'll Be Happy").assertIsDisplayed()
    compose.onNodeWithText("Cloak n Dagger").assertDoesNotExist()
  }

  @Test
  fun refreshingTheLibraryReplacesTheSearchIndexForTheCurrentQuery() {
    showSearch()
    search("brand new")
    assertTrue(
      library.searchState.value.results
        .isEmpty(),
    )
    firstTitle.set("Brand New Track")
    instrumentation.runOnMainSync { library.ensureLoaded(force = true) }
    runBlocking {
      withTimeout(10_000) {
        library.searchState.first { state ->
          state.results.any {
            it is SearchResult.SongResult &&
              it.song.name == "Brand New Track"
          }
        }
      }
    }
    compose.onNodeWithText("Brand New Track").assertIsDisplayed()
  }

  private fun search(query: String) {
    compose.onNodeWithTag("search-query").performTextReplacement(query)
    runBlocking {
      withTimeout(10_000) { library.searchState.first { it.query == query && !it.isSearching } }
    }
    compose.waitForIdle()
  }

  private fun showSearch(restoration: StateRestorationTester? = null) {
    val content: @androidx.compose.runtime.Composable () -> Unit = {
      AureliaTheme(darkTheme = true, useDynamicColor = false) {
        SearchScreen(
          libraryViewModel = library,
          sessionStore = session,
          playerController = player,
          playlistViewModel = playlists,
          onOpenPlayer = { openedPlayer = true },
          onNavigateToAlbum = { openedAlbum = it },
          onNavigateToArtist = { openedArtist = it },
        )
      }
    }
    if (restoration != null) restoration.setContent(content) else compose.setContent(content)
  }
}
