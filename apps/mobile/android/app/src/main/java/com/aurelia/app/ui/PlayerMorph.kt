package com.aurelia.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AnimatedArtwork
import com.aurelia.app.ui.components.ArtworkCloudColors
import com.aurelia.app.utils.optimizedArtworkUrl
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.lerp as lerpColor

internal data class PlayerMorph(
  val expansion: Float,
  val artwork: AsyncImagePainter,
  val primary: Color,
  val onPrimary: Color,
  val collapsedContentBottom: Float,
  val lyricsAlpha: Float,
  val artworkItemId: String? = null,
  val sessionStore: SessionStore? = null,
  val onArtworkColors: ((ArtworkCloudColors) -> Unit)? = null,
  val video: PlayerArtworkVideoState? = null,
) {
  fun skipBackground(enabled: Boolean): Color =
    primary.copy(alpha = if (enabled) mix(0.2f, 0.15f, expansion) else 0.08f)
}

internal val LocalPlayerMorph = compositionLocalOf<PlayerMorph?> { null }

@Composable
internal fun rememberPlayerMorph(
  expansion: Float,
  artworkUrl: String?,
  collapsedContentBottom: Float,
  showLyrics: Boolean,
  transitionsEnabled: Boolean,
  artworkItemId: String? = null,
  sessionStore: SessionStore? = null,
  onArtworkColors: ((ArtworkCloudColors) -> Unit)? = null,
): PlayerMorph {
  val context = LocalContext.current
  val width =
    with(LocalDensity.current) {
      LocalConfiguration.current.screenWidthDp.dp
        .roundToPx()
    }
  val request =
    remember(context, artworkUrl, width) {
      ImageRequest
        .Builder(context)
        .data(optimizedArtworkUrl(artworkUrl, width))
        .size(width)
        .crossfade(false)
        .build()
    }
  // One painter and decode size survive both endpoint compositions, including collapse.
  val artwork = rememberAsyncImagePainter(request)
  val colors = MaterialTheme.colorScheme
  val albumColors = rememberPlayerAlbumColors(artworkUrl)
  val albumPrimary by animateColorAsState(albumColors.primary, label = "albumPrimary")
  val albumOnPrimary =
    if (0.299 * albumPrimary.red + 0.587 * albumPrimary.green + 0.114 * albumPrimary.blue >
      0.5
    ) {
      Color.Black
    } else {
      Color.White
    }
  val p = expansion.coerceIn(0f, 1f)
  val videoLayer = rememberGraphicsLayer()
  val video = remember(videoLayer) { PlayerArtworkVideoState(videoLayer) }
  return PlayerMorph(
    p,
    artwork,
    lerpColor(colors.primary, albumPrimary, p),
    lerpColor(colors.onPrimary, albumOnPrimary, p),
    collapsedContentBottom,
    rememberPlayerLyricsVisibility(showLyrics, p, transitionsEnabled),
    artworkItemId,
    sessionStore,
    onArtworkColors,
    video,
  )
}

@Composable
internal fun PlayerArtwork(modifier: Modifier = Modifier) {
  val morph = checkNotNull(LocalPlayerMorph.current)
  Box(
    modifier
      .onSizeChanged { morph.video?.displayedSize = it }
      .clip(PlayerArtworkShape(morph.expansion))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    if (morph.artwork.state is AsyncImagePainter.State.Success) {
      Image(morph.artwork, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
      Icon(
        Icons.Filled.Album,
        contentDescription = "Album art",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(lerp(18.dp, 64.dp, morph.expansion)),
      )
    }
    morph.video?.let { video ->
      val layer = video.layer
      Box(
        Modifier.fillMaxSize().drawWithContent {
          val rendered = video.renderedSize
          if (rendered.width > 0 && rendered.height > 0) {
            scale(size.width / rendered.width, size.height / rendered.height, Offset.Zero) {
              drawLayer(layer)
            }
          }
        },
      )
    }
  }
}

@Stable
internal class PlayerArtworkVideoState(
  val layer: GraphicsLayer,
) {
  var ready by mutableStateOf(false)
  var displayedSize by mutableStateOf(IntSize.Zero)
  var renderedSize by mutableStateOf(IntSize.Zero)
}

/** Small allocation steps avoid rebuilding GPU layers for every pixel of a swipe. */
internal fun artworkRenderSize(displayed: IntSize): IntSize {
  if (displayed.width <= 0 || displayed.height <= 0) return IntSize.Zero

  fun roundUp(value: Int): Int = ((value + 31) / 32) * 32
  return IntSize(roundUp(displayed.width), roundUp(displayed.height))
}

/** Zero at mini/hidden, normal speed at full, with no abrupt acceleration at either end. */
internal fun playerArtworkPlaybackSpeed(
  expansion: Float,
  lyricsAlpha: Float,
): Float {
  fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
  return smooth(expansion) * smooth(1f - lyricsAlpha)
}

/** One decoder and surface live outside AnimatedContent; both endpoints draw its live layer. */
@Composable
internal fun PlayerArtworkVideoSource() {
  val morph = checkNotNull(LocalPlayerMorph.current)
  val video = morph.video ?: return
  val layer = video.layer
  val size = artworkRenderSize(video.displayedSize)
  if (size == IntSize.Zero) return
  val density = LocalDensity.current
  val store = morph.sessionStore ?: return
  Box(
    Modifier
      .requiredSize(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
      .onSizeChanged { video.renderedSize = it }
      .drawWithContent {
        // Record without drawing here. The shared artwork bounds own placement and clipping.
        layer.record { this@drawWithContent.drawContent() }
      },
  ) {
    AnimatedArtwork(
      itemId = morph.artworkItemId,
      sessionStore = store,
      modifier = Modifier.fillMaxSize(),
      playbackSpeed = playerArtworkPlaybackSpeed(morph.expansion, morph.lyricsAlpha),
      onReady = { video.ready = it },
      // GPU readback for ambient colors can wait until the gesture has settled.
      onColors = if (morph.expansion >= 0.999f) morph.onArtworkColors else null,
    )
  }
}

/** Interpolate both corner extent and curvature: a circle at zero, the player's squircle at one. */
internal data class PlayerArtworkShape(
  val expansion: Float,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val p = expansion.coerceIn(0f, 1f)
    val r = size.minDimension * mix(0.5f, 0.22f, p)
    val control = r * mix(0.55228475f, 1f, p)
    val w = size.width
    val h = size.height
    return Outline.Generic(
      Path().apply {
        moveTo(r, 0f)
        lineTo(w - r, 0f)
        cubicTo(w - r + control, 0f, w, r - control, w, r)
        lineTo(w, h - r)
        cubicTo(w, h - r + control, w - r + control, h, w - r, h)
        lineTo(r, h)
        cubicTo(r - control, h, 0f, h - r + control, 0f, h - r)
        lineTo(0f, r)
        cubicTo(0f, r - control, r - control, 0f, r, 0f)
        close()
      },
    )
  }
}

@Composable
internal fun playerPlayButtonShape(
  isPlaying: Boolean,
  isBuffering: Boolean,
  fallbackExpansion: Float,
): Shape {
  val expansion = LocalPlayerMorph.current?.expansion ?: fallbackExpansion
  val fullCorner by animateFloatAsState(if (isPlaying || isBuffering) 32.5f else 50f, label = "playCornerPercent")
  return RoundedCornerShape(percent = mix(50f, fullCorner, expansion).roundToInt())
}

private fun mix(
  start: Float,
  end: Float,
  progress: Float,
): Float = start + (end - start) * progress

internal fun playerArtworkBounds(
  collapsed: Rect,
  expanded: Rect,
  progress: Float,
): Rect {
  val p = progress.coerceIn(0f, 1f)
  // Move clear of the side-by-side text before growing into the stacked layout.
  // Keep the center on a direct path: growing artwork must never turn back down.
  val width = mix(collapsed.width, expanded.width, p * p)
  val height = mix(collapsed.height, expanded.height, p * p)
  val left = mix(collapsed.left, expanded.left, p)
  val centerY = mix(collapsed.center.y, expanded.center.y, p)
  return Rect(left, centerY - height / 2, left + width, centerY + height / 2)
}

internal fun playerTextBounds(
  collapsed: Rect,
  expanded: Rect,
  progress: Float,
): Rect {
  val p = progress.coerceIn(0f, 1f)
  val bounds = lerpRect(collapsed, expanded, p)
  val top = mix(collapsed.top, expanded.top, p * p)
  return Rect(bounds.left, top, bounds.right, top + bounds.height)
}
