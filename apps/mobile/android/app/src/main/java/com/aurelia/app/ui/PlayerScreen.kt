@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import android.content.pm.ApplicationInfo
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.aurelia.app.audio.AudioManager
import com.aurelia.app.audio.VisualizerStyle
import com.aurelia.app.data.model.Lyrics
import com.aurelia.app.player.RepeatMode
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AnimatedPlayPauseIcon
import com.aurelia.app.ui.components.ArtworkCloudBackdrop
import com.aurelia.app.ui.components.ArtworkCloudColors
import com.aurelia.app.ui.components.AudioVisualizer
import com.aurelia.app.ui.components.VisualizerFrameMetrics
import com.aurelia.app.ui.components.WavyMusicSlider
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.ui.theme.rememberNowPlayingStyle
import com.aurelia.app.utils.formatDuration
import com.aurelia.app.utils.optimizedArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.Song
import kotlin.math.abs

private enum class ControlButton { NONE, PREVIOUS, PLAY_PAUSE, NEXT }

private const val ALBUM_COLOR_CACHE_LIMIT = 64
private val albumArtColorCache = LinkedHashMap<String, AlbumArtColors>(ALBUM_COLOR_CACHE_LIMIT)

/**
 * Data class holding dynamically extracted colors from album art.
 */
internal data class AlbumArtColors(
  val primary: Color,
  val secondary: Color,
  val accent: Color,
  val onPrimary: Color,
  val isLight: Boolean,
)

private fun getCachedAlbumArtColors(albumArtUrl: String?): AlbumArtColors? {
  if (albumArtUrl.isNullOrBlank()) return null
  return synchronized(albumArtColorCache) { albumArtColorCache[albumArtUrl] }
}

private fun putCachedAlbumArtColors(
  albumArtUrl: String,
  colors: AlbumArtColors,
) {
  synchronized(albumArtColorCache) {
    if (!albumArtColorCache.containsKey(albumArtUrl) && albumArtColorCache.size >= ALBUM_COLOR_CACHE_LIMIT) {
      val oldestKey = albumArtColorCache.entries.firstOrNull()?.key
      if (oldestKey != null) albumArtColorCache.remove(oldestKey)
    }
    albumArtColorCache[albumArtUrl] = colors
  }
}

/**
 * Extracts dominant colors from album art URL using Palette API.
 * Falls back to caller-provided seed colors (mini-player tint) if extraction fails.
 */
@Composable
private fun rememberAlbumArtColors(
  albumArtUrl: String?,
  fallbackColors: AlbumArtColors,
): AlbumArtColors {
  val context = LocalContext.current
  val imageLoader = remember(context) { ImageLoader(context) }
  var colors by remember(albumArtUrl, fallbackColors) {
    mutableStateOf(getCachedAlbumArtColors(albumArtUrl) ?: fallbackColors)
  }

  LaunchedEffect(albumArtUrl, fallbackColors) {
    if (albumArtUrl.isNullOrBlank()) {
      colors = fallbackColors
      return@LaunchedEffect
    }

    val cached = getCachedAlbumArtColors(albumArtUrl)
    if (cached != null) {
      colors = cached
      return@LaunchedEffect
    }

    try {
      // Use smaller size for palette extraction - 200px is plenty for color analysis
      val request =
        ImageRequest
          .Builder(context)
          .data(optimizedArtworkUrl(albumArtUrl, 200))
          .allowHardware(false)
          .size(200)
          .build()

      val result =
        withContext(Dispatchers.IO) {
          (imageLoader.execute(request) as? SuccessResult)?.drawable
        }
      val bitmap = (result as? BitmapDrawable)?.bitmap

      if (bitmap != null) {
        colors =
          withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).generate()

            // Use dominant swatch as the base color (most representative color)
            val dominant = palette.dominantSwatch

            // For primary, prefer muted or dark muted for better aesthetics
            // Fall back to dominant if those aren't available
            val primaryColor =
              palette.mutedSwatch?.rgb?.let { Color(it) }
                ?: palette.darkMutedSwatch?.rgb?.let { Color(it) }
                ?: dominant?.rgb?.let { Color(it) }
                ?: fallbackColors.primary

            // For secondary/accent, use vibrant but ensure it's not too neon
            val secondaryColor =
              palette.lightMutedSwatch?.rgb?.let { Color(it) }
                ?: palette.vibrantSwatch?.rgb?.let { Color(it) }
                ?: dominant?.rgb?.let { Color(it) }
                ?: fallbackColors.secondary

            // Determine if the primary color is light or dark for text contrast
            val hsl = FloatArray(3)
            android.graphics.Color.colorToHSV(primaryColor.toArgb(), hsl)
            val isLight = hsl[2] > 0.6f

            AlbumArtColors(
              primary = primaryColor,
              secondary = secondaryColor,
              accent = primaryColor,
              onPrimary = if (isLight) Color.Black else Color.White,
              isLight = isLight,
            )
          }
        putCachedAlbumArtColors(albumArtUrl, colors)
      } else {
        colors = fallbackColors
      }
    } catch (e: Exception) {
      colors = fallbackColors
    }
  }

  return colors
}

@Composable
internal fun rememberPlayerAlbumColors(albumArtUrl: String?): AlbumArtColors {
  val colors = MaterialTheme.colorScheme
  val miniPlayerSeedColors =
    remember(colors.primary, colors.primaryContainer, colors.onPrimary) {
      val luminance = (0.299 * colors.primary.red + 0.587 * colors.primary.green + 0.114 * colors.primary.blue)
      AlbumArtColors(
        primary = colors.primary,
        secondary = colors.primaryContainer,
        accent = colors.primary,
        onPrimary = if (luminance > 0.5f) Color.Black else Color.White,
        isLight = luminance > 0.6f,
      )
    }

  return rememberAlbumArtColors(albumArtUrl, miniPlayerSeedColors)
}

@Composable
internal fun PlayerBackdrop(
  albumArtUrl: String?,
  sessionStore: SessionStore,
  modifier: Modifier = Modifier,
  frameColors: ArtworkCloudColors? = null,
  animateArtwork: Boolean = true,
) {
  val context = LocalContext.current
  val debuggable = remember(context) { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
  PlayerBackdrop(
    albumArtUrl = albumArtUrl,
    frameColors = frameColors,
    animateArtwork = animateArtwork,
    disableImageLayer =
      remember(sessionStore, debuggable) {
        debuggable &&
          sessionStore.getDebugDisablePlayerBackdropImageLayer()
      },
    disableTransitions =
      remember(sessionStore, debuggable) {
        debuggable &&
          sessionStore.getDebugDisablePlayerTransitions()
      },
    modifier = modifier,
  )
}

/** Static artwork uses color clouds; ready video shares a small blurred GPU layer. */
@Composable
private fun PlayerBackdrop(
  albumArtUrl: String?,
  animateArtwork: Boolean,
  disableImageLayer: Boolean,
  disableTransitions: Boolean,
  modifier: Modifier = Modifier,
  frameColors: ArtworkCloudColors? = null,
) {
  val video = LocalPlayerMorph.current?.video
  val videoReady = !disableImageLayer && video?.ready == true
  val reveal by animateFloatAsState(
    targetValue = if (videoReady) 1f else 0f,
    animationSpec = tween(if (disableTransitions) 0 else 450),
    label = "artwork-backdrop-reveal",
  )
  val colors = rememberPlayerAlbumColors(albumArtUrl)
  Box(modifier.fillMaxSize()) {
    if (reveal < 1f) {
      ArtworkCloudBackdrop(
        colors = frameColors ?: ArtworkCloudColors(colors.primary, colors.secondary, colors.accent),
        modifier = Modifier.fillMaxSize(),
        animated = animateArtwork && !disableTransitions && !videoReady,
        showClouds = !disableImageLayer,
      )
    }
    if (video != null && reveal > 0f) {
      PlayerVideoBackdrop(video, Modifier.fillMaxSize().alpha(reveal))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
  viewModel: PlayerViewModel,
  sessionStore: com.aurelia.app.storage.SessionStore,
  onBack: () -> Unit,
  onNavigateToAlbum: (Screen.AlbumDetail) -> Unit = {},
  onNavigateToArtist: (Screen.ArtistDetail) -> Unit = {},
  modifier: Modifier = Modifier,
  isEmbedded: Boolean = false,
  isVisible: Boolean = true,
  sharedTransitionScope: SharedTransitionScope? = null,
  animatedVisibilityScope: AnimatedVisibilityScope? = null,
  sharedContentKey: String? = null,
) {
  val context = LocalContext.current
  val isDebuggable =
    remember(context) {
      (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
  val disableBackdropImageLayer =
    remember(sessionStore, isDebuggable) {
      isDebuggable && sessionStore.getDebugDisablePlayerBackdropImageLayer()
    }
  val disablePlayerTransitions =
    remember(sessionStore, isDebuggable) {
      isDebuggable && sessionStore.getDebugDisablePlayerTransitions()
    }

  val state by viewModel.state.collectAsStateWithLifecycle()
  val playbackPositionState =
    rememberPlaybackPositionState(
      state = state,
      isActive = isVisible,
      targetFps = if (state.showLyrics) 60 else 30,
    )

  var showQueueSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  val colors = MaterialTheme.colorScheme

  // Visualizer state
  val visualizerEnabled = remember(sessionStore) { sessionStore.getVisualizerEnabled() }
  val visualizerStyleName = remember(sessionStore) { sessionStore.getVisualizerStyle() }
  val visualizerStyle =
    remember(visualizerStyleName) {
      try {
        VisualizerStyle.valueOf(visualizerStyleName)
      } catch (_: Exception) {
        VisualizerStyle.BARS
      }
    }

  // Extract dynamic colors from album art
  val albumColors = rememberPlayerAlbumColors(state.albumArtUrl)
  val morph = LocalPlayerMorph.current
  val supportingContentModifier =
    Modifier.playerSupportingContent(
      sharedTransitionScope = sharedTransitionScope,
      expansion = morph?.expansion ?: 1f,
      collapsedContentBottom = morph?.collapsedContentBottom ?: 0f,
    )

  // Animate color transitions
  val colorAnimationSpec = tween<Color>(durationMillis = if (disablePlayerTransitions) 0 else 500)
  val primaryColor by animateColorAsState(
    targetValue = albumColors.primary,
    animationSpec = colorAnimationSpec,
    label = "primaryColor",
  )
  val secondaryColor by animateColorAsState(
    targetValue = albumColors.secondary,
    animationSpec = colorAnimationSpec,
    label = "secondaryColor",
  )
  val accentColor by animateColorAsState(
    targetValue = albumColors.accent,
    animationSpec = colorAnimationSpec,
    label = "accentColor",
  )

  val onPrimaryColor =
    remember(primaryColor) {
      val luminance = (0.299 * primaryColor.red + 0.587 * primaryColor.green + 0.114 * primaryColor.blue)
      if (luminance > 0.5f) Color.Black else Color.White
    }

  Box(modifier = modifier.fillMaxSize()) {
    // The shared sheet owns the backdrop throughout the mini/full morph.
    if (LocalPlayerMorph.current == null) {
      PlayerBackdrop(
        albumArtUrl = state.albumArtUrl,
        animateArtwork = isVisible,
        disableImageLayer = disableBackdropImageLayer,
        disableTransitions = disablePlayerTransitions,
        modifier = Modifier.fillMaxSize(),
      )
    }

    FullscreenVisualizer(
      visualizerEnabled = visualizerEnabled,
      isPlaying = state.isPlaying,
      visualizerStyle = visualizerStyle,
      primaryColor = primaryColor,
      disablePlayerTransitions = disablePlayerTransitions,
      modifier = Modifier.align(Alignment.BottomCenter),
    )

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .statusBarsPadding(),
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .playerSupportingContent(
              sharedTransitionScope = sharedTransitionScope,
              expansion = morph?.expansion ?: 1f,
              collapsedContentBottom = morph?.collapsedContentBottom ?: 0f,
              role = PlayerSupportingRole.Header,
            ).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Close button - transparent bg normally, colored when pressed
        FilledIconButton(
          onClick = onBack,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = primaryColor.copy(alpha = 0.15f),
              contentColor = Color.White,
            ),
          modifier = Modifier.size(42.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Close player",
          )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Lyrics button - colored bg when active, transparent when inactive
        FilledIconButton(
          onClick = { viewModel.toggleLyrics() },
          enabled = state.lyrics != null,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = if (state.showLyrics) primaryColor else primaryColor.copy(alpha = 0.15f),
              contentColor = Color.White,
            ),
          modifier = Modifier.size(42.dp),
          shape = RoundedCornerShape(topStart = 21.dp, bottomStart = 21.dp, topEnd = 4.dp, bottomEnd = 4.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Show lyrics",
            modifier = Modifier.padding(start = 4.dp),
          )
        }

        Spacer(modifier = Modifier.size(4.dp))

        // Queue button - transparent bg
        FilledIconButton(
          onClick = { showQueueSheet = true },
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = primaryColor.copy(alpha = 0.15f),
              contentColor = Color.White,
            ),
          modifier = Modifier.size(42.dp),
          shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 21.dp, bottomEnd = 21.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "Show queue",
            modifier = Modifier.padding(end = 4.dp),
          )
        }
      }

      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp))
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
      ) {
        PlayerArtworkLyricsTransition(
          lyricsAlpha = morph?.lyricsAlpha ?: if (state.showLyrics) 1f else 0f,
          sharedTransitionScope = sharedTransitionScope,
          modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).aspectRatio(1f),
        ) { showLyrics, layerModifier ->
          if (showLyrics) {
            SyncedLyricsPanel(
              lyrics = state.lyrics,
              positionState = playbackPositionState,
              onLineClick = { timeMs -> viewModel.seekTo(timeMs.toLong()) },
              primaryColor = primaryColor,
              modifier = layerModifier.fillMaxSize(),
            )
          } else {
            PlayerArtwork(
              modifier =
                Modifier
                  .playerSharedElement(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    key = playerSharedKey(sharedContentKey, "artwork"),
                  ).then(layerModifier)
                  .fillMaxSize()
                  .clickable(enabled = !state.showLyrics && !state.currentAlbumId.isNullOrBlank()) {
                    state.currentAlbumId?.let { id ->
                      onNavigateToAlbum(Screen.AlbumDetail(id, state.currentAlbumName ?: "Unknown Album"))
                    }
                  },
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.Start,
        ) {
          // Dynamic "Now Playing" title with pulsing weight when playing
          val nowPlayingStyle =
            rememberNowPlayingStyle(
              isPlaying = state.isPlaying,
              baseStyle = MaterialTheme.typography.headlineMedium,
            )
          Text(
            text = state.title.ifBlank { "Nothing playing" },
            style = nowPlayingStyle,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.playerSharedBounds(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                key = playerSharedKey(sharedContentKey, "title"),
              ),
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = state.artist.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier
                .playerSharedBounds(
                  sharedTransitionScope = sharedTransitionScope,
                  animatedVisibilityScope = animatedVisibilityScope,
                  key = playerSharedKey(sharedContentKey, "artist"),
                ).clickable(
                  enabled = !state.currentArtistId.isNullOrBlank(),
                  onClick = {
                    state.currentArtistId?.let { id ->
                      onNavigateToArtist(Screen.ArtistDetail(id, state.artist))
                    }
                  },
                ),
          )
          state.formatInfo?.let { info ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = info,
              modifier = supportingContentModifier,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.55f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isEmbedded) {
          PlaybackProgressSection(
            positionState = playbackPositionState,
            durationMs = state.durationMs,
            isPlaying = state.isPlaying,
            accentColor = primaryColor,
            isVisible = isVisible,
            onSeekTo = { targetPosition -> viewModel.seekTo(targetPosition) },
            modifier =
              Modifier.fillMaxWidth().playerSupportingContent(
                sharedTransitionScope = sharedTransitionScope,
                expansion = morph?.expansion ?: 1f,
                collapsedContentBottom = morph?.collapsedContentBottom ?: 0f,
                role = PlayerSupportingRole.SeekBar,
              ),
          )
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedPlaybackControls(
          isPlaying = state.isPlaying,
          isBuffering = state.isBuffering,
          hasPrevious = state.hasPrevious,
          hasNext = state.hasNext,
          onPrevious = { viewModel.skipPrevious() },
          onPlayPause = { viewModel.togglePlayPause() },
          onNext = { viewModel.skipNext() },
          height = 80.dp,
          primaryColor = primaryColor,
          previousModifier =
            Modifier.playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "previous"),
            ),
          playPauseModifier =
            Modifier.playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "play-pause"),
            ),
          nextModifier =
            Modifier.playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "next"),
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        SecondaryControls(
          isShuffled = state.isShuffled,
          repeatMode = state.repeatMode,
          isFavorite = state.isFavorite,
          isFavoriteLoading = state.isFavoriteLoading,
          onShuffleClick = { viewModel.toggleShuffle() },
          onRepeatClick = { viewModel.cycleRepeatMode() },
          primaryColor = primaryColor,
          onFavoriteClick = { viewModel.toggleFavorite() },
          modifier =
            Modifier.playerSupportingContent(
              sharedTransitionScope = sharedTransitionScope,
              expansion = morph?.expansion ?: 1f,
              collapsedContentBottom = morph?.collapsedContentBottom ?: 0f,
              role = PlayerSupportingRole.Footer,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }

  if (showQueueSheet) {
    ModalBottomSheet(
      onDismissRequest = { showQueueSheet = false },
      sheetState = sheetState,
      containerColor = colors.surface,
      dragHandle = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Spacer(modifier = Modifier.height(8.dp))
          Box(
            modifier =
              Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.onSurfaceVariant.copy(alpha = 0.4f)),
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Queue",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
          )
          if (state.queue.isNotEmpty()) {
            Text(
              text = "${state.queue.size} tracks",
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
        }
      },
    ) {
      QueueContent(
        queue = state.queue,
        currentIndex = state.currentQueueIndex,
        onItemClick = { index ->
          viewModel.playQueueItem(index)
          scope.launch {
            sheetState.hide()
            showQueueSheet = false
          }
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
      )
    }
  }
}

@Composable
private fun rememberPlaybackPositionState(
  state: PlayerState,
  isActive: Boolean,
  targetFps: Int,
): State<Long> {
  val latestState by rememberUpdatedState(state)
  val isAdvancing = state.isPlaying && !state.isBuffering
  return produceState(
    initialValue = state.playbackPositionAt(SystemClock.elapsedRealtime()).toLong(),
    isActive,
    isAdvancing,
    targetFps,
  ) {
    if (!isActive || !isAdvancing) {
      snapshotFlow { latestState }.collect { current ->
        value = current.boundedPlaybackPosition(current.positionMs.toDouble()).toLong()
      }
      return@produceState
    }

    val frameIntervalNanos = (1_000_000_000L / targetFps.coerceAtLeast(1)).coerceAtLeast(1L)
    var previousState = latestState
    var lastRealtime = SystemClock.elapsedRealtime()
    var displayedPosition = previousState.playbackPositionAt(lastRealtime)
    value = displayedPosition.toLong()
    var lastEmitNanos = 0L

    while (currentCoroutineContext().isActive) {
      withFrameNanos { frameNanos ->
        if (lastEmitNanos == 0L || frameNanos - lastEmitNanos >= frameIntervalNanos) {
          val current = latestState
          val now = SystemClock.elapsedRealtime()
          val elapsedMs = (now - lastRealtime).coerceAtLeast(0L)
          val targetPosition = current.playbackPositionAt(now)
          val advance = elapsedMs * current.playbackSpeed.toDouble()
          val predictedPosition = displayedPosition + advance
          val error = targetPosition - predictedPosition
          val discontinuity =
            current.positionDiscontinuitySequence != previousState.positionDiscontinuitySequence ||
              current.currentSongId != previousState.currentSongId

          displayedPosition =
            if (discontinuity ||
              !current.isPlaying ||
              current.isBuffering ||
              current.playbackSpeed != previousState.playbackSpeed ||
              elapsedMs > 250L ||
              abs(error) > 250.0
            ) {
              // Seeks (including small backward seeks) and real clock jumps are immediate.
              targetPosition
            } else {
              // Converge small sample corrections without resetting the clock or adding a fixed delay.
              // Limit negative correction so ordinary clock jitter cannot reverse a word highlight.
              val correctionLimit = advance.coerceAtLeast(0.0) * 0.5
              val correction =
                (error * (elapsedMs / 200.0).coerceAtMost(1.0))
                  .coerceIn(-correctionLimit, correctionLimit)
              predictedPosition + correction
            }
          displayedPosition = current.boundedPlaybackPosition(displayedPosition)
          value = displayedPosition.toLong()
          previousState = current
          lastRealtime = now
          lastEmitNanos = frameNanos
        }
      }
    }
  }
}

private fun PlayerState.playbackPositionAt(realtimeMs: Long): Double {
  val elapsedMs =
    if (isPlaying && !isBuffering && updateTimeMs > 0L) {
      (realtimeMs - updateTimeMs).coerceAtLeast(0L)
    } else {
      0L
    }
  return boundedPlaybackPosition(positionMs + elapsedMs * playbackSpeed.toDouble())
}

private fun PlayerState.boundedPlaybackPosition(position: Double): Double =
  if (durationMs > 0L) position.coerceIn(0.0, durationMs.toDouble()) else position.coerceAtLeast(0.0)

@Composable
private fun SyncedLyricsPanel(
  lyrics: Lyrics?,
  positionState: State<Long>,
  onLineClick: (Int) -> Unit,
  primaryColor: Color,
  modifier: Modifier = Modifier,
) {
  LyricsView(
    lyrics = lyrics,
    positionState = positionState,
    onLineClick = onLineClick,
    primaryColor = primaryColor,
    modifier = modifier.lyricsFadingEdges(),
  )
}

@Composable
private fun PlaybackProgressSection(
  positionState: State<Long>,
  durationMs: Long,
  isPlaying: Boolean,
  accentColor: Color,
  isVisible: Boolean,
  onSeekTo: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentPositionMs = positionState.value
  val progressFraction =
    if (durationMs > 0L) {
      (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }

  Column(modifier = modifier) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = formatDuration(currentPositionMs, clampNegative = true),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.widthIn(min = 40.dp),
      )

      WavyMusicSlider(
        value = progressFraction,
        onValueChange = { newFraction ->
          onSeekTo((newFraction * durationMs).toLong())
        },
        modifier = Modifier.weight(1f),
        trackHeight = 6.dp,
        thumbRadius = 8.dp,
        activeTrackColor = accentColor,
        inactiveTrackColor = accentColor.copy(alpha = 0.2f),
        thumbColor = accentColor,
        waveLength = 30.dp,
        isPlaying = isPlaying,
        isWaveEligible = true,
        animateWave = isVisible,
      )

      Text(
        text = formatDuration(durationMs, clampNegative = true),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.widthIn(min = 40.dp),
      )
    }
  }
}

@Composable
private fun AnimatedPlaybackControls(
  isPlaying: Boolean,
  isBuffering: Boolean,
  hasPrevious: Boolean,
  hasNext: Boolean,
  onPrevious: () -> Unit,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  height: Dp,
  primaryColor: Color,
  modifier: Modifier = Modifier,
  previousModifier: Modifier = Modifier,
  playPauseModifier: Modifier = Modifier,
  nextModifier: Modifier = Modifier,
) {
  var lastClicked by remember { mutableStateOf<ControlButton?>(null) }
  val baseWeight = 1f
  val expandedWeight = 1.2f
  val compressedWeight = 0.7f

  LaunchedEffect(lastClicked) {
    if (lastClicked != null) {
      delay(250L)
      lastClicked = null
    }
  }

  fun weightFor(button: ControlButton): Float =
    when (lastClicked) {
      button -> expandedWeight
      null -> baseWeight
      else -> compressedWeight
    }

  val animationSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .height(height),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val prevWeight by animateFloatAsState(
      targetValue = weightFor(ControlButton.PREVIOUS),
      animationSpec = animationSpec,
      label = "prevWeight",
    )
    val morph = LocalPlayerMorph.current
    val buttonPrimary = morph?.primary ?: primaryColor
    val prevBgAlpha = if (hasPrevious) 0.15f else 0.08f
    val prevIconAlpha = if (hasPrevious) 1f else 0.4f
    Box(
      modifier =
        previousModifier
          .weight(prevWeight)
          .fillMaxHeight()
          .clip(CircleShape)
          .background(morph?.skipBackground(hasPrevious) ?: primaryColor.copy(alpha = prevBgAlpha))
          .then(
            if (hasPrevious) {
              Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                lastClicked = ControlButton.PREVIOUS
                onPrevious()
              }
            } else {
              Modifier
            },
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.SkipPrevious,
        contentDescription = "Previous",
        tint = buttonPrimary.copy(alpha = prevIconAlpha),
        modifier =
          Modifier.size(
            lerp(22.dp, 32.dp, LocalPlayerMorph.current?.expansion ?: 1f),
          ),
      )
    }

    val playWeight by animateFloatAsState(
      targetValue = weightFor(ControlButton.PLAY_PAUSE),
      animationSpec = animationSpec,
      label = "playWeight",
    )
    Box(
      modifier =
        playPauseModifier
          .weight(playWeight)
          .fillMaxHeight()
          .clip(playerPlayButtonShape(isPlaying, isBuffering, fallbackExpansion = 1f))
          .background(buttonPrimary)
          .then(
            if (!isBuffering) {
              Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                lastClicked = ControlButton.PLAY_PAUSE
                onPlayPause()
              }
            } else {
              Modifier
            },
          ),
      contentAlignment = Alignment.Center,
    ) {
      val onPrimaryColor =
        morph?.onPrimary ?: remember(primaryColor) {
          val luminance = (0.299 * primaryColor.red + 0.587 * primaryColor.green + 0.114 * primaryColor.blue)
          if (luminance > 0.5f) Color.Black else Color.White
        }
      if (isBuffering) {
        CircularProgressIndicator(
          modifier =
            Modifier.size(
              lerp(18.dp, 32.dp, LocalPlayerMorph.current?.expansion ?: 1f),
            ),
          color = onPrimaryColor,
          strokeWidth = 3.dp,
        )
      } else {
        AnimatedPlayPauseIcon(
          isPlaying = isPlaying,
          tint = onPrimaryColor,
          modifier =
            Modifier.size(
              lerp(20.dp, 36.dp, LocalPlayerMorph.current?.expansion ?: 1f),
            ),
        )
      }
    }

    val nextWeight by animateFloatAsState(
      targetValue = weightFor(ControlButton.NEXT),
      animationSpec = animationSpec,
      label = "nextWeight",
    )
    val nextBgAlpha = if (hasNext) 0.15f else 0.08f
    val nextIconAlpha = if (hasNext) 1f else 0.4f
    Box(
      modifier =
        nextModifier
          .weight(nextWeight)
          .fillMaxHeight()
          .clip(CircleShape)
          .background(morph?.skipBackground(hasNext) ?: primaryColor.copy(alpha = nextBgAlpha))
          .then(
            if (hasNext) {
              Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                lastClicked = ControlButton.NEXT
                onNext()
              }
            } else {
              Modifier
            },
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.SkipNext,
        contentDescription = "Next",
        tint = buttonPrimary.copy(alpha = nextIconAlpha),
        modifier =
          Modifier.size(
            lerp(22.dp, 32.dp, LocalPlayerMorph.current?.expansion ?: 1f),
          ),
      )
    }
  }
}

@Composable
private fun SecondaryControls(
  isShuffled: Boolean,
  repeatMode: RepeatMode,
  isFavorite: Boolean,
  isFavoriteLoading: Boolean,
  onShuffleClick: () -> Unit,
  onRepeatClick: () -> Unit,
  onFavoriteClick: () -> Unit,
  primaryColor: Color,
  modifier: Modifier = Modifier,
) {
  val onPrimaryColor =
    remember(primaryColor) {
      val luminance = (0.299 * primaryColor.red + 0.587 * primaryColor.green + 0.114 * primaryColor.blue)
      if (luminance > 0.5f) Color.Black else Color.White
    }
  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier =
        Modifier
          .background(
            color = Color.White.copy(alpha = 0.2f),
            shape = RoundedCornerShape(28.dp),
          ).padding(4.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Shuffle button - same style as play/pause
      FilledIconButton(
        onClick = onShuffleClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp),
        colors =
          IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isShuffled) primaryColor else primaryColor.copy(alpha = 0.15f),
            contentColor = if (isShuffled) onPrimaryColor else Color.White,
          ),
      ) {
        Icon(
          imageVector = Icons.Filled.Shuffle,
          contentDescription = if (isShuffled) "Shuffle on" else "Shuffle off",
          modifier =
            Modifier
              .padding(start = 4.dp)
              .size(24.dp),
        )
      }

      Spacer(modifier = Modifier.width(4.dp))

      // Repeat button - same style as play/pause
      FilledIconButton(
        onClick = onRepeatClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(4.dp),
        colors =
          IconButtonDefaults.filledIconButtonColors(
            containerColor = if (repeatMode != RepeatMode.NONE) primaryColor else primaryColor.copy(alpha = 0.15f),
            contentColor = if (repeatMode != RepeatMode.NONE) onPrimaryColor else Color.White,
          ),
      ) {
        val (icon, contentDesc) =
          when (repeatMode) {
            RepeatMode.ONE -> Icons.Filled.RepeatOne to "Repeat one"
            RepeatMode.ALL -> Icons.Filled.Repeat to "Repeat all"
            RepeatMode.NONE -> Icons.Filled.Repeat to "Repeat off"
          }
        Icon(
          imageVector = icon,
          contentDescription = contentDesc,
          modifier = Modifier.size(24.dp),
        )
      }

      Spacer(modifier = Modifier.width(4.dp))

      // Favorite button - same style as play/pause
      FilledIconButton(
        onClick = onFavoriteClick,
        enabled = !isFavoriteLoading,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp),
        colors =
          IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isFavorite) primaryColor else primaryColor.copy(alpha = 0.15f),
            contentColor = if (isFavorite) onPrimaryColor else Color.White,
          ),
      ) {
        val iconAlpha = if (isFavoriteLoading) 0.3f else 1f
        Icon(
          imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
          contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
          modifier =
            Modifier
              .padding(end = 4.dp)
              .size(24.dp)
              .alpha(iconAlpha),
        )
      }
    }
  }
}

@Composable
private fun QueueContent(
  queue: List<Song>,
  currentIndex: Int,
  onItemClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val listState = rememberLazyListState()

  LaunchedEffect(currentIndex) {
    if (currentIndex >= 0 && queue.isNotEmpty()) {
      listState.animateScrollToItem(currentIndex.coerceAtMost(queue.lastIndex))
    }
  }

  if (queue.isEmpty()) {
    Box(
      modifier =
        modifier
          .height(200.dp)
          .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.QueueMusic,
          contentDescription = null,
          modifier = Modifier.size(48.dp),
          tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Queue is empty",
          style = MaterialTheme.typography.bodyLarge,
          color = colors.onSurfaceVariant,
        )
        Text(
          text = "Play some songs to build your queue",
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  } else {
    LazyColumn(
      modifier = modifier,
      state = listState,
      contentPadding = PaddingValues(bottom = 16.dp),
    ) {
      itemsIndexed(queue, key = { idx, item -> "${item.id}_$idx" }) { index, item ->
        QueueItemRow(
          item = item,
          isPlaying = index == currentIndex,
          position = index + 1,
          onClick = { onItemClick(index) },
        )
      }
    }
  }
}

@Composable
private fun QueueItemRow(
  item: Song,
  isPlaying: Boolean,
  position: Int,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val backgroundColor =
    if (isPlaying) colors.primaryContainer.copy(alpha = 0.3f) else colors.surface

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(backgroundColor)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier.size(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (isPlaying) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          verticalAlignment = Alignment.Bottom,
          modifier = Modifier.size(16.dp),
        ) {
          repeat(3) { idx ->
            val height by animateFloatAsState(
              targetValue = if (isPlaying) (0.4f + (idx * 0.2f)) else 0.3f,
              animationSpec = tween(300),
              label = "bar$idx",
            )
            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxHeight(height)
                  .background(colors.primary, RoundedCornerShape(1.dp)),
            )
          }
        }
      } else {
        Text(
          text = position.toString(),
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }
    }

    AlbumArt(
      imageUrl = item.albumArtUrl,
      modifier = Modifier.size(48.dp),
      cornerRadius = 6.dp,
    )

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isPlaying) colors.primary else colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = item.artists?.joinToString(", ") ?: "",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun FullscreenVisualizer(
  visualizerEnabled: Boolean,
  isPlaying: Boolean,
  visualizerStyle: VisualizerStyle,
  primaryColor: Color,
  disablePlayerTransitions: Boolean,
  modifier: Modifier = Modifier,
) {
  val visualizerState by AudioManager.visualizerState.collectAsStateWithLifecycle()
  val shouldShowVisualizer =
    visualizerEnabled && visualizerState.enabled && isPlaying && visualizerState.frequencyData.isNotEmpty()

  AnimatedVisibility(
    visible = shouldShowVisualizer,
    modifier = modifier,
    enter = if (disablePlayerTransitions) EnterTransition.None else fadeIn(animationSpec = tween(300)),
    exit = if (disablePlayerTransitions) ExitTransition.None else fadeOut(animationSpec = tween(300)),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(160.dp)
          .graphicsLayer { alpha = 0.40f },
    ) {
      AudioVisualizer(
        frequencyData = visualizerState.frequencyData,
        timeDomainData = visualizerState.waveform,
        style = visualizerStyle,
        accentColor = primaryColor,
        modifier = Modifier.fillMaxSize(),
        boost = 0.88f,
      )
    }
  }
  VisualizerFrameMetrics(tag = "FullscreenVisualizer", enabled = shouldShowVisualizer)
}
