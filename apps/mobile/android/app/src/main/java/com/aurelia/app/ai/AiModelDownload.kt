package com.aurelia.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OnDeviceAiModel(
  val id: String,
  val name: String,
  val fileName: String,
  val downloadUrl: String,
  val sizeLabel: String,
  val sizeBytes: Long,
  val sha256: String,
)

sealed interface AiModelDownloadState {
  data object Idle : AiModelDownloadState

  data class Ready(
    val path: String,
  ) : AiModelDownloadState

  data class Missing(
    val expectedPath: String?,
  ) : AiModelDownloadState

  data class Downloading(
    val modelName: String,
    val bytesRead: Long,
    val totalBytes: Long?,
  ) : AiModelDownloadState

  data class Error(
    val message: String,
  ) : AiModelDownloadState
}

object OnDeviceAiModels {
  val default =
    OnDeviceAiModel(
      id = "gemma-4-e2b-it",
      name = "Gemma 4 E2B IT",
      fileName = "gemma-4-E2B-it.litertlm",
      downloadUrl =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
          "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
      sizeLabel = "2.6 GB",
      sizeBytes = 2_588_147_712L,
      sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    )
}

class AiModelDownloader(
  private val client: OkHttpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build(),
) {
  private val downloadLock = Mutex()

  suspend fun download(
    model: OnDeviceAiModel,
    modelsDir: File,
    onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
  ): File =
    downloadLock.withLock {
      check(modelsDir.isDirectory || modelsDir.mkdirs()) { "Could not create model directory" }
      val destination = File(modelsDir, model.fileName)
      // The digest also versions partial files: never resume bytes from a different model revision.
      val partial = File(modelsDir, "${model.fileName}.${model.sha256}.download")
      if (partial.length() >= model.sizeBytes) partial.delete()
      val offset = partial.length()
      val request =
        Request
          .Builder()
          .url(model.downloadUrl)
          .apply { if (offset > 0) header("Range", "bytes=$offset-") }
          .build()
      val closed = CompletableDeferred<Unit>()
      try {
        suspendCancellableCoroutine { continuation ->
          val call = client.newCall(request)
          continuation.invokeOnCancellation { call.cancel() }
          call.enqueue(
            object : Callback {
              override fun onFailure(
                call: Call,
                e: IOException,
              ) {
                if (continuation.isActive) continuation.resumeWithException(e)
                closed.complete(Unit)
              }

              override fun onResponse(
                call: Call,
                response: Response,
              ) {
                try {
                  response.use {
                    check(response.isSuccessful) { "Model download failed: HTTP ${response.code}" }
                    val resumed = response.code == 206
                    if (resumed) {
                      check(
                        response.header("Content-Range") == "bytes $offset-${model.sizeBytes - 1}/${model.sizeBytes}",
                      ) {
                        "Server returned an invalid model byte range"
                      }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytesRead = if (resumed) offset else 0L

                    fun checkActive() {
                      if (!continuation.isActive) throw CancellationException("Model download cancelled")
                    }
                    if (resumed && offset > 0) {
                      partial.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                          checkActive()
                          val read = input.read(buffer)
                          if (read == -1) break
                          digest.update(buffer, 0, read)
                        }
                      }
                    }
                    var lastProgress = System.nanoTime()
                    onProgress(bytesRead, model.sizeBytes)
                    response.body.byteStream().use { input ->
                      FileOutputStream(partial, resumed).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                          checkActive()
                          val read = input.read(buffer)
                          if (read == -1) break
                          bytesRead += read
                          check(bytesRead <= model.sizeBytes) { "Model exceeds expected size" }
                          output.write(buffer, 0, read)
                          digest.update(buffer, 0, read)
                          val now = System.nanoTime()
                          if (now - lastProgress >= 250_000_000L) {
                            onProgress(bytesRead, model.sizeBytes)
                            lastProgress = now
                          }
                        }
                      }
                    }
                    checkActive()
                    check(bytesRead == model.sizeBytes) { "Model download was incomplete; retry to resume" }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualHash != model.sha256) {
                      partial.delete()
                      error("Model checksum did not match")
                    }
                    check(partial.renameTo(destination)) { "Could not finalize downloaded model" }
                    onProgress(bytesRead, model.sizeBytes)
                    if (continuation.isActive) continuation.resume(destination)
                  }
                } catch (error: Exception) {
                  if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                  closed.complete(Unit)
                }
              }
            },
          )
        }
      } finally {
        // Do not let a retry reuse the partial file while the cancelled callback still has it open.
        withContext(NonCancellable) { closed.await() }
      }
    }
}
