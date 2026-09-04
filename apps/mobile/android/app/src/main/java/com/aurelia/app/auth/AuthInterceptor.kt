package com.aurelia.app.auth

import android.os.Handler
import android.os.Looper
import android.util.Log
import uniffi.aurelia_core.AppException

object AuthInterceptor {
  private const val TAG = "AuthInterceptor"
  private var logoutCallback: (() -> Unit)? = null

  fun setLogoutCallback(callback: () -> Unit) {
    logoutCallback = callback
  }

  fun clearLogoutCallback() {
    logoutCallback = null
  }

  fun isUnauthorizedError(error: Throwable): Boolean = error is AppException.Http && error.status.toInt() == 401

  fun handlePotentialAuthError(error: Throwable): Boolean {
    if (isUnauthorizedError(error)) {
      Log.w(TAG, "Unauthorized error detected, triggering logout")
      triggerLogout()
      return true
    }
    return false
  }

  private fun triggerLogout() {
    Handler(Looper.getMainLooper()).post { logoutCallback?.invoke() }
  }
}
