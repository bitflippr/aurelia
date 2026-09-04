package com.aurelia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.ui.components.AlbumArtStyle
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaGridCard
import com.aurelia.app.ui.navigation.Screen

data class AlbumItem(
  val id: String,
  val name: String,
  val artist: String,
  val albumArtUrl: String?,
  val songCount: Int,
)

@Composable
fun AlbumsScreen(
  libraryViewModel: LibraryViewModel,
  onNavigateToAlbum: (Screen.AlbumDetail) -> Unit = {},
  hasPlayerBar: Boolean = false,
) {
  val state by libraryViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)

  // Group songs by album
  val albums =
    remember(state.songs) {
      state.songs
        .filter { it.albumId != null }
        .groupBy { it.albumId }
        .map { (albumId, songs) ->
          val firstSong = songs.first()
          AlbumItem(
            id = albumId ?: "",
            name = firstSong.album ?: "Unknown Album",
            artist = firstSong.artists?.firstOrNull() ?: "Unknown Artist",
            albumArtUrl = firstSong.albumArtUrl,
            songCount = songs.size,
          )
        }.sortedBy { it.name.lowercase() }
    }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .statusBarsPadding(),
  ) {
    when {
      state.isLoading -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(color = colors.primary)
        }
      }

      albums.isEmpty() -> {
        LibraryMessageState(
          icon = Icons.Filled.Album,
          title = "No albums yet",
          subtitle = "Sync your library to browse albums here.",
          modifier = Modifier.fillMaxSize(),
        )
      }

      else -> {
        LazyVerticalGrid(
          columns = GridCells.Fixed(2),
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 16.dp,
              bottom = bottomPadding,
            ),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryScreenHeader(
              title = "Albums",
              subtitle = "${albums.size} albums",
              modifier = Modifier.padding(horizontal = 8.dp),
            )
          }

          items(
            items = albums,
            key = { it.id },
          ) { album ->
            MediaGridCard(
              title = album.name,
              subtitle = album.artist,
              metadata = "${album.songCount} songs",
              imageUrl = album.albumArtUrl,
              artworkStyle = AlbumArtStyle.Album,
              onClick = {
                onNavigateToAlbum(Screen.AlbumDetail(album.id, album.name))
              },
            )
          }
        }
      }
    }
  }
}
