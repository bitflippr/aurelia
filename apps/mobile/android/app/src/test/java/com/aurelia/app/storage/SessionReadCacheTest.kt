package com.aurelia.app.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionReadCacheTest {
  @Test
  fun recreatedAndConcurrentConsumersShareAnInFlightRead() =
    runTest {
      val cache = SessionReadCache<String, List<String>>(backgroundScope)
      val finish = CompletableDeferred<List<String>>()
      var calls = 0
      val first =
        async {
          cache.get("profile") {
            calls++
            finish.await()
          }
        }
      runCurrent()
      first.cancelAndJoin()
      val recreated = async { cache.get("profile") { error("Must join the original read") } }
      val anotherScreen = async { cache.get("profile") { error("Must share the same read") } }
      finish.complete(listOf("original"))
      assertEquals(listOf("original"), recreated.await())
      assertEquals(recreated.await(), anotherScreen.await())
      assertEquals(1, calls)
    }

  @Test
  fun emptyResultsStayLoadedUntilExplicitRefresh() =
    runTest {
      val cache = SessionReadCache<String, List<String>>(backgroundScope)
      var calls = 0
      repeat(3) {
        assertEquals(
          emptyList<String>(),
          cache.get("profile") {
            calls++
            emptyList()
          },
        )
      }
      assertEquals(1, calls)
      assertEquals(
        listOf("new"),
        cache.get("profile", force = true) {
          calls++
          listOf("new")
        },
      )
      assertEquals(2, calls)
    }

  @Test
  fun failedReadsNeedExplicitRetryInsteadOfRetryingOnEveryRecreation() =
    runTest {
      supervisorScope {
        val cache = SessionReadCache<String, String>(this)
        var calls = 0
        repeat(2) {
          val error =
            runCatching {
              cache.get("profile") {
                calls++
                error("offline")
              }
            }.exceptionOrNull()
          assertEquals("offline", error?.message)
        }
        assertEquals(1, calls)
        assertEquals(
          "online",
          cache.get("profile", force = true) {
            calls++
            "online"
          },
        )
        assertEquals(2, calls)
      }
    }

  @Test
  fun profilesAreIsolatedAndANewProcessGetsANewRead() =
    runTest {
      val cache = SessionReadCache<String, String>(backgroundScope)
      assertEquals("A", cache.get("profile-a") { "A" })
      assertEquals("B", cache.get("profile-b") { "B" })
      assertEquals("A", cache.get("profile-a") { error("Already loaded") })
      val nextProcess = SessionReadCache<String, String>(backgroundScope)
      assertEquals("new A", nextProcess.get("profile-a") { "new A" })
    }

  @Test
  fun invalidationPreventsAnOldRequestFromReplacingTheFreshResult() =
    runTest {
      val cache = SessionReadCache<String, String>(backgroundScope)
      val finishOld = CompletableDeferred<Unit>()
      val old =
        async {
          cache.get("profile") {
            withContext(NonCancellable) { finishOld.await() }
            "old"
          }
        }
      runCurrent()
      cache.clear()
      assertEquals("fresh", cache.get("profile") { "fresh" })
      finishOld.complete(Unit)
      assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
      assertEquals("fresh", cache.get("profile") { error("Already refreshed") })
    }
}
