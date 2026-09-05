package com.aurelia.app.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMorphTest {
  @Test
  fun `growing artwork never reverses its vertical path and meets both endpoints`() {
    val mini = Rect(73f, 1956f, 180f, 2063f)
    val full = Rect(59f, 386f, 1021f, 1348f)
    assertEquals(mini, playerArtworkBounds(mini, full, 0f))
    assertEquals(full, playerArtworkBounds(mini, full, 1f))
    var previous = mini
    for (step in 1..100) {
      val next = playerArtworkBounds(mini, full, step / 100f)
      assertTrue(next.center.y < previous.center.y)
      assertTrue(next.width >= previous.width)
      previous = next
    }
  }

  @Test
  fun `title clears artwork throughout expansion and collapse`() {
    // Resting bounds captured from the real Pixel 10 Pro XL, in screen pixels.
    val miniArt = Rect(73f, 1956f, 180f, 2063f)
    val fullArt = Rect(59f, 386f, 1021f, 1348f)
    val miniTitle = Rect(209f, 1967f, 350f, 2012f)
    val fullTitle = Rect(59f, 1435f, 331f, 1521f)
    for (step in 0..100) {
      val progress = step / 100f
      val artwork = playerArtworkBounds(miniArt, fullArt, progress)
      val title = playerTextBounds(miniTitle, fullTitle, progress)
      assertFalse("Artwork intersects title at expansion=$progress", artwork.overlaps(title))
    }
  }
}
