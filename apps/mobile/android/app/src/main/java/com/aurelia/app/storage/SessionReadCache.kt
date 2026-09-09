package com.aurelia.app.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Shares completed reads and in-flight work independently of a screen's coroutine lifetime. */
internal class SessionReadCache<K, V>(
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val entries = mutableMapOf<K, Deferred<V>>()

  suspend fun get(
    key: K,
    force: Boolean = false,
    load: suspend () -> V,
  ): V {
    val entry =
      synchronized(this) {
        if (force) entries.remove(key)?.cancel()
        // Empty results and failures are also retained; retries must be intentional.
        entries.getOrPut(key) { scope.async { load() } }
      }
    return entry.await()
  }

  @Synchronized
  fun invalidate(key: K) {
    entries.remove(key)?.cancel()
  }

  @Synchronized
  fun clear() {
    entries.values.forEach { it.cancel() }
    entries.clear()
  }
}
