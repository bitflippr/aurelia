package com.aurelia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.ui.components.ActionButtonRow
import com.aurelia.app.ui.components.AlbumArt
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.DetailHeroGradient
import com.aurelia.app.ui.theme.SquircleShape
import com.aurelia.app.ui.theme.rememberGoogleSansFlexWideFont
import com.aurelia.app.utils.formatDuration

@Composable
fun PlaylistDetailScreen(
  playlistId: String,
  playlistName: String,
  viewModel: PlaylistViewModel,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit,
  hasPlayerBar: Boolean = false,
) {
  val state by viewModel.detailState.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val wideFont = rememberGoogleSansFlexWideFont()
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  LaunchedEffect(playlistId) {
    viewModel.loadPlaylistDetail(playlistId, playlistName)
  }

  val gradient = DetailHeroGradient()

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(gradient)
        .statusBarsPadding(),
  ) {
    // Header with back button
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back",
          tint = colors.onPrimaryContainer,
        )
      }
    }

    when {
      state.isLoading -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      }

      state.error != null -> {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = state.error ?: "An error occurred",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.error,
            textAlign = TextAlign.Center,
          )
        }
      }

      else -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
          // Playlist header
          item {
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              // Playlist art placeholder
              Surface(
                modifier =
                  Modifier
                    .size(240.dp)
                    .clip(SquircleShape),
                shape = SquircleShape,
                color = colors.primaryContainer,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
              ) {
                Box(
                  modifier = Modifier.fillMaxSize(),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = colors.onPrimaryContainer.copy(alpha = 0.5f),
                  )
                }
              }

              Spacer(modifier = Modifier.height(24.dp))

              // Playlist name
              Text(
                text = playlistName,
                style =
                  MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = wideFont,
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                  ),
                fontWeight = FontWeight.Black,
                color = colors.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
              )

              Spacer(modifier = Modifier.height(8.dp))

              // Song count
              Text(
                text = "${state.songs.size} songs",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.6f),
              )

              Spacer(modifier = Modifier.height(24.dp))

              ActionButtonRow(
                enabled = state.songs.isNotEmpty(),
                onPlay = {
                  viewModel.playPlaylist(0)
                  onOpenPlayer()
                },
                onShuffle = {
                  viewModel.shufflePlaylist()
                  onOpenPlayer()
                },
              )

              Spacer(modifier = Modifier.height(32.dp))
            }
          }

          // Song list
          if (state.songs.isEmpty()) {
            item {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = "This playlist is empty",
                  style = MaterialTheme.typography.bodyLarge,
                  color = colors.onSurfaceVariant,
                )
              }
            }
          } else {
            itemsIndexed(
              items = state.songs,
              key = { index, song -> "${song.id}_$index" },
            ) { index, song ->
              PlaylistSongItem(
                title = song.name,
                artist = song.artists?.joinToString(", ") ?: "Unknown Artist",
                albumArtUrl = song.albumArtUrl,
                duration = song.duration?.let { formatDuration((it * 1000).toLong()) },
                onClick = {
                  viewModel.playPlaylist(index)
                  onOpenPlayer()
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PlaylistSongItem(
  title: String,
  artist: String,
  albumArtUrl: String?,
  duration: String?,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = colors.surface.copy(alpha = 0f),
    onClick = onClick,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AlbumArt(
        imageUrl = albumArtUrl,
        size = 48.dp,
        cornerRadius = 8.dp,
        style = AlbumArtStyle.Song,
        containerColor = colors.surfaceVariant,
        contentColor = colors.onSurfaceVariant.copy(alpha = 0.5f),
      )

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = artist,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      duration?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }
    }
  }
}
