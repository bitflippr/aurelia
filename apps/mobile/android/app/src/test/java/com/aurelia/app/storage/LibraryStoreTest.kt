package com.aurelia.app.storage

import com.aurelia.app.utils.SessionData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
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
        LibraryStore(this, { emptyList() }, {
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
      advanceTimeBy(120_000)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(1, calls)
      store.ensureLoaded(session, force = true)
      runCurrent()
      assertEquals(2, calls)
    }

  @Test
  fun failedRefreshRequiresAnExplicitRetry() =
    runTest {
      var calls = 0
      val store =
        LibraryStore(this, { emptyList() }, {
          if (++calls == 1) error("offline")
          emptyList()
        })
      store.ensureLoaded(session)
      runCurrent()
      assertFalse(store.snapshots.value.isLoading)
      assertNotNull(store.snapshots.value.error)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(1, calls)
      store.ensureLoaded(session, force = true)
      runCurrent()
      assertEquals(2, calls)
      assertNull(store.snapshots.value.error)
    }

  @Test
  fun completedSyncPublishesTheDiskSnapshotWithoutAnotherFetch() =
    runTest {
      var fetchCalls = 0
      var cacheCalls = 0
      val store =
        LibraryStore(this, {
          cacheCalls++
          emptyList()
        }, {
          fetchCalls++
          emptyList()
        })
      store.ensureLoaded(session)
      runCurrent()
      store.reloadFromCache(session)
      store.ensureLoaded(session)
      runCurrent()
      assertEquals(1, fetchCalls)
      assertEquals(2, cacheCalls)
      assertFalse(store.snapshots.value.isLoading)
      assertEquals(session.appDataDir, store.snapshots.value.profilePath)
    }

  @Test
  fun oldProfileResponseCannotOverwriteANewerVisitToSameProfile() =
    runTest {
      val finish = CompletableDeferred<Unit>()
      var calls = 0
      val store =
        LibraryStore(this, { emptyList() }, {
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
