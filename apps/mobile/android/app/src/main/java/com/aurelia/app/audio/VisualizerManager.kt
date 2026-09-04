package com.aurelia.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisualizerState(
  val enabled: Boolean = false,
  val available: Boolean = false,
  val hasPermission: Boolean = false,
  val frequencyData: ByteArray = ByteArray(0),
  val waveform: ByteArray = ByteArray(0),
  val frameId: Long = 0L,
)

enum class VisualizerStyle {
  BARS,
  CURVE,
  WAVE,
}

class VisualizerManager(
  private val context: Context,
  private val audioSessionId: Int,
) {
  private var visualizer: Visualizer? = null
  private val _state = MutableStateFlow(VisualizerState())
  val state: StateFlow<VisualizerState> = _state.asStateFlow()

  private val mainHandler = Handler(Looper.getMainLooper())
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null
  private var captureRunnable: Runnable? = null
  private var pendingEnable = false

  private var waveBuffer = ByteArray(0)
  private var freqBuffer = ByteArray(0)
  private val processedFrequency = ByteArray(VisualizerSignalProcessor.OUTPUT_FREQUENCY_BIN_COUNT)
  private val processedWaveform = ByteArray(VisualizerSignalProcessor.OUTPUT_WAVEFORM_SAMPLE_COUNT)
  private val signalProcessor = VisualizerSignalProcessor()

  private var frameCounter = 0L
  private var lastFftErrorLogMs = 0L
  private var lastWaveErrorLogMs = 0L
  private var lastCaptureErrorLogMs = 0L
  private var isReleased = false

  // Propagates every state change to AudioManager so its StateFlow stays in sync
  var stateCallback: ((VisualizerState) -> Unit)? = null

  private val enableRunnable =
    Runnable {
      if (isReleased || !pendingEnable) return@Runnable
      enableInternal()
    }

  private fun setState(newState: VisualizerState) {
    _state.value = newState
    stateCallback?.invoke(newState)
  }

  init {
    val hasPermission =
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
      ) == PackageManager.PERMISSION_GRANTED

    setState(_state.value.copy(hasPermission = hasPermission))
    Log.d(TAG, "VisualizerManager initialized for session $audioSessionId, permission=$hasPermission")
  }

  fun setEnabled(enabled: Boolean) {
    if (isReleased) {
      Log.w(TAG, "Cannot enable visualizer - already released")
      return
    }

    // All visualizer operations must happen on main thread
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { setEnabled(enabled) }
      return
    }

    if (enabled && !_state.value.hasPermission) {
      Log.w(TAG, "Cannot enable visualizer - missing RECORD_AUDIO permission")
      disableInternal(clearData = true)
      return
    }

    if (enabled) {
      pendingEnable = true
      mainHandler.removeCallbacks(enableRunnable)
      // Delay visualizer creation to ensure audio session is stable
      mainHandler.postDelayed(enableRunnable, ENABLE_DELAY_MS)
    } else {
      pendingEnable = false
      mainHandler.removeCallbacks(enableRunnable)
      disableInternal(clearData = true)
    }
  }

  private fun enableInternal() {
    if (isReleased) return
    // Create visualizer lazily only when enabled
    if (visualizer == null && !createVisualizer()) {
      Log.w(TAG, "Cannot enable visualizer - creation failed")
      setState(_state.value.copy(enabled = false, available = false))
      return
    }

    val viz = visualizer
    if (viz == null) {
      Log.w(TAG, "Cannot enable visualizer - visualizer is null after creation")
      setState(_state.value.copy(enabled = false, available = false))
      return
    }

    try {
      viz.enabled = true
      setState(_state.value.copy(enabled = true, available = true))
      startCapture()
      Log.d(TAG, "Visualizer enabled successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to enable visualizer", e)
      setState(_state.value.copy(enabled = false))
    }
  }

  private fun disableInternal(clearData: Boolean) {
    stopCapture()
    try {
      visualizer?.enabled = false
    } catch (e: Exception) {
      Log.e(TAG, "Error disabling visualizer", e)
    }

    val nextState =
      if (clearData) {
        _state.value.copy(
          enabled = false,
          frequencyData = ByteArray(0),
          waveform = ByteArray(0),
        )
      } else {
        _state.value.copy(enabled = false)
      }
    setState(nextState)
    Log.d(TAG, "Visualizer disabled")
  }

  private fun createVisualizer(): Boolean {
    if (isReleased) {
      Log.w(TAG, "Cannot create visualizer - already released")
      return false
    }

    // Check if audio session is ready (0 means not ready yet)
    if (audioSessionId == 0) {
      Log.w(TAG, "Cannot create visualizer - audio session ID is 0 (not ready yet)")
      return false
    }

    return try {
      Log.d(TAG, "Creating visualizer with audio session $audioSessionId")

      visualizer =
        Visualizer(audioSessionId).apply {
          // Use maximum capture size for better frequency resolution
          val range = Visualizer.getCaptureSizeRange()
          captureSize = range[1] // Use maximum size for better resolution
          enabled = false
        }

      val captureSize = visualizer?.captureSize ?: 0
      if (captureSize <= 0) {
        Log.e(TAG, "Failed to create visualizer - invalid capture size: $captureSize")
        visualizer?.release()
        visualizer = null
        return false
      }

      // Initialize buffers based on actual capture size
      initializeBuffers(captureSize)

      setState(_state.value.copy(available = true))
      Log.d(TAG, "Visualizer created successfully with capture size $captureSize")
      Log.d(TAG, "Buffers initialized - waveBuffer: ${waveBuffer.size}, freqBuffer: ${freqBuffer.size}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create visualizer for session $audioSessionId", e)
      setState(_state.value.copy(available = false))
      false
    }
  }

  private fun initializeBuffers(captureSize: Int) {
    // waveBuffer needs captureSize bytes
    waveBuffer = ByteArray(captureSize)

    // getFft() writes captureSize bytes (not captureSize/2+1).
    // Using a smaller buffer causes a heap overflow in native code, which
    // manifests as an MTE tag mismatch SIGSEGV on Android 14+ with MTE enabled.
    freqBuffer = ByteArray(captureSize)

    signalProcessor.reset()

    Log.d(TAG, "Initialized buffers for capture size $captureSize:")
    Log.d(TAG, "  waveBuffer size: ${waveBuffer.size}")
    Log.d(TAG, "  freqBuffer size: ${freqBuffer.size}")
    Log.d(TAG, "  outputWaveform size: ${processedWaveform.size}")
    Log.d(TAG, "  outputFrequency size: ${processedFrequency.size}")
  }

  private fun startCapture() {
    if (isReleased) {
      Log.w(TAG, "Cannot start capture - already released")
      return
    }

    stopCapture()

    val viz = visualizer
    if (viz == null) {
      Log.w(TAG, "Cannot start capture - visualizer is null")
      return
    }

    // Check if visualizer is actually enabled before starting
    if (!viz.enabled) {
      Log.w(TAG, "Cannot start capture - visualizer not enabled")
      return
    }

    // Verify buffers are initialized
    if (waveBuffer.isEmpty() || freqBuffer.isEmpty()) {
      Log.w(TAG, "Cannot start capture - buffers not initialized")
      return
    }

    Log.d(TAG, "Starting capture with buffer sizes - wave: ${waveBuffer.size}, freq: ${freqBuffer.size}")

    val handler = ensureCaptureHandler()
    captureRunnable =
      object : Runnable {
        override fun run() {
          if (isReleased) {
            return
          }

          val currentViz = visualizer
          if (currentViz == null) {
            return
          }

          if (!currentViz.enabled) {
            return
          }

          try {
            // Get FFT data (frequency spectrum)
            val fftResult = currentViz.getFft(freqBuffer)
            if (fftResult != Visualizer.SUCCESS) {
              logThrottled("FFT capture failed ($fftResult)", CaptureErrorType.FFT)
              handler.postDelayed(this, CAPTURE_INTERVAL_MS)
              return
            }

            // Get waveform data
            val waveResult = currentViz.getWaveForm(waveBuffer)
            if (waveResult != Visualizer.SUCCESS) {
              logThrottled("Waveform capture failed ($waveResult)", CaptureErrorType.WAVE)
              handler.postDelayed(this, CAPTURE_INTERVAL_MS)
              return
            }

            signalProcessor.process(freqBuffer, waveBuffer, processedFrequency, processedWaveform)
            frameCounter += 1L

            setState(
              _state.value.copy(
                frequencyData = processedFrequency.copyOf(),
                waveform = processedWaveform.copyOf(),
                frameId = frameCounter,
              ),
            )
          } catch (e: IllegalStateException) {
            logThrottled("Visualizer not initialized: ${e.message}", CaptureErrorType.CAPTURE)
            return
          } catch (e: Exception) {
            logThrottled("Capture error: ${e.message}", CaptureErrorType.CAPTURE)
            return
          }

          // Schedule next frame (60fps)
          handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
      }

    val runnable = captureRunnable ?: return
    handler.post(runnable)
    Log.d(TAG, "Capture started")
  }

  private fun ensureCaptureHandler(): Handler {
    val existing = captureHandler
    if (existing != null) return existing

    val thread = HandlerThread("VisualizerCapture-$audioSessionId").apply { start() }
    captureThread = thread
    return Handler(thread.looper).also { captureHandler = it }
  }

  private fun logThrottled(
    message: String,
    type: CaptureErrorType,
  ) {
    val now = SystemClock.elapsedRealtime()
    when (type) {
      CaptureErrorType.FFT -> {
        if (now - lastFftErrorLogMs >= LOG_THROTTLE_MS) {
          lastFftErrorLogMs = now
          Log.w(TAG, message)
        }
      }
      CaptureErrorType.WAVE -> {
        if (now - lastWaveErrorLogMs >= LOG_THROTTLE_MS) {
          lastWaveErrorLogMs = now
          Log.w(TAG, message)
        }
      }
      CaptureErrorType.CAPTURE -> {
        if (now - lastCaptureErrorLogMs >= LOG_THROTTLE_MS) {
          lastCaptureErrorLogMs = now
          Log.e(TAG, message)
        }
      }
    }
  }

  private fun stopCapture() {
    captureRunnable?.let {
      captureHandler?.removeCallbacks(it)
    }
    captureRunnable = null
    signalProcessor.reset()
    Log.d(TAG, "Capture stopped")
  }

  fun updatePermissionStatus(hasPermission: Boolean) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { updatePermissionStatus(hasPermission) }
      return
    }

    val previousState = _state.value
    setState(previousState.copy(hasPermission = hasPermission))
    if (!hasPermission) {
      pendingEnable = false
      mainHandler.removeCallbacks(enableRunnable)
      disableInternal(clearData = true)
    }
  }

  private fun shutdownCaptureThread() {
    captureThread?.quitSafely()
    captureThread = null
    captureHandler = null
  }

  fun release() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { release() }
      return
    }

    Log.d(TAG, "Releasing VisualizerManager for session $audioSessionId")
    isReleased = true
    pendingEnable = false
    mainHandler.removeCallbacks(enableRunnable)
    stopCapture()
    shutdownCaptureThread()

    try {
      visualizer?.enabled = false
      visualizer?.release()
      visualizer = null
      Log.d(TAG, "Visualizer released successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error releasing visualizer", e)
    }
  }

  companion object {
    private const val TAG = "VisualizerManager"
    private const val ENABLE_DELAY_MS = 500L
    private const val CAPTURE_INTERVAL_MS = 16L
    private const val LOG_THROTTLE_MS = 5_000L
  }
}

private enum class CaptureErrorType {
  FFT,
  WAVE,
  CAPTURE,
}
