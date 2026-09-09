package com.aurelia.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreRowsTest {
  @Test
  fun rowsFillEveryWidthAndPreserveEveryLabelAtReadableWidths() {
    val natural = listOf(68f, 106f, 125f, 93f, 154f, 114f, 75f, 130f)
    for (width in listOf(240f, 320f, 390f, 460f, 800f)) {
      for (scale in listOf(1f, 1.5f, 2f)) {
        val minimum = natural.map { it * scale * 0.65f }
        val rows = fitGenreRows(natural.map { it * scale }, minimum, width, 8f)
        assertEquals(natural.indices.toList(), rows.flatMap { row -> row.widths.indices.map { row.start + it } })
        rows.forEach { row ->
          assertEquals(width, row.widths.sum() + 8f * (row.widths.size - 1), 0.001f)
          row.widths.forEachIndexed { i, cell ->
            assertTrue(cell > 0f)
            assertTrue(row.widths.size == 1 || cell >= minimum[row.start + i])
          }
        }
      }
    }
  }

  @Test
  fun breaksAdaptToSpaceAndLabelWidths() {
    val natural = listOf(68f, 106f, 125f, 93f, 154f, 114f, 75f, 130f)
    val minimum = natural.map { it * 0.65f }
    val narrow = fitGenreRows(natural, minimum, 280f, 8f)
    val wide = fitGenreRows(natural, minimum, 460f, 8f)
    assertTrue(narrow.size > wide.size)
    assertTrue(narrow.all { it.widths.size > 1 })
    assertTrue(narrow.any { it.widths.distinct().size > 1 })
  }

  @Test
  fun overlongLabelGetsItsOwnRowWithoutDroppingOtherGenres() {
    val rows = fitGenreRows(listOf(80f, 1800f, 90f), listOf(60f, 900f, 65f), 320f, 8f)
    assertEquals(3, rows.sumOf { it.widths.size })
    assertEquals(listOf(320f), rows.single { it.start == 1 }.widths)
    assertTrue(fitGenreRows(emptyList(), emptyList(), 320f, 8f).isEmpty())
  }
}
