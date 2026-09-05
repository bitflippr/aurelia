package com.aurelia.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetOffsetTest {
  @Test
  fun `fast closing fling never carries the sheet past its compact endpoint`() =
    checkFling(start = 1800f, target = 1920f, velocity = 20000f)

  @Test
  fun `fast opening fling never carries the sheet above its expanded endpoint`() =
    checkFling(start = 120f, target = 0f, velocity = -20000f)

  private fun checkFling(
    start: Float,
    target: Float,
    velocity: Float,
  ) {
    // Run the real Animatable spring one frame at a time, without wall-clock delays.
    val clock =
      object : MonotonicFrameClock {
        var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
          nanos += 16_666_667L
          return onFrame(nanos)
        }
      }
    runBlocking(clock) {
      val offset = createPlayerSheetOffset(1920f)
      offset.snapTo(start)
      var lowest = offset.value
      var highest = offset.value
      offset.animateTo(
        targetValue = target,
        initialVelocity = velocity,
        animationSpec =
          spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
      ) {
        lowest = minOf(lowest, value)
        highest = maxOf(highest, value)
      }
      assertTrue("Sheet moved outside its endpoints: $lowest..$highest", lowest >= 0f && highest <= 1920f)
      assertEquals(target, offset.value, 0.01f)
      assertEquals(0f, offset.velocity, 0.01f)
    }
  }
}
