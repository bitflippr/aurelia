@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSharedTransitionTest {
  @Test
  fun `artwork animation reaches both endpoints without a final millisecond jump`() {
    assertContinuousEndpoints(
      artwork = true,
      mini = Rect(73f, 1956f, 180f, 2063f),
      full = Rect(59f, 386f, 1021f, 1348f),
    )
  }

  @Test
  fun `text animation reaches both endpoints without a final millisecond jump`() {
    assertContinuousEndpoints(
      artwork = false,
      mini = Rect(209f, 1967f, 350f, 2012f),
      full = Rect(59f, 1435f, 331f, 1521f),
    )
  }

  private fun assertContinuousEndpoints(
    artwork: Boolean,
    mini: Rect,
    full: Rect,
  ) {
    val converter = Rect.VectorConverter
    val velocity = AnimationVector4D(0f, 0f, 0f, 0f)
    listOf(mini to full, full to mini).forEach { (start, end) ->
      val spec = playerBoundsTransform(artwork).createAnimationSpec(start, end).vectorize(converter)
      val initial = converter.convertToVector(start)
      val target = converter.convertToVector(end)
      val duration = spec.getDurationNanos(initial, target, velocity)
      val almostFinished = converter.convertFromVector(spec.getValueFromNanos(duration - 1L, initial, target, velocity))
      assertEquals(end.left, almostFinished.left, 0.001f)
      assertEquals(end.top, almostFinished.top, 0.001f)
      assertEquals(end.right, almostFinished.right, 0.001f)
      assertEquals(end.bottom, almostFinished.bottom, 0.001f)
      assertEquals(start, converter.convertFromVector(spec.getValueFromNanos(0L, initial, target, velocity)))
      assertEquals(end, converter.convertFromVector(spec.getValueFromNanos(duration, initial, target, velocity)))
    }
  }

  @Test
  fun `collapsed to expanded seeks forward`() {
    assertEquals(
      PlayerTransitionSeek(fraction = 0.35f, targetExpanded = true),
      playerTransitionSeek(expansionProgress = 0.35f, currentExpanded = false),
    )
  }

  @Test
  fun `expanded to collapsed seeks backward`() {
    val seek = playerTransitionSeek(expansionProgress = 0.65f, currentExpanded = true)

    assertEquals(0.35f, seek.fraction, 0.0001f)
    assertEquals(false, seek.targetExpanded)
  }

  @Test
  fun `seek progress is clamped to transition bounds`() {
    assertEquals(
      PlayerTransitionSeek(fraction = 0f, targetExpanded = true),
      playerTransitionSeek(expansionProgress = -1f, currentExpanded = false),
    )
    assertEquals(
      PlayerTransitionSeek(fraction = 0f, targetExpanded = false),
      playerTransitionSeek(expansionProgress = 2f, currentExpanded = true),
    )
  }
}
