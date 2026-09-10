package com.aurelia.app.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerArtworkMotionTest {
  @Test
  fun `speed slows continuously to a real stop and retraces on reversal`() {
    assertEquals(0f, playerArtworkPlaybackSpeed(0f, 0f), 0f)
    assertEquals(0.5f, playerArtworkPlaybackSpeed(0.5f, 0f), 0.00001f)
    assertEquals(1f, playerArtworkPlaybackSpeed(1f, 0f), 0f)
    val speeds = (0..100).map { playerArtworkPlaybackSpeed(it / 100f, 0f) }
    assertTrue(speeds.zipWithNext().all { (a, b) -> b > a && b - a < 0.016f })
    assertEquals(speeds.reversed(), (100 downTo 0).map { playerArtworkPlaybackSpeed(it / 100f, 0f) })
    assertTrue(playerArtworkPlaybackSpeed(0.01f, 0f) < 0.001f)
    assertTrue(playerArtworkPlaybackSpeed(0.99f, 0f) > 0.999f)
  }

  @Test
  fun `lyrics fade slows the same video without changing the expansion curve`() {
    assertEquals(0f, playerArtworkPlaybackSpeed(1f, 1f), 0f)
    assertEquals(0.5f, playerArtworkPlaybackSpeed(1f, 0.5f), 0.00001f)
    assertEquals(0.25f, playerArtworkPlaybackSpeed(0.5f, 0.5f), 0.00001f)
  }

  @Test
  fun `render target follows displayed pixels with bounded allocation rounding`() {
    assertEquals(IntSize.Zero, artworkRenderSize(IntSize.Zero))
    assertEquals(IntSize(128, 128), artworkRenderSize(IntSize(107, 107)))
    assertEquals(IntSize(992, 992), artworkRenderSize(IntSize(962, 962)))
    for (size in 44..1400) {
      val render = artworkRenderSize(IntSize(size, size))
      assertTrue(render.width in size until size + 32)
      assertEquals(render.width, render.height)
    }
  }
}
