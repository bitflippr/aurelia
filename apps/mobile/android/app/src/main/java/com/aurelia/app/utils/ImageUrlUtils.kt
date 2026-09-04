package com.aurelia.app.utils

import android.net.Uri

private val IMAGE_SIZE_QUERY_KEYS =
  setOf(
    "maxWidth",
    "maxHeight",
    "width",
    "height",
    "quality",
  )

/**
 * Adds Jellyfin image sizing parameters so we don't always download full-resolution artwork.
 * Non-Jellyfin URLs are returned unchanged.
 */
fun optimizedArtworkUrl(
  rawUrl: String?,
  targetWidthPx: Int,
  quality: Int = 82,
): String? {
  if (rawUrl.isNullOrBlank()) return rawUrl

  val clampedWidth = targetWidthPx.coerceAtLeast(32)
  val clampedQuality = quality.coerceIn(40, 100)

  val parsed =
    try {
      Uri.parse(rawUrl)
    } catch (_: Exception) {
      return rawUrl
    }

  val path = parsed.encodedPath ?: return rawUrl
  val isJellyfinImagePath = path.contains("/Items/") && path.contains("/Images/")
  if (!isJellyfinImagePath) return rawUrl

  val builder = parsed.buildUpon().clearQuery()
  for (name in parsed.queryParameterNames) {
    if (name in IMAGE_SIZE_QUERY_KEYS) continue
    for (value in parsed.getQueryParameters(name)) {
      builder.appendQueryParameter(name, value)
    }
  }

  return builder
    .appendQueryParameter("maxWidth", clampedWidth.toString())
    .appendQueryParameter("quality", clampedQuality.toString())
    .build()
    .toString()
}

fun jellyfinPrimaryImageUrl(
  serverUrl: String?,
  itemId: String?,
  token: String?,
): String? {
  if (serverUrl.isNullOrBlank() || itemId.isNullOrBlank()) return null

  val base = serverUrl.trimEnd('/')
  val builder = Uri.parse("$base/Items/$itemId/Images/Primary").buildUpon()
  if (!token.isNullOrBlank()) {
    builder.appendQueryParameter("api_key", token)
  }
  return builder.build().toString()
}
