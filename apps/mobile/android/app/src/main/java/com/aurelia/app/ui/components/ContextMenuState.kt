package com.aurelia.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import uniffi.aurelia_core.Song

/**
 * Encapsulates the three pieces of state needed for song context menus and playlist pickers.
 * Replaces repeated `remember { mutableStateOf(...) }` blocks across 5+ screens.
 */
class ContextMenuState {
  var selectedSong: Song? by mutableStateOf(null)
  var showContextMenu: Boolean by mutableStateOf(false)
  var showPlaylistPicker: Boolean by mutableStateOf(false)

  fun openContextMenu(song: Song) {
    selectedSong = song
    showContextMenu = true
  }

  fun openPlaylistPicker(song: Song) {
    selectedSong = song
    showPlaylistPicker = true
  }

  fun dismissContextMenu() {
    showContextMenu = false
  }

  fun dismissPlaylistPicker() {
    showPlaylistPicker = false
    selectedSong = null
  }
}

@Composable
fun rememberContextMenuState(): ContextMenuState = remember { ContextMenuState() }
