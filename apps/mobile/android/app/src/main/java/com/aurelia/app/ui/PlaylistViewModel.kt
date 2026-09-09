package com.aurelia.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.ai.AiGenerationState
import com.aurelia.app.ai.AiModelDownloadState
import com.aurelia.app.ai.AiModelDownloader
import com.aurelia.app.ai.GemmaPlaylistGenerator
import com.aurelia.app.ai.GemmaRuntimeConfig
import com.aurelia.app.ai.OnDeviceAiModels
import com.aurelia.app.ai.SmartPlaylistPlanner
import com.aurelia.app.ai.SmartPlaylistPreview
import com.aurelia.app.ai.SmartPlaylistRequest
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.SessionData
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_core.AppException
import uniffi.aurelia_core.PlaylistCreateData
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.addPlaylistItems
import uniffi.aurelia_core.createPlaylist
import uniffi.aurelia_core.deletePlaylist
import java.io.File

class PlaylistViewModel(
  private val sessionStore: SessionStore,
  private val playerController: PlayerController,
) : ViewModel() {
  private val mutableState = MutableStateFlow(PlaylistsState())
  val state: StateFlow<PlaylistsState> = mutableState

  private val mutableDetailState = MutableStateFlow(PlaylistDetailState())
  val detailState: StateFlow<PlaylistDetailState> = mutableDetailState

  private val mutableSmartPlaylistState = MutableStateFlow(SmartPlaylistState())
  val smartPlaylistState: StateFlow<SmartPlaylistState> = mutableSmartPlaylistState

  private var loadJob: Job? = null
  private var loadedSession: SessionData? = null
  private var detailJob: Job? = null
  private val onDevicePlaylistGenerator by lazy { GemmaPlaylistGenerator() }
  private val aiModelDownloader by lazy { AiModelDownloader() }

  fun ensureLoaded(force: Boolean = false) {
    if (!force && loadJob?.isActive == true) return
    val session = validateSession(sessionStore)
    if (session == null) {
      mutableState.update { it.copy(error = "Missing session data", isLoading = false) }
      return
    }
    if (!force && loadedSession == session) return
    loadJob?.cancel()

    mutableState.update { it.copy(isLoading = true, error = null) }

    loadJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val playlists = sessionStore.reads.playlists(session, force)
          if (session.appDataDir != sessionStore.getAppDataDir()) return@launch
          mutableState.update { it.copy(isLoading = false, playlists = playlists) }
          loadedSession = session
        } catch (error: CancellationException) {
          throw error
        } catch (error: AppException) {
          if (!AuthInterceptor.handlePotentialAuthError(error)) {
            mutableState.update { it.copy(isLoading = false, error = error.message ?: "Failed to load playlists") }
          }
        } catch (error: Exception) {
          if (!AuthInterceptor.handlePotentialAuthError(error)) {
            mutableState.update { it.copy(isLoading = false, error = "Failed to load playlists") }
          }
        }
      }
  }

  fun loadPlaylists() {
    ensureLoaded(force = false)
  }

  fun refreshAiModelState() {
    val path = sessionStore.getOnDeviceAiModelPath()
    val state =
      if (!path.isNullOrBlank() && File(path).exists()) {
        AiModelDownloadState.Ready(path)
      } else {
        AiModelDownloadState.Missing(path)
      }
    mutableSmartPlaylistState.update { it.copy(modelDownload = state) }
  }

  fun downloadAiModel() {
    val current = mutableSmartPlaylistState.value.modelDownload
    if (current is AiModelDownloadState.Downloading) return

    val modelsDir = sessionStore.getOnDeviceAiModelsDir()
    if (modelsDir.isNullOrBlank()) {
      mutableSmartPlaylistState.update {
        it.copy(modelDownload = AiModelDownloadState.Error("Model folder is not available"))
      }
      return
    }

    val model = OnDeviceAiModels.default
    mutableSmartPlaylistState.update {
      it.copy(modelDownload = AiModelDownloadState.Downloading(model.name, 0L, null))
    }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val downloadedFile =
          aiModelDownloader.download(model, File(modelsDir)) { bytesRead, totalBytes ->
            mutableSmartPlaylistState.update {
              it.copy(modelDownload = AiModelDownloadState.Downloading(model.name, bytesRead, totalBytes))
            }
          }
        sessionStore.setOnDeviceAiModelPath(downloadedFile.absolutePath)
        mutableSmartPlaylistState.update {
          it.copy(modelDownload = AiModelDownloadState.Ready(downloadedFile.absolutePath))
        }
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.w("PlaylistViewModel", "Failed to download AI model", error)
        mutableSmartPlaylistState.update {
          it.copy(modelDownload = AiModelDownloadState.Error(error.message ?: "Model download failed"))
        }
      }
    }
  }

  fun createPlaylist(
    name: String,
    songIds: List<String>? = null,
  ) {
    val session = validateSession(sessionStore) ?: return

    mutableState.update { it.copy(isCreating = true) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val data =
          PlaylistCreateData(
            name = name,
            ids = songIds,
            userId = session.userId,
            isPublic = false,
          )
        val newPlaylist = createPlaylist(session.serverUrl, session.token, data)
        mutableState.update { current ->
          current.copy(
            isCreating = false,
            playlists = current.playlists + newPlaylist,
          )
        }
        ensureLoaded(force = true)
      } catch (error: Exception) {
        mutableState.update { it.copy(isCreating = false, error = "Failed to create playlist") }
      }
    }
  }

  fun deletePlaylist(playlistId: String) {
    val session = validateSession(sessionStore) ?: return

    mutableState.update { it.copy(isDeleting = true) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        deletePlaylist(session.serverUrl, session.token, playlistId)
        sessionStore.reads.forgetPlaylist(session, playlistId)
        mutableState.update { current ->
          current.copy(
            isDeleting = false,
            playlists = current.playlists.filter { it.id != playlistId },
          )
        }
        ensureLoaded(force = true)
      } catch (error: Exception) {
        mutableState.update { it.copy(isDeleting = false, error = "Failed to delete playlist") }
      }
    }
  }

  fun loadPlaylistDetail(
    playlistId: String,
    playlistName: String,
    force: Boolean = false,
  ) {
    val session = validateSession(sessionStore)
    if (session == null) {
      mutableDetailState.update { it.copy(error = "Missing session data", isLoading = false) }
      return
    }

    detailJob?.cancel()
    mutableDetailState.value = PlaylistDetailState(isLoading = true)

    detailJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val songs = sessionStore.reads.playlistItems(session, playlistId, force)
          if (session.appDataDir != sessionStore.getAppDataDir()) return@launch
          // Find the playlist from the main state if available
          val playlist = mutableState.value.playlists.find { it.id == playlistId }
          mutableDetailState.update {
            it.copy(
              isLoading = false,
              playlist = playlist,
              songs = songs,
            )
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: AppException) {
          if (!AuthInterceptor.handlePotentialAuthError(error)) {
            mutableDetailState.update { it.copy(isLoading = false, error = error.message ?: "Failed to load playlist") }
          }
        } catch (error: Exception) {
          if (!AuthInterceptor.handlePotentialAuthError(error)) {
            mutableDetailState.update { it.copy(isLoading = false, error = "Failed to load playlist") }
          }
        }
      }
  }

  fun addSongsToPlaylist(
    playlistId: String,
    songIds: List<String>,
  ) {
    val session = validateSession(sessionStore) ?: return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        addPlaylistItems(session.serverUrl, session.token, playlistId, songIds)
        // Reload playlist detail to reflect changes
        loadPlaylistDetail(playlistId, "", force = true)
        // Also reload playlists to update child count
        ensureLoaded(force = true)
      } catch (error: Exception) {
        android.util.Log.e("PlaylistViewModel", "Failed to add songs to playlist", error)
        mutableState.update { it.copy(error = "Failed to add songs to playlist") }
      }
    }
  }

  fun generateSmartPlaylist(
    request: SmartPlaylistRequest,
    librarySongs: List<Song>,
  ) {
    if (request.prompt.isBlank()) {
      mutableSmartPlaylistState.update {
        it.copy(generation = AiGenerationState.Error("Describe the playlist you want"))
      }
      return
    }
    if (librarySongs.isEmpty()) {
      mutableSmartPlaylistState.update {
        it.copy(generation = AiGenerationState.Error("Sync your library before generating a playlist"))
      }
      return
    }
    refreshAiModelState()
    if (mutableSmartPlaylistState.value.modelDownload !is AiModelDownloadState.Ready) {
      mutableSmartPlaylistState.update {
        it.copy(generation = AiGenerationState.Error("Download the on-device AI model before generating a playlist"))
      }
      return
    }

    mutableSmartPlaylistState.update {
      it.copy(generation = AiGenerationState.Loading())
    }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val candidates = SmartPlaylistPlanner.prepareCandidates(librarySongs, request)
        val modelPath = sessionStore.getOnDeviceAiModelPath()
        val cacheDir = sessionStore.getOnDeviceAiCacheDir()
        val preview =
          onDevicePlaylistGenerator.generate(
            request = request,
            candidates = candidates,
            runtimeConfig =
              GemmaRuntimeConfig(
                modelPath = modelPath,
                cacheDir = cacheDir,
              ),
          )
        mutableSmartPlaylistState.update {
          it.copy(generation = AiGenerationState.Preview(preview))
        }
      } catch (error: Exception) {
        Log.w("PlaylistViewModel", "Failed to generate smart playlist", error)
        mutableSmartPlaylistState.update {
          it.copy(generation = AiGenerationState.Error(error.message ?: error.javaClass.simpleName))
        }
      }
    }
  }

  fun playSmartPlaylist(preview: SmartPlaylistPreview) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    if (preview.songs.isEmpty()) return

    playerController.setQueue(preview.songs, serverUrl, token)
  }

  fun saveSmartPlaylist(preview: SmartPlaylistPreview) {
    val playlistName =
      preview.name
        .trim()
        .ifBlank { "Smart Playlist" }
        .take(80)
    createPlaylist(
      playlistName,
      preview.songs.mapNotNull { runCatching { it.id }.getOrNull() }.filter { it.isNotBlank() },
    )
  }

  fun clearSmartPlaylistGeneration() {
    mutableSmartPlaylistState.update { it.copy(generation = AiGenerationState.Idle) }
  }

  fun playPlaylist(startIndex: Int = 0) {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val songs = mutableDetailState.value.songs

    if (songs.isEmpty()) return

    playerController.setQueue(songs, serverUrl, token, startIndex)
  }

  fun shufflePlaylist() {
    val serverUrl = sessionStore.getServerUrl() ?: return
    val token = sessionStore.getToken() ?: return
    val songs = mutableDetailState.value.songs.shuffled()

    if (songs.isEmpty()) return

    playerController.setQueue(songs, serverUrl, token)
  }

  fun clearError() {
    mutableState.update { it.copy(error = null) }
    mutableDetailState.update { it.copy(error = null) }
  }
}
