package com.aurelia.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurelia.app.auth.AuthInterceptor
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.sync.SyncWorker
import com.aurelia.app.ui.AppViewModel
import com.aurelia.app.ui.LoginScreen
import com.aurelia.app.ui.MainScreen
import com.aurelia.app.ui.SetupScreen
import com.aurelia.app.ui.SharedPlayerControllerViewModel
import com.aurelia.app.ui.theme.AureliaTheme
import com.aurelia.app.ui.viewModelFactory

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(
      ActivityResultContracts.RequestPermission(),
    ) { _ ->
      // Permission granted, notifications will work when playback starts
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    checkNotificationPermission()
    setContent { AureliaApp() }
  }

  private fun checkNotificationPermission() {
    val permissionStatus = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
    if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}

@Composable
private fun AureliaApp() {
  val context = LocalContext.current
  val sessionStore = remember { SessionStore.forApplication(context) }

  if (sessionStore.getAppDataDir().isNullOrBlank()) {
    sessionStore.setAppDataDir(context.filesDir.absolutePath)
  }

  // Use SharedPlayerControllerViewModel to ensure PlayerController survives configuration changes
  val sharedPlayerViewModel: SharedPlayerControllerViewModel = viewModel()
  val playerController = sharedPlayerViewModel.playerController

  val appViewModel: AppViewModel =
    viewModel(
      factory = viewModelFactory { AppViewModel(sessionStore) },
    )
  val appState by appViewModel.state.collectAsStateWithLifecycle()

  // Track dynamic color preference changes
  var useDynamicColor by remember { mutableStateOf(sessionStore.getUseDynamicColor()) }

  // Set up auth interceptor for automatic logout on 401 errors
  DisposableEffect(Unit) {
    AuthInterceptor.setLogoutCallback {
      sessionStore.clear()
      appViewModel.checkSession()
    }
    onDispose {
      AuthInterceptor.clearLogoutCallback()
    }
  }

  AureliaTheme(useDynamicColor = useDynamicColor) {
    when {
      appState.isLoading -> {
        // Show nothing while checking session - themed background only
      }
      appState.isLoggedIn -> {
        if (appState.isInitialSyncComplete) {
          // Schedule background sync when user is logged in and initial sync is complete
          LaunchedEffect(Unit) {
            SyncWorker.schedule(context)
          }
          key(appState.sessionVersion) {
            MainScreen(
              sessionStore = sessionStore,
              playerController = playerController,
              onLogout = {
                SyncWorker.cancel(context)
                sessionStore.clear()
                appViewModel.checkSession()
              },
              onSessionSwitched = {
                playerController.stop()
                SyncWorker.schedule(context)
                appViewModel.refreshForSessionSwitch()
              },
            )
          }
        } else {
          key(appState.sessionVersion) {
            SetupScreen(
              sessionStore = sessionStore,
              onLogout = {
                SyncWorker.cancel(context)
                sessionStore.clear()
                appViewModel.checkSession()
              },
              onSetupComplete = {
                appViewModel.checkSession()
              },
            )
          }
        }
      }
      else -> {
        LoginScreen(sessionStore) { appViewModel.checkSession() }
      }
    }
  }
}
