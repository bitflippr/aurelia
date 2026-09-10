package com.aurelia.app.player.auto

import android.util.Log
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.validateSession
import uniffi.aurelia_core.Playlist
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.buildAndroidStreamUrl
import uniffi.aurelia_core.fetchSongs
import uniffi.aurelia_core.getPlaylistItems
import uniffi.aurelia_core.getPlaylists
import uniffi.aurelia_core.getRecentlyPlayed
import uniffi.aurelia_core.loadCachedSongs

internal class AutoMediaUnavailableException(
  val reason: Reason,
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause) {
  enum class Reason {
    SIGNED_OUT,
    LOAD_FAILED,
  }
}

internal class JellyfinAutoMediaSource(
  private val sessionStore: SessionStore,
) : AutoMediaSource {
  override suspend fun songs(): List<Song> {
    val session = requireSession()
    val cachedSongs =
      session.appDataDir
        ?.takeIf { it.isNotBlank() }
        ?.let { appDataDir ->
          try {
            loadCachedSongs(appDataDir)
          } catch (error: Exception) {
            Log.w(TAG, "Could not load the cached library for Android Auto", error)
            emptyList()
          }
        }.orEmpty()
    if (cachedSongs.isNotEmpty()) return cachedSongs

    return load("Could not load the music library") {
      fetchSongs(session.serverUrl, session.token, session.userId, session.appDataDir.orEmpty())
    }
  }

  override suspend fun recentlyPlayed(): List<Song> {
    val session = requireSession()
    return load("Could not load recently played songs") {
      getRecentlyPlayed(session.serverUrl, session.token, session.userId)
    }
  }

  override suspend fun playlists(): List<Playlist> {
    val session = requireSession()
    return load("Could not load playlists") {
      getPlaylists(session.serverUrl, session.token, session.userId)
    }
  }

  override suspend fun playlistSongs(playlistId: String): List<Song> {
    val session = requireSession()
    return load("Could not load the playlist") {
      getPlaylistItems(session.serverUrl, session.token, playlistId)
    }
  }

  override fun streamUrl(song: Song): String {
    val session = requireSession()
    return buildAndroidStreamUrl(session.serverUrl, session.token, song.id, song.container, song.codec)
  }

  private fun requireSession() =
    validateSession(sessionStore)
      ?: throw AutoMediaUnavailableException(
        AutoMediaUnavailableException.Reason.SIGNED_OUT,
        "Sign in to Aurelia on your phone",
      )

  private suspend fun <T> load(
    message: String,
    block: suspend () -> T,
  ): T =
    try {
      block()
    } catch (error: AutoMediaUnavailableException) {
      throw error
    } catch (error: Exception) {
      throw AutoMediaUnavailableException(AutoMediaUnavailableException.Reason.LOAD_FAILED, message, error)
    }

  private companion object {
    const val TAG = "AutoMediaSource"
  }
}
