@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlayerSharedTransitionUiTest {
  @get:Rule
  val composeTestRule = createComposeRule()

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
}

@Suppress("FunctionName")
@Composable
private fun PlayerTransitionHarness(
  expansionProgress: Float,
  onMiniPlayerClick: () -> Unit,
) {
  val transitionState = remember { SeekableTransitionState(false) }
  val transition = rememberTransition(transitionState, label = "player-transition-harness")

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
        .height(lerp(64.dp, 700.dp, expansionProgress))
        .background(MaterialTheme.colorScheme.primaryContainer),
  ) {
    SharedTransitionLayout {
      transition.AnimatedContent(
        modifier =
          Modifier.playerTransitionContentLayout(fullHeight = 700.dp),
        transitionSpec = {
          fadeIn(animationSpec = tween(500)) togetherWith
            fadeOut(animationSpec = tween(500))
        },
        contentKey = { expanded -> expanded },
      ) { expanded ->
        if (expanded) {
          Box(
            modifier = Modifier.fillMaxSize().testTag("full-player"),
          ) {
            Box(
              modifier =
                Modifier
                  .align(Alignment.TopCenter)
                  .size(240.dp)
                  .background(Color.DarkGray)
                  .playerSharedElement(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    key = "player-test-artwork",
                  ),
            )
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
          }
        } else {
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
                  .background(Color.DarkGray)
                  .playerSharedElement(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    key = "player-test-artwork",
                  ),
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
