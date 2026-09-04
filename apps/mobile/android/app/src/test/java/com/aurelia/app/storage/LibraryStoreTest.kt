package com.aurelia.app.storage

import com.aurelia.app.utils.SessionData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStoreTest {
  private val session = SessionData("server", "user", "token", "/profile-a")

  @Test
  fun twoScreensShareOneRefreshAndEmptySuccessIsFresh() =
    runTest {
      var calls = 0
      val finish = CompletableDeferred<Unit>()
      val store =
        LibraryStore(this, { 100L }, { emptyList() }, {
          calls++
          finish.await()
          emptyList()
        })
      store.ensureLoaded(session)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(1, calls)
      assertTrue(store.snapshots.value.isLoading)
      finish.complete(Unit)
      runCurrent()
      assertFalse(store.snapshots.value.isLoading)
      assertEquals(emptyList<Any>(), store.snapshots.value.songs)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(1, calls)
    }

  @Test
  fun failedRefreshCanRetry() =
    runTest {
      var calls = 0
      val store =
        LibraryStore(this, { 100L }, { emptyList() }, {
          if (++calls == 1) error("offline")
          emptyList()
        })
      store.ensureLoaded(session)
      runCurrent()
      assertFalse(store.snapshots.value.isLoading)
      assertNotNull(store.snapshots.value.error)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(2, calls)
      assertNull(store.snapshots.value.error)
    }

  @Test
  fun oldProfileResponseCannotOverwriteANewerVisitToSameProfile() =
    runTest {
      val finish = CompletableDeferred<Unit>()
      var calls = 0
      val store =
        LibraryStore(this, { 100L }, { emptyList() }, {
          if (++calls == 1) {
            withContext(NonCancellable) {
              finish.await()
              error("stale failure")
            }
          }
          emptyList()
        })
      store.ensureLoaded(session)
      runCurrent()
      store.clear()
      store.ensureLoaded(session)
      runCurrent()
      finish.complete(Unit)
      runCurrent()
      assertNull(store.snapshots.value.error)
      assertFalse(store.snapshots.value.isLoading)
      assertEquals(session.appDataDir, store.snapshots.value.profilePath)
    }
}
