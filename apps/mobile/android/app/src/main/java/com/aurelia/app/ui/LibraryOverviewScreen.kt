package com.aurelia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.navigation.Screen

private enum class LibraryCategory(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val screen: Screen,
) {
  Songs("Songs", "Every track in your collection", Icons.Filled.MusicNote, Screen.Songs),
  Albums("Albums", "Browse your records", Icons.Filled.Album, Screen.Albums),
  Artists("Artists", "Explore by artist", Icons.Filled.Person, Screen.Artists),
  Playlists(
    "Playlists",
    "Your playlists and smart playlists",
    Icons.AutoMirrored.Filled.PlaylistPlay,
    Screen.Playlists,
  ),
}

@Composable
internal fun LibraryOverviewScreen(
  hasPlayerBar: Boolean,
  onNavigate: (Screen) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  LazyColumn(
    modifier = Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding =
      PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = 24.dp,
        bottom = BottomBarDimensions.calculateBottomPadding(hasPlayerBar),
      ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item(key = "header") {
      Row(
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Your collection", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
          Text(
            "Library",
            style =
              MaterialTheme.typography.headlineLarge.copy(
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
              ),
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
          )
        }
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Filled.Settings, "Settings", tint = colors.onSurfaceVariant)
        }
      }
    }
    items(LibraryCategory.entries, key = { it.name }) { category ->
      Surface(
        onClick = { onNavigate(category.screen) },
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLow,
      ) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
              Icon(category.icon, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              category.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium,
              color = colors.onSurface,
            )
            Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
          }
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
      }
    }
  }
}
