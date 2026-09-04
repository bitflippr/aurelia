package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.aurelia_core.AppException
import uniffi.aurelia_core.AuthRequest
import uniffi.aurelia_core.BackendProvider
import uniffi.aurelia_core.authenticate

class LoginViewModel(
  private val sessionStore: SessionStore,
) : ViewModel() {
  private val mutableState =
    MutableStateFlow(
      LoginState(useDynamicColor = sessionStore.getUseDynamicColor()),
    )
  val state: StateFlow<LoginState> = mutableState

  fun updateServerUrl(value: String) {
    mutableState.update { it.copy(serverUrl = value) }
  }

  fun updateUsername(value: String) {
    mutableState.update { it.copy(username = value) }
  }

  fun updatePassword(value: String) {
    mutableState.update { it.copy(password = value) }
  }

  fun toggleDynamicColor(enabled: Boolean) {
    sessionStore.setUseDynamicColor(enabled)
    mutableState.update { it.copy(useDynamicColor = enabled) }
  }

  fun submit() {
    val current = mutableState.value
    if (current.serverUrl.isBlank() || current.username.isBlank() || current.password.isBlank()) {
      mutableState.update { it.copy(error = "All fields are required") }
      return
    }

    mutableState.update { it.copy(isSubmitting = true, error = null) }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val resolvedProvider = BackendProvider.JELLYFIN

        val response =
          authenticate(
            AuthRequest(
              provider = resolvedProvider,
              serverUrl = current.serverUrl.trim(),
              username = current.username,
              password = current.password,
              deviceId = sessionStore.getDeviceId(),
            ),
          )
        sessionStore.save(
          serverUrl = current.serverUrl.trim(),
          userId = response.userId,
          token = response.token,
          username = current.username,
          provider = resolvedProvider,
        )
        mutableState.update {
          it.copy(
            isSubmitting = false,
            token = response.token,
            userId = response.userId,
          )
        }
      } catch (error: AppException) {
        mutableState.update {
          it.copy(isSubmitting = false, error = error.message ?: "Login failed")
        }
      } catch (error: Exception) {
        mutableState.update {
          it.copy(isSubmitting = false, error = "Unexpected error: ${error.message}")
        }
      }
    }
  }
}
