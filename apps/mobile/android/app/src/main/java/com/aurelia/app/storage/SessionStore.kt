package com.aurelia.app.storage

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.aurelia_core.BackendProvider
import uniffi.aurelia_core.Credentials
import uniffi.aurelia_core.clearCredentials
import uniffi.aurelia_core.loadCredentials
import uniffi.aurelia_core.saveCredentials
import java.io.File

@Serializable
private data class StoredSessionProfile(
  val id: String,
  val provider: String,
  val serverUrl: String,
  val username: String,
  val token: String? = null,
  val userId: String,
  val updatedAt: Long,
)

data class SessionProfile(
  val id: String,
  val label: String,
  val provider: BackendProvider,
  val serverUrl: String,
  val userId: String,
  val username: String,
)

class SessionStore(
  context: Context,
) {
  val library = LibraryStore()
  val reads = SessionReads()
  private val prefs = context.getSharedPreferences("aurelia_session", Context.MODE_PRIVATE)
  private val externalFilesDir = context.getExternalFilesDir(null)?.absolutePath
  private val json = Json { ignoreUnknownKeys = true }
  private var migrationAttempted = false

  @Synchronized
  fun save(
    serverUrl: String,
    userId: String,
    token: String,
    username: String = "",
    provider: BackendProvider = BackendProvider.JELLYFIN,
  ) {
    val base = checkNotNull(getBaseAppDataDir()) { "App data directory is not configured" }
    val credentials = Credentials(provider, serverUrl, username, token, userId)
    val profileId = buildProfileId(credentials)
    saveCredentials(resolveProfileAppDataDir(base, profileId), credentials)
    upsertProfile(credentials)
    setActiveProfileId(profileId)
    library.clear()
    reads.clear()
  }

  @Synchronized
  fun snapshot(): com.aurelia.app.utils.SessionData? {
    val credentials = getCredentials() ?: return null
    return com.aurelia.app.utils.SessionData(
      credentials.serverUrl,
      credentials.userId,
      credentials.token,
      getAppDataDir(),
    )
  }

  fun setAppDataDir(path: String) {
    prefs.edit { putString(KEY_APP_DATA_DIR, path) }
  }

  fun getAppDataDir(): String? {
    val baseAppDataDir = getBaseAppDataDir() ?: return null
    val activeProfileId = getActiveProfileId() ?: return baseAppDataDir
    return resolveProfileAppDataDir(baseAppDataDir, activeProfileId)
  }

  fun setUseDynamicColor(enabled: Boolean) {
    prefs.edit { putBoolean("useDynamicColor", enabled) }
  }

  fun getUseDynamicColor(): Boolean = prefs.getBoolean("useDynamicColor", true)

  @Synchronized
  fun clear() {
    library.clear()
    reads.clear()
    val appDataDir = getAppDataDir()
    if (!appDataDir.isNullOrEmpty()) {
      try {
        clearCredentials(appDataDir)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to clear credentials from redb", e)
      }
    }
    setActiveProfileId(null)
    // Also clear previous SharedPreferences credentials (but keep settings)
    prefs.edit {
      remove("serverUrl")
        .remove("userId")
        .remove("token")
    }
  }

  @Synchronized
  fun getCredentials(): Credentials? {
    val baseAppDataDir = getBaseAppDataDir() ?: return null

    // Attempt migration from SharedPreferences on first access
    if (!migrationAttempted) {
      migrationAttempted = true
      migrateFromSharedPreferences(baseAppDataDir)
    }

    bootstrapActiveProfileFromLegacyCredentials(baseAppDataDir)

    val appDataDir = getAppDataDir() ?: return null

    return try {
      loadCredentials(appDataDir)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load credentials from redb", e)
      null
    }
  }

  fun getServerUrl(): String? = getCredentials()?.serverUrl

  fun getUserId(): String? = getCredentials()?.userId

  fun getToken(): String? = getCredentials()?.token

  fun getProvider(): BackendProvider? = getCredentials()?.provider

  fun getProfiles(): List<SessionProfile> =
    runCatching { loadStoredProfiles() }
      .getOrElse {
        Log.w(TAG, "Failed to read profiles", it)
        emptyList()
      }.sortedByDescending { it.updatedAt }
      .mapNotNull { storedProfile ->
        val provider = storedProfile.provider.toBackendProvider() ?: return@mapNotNull null
        val username = storedProfile.username.ifBlank { storedProfile.userId }
        SessionProfile(
          id = storedProfile.id,
          label = "$username @ ${storedProfile.serverUrl} (${provider.name.lowercase()})",
          provider = provider,
          serverUrl = storedProfile.serverUrl,
          userId = storedProfile.userId,
          username = username,
        )
      }

  fun getActiveProfileId(): String? = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

  @Synchronized
  fun switchProfile(profileId: String): Boolean {
    val base = getBaseAppDataDir() ?: return false
    return try {
      if (loadStoredProfiles().none { it.id == profileId }) return false
      val path = resolveProfileAppDataDir(base, profileId)
      if (loadCredentials(path) == null) return false
      setActiveProfileId(profileId)
      library.clear()
      reads.clear()
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to switch profile", e)
      false
    }
  }

  fun removeProfile(profileId: String): Boolean {
    val profiles = loadStoredProfiles().toMutableList()
    val removed = profiles.removeAll { it.id == profileId }
    if (!removed) return false

    saveStoredProfiles(profiles)
    if (getActiveProfileId() == profileId) {
      val replacement = profiles.maxByOrNull { it.updatedAt }
      setActiveProfileId(null)
      if (replacement != null) switchProfile(replacement.id)
      library.clear()
    }
    return true
  }

  fun setOnDeviceAiModelPath(path: String?) {
    prefs.edit {
      if (path.isNullOrBlank()) {
        remove(KEY_ON_DEVICE_AI_MODEL_PATH)
      } else {
        putString(KEY_ON_DEVICE_AI_MODEL_PATH, path)
      }
    }
  }

  fun getOnDeviceAiModelPath(): String? {
    prefs
      .getString(KEY_ON_DEVICE_AI_MODEL_PATH, null)
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    val modelDirs =
      listOfNotNull(
        externalFilesDir?.let { File(it, "models") },
        getBaseAppDataDir()?.let { File(it, "models") },
      ).distinctBy { it.absolutePath }

    val discoveredModel =
      modelDirs.firstNotNullOfOrNull { modelDir ->
        modelDir
          .listFiles { file -> file.isFile && file.extension.equals("litertlm", ignoreCase = true) }
          ?.sortedBy { it.name.lowercase() }
          ?.firstOrNull()
      }

    return discoveredModel?.absolutePath
      ?: modelDirs.firstOrNull()?.let { File(it, "gemma-4.litertlm").absolutePath }
  }

  fun getOnDeviceAiModelsDir(): String? {
    val modelDir =
      externalFilesDir?.let { File(it, "models") }
        ?: getBaseAppDataDir()?.let { File(it, "models") }
        ?: return null
    modelDir.mkdirs()
    return modelDir.absolutePath
  }

  fun getOnDeviceAiCacheDir(): String? {
    val baseAppDataDir = getBaseAppDataDir() ?: return null
    return File(baseAppDataDir, "ai-cache").apply { mkdirs() }.absolutePath
  }

  // EQ Settings
  fun setEQEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("eq_enabled", enabled) }
  }

  fun getEQEnabled(): Boolean = prefs.getBoolean("eq_enabled", false)

  fun setEQBands(bands: List<Float>) {
    prefs.edit { putString("eq_bands", bands.joinToString(",")) }
  }

  fun getEQBands(): List<Float> {
    val stored = prefs.getString("eq_bands", null) ?: return listOf(0f, 0f, 0f, 0f, 0f)
    return stored.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 5 }
      ?: listOf(0f, 0f, 0f, 0f, 0f)
  }

  fun setEQPreset(presetName: String?) {
    prefs.edit {
      if (presetName.isNullOrBlank()) remove("eq_preset") else putString("eq_preset", presetName)
    }
  }

  fun getEQPreset(): String? = prefs.getString("eq_preset", null)

  // Visualizer Settings
  fun setVisualizerEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("visualizer_enabled", enabled) }
  }

  fun getVisualizerEnabled(): Boolean = prefs.getBoolean("visualizer_enabled", false)

  fun setVisualizerStyle(style: String) {
    prefs.edit { putString("visualizer_style", style) }
  }

  fun getVisualizerStyle(): String = prefs.getString("visualizer_style", "BARS") ?: "BARS"

  // Debug performance toggles (debug builds only UI)
  fun setDebugDisablePlayerBackdropBlur(disabled: Boolean) {
    prefs.edit { putBoolean("debug_disable_player_backdrop_blur", disabled) }
  }

  fun getDebugDisablePlayerBackdropBlur(): Boolean = prefs.getBoolean("debug_disable_player_backdrop_blur", false)

  fun setDebugDisablePlayerBackdropImageLayer(disabled: Boolean) {
    prefs.edit { putBoolean("debug_disable_player_backdrop_image_layer", disabled) }
  }

  fun getDebugDisablePlayerBackdropImageLayer(): Boolean =
    prefs.getBoolean("debug_disable_player_backdrop_image_layer", false)

  fun setDebugDisablePlayerTransitions(disabled: Boolean) {
    prefs.edit { putBoolean("debug_disable_player_transitions", disabled) }
  }

  fun getDebugDisablePlayerTransitions(): Boolean = prefs.getBoolean("debug_disable_player_transitions", false)

  fun getDeviceId(): String {
    val savedId = prefs.getString("device_id", null)
    if (savedId != null) return savedId

    val newId =
      java.util.UUID
        .randomUUID()
        .toString()
    prefs.edit { putString("device_id", newId) }
    return newId
  }

  private fun migrateFromSharedPreferences(appDataDir: String) {
    val oldServerUrl = prefs.getString("serverUrl", null)
    val oldUserId = prefs.getString("userId", null)
    val oldToken = prefs.getString("token", null)

    // Only migrate if old credentials exist
    if (oldServerUrl != null && oldUserId != null && oldToken != null) {
      // Check if redb already has credentials
      try {
        val existingCreds = loadCredentials(appDataDir)
        if (existingCreds != null) {
          // Already have credentials in redb, just clear old ones
          clearOldSharedPreferencesCredentials()
          Log.d(TAG, "Credentials already in redb, cleared previous SharedPreferences")
          return
        }
      } catch (e: Exception) {
        Log.w(TAG, "Could not check existing redb credentials", e)
      }

      // Migrate to redb
      try {
        val credentials =
          Credentials(
            provider = BackendProvider.JELLYFIN,
            serverUrl = oldServerUrl,
            username = "",
            token = oldToken,
            userId = oldUserId,
          )
        saveCredentials(appDataDir, credentials)
        clearOldSharedPreferencesCredentials()
        Log.i(TAG, "Successfully migrated credentials from SharedPreferences to redb")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to migrate credentials to redb", e)
      }
    }
  }

  private fun clearOldSharedPreferencesCredentials() {
    prefs.edit {
      remove("serverUrl")
        .remove("userId")
        .remove("token")
    }
  }

  private fun getBaseAppDataDir(): String? = prefs.getString(KEY_APP_DATA_DIR, null)

  private fun setActiveProfileId(profileId: String?) {
    prefs.edit {
      if (profileId.isNullOrBlank()) {
        remove(KEY_ACTIVE_PROFILE_ID)
      } else {
        putString(KEY_ACTIVE_PROFILE_ID, profileId)
      }
    }
  }

  private fun bootstrapActiveProfileFromLegacyCredentials(baseAppDataDir: String) {
    if (!getActiveProfileId().isNullOrBlank()) return
    val legacyCredentials =
      try {
        loadCredentials(baseAppDataDir)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to load legacy credentials for bootstrap", e)
        null
      } ?: return

    val profileId = buildProfileId(legacyCredentials)
    try {
      val profileAppDataDir = resolveProfileAppDataDir(baseAppDataDir, profileId)
      if (loadCredentials(profileAppDataDir) == null) {
        saveCredentials(profileAppDataDir, legacyCredentials)
      }
      upsertProfile(legacyCredentials)
      setActiveProfileId(profileId)
      clearCredentials(baseAppDataDir)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to bootstrap profile credentials", e)
    }
  }

  private fun buildProfileId(credentials: Credentials): String {
    val provider = credentials.provider.name.lowercase()
    val username = credentials.username.trim().lowercase()
    val serverUrl = credentials.serverUrl.trim().lowercase()
    return "$provider|$username|$serverUrl"
  }

  private fun profileDirectoryName(profileId: String): String {
    val slug =
      profileId
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "profile" }
    val checksum = profileId.hashCode().toUInt().toString(16)
    return "$slug-$checksum"
  }

  private fun resolveProfileAppDataDir(
    baseAppDataDir: String,
    profileId: String,
  ): String {
    val profileDir = File(baseAppDataDir, "profiles/${profileDirectoryName(profileId)}")
    if (!profileDir.exists()) {
      profileDir.mkdirs()
    }
    return profileDir.absolutePath
  }

  private fun upsertProfile(credentials: Credentials): String {
    val profileId = buildProfileId(credentials)
    val profiles = loadStoredProfiles().toMutableList()
    val provider = credentials.provider.name.lowercase()
    val updatedProfile =
      StoredSessionProfile(
        id = profileId,
        provider = provider,
        serverUrl = credentials.serverUrl,
        username = credentials.username,
        token = null,
        userId = credentials.userId,
        updatedAt = System.currentTimeMillis(),
      )
    profiles.removeAll { it.id == profileId }
    profiles += updatedProfile
    saveStoredProfiles(profiles)
    return profileId
  }

  private fun loadStoredProfiles(): List<StoredSessionProfile> {
    val raw = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
    val profiles = json.decodeFromString<List<StoredSessionProfile>>(raw)
    val base = getBaseAppDataDir() ?: return profiles
    val migrated =
      profiles.map { profile ->
        val legacy = profile.toCredentials() ?: return@map profile
        val path = resolveProfileAppDataDir(base, profile.id)
        if (loadCredentials(path) == null) saveCredentials(path, legacy)
        profile.copy(token = null)
      }
    if (migrated != profiles) saveStoredProfiles(migrated)
    return migrated
  }

  private fun saveStoredProfiles(profiles: List<StoredSessionProfile>) {
    check(prefs.edit().putString(KEY_PROFILES_JSON, json.encodeToString(profiles)).commit()) {
      "Could not save profile metadata"
    }
  }

  companion object {
    @Volatile
    private var applicationInstance: SessionStore? = null

    /** Retain loaded data for the process, holding only the application context. */
    fun forApplication(context: Context): SessionStore =
      applicationInstance ?: synchronized(this) {
        applicationInstance ?: SessionStore(context.applicationContext).also { applicationInstance = it }
      }

    private const val TAG = "SessionStore"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_APP_DATA_DIR = "appDataDir"
    private const val KEY_ON_DEVICE_AI_MODEL_PATH = "on_device_ai_model_path"
    private const val KEY_PROFILES_JSON = "saved_profiles_json"
  }
}

private fun String.toBackendProvider(): BackendProvider? =
  when (lowercase()) {
    "jellyfin" -> BackendProvider.JELLYFIN
    else -> null
  }

private fun StoredSessionProfile.toCredentials(): Credentials? {
  val provider = provider.toBackendProvider() ?: return null
  return Credentials(
    provider = provider,
    serverUrl = serverUrl,
    username = username,
    token = token ?: return null,
    userId = userId,
  )
}
