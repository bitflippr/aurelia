package com.aurelia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.utils.optimizedArtworkUrl

/** Soft artwork fills the top edge, with a theme scrim to keep system icons readable. */
@Composable
internal fun DetailArtworkBackdrop(
  imageUrl: String?,
  modifier: Modifier = Modifier,
) {
  val background = MaterialTheme.colorScheme.background
  Box(modifier.clipToBounds()) {
    if (!imageUrl.isNullOrBlank()) {
      AsyncImage(
        model =
          ImageRequest
            .Builder(LocalContext.current)
            .data(optimizedArtworkUrl(imageUrl, 256))
            .size(256)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().blur(48.dp),
      )
    }
    Box(
      Modifier.matchParentSize().background(
        Brush.verticalGradient(
          0f to background.copy(alpha = 0.55f),
          0.35f to background.copy(alpha = 0.4f),
          0.75f to background.copy(alpha = 0.85f),
          1f to background,
        ),
      ),
    )
  }
}

@Composable
internal fun DetailPageHeader(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
    }
    Text(
      title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.width(48.dp))
  }
}

@Composable
internal fun DetailSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  Row(
    modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).heightIn(min = 40.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.weight(1f).semantics { heading() },
    )
    if (actionLabel != null) {
      TextButton(
        onClick = onAction,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.semantics { contentDescription = "$actionLabel $title" },
      ) {
        Text(actionLabel, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

/** Collection playback uses the same grouped shapes as the player, with full touch targets. */
@Composable
internal fun DetailPlaybackActions(
  enabled: Boolean,
  onPlay: () -> Unit,
  onShuffle: () -> Unit,
  onAddToQueue: () -> Unit,
  onAddToPlaylist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  val tonalColors =
    ButtonDefaults.filledTonalButtonColors(
      containerColor = colors.primaryContainer,
      contentColor = colors.onPrimaryContainer,
    )
  Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Button(
      onClick = onPlay,
      enabled = enabled,
      modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
      colors =
        ButtonDefaults.buttonColors(
          contentColor = if (colors.primary.luminance() > 0.179f) Color.Black else Color.White,
        ),
      shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 5.dp, bottomEnd = 5.dp),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
      Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
      Spacer(Modifier.width(6.dp))
      Text("Play", style = MaterialTheme.typography.labelLarge)
    }
    FilledTonalButton(
      colors = tonalColors,
      onClick = onShuffle,
      enabled = enabled,
      modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
      shape = RoundedCornerShape(5.dp),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
      Icon(Icons.Filled.Shuffle, null, Modifier.size(20.dp))
      Spacer(Modifier.width(6.dp))
      Text("Shuffle", style = MaterialTheme.typography.labelLarge)
    }
    Box(Modifier.fillMaxHeight()) {
      FilledTonalButton(
        colors = tonalColors,
        onClick = { menuExpanded = true },
        enabled = enabled,
        modifier = Modifier.width(48.dp).heightIn(min = 48.dp).fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 24.dp, bottomEnd = 24.dp),
        contentPadding = PaddingValues(0.dp),
      ) {
        Icon(Icons.Filled.MoreVert, "More actions", Modifier.size(20.dp))
      }
      DropdownMenu(expanded = menuExpanded && enabled, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(
          text = { Text("Add to queue") },
          onClick = {
            menuExpanded = false
            onAddToQueue()
          },
        )
        DropdownMenuItem(
          text = { Text("Add to playlist") },
          onClick = {
            menuExpanded = false
            onAddToPlaylist()
          },
        )
      }
    }
  }
}

@Composable
internal fun DetailEmptyState(
  isLoading: Boolean,
  error: String?,
  emptyMessage: String,
  onRetry: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (isLoading) {
      LibraryLoadingState(Modifier.size(48.dp))
    } else {
      Text(
        if (error != null) "Couldn't load songs" else emptyMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (error != null) {
        TextButton(onClick = onRetry) { Text("Retry") }
      }
    }
  }
}
