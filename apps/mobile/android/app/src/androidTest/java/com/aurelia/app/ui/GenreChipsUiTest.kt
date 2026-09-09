package com.aurelia.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

class GenreChipsUiTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun fittedRowsKeepReadableLabelsFillBothEdgesAndPlayTheSelectedGenre() {
    val genres = listOf("Pop", "Hip-Hop", "Electronic", "R&B", "Alternative", "Indie Rock", "Jazz", "Classical")
    val size = mutableStateOf(280.dp)
    val scale = mutableStateOf(1f)
    var selected: String? = null
    compose.setContent {
      val density = LocalDensity.current.density
      CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
        MaterialTheme(typography = rememberAureliaTypography()) {
          Column(Modifier.width(size.value).verticalScroll(rememberScrollState())) {
            GenreChips(genres, { selected = it }, Modifier.testTag("genres"))
          }
        }
      }
    }
    for (width in listOf(280.dp, 340.dp)) {
      for (fontScale in listOf(1f, 1.5f, 2f)) {
        compose.runOnIdle {
          size.value = width
          scale.value = fontScale
        }
        val bounds = compose.onNodeWithTag("genres").getUnclippedBoundsInRoot()
        val chips = genres.map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        chips.groupBy { it.top }.values.forEach { row ->
          assertEquals(bounds.left.value, row.first().left.value, 0.5f)
          assertEquals(bounds.right.value, row.last().right.value, 0.5f)
          assertTrue(row.all { it.bottom - it.top >= 48.dp })
        }
        genres.forEach { genre ->
          val layouts = mutableListOf<TextLayoutResult>()
          compose.onNodeWithText(genre, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
          ) { it(layouts) }
          assertFalse("$genre at $width / $fontScale", layouts.single().isLineEllipsized(0))
        }
      }
    }
    compose.onNodeWithText("Classical").performScrollTo().performClick()
    assertEquals("Classical", selected)
  }
}
