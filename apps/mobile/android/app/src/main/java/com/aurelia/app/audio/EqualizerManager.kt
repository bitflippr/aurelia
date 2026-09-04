package com.aurelia.app.audio

import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EQBand(
  val frequency: Int,
  val gain: Float = 0f,
)

data class EQPreset(
  val name: String,
  val gains: List<Float>,
)

object EQPresets {
  val all: List<EQPreset> =
    listOf(
      EQPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
      EQPreset("Rock", listOf(3f, 2f, -1f, 1f, 2f)),
      EQPreset("Pop", listOf(-2f, 1f, 3f, 2f, 1f)),
      EQPreset("Jazz", listOf(2f, -1f, 2f, 3f, 0f)),
      EQPreset("Classical", listOf(1f, -1f, 1f, 2f, 3f)),
      EQPreset("Hip Hop", listOf(4f, 3f, -2f, 0f, -1f)),
      EQPreset("Electronic", listOf(2f, -3f, 4f, 2f, 3f)),
      EQPreset("Vocal", listOf(-2f, -1f, 4f, 1f, 2f)),
    )

  fun byName(name: String): EQPreset? = all.find { it.name == name }
}

data class EqualizerState(
  val enabled: Boolean = false,
  val bands: List<EQBand> =
    listOf(
      EQBand(60),
      EQBand(250),
      EQBand(1000),
      EQBand(4000),
      EQBand(16000),
    ),
  val currentPreset: String? = null,
  val available: Boolean = false,
)

class EqualizerManager(
  audioSessionId: Int,
) {
  private var equalizer: Equalizer? = null
  private val _state = MutableStateFlow(EqualizerState())
  val state: StateFlow<EqualizerState> = _state.asStateFlow()

  private val targetFrequencies = listOf(60, 250, 1000, 4000, 16000)
  private val bandMapping = mutableMapOf<Int, Int>()

  init {
    try {
      equalizer =
        Equalizer(0, audioSessionId).apply {
          enabled = false
        }
      mapBands()
      _state.value =
        _state.value.copy(
          available = true,
          bands =
            _state.value.bands.mapIndexed { index, band ->
              band.copy(gain = 0f)
            },
        )
      Log.d(TAG, "Equalizer initialized with ${equalizer?.numberOfBands} bands")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize equalizer", e)
      _state.value = _state.value.copy(available = false)
    }
  }

  private fun mapBands() {
    val eq = equalizer ?: return
    val numBands = eq.numberOfBands.toInt()
    bandMapping.clear()

    targetFrequencies.forEachIndexed { targetIndex, targetFreq ->
      var closestBandIndex = 0
      var closestDistance = Int.MAX_VALUE

      for (i in 0 until numBands) {
        val bandFreq = eq.getCenterFreq(i.toShort()).toInt() / 1000
        val distance = kotlin.math.abs(bandFreq - targetFreq)
        if (distance < closestDistance) {
          closestDistance = distance
          closestBandIndex = i
        }
      }
      bandMapping[targetIndex] = closestBandIndex
    }
  }

  fun setEnabled(enabled: Boolean) {
    try {
      equalizer?.enabled = enabled
      _state.value = _state.value.copy(enabled = enabled)
      Log.d(TAG, "Equalizer enabled: $enabled")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set enabled", e)
    }
  }

  fun setBandGain(
    bandIndex: Int,
    gainDb: Float,
  ) {
    if (bandIndex !in 0..4) return

    try {
      val eq = equalizer ?: return
      val actualBand = bandMapping[bandIndex] ?: return

      val minGain = eq.bandLevelRange[0] / 100
      val maxGain = eq.bandLevelRange[1] / 100
      val clampedGain = gainDb.coerceIn(minGain.toFloat(), maxGain.toFloat())

      eq.setBandLevel(actualBand.toShort(), (clampedGain * 100).toInt().toShort())

      _state.value =
        _state.value.copy(
          bands =
            _state.value.bands.mapIndexed { index, band ->
              if (index == bandIndex) band.copy(gain = gainDb) else band
            },
          currentPreset = null,
        )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set band gain", e)
    }
  }

  fun applyPreset(preset: EQPreset) {
    if (preset.gains.size != 5) return

    preset.gains.forEachIndexed { index, gain ->
      setBandGain(index, gain)
    }
    _state.value = _state.value.copy(currentPreset = preset.name)
    Log.d(TAG, "Applied preset: ${preset.name}")
  }

  fun reset() {
    repeat(5) { index ->
      setBandGain(index, 0f)
    }
    _state.value = _state.value.copy(currentPreset = "Flat")
  }

  fun release() {
    try {
      equalizer?.release()
      equalizer = null
      Log.d(TAG, "Equalizer released")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to release equalizer", e)
    }
  }

  companion object {
    private const val TAG = "EqualizerManager"
  }
}
