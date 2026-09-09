package com.aurelia.app.storage

import com.aurelia.app.utils.SessionData
import uniffi.aurelia_core.Artist
import uniffi.aurelia_core.Playlist
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.fetchArtist
import uniffi.aurelia_core.getParsedLyrics
import uniffi.aurelia_core.getPlaylistItems
import uniffi.aurelia_core.getPlaylists
import uniffi.aurelia_core.syncLibrarySmart
import uniffi.aurelia_lyrics.ParsedLyrics

/** Session-owned server reads. UI recreation joins these requests instead of starting new ones. */
class SessionReads {
  private val playlists = SessionReadCache<SessionData, List<Playlist>>()
  private val playlistItems = SessionReadCache<Pair<SessionData, String>, List<Song>>()
  private val artists = SessionReadCache<Pair<SessionData, String>, Artist>()
  private val lyrics = SessionReadCache<Pair<SessionData, String>, ParsedLyrics>()
  private val initialSync = SessionReadCache<SessionData, Unit>()

  suspend fun playlists(
    session: SessionData,
    force: Boolean = false,
  ): List<Playlist> = playlists.get(session, force) { getPlaylists(session.serverUrl, session.token, session.userId) }

  suspend fun playlistItems(
    session: SessionData,
    playlistId: String,
    force: Boolean = false,
  ): List<Song> =
    playlistItems.get(session to playlistId, force) { getPlaylistItems(session.serverUrl, session.token, playlistId) }

  suspend fun artist(
    session: SessionData,
    artistId: String,
  ): Artist =
    artists.get(session to artistId) {
      fetchArtist(session.serverUrl, session.token, session.userId, artistId, session.appDataDir.orEmpty())
    }

  fun forgetPlaylist(
    session: SessionData,
    playlistId: String,
  ) {
    playlistItems.invalidate(session to playlistId)
  }

  suspend fun lyrics(
    session: SessionData,
    songId: String,
    artist: String,
    title: String,
    force: Boolean = false,
  ): ParsedLyrics =
    lyrics.get(session to songId, force) { getParsedLyrics(session.serverUrl, session.token, songId, artist, title) }

  suspend fun initialSync(
    session: SessionData,
    force: Boolean = false,
  ) {
    initialSync.get(session, force) {
      syncLibrarySmart(session.serverUrl, session.token, session.userId, session.appDataDir.orEmpty())
      Unit
    }
  }

  fun clear() {
    playlists.clear()
    playlistItems.clear()
    artists.clear()
    lyrics.clear()
    initialSync.clear()
  }
}
