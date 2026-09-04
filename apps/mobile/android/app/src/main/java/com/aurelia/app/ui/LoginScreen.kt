package com.aurelia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurelia.app.storage.SessionStore

@Composable
fun LoginScreen(
  sessionStore: SessionStore,
  onLoginSuccess: () -> Unit,
) {
  val viewModel: LoginViewModel =
    viewModel(
      factory = viewModelFactory { LoginViewModel(sessionStore) },
    )
  val state by viewModel.state.collectAsStateWithLifecycle()

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
        .imePadding()
        .padding(PaddingValues(24.dp)),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
          modifier = Modifier.size(56.dp),
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        Text(
          text = "Welcome to Aurelia",
          style = MaterialTheme.typography.displayLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = "Sign in to your media server library",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::updateServerUrl,
            label = { Text("Server URL") },
            placeholder = { Text("https://your-server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::updateUsername,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          if (state.error != null) {
            Text(text = state.error ?: "", color = MaterialTheme.colorScheme.error)
          }

          Button(
            onClick = viewModel::submit,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
          ) {
            Text(text = if (state.isSubmitting) "Signing in..." else "Sign in")
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))
    }
  }

  if (state.token != null) {
    onLoginSuccess()
  }
}
