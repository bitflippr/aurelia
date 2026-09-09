package com.aurelia.app.ui

import android.util.Log
import com.aurelia.app.storage.SessionReadCache
import com.aurelia.app.utils.SessionData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.getInstantMix

/** One set of listening mixes per profile and app process, including empty results. */
internal object HomeMixStore {
  private val cache = SessionReadCache<SessionData, List<HomeMix>>()

  suspend fun loadOnce(
    session: SessionData,
    songs: List<Song>,
  ): List<HomeMix> = cache.get(session) { buildMixes(session.serverUrl, session.token, songs) }

  private suspend fun buildMixes(
    serverUrl: String,
    token: String,
    songs: List<Song>,
  ): List<HomeMix> =
    coroutineScope {
      val played = songs.filter { (it.playCount ?: 0) > 0 }
      if (played.isEmpty()) return@coroutineScope emptyList()

      // Top artists by cumulative play count
      val topArtists =
        played
          .flatMap { song ->
            val plays = song.playCount ?: 0
            song.artistIds.orEmpty().zip(song.artists.orEmpty()).map { (id, name) ->
              Triple(id, name, plays)
            }
          }.groupBy { it.first }
          .map { (_, entries) -> entries.first().first to entries.sumOf { it.third } }
          .sortedByDescending { it.second }
          .map { it.first }

      val topSongId = played.maxByOrNull { it.playCount ?: 0 }?.id

      val seedIds = (topArtists + listOfNotNull(topSongId)).distinct().take(UiConstants.MIX_SEEDS_LIMIT)

      // Build all mixes in parallel, then swap them in atomically so the row doesn't jump
      val mixes =
        seedIds
          .map { seedId ->
            async {
              try {
                val mixSongs = getInstantMix(serverUrl, token, seedId).take(UiConstants.MIX_SIZE_LIMIT)
                if (mixSongs.isEmpty()) return@async null
                HomeMix(
                  seedId = seedId,
                  seedTitle = mixSeedTitle(seedId, songs, mixSongs),
                  artworkUrl = mixSongs.firstOrNull { !it.albumArtUrl.isNullOrBlank() }?.albumArtUrl,
                  songs = mixSongs,
                )
              } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("HomeViewModel", "Failed to load instant mix for $seedId", e)
                null
              }
            }
          }.awaitAll()
          .filterNotNull()

      mixes
    }

  private fun mixSeedTitle(
    seedId: String,
    songs: List<Song>,
    mixSongs: List<Song>,
  ): String {
    val seedSong = songs.firstOrNull { it.id == seedId }
    if (seedSong != null) return seedSong.name
    val artistSong =
      (mixSongs + songs).firstOrNull { song -> song.artistIds.orEmpty().contains(seedId) }
    return artistSong?.artists.orEmpty().firstOrNull() ?: "Your mix"
  }
}
