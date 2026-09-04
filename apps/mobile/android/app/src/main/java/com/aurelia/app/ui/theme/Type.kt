package com.aurelia.app.ui.theme

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Single cached FontFamily for Google Sans Flex.
 * Using resource-based font to avoid repeated asset loading.
 */
private var cachedGoogleSansFlex: FontFamily? = null

@OptIn(ExperimentalTextApi::class)
fun getGoogleSansFlexFont(context: Context): FontFamily =
  cachedGoogleSansFlex ?: run {
    try {
      val fontFamily =
        FontFamily(
          // Regular weight
          Font(
            path = "fonts/google_sans_flex_regular.ttf",
            assetManager = context.assets,
            weight = FontWeight.Normal,
            variationSettings =
              FontVariation.Settings(
                FontVariation.weight(400),
                FontVariation.width(100f),
                FontVariation.Setting("ROND", 100f),
              ),
          ),
          // Medium weight
          Font(
            path = "fonts/google_sans_flex_regular.ttf",
            assetManager = context.assets,
            weight = FontWeight.Medium,
            variationSettings =
              FontVariation.Settings(
                FontVariation.weight(500),
                FontVariation.width(100f),
                FontVariation.Setting("ROND", 100f),
              ),
          ),
          // SemiBold weight
          Font(
            path = "fonts/google_sans_flex_regular.ttf",
            assetManager = context.assets,
            weight = FontWeight.SemiBold,
            variationSettings =
              FontVariation.Settings(
                FontVariation.weight(600),
                FontVariation.width(100f),
                FontVariation.Setting("ROND", 100f),
              ),
          ),
          // Bold weight
          Font(
            path = "fonts/google_sans_flex_regular.ttf",
            assetManager = context.assets,
            weight = FontWeight.Bold,
            variationSettings =
              FontVariation.Settings(
                FontVariation.weight(700),
                FontVariation.width(100f),
                FontVariation.Setting("ROND", 100f),
              ),
          ),
        )
      cachedGoogleSansFlex = fontFamily
      fontFamily
    } catch (e: Exception) {
      e.printStackTrace()
      FontFamily.SansSerif
    }
  }

// Wide variant for display/headline styles
private var cachedGoogleSansFlexWide: FontFamily? = null

@OptIn(ExperimentalTextApi::class)
fun getGoogleSansFlexWideFont(context: Context): FontFamily =
  cachedGoogleSansFlexWide ?: run {
    try {
      val fontFamily =
        FontFamily(
          Font(
            path = "fonts/google_sans_flex_regular.ttf",
            assetManager = context.assets,
            weight = FontWeight.Black,
            variationSettings =
              FontVariation.Settings(
                FontVariation.weight(900),
                FontVariation.width(120f),
                FontVariation.Setting("ROND", 100f),
              ),
          ),
        )
      cachedGoogleSansFlexWide = fontFamily
      fontFamily
    } catch (e: Exception) {
      e.printStackTrace()
      FontFamily.SansSerif
    }
  }

@Composable
fun rememberGoogleSansFlexFont(): FontFamily {
  val context = LocalContext.current
  return remember { getGoogleSansFlexFont(context) }
}

@Composable
fun rememberGoogleSansFlexWideFont(): FontFamily {
  val context = LocalContext.current
  return remember { getGoogleSansFlexWideFont(context) }
}

@Composable
fun rememberAureliaTypography(): Typography {
  val baseFont = rememberGoogleSansFlexFont()
  val wideFont = rememberGoogleSansFlexWideFont()

  return Typography(
    // Display styles - wide variant
    displayLarge =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    displayMedium =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    displaySmall =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    // Headline styles - wide variant
    headlineLarge =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    headlineMedium =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    headlineSmall =
      TextStyle(
        fontFamily = wideFont,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    // Title styles - base font
    titleLarge =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    titleMedium =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    titleSmall =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    // Body styles
    bodyLarge =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    bodyMedium =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    bodySmall =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    // Label styles
    labelLarge =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    labelMedium =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    labelSmall =
      TextStyle(
        fontFamily = baseFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
  )
}

@Composable
fun rememberNowPlayingStyle(
  isPlaying: Boolean,
  baseStyle: TextStyle,
): TextStyle {
  // Use cached font to avoid memory issues - animation effects handled via fontWeight
  val fontFamily = rememberGoogleSansFlexFont()

  val animatedWeight by animateFloatAsState(
    targetValue = if (isPlaying) FontWeight.Bold.weight.toFloat() else FontWeight.SemiBold.weight.toFloat(),
    animationSpec = tween(300),
    label = "nowPlayingWeight",
  )

  return baseStyle.copy(
    fontFamily = fontFamily,
    fontWeight = FontWeight(animatedWeight.toInt()),
  )
}
