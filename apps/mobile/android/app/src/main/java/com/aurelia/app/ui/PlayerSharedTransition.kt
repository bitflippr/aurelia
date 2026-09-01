@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
  fillMaxWidth().layout { measurable, constraints ->
    val fullHeightPx = fullHeight.roundToPx()
    val placeable =
      measurable.measure(
        constraints.copy(
          minHeight = fullHeightPx,
          maxHeight = fullHeightPx,
        ),
      )

    layout(
      width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth),
      height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight),
    ) {
      placeable.placeRelative(x = 0, y = 0)
    }
  }

@Composable
internal fun PlayerMiniTransitionContainer(
  sheetHeight: Dp,
  content: @Composable () -> Unit,
) {
  Layout(
    content = content,
    modifier = Modifier.fillMaxWidth(),
  ) { measurables, constraints ->
    val placeables =
      measurables.map { measurable ->
        measurable.measure(
          constraints.copy(
            minWidth = 0,
            minHeight = 0,
          ),
        )
      }
    val width = placeables.maxOfOrNull { it.width }?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: 0
    val height = constraints.maxHeight
    val sheetBottom = sheetHeight.roundToPx()

    layout(width = width, height = height) {
      placeables.forEach { placeable ->
        placeable.placeRelative(
          x = 0,
          y = (sheetBottom - placeable.height).coerceAtLeast(0),
        )
      }
    }
  }
}

internal fun AnimatedContentTransitionScope<Boolean>.playerContentTransform(): ContentTransform =
  if (targetState) {
    fadeIn(
      animationSpec =
        keyframes {
          durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
          0f at 0
          1f at 100
          1f at PLAYER_SHARED_TRANSITION_DURATION_MS
        },
    ) togetherWith
      fadeOut(
        animationSpec =
          keyframes {
            durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
            1f at 0
            1f at 100
            0f at 180
            0f at PLAYER_SHARED_TRANSITION_DURATION_MS
          },
      )
  } else {
    fadeIn(
      animationSpec =
        keyframes {
          durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
          0f at 0
          0f at 320
          1f at 410
          1f at PLAYER_SHARED_TRANSITION_DURATION_MS
        },
    ) togetherWith
      fadeOut(
        animationSpec =
          keyframes {
            durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
            1f at 0
            1f at 410
            0f at PLAYER_SHARED_TRANSITION_DURATION_MS
          },
      )
  }

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
