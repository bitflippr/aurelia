package com.aurelia.app.ai

import uniffi.aurelia_core.Song

const val DEFAULT_SMART_PLAYLIST_SIZE = 25
const val MIN_SMART_PLAYLIST_SIZE = 5
const val MAX_SMART_PLAYLIST_SIZE = 40

data class SmartPlaylistRequest(
  val prompt: String,
  val targetCount: Int = DEFAULT_SMART_PLAYLIST_SIZE,
)

data class CandidateSong(
  val alias: String,
  val song: Song,
  val score: Int,
)

data class SmartPlaylistPreview(
  val name: String,
  val description: String,
  val songs: List<Song>,
  val usedOnDeviceModel: Boolean,
  val fallbackReason: String? = null,
)

sealed interface AiGenerationState {
  data object Idle : AiGenerationState

  data class Loading(
    val message: String = "Generating on device",
  ) : AiGenerationState

  data class Preview(
    val preview: SmartPlaylistPreview,
  ) : AiGenerationState

  data class Error(
    val message: String,
  ) : AiGenerationState
}
