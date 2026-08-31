package com.aurelia.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSharedTransitionTest {
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
