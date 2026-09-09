package com.aurelia.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.audio.AudioManager
import com.aurelia.app.audio.EQPresets
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_core.clearCache
import uniffi.aurelia_core.getSyncState
import uniffi.aurelia_core.syncLibrarySmart
import java.time.Duration
import java.time.Instant

data class SettingsState(
  val isSyncing: Boolean = false,
  val isClearing: Boolean = false,
  val syncSuccess: Boolean? = null,
  val clearSuccess: Boolean? = null,
  val error: String? = null,
  val lastSyncTime: String? = null,
  val autoSyncEnabled: Boolean = true,
  val syncIntervalHours: Long = 24,
  val eqEnabled: Boolean = false,
  val eqBands: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
  val eqPreset: String? = null,
  val visualizerEnabled: Boolean = false,
  val visualizerStyle: String = "BARS",
)

class SettingsViewModel(
  private val sessionStore: SessionStore,
) : ViewModel() {
  private val mutableState = MutableStateFlow(SettingsState())
  val state: StateFlow<SettingsState> = mutableState

  init {
    loadSyncState()
    loadEQState()
    loadVisualizerState()
  }

  private fun loadSyncState() {
    val appDataDir = sessionStore.getAppDataDir()
    if (appDataDir.isNullOrBlank()) return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val syncState = getSyncState(appDataDir)
        mutableState.update {
          it.copy(lastSyncTime = syncState.lastSyncTime)
        }
      } catch (_: Exception) {
        // Ignore errors loading sync state
      }
    }
  }

  fun syncLibrary() {
    val serverUrl = sessionStore.getServerUrl()
    val userId = sessionStore.getUserId()
    val token = sessionStore.getToken()
    val appDataDir = sessionStore.getAppDataDir()

    if (serverUrl.isNullOrBlank() || userId.isNullOrBlank() || token.isNullOrBlank()) {
      mutableState.update { it.copy(error = "Missing session data") }
      return
    }

    mutableState.update { it.copy(isSyncing = true, error = null, syncSuccess = null) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Smart sync: paginated + incremental (songs, albums, artists)
        syncLibrarySmart(serverUrl, token, userId, appDataDir ?: "")
        sessionStore.snapshot()?.takeIf { it.appDataDir == appDataDir }?.let {
          sessionStore.library.reloadFromCache(it)
        }
        loadSyncState() // Refresh sync state after sync
        mutableState.update { it.copy(isSyncing = false, syncSuccess = true) }
      } catch (e: Exception) {
        if (!AuthInterceptor.handlePotentialAuthError(e)) {
          mutableState.update {
            it.copy(isSyncing = false, syncSuccess = false, error = e.message ?: "Sync failed")
          }
        }
      }
    }
  }

  fun clearLocalCache() {
    val appDataDir = sessionStore.getAppDataDir()

    if (appDataDir.isNullOrBlank()) {
      mutableState.update { it.copy(error = "No cache directory") }
      return
    }

    mutableState.update { it.copy(isClearing = true, error = null, clearSuccess = null) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        clearCache(appDataDir)
        sessionStore.reads.clear()
        sessionStore.library.clear()
        mutableState.update { it.copy(isClearing = false, clearSuccess = true, lastSyncTime = null) }
      } catch (e: Exception) {
        mutableState.update {
          it.copy(isClearing = false, clearSuccess = false, error = e.message ?: "Clear failed")
        }
      }
    }
  }

  fun setAutoSyncEnabled(
    context: Context,
    enabled: Boolean,
  ) {
    mutableState.update { it.copy(autoSyncEnabled = enabled) }
    if (enabled) {
      SyncWorker.schedule(context, mutableState.value.syncIntervalHours)
    } else {
      SyncWorker.cancel(context)
    }
  }

  fun setSyncInterval(
    context: Context,
    hours: Long,
  ) {
    mutableState.update { it.copy(syncIntervalHours = hours) }
    if (mutableState.value.autoSyncEnabled) {
      SyncWorker.schedule(context, hours)
    }
  }

  fun clearMessages() {
    mutableState.update { it.copy(syncSuccess = null, clearSuccess = null, error = null) }
  }

  fun setEQEnabled(enabled: Boolean) {
    AudioManager.setEQEnabled(enabled, sessionStore)
    mutableState.update { it.copy(eqEnabled = enabled) }
  }

  fun setEQBandGain(
    bandIndex: Int,
    gain: Float,
  ) {
    if (bandIndex !in 0..4) return
    AudioManager.setEQBandGain(bandIndex, gain, sessionStore)
    val newBands = mutableState.value.eqBands.toMutableList()
    newBands[bandIndex] = gain
    mutableState.update { it.copy(eqBands = newBands, eqPreset = null) }
  }

  fun applyEQPreset(presetName: String) {
    val preset = EQPresets.byName(presetName) ?: return
    AudioManager.applyEQPreset(presetName, sessionStore)
    mutableState.update { it.copy(eqBands = preset.gains, eqPreset = presetName) }
  }

  fun resetEQ() {
    AudioManager.applyEQPreset("Flat", sessionStore)
    mutableState.update { it.copy(eqBands = listOf(0f, 0f, 0f, 0f, 0f), eqPreset = "Flat") }
  }

  private fun loadEQState() {
    mutableState.update {
      it.copy(
        eqEnabled = sessionStore.getEQEnabled(),
        eqBands = sessionStore.getEQBands(),
        eqPreset = sessionStore.getEQPreset(),
      )
    }
  }

  fun setVisualizerEnabled(enabled: Boolean) {
    AudioManager.setVisualizerEnabled(enabled, sessionStore)
    val appliedValue = sessionStore.getVisualizerEnabled()
    mutableState.update { it.copy(visualizerEnabled = appliedValue) }
  }

  fun setVisualizerStyle(style: String) {
    sessionStore.setVisualizerStyle(style)
    mutableState.update { it.copy(visualizerStyle = style) }
  }

  fun onVisualizerPermissionChanged(hasPermission: Boolean) {
    AudioManager.onVisualizerPermissionChanged(hasPermission, sessionStore)
    if (!hasPermission) {
      mutableState.update { it.copy(visualizerEnabled = false) }
    }
  }

  private fun loadVisualizerState() {
    mutableState.update {
      it.copy(
        visualizerEnabled = sessionStore.getVisualizerEnabled(),
        visualizerStyle = sessionStore.getVisualizerStyle(),
      )
    }
  }

  companion object {
    fun formatRelativeTime(isoTime: String?): String {
      if (isoTime.isNullOrBlank()) return "Never"

      return try {
        val syncTime = Instant.parse(isoTime)
        val now = Instant.now()
        val duration = Duration.between(syncTime, now)

        when {
          duration.toMinutes() < 1 -> "Just now"
          duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
          duration.toHours() < 24 -> "${duration.toHours()}h ago"
          duration.toDays() < 7 -> "${duration.toDays()}d ago"
          else -> syncTime.toString().take(10) // Date only
        }
      } catch (_: Exception) {
        "Unknown"
      }
    }
  }
}
