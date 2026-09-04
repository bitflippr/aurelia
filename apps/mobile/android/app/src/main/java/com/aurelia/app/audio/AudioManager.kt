package com.aurelia.app.audio

import android.content.Context
import android.util.Log
import com.aurelia.app.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioManager {
  private const val TAG = "AudioManager"

  private var equalizerManager: EqualizerManager? = null
  private var visualizerManager: VisualizerManager? = null

  private val _eqState = MutableStateFlow(EqualizerState())
  val eqState: StateFlow<EqualizerState> = _eqState.asStateFlow()

  private val _visualizerState = MutableStateFlow(VisualizerState())
  val visualizerState: StateFlow<VisualizerState> = _visualizerState.asStateFlow()

  private var currentAudioSessionId: Int = -1
  private var isInitialized = false
  private var isPlaybackActive = false

  fun initialize(
    context: Context,
    audioSessionId: Int,
    sessionStore: SessionStore,
  ) {
    if (isInitialized && currentAudioSessionId == audioSessionId) {
      Log.d(TAG, "AudioManager already initialized for session $audioSessionId")
      return
    }

    Log.d(TAG, "Initializing AudioManager for session $audioSessionId")
    release()

    currentAudioSessionId = audioSessionId

    equalizerManager =
      EqualizerManager(audioSessionId).also { manager ->
        val savedEnabled = sessionStore.getEQEnabled()
        val savedBands = sessionStore.getEQBands()
        val savedPreset = sessionStore.getEQPreset()

        if (savedPreset != null) {
          EQPresets.byName(savedPreset)?.let { manager.applyPreset(it) }
        } else {
          savedBands.forEachIndexed { index, gain ->
            manager.setBandGain(index, gain)
          }
        }
        manager.setEnabled(savedEnabled)
      }

    visualizerManager =
      VisualizerManager(context, audioSessionId).also { manager ->
        manager.stateCallback = { newState -> _visualizerState.value = newState }

        // If permission was revoked while setting stayed enabled, force-disable.
        if (!manager.state.value.hasPermission && sessionStore.getVisualizerEnabled()) {
          Log.w(TAG, "Visualizer permission missing - disabling saved visualizer preference")
          sessionStore.setVisualizerEnabled(false)
        }

        if (isPlaybackActive && sessionStore.getVisualizerEnabled() && manager.state.value.hasPermission) {
          manager.setEnabled(true)
        }
      }

    isInitialized = true
  }

  fun setEQEnabled(
    enabled: Boolean,
    sessionStore: SessionStore,
  ) {
    equalizerManager?.setEnabled(enabled)
    sessionStore.setEQEnabled(enabled)
    _eqState.value = equalizerManager?.state?.value ?: _eqState.value
  }

  fun setEQBandGain(
    bandIndex: Int,
    gain: Float,
    sessionStore: SessionStore,
  ) {
    equalizerManager?.setBandGain(bandIndex, gain)
    val bands =
      equalizerManager
        ?.state
        ?.value
        ?.bands
        ?.map { it.gain } ?: listOf(0f, 0f, 0f, 0f, 0f)
    sessionStore.setEQBands(bands)
    sessionStore.setEQPreset(null)
    _eqState.value = equalizerManager?.state?.value ?: _eqState.value
  }

  fun applyEQPreset(
    presetName: String,
    sessionStore: SessionStore,
  ) {
    val preset = EQPresets.byName(presetName) ?: return
    equalizerManager?.applyPreset(preset)
    sessionStore.setEQBands(preset.gains)
    sessionStore.setEQPreset(presetName)
    _eqState.value = equalizerManager?.state?.value ?: _eqState.value
  }

  fun setVisualizerEnabled(
    enabled: Boolean,
    sessionStore: SessionStore,
  ) {
    val manager = visualizerManager
    if (enabled) {
      if (manager != null && !manager.state.value.hasPermission) {
        Log.w(TAG, "Cannot enable visualizer - RECORD_AUDIO permission missing")
        manager?.setEnabled(false)
        sessionStore.setVisualizerEnabled(false)
        _visualizerState.value = manager?.state?.value ?: _visualizerState.value
        return
      }
    }

    sessionStore.setVisualizerEnabled(enabled)
    if (enabled && isPlaybackActive) {
      manager?.setEnabled(true)
    } else {
      manager?.setEnabled(false)
    }
    // _visualizerState is kept in sync via stateCallback set during initialize()
  }

  fun onVisualizerPermissionChanged(
    hasPermission: Boolean,
    sessionStore: SessionStore,
  ) {
    visualizerManager?.updatePermissionStatus(hasPermission)
    if (!hasPermission) {
      sessionStore.setVisualizerEnabled(false)
      visualizerManager?.setEnabled(false)
    }
  }

  fun syncState() {
    _eqState.value = equalizerManager?.state?.value ?: _eqState.value
    _visualizerState.value = visualizerManager?.state?.value ?: _visualizerState.value
  }

  fun onPlaybackStarted(sessionStore: SessionStore) {
    isPlaybackActive = true
    // Auto-enable visualizer if user has it enabled in settings
    if (sessionStore.getVisualizerEnabled()) {
      val hasPermission = visualizerManager?.state?.value?.hasPermission == true
      if (hasPermission) {
        Log.d(TAG, "Auto-enabling visualizer on playback start (user has it enabled in settings)")
        visualizerManager?.setEnabled(true)
      } else {
        Log.w(TAG, "Playback started but visualizer permission missing - disabling visualizer preference")
        sessionStore.setVisualizerEnabled(false)
        visualizerManager?.setEnabled(false)
      }
    }
  }

  fun onPlaybackStopped(sessionStore: SessionStore) {
    isPlaybackActive = false
    // Stop analyzer work while paused or stopped.
    if (visualizerManager?.state?.value?.enabled == true) {
      Log.d(TAG, "Playback stopped - disabling active visualizer capture")
      visualizerManager?.setEnabled(false)
    }
  }

  fun release() {
    Log.d(TAG, "Releasing AudioManager")
    equalizerManager?.release()
    visualizerManager?.release()
    equalizerManager = null
    visualizerManager = null
    isInitialized = false
    isPlaybackActive = false
    currentAudioSessionId = -1
    _eqState.value = EqualizerState()
    _visualizerState.value = VisualizerState()
  }
}
