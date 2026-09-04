package com.aurelia.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.aurelia.app.audio.VisualizerStyle

@Composable
fun AudioVisualizer(
  frequencyData: ByteArray,
  timeDomainData: ByteArray,
  style: VisualizerStyle,
  accentColor: Color,
  modifier: Modifier = Modifier,
  boost: Float = 1.0f,
) {
  val barGradient =
    remember(accentColor) {
      Brush.verticalGradient(
        0f to accentColor.copy(alpha = 0.13f),
        1f to accentColor.copy(alpha = 0.50f),
      )
    }
  val curveGradient =
    remember(accentColor) {
      Brush.verticalGradient(
        0f to accentColor.copy(alpha = 0.09f),
        0.5f to accentColor.copy(alpha = 0.25f),
        1f to accentColor.copy(alpha = 0.44f),
      )
    }

  Canvas(modifier = modifier.fillMaxSize()) {
    if (frequencyData.isEmpty() && timeDomainData.isEmpty()) {
      return@Canvas
    }

    when (style) {
      VisualizerStyle.BARS ->
        drawBars(
          frequencyData = frequencyData,
          gradient = barGradient,
          boost = boost,
        )
      VisualizerStyle.CURVE ->
        drawCurve(
          frequencyData = frequencyData,
          accentColor = accentColor,
          gradient = curveGradient,
          boost = boost,
        )
      VisualizerStyle.WAVE ->
        drawWave(
          waveformData = timeDomainData,
          accentColor = accentColor,
          boost = boost,
        )
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
  frequencyData: ByteArray,
  gradient: Brush,
  boost: Float,
) {
  if (frequencyData.isEmpty()) return

  val barCount = 64
  val barWidth = size.width / barCount.toFloat()
  val heightScale = (size.height / 255f) * boost
  val dataStep = frequencyData.size.toFloat() / barCount

  for (i in 0 until barCount) {
    val dataIndex = (i * dataStep).toInt().coerceIn(0, frequencyData.size - 1)
    val value = frequencyData[dataIndex].toInt() and 0xFF
    val barHeight = (value * heightScale).coerceAtMost(size.height)
    val width = (barWidth - 2f).coerceAtLeast(1f)
    drawRect(
      brush = gradient,
      topLeft = Offset(i * barWidth, size.height - barHeight),
      size = Size(width, barHeight),
    )
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurve(
  frequencyData: ByteArray,
  accentColor: Color,
  gradient: Brush,
  boost: Float,
) {
  if (frequencyData.isEmpty()) return

  val sampleCount = 64
  val stepX = size.width / (sampleCount - 1).toFloat()
  val heightScale = (size.height / 255f) * boost
  val dataStep = frequencyData.size.toFloat() / sampleCount

  val crestPoints = mutableListOf<Offset>()
  for (i in 0 until sampleCount) {
    val dataIndex = (i * dataStep).toInt().coerceIn(0, frequencyData.size - 1)
    val value = frequencyData[dataIndex].toInt() and 0xFF
    val amplitude = (value * heightScale).coerceAtMost(size.height)
    val x = i * stepX
    crestPoints.add(Offset(x, size.height - amplitude))
  }

  if (crestPoints.size < 2) return

  val path =
    Path().apply {
      moveTo(0f, size.height)
      lineTo(crestPoints[0].x, crestPoints[0].y)

      for (i in 0 until crestPoints.size - 1) {
        val current = crestPoints[i]
        val next = crestPoints[i + 1]
        val controlX = (current.x + next.x) / 2
        val controlY = (current.y + next.y) / 2
        quadraticTo(current.x, current.y, controlX, controlY)
      }
      lineTo(crestPoints.last().x, crestPoints.last().y)
      lineTo(size.width, size.height)
      close()
    }

  drawPath(path, brush = gradient)

  val strokePath =
    Path().apply {
      moveTo(crestPoints[0].x, crestPoints[0].y)
      for (i in 0 until crestPoints.size - 1) {
        val current = crestPoints[i]
        val next = crestPoints[i + 1]
        val controlX = (current.x + next.x) / 2
        val controlY = (current.y + next.y) / 2
        quadraticTo(current.x, current.y, controlX, controlY)
      }
      lineTo(crestPoints.last().x, crestPoints.last().y)
    }

  drawPath(
    path = strokePath,
    color = accentColor.copy(alpha = 0.56f),
    style =
      androidx.compose.ui.graphics.drawscope
        .Stroke(width = 2f),
  )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(
  waveformData: ByteArray,
  accentColor: Color,
  boost: Float,
) {
  if (waveformData.isEmpty()) return

  val bufferLength = waveformData.size
  val sliceWidth = size.width / bufferLength.toFloat()
  val centerY = size.height / 2f

  val path =
    Path().apply {
      var x = 0f
      for (i in 0 until bufferLength) {
        val sample = waveformData[i].toInt() and 0xFF
        val deviation = (sample - 128f) * boost
        val y = centerY - (deviation / 128f) * centerY
        if (i == 0) {
          moveTo(x, y)
        } else {
          lineTo(x, y)
        }
        x += sliceWidth
      }
    }

  drawPath(
    path = path,
    color = accentColor.copy(alpha = 0.50f),
    style =
      androidx.compose.ui.graphics.drawscope
        .Stroke(width = 2f),
  )
}
