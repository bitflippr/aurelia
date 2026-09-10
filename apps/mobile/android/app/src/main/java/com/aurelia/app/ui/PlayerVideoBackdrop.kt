package com.aurelia.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Blur only a 64-pixel render target, then enlarge it. Shares the cover's decoder and timing. */
@Composable
internal fun PlayerVideoBackdrop(
  video: PlayerArtworkVideoState,
  modifier: Modifier = Modifier,
) {
  val blurred =
    rememberGraphicsLayer().apply {
      renderEffect = BlurEffect(8f, 8f, TileMode.Clamp)
      clip = true
    }
  val dark = isSystemInDarkTheme()
  Canvas(modifier) {
    val source = video.renderedSize
    if (source.width <= 0 || source.height <= 0 || size.minDimension <= 0f) return@Canvas
    val shrink = 64f / size.maxDimension
    val target =
      IntSize((size.width * shrink).roundToInt().coerceAtLeast(1), (size.height * shrink).roundToInt().coerceAtLeast(1))
    blurred.record(size = target) {
      val crop = maxOf(size.width / source.width, size.height / source.height)
      translate((size.width - source.width * crop) / 2f, (size.height - source.height * crop) / 2f) {
        scale(crop, crop, Offset.Zero) { drawLayer(video.layer) }
      }
    }
    scale(size.width / target.width, size.height / target.height, Offset.Zero) { drawLayer(blurred) }
    drawRect(
      Brush.verticalGradient(
        if (dark) {
          listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.72f))
        } else {
          listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.78f))
        },
      ),
    )
  }
}
