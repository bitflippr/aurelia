package com.aurelia.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurelia.app.storage.SessionStore

@Composable
fun SetupScreen(
  sessionStore: SessionStore,
  onLogout: () -> Unit,
  onSetupComplete: () -> Unit,
) {
  val viewModel: SetupViewModel =
    viewModel(
      factory = viewModelFactory { SetupViewModel(sessionStore) },
    )
  val state by viewModel.state.collectAsStateWithLifecycle()

  // Trigger sync on launch
  LaunchedEffect(Unit) {
    viewModel.syncLibrary()
  }

  // Handle completion redirect
  LaunchedEffect(state.isSuccess) {
    if (state.isSuccess) {
      onSetupComplete()
    }
  }

  // Set up rotating animation for sync icon
  val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
  val rotation by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = -360f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(2000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
      ),
    label = "sync_rotation",
  )

  val gradient =
    Brush.verticalGradient(
      colors =
        listOf(
          MaterialTheme.colorScheme.background,
          MaterialTheme.colorScheme.background,
        ),
    )

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(gradient)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(24.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Icon Section
      Surface(
        modifier = Modifier.size(80.dp),
        shape = CircleShape,
        color =
          when {
            state.error != null -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.primaryContainer
          },
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (state.error != null) {
            Icon(
              imageVector = Icons.Default.ErrorOutline,
              contentDescription = "Error",
              tint = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.size(40.dp),
            )
          } else {
            Icon(
              imageVector = Icons.Default.Sync,
              contentDescription = "Syncing",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier =
                Modifier
                  .size(40.dp)
                  .rotate(if (state.isSyncing) rotation else 0f),
            )
          }
        }
      }

      // Title & Description
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = if (state.error != null) "Setup Failed" else "Setting Up Your Library",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
        )

        Text(
          text =
            if (state.error != null) {
              "We encountered an issue syncing your media library. Please check your network connection or server settings."
            } else {
              "Aurelia is preparing your music database. This will take a few moments."
            },
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }

      // Progress & Status Container
      if (state.error == null) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          tonalElevation = 2.dp,
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = state.stage,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              if (state.total > 0) {
                Text(
                  text = "${state.current} / ${state.total}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            val progressValue =
              if (state.total > 0) {
                state.current.toFloat() / state.total.toFloat()
              } else {
                -1f
              }

            if (progressValue >= 0f) {
              LinearProgressIndicator(
                progress = { progressValue },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
              )
            } else {
              LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
              )
            }
          }
        }
      } else {
        // Detailed error print
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = state.error ?: "Unknown error",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center,
          )
        }

        // Action Buttons on Error
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = { viewModel.syncLibrary(force = true) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
          ) {
            Text("Retry Sync")
          }

          OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
          ) {
            Text("Log Out")
          }
        }
      }
    }
  }
}
