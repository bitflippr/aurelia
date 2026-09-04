package com.aurelia.app.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Signal processing pipeline for Android visualizer frames.
 *
 * Converts Android Visualizer FFT + waveform bytes into compact, smoothed
 * 0..255 buffers suitable for the Compose visualizer.
 */
class VisualizerSignalProcessor(
  private val attack: Float = DEFAULT_ATTACK_SMOOTHING,
  private val decay: Float = DEFAULT_DECAY_SMOOTHING,
) {
  private val smoothedFrequency = FloatArray(OUTPUT_FREQUENCY_BIN_COUNT)
  private val smoothedWaveform = FloatArray(OUTPUT_WAVEFORM_SAMPLE_COUNT) { WAVEFORM_CENTER.toFloat() }
  private var fftMagnitudeScratch = FloatArray(0)

  fun process(
    rawFft: ByteArray,
    rawWaveform: ByteArray,
    outFrequency: ByteArray,
    outWaveform: ByteArray,
  ) {
    require(outFrequency.size == OUTPUT_FREQUENCY_BIN_COUNT) {
      "Expected $OUTPUT_FREQUENCY_BIN_COUNT frequency bins, got ${outFrequency.size}"
    }
    require(outWaveform.size == OUTPUT_WAVEFORM_SAMPLE_COUNT) {
      "Expected $OUTPUT_WAVEFORM_SAMPLE_COUNT waveform samples, got ${outWaveform.size}"
    }

    ensureScratchCapacity(rawFft.size / 2)
    val sourceMagnitudeCount = decodeFftToMagnitudes(rawFft, fftMagnitudeScratch)

    for (i in outFrequency.indices) {
      val rawValue =
        if (sourceMagnitudeCount == 0) {
          0f
        } else {
          val sourceIndex =
            ((i * sourceMagnitudeCount) / OUTPUT_FREQUENCY_BIN_COUNT)
              .coerceIn(0, sourceMagnitudeCount - 1)
          magnitudeToByteScale(fftMagnitudeScratch[sourceIndex])
        }
      outFrequency[i] =
        smoothAndQuantize(
          smoothedFrequency,
          i,
          shapeFrequencyForDisplay(rawValue),
        )
    }

    for (i in outWaveform.indices) {
      val rawValue =
        if (rawWaveform.isEmpty()) {
          WAVEFORM_CENTER.toFloat()
        } else {
          val sourceIndex =
            ((i * rawWaveform.size) / OUTPUT_WAVEFORM_SAMPLE_COUNT)
              .coerceIn(0, rawWaveform.size - 1)
          waveformByteToUnsigned(rawWaveform[sourceIndex]).toFloat()
        }
      outWaveform[i] = smoothAndQuantize(smoothedWaveform, i, rawValue)
    }
  }

  fun reset() {
    smoothedFrequency.fill(0f)
    smoothedWaveform.fill(WAVEFORM_CENTER.toFloat())
  }

  private fun ensureScratchCapacity(size: Int) {
    if (size <= 0) return
    if (fftMagnitudeScratch.size < size) {
      fftMagnitudeScratch = FloatArray(size)
    }
  }

  private fun smoothAndQuantize(
    smoothedBuffer: FloatArray,
    index: Int,
    rawValue: Float,
  ): Byte {
    val current = smoothedBuffer[index]
    val rate = if (rawValue > current) attack else decay
    val updated = current + (rawValue - current) * rate
    smoothedBuffer[index] = updated
    val clamped = updated.roundToInt().coerceIn(0, 255)
    return clamped.toByte()
  }

  companion object {
    const val OUTPUT_FREQUENCY_BIN_COUNT: Int = 128
    const val OUTPUT_WAVEFORM_SAMPLE_COUNT: Int = 256
    const val WAVEFORM_CENTER: Int = 128

    const val DEFAULT_ATTACK_SMOOTHING: Float = 0.8f
    const val DEFAULT_DECAY_SMOOTHING: Float = 0.15f

    private const val MIN_DB: Float = -100f
    private const val MAX_DB: Float = 0f
    private const val DB_RANGE: Float = MAX_DB - MIN_DB
    private const val MIN_MAGNITUDE_RATIO: Float = 1e-7f
    private const val MAX_FFT_MAGNITUDE: Float = 180f

    /**
     * Decodes interleaved complex FFT bytes into magnitude bins.
     *
     * Returns number of valid magnitudes written to [outMagnitudes].
     */
    fun decodeFftToMagnitudes(
      rawFft: ByteArray,
      outMagnitudes: FloatArray,
    ): Int {
      if (rawFft.size < 2 || outMagnitudes.isEmpty()) return 0

      val fftSize = rawFft.size
      val binCount = minOf(fftSize / 2, outMagnitudes.size)
      if (binCount <= 0) return 0

      // DC and Nyquist are stored as real-only values.
      outMagnitudes[0] = abs(rawFft[0].toFloat())
      if (binCount > 1) {
        outMagnitudes[binCount - 1] = abs(rawFft[1].toFloat())
      }

      for (k in 1 until (binCount - 1)) {
        val realIndex = k * 2
        val imagIndex = realIndex + 1
        if (imagIndex >= rawFft.size) break
        val real = rawFft[realIndex].toFloat()
        val imag = rawFft[imagIndex].toFloat()
        outMagnitudes[k] = sqrt(real * real + imag * imag)
      }

      return binCount
    }

    fun magnitudeToByteScale(magnitude: Float): Float {
      if (magnitude <= 0f) return 0f
      val ratio = (magnitude / MAX_FFT_MAGNITUDE).coerceAtLeast(MIN_MAGNITUDE_RATIO)
      val db = 20f * log10(ratio)
      val normalized = ((db - MIN_DB) / DB_RANGE).coerceIn(0f, 1f)
      return normalized * 255f
    }

    /**
     * Android's Visualizer FFT has a noticeably elevated floor.
     * Gate low-level energy, then apply a mild shaping curve so idle bars sit near bottom
     * while preserving punch on transients.
     */
    fun shapeFrequencyForDisplay(value: Float): Float {
      if (value <= FREQUENCY_NOISE_FLOOR) return 0f
      val normalized =
        ((value - FREQUENCY_NOISE_FLOOR) / (255f - FREQUENCY_NOISE_FLOOR)).coerceIn(0f, 1f)
      return (normalized.pow(FREQUENCY_SHAPE_GAMMA) * 255f).coerceIn(0f, 255f)
    }

    fun waveformByteToUnsigned(value: Byte): Int = (value.toInt() + WAVEFORM_CENTER).coerceIn(0, 255)

    private const val FREQUENCY_NOISE_FLOOR: Float = 96f
    private const val FREQUENCY_SHAPE_GAMMA: Float = 1.2f
  }
}
