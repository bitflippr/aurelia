package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppViewModel(
  private val sessionStore: SessionStore,
) : ViewModel() {
  private val mutableState = MutableStateFlow(checkSessionState(sessionVersion = 0))
  val state: StateFlow<AppState> = mutableState

  private fun checkSessionState(sessionVersion: Int): AppState {
    val hasSession =
      !sessionStore.getServerUrl().isNullOrBlank() &&
        !sessionStore.getUserId().isNullOrBlank() &&
        !sessionStore.getToken().isNullOrBlank()
    val appDataDir = sessionStore.getAppDataDir()
    val isInitialSyncComplete =
      if (hasSession && !appDataDir.isNullOrBlank()) {
        try {
          val syncState = uniffi.aurelia_core.getSyncState(appDataDir)
          syncState.lastSyncTime != "1970-01-01T00:00:00Z"
        } catch (e: Exception) {
          false
        }
      } else {
        false
      }
    return AppState(
      isLoading = false,
      isLoggedIn = hasSession,
      isInitialSyncComplete = isInitialSyncComplete,
      sessionVersion = sessionVersion,
    )
  }

  fun checkSession() {
    mutableState.value = checkSessionState(mutableState.value.sessionVersion)
  }

  fun refreshForSessionSwitch() {
    val nextVersion = mutableState.value.sessionVersion + 1
    mutableState.value = checkSessionState(nextVersion)
  }
}
