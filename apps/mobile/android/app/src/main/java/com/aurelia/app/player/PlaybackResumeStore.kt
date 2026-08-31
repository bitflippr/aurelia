package com.aurelia.app.player

import android.content.Context
import androidx.core.content.edit

internal data class PlaybackResumeState(
  val mediaId: String,
  val positionMs: Long,
)

internal class PlaybackResumeStore(
  context: Context,
) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): PlaybackResumeState? {
    val mediaId = preferences.getString(KEY_MEDIA_ID, null)?.takeIf { it.isNotBlank() } ?: return null
    return PlaybackResumeState(mediaId, preferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L))
  }

  fun save(
    mediaId: String,
    positionMs: Long,
  ) {
    if (mediaId.isBlank()) return
    preferences.edit {
      putString(KEY_MEDIA_ID, mediaId)
      putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
    }
  }

  fun clear() {
    preferences.edit { clear() }
  }

  private companion object {
    const val PREFERENCES_NAME = "aurelia_playback_resume"
    const val KEY_MEDIA_ID = "media_id"
    const val KEY_POSITION_MS = "position_ms"
  }
}
