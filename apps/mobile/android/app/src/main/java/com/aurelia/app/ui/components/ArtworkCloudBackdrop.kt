package com.aurelia.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

internal data class ArtworkCloudColors(
  val primary: Color,
  val secondary: Color,
  val accent: Color,
)

internal fun artworkCloudColors(bitmap: Bitmap): ArtworkCloudColors {
  val palette = Palette.from(bitmap).maximumColorCount(8).generate()
  val dominant = palette.dominantSwatch?.rgb?.let { Color(it) } ?: Color.DarkGray
  return ArtworkCloudColors(
    palette.mutedSwatch?.rgb?.let { Color(it) } ?: dominant,
    palette.lightMutedSwatch?.rgb?.let { Color(it) }
      ?: palette.vibrantSwatch?.rgb?.let { Color(it) } ?: dominant,
    palette.darkVibrantSwatch?.rgb?.let { Color(it) } ?: dominant,
  )
}

/** Slowly drifting, broad gradients. Only colors survive the artwork sampling. */
@Composable
internal fun ArtworkCloudBackdrop(
  colors: ArtworkCloudColors,
  modifier: Modifier = Modifier,
  animated: Boolean = true,
  showClouds: Boolean = true,
) {
  val moving = rememberArtworkMotionEnabled() && animated && showClouds
  val duration = if (moving) 5_000 else 0
  val primary by animateColorAsState(colors.primary, tween(duration), label = "cloud-primary")
  val secondary by animateColorAsState(colors.secondary, tween(duration), label = "cloud-secondary")
  val accent by animateColorAsState(colors.accent, tween(duration), label = "cloud-accent")
  var seconds by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(moving) {
    if (!moving) return@LaunchedEffect
    var previous = withFrameNanos { it }
    while (isActive) {
      withFrameNanos { now ->
        seconds = (seconds + (now - previous).coerceAtMost(100_000_000) / 1_000_000_000f) % 3_600f
        previous = now
      }
    }
  }
  val dark = isSystemInDarkTheme()
  Canvas(modifier) {
    drawRect(if (dark) Color(0xFF100F18) else Color(0xFFF0ECF4))
    if (showClouds) {
      val t = seconds * 0.12f
      val shades = listOf(primary, secondary, accent, secondary)
      val centers =
        listOf(
          Offset(0.2f + 0.22f * sin(t), 0.2f + 0.13f * cos(t * 0.8f)),
          Offset(0.8f + 0.2f * cos(t * 0.7f), 0.38f + 0.2f * sin(t * 0.9f)),
          Offset(0.3f + 0.25f * cos(t * 0.6f), 0.8f + 0.16f * sin(t * 0.7f)),
          Offset(0.75f + 0.2f * sin(t * 0.8f), 1.05f + 0.15f * cos(t)),
        )
      for (index in shades.indices) {
        val color = shades[index]
        drawRect(
          Brush.radialGradient(
            0f to color.copy(alpha = if (dark) 0.60f else 0.48f),
            0.45f to color.copy(alpha = if (dark) 0.28f else 0.22f),
            1f to color.copy(alpha = 0f),
            center = Offset(centers[index].x * size.width, centers[index].y * size.height),
            radius = size.maxDimension * 0.8f,
          ),
        )
      }
    }
    drawRect(
      Brush.verticalGradient(
        listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = if (dark) 0.35f else 0.12f)),
      ),
    )
  }
}
