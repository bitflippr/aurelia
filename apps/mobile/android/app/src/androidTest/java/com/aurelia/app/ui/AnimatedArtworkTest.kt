@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AnimatedArtwork
import com.aurelia.app.ui.components.ArtworkCloudColors
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AnimatedArtworkTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val server = MockWebServer()
  private val metadataRequests = AtomicInteger()
  private val mediaRequests = AtomicInteger()
  private val unauthorizedRequests = AtomicInteger()
  private val id = UUID.randomUUID().toString()
  private val dataDir = File(context.cacheDir, "artwork-test-$id")
  private lateinit var session: SessionStore
  private var missing = false
  private var brokenMedia = false

  @Before
  fun setUp() {
    val clip =
      instrumentation.context.assets
        .open("artwork-loop.mp4")
        .use { it.readBytes() }
    val checksum = MessageDigest.getInstance("SHA-256").digest(clip).joinToString("") { "%02x".format(it) }
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.getHeader("Authorization") != "MediaBrowser Token=\"artwork-token\"") {
            unauthorizedRequests.incrementAndGet()
            return MockResponse().setResponseCode(401)
          }
          if (request.path == "/jellyfin/AnimatedArtwork/Items/album") {
            metadataRequests.incrementAndGet()
            if (missing) return MockResponse().setResponseCode(404)
            return MockResponse().setHeader("Content-Type", "application/json").setBody(
              """
              {"ApiVersion":1,"AlbumId":"album","Square":{
                "Url":"/jellyfin/AnimatedArtwork/Items/album/square","ContentType":"video/mp4",
                "Sha256":"$checksum","Bytes":${clip.size},"Width":64,"Height":64,"Duration":1
              },"Tall":null}
              """.trimIndent(),
            )
          }
          mediaRequests.incrementAndGet()
          return if (brokenMedia) {
            MockResponse().setResponseCode(404)
          } else {
            MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(clip))
          }
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
    session.save(server.url("/jellyfin").toString(), "artwork-user", "artwork-token", "Listener")
  }

  @After
  fun tearDown() {
    session.reads.clear()
    server.shutdown()
    dataDir.deleteRecursively()
  }

  @Test
  fun rendersMovingFramesAndReusesReadsAfterRemountAndBackground() {
    val visible = mutableStateOf(false)
    val sampledColors = mutableSetOf<ArtworkCloudColors>()
    compose.setContent {
      Box(Modifier.size(160.dp).background(Color.Magenta)) {
        AnimatedArtwork(
          "album",
          session,
          Modifier.fillMaxSize(),
          enabled = visible.value,
          onColors = { sampledColors.add(it) },
        )
      }
    }
    compose.waitForIdle()
    assertEquals(0, metadataRequests.get())
    compose.runOnIdle { visible.value = true }
    val frames = mutableSetOf<Int>()
    compose.waitUntil(20_000) {
      frameSignature()?.let { frames.add(it) }
      frames.size >= 3
    }
    val loopFrames = mutableSetOf<Int>()
    val started = SystemClock.uptimeMillis()
    compose.waitUntil(10_000) {
      if (SystemClock.uptimeMillis() - started > 1_500) frameSignature()?.let { loopFrames.add(it) }
      loopFrames.size >= 3 // Still moving after the one-second clip has ended.
    }
    compose.waitUntil(10_000) { sampledColors.isNotEmpty() }
    assertEquals(1, metadataRequests.get())
    assertTrue(mediaRequests.get() > 0)
    assertEquals(0, unauthorizedRequests.get())
    val initialMediaRequests = mediaRequests.get()

    compose.runOnIdle { visible.value = false }
    compose.waitUntil(5_000) { !hasTexture() }
    compose.runOnIdle { visible.value = true }
    compose.waitUntil(10_000) { frameSignature() != null }
    assertEquals(1, metadataRequests.get())
    assertEquals(initialMediaRequests, mediaRequests.get())

    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    assertTrue(hasTexture())
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitUntil(10_000) { frameSignature() != null }
    assertEquals(1, metadataRequests.get())
    assertEquals(initialMediaRequests, mediaRequests.get())
  }

  @Test
  fun missingArtworkKeepsStaticCoverAndDoesNotRetryOnRemount() {
    missing = true
    val visible = mutableStateOf(true)
    compose.setContent {
      Box(Modifier.size(160.dp).background(Color.Magenta)) {
        if (visible.value) AnimatedArtwork("album", session, Modifier.fillMaxSize())
      }
    }
    compose.waitUntil(10_000) { metadataRequests.get() == 1 }
    compose.waitForIdle()
    assertTrue(!hasTexture())
    compose.runOnIdle { visible.value = false }
    compose.runOnIdle { visible.value = true }
    compose.waitForIdle()
    assertEquals(1, metadataRequests.get())
    assertEquals(0, mediaRequests.get())
  }

  @Test
  fun failedMediaReleasesVideoAndLeavesStaticCover() {
    brokenMedia = true
    compose.setContent {
      Box(Modifier.size(160.dp).background(Color.Magenta)) {
        AnimatedArtwork("album", session, Modifier.fillMaxSize())
      }
    }
    compose.waitUntil(10_000) { mediaRequests.get() > 0 }
    compose.waitUntil(10_000) { !hasTexture() }
    assertEquals(0, unauthorizedRequests.get())
  }

  @Test
  fun staticCoverCloudsMoveWithoutLoadingVideoAndFreezeWhenDisabled() {
    val animated = mutableStateOf(true)
    compose.mainClock.autoAdvance = false
    compose.setContent {
      Box(Modifier.size(160.dp, 240.dp).testTag("animated-backdrop")) {
        PlayerBackdrop(
          null,
          session,
          animateArtwork = animated.value,
          frameColors = ArtworkCloudColors(Color.Red, Color.Blue, Color.Green),
        )
      }
    }
    compose.mainClock.advanceTimeBy(100)
    val initial = backdropSignature()
    compose.mainClock.advanceTimeBy(8_000)
    assertTrue(initial != backdropSignature())
    animated.value = false
    compose.mainClock.advanceTimeBy(100)
    val stopped = backdropSignature()
    compose.mainClock.advanceTimeBy(8_000)
    assertEquals(stopped, backdropSignature())
    assertTrue(!hasTexture())
    assertEquals(0, metadataRequests.get())
  }

  @Test
  fun lateVideoFadesIntoCoverAndBlurredBackdropUsingOneSurface() {
    val item = mutableStateOf<String?>(null)
    val expansion = mutableStateOf(0f)
    var video: PlayerArtworkVideoState? = null
    compose.mainClock.autoAdvance = false
    compose.setContent {
      val morph = rememberPlayerMorph(expansion.value, null, 0f, false, false, item.value, session)
      video = morph.video
      CompositionLocalProvider(LocalPlayerMorph provides morph) {
        Box(Modifier.size(160.dp, 240.dp)) {
          PlayerBackdrop(null, session, Modifier.fillMaxSize().testTag("animated-backdrop"), animateArtwork = false)
          PlayerArtworkVideoSource()
          Box(Modifier.size(80.dp).testTag("revealing-cover").background(Color.Magenta)) {
            PlayerArtwork(Modifier.fillMaxSize())
          }
        }
      }
    }
    compose.mainClock.advanceTimeBy(32)
    val staticBackground = backdropSignature()

    fun coverSignature() =
      compose
        .onNodeWithTag("revealing-cover")
        .captureToImage()
        .toPixelMap()
        .buffer
        .contentHashCode()
    val staticCover = coverSignature()
    item.value = "album"
    compose.mainClock.advanceTimeBy(32)
    compose.waitUntil(20_000) {
      compose.mainClock.advanceTimeByFrame()
      video?.ready == true
    }
    compose.mainClock.advanceTimeBy(32)
    compose.mainClock.advanceTimeBy(192)
    val halfwayBackground = backdropSignature()
    val halfwayCover = coverSignature()
    compose.mainClock.advanceTimeBy(600)
    val videoBackground = backdropSignature()
    val videoCover = coverSignature()
    assertTrue(
      "Backdrop must blend through an intermediate frame",
      halfwayBackground != staticBackground && halfwayBackground != videoBackground,
    )
    assertTrue(
      "Cover must blend through an intermediate frame",
      halfwayCover != staticCover && halfwayCover != videoCover,
    )
    assertVideoPaused()
    val heldBackground = backdropSignature()
    SystemClock.sleep(300)
    assertEquals("Paused backdrop must hold the cover frame", heldBackground, backdropSignature())
    instrumentation.runOnMainSync { assertEquals(1, allTextures(compose.activity.window.decorView).size) }
    expansion.value = 1f
    compose.mainClock.advanceTimeBy(32)
    val frames = mutableSetOf<Int>()
    compose.waitUntil(10_000) {
      frames.add(backdropSignature())
      frames.size >= 3
    }
    assertEquals(1, metadataRequests.get())
  }

  @Test
  fun sharedTransitionChangesVideoSpeedAndRenderSizeWithoutReplacingSurface() {
    val expansion = mutableStateOf(0f)
    val mounted = mutableStateOf(true)
    compose.setContent {
      if (!mounted.value) return@setContent
      val morph = rememberPlayerMorph(expansion.value, null, 0f, false, false, "album", session)
      val state = remember { SeekableTransitionState(false) }
      val transition = rememberTransition(state)
      LaunchedEffect(expansion.value) {
        when (expansion.value) {
          0f -> state.snapTo(false)
          1f -> state.snapTo(true)
          else -> {
            val seek = playerTransitionSeek(expansion.value, state.currentState)
            state.seekTo(seek.fraction, seek.targetExpanded)
          }
        }
      }
      CompositionLocalProvider(LocalPlayerMorph provides morph) {
        Box(Modifier.size(300.dp, 500.dp).testTag("video-sheet")) {
          PlayerArtworkVideoSource()
          SharedTransitionLayout {
            transition.AnimatedContent(
              modifier = Modifier.fillMaxSize(),
              transitionSpec = { playerContentTransform() },
            ) { expanded ->
              Box(Modifier.fillMaxSize()) {
                PlayerArtwork(
                  Modifier
                    .align(if (expanded) Alignment.TopCenter else Alignment.BottomStart)
                    .playerSharedElement(this@SharedTransitionLayout, this@AnimatedContent, "player-test-artwork")
                    .size(if (expanded) 240.dp else 44.dp),
                )
              }
            }
          }
        }
      }
    }
    compose.waitUntil(20_000) { frameSignature() != null }
    var original: TextureView? = null
    instrumentation.runOnMainSync { original = findTexture(compose.activity.window.decorView) }
    assertRenderSize(44)
    assertVideoPaused()
    assertMiniPlayerShowsVideoFrame()
    compose.runOnIdle { expansion.value = 1f }
    compose.waitForIdle()
    assertRenderSize(240)
    val fullSpeedFrames = distinctVideoFrames(1_600)
    assertTrue("Full-speed cover must be moving", fullSpeedFrames >= 8)
    for (progress in listOf(0.75f, 0.5f, 0.2f, 0f, 0.2f, 0.7f, 1f)) {
      compose.runOnIdle { expansion.value = progress }
      compose.waitForIdle()
      instrumentation.runOnMainSync {
        val textures = allTextures(compose.activity.window.decorView)
        assertEquals("One video surface throughout shared transition at $progress", 1, textures.size)
        assertTrue("Keep the original surface at $progress", original === textures.single())
      }
      when (progress) {
        0f -> {
          assertRenderSize(44)
          assertVideoPaused()
          assertMiniPlayerShowsVideoFrame()
        }
        0.2f -> {
          val slowFrames = distinctVideoFrames(1_600)
          assertTrue("Cover must keep moving slowly near mini", slowFrames >= 2)
          assertTrue(
            "Video really slows down: slow=$slowFrames, full=$fullSpeedFrames",
            slowFrames < fullSpeedFrames / 2,
          )
        }
        1f -> {
          assertRenderSize(240)
          assertSharedVideoMoves("resumed")
        }
        else -> assertSharedVideoMoves("drag-$progress")
      }
    }
    compose.runOnIdle { mounted.value = false }
    compose.waitUntil(5_000) { !hasTexture() }
    assertEquals(1, metadataRequests.get())
  }

  private fun assertRenderSize(displayedDp: Int) {
    instrumentation.runOnMainSync {
      val target = (displayedDp * context.resources.displayMetrics.density).toInt()
      val surface = checkNotNull(findTexture(compose.activity.window.decorView))
      assertTrue(
        "Video render surface ${surface.width}px must follow the ${target}px cover",
        surface.width in target until target + 32,
      )
    }
  }

  private fun distinctVideoFrames(durationMs: Long): Int {
    val frames = mutableSetOf<Int>()
    val started = SystemClock.uptimeMillis()
    compose.waitUntil(5_000) {
      frameSignature()?.let { frames.add(it) }
      SystemClock.uptimeMillis() - started >= durationMs
    }
    return frames.size
  }

  private fun assertVideoPaused(): Int {
    val started = SystemClock.uptimeMillis()
    var held: Int? = null
    compose.waitUntil(conditionDescription = "The video holds a paused frame", timeoutMillis = 5_000) {
      // Allow frames already queued at the moment of pausing to reach the surface.
      if (SystemClock.uptimeMillis() - started < 250) return@waitUntil false
      val frame = frameSignature() ?: return@waitUntil false
      if (held == null) held = frame
      assertEquals("Paused artwork must retain the same frame", held, frame)
      SystemClock.uptimeMillis() - started >= 900
    }
    return checkNotNull(held)
  }

  private fun assertMiniPlayerShowsVideoFrame() {
    val pixels = compose.onNodeWithTag("video-sheet").captureToImage().toPixelMap()
    var coloredPixels = 0
    for (y in 0 until pixels.height) {
      for (x in 0 until pixels.width) {
        val color = pixels[x, y]
        if (color.red > 0.75f && color.green < 0.25f && color.blue < 0.25f) coloredPixels++
      }
    }
    assertTrue("Mini-player must draw the video, not its gray static placeholder", coloredPixels > 20)
  }

  private fun assertSharedVideoMoves(label: String) {
    val frames = mutableSetOf<Int>()
    compose.waitUntil(conditionDescription = "Live video frames at $label", timeoutMillis = 5_000) {
      val frame = compose.onNodeWithTag("video-sheet").captureToImage()
      frames.add(frame.toPixelMap().buffer.contentHashCode())
      frames.size >= 3
    }
  }

  private fun allTextures(view: View): List<TextureView> =
    when (view) {
      is TextureView -> listOf(view)
      is ViewGroup -> (0 until view.childCount).flatMap { allTextures(view.getChildAt(it)) }
      else -> emptyList()
    }

  private fun backdropSignature(): Int =
    compose
      .onNodeWithTag("animated-backdrop")
      .captureToImage()
      .toPixelMap()
      .buffer
      .contentHashCode()

  @Test
  fun systemMotionSettingPausesAndResumesArtworkWithoutRefetching() {
    val key = Settings.Global.ANIMATOR_DURATION_SCALE
    val resolver = context.contentResolver
    val original = Settings.Global.getString(resolver, key)
    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
    try {
      Settings.Global.putFloat(resolver, key, 1f)
      compose.setContent {
        Box(Modifier.size(160.dp).background(Color.Magenta)) {
          AnimatedArtwork("album", session, Modifier.fillMaxSize())
        }
      }
      compose.waitUntil(20_000) { frameSignature() != null }
      Settings.Global.putFloat(resolver, key, 0f)
      assertVideoPaused()
      assertTrue(hasTexture())
      Settings.Global.putFloat(resolver, key, 1f)
      val resumed = mutableSetOf<Int>()
      compose.waitUntil(10_000) {
        frameSignature()?.let { resumed.add(it) }
        resumed.size >= 3
      }
      assertEquals(1, metadataRequests.get())
    } finally {
      Settings.Global.putString(resolver, key, original)
      instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
  }

  private fun hasTexture(): Boolean {
    var exists = false
    instrumentation.runOnMainSync { exists = findTexture(compose.activity.window.decorView) != null }
    return exists
  }

  private fun frameSignature(): Int? {
    var bitmap: Bitmap? = null
    instrumentation.runOnMainSync { bitmap = findTexture(compose.activity.window.decorView)?.getBitmap(32, 32) }
    return bitmap?.let {
      val pixels = IntArray(it.width * it.height)
      it.getPixels(pixels, 0, it.width, 0, 0, it.width, it.height)
      it.recycle()
      if (pixels.all { pixel ->
          pixel == 0 || pixel == android.graphics.Color.BLACK
        }
      ) {
        null
      } else {
        pixels.contentHashCode()
      }
    }
  }

  private fun findTexture(view: View): TextureView? {
    if (view is TextureView) return view
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) {
        findTexture(view.getChildAt(index))?.let { return it }
      }
    }
    return null
  }
}
