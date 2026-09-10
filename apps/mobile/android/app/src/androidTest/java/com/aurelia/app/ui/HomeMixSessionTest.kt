package com.aurelia.app.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HomeMixSessionTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val id = UUID.randomUUID().toString()
  private val dataDir = File(context.cacheDir, "home-mixes-$id")
  private val server = MockWebServer()
  private val mixRequests = AtomicInteger()
  private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
  private val models = mutableListOf<ViewModel>()
  private var mixGate: CountDownLatch? = null
  private var emptyMixes = false
  private lateinit var session: SessionStore
  private lateinit var player: PlayerController

  @Before
  fun setUp() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val isMix = request.path.orEmpty().contains("InstantMix")
          val path = request.path.orEmpty().substringBefore('?')
          requestCounts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
          val version = if (isMix) mixRequests.incrementAndGet() else 0
          if (isMix) mixGate?.await(10, TimeUnit.SECONDS)
          val songs =
            """
            {"Items":[{"Id":"song","Name":"Track $version","Type":"Audio",
            "Artists":["Artist"],"ArtistItems":[{"Id":"artist","Name":"Artist"}],
            "Album":"Album","AlbumId":"album","RunTimeTicks":1800000000,
            "UserData":{"PlayCount":5,"IsFavorite":true}}],"TotalRecordCount":1}
            """.trimIndent()
          val body =
            when {
              isMix && emptyMixes -> """{"Items":[],"TotalRecordCount":0}"""
              path == "/Items/artist" ->
                """{"Id":"artist","Name":"Artist","Type":"MusicArtist","Overview":"Biography"}"""
              path.endsWith("/Lyrics") -> """{"Lyrics":[{"Text":"A line of lyrics","Start":0}]}"""
              request.path.orEmpty().contains("IncludeItemTypes=Playlist") ->
                """
                {"Items":[{"Id":"playlist","Name":"Playlist","Type":"Playlist","ChildCount":1}],
                "TotalRecordCount":1}
                """.trimIndent()
              else -> songs
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
    instrumentation.runOnMainSync { player = PlayerController(context) }
  }

  @After
  fun tearDown() {
    instrumentation.runOnMainSync {
      models.forEach { it.viewModelScope.cancel() }
      player.release()
    }
    session.library.clear()
    session.reads.clear()
    mixGate?.countDown()
    server.close()
    context.deleteSharedPreferences("aurelia_session-$id")
    dataDir.deleteRecursively()
  }

  @Test
  fun mixesStayLoadingUntilTheResponseIncludingAnEmptyResponse() {
    emptyMixes = true
    mixGate = CountDownLatch(1)
    val home = createHome()
    runBlocking { withTimeout(5_000) { while (mixRequests.get() < 2) delay(10) } }
    assertTrue(home.state.value.isLoadingMixes)
    assertTrue(
      home.state.value.mixes
        .isEmpty(),
    )
    mixGate?.countDown()
    runBlocking { withTimeout(5_000) { home.state.first { !it.isLoadingMixes } } }
    assertTrue(
      home.state.value.mixes
        .isEmpty(),
    )
    val recreated = createHome()
    runBlocking { withTimeout(5_000) { recreated.state.first { !it.isLoadingMixes } } }
    assertTrue(
      recreated.state.value.mixes
        .isEmpty(),
    )
    assertEquals(2, mixRequests.get())
  }

  @Test
  fun recreatingHomeInTheSameProcessKeepsTheOriginalMixes() {
    val first = createHome()
    val initial = awaitMixes(first)
    val requests = mixRequests.get()
    instrumentation.runOnMainSync { first.viewModelScope.cancel() }
    val recreated = createHome()
    val restored = awaitMixes(recreated)
    assertEquals("Recreating Home must not fetch new mixes", requests, mixRequests.get())
    assertEquals(initial, restored)
  }

  @Test
  fun recreationDuringLoadingJoinsTheOriginalMixRequests() {
    val gate = CountDownLatch(1)
    mixGate = gate
    val first = createHome()
    runBlocking { withTimeout(5_000) { while (mixRequests.get() < 2) delay(10) } }
    assertTrue(first.state.value.isLoadingMixes)
    instrumentation.runOnMainSync { first.viewModelScope.cancel() }
    val recreated = createHome()
    gate.countDown()
    assertTrue(awaitMixes(recreated).isNotEmpty())
    assertFalse(recreated.state.value.isLoadingMixes)
    assertEquals(2, mixRequests.get())
  }

  @Test
  fun recreatedLibraryAndPlaylistScreensReuseTheirLoadedData() {
    val home = createHome()
    awaitMixes(home)
    val first = createPlaylists()
    awaitPlaylists(first)
    first.loadPlaylistDetail("playlist", "Playlist")
    awaitPlaylistItems(first)
    val before = counts()
    instrumentation.runOnMainSync {
      home.viewModelScope.cancel()
      first.viewModelScope.cancel()
    }
    awaitMixes(createHome())
    val recreated = createPlaylists()
    awaitPlaylists(recreated)
    recreated.loadPlaylistDetail("playlist", "Playlist")
    awaitPlaylistItems(recreated)
    assertEquals(before, counts())
    recreated.ensureLoaded(force = true)
    awaitPlaylists(recreated)
    assertEquals(before.getValue("/Users/user/Items") + 1, counts().getValue("/Users/user/Items"))
  }

  @Test
  fun artistAndLyricsReadsSurviveNewScreenConsumers() =
    runBlocking {
      val credentials = checkNotNull(session.snapshot())
      val artist = session.reads.artist(credentials, "artist")
      val lyrics = session.reads.lyrics(credentials, "song", "Artist", "Track")
      assertEquals("Artist", artist.name)
      assertTrue(lyrics.plain.isNotEmpty() || lyrics.synced.isNotEmpty())
      val before = counts()
      assertEquals(artist, session.reads.artist(credentials.copy(), "artist"))
      assertEquals(lyrics, session.reads.lyrics(credentials.copy(), "song", "Artist", "Track"))
      assertEquals(before, counts())
    }

  private fun counts() = requestCounts.mapValues { it.value.get() }

  private fun createPlaylists(): PlaylistViewModel {
    lateinit var model: PlaylistViewModel
    instrumentation.runOnMainSync {
      model = PlaylistViewModel(session, player)
      models.add(model)
      model.ensureLoaded()
    }
    return model
  }

  private fun awaitPlaylists(model: PlaylistViewModel) =
    runBlocking {
      withTimeout(5_000) { model.state.first { !it.isLoading } }.also {
        assertEquals(null, it.error)
        assertTrue(it.playlists.isNotEmpty())
      }
    }

  private fun awaitPlaylistItems(model: PlaylistViewModel) =
    runBlocking {
      withTimeout(5_000) { model.detailState.first { !it.isLoading } }.also {
        assertEquals(null, it.error)
        assertTrue(it.songs.isNotEmpty())
      }
    }

  private fun createHome(): HomeViewModel {
    lateinit var model: HomeViewModel
    instrumentation.runOnMainSync {
      model = HomeViewModel(session, player)
      models.add(model)
      model.ensureLoaded()
    }
    return model
  }

  private fun awaitMixes(model: HomeViewModel): List<HomeMix> =
    runBlocking {
      withTimeout(5_000) { model.state.first { it.mixes.isNotEmpty() }.mixes }
    }
}
