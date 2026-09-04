package com.aurelia.app.utils

import com.aurelia.app.storage.SessionStore

data class SessionData(
  val serverUrl: String,
  val userId: String,
  val token: String,
  val appDataDir: String?,
)

/**
 * Validates that required session credentials are present.
 * Returns null if any required field is missing.
 */
fun validateSession(
  sessionStore: SessionStore,
  requireAppDataDir: Boolean = false,
): SessionData? {
  val session = sessionStore.snapshot() ?: return null
  if (session.serverUrl.isBlank() || session.userId.isBlank() || session.token.isBlank()) return null
  if (requireAppDataDir && session.appDataDir.isNullOrBlank()) return null
  return session
}
