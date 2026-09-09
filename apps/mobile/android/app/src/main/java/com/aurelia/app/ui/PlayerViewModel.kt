package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.data.model.Lyrics
import com.aurelia.app.data.model.LyricsAgent
import com.aurelia.app.data.model.LyricsSection
import com.aurelia.app.data.model.SyncedLine
import com.aurelia.app.data.model.SyncedWord
import com.aurelia.app.player.PlayerController
import com.aurelia.app.player.PlayerSnapshot
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_lyrics.ParsedLyrics
import uniffi.aurelia_lyrics.ParsedLyricsLine
import uniffi.aurelia_core.toggleFavorite as uniffiToggleFavorite

class PlayerViewModel(
  private val playerController: PlayerController,
  private val sessionStore: SessionStore,
) : ViewModel() {
  private val mutableState = MutableStateFlow(PlayerState())
  val state: StateFlow<PlayerState> = mutableState

  private var lastFetchedSongId: String? = null
  private var favoriteCache: MutableMap<String, Boolean> = mutableMapOf()

  init {
    viewModelScope.launch {
      playerController.snapshots.collect { snapshot ->
        val newSongId = snapshot.currentSongId

        // Update favorite cache from queue songs
        val queue = playerController.getQueue()
        queue.forEach { song ->
          if (!favoriteCache.containsKey(song.id)) {
            favoriteCache[song.id] = song.isFavorite ?: false
          }
        }

        mutableState.update { current ->
          val isFavorite = newSongId?.let { favoriteCache[it] } ?: false
          current.fromSnapshot(snapshot, isFavorite)
        }

        if (newSongId != null && newSongId.isNotBlank() && newSongId != lastFetchedSongId) {
          lastFetchedSongId = newSongId
          fetchLyrics(newSongId, snapshot.artist, snapshot.title)
        }
      }
    }
  }

  private fun fetchLyrics(
    songId: String?,
    artist: String,
    title: String,
    force: Boolean = false,
  ) {
    val session = validateSession(sessionStore) ?: return
    if (songId.isNullOrBlank()) return
    viewModelScope.launch(Dispatchers.IO) {
      lastFetchedSongId = songId
      mutableState.update { it.copy(lyrics = null) }
      try {
        val lyrics = sessionStore.reads.lyrics(session, songId, artist, title, force).toUiLyrics()

        // Ensure we are still playing the same song
        if (songId == mutableState.value.currentSongId && session.appDataDir == sessionStore.getAppDataDir()) {
          if (lyrics.isValid()) {
            mutableState.update { it.copy(lyrics = lyrics) }
          } else {
            mutableState.update { it.copy(showLyrics = false) }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (songId == mutableState.value.currentSongId) {
          mutableState.update { it.copy(showLyrics = false) }
        }
        // Only handle auth errors, ignore others (e.g., lyrics not found)
        AuthInterceptor.handlePotentialAuthError(e)
      }
    }
  }

  fun togglePlayPause() {
    val currentState = mutableState.value
    if (currentState.isPlaying) {
      playerController.pause()
    } else {
      playerController.resume()
    }
  }

  fun seekTo(positionMs: Long) {
    playerController.seekTo(positionMs)
  }

  fun skipNext() {
    playerController.skipNext()
  }

  fun skipPrevious() {
    playerController.skipPrevious()
  }

  private fun PlayerState.fromSnapshot(
    snapshot: PlayerSnapshot,
    isFavorite: Boolean,
  ): PlayerState =
    copy(
      title = snapshot.title,
      artist = snapshot.artist,
      albumArtUrl = snapshot.albumArtUrl,
      isPlaying = snapshot.isPlaying,
      isBuffering = snapshot.isBuffering,
      positionMs = snapshot.positionMs,
      durationMs = snapshot.durationMs,
      queue = playerController.getQueue(),
      currentQueueIndex = playerController.getCurrentQueueIndex(),
      isShuffled = snapshot.isShuffled,
      repeatMode = snapshot.repeatMode,
      currentSongId = snapshot.currentSongId,
      currentAlbumId = snapshot.currentAlbumId,
      currentArtistId = snapshot.currentArtistId,
      currentAlbumName = snapshot.currentAlbumName,
      isFavorite = isFavorite,
      playbackSpeed = snapshot.playbackSpeed,
      updateTimeMs = snapshot.updateTimeMs,
      codec = snapshot.codec,
      bitRate = snapshot.bitRate,
      sampleRate = snapshot.sampleRate,
      formatInfo = formatAudioInfo(snapshot.codec, snapshot.sampleRate, snapshot.bitRate),
    )

  private fun formatAudioInfo(
    codec: String?,
    sampleRate: Int?,
    bitRate: Int?,
  ): String? {
    val parts = mutableListOf<String>()
    codec?.let {
      val codecDisplay = it.substringAfterLast('/')
      parts.add(codecDisplay.uppercase())
    }
    sampleRate?.let { parts.add("${it / 1000.0} kHz") }
    bitRate?.let {
      val bitRateBps = if (it in 1 until 10_000) it * 1000 else it
      parts.add("${bitRateBps / 1000} kbps")
    }
    return if (parts.isEmpty()) null else parts.joinToString(" / ")
  }

  private fun ParsedLyricsLine.toUiSyncedLine(): SyncedLine =
    SyncedLine(
      time = timeMs.toInt(),
      endTime = endTimeMs?.toInt(),
      line = line,
      words =
        words?.map { word ->
          SyncedWord(
            time = word.timeMs.toInt(),
            endTime = word.endTimeMs?.toInt(),
            word = word.word,
          )
        },
      agentId = agentId,
      translation = translation,
    )

  private fun ParsedLyrics.toUiLyrics(): Lyrics {
    val syncedLines =
      synced
        .takeIf { it.isNotEmpty() }
        ?.map { it.toUiSyncedLine() }

    val uiSections =
      sections?.takeIf { it.isNotEmpty() }?.map { section ->
        LyricsSection(
          name = section.name,
          startTime = section.startTimeMs.toInt(),
          endTime = section.endTimeMs.toInt(),
          lines = section.lines.map { it.toUiSyncedLine() },
          agentId = section.agentId,
        )
      }

    val uiAgents =
      agents?.takeIf { it.isNotEmpty() }?.map { agent ->
        LyricsAgent(id = agent.id, agentType = agent.agentType)
      }

    val plainLines = plain.takeIf { it.isNotEmpty() }

    return Lyrics(
      plain = plainLines,
      synced = syncedLines,
      sections = uiSections,
      agents = uiAgents,
      songwriters = songwriters?.takeIf { it.isNotEmpty() },
      language = language,
      areFromRemote = areFromRemote,
    )
  }

  fun playQueueItem(index: Int) {
    playerController.playQueueItem(index)
  }

  fun toggleLyrics() {
    val currentState = mutableState.value
    if (currentState.lyrics == null && currentState.title.isNotBlank()) {
      fetchLyrics(currentState.currentSongId, currentState.artist, currentState.title, force = true)
    }
    mutableState.update { it.copy(showLyrics = !it.showLyrics) }
  }

  fun setLyrics(lyrics: Lyrics?) {
    mutableState.update { it.copy(lyrics = lyrics) }
  }

  fun clearLyrics() {
    mutableState.update { it.copy(lyrics = null, showLyrics = false) }
  }

  fun toggleShuffle() {
    playerController.toggleShuffle()
  }

  fun cycleRepeatMode() {
    playerController.cycleRepeatMode()
  }

  fun toggleFavorite() {
    val currentState = mutableState.value
    val songId = currentState.currentSongId ?: return

    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val userId = sessionStore.getUserId() ?: return
    val targetFavoriteState = !currentState.isFavorite

    mutableState.update { it.copy(isFavoriteLoading = true) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val newFavoriteState =
          uniffiToggleFavorite(
            serverUrl = serverUrl,
            token = token,
            userId = userId,
            itemId = songId,
            isFavorite = targetFavoriteState,
          )
        favoriteCache[songId] = newFavoriteState
        mutableState.update {
          it.copy(isFavorite = newFavoriteState, isFavoriteLoading = false)
        }
      } catch (e: Exception) {
        if (!AuthInterceptor.handlePotentialAuthError(e)) {
          android.util.Log.e("PlayerViewModel", "Failed to toggle favorite", e)
          mutableState.update { it.copy(isFavoriteLoading = false) }
        }
      }
    }
  }

  fun setFavoriteFromSong(
    songId: String,
    isFavorite: Boolean,
  ) {
    favoriteCache[songId] = isFavorite
    if (mutableState.value.currentSongId == songId) {
      mutableState.update { it.copy(isFavorite = isFavorite) }
    }
  }
}
