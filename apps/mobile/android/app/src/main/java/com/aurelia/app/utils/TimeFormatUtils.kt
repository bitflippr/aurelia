package com.aurelia.app.utils

/**
 * Formats a duration in milliseconds to "m:ss" format.
 *
 * @param durationMs The duration in milliseconds
 * @param clampNegative Whether to clamp negative values to 0 (default: false)
 * @return Formatted string in "m:ss" format
 */
fun formatDuration(
  durationMs: Long,
  clampNegative: Boolean = false,
): String {
  val totalSeconds = (if (clampNegative) durationMs.coerceAtLeast(0L) else durationMs) / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%d:%02d".format(minutes, seconds)
}
