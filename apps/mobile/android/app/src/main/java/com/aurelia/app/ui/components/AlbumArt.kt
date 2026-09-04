package com.aurelia.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.utils.optimizedArtworkUrl

enum class AlbumArtStyle {
  Song,
  Album,
  Playlist,
}

@Composable
fun AlbumArt(
  imageUrl: String?,
  modifier: Modifier = Modifier,
  size: Dp = 48.dp,
  cornerRadius: Dp = 8.dp,
  style: AlbumArtStyle = AlbumArtStyle.Song,
  containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
  contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
) {
  val shape = RoundedCornerShape(cornerRadius)
  val icon =
    when (style) {
      AlbumArtStyle.Song -> Icons.Filled.MusicNote
      AlbumArtStyle.Album -> Icons.Filled.Album
      AlbumArtStyle.Playlist -> Icons.AutoMirrored.Filled.PlaylistPlay
    }

  Surface(
    modifier =
      modifier
        .size(size)
        .clip(shape),
    shape = shape,
    color = containerColor,
  ) {
    if (imageUrl.isNullOrBlank()) {
      PlaceholderIcon(icon = icon, contentColor = contentColor, size = size)
    } else {
      val context = LocalContext.current
      val pxSize = with(LocalDensity.current) { size.toPx().toInt() }
      Box(modifier = Modifier.fillMaxSize()) {
        PlaceholderIcon(icon = icon, contentColor = contentColor, size = size)
        AsyncImage(
          model =
            ImageRequest
              .Builder(context)
              .data(optimizedArtworkUrl(imageUrl, pxSize))
              .crossfade(false)
              .size(pxSize)
              .build(),
          contentDescription = "Album art",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
    }
  }
}

@Composable
private fun PlaceholderIcon(
  icon: ImageVector,
  contentColor: Color,
  size: Dp,
) {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(size * 0.4f),
    )
  }
}
