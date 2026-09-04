package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.validateSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.aurelia_core.getSyncProgress
import uniffi.aurelia_core.syncLibrarySmart

data class SetupState(
  val isSyncing: Boolean = false,
  val stage: String = "Initializing",
  val current: Int = 0,
  val total: Int = 0,
  val isComplete: Boolean = false,
  val isSuccess: Boolean = false,
  val error: String? = null,
)

class SetupViewModel(
  private val sessionStore: SessionStore,
) : ViewModel() {
  private val _state = MutableStateFlow(SetupState())
  val state: StateFlow<SetupState> = _state.asStateFlow()

  fun syncLibrary() {
    if (_state.value.isSyncing) return
    val session = validateSession(sessionStore, requireAppDataDir = true)
    if (session == null) {
      _state.value = SetupState(error = "Missing session data")
      return
    }
    val serverUrl = session.serverUrl
    val userId = session.userId
    val token = session.token
    val appDataDir = session.appDataDir.orEmpty()

    _state.value = SetupState(isSyncing = true)

    // Launch the sync operation
    viewModelScope.launch(Dispatchers.IO) {
      val pollingJob =
        launch {
          while (isActive) {
            delay(500)
            try {
              val progress = getSyncProgress(appDataDir)
              _state.value =
                _state.value.copy(
                  stage = progress.stage,
                  current = progress.current.toInt(),
                  total = progress.total.toInt(),
                  isComplete = progress.isComplete,
                )
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              // Ignore polling errors
            }
          }
        }

      try {
        try {
          syncLibrarySmart(serverUrl, token, userId, appDataDir)
        } finally {
          pollingJob.cancelAndJoin()
        }

        _state.value =
          SetupState(
            isSyncing = false,
            stage = "Complete",
            isComplete = true,
            isSuccess = true,
          )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (!AuthInterceptor.handlePotentialAuthError(e)) {
          _state.value =
            SetupState(
              isSyncing = false,
              error = e.message ?: "Synchronization failed",
            )
        }
      }
    }
  }
}
