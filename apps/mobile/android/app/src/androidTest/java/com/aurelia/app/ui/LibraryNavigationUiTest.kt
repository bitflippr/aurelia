package com.aurelia.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aurelia.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryNavigationUiTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun bottomBarHasOnlyThreeDestinationsAndUpdatesSelection() {
    val selected = mutableStateOf(MainTab.Home)
    compose.setContent {
      MaterialTheme {
        BottomNavBar(selectedTab = selected.value, onNavigate = { selected.value = it })
      }
    }
    compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    listOf("Home", "Search", "Library").forEach { label ->
      compose.onNodeWithText(label).performClick().assertIsSelected()
    }
    assertEquals(MainTab.Library, selected.value)
  }

  @Test
  fun libraryExposesAllCollectionDestinationsAndSettings() {
    var destination: Screen? = null
    var settings = false
    compose.setContent {
      MaterialTheme {
        LibraryOverviewScreen(hasPlayerBar = false, onNavigate = { destination = it }, onOpenSettings = {
          settings =
            true
        })
      }
    }
    listOf(
      "Songs" to Screen.Songs,
      "Albums" to Screen.Albums,
      "Artists" to Screen.Artists,
      "Playlists" to Screen.Playlists,
    ).forEach { (label, expected) ->
      compose.onNodeWithText(label).performClick()
      assertEquals(expected, destination)
    }
    compose.onNodeWithContentDescription("Settings").performClick()
    assertTrue(settings)
  }
}
