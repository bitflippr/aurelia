package com.aurelia.app.ui

import kotlin.math.ln
import kotlin.math.pow

internal data class GenreRow(
  val start: Int,
  val widths: List<Float>,
  val ratio: Float,
)

/** Fits all row breaks together, keeping order and avoiding a stretched leftover last row. */
internal fun fitGenreRows(
  naturalWidths: List<Float>,
  minimumWidths: List<Float>,
  width: Float,
  gap: Float,
): List<GenreRow> {
  if (naturalWidths.isEmpty() || width <= 0f) return emptyList()
  val costs = DoubleArray(naturalWidths.size + 1) { Double.POSITIVE_INFINITY }
  val rows = arrayOfNulls<GenreRow>(naturalWidths.size + 1)
  costs[0] = 0.0
  for (end in 1..naturalWidths.size) {
    for (start in 0 until end) {
      val count = end - start
      val available = width - gap * (count - 1)
      if (available <= 0f) continue
      val natural = naturalWidths.subList(start, end)
      val ratio = available / natural.sum()
      // A single overlong label still gets a row; its Text can ellipsize accessibly.
      if (count > 1 && natural.indices.any { natural[it] * ratio < minimumWidths[start + it] }) continue
      val distortion = ln(ratio.toDouble())
      var cost = 0.065 + distortion * distortion * if (ratio < 1f) 3 else 2
      if (ratio < 0.72f) cost += 25 * (0.72 - ratio).pow(2)
      if (ratio > 1.5f) cost += 12 * (ratio - 1.5).pow(2)
      if (count == 1 && naturalWidths.size > 1) cost += 0.5
      val candidate = costs[start] + cost
      if (candidate < costs[end]) {
        costs[end] = candidate
        rows[end] = GenreRow(start, natural.map { it * ratio }, ratio)
      }
    }
  }
  val result = mutableListOf<GenreRow>()
  var end = naturalWidths.size
  while (end > 0) {
    val row = checkNotNull(rows[end])
    result.add(row)
    end = row.start
  }
  return result.asReversed()
}
