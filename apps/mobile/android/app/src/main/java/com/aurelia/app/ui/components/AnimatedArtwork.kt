package com.aurelia.app.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uniffi.aurelia_core.AnimatedArtworkVariant
import java.io.File
import java.security.MessageDigest

/** Overlay an optional silent video. Pausing retains its frame and playback position. */
@Composable
@OptIn(UnstableApi::class)
internal fun AnimatedArtwork(
  itemId: String?,
  sessionStore: SessionStore,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  playbackSpeed: Float = 1f,
  onColors: ((ArtworkCloudColors) -> Unit)? = null,
  onReady: ((Boolean) -> Unit)? = null,
) {
  if (!enabled) return
  val context = LocalContext.current
  val motionEnabled = rememberArtworkMotionEnabled()
  val session = sessionStore.snapshot()
  val shouldLoad = !itemId.isNullOrBlank() && session != null
  var artwork by remember(itemId, session, shouldLoad) { mutableStateOf<AnimatedArtworkVariant?>(null) }
  LaunchedEffect(itemId, session, shouldLoad) {
    if (shouldLoad) {
      artwork =
        try {
          sessionStore.reads.animatedArtwork(session, itemId)?.square
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null // Optional plugin, offline server, unauthorized request, or malformed metadata.
        }
    }
  }
  val variant = artwork
  if (shouldLoad && variant != null) {
    val cache by produceState<SimpleCache?>(null) {
      value =
        withContext(Dispatchers.IO) {
          try {
            ArtworkVideoCache.get(context.applicationContext)
          } catch (_: Exception) {
            null
          }
        }
    }
    cache?.let {
      key(variant, session.token) {
        ArtworkVideo(
          variant,
          session.token,
          artworkCacheKey(session.serverUrl, session.userId, variant.sha256),
          it,
          modifier,
          if (motionEnabled) playbackSpeed.coerceIn(0f, 1f) else 0f,
          onColors,
          onReady,
        )
      }
    }
  }
}

@Composable
internal fun rememberArtworkMotionEnabled(): Boolean {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  var motionEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
  DisposableEffect(lifecycle, context) {
    val observer =
      LifecycleEventObserver { _, _ ->
        active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        motionEnabled = ValueAnimator.areAnimatorsEnabled()
      }
    val motionListener = ValueAnimator.DurationScaleChangeListener { scale -> motionEnabled = scale > 0f }
    lifecycle.addObserver(observer)
    ValueAnimator.registerDurationScaleChangeListener(motionListener)
    onDispose {
      lifecycle.removeObserver(observer)
      ValueAnimator.unregisterDurationScaleChangeListener(motionListener)
    }
  }
  return active && motionEnabled
}

/** Account-scoped content keys avoid persisting bearer tokens in the media cache index. */
internal fun artworkCacheKey(
  serverUrl: String,
  userId: String,
  checksum: String,
): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest("$serverUrl\n$userId\n$checksum".toByteArray())
    .joinToString("") { "%02x".format(it) }

@OptIn(UnstableApi::class)
private object ArtworkVideoCache {
  private var cache: SimpleCache? = null

  @Synchronized
  fun get(context: Context): SimpleCache =
    cache ?: SimpleCache(
      File(context.cacheDir, "animated-artwork"),
      LeastRecentlyUsedCacheEvictor(128L * 1024 * 1024),
      StandaloneDatabaseProvider(context),
    ).also { cache = it }

  // A redirected media response must never forward the Jellyfin session header elsewhere.
  val httpClient: OkHttpClient =
    OkHttpClient
      .Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
}

@Composable
@OptIn(UnstableApi::class)
private fun ArtworkVideo(
  variant: AnimatedArtworkVariant,
  token: String,
  cacheKey: String,
  cache: SimpleCache,
  modifier: Modifier,
  playbackSpeed: Float,
  onColors: ((ArtworkCloudColors) -> Unit)?,
  onReady: ((Boolean) -> Unit)?,
) {
  val context = LocalContext.current
  var firstFrame by remember(variant, token) { mutableStateOf(false) }
  var failed by remember(variant, token) { mutableStateOf(false) }
  val currentOnReady by rememberUpdatedState(onReady)
  DisposableEffect(firstFrame, failed) {
    currentOnReady?.invoke(firstFrame && !failed)
    onDispose { currentOnReady?.invoke(false) }
  }
  if (failed) return
  val player =
    remember(context, variant, token, cacheKey, cache) {
      val upstream =
        OkHttpDataSource.Factory(ArtworkVideoCache.httpClient).setDefaultRequestProperties(
          mapOf("Authorization" to "MediaBrowser Token=\"$token\""),
        )
      val source =
        CacheDataSource
          .Factory()
          .setCache(cache)
          .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
          .setUpstreamDataSourceFactory(upstream)
      ExoPlayer.Builder(context).build().apply {
        volume = 0f
        trackSelectionParameters =
          trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        repeatMode = Player.REPEAT_MODE_ONE
        setMediaSource(
          ProgressiveMediaSource.Factory(source).createMediaSource(
            MediaItem
              .Builder()
              .setUri(variant.url)
              .setCustomCacheKey(cacheKey)
              .build(),
          ),
        )
      }
    }
  DisposableEffect(player) {
    val listener =
      object : Player.Listener {
        override fun onRenderedFirstFrame() {
          firstFrame = true
        }

        override fun onPlayerError(error: PlaybackException) {
          failed = true
        }
      }
    player.addListener(listener)
    player.prepare()
    onDispose {
      player.removeListener(listener)
      player.release()
    }
  }
  val playing = playbackSpeed > 0f
  LaunchedEffect(player, playbackSpeed) {
    if (playing) {
      // Media3 requires a positive rate; an exact zero is a real pause, never a seek.
      player.setPlaybackSpeed(playbackSpeed.coerceAtLeast(0.01f))
      player.play()
    } else {
      player.pause()
    }
  }
  var texture by remember(player) { mutableStateOf<TextureView?>(null) }
  val currentOnColors by rememberUpdatedState(onColors)
  LaunchedEffect(player, firstFrame, onColors != null, playing) {
    if (!firstFrame || onColors == null) return@LaunchedEffect
    while (isActive) {
      // Sample the existing cover decoder; the backdrop never plays its own video.
      val bitmap = texture?.takeIf { it.isAvailable }?.getBitmap(24, 24)
      if (bitmap != null) {
        val colors =
          withContext(Dispatchers.Default) {
            try {
              artworkCloudColors(bitmap)
            } finally {
              bitmap.recycle()
            }
          }
        currentOnColors?.invoke(colors)
      }
      if (!playing) break
      delay(2_000)
    }
  }
  val reveal by animateFloatAsState(
    targetValue = if (firstFrame) 1f else 0f,
    animationSpec = tween(450),
    label = "artwork-video-reveal",
  )
  val currentVariant by rememberUpdatedState(variant)
  AndroidView(
    factory = {
      TextureView(it).apply {
        isOpaque = false
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
          cropArtwork(this, currentVariant)
        }
      }
    },
    modifier = modifier.graphicsLayer { alpha = reveal },
    update = {
      if (texture !== it) {
        texture = it
        cropArtwork(it, variant)
        player.setVideoTextureView(it)
      }
    },
    onRelease = {
      texture = null
      player.clearVideoTextureView(it)
    },
  )
}

private fun cropArtwork(
  view: TextureView,
  variant: AnimatedArtworkVariant,
) {
  if (view.width == 0 || view.height == 0) return
  val videoAspect = variant.width.toFloat() / variant.height.toFloat()
  val viewAspect = view.width.toFloat() / view.height.toFloat()
  val scaleX = (videoAspect / viewAspect).coerceAtLeast(1f)
  val scaleY = (viewAspect / videoAspect).coerceAtLeast(1f)
  view.setTransform(Matrix().apply { setScale(scaleX, scaleY, view.width / 2f, view.height / 2f) })
}
