package com.aurelia.app.storage

import android.os.SystemClock
import com.aurelia.app.utils.SessionData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.aurelia_core.Song
import uniffi.aurelia_core.fetchSongs
import uniffi.aurelia_core.loadCachedSongs

data class LibrarySnapshot(
  val profilePath: String? = null,
  val songs: List<Song>? = null,
  val isLoading: Boolean = false,
  val error: Throwable? = null,
)

/** Owns the active profile's song snapshot and refresh, shared by native screens. */
class LibraryStore(
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  private val clock: () -> Long = SystemClock::elapsedRealtime,
  private val loadCache: suspend (String) -> List<Song> = { loadCachedSongs(it) },
  private val fetch: suspend (SessionData) -> List<Song> = {
    fetchSongs(it.serverUrl, it.token, it.userId, it.appDataDir.orEmpty())
  },
) {
  private val mutableSnapshot = MutableStateFlow(LibrarySnapshot())
  val snapshots = mutableSnapshot.asStateFlow()
  private var session: SessionData? = null
  private var job: Job? = null
  private var loadedAt: Long? = null
  private var generation = 0L

  @Synchronized
  fun ensureLoaded(
    request: SessionData,
    force: Boolean = false,
  ) {
    if (request != session) {
      generation++
      job?.cancel()
      session = request
      loadedAt = null
      mutableSnapshot.value = LibrarySnapshot(profilePath = request.appDataDir)
    }
    if (job?.isActive == true) return
    if (!force && loadedAt?.let { clock() - it < 60_000 } == true) return
    mutableSnapshot.value = mutableSnapshot.value.copy(isLoading = true, error = null)
    val requestGeneration = generation
    job =
      scope.launch {
        try {
          if (mutableSnapshot.value.songs == null && !request.appDataDir.isNullOrBlank()) {
            val cached =
              try {
                loadCache(request.appDataDir)
              } catch (
                e: CancellationException,
              ) {
                throw e
              } catch (_: Exception) {
                null
              }
            if (cached != null) publish(requestGeneration) { it.copy(songs = cached) }
          }
          val songs = fetch(request)
          synchronized(this@LibraryStore) {
            if (generation == requestGeneration) {
              loadedAt = clock()
              mutableSnapshot.value = LibrarySnapshot(request.appDataDir, songs)
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          publish(requestGeneration) { it.copy(isLoading = false, error = e) }
        }
      }
  }

  @Synchronized
  fun clear() {
    generation++
    job?.cancel()
    job = null
    session = null
    loadedAt = null
    mutableSnapshot.value = LibrarySnapshot()
  }

  @Synchronized
  private fun publish(
    requestGeneration: Long,
    update: (LibrarySnapshot) -> LibrarySnapshot,
  ) {
    if (generation == requestGeneration) mutableSnapshot.value = update(mutableSnapshot.value)
  }
}
