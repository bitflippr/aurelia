package com.aurelia.app.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AiModelDownloaderTest {
  @get:Rule val directory = TemporaryFolder()

  private fun model(
    server: MockWebServer,
    content: String,
  ) = OnDeviceAiModel(
    "test",
    "Test",
    "test.bin",
    server.url("/model").toString(),
    "small",
    content.length.toLong(),
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) },
  )

  @Test
  fun resumesPinnedPartialAndValidatesCompleteFile() =
    runBlocking {
      MockWebServer().use { server ->
        val model = model(server, "abcdef")
        File(directory.root, "${model.fileName}.${model.sha256}.download").writeText("abc")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        val file = AiModelDownloader().download(model, directory.root) { _, _ -> }
        assertEquals("abcdef", file.readText())
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
      }
    }

  @Test
  fun serverIgnoringRangeRestartsInsteadOfAppendingDuplicateBytes() =
    runBlocking {
      MockWebServer().use { server ->
        val model = model(server, "abcdef")
        File(directory.root, "${model.fileName}.${model.sha256}.download").writeText("abc")
        server.enqueue(MockResponse().setBody("abcdef"))
        assertEquals("abcdef", AiModelDownloader().download(model, directory.root) { _, _ -> }.readText())
      }
    }

  @Test
  fun wrongDigestNeverReplacesExistingModel() =
    runBlocking {
      MockWebServer().use { server ->
        val model = model(server, "abcdef")
        val existing = File(directory.root, model.fileName).apply { writeText("previous") }
        server.enqueue(MockResponse().setBody("broken"))
        try {
          AiModelDownloader().download(model, directory.root) { _, _ -> }
          fail("Expected checksum failure")
        } catch (error: IllegalStateException) {
          assertEquals("Model checksum did not match", error.message)
        }
        assertEquals("previous", existing.readText())
      }
    }

  @Test
  fun cancellationClosesThePartialFileBeforeRetry() =
    runBlocking {
      MockWebServer().use { server ->
        val content = "a".repeat(4096)
        val model = model(server, content)
        val partial = File(directory.root, "${model.fileName}.${model.sha256}.download")
        val downloader = AiModelDownloader()
        server.enqueue(MockResponse().setBody(content).throttleBody(1024, 1, TimeUnit.SECONDS))
        val download = async { downloader.download(model, directory.root) { _, _ -> } }
        withTimeout(5_000) { while (partial.length() == 0L) delay(10) }
        withTimeout(2_000) { download.cancelAndJoin() }
        assertFalse(File(directory.root, model.fileName).exists())
        server.enqueue(MockResponse().setBody(content))
        assertEquals(content, downloader.download(model, directory.root) { _, _ -> }.readText())
      }
    }
}
