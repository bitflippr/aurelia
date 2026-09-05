package com.aurelia.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared dimensions for the floating mini player and app navigation bar.
 * Use these to calculate consistent bottom padding across all screens.
 */
object BottomBarDimensions {
  val MiniPlayerHeight = 64.dp
  val NavBarContentHeight = 72.dp
  val NavBarSpacing = 4.dp // spacing between player and nav when both visible

  /**
   * Calculate the total bottom padding needed for scrollable content.
   * This accounts for: nav bar + optional mini player + system nav bar.
   */
  @Composable
  fun calculateBottomPadding(hasPlayerBar: Boolean): Dp {
    val systemNavBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val navBarTotal = NavBarContentHeight + 24.dp // leave content clear of the dock fade
    val playerBarTotal =
      if (hasPlayerBar) {
        MiniPlayerHeight + NavBarSpacing
      } else {
        0.dp
      }

    return navBarTotal + playerBarTotal + systemNavBarHeight
  }
}
