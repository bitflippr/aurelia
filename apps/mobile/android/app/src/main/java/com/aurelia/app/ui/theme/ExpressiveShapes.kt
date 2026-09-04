package com.aurelia.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pillStar
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlin.math.min

// =============================================================================
// Pill Shape - Hero Card (Continue Listening)
// =============================================================================

/**
 * Stadium/Pill shape for the hero section - friendly and premium feel.
 */
val HeroPillShape: Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val radius = min(size.width, size.height) / 2f
      return Outline.Rounded(
        RoundRect(
          rect = Rect(0f, 0f, size.width, size.height),
          cornerRadius = CornerRadius(radius, radius),
        ),
      )
    }
  }

// =============================================================================
// Cookie Shape - Quick Picks (9-sided wavy)
// =============================================================================

/**
 * Creates a wavy cookie-like shape with soft, rounded edges.
 * Uses RoundedPolygon for smooth curve generation.
 */
fun createCookieShape(smoothing: Float = 0.5f): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val polygon =
        RoundedPolygon.pillStar(
          numVerticesPerRadius = 9,
          innerRadiusRatio = 0.88f,
          rounding = CornerRounding(radius = 0.2f, smoothing = smoothing),
        )
      val path = polygon.toPath().asComposePath()
      val matrix = android.graphics.Matrix()
      matrix.setScale(size.width, size.height)
      matrix.postTranslate(size.width / 2f, size.height / 2f)
      path.transform(matrixFromValues(matrix.values()))
      return Outline.Generic(path)
    }
  }

/**
 * Cookie shape instance with default smoothing for Quick Picks.
 */
val QuickPickCookieShape: Shape = createCookieShape(smoothing = 0.6f)

// =============================================================================
// Scallop Shape - Recently Added Albums
// =============================================================================

/**
 * Creates a scalloped edge shape - badge-like feel for fresh content.
 * Uses a 12-pointed star with shallow points for subtle waviness.
 */
fun createScallopShape(): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val polygon =
        RoundedPolygon.star(
          numVerticesPerRadius = 12,
          innerRadius = 0.92f,
          rounding = CornerRounding(radius = 0.15f, smoothing = 0.5f),
        )
      val path = polygon.toPath().asComposePath()
      val matrix = android.graphics.Matrix()
      matrix.setScale(size.width / 2f, size.height / 2f)
      matrix.postTranslate(size.width / 2f, size.height / 2f)
      path.transform(matrixFromValues(matrix.values()))
      return Outline.Generic(path)
    }
  }

val RecentlyAddedScallopShape: Shape = createScallopShape()

// =============================================================================
// Clover Shape - From Your Library
// =============================================================================

/**
 * Creates a 4-leaf clover shape - organic, discovery/exploration vibe.
 */
fun createCloverShape(): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val polygon =
        RoundedPolygon.pillStar(
          numVerticesPerRadius = 4,
          innerRadiusRatio = 0.75f,
          rounding = CornerRounding(radius = 0.3f, smoothing = 0.7f),
        )
      val path = polygon.toPath().asComposePath()
      val matrix = android.graphics.Matrix()
      matrix.setScale(size.width / 2f, size.height / 2f)
      matrix.postTranslate(size.width / 2f, size.height / 2f)
      path.transform(matrixFromValues(matrix.values()))
      return Outline.Generic(path)
    }
  }

val LibraryCloverShape: Shape = createCloverShape()

// =============================================================================
// Squircle Shape - Fallback/Default
// =============================================================================

/**
 * Creates a squircle (superellipse) shape - softer than rounded rectangle.
 * Good default for cards that need less personality.
 */
fun createSquircleShape(cornerRatio: Float = 0.2f): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val path = Path()
      val w = size.width
      val h = size.height
      val r = min(w, h) * cornerRatio

      // Approximate squircle with cubic beziers
      path.moveTo(r, 0f)
      path.lineTo(w - r, 0f)
      path.cubicTo(w, 0f, w, 0f, w, r)
      path.lineTo(w, h - r)
      path.cubicTo(w, h, w, h, w - r, h)
      path.lineTo(r, h)
      path.cubicTo(0f, h, 0f, h, 0f, h - r)
      path.lineTo(0f, r)
      path.cubicTo(0f, 0f, 0f, 0f, r, 0f)
      path.close()

      return Outline.Generic(path)
    }
  }

val SquircleShape: Shape = createSquircleShape(cornerRatio = 0.22f)

// =============================================================================
// Soft Rounded Shape - For album cards with adjustable corner softness
// =============================================================================

/**
 * Creates a soft rounded shape with variable corner radius.
 * More visually interesting than standard RoundedCornerShape.
 */
fun createSoftRoundedShape(
  topLeftRatio: Float = 0.2f,
  topRightRatio: Float = 0.2f,
  bottomRightRatio: Float = 0.2f,
  bottomLeftRatio: Float = 0.2f,
): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val minDim = min(size.width, size.height)
      return Outline.Rounded(
        RoundRect(
          rect = Rect(0f, 0f, size.width, size.height),
          topLeft = CornerRadius(minDim * topLeftRatio),
          topRight = CornerRadius(minDim * topRightRatio),
          bottomRight = CornerRadius(minDim * bottomRightRatio),
          bottomLeft = CornerRadius(minDim * bottomLeftRatio),
        ),
      )
    }
  }

// Asymmetric soft shape for visual interest
val AsymmetricSoftShape: Shape =
  createSoftRoundedShape(
    topLeftRatio = 0.25f,
    topRightRatio = 0.15f,
    bottomRightRatio = 0.25f,
    bottomLeftRatio = 0.15f,
  )

// =============================================================================
// Puffy Shape - Cloud-like for Hero Album Art
// =============================================================================

/**
 * M3E Puffy shape - the official Material 3 Expressive cloud shape.
 * Wraps MaterialShapes.Puffy RoundedPolygon as a Compose Shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val PuffyShape: Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline {
      val polygon = MaterialShapes.Puffy
      val androidPath = polygon.toPath()

      // Get actual path bounds
      val pathBounds = android.graphics.RectF()
      androidPath.computeBounds(pathBounds, true)

      // Transform from actual bounds to target size, preserving aspect ratio
      val matrix = android.graphics.Matrix()
      matrix.setRectToRect(
        pathBounds,
        android.graphics.RectF(0f, 0f, size.width, size.height),
        android.graphics.Matrix.ScaleToFit.CENTER,
      )
      androidPath.transform(matrix)

      return Outline.Generic(androidPath.asComposePath())
    }
  }

// =============================================================================
// Animation Utilities
// =============================================================================

/**
 * Animates scale for press feedback using spring physics.
 */
@Composable
fun rememberPressScale(
  isPressed: Boolean,
  pressedScale: Float = 0.96f,
  normalScale: Float = 1f,
): Float {
  val scale by animateFloatAsState(
    targetValue = if (isPressed) pressedScale else normalScale,
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    label = "pressScale",
  )
  return scale
}

/**
 * Animates elevation for interaction feedback.
 */
@Composable
fun rememberInteractiveElevation(
  isPressed: Boolean,
  isPlaying: Boolean = false,
  baseElevation: Float = 2f,
  pressedElevation: Float = 0f,
  playingElevation: Float = 8f,
): Float {
  val elevation by animateFloatAsState(
    targetValue =
      when {
        isPressed -> pressedElevation
        isPlaying -> playingElevation
        else -> baseElevation
      },
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
      ),
    label = "elevation",
  )
  return elevation
}

// =============================================================================
// Matrix Extensions
// =============================================================================

/**
 * Extension to get float values from Android Matrix.
 */
private fun android.graphics.Matrix.values(): FloatArray {
  val values = FloatArray(9)
  getValues(values)
  return values
}

/**
 * Create Compose Matrix from Android Matrix values.
 */
private fun matrixFromValues(values: FloatArray): androidx.compose.ui.graphics.Matrix =
  androidx.compose.ui.graphics.Matrix().apply {
    this[0, 0] = values[0]
    this[0, 1] = values[1]
    this[0, 3] = values[2]
    this[1, 0] = values[3]
    this[1, 1] = values[4]
    this[1, 3] = values[5]
    this[3, 0] = values[6]
    this[3, 1] = values[7]
    this[3, 3] = values[8]
  }
