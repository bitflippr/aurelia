@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

private const val PLAYER_SHARED_TRANSITION_DURATION_MS = 500

private val PLAYER_SHARED_BOUNDS_TRANSFORM =
  BoundsTransform { _, _ ->
    tween(
      durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS,
      easing = FastOutSlowInEasing,
    )
  }

internal data class PlayerTransitionSeek(
  val fraction: Float,
  val targetExpanded: Boolean,
)

internal fun Modifier.playerTransitionContentLayout(fullHeight: Dp): Modifier =
  fillMaxWidth()
    .wrapContentSize(Alignment.TopStart)
    .requiredHeight(fullHeight)

internal fun playerTransitionSeek(
  expansionProgress: Float,
  currentExpanded: Boolean,
): PlayerTransitionSeek {
  val progress = expansionProgress.coerceIn(0f, 1f)
  return if (currentExpanded) {
    PlayerTransitionSeek(
      fraction = 1f - progress,
      targetExpanded = false,
    )
  } else {
    PlayerTransitionSeek(
      fraction = progress,
      targetExpanded = true,
    )
  }
}

@Composable
internal fun Modifier.playerSharedElement(
  sharedTransitionScope: SharedTransitionScope?,
  animatedVisibilityScope: AnimatedVisibilityScope?,
  key: String?,
): Modifier {
  if (sharedTransitionScope == null || animatedVisibilityScope == null || key == null) {
    return this
  }

  return with(sharedTransitionScope) {
    this@playerSharedElement.sharedElement(
      sharedContentState = rememberSharedContentState(key = key),
      animatedVisibilityScope = animatedVisibilityScope,
      boundsTransform = PLAYER_SHARED_BOUNDS_TRANSFORM,
    )
  }
}

@Composable
internal fun Modifier.playerSharedBounds(
  sharedTransitionScope: SharedTransitionScope?,
  animatedVisibilityScope: AnimatedVisibilityScope?,
  key: String?,
): Modifier {
  if (sharedTransitionScope == null || animatedVisibilityScope == null || key == null) {
    return this
  }

  return with(sharedTransitionScope) {
    this@playerSharedBounds.sharedBounds(
      sharedContentState = rememberSharedContentState(key = key),
      animatedVisibilityScope = animatedVisibilityScope,
      boundsTransform = PLAYER_SHARED_BOUNDS_TRANSFORM,
    )
  }
}

internal fun playerSharedKey(
  contentKey: String?,
  element: String,
): String? = contentKey?.let { "player-$it-$element" }
