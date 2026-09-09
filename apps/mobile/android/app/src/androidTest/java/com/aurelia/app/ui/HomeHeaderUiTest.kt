package com.aurelia.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aurelia.app.ui.theme.rememberAureliaTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeHeaderUiTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun everyOptionFitsOneLineAlongsideTheProfileButton() {
    val title = mutableStateOf(HomeHeaderText.options.first())
    val width = mutableStateOf(320.dp)
    val fontScale = mutableStateOf(1f)
    var settingsOpened = false
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
        MaterialTheme(typography = rememberAureliaTypography()) {
          Box(Modifier.width(width.value)) {
            HomeHeader("Listener", { settingsOpened = true }, title.value)
          }
        }
      }
    }
    for (screenWidth in listOf(320.dp, 390.dp)) {
      for (scale in listOf(1f, 1.5f, 2f)) {
        HomeHeaderText.options.forEach { option ->
          compose.runOnIdle {
            title.value = option
            width.value = screenWidth
            fontScale.value = scale
          }
          val layouts = mutableListOf<TextLayoutResult>()
          compose.onNodeWithText(option).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
          val layout = layouts.single()
          assertEquals("$option at $screenWidth / $scale", 1, layout.lineCount)
          assertFalse("$option at $screenWidth / $scale", layout.isLineEllipsized(0))
          assertFalse(layout.didOverflowWidth)
        }
      }
    }
    compose.onNodeWithContentDescription("Profile and settings").assertIsDisplayed().performClick()
    assertTrue(settingsOpened)
  }

  @Test
  fun leavingAndRecreatingHomeKeepsTheProcessGreeting() {
    val visible = mutableStateOf(true)
    val username = mutableStateOf("Listener")
    val chosen = HomeHeaderText.current
    compose.setContent {
      MaterialTheme {
        if (visible.value) HomeHeader(username.value, {})
      }
    }
    compose.onNodeWithText(chosen).assertIsDisplayed()
    compose.runOnIdle { username.value = "Another listener" }
    compose.onNodeWithText(chosen).assertIsDisplayed()
    compose.runOnIdle { visible.value = false }
    compose.onNodeWithText(chosen).assertDoesNotExist()
    compose.runOnIdle { visible.value = true }
    compose.onNodeWithText(chosen).assertIsDisplayed()
  }
}
