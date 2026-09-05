package com.aurelia.app.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.storage.SessionProfile
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.BottomBarDimensions.MiniPlayerHeight
import com.aurelia.app.ui.components.BottomBarDimensions.NavBarContentHeight
import com.aurelia.app.ui.components.EqualizerSection
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.VisualizerSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.AuthRequest
import uniffi.aurelia_core.BackendProvider
import uniffi.aurelia_core.authenticate

private enum class SettingsPage(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
) {
  Appearance("Appearance", "Theme and visual preferences", Icons.Filled.Palette),
  Audio("Audio", "Equalizer and visualizer controls", Icons.Filled.Equalizer),
  Library("Library", "Sync and local cache controls", Icons.Filled.Sync),
  Connections("Connections", "Server and profiles", Icons.Filled.Storage),
  Account("Account", "Sign out and app information", Icons.Filled.ManageAccounts),
}

@Composable
fun SettingsScreen(
  sessionStore: SessionStore,
  settingsViewModel: SettingsViewModel,
  onLogout: () -> Unit,
  onSessionSwitched: () -> Unit,
  hasPlayerBar: Boolean = false,
) {
  val context = LocalContext.current
  val colors = MaterialTheme.colorScheme
  val isDebuggable =
    remember(context) {
      (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
  val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
  var showLogoutDialog by remember { mutableStateOf(false) }
  var showClearCacheDialog by remember { mutableStateOf(false) }
  var useDynamicColor by remember { mutableStateOf(sessionStore.getUseDynamicColor()) }
  var disableBackdropBlur by remember { mutableStateOf(sessionStore.getDebugDisablePlayerBackdropBlur()) }
  var disableBackdropImageLayer by remember { mutableStateOf(sessionStore.getDebugDisablePlayerBackdropImageLayer()) }
  var disablePlayerTransitions by remember { mutableStateOf(sessionStore.getDebugDisablePlayerTransitions()) }
  var profiles by remember { mutableStateOf(sessionStore.getProfiles()) }
  var activeProfileId by remember { mutableStateOf(sessionStore.getActiveProfileId()) }
  var switchingProfileId by remember { mutableStateOf<String?>(null) }
  var removingProfileId by remember { mutableStateOf<String?>(null) }
  var showAddProfileDialog by remember { mutableStateOf(false) }
  var addProfileServerUrl by remember { mutableStateOf("") }
  var addProfileUsername by remember { mutableStateOf("") }
  var addProfilePassword by remember { mutableStateOf("") }
  var addProfileDetectedProvider by remember { mutableStateOf<BackendProvider?>(null) }
  var addProfileError by remember { mutableStateOf<String?>(null) }
  var addProfileIsDetectingProvider by remember { mutableStateOf(false) }
  var addProfileIsSubmitting by remember { mutableStateOf(false) }
  var selectedSettingsPage by remember { mutableStateOf<SettingsPage?>(null) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  // Show snackbar for sync/clear results
  LaunchedEffect(settingsState.syncSuccess, settingsState.clearSuccess, settingsState.error) {
    when {
      settingsState.syncSuccess == true -> {
        snackbarHostState.showSnackbar("Library synced successfully")
        settingsViewModel.clearMessages()
      }

      settingsState.clearSuccess == true -> {
        snackbarHostState.showSnackbar("Cache cleared successfully")
        settingsViewModel.clearMessages()
      }

      settingsState.error != null -> {
        snackbarHostState.showSnackbar(settingsState.error ?: "An error occurred")
        settingsViewModel.clearMessages()
      }
    }
  }

  // Calculate bottom padding to avoid navbar/playerbar overlap
  val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val bottomPadding =
    NavBarContentHeight + systemNavBarInset + 24.dp +
      (if (hasPlayerBar) MiniPlayerHeight + 12.dp else 0.dp)

  val resetAddProfileState = {
    addProfileServerUrl = ""
    addProfileUsername = ""
    addProfilePassword = ""
    addProfileDetectedProvider = null
    addProfileError = null
    addProfileIsDetectingProvider = false
    addProfileIsSubmitting = false
  }

  if (showClearCacheDialog) {
    AlertDialog(
      onDismissRequest = { showClearCacheDialog = false },
      title = { Text("Clear cache?") },
      text = { Text("This will remove all locally stored song data. You'll need to sync again to restore it.") },
      confirmButton = {
        Button(
          onClick = {
            showClearCacheDialog = false
            settingsViewModel.clearLocalCache()
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = colors.error,
              contentColor = colors.onError,
            ),
        ) {
          Text("Clear")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearCacheDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }

  if (showLogoutDialog) {
    AlertDialog(
      onDismissRequest = { showLogoutDialog = false },
      title = { Text("Log out?") },
      text = { Text("You'll need to sign in again to access your library.") },
      confirmButton = {
        Button(
          onClick = {
            showLogoutDialog = false
            onLogout()
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = colors.error,
              contentColor = colors.onError,
            ),
        ) {
          Text("Log out")
        }
      },
      dismissButton = {
        TextButton(onClick = { showLogoutDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }

  if (showAddProfileDialog) {
    AlertDialog(
      onDismissRequest = {
        if (!addProfileIsSubmitting) {
          showAddProfileDialog = false
          resetAddProfileState()
        }
      },
      title = { Text("Add profile") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "Sign in to add another provider profile.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
          )

          androidx.compose.material3.OutlinedTextField(
            value = addProfileServerUrl,
            onValueChange = {
              addProfileServerUrl = it
              addProfileDetectedProvider = null
            },
            label = { Text("Server URL") },
            placeholder = { Text("https://your-server") },
            singleLine = true,
            enabled = !addProfileIsSubmitting,
            modifier = Modifier.fillMaxWidth(),
          )

          androidx.compose.material3.OutlinedTextField(
            value = addProfileUsername,
            onValueChange = { addProfileUsername = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !addProfileIsSubmitting,
            modifier = Modifier.fillMaxWidth(),
          )

          androidx.compose.material3.OutlinedTextField(
            value = addProfilePassword,
            onValueChange = { addProfilePassword = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !addProfileIsSubmitting,
            visualTransformation =
              androidx.compose.ui.text.input
                .PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
          )

          if (!addProfileError.isNullOrBlank()) {
            Text(
              text = addProfileError ?: "",
              style = MaterialTheme.typography.bodySmall,
              color = colors.error,
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (addProfileServerUrl.isBlank() || addProfileUsername.isBlank() || addProfilePassword.isBlank()) {
              addProfileError = "All fields are required"
              return@Button
            }

            val previousActiveProfileId = activeProfileId
            addProfileError = null
            addProfileIsSubmitting = true
            scope.launch(Dispatchers.IO) {
              try {
                val resolvedProvider = BackendProvider.JELLYFIN

                val response =
                  authenticate(
                    AuthRequest(
                      provider = resolvedProvider,
                      serverUrl = addProfileServerUrl.trim(),
                      username = addProfileUsername.trim(),
                      password = addProfilePassword,
                      deviceId = sessionStore.getDeviceId(),
                    ),
                  )

                sessionStore.save(
                  serverUrl = addProfileServerUrl.trim(),
                  userId = response.userId,
                  token = response.token,
                  username = addProfileUsername.trim(),
                  provider = resolvedProvider,
                )

                if (!previousActiveProfileId.isNullOrBlank()) {
                  sessionStore.switchProfile(previousActiveProfileId)
                }

                withContext(Dispatchers.Main) {
                  profiles = sessionStore.getProfiles()
                  activeProfileId = sessionStore.getActiveProfileId()
                  addProfileIsSubmitting = false
                  showAddProfileDialog = false
                  resetAddProfileState()
                }
              } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                  addProfileError = error.message ?: "Failed to add profile"
                  addProfileIsSubmitting = false
                }
              }
            }
          },
          enabled = !addProfileIsSubmitting,
        ) {
          if (addProfileIsSubmitting) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = colors.onPrimary,
            )
          } else {
            Text("Add")
          }
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showAddProfileDialog = false
            resetAddProfileState()
          },
          enabled = !addProfileIsSubmitting,
        ) {
          Text("Cancel")
        }
      },
    )
  }

  BackHandler(enabled = selectedSettingsPage != null) {
    selectedSettingsPage = null
  }

  androidx.compose.foundation.layout.Box(
    modifier =
      Modifier
        .fillMaxSize()
        .statusBarsPadding(),
  ) {
    AnimatedContent(
      targetState = selectedSettingsPage,
      transitionSpec = {
        if (targetState != null) {
          slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260))
            .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)))
        } else {
          slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260))
            .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)))
        }
      },
      label = "settingsPage",
    ) { page ->
      if (page == null) {
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(bottom = bottomPadding),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          LibraryScreenHeader(
            title = "Settings",
            subtitle = "App preferences",
          )

          Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SettingsSection(title = "Categories") {
              SettingsPage.entries.forEachIndexed { index, page ->
                SettingsNavItem(
                  icon = page.icon,
                  title = page.title,
                  subtitle = page.subtitle,
                  onClick = { selectedSettingsPage = page },
                )
                if (index < SettingsPage.entries.lastIndex) {
                  HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.outline.copy(alpha = 0.2f),
                  )
                }
              }
            }
          }
        }
      } else {
        Column(modifier = Modifier.fillMaxSize()) {
          SettingsSubpageHeader(
            title = page.title,
            subtitle = page.subtitle,
            onBack = { selectedSettingsPage = null },
          )

          Column(
            modifier =
              Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            if (page == SettingsPage.Appearance) {
              SettingsSection(title = "Appearance") {
                SettingsToggleItem(
                  icon = Icons.Filled.Palette,
                  title = "Material You",
                  subtitle = "Use system color palette",
                  checked = useDynamicColor,
                  onCheckedChange = {
                    useDynamicColor = it
                    sessionStore.setUseDynamicColor(it)
                  },
                )
              }

              if (isDebuggable) {
                SettingsSection(title = "Performance Debug") {
                  SettingsToggleItem(
                    icon = Icons.Filled.Info,
                    title = "Disable backdrop blur",
                    subtitle = "Player screen: remove album art blur layer",
                    checked = disableBackdropBlur,
                    onCheckedChange = {
                      disableBackdropBlur = it
                      sessionStore.setDebugDisablePlayerBackdropBlur(it)
                    },
                  )
                  HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.outline.copy(alpha = 0.2f),
                  )
                  SettingsToggleItem(
                    icon = Icons.Filled.Info,
                    title = "Disable backdrop image",
                    subtitle = "Player screen: hide album art background image layer",
                    checked = disableBackdropImageLayer,
                    onCheckedChange = {
                      disableBackdropImageLayer = it
                      sessionStore.setDebugDisablePlayerBackdropImageLayer(it)
                    },
                  )
                  HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.outline.copy(alpha = 0.2f),
                  )
                  SettingsToggleItem(
                    icon = Icons.Filled.Info,
                    title = "Disable player transitions",
                    subtitle = "Player screen: turn off fades/color tween animations",
                    checked = disablePlayerTransitions,
                    onCheckedChange = {
                      disablePlayerTransitions = it
                      sessionStore.setDebugDisablePlayerTransitions(it)
                    },
                  )
                }
              }
            }

            if (page == SettingsPage.Audio) {
              EqualizerSection(
                state =
                  com.aurelia.app.audio.EqualizerState(
                    enabled = settingsState.eqEnabled,
                    bands =
                      settingsState.eqBands.mapIndexed { index, gain ->
                        com.aurelia.app.audio.EQBand(
                          frequency = listOf(60, 250, 1000, 4000, 16000)[index],
                          gain = gain,
                        )
                      },
                    currentPreset = settingsState.eqPreset,
                    available = true,
                  ),
                onEnabledChange = { settingsViewModel.setEQEnabled(it) },
                onBandGainChange = { index, gain -> settingsViewModel.setEQBandGain(index, gain) },
                onPresetSelected = { preset -> settingsViewModel.applyEQPreset(preset.name) },
                onReset = { settingsViewModel.resetEQ() },
              )

              // Visualizer section
              val hasVisualizerPermission =
                ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

              LaunchedEffect(hasVisualizerPermission, settingsState.visualizerEnabled) {
                if (!hasVisualizerPermission && settingsState.visualizerEnabled) {
                  settingsViewModel.onVisualizerPermissionChanged(false)
                }
              }

              var showPermissionRationale by remember { mutableStateOf(false) }
              val permissionLauncher =
                rememberLauncherForActivityResult(
                  contract = ActivityResultContracts.RequestPermission(),
                ) { isGranted ->
                  if (isGranted) {
                    settingsViewModel.onVisualizerPermissionChanged(true)
                    settingsViewModel.setVisualizerEnabled(true)
                  } else {
                    settingsViewModel.onVisualizerPermissionChanged(false)
                  }
                }

              if (showPermissionRationale) {
                AlertDialog(
                  onDismissRequest = { showPermissionRationale = false },
                  title = { Text("Microphone permission needed") },
                  text = {
                    Text(
                      "The visualizer needs microphone access to analyze audio. This is only used locally and never recorded or sent anywhere.",
                    )
                  },
                  confirmButton = {
                    Button(onClick = {
                      showPermissionRationale = false
                      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                      Text("Continue")
                    }
                  },
                  dismissButton = {
                    TextButton(onClick = { showPermissionRationale = false }) {
                      Text("Cancel")
                    }
                  },
                )
              }

              VisualizerSection(
                enabled = settingsState.visualizerEnabled,
                style = settingsState.visualizerStyle,
                onEnabledChange = { enabled ->
                  if (enabled) {
                    if (hasVisualizerPermission) {
                      settingsViewModel.onVisualizerPermissionChanged(true)
                      settingsViewModel.setVisualizerEnabled(true)
                    } else {
                      showPermissionRationale = true
                    }
                  } else {
                    settingsViewModel.setVisualizerEnabled(false)
                  }
                },
                onStyleChange = { settingsViewModel.setVisualizerStyle(it) },
              )
            }

            if (page == SettingsPage.Library) {
              SettingsSection(title = "Library") {
                val lastSyncedText =
                  when {
                    settingsState.isSyncing -> "Syncing..."
                    settingsState.lastSyncTime != null -> "Last synced ${SettingsViewModel.formatRelativeTime(
                      settingsState.lastSyncTime,
                    )}"
                    else -> "Never synced"
                  }
                SettingsActionItem(
                  icon = Icons.Filled.Sync,
                  title = "Sync library",
                  subtitle = lastSyncedText,
                  isLoading = settingsState.isSyncing,
                  onClick = { if (!settingsState.isSyncing) settingsViewModel.syncLibrary() },
                )
                HorizontalDivider(
                  modifier = Modifier.padding(start = 56.dp),
                  color = colors.outline.copy(alpha = 0.2f),
                )
                SettingsActionItem(
                  icon = Icons.Filled.Delete,
                  title = "Clear cache",
                  subtitle = if (settingsState.isClearing) "Clearing..." else "Remove locally stored data",
                  isLoading = settingsState.isClearing,
                  onClick = { if (!settingsState.isClearing) showClearCacheDialog = true },
                )
              }

              SettingsSection(title = "Background Sync") {
                val syncContext = LocalContext.current
                SettingsToggleItem(
                  icon = Icons.Filled.Sync,
                  title = "Auto-sync",
                  subtitle = "Sync library automatically on WiFi",
                  checked = settingsState.autoSyncEnabled,
                  onCheckedChange = { settingsViewModel.setAutoSyncEnabled(syncContext, it) },
                )
                if (settingsState.autoSyncEnabled) {
                  HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.outline.copy(alpha = 0.2f),
                  )
                  val intervalText =
                    when (settingsState.syncIntervalHours) {
                      6L -> "Every 6 hours"
                      12L -> "Every 12 hours"
                      24L -> "Daily"
                      168L -> "Weekly"
                      else -> "Every ${settingsState.syncIntervalHours}h"
                    }
                  var showIntervalPicker by remember { mutableStateOf(false) }
                  SettingsActionItem(
                    icon = Icons.Filled.Storage,
                    title = "Sync frequency",
                    subtitle = intervalText,
                    onClick = { showIntervalPicker = true },
                  )
                  if (showIntervalPicker) {
                    AlertDialog(
                      onDismissRequest = { showIntervalPicker = false },
                      title = { Text("Sync frequency") },
                      text = {
                        Column {
                          listOf(
                            6L to "Every 6 hours",
                            12L to "Every 12 hours",
                            24L to "Daily",
                            168L to "Weekly",
                          ).forEach { (hours, label) ->
                            TextButton(
                              onClick = {
                                settingsViewModel.setSyncInterval(syncContext, hours)
                                showIntervalPicker = false
                              },
                              modifier = Modifier.fillMaxWidth(),
                            ) {
                              Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                  if (settingsState.syncIntervalHours ==
                                    hours
                                  ) {
                                    colors.primary
                                  } else {
                                    colors.onSurface
                                  },
                              )
                            }
                          }
                        }
                      },
                      confirmButton = {
                        TextButton(onClick = { showIntervalPicker = false }) {
                          Text("Cancel")
                        }
                      },
                    )
                  }
                }
              }
            }

            if (page == SettingsPage.Connections) {
              SettingsSection(title = "Server") {
                val serverUrl = sessionStore.getServerUrl() ?: "Not connected"
                val username = sessionStore.getUserId() ?: "Unknown"
                val provider = sessionStore.getProvider()?.name?.lowercase() ?: "unknown"

                SettingsInfoItem(
                  icon = Icons.Filled.Storage,
                  title = "Connected to",
                  subtitle = serverUrl,
                )
                HorizontalDivider(
                  modifier = Modifier.padding(start = 56.dp),
                  color = colors.outline.copy(alpha = 0.2f),
                )
                SettingsInfoItem(
                  icon = Icons.Filled.Info,
                  title = "Logged in as",
                  subtitle = username,
                )
                HorizontalDivider(
                  modifier = Modifier.padding(start = 56.dp),
                  color = colors.outline.copy(alpha = 0.2f),
                )
                SettingsInfoItem(
                  icon = Icons.Filled.Info,
                  title = "Provider",
                  subtitle = provider,
                )
              }

              SettingsSection(title = "Profiles") {
                SettingsActionItem(
                  icon = Icons.Filled.Add,
                  title = "Add profile",
                  subtitle = "Sign in to another provider account",
                  onClick = {
                    resetAddProfileState()
                    showAddProfileDialog = true
                  },
                )

                if (profiles.isNotEmpty()) {
                  HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.outline.copy(alpha = 0.2f),
                  )
                }

                if (profiles.isEmpty()) {
                  SettingsInfoItem(
                    icon = Icons.Filled.Info,
                    title = "No saved profiles",
                    subtitle = "Sign in to add a provider profile",
                  )
                } else {
                  profiles.forEachIndexed { index, profile ->
                    ProfileActionItem(
                      profile = profile,
                      isActive = profile.id == activeProfileId,
                      isRemoving = removingProfileId == profile.id,
                      isSwitching = switchingProfileId == profile.id,
                      onRemove = {
                        val wasActive = profile.id == activeProfileId
                        removingProfileId = profile.id
                        val removed = sessionStore.removeProfile(profile.id)
                        profiles = sessionStore.getProfiles()
                        activeProfileId = sessionStore.getActiveProfileId()
                        removingProfileId = null

                        if (!removed) {
                          android.util.Log.w("SettingsScreen", "Failed to remove profile ${profile.id}")
                          return@ProfileActionItem
                        }

                        if (profiles.isEmpty()) {
                          onLogout()
                        } else if (wasActive) {
                          onSessionSwitched()
                        }
                      },
                      onSwitch = {
                        if (profile.id == activeProfileId) {
                          return@ProfileActionItem
                        }
                        switchingProfileId = profile.id
                        val switched = sessionStore.switchProfile(profile.id)
                        profiles = sessionStore.getProfiles()
                        activeProfileId = sessionStore.getActiveProfileId()
                        switchingProfileId = null

                        if (switched) {
                          onSessionSwitched()
                        }
                      },
                    )
                    if (index < profiles.lastIndex) {
                      HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colors.outline.copy(alpha = 0.2f),
                      )
                    }
                  }
                }
              }
            }

            if (page == SettingsPage.Account) {
              SettingsSection(title = "Account") {
                SettingsActionItem(
                  icon = Icons.AutoMirrored.Filled.ExitToApp,
                  title = "Log out",
                  subtitle = "Sign out from this device",
                  isDestructive = true,
                  onClick = { showLogoutDialog = true },
                )
              }

              SettingsSection(title = "About") {
                SettingsInfoItem(
                  icon = Icons.Filled.Info,
                  title = "Version",
                  subtitle = "1.0.0",
                )
              }
            }
          }
        }
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = bottomPadding),
    )
  }
}

@Composable
private fun SettingsSection(
  title: String,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = colors.primary,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = colors.surfaceContainerLow,
      tonalElevation = 2.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        content()
      }
    }
  }
}

@Composable
private fun SettingsSubpageHeader(
  title: String,
  subtitle: String,
  onBack: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    androidx.compose.material3.IconButton(onClick = onBack) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        tint = colors.onSurface,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Black,
        color = colors.onBackground,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SettingsNavItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
) {
  SettingsActionItem(
    icon = icon,
    title = title,
    subtitle = subtitle,
    onClick = onClick,
  )
}

@Composable
private fun SettingsToggleItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable { onCheckedChange(!checked) }
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = colors.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = colors.primary,
          checkedTrackColor = colors.primaryContainer,
        ),
    )
  }
}

@Composable
private fun SettingsActionItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  isDestructive: Boolean = false,
  isLoading: Boolean = false,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val contentColor = if (isDestructive) colors.error else colors.onSurface

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(enabled = !isLoading, onClick = onClick)
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (isDestructive) colors.error else colors.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = contentColor,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = if (isDestructive) colors.error.copy(alpha = 0.7f) else colors.onSurfaceVariant,
      )
    }
    if (isLoading) {
      CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = colors.primary,
      )
    } else {
      Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun ProfileActionItem(
  profile: SessionProfile,
  isActive: Boolean,
  isRemoving: Boolean,
  isSwitching: Boolean,
  onRemove: () -> Unit,
  onSwitch: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.Storage,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = profile.username.ifBlank { profile.userId },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = colors.onSurface,
      )
      Text(
        text = profile.serverUrl,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
      if (isActive) {
        Text(
          text = "Active",
          style = MaterialTheme.typography.labelSmall,
          color = colors.primary,
        )
      }
    }

    if (!isActive) {
      TextButton(
        enabled = !isRemoving && !isSwitching,
        onClick = onSwitch,
      ) {
        if (isSwitching) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = colors.primary,
          )
        } else {
          Text("Switch")
        }
      }
    }

    TextButton(
      enabled = !isRemoving && !isSwitching,
      onClick = onRemove,
    ) {
      if (isRemoving) {
        CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          strokeWidth = 2.dp,
          color = colors.error,
        )
      } else {
        Icon(
          imageVector = Icons.Filled.Delete,
          contentDescription = "Remove profile",
          tint = colors.error,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun SettingsInfoItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
) {
  val colors = MaterialTheme.colorScheme

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = colors.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
  }
}
