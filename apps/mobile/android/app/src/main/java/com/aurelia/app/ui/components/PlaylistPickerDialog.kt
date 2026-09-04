package com.aurelia.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.aurelia_core.Playlist

@Composable
fun PlaylistPickerDialog(
  playlists: List<Playlist>,
  isLoading: Boolean,
  onDismiss: () -> Unit,
  onSelectPlaylist: (Playlist) -> Unit,
  onCreatePlaylist: (String) -> Unit,
) {
  var showCreateInput by remember { mutableStateOf(false) }
  var newPlaylistName by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add to Playlist") },
    text = {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
      ) {
        if (showCreateInput) {
          OutlinedTextField(
            value = newPlaylistName,
            onValueChange = { newPlaylistName = it },
            label = { Text("New playlist name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
              KeyboardActions(
                onDone = {
                  if (newPlaylistName.isNotBlank()) {
                    onCreatePlaylist(newPlaylistName.trim())
                    newPlaylistName = ""
                    showCreateInput = false
                  }
                },
              ),
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row {
            TextButton(onClick = { showCreateInput = false }) {
              Text("Cancel")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
              onClick = {
                if (newPlaylistName.isNotBlank()) {
                  onCreatePlaylist(newPlaylistName.trim())
                  newPlaylistName = ""
                  showCreateInput = false
                }
              },
              enabled = newPlaylistName.isNotBlank(),
            ) {
              Text("Create")
            }
          }
          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        if (isLoading) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator()
          }
        } else {
          LazyColumn {
            // Create new playlist option
            if (!showCreateInput) {
              item {
                Surface(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(8.dp))
                      .clickable { showCreateInput = true },
                  color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                  Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Icon(
                      imageVector = Icons.Default.Add,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                      text = "Create New Playlist",
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.Medium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                  }
                }
                Spacer(modifier = Modifier.height(8.dp))
              }
            }

            // Existing playlists
            items(playlists, key = { it.id }) { playlist ->
              Surface(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectPlaylist(playlist) },
                color = MaterialTheme.colorScheme.surface,
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  AlbumArt(
                    imageUrl = null,
                    size = 40.dp,
                    cornerRadius = 4.dp,
                    style = AlbumArtStyle.Playlist,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                  )
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = playlist.name,
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.Medium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = "${playlist.childCount ?: 0} songs",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
              Spacer(modifier = Modifier.height(4.dp))
            }

            if (playlists.isEmpty() && !showCreateInput) {
              item {
                Box(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(16.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = "No playlists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
