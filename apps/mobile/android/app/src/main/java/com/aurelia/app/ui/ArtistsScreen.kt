package com.aurelia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.ArtistAvatar
import com.aurelia.app.ui.components.BottomBarDimensions
import com.aurelia.app.ui.components.LibraryMessageState
import com.aurelia.app.ui.components.LibraryScreenHeader
import com.aurelia.app.ui.components.MediaListItem
import com.aurelia.app.ui.navigation.Screen
import com.aurelia.app.utils.jellyfinPrimaryImageUrl

data class ArtistItem(
  val id: String,
  val name: String,
  val songCount: Int,
  val albumCount: Int,
  val imageUrl: String?,
)

@Composable
fun ArtistsScreen(
  libraryViewModel: LibraryViewModel,
  sessionStore: SessionStore,
  onNavigateToArtist: (Screen.ArtistDetail) -> Unit = {},
  hasPlayerBar: Boolean = false,
) {
  val state by libraryViewModel.state.collectAsStateWithLifecycle()
  val colors = MaterialTheme.colorScheme
  val bottomPadding = BottomBarDimensions.calculateBottomPadding(hasPlayerBar)
  val serverUrl = remember { sessionStore.getServerUrl() }
  val token = remember { sessionStore.getToken() }

  // Group songs by artist (using artist ID)
  val artists =
    remember(state.songs) {
      val artistData =
        mutableMapOf<String, Triple<String, MutableList<uniffi.aurelia_core.Song>, MutableSet<String>>>()

      for (song in state.songs) {
        val artistNames = song.artists ?: listOf("Unknown Artist")
        val artistIds = song.artistIds ?: emptyList()

        for ((index, artistName) in artistNames.withIndex()) {
          val artistId = artistIds.getOrNull(index) ?: artistName // Fall back to name as ID
          val data =
            artistData.getOrPut(artistId) { Triple(artistName, mutableListOf(), mutableSetOf()) }
          data.second.add(song)
          song.albumId?.let { data.third.add(it) }
        }
      }

      artistData
        .map { (id, data) ->
          ArtistItem(
            id = id,
            name = data.first,
            songCount = data.second.size,
            albumCount = data.third.size,
            imageUrl = jellyfinPrimaryImageUrl(serverUrl, id, token),
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

      artists.isEmpty() -> {
        LibraryMessageState(
          icon = Icons.Filled.Person,
          title = "No artists yet",
          subtitle = "Sync your library to browse artists here.",
          modifier = Modifier.fillMaxSize(),
        )
      }

      else -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 8.dp,
              bottom = bottomPadding,
            ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(key = "header") {
            LibraryScreenHeader(
              title = "Artists",
              subtitle = "${artists.size} artists",
              modifier = Modifier.padding(horizontal = 8.dp),
            )
          }

          items(
            items = artists,
            key = { it.id },
          ) { artist ->
            MediaListItem(
              title = artist.name,
              subtitle = "${artist.songCount} songs - ${artist.albumCount} albums",
              imageUrl = null,
              leadingContent = { ArtistAvatar(imageUrl = artist.imageUrl) },
              onClick = {
                onNavigateToArtist(Screen.ArtistDetail(artist.id, artist.name))
              },
            )
          }
        }
      }
    }
  }
}
