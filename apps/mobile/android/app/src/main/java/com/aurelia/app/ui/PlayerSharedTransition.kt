@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.aurelia.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedDurationBasedAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

private const val PLAYER_SHARED_TRANSITION_DURATION_MS = 500

internal fun createPlayerSheetOffset(collapsedPosition: Float): Animatable<Float, AnimationVector1D> =
  Animatable(collapsedPosition).apply {
    // A critically damped spring can still pass its target with enough initial
    // velocity. Stop at the physical endpoints instead of springing back to them.
    updateBounds(lowerBound = 0f, upperBound = collapsedPosition)
  }

internal fun Modifier.lyricsFadingEdges(): Modifier =
  graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
      val edge = (36.dp.toPx() / size.height).coerceAtMost(0.5f)
      val mask =
        Brush.verticalGradient(
          0f to Color.Transparent,
          edge to Color.Black,
          (1f - edge) to Color.Black,
          1f to Color.Transparent,
        )
      onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
      }
    }

@Composable
internal fun rememberPlayerLyricsVisibility(
  showLyrics: Boolean,
  expansion: Float,
  transitionsEnabled: Boolean = true,
): Float {
  val selected by animateFloatAsState(
    targetValue = if (showLyrics) 1f else 0f,
    animationSpec = tween(if (transitionsEnabled) 300 else 0),
    label = "player-lyrics-selection",
  )
  // Let the cover become visibly smaller before it replaces the lyrics.
  // This curve is driven by the gesture, so reversing a drag retraces it exactly.
  val p = ((expansion - 0.65f) / 0.35f).coerceIn(0f, 1f)
  return selected * p * p * (3f - 2f * p)
}

@Composable
internal fun PlayerArtworkLyricsTransition(
  lyricsAlpha: Float,
  modifier: Modifier = Modifier,
  sharedTransitionScope: SharedTransitionScope? = null,
  content: @Composable (Boolean, Modifier) -> Unit,
) {
  val savedLyrics = rememberSaveableStateHolder()
  Box(modifier) {
    // Always keep the artwork endpoint available for the mini-player to match.
    // Its alpha must be inside sharedElement, which draws above ancestor layers.
    content(false, Modifier.graphicsLayer { alpha = 1f - lyricsAlpha })
    if (lyricsAlpha > 0f) {
      savedLyrics.SaveableStateProvider("lyrics") {
        val layer =
          if (sharedTransitionScope == null) {
            Modifier
          } else {
            with(sharedTransitionScope) {
              Modifier.skipToLookaheadPosition().renderInSharedTransitionScopeOverlay(renderInOverlay = { true })
            }
          }
        content(true, layer.graphicsLayer { alpha = lyricsAlpha })
      }
    }
  }
}

private val PLAYER_SHARED_BOUNDS_TRANSFORM =
  BoundsTransform { _, _ ->
    tween(
      durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS,
      // The sheet's spring already eases progress. Keep shared bounds on that
      // same progress so they cannot lag behind it and then catch up.
      easing = LinearEasing,
    )
  }

internal fun playerBoundsTransform(artwork: Boolean): BoundsTransform =
  BoundsTransform { initial, target ->
    val expanding = if (artwork) target.width > initial.width else target.top < initial.top
    PlayerBoundsAnimationSpec(artwork, expanding)
  }

private class PlayerBoundsAnimationSpec(
  private val artwork: Boolean,
  private val expanding: Boolean,
) : FiniteAnimationSpec<Rect> {
  override fun <V : AnimationVector> vectorize(
    converter: TwoWayConverter<Rect, V>,
  ): VectorizedDurationBasedAnimationSpec<V> =
    object : VectorizedDurationBasedAnimationSpec<V> {
      override val durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
      override val delayMillis = 0
      private val durationNanos = durationMillis * 1_000_000L

      private fun boundsAt(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
      ): Rect {
        val initial = converter.convertFromVector(initialValue)
        val target = converter.convertFromVector(targetValue)
        if (playTimeNanos <= 0L) return initial
        if (playTimeNanos >= durationNanos) return target
        // Keyframes truncate to whole milliseconds, leaving the last moving
        // frame several pixels short of the resting layout. Evaluate the same
        // path at the exact seek fraction so removing the overlay cannot jump.
        val fraction = playTimeNanos.toFloat() / durationNanos
        val expansion = if (expanding) fraction else 1f - fraction
        val collapsed = if (expanding) initial else target
        val expanded = if (expanding) target else initial
        return if (artwork) {
          playerArtworkBounds(collapsed, expanded, expansion)
        } else {
          playerTextBounds(collapsed, expanded, expansion)
        }
      }

      override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
      ): V = converter.convertToVector(boundsAt(playTimeNanos, initialValue, targetValue))

      override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
      ): V {
        if (playTimeNanos <= 0L) return initialVelocity
        // Match tween's 1ms finite difference, in pixels per second.
        val time = playTimeNanos.coerceAtMost(durationNanos)
        val previousTime = (time - 1_000_000L).coerceAtLeast(0L)
        val current = boundsAt(time, initialValue, targetValue)
        val previous = boundsAt(previousTime, initialValue, targetValue)
        val seconds = (time - previousTime) / 1_000_000_000f
        return converter.convertToVector(
          Rect(
            (current.left - previous.left) / seconds,
            (current.top - previous.top) / seconds,
            (current.right - previous.right) / seconds,
            (current.bottom - previous.bottom) / seconds,
          ),
        )
      }
    }
}

private val PLAYER_ARTWORK_BOUNDS_TRANSFORM = playerBoundsTransform(artwork = true)
private val PLAYER_TEXT_BOUNDS_TRANSFORM = playerBoundsTransform(artwork = false)

internal data class PlayerTransitionSeek(
  val fraction: Float,
  val targetExpanded: Boolean,
)

/** A moving sheet outline in a stationary, screen-sized shared-element layout. */
internal data class PlayerSheetShape(
  val top: Float,
  val height: Float,
  val horizontalInset: Float,
  val topRadius: Float,
  val bottomRadius: Float,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline =
    Outline.Rounded(
      RoundRect(
        left = horizontalInset,
        top = top,
        right = size.width - horizontalInset,
        bottom = (top + height).coerceAtMost(size.height),
        topLeftCornerRadius = CornerRadius(topRadius),
        topRightCornerRadius = CornerRadius(topRadius),
        bottomLeftCornerRadius = CornerRadius(bottomRadius),
        bottomRightCornerRadius = CornerRadius(bottomRadius),
      ),
    )
}

internal fun AnimatedContentTransitionScope<Boolean>.playerContentTransform(): ContentTransform =
  if (targetState) {
    fadeIn(
      animationSpec =
        keyframes {
          durationMillis = PLAYER_SHARED_TRANSITION_DURATION_MS
          0f at 0
          // Shared elements draw in the overlay throughout the morph. Keep
          // full-only content out of their path until there is room for it.
          0f at 350
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
      boundsTransform =
        if (key.endsWith(
            "-artwork",
          )
        ) {
          PLAYER_ARTWORK_BOUNDS_TRANSFORM
        } else {
          PLAYER_SHARED_BOUNDS_TRANSFORM
        },
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
      boundsTransform =
        if (key.endsWith("-title") ||
          key.endsWith("-artist")
        ) {
          PLAYER_TEXT_BOUNDS_TRANSFORM
        } else {
          PLAYER_SHARED_BOUNDS_TRANSFORM
        },
    )
  }
}

internal fun playerSharedKey(
  contentKey: String?,
  element: String,
): String? = contentKey?.let { "player-$it-$element" }

internal enum class PlayerSupportingRole {
  Metadata,
  SeekBar,
  Header,
  Footer,
}

/** Full-only content moves and fades with the gesture, independently of the screen fade. */
internal fun Modifier.playerSupportingContent(
  sharedTransitionScope: SharedTransitionScope?,
  expansion: Float,
  collapsedContentBottom: Float,
  role: PlayerSupportingRole = PlayerSupportingRole.Metadata,
): Modifier {
  if (sharedTransitionScope == null) return this
  val p = expansion.coerceIn(0f, 1f)
  return with(sharedTransitionScope) {
    this@playerSupportingContent
      .approachLayout(
        isMeasurementApproachInProgress = { false },
        isPlacementApproachInProgress = { true },
      ) { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
          val coordinates = coordinates
          if (coordinates == null) {
            placeable.place(0, 0)
          } else {
            val target = lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates)
            val actual = lookaheadScopeCoordinates.localPositionOf(coordinates)
            // The seekbar needs to disappear above the transport row, rather
            // than travel through its buttons on the way to the compact player.
            val collapsedY =
              when (role) {
                PlayerSupportingRole.SeekBar -> collapsedContentBottom - placeable.height * 2
                PlayerSupportingRole.Footer -> maxOf(collapsedContentBottom, target.y) + placeable.height
                else -> collapsedContentBottom
              }
            // Keep the header above the growing artwork; details below it follow
            // the shared text. The footer retreats below the transport buttons.
            val travel = if (role == PlayerSupportingRole.Header) p else p * p
            val position = target.copy(y = target.y + (collapsedY - target.y) * (1f - travel))
            placeable.place((position - actual).round())
          }
        }
      }.renderInSharedTransitionScopeOverlay(renderInOverlay = { true })
      .graphicsLayer {
        // There is no room for these details in the compact layout. Reveal them
        // as the text and transport controls separate; reverse the same curve on collapse.
        val start =
          when (role) {
            PlayerSupportingRole.SeekBar -> 0.8f
            PlayerSupportingRole.Metadata -> 0.55f
            else -> 0.65f
          }
        val fraction = ((p - start) / (1f - start)).coerceIn(0f, 1f)
        alpha = fraction * fraction * (3f - 2f * fraction)
      }
  }
}
