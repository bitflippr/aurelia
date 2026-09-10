package com.aurelia.app.player

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.aurelia_core.buildAndroidStreamUrl
import uniffi.aurelia_core.buildMobileStreamUrl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackServiceTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun authenticatedStreamsKeepPlayingAfterActivityStopsAndControllerReconnects() {
    val rejected = AtomicInteger()
    val requested = AtomicInteger()
    val audio = silentWav()
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requested.incrementAndGet()
          // Jellyfin's current authorization accepts ApiKey; api_key requires legacy auth.
          if (request.requestUrl?.queryParameter("ApiKey") != "playback-token") {
            rejected.incrementAndGet()
            return MockResponse().setResponseCode(401)
          }
          return MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))
        }
      }
    server.start()
    var controller: MediaController? = null
    try {
      controller = connect()
      val player = controller
      for (container in listOf("m4a", "flac")) {
        val uri = buildMobileStreamUrl(server.url("/jellyfin").toString(), "playback-token", "song", container)
        instrumentation.runOnMainSync {
          player.setMediaItem(
            MediaItem
              .Builder()
              .setMediaId("song")
              .setUri(uri)
              .setMediaMetadata(MediaMetadata.Builder().setTitle("Background playback test").build())
              .build(),
          )
          player.prepare()
          player.play()
        }
        compose.waitUntil(10_000) {
          var complete = false
          instrumentation.runOnMainSync { complete = player.isPlaying || player.playerError != null }
          complete
        }
        instrumentation.runOnMainSync {
          assertTrue(
            "$container should play; rejected HTTP requests: ${rejected.get()}, error: ${player.playerError}",
            player.isPlaying,
          )
        }
      }
      compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
      var position = 0L
      instrumentation.runOnMainSync { position = player.currentPosition }
      SystemClock.sleep(600)
      instrumentation.runOnMainSync {
        assertTrue("Audio must continue after leaving the activity", player.isPlaying)
        assertTrue("Background audio position must advance", player.currentPosition > position)
        player.release()
      }
      controller = null
      SystemClock.sleep(300)
      controller = connect()
      val reconnected = controller
      instrumentation.runOnMainSync {
        assertTrue("Service must retain playback after UI controller disconnects", reconnected.isPlaying)
        assertEquals("song", reconnected.currentMediaItem?.mediaId)
      }
      val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
      assertTrue(
        "System media controls require a foreground media-session notification",
        notifications.any {
          it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
            it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        },
      )
      assertTrue(requested.get() >= 2)
      assertEquals(0, rejected.get())
    } finally {
      instrumentation.runOnMainSync {
        controller?.run {
          stop()
          clearMediaItems()
          release()
        }
      }
      server.shutdown()
    }
  }

  @Test
  fun eac3StereoStreamCanSeekForwardAndBackwardThroughController() {
    val audio =
      instrumentation.context.assets
        .open("streaming-stereo.flac")
        .use { it.readBytes() }
    val requestedOffsets = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requestedOffsets.add(request.requestUrl?.queryParameter("startTimeTicks")?.toLong() ?: 0L)
          return MockResponse().setHeader("Content-Type", "audio/flac").setChunkedBody(Buffer().write(audio), 4096)
        }
      }
    server.start()
    var controller: MediaController? = null
    try {
      controller = connect()
      val player = controller
      val uri = buildAndroidStreamUrl(server.url("/jellyfin").toString(), "token", "eac3-song", "m4a", "eac3")
      instrumentation.runOnMainSync {
        player.setMediaItem(
          MediaItem
            .Builder()
            .setMediaId("eac3-song")
            .setUri(uri)
            .setMediaMetadata(
              MediaMetadata
                .Builder()
                .setTitle("EAC3 seek test")
                .setDurationMs(120000)
                .setExtras(
                  Bundle().apply {
                    putString(AureliaMediaItems.EXTRA_CONTAINER, "m4a")
                    putString(AureliaMediaItems.EXTRA_CODEC, "eac3")
                  },
                ).build(),
            ).build(),
        )
        player.prepare()
        player.play()
      }
      compose.waitUntil(10000) {
        var ready = false
        instrumentation.runOnMainSync { ready = player.isPlaying }
        ready
      }
      for (target in listOf(60000L, 15000L)) {
        instrumentation.runOnMainSync {
          assertTrue(
            "EAC3 must expose seeking after FLAC prepares",
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
          )
          player.seekTo(target)
        }
        compose.waitUntil(10000) { requestedOffsets.contains(target * 10000) }
        compose.waitUntil(10000) {
          var ready = false
          instrumentation.runOnMainSync {
            ready = player.isPlaying && player.currentPosition in target..(target + 5000)
          }
          ready
        }
      }
    } finally {
      instrumentation.runOnMainSync {
        controller?.run {
          stop()
          clearMediaItems()
          release()
        }
      }
      server.shutdown()
    }
  }

  private fun connect(): MediaController {
    lateinit var future: ListenableFuture<MediaController>
    instrumentation.runOnMainSync {
      future =
        MediaController
          .Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
          ).buildAsync()
    }
    return future.get(10, TimeUnit.SECONDS)
  }

  private fun silentWav(): ByteArray {
    val bytes = 16_000 * 2 * 30
    return ByteBuffer
      .allocate(44 + bytes)
      .order(ByteOrder.LITTLE_ENDIAN)
      .apply {
        put("RIFF".toByteArray())
        putInt(36 + bytes)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(16_000)
        putInt(32_000)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(bytes)
      }.array()
  }
}
