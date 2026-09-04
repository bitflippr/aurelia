package com.aurelia.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurelia.app.ui.theme.SquircleShape
import com.aurelia.app.utils.optimizedArtworkUrl

@Composable
fun LibraryScreenHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    trailing?.invoke()
  }
}

@Composable
fun LibrarySectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Black,
      color = MaterialTheme.colorScheme.onBackground,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun LibraryLoadingState(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
fun LibraryMessageState(
  icon: ImageVector,
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  Column(
    modifier = modifier.padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Surface(
      modifier = Modifier.size(76.dp),
      shape = SquircleShape,
      color = if (isError) colors.errorContainer else colors.surfaceContainerHigh,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
          modifier = Modifier.size(34.dp),
        )
      }
    }
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = if (isError) colors.error else colors.onSurface,
      textAlign = TextAlign.Center,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    if (!actionLabel.isNullOrBlank() && onAction != null) {
      Button(onClick = onAction) {
        Text(actionLabel)
      }
    }
  }
}

@Composable
fun LibraryArtwork(
  imageUrl: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  size: Dp = 56.dp,
  shape: Shape = RoundedCornerShape(14.dp),
  style: AlbumArtStyle = AlbumArtStyle.Song,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
  contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f),
) {
  val placeholder =
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
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = placeholder,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(size * 0.42f),
      )
      if (!imageUrl.isNullOrBlank()) {
        val context = LocalContext.current
        val pxSize = with(LocalDensity.current) { size.toPx().toInt() }
        AsyncImage(
          model =
            ImageRequest
              .Builder(context)
              .data(optimizedArtworkUrl(imageUrl, pxSize))
              .crossfade(false)
              .size(pxSize)
              .build(),
          contentDescription = contentDescription,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaListItem(
  title: String,
  subtitle: String,
  imageUrl: String?,
  modifier: Modifier = Modifier,
  metadata: String? = null,
  artworkStyle: AlbumArtStyle = AlbumArtStyle.Song,
  artworkShape: Shape = RoundedCornerShape(14.dp),
  isCurrent: Boolean = false,
  isPlaying: Boolean = false,
  showMore: Boolean = false,
  leadingLabel: String? = null,
  leadingContent: (@Composable () -> Unit)? = null,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  onMoreClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor = if (isCurrent) colors.primaryContainer else colors.surfaceContainerLow
  val titleColor = if (isCurrent) colors.onPrimaryContainer else colors.onSurface
  val subtitleColor =
    if (isCurrent) colors.onPrimaryContainer.copy(alpha = 0.72f) else colors.onSurfaceVariant

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(22.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(22.dp),
    color = containerColor,
    tonalElevation = if (isCurrent) 2.dp else 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (leadingLabel != null) {
        Box(
          modifier = Modifier.width(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (isPlaying) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = colors.primary,
              modifier = Modifier.size(20.dp),
            )
          } else {
            Text(
              text = leadingLabel,
              style = MaterialTheme.typography.labelLarge,
              color = subtitleColor,
              maxLines = 1,
            )
          }
        }
      } else if (leadingContent != null) {
        leadingContent()
      } else {
        Box(contentAlignment = Alignment.Center) {
          LibraryArtwork(
            imageUrl = imageUrl,
            contentDescription = title,
            size = 54.dp,
            shape = artworkShape,
            style = artworkStyle,
            containerColor = if (isCurrent) colors.primary.copy(alpha = 0.18f) else colors.surfaceContainerHighest,
          )
          if (isPlaying) {
            Surface(
              modifier = Modifier.size(54.dp),
              shape = artworkShape,
              color = colors.primary.copy(alpha = 0.72f),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Filled.PlayArrow,
                  contentDescription = null,
                  tint = colors.onPrimary,
                  modifier = Modifier.size(24.dp),
                )
              }
            }
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
          color = titleColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = subtitleColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      if (!metadata.isNullOrBlank()) {
        Text(
          text = metadata,
          style = MaterialTheme.typography.labelMedium,
          color = subtitleColor,
          maxLines = 1,
        )
      }

      trailing?.invoke()

      if (showMore && onMoreClick != null) {
        IconButton(onClick = onMoreClick) {
          Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "More options",
            tint = subtitleColor,
          )
        }
      }
    }
  }
}

@Composable
fun MediaGridCard(
  title: String,
  subtitle: String,
  imageUrl: String?,
  modifier: Modifier = Modifier,
  metadata: String? = null,
  artworkStyle: AlbumArtStyle = AlbumArtStyle.Album,
  shape: Shape = RoundedCornerShape(24.dp),
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  ElevatedCard(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    onClick = onClick,
  ) {
    Column {
      LibraryArtwork(
        imageUrl = imageUrl,
        contentDescription = title,
        modifier = Modifier.fillMaxWidth(),
        size = 180.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        style = artworkStyle,
        containerColor = colors.surfaceContainerHighest,
      )
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!metadata.isNullOrBlank()) {
          Text(
            text = metadata,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
fun ArtistAvatar(
  modifier: Modifier = Modifier,
  size: Dp = 56.dp,
  imageUrl: String? = null,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
  Surface(
    modifier = modifier.size(size),
    shape = CircleShape,
    color = containerColor,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        modifier = Modifier.size(size * 0.48f),
      )
      if (!imageUrl.isNullOrBlank()) {
        val context = LocalContext.current
        val pxSize = with(LocalDensity.current) { size.toPx().toInt() }
        AsyncImage(
          model =
            ImageRequest
              .Builder(context)
              .data(optimizedArtworkUrl(imageUrl, pxSize))
              .crossfade(false)
              .size(pxSize)
              .build(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
    }
  }
}

@Composable
fun DetailHeroGradient(): Brush {
  val colors = MaterialTheme.colorScheme
  return Brush.verticalGradient(
    colors =
      listOf(
        colors.primaryContainer.copy(alpha = 0.9f),
        colors.secondaryContainer.copy(alpha = 0.42f),
        colors.background,
      ),
  )
}

@Composable
fun ActionButtonRow(
  onPlay: () -> Unit,
  onShuffle: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
  ) {
    Button(
      onClick = onPlay,
      enabled = enabled,
      modifier =
        Modifier
          .height(54.dp)
          .width(156.dp),
      shape = RoundedCornerShape(topStart = 27.dp, bottomStart = 27.dp, topEnd = 10.dp, bottomEnd = 10.dp),
      colors =
        ButtonDefaults.buttonColors(
          containerColor = colors.primary,
          contentColor = colors.onPrimary,
        ),
      contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(23.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Play",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }

    Spacer(modifier = Modifier.width(8.dp))

    Button(
      onClick = onShuffle,
      enabled = enabled,
      modifier = Modifier.height(54.dp),
      shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 27.dp, bottomEnd = 27.dp),
      colors =
        ButtonDefaults.buttonColors(
          containerColor = colors.secondaryContainer,
          contentColor = colors.onSecondaryContainer,
        ),
      contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.Shuffle,
        contentDescription = "Shuffle",
        modifier = Modifier.size(23.dp),
      )
    }
  }
}
