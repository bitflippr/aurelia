@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlayerSharedTransitionUiTest {
  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun artworkAndLyricsBlendWithoutMovingControlsAndCanReverseMidTransition() {
    val lyricsVisible = mutableStateOf(false)
    composeTestRule.setContent {
      Column {
        PlayerArtworkLyricsTransition(
          lyricsAlpha = rememberPlayerLyricsVisibility(lyricsVisible.value, 1f),
          modifier = Modifier.size(240.dp).background(Color.Black).testTag("art-lyrics"),
        ) { lyrics, layer ->
          Box(
            layer
              .fillMaxSize()
              .background(if (lyrics) Color.Blue else Color.Red)
              .testTag(if (lyrics) "lyrics" else "artwork"),
          )
        }
        Text("Playback controls", Modifier.testTag("controls"))
      }
    }
    val controlsBounds = composeTestRule.onNodeWithTag("controls").getUnclippedBoundsInRoot()
    val artworkBounds = composeTestRule.onNodeWithTag("artwork").getUnclippedBoundsInRoot()
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.runOnIdle { lyricsVisible.value = true }
    composeTestRule.mainClock.advanceTimeBy(120)
    composeTestRule.onNodeWithTag("artwork").assertExists()
    composeTestRule.onNodeWithTag("lyrics").assertExists()
    assertEquals(artworkBounds, composeTestRule.onNodeWithTag("artwork").getUnclippedBoundsInRoot())
    assertEquals(artworkBounds, composeTestRule.onNodeWithTag("lyrics").getUnclippedBoundsInRoot())
    val pixels = composeTestRule.onNodeWithTag("art-lyrics").captureToImage().toPixelMap()
    val center = pixels[pixels.width / 2, pixels.height / 2]
    assertTrue(
      "Artwork and lyrics should crossfade, rather than switch instantly",
      center.red > 0.05f && center.blue > 0.05f,
    )
    assertEquals(controlsBounds, composeTestRule.onNodeWithTag("controls").getUnclippedBoundsInRoot())

    composeTestRule.runOnIdle { lyricsVisible.value = false }
    composeTestRule.mainClock.advanceTimeBy(400)
    composeTestRule.onNodeWithTag("artwork").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lyrics").assertDoesNotExist()
    composeTestRule.runOnIdle { lyricsVisible.value = true }
    composeTestRule.mainClock.advanceTimeBy(400)
    composeTestRule.onNodeWithTag("lyrics").assertIsDisplayed()
    composeTestRule.onNodeWithTag("artwork").assertExists()
    assertEquals(controlsBounds, composeTestRule.onNodeWithTag("controls").getUnclippedBoundsInRoot())
  }

  @Test
  fun lyricsFadeIntoSharedArtworkInBothSwipeDirections() {
    val progress = mutableFloatStateOf(1f)
    composeTestRule.setContent {
      MaterialTheme { PlayerTransitionHarness(progress.floatValue, onMiniPlayerClick = {}, showLyrics = true) }
    }

    fun pixelsAt(p: Float): Pair<Float, Float> {
      composeTestRule.runOnIdle { progress.floatValue = p }
      val image = composeTestRule.onNodeWithTag("player-sheet").captureToImage().toPixelMap()
      // Blue exists only in the lyrics, red only in the shared artwork.
      var red = 0f
      var blue = 0f
      for (y in 0 until image.height step 8) {
        for (x in 0 until image.width step 8) {
          val c = image[x, y]
          red = maxOf(red, c.red - maxOf(c.green, c.blue))
          blue = maxOf(blue, c.blue - maxOf(c.red, c.green))
        }
      }
      return red to blue
    }
    val full = pixelsAt(1f)
    assertTrue("Full lyrics must not show the hidden cover", full.first < 0.02f && full.second > 0.95f)
    val midClose = pixelsAt(0.8f)
    assertTrue(
      "Closing should crossfade lyrics into the moving cover",
      midClose.first > 0.05f && midClose.second > 0.05f,
    )
    val lower = pixelsAt(0.5f)
    assertTrue("Lyrics should disappear before reaching the mini-player", lower.first > 0.95f && lower.second < 0.02f)
    val mini = pixelsAt(0f)
    assertTrue("The mini-player must have fully visible artwork", mini.first > 0.95f && mini.second < 0.02f)
    val midOpen = pixelsAt(0.8f)
    assertEquals(midClose.first, midOpen.first, 0.03f)
    assertEquals(midClose.second, midOpen.second, 0.03f)
    val reopened = pixelsAt(1f)
    assertTrue("Reopening must return to lyrics", reopened.first < 0.02f && reopened.second > 0.95f)
  }

  @Test
  fun lyricsViewportFadesToTheBackgroundAtBothEdges() {
    composeTestRule.setContent {
      Box(Modifier.size(200.dp).background(Color.Blue).testTag("viewport")) {
        Box(Modifier.fillMaxSize().lyricsFadingEdges().background(Color.White))
      }
    }
    val pixels = composeTestRule.onNodeWithTag("viewport").captureToImage().toPixelMap()
    val x = pixels.width / 2
    val top = pixels[x, 1]
    val bottom = pixels[x, pixels.height - 2]
    val inner = pixels[x, pixels.height / 2]
    val ramp = pixels[x, pixels.height / 10]
    assertTrue(top.red < 0.1f && bottom.red < 0.1f)
    assertTrue("Edges must reveal the backdrop, not paint a solid fade color", top.blue > 0.95f && bottom.blue > 0.95f)
    assertTrue("The middle must remain opaque", inner.red > 0.95f)
    assertTrue("Opacity must ramp between edge and middle", ramp.red > top.red && ramp.red < inner.red)
  }

  @Test
  fun collapsingAfterExpansionLeavesMiniPlayerVisibleAndClickable() {
    val expansionProgress = mutableFloatStateOf(0f)
    val miniPlayerClicks = AtomicInteger()

    composeTestRule.setContent {
      MaterialTheme {
        PlayerTransitionHarness(
          expansionProgress = expansionProgress.floatValue,
          onMiniPlayerClick = { miniPlayerClicks.incrementAndGet() },
        )
      }
    }

    composeTestRule.onNodeWithTag("mini-player").assertIsDisplayed()

    composeTestRule.runOnIdle { expansionProgress.floatValue = 1f }
    composeTestRule.onNodeWithTag("full-player").assertIsDisplayed()

    listOf(0.96f, 0.82f, 0.61f, 0.39f, 0.18f, 0.04f, 0f).forEach { progress ->
      composeTestRule.runOnIdle { expansionProgress.floatValue = progress }
    }

    composeTestRule.onNodeWithTag("mini-player").assertIsDisplayed().performClick()
    composeTestRule.runOnIdle { assertEquals(1, miniPlayerClicks.get()) }
  }

  @Test
  fun supportingDetailsSlideAndFadeWithReversibleSwipeProgress() {
    val expansionProgress = mutableFloatStateOf(1f)
    composeTestRule.setContent {
      MaterialTheme {
        PlayerTransitionHarness(expansionProgress.floatValue, onMiniPlayerClick = {})
      }
    }

    fun sample(progress: Float): Pair<Float, Float> {
      composeTestRule.runOnIdle { expansionProgress.floatValue = progress }
      val bounds = composeTestRule.onNodeWithTag("full-player-content-probe").getUnclippedBoundsInRoot()
      val sheet = composeTestRule.onNodeWithTag("player-sheet")
      val sheetBounds = sheet.getUnclippedBoundsInRoot()
      val pixels = sheet.captureToImage().toPixelMap()
      val scale = pixels.width / (sheetBounds.right.value - sheetBounds.left.value)
      val x = (((bounds.left.value + bounds.right.value) / 2 - sheetBounds.left.value) * scale).toInt()
      val y = (((bounds.top.value + bounds.bottom.value) / 2 - sheetBounds.top.value) * scale).toInt()
      return bounds.top.value to pixels[x, y].red
    }

    val full = sample(1f)
    val closing = sample(0.8f)
    val almostHidden = sample(0.6f)
    assertTrue(
      "Details should move downward on collapse",
      closing.first > full.first && almostHidden.first > closing.first,
    )
    assertTrue(
      "Details should fade during that movement",
      closing.second < full.second - 0.1f && almostHidden.second < closing.second - 0.1f,
    )
    val reversing = sample(0.8f)
    assertEquals("Reversing a swipe must retrace the same position", closing.first, reversing.first, 1f)
    assertEquals("Reversing a swipe must retrace the same opacity", closing.second, reversing.second, 0.02f)
    composeTestRule.runOnIdle { expansionProgress.floatValue = 0f }
    val opening = sample(0.8f)
    assertEquals("Opening and closing must use the same position", closing.first, opening.first, 1f)
    assertEquals("Opening and closing must use the same opacity", closing.second, opening.second, 0.02f)
  }

  @Test
  fun midExpansionKeepsMiniPlayerAtItsRestingPosition() {
    val expansionProgress = mutableFloatStateOf(0f)

    composeTestRule.setContent {
      MaterialTheme {
        PlayerTransitionHarness(
          expansionProgress = expansionProgress.floatValue,
          onMiniPlayerClick = {},
        )
      }
    }

    composeTestRule.runOnIdle { expansionProgress.floatValue = 0.5f }

    val sheetBounds = composeTestRule.onNodeWithTag("player-sheet").getUnclippedBoundsInRoot()
    val miniPlayerBounds = composeTestRule.onNodeWithTag("mini-player").getUnclippedBoundsInRoot()

    assertEquals(
      "Mini-player endpoint must remain at its resting position",
      sheetBounds.bottom.value,
      miniPlayerBounds.bottom.value,
      1f,
    )
  }
}

@Suppress("FunctionName")
@Composable
private fun PlayerTransitionHarness(
  expansionProgress: Float,
  onMiniPlayerClick: () -> Unit,
  showLyrics: Boolean = false,
) {
  val transitionState = remember { SeekableTransitionState(false) }
  val transition = rememberTransition(transitionState, label = "player-transition-harness")
  val collapsedContentBottom = with(LocalDensity.current) { 700.dp.toPx() }
  val lyricsAlpha = rememberPlayerLyricsVisibility(showLyrics, expansionProgress)

  LaunchedEffect(expansionProgress) {
    when {
      expansionProgress <= 0f -> {
        transitionState.snapTo(false)
      }

      expansionProgress >= 1f -> {
        transitionState.snapTo(true)
      }

      else -> {
        val seek = playerTransitionSeek(expansionProgress, transitionState.currentState)
        transitionState.seekTo(
          fraction = seek.fraction,
          targetState = seek.targetExpanded,
        )
      }
    }
  }

  Box(
    modifier =
      Modifier
        .width(360.dp)
        .height(700.dp)
        .background(Color.Black)
        .testTag("player-sheet"),
  ) {
    SharedTransitionLayout {
      transition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { playerContentTransform() },
        contentKey = { expanded -> expanded },
      ) { expanded ->
        if (expanded) {
          Box(
            modifier = Modifier.fillMaxSize().testTag("full-player"),
          ) {
            PlayerArtworkLyricsTransition(
              lyricsAlpha = lyricsAlpha,
              sharedTransitionScope = this@SharedTransitionLayout,
              modifier = Modifier.align(Alignment.TopCenter).size(240.dp),
            ) { lyrics, layer ->
              if (lyrics) {
                Box(layer.fillMaxSize().background(Color.Blue).testTag("harness-lyrics"))
              } else {
                Box(
                  Modifier
                    .playerSharedElement(
                      sharedTransitionScope = this@SharedTransitionLayout,
                      animatedVisibilityScope = this@AnimatedContent,
                      key = "player-test-artwork",
                    ).then(layer)
                    .fillMaxSize()
                    .background(Color.Red)
                    .testTag("harness-artwork"),
                )
              }
            }
            Text(
              text = "Test song",
              modifier =
                Modifier
                  .align(Alignment.Center)
                  .playerSharedBounds(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    key = "player-test-title",
                  ),
            )
            Box(
              modifier =
                Modifier
                  .align(Alignment.Center)
                  .playerSupportingContent(this@SharedTransitionLayout, expansionProgress, collapsedContentBottom)
                  .size(64.dp)
                  .background(Color.White)
                  .testTag("full-player-content-probe"),
            )
          }
        } else {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(64.dp)
                  .clickable(onClick = onMiniPlayerClick)
                  .testTag("mini-player"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier =
                  Modifier
                    .size(48.dp)
                    .playerSharedElement(
                      sharedTransitionScope = this@SharedTransitionLayout,
                      animatedVisibilityScope = this@AnimatedContent,
                      key = "player-test-artwork",
                    ).graphicsLayer { alpha = 1f - lyricsAlpha }
                    .background(Color.Red),
              )
              Text(
                text = "Test song",
                modifier =
                  Modifier.playerSharedBounds(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    key = "player-test-title",
                  ),
              )
            }
          }
        }
      }
    }
  }
}
