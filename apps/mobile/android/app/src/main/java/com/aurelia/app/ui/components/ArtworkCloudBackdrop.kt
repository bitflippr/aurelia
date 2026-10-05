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
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
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

/**
 * A cloud: its color (primary, secondary or accent), the point it circles, how far it wanders from
 * there on each axis and how fast, in fractions of the backdrop and radians per second.
 */
private class Cloud(
  val color: Int,
  val homeX: Float,
  val homeY: Float,
  val wanderX: Float,
  val wanderY: Float,
  val speedX: Float,
  val speedY: Float,
  val phase: Float,
)

/**
 * Loops with unrelated speeds, so the pattern takes minutes to come around. The desktop's
 * `<aurelia-clouds>` uses the same clouds.
 */
private val clouds =
  listOf(
    Cloud(0, 0.22f, 0.25f, 0.28f, 0.22f, 0.31f, 0.23f, 0f),
    Cloud(1, 0.78f, 0.3f, 0.25f, 0.25f, 0.19f, 0.29f, 1.7f),
    Cloud(2, 0.35f, 0.8f, 0.3f, 0.2f, 0.26f, 0.17f, 3.1f),
    Cloud(1, 0.75f, 0.85f, 0.24f, 0.2f, 0.22f, 0.33f, 4.4f),
    Cloud(0, 0.5f, 0.5f, 0.35f, 0.3f, 0.15f, 0.21f, 2.3f),
  )

/** Art colors are toned for flat tints; a little richer reads better as light on a dark base. */
private fun richer(color: Color): Color {
  val hsl = FloatArray(3)
  ColorUtils.colorToHSL(color.toArgb(), hsl)
  hsl[1] = (hsl[1] * 1.15f).coerceAtMost(1f)
  hsl[2] = hsl[2].coerceIn(0.38f, 0.5f)
  return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = color.alpha)
}

/**
 * Drifting gradients that swell and shrink as they go. Only colors survive the artwork sampling.
 * Clouds this size leave dark gaps between them, so overlapping hues don't all average to brown.
 */
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
        seconds += (now - previous).coerceAtMost(100_000_000) / 1_000_000_000f
        previous = now
      }
    }
  }
  val dark = isSystemInDarkTheme()
  Canvas(modifier) {
    drawRect(if (dark) Color(0xFF100F18) else Color(0xFFF0ECF4))
    if (showClouds) {
      val t = seconds
      val shades = listOf(primary, secondary, accent).map { if (dark) richer(it) else it }
      for (cloud in clouds) {
        val color = shades[cloud.color]
        val center =
          Offset(
            (cloud.homeX + cloud.wanderX * sin(t * cloud.speedX + cloud.phase)) * size.width,
            (cloud.homeY + cloud.wanderY * cos(t * cloud.speedY + cloud.phase)) * size.height,
          )
        val swell = 1f + 0.18f * sin(t * 0.37f + cloud.phase * 1.9f)
        drawRect(
          Brush.radialGradient(
            0f to color.copy(alpha = if (dark) 0.70f else 0.48f),
            0.45f to color.copy(alpha = if (dark) 0.33f else 0.22f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = size.maxDimension * 0.5f * swell,
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
