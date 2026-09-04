package com.aurelia.app.ai

import uniffi.aurelia_core.Song
import kotlin.math.abs

object SmartPlaylistPlanner {
  fun prepareCandidates(
    songs: List<Song>,
    request: SmartPlaylistRequest,
  ): List<CandidateSong> {
    val queryTerms = request.prompt.tokenize()
    return songs
      .asSequence()
      .distinctBy { it.safeId() }
      .filter { it.safeId().isNotBlank() && it.safeName().isNotBlank() }
      .mapIndexed { index, song ->
        CandidateSong(
          alias = "s${index + 1}",
          song = song,
          score = scoreSong(song, queryTerms),
        )
      }.sortedWith(compareByDescending<CandidateSong> { it.score }.thenBy { it.song.safeName() })
      .mapIndexed { index, candidate -> candidate.copy(alias = "s${index + 1}") }
      .toList()
  }

  fun buildGemmaPrompt(request: SmartPlaylistRequest): String {
    val count = request.targetCount.coerceIn(MIN_SMART_PLAYLIST_SIZE, MAX_SMART_PLAYLIST_SIZE)

    return """
      Create a playlist from the user's local music library.
      Request: ${request.prompt}
      Target track count: $count

      You have tools for inspecting the full library. Use them before choosing tracks:
      - search_songs finds candidates by any title, artist, album, genre, year, or prompt terms.
      - list_songs pages through the complete library.
      - get_songs returns exact metadata for aliases you are considering.
      - submit_playlist sends your final playlist to the app.

      Select only aliases returned by the tools. Do not invent songs or aliases.
      Prioritize the request over popularity. If the request mentions mood, intensity, era, artist, genre, or listening context, choose tracks whose metadata best supports it.
      Order the playlist for a coherent listening flow.
      Finish by calling submit_playlist with exactly $count aliases when enough matching songs exist.

      Do not return the final playlist as text. The app only accepts the submit_playlist tool result.
      """.trimIndent()
  }

  fun validateSubmittedPlaylist(
    submittedPlaylist: SubmittedPlaylist,
    candidates: List<CandidateSong>,
    targetCount: Int,
  ): SmartPlaylistPreview =
    validateParsed(
      submittedPlaylist = submittedPlaylist,
      candidates = candidates,
      targetCount = targetCount,
    )

  private fun validateParsed(
    submittedPlaylist: SubmittedPlaylist,
    candidates: List<CandidateSong>,
    targetCount: Int,
  ): SmartPlaylistPreview {
    val candidateByAlias = candidates.associateBy { it.alias }
    val wanted = targetCount.coerceIn(MIN_SMART_PLAYLIST_SIZE, MAX_SMART_PLAYLIST_SIZE)
    val aliases =
      submittedPlaylist.aliases
        .asSequence()
        .filter { it in candidateByAlias }
        .distinct()
        .toList()

    val songs = aliases.take(wanted).mapNotNull { candidateByAlias[it]?.song }
    require(songs.isNotEmpty()) { "The on-device model did not return usable songs" }
    if (songs.size < wanted && candidates.size >= wanted) {
      AiDebugLog.warn("Gemma returned ${songs.size}/$wanted usable aliases; showing the model-selected subset")
    }

    return SmartPlaylistPreview(
      name = submittedPlaylist.name.ifBlank { fallbackName(targetCount) },
      description = submittedPlaylist.description.ifBlank { "Generated from your local library." },
      songs = songs,
      usedOnDeviceModel = true,
    )
  }

  private fun scoreSong(
    song: Song,
    queryTerms: Set<String>,
  ): Int {
    val searchable =
      listOfNotNull(
        song.safeName(),
        song.album,
        song.artists?.joinToString(" "),
        song.albumArtists?.joinToString(" "),
        song.genres?.joinToString(" "),
        song.year?.toString(),
      ).joinToString(" ").lowercase()

    var score = 1
    queryTerms.forEach { term ->
      if (searchable.contains(term)) score += 20
    }
    if (song.isFavorite == true) score += 6
    score += (song.playCount ?: 0).coerceAtMost(10)
    if (!song.datePlayed.isNullOrBlank()) score += 3

    val promptYears = queryTerms.mapNotNull { it.toIntOrNull() }
    val songYear = song.year
    if (songYear != null && promptYears.any { abs(it - songYear) <= 3 }) score += 18

    return score
  }

  private fun String.tokenize(): Set<String> =
    lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter { it.length >= 3 }
      .filterNot { it in stopWords }
      .toSet()

  private fun Song.safeId(): String = runCatching { id }.getOrNull().orEmpty()

  private fun Song.safeName(): String = runCatching { name }.getOrNull().orEmpty()

  private fun fallbackName(count: Int): String = "Smart Mix $count"

  private val stopWords =
    setOf(
      "the",
      "and",
      "for",
      "with",
      "from",
      "that",
      "this",
      "make",
      "playlist",
      "songs",
      "music",
      "track",
      "tracks",
    )
}
