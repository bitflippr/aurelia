package com.aurelia.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerSignalProcessorTest {
  @Test
  fun decodeFftToMagnitudes_detectsNonZeroFrequencyBin() {
    val rawFft = ByteArray(256)
    // Bin 12 real/imag pair in Android Visualizer format.
    rawFft[24] = 100.toByte()
    rawFft[25] = 60.toByte()

    val magnitudes = FloatArray(128)
    val count = VisualizerSignalProcessor.decodeFftToMagnitudes(rawFft, magnitudes)

    assertEquals(128, count)
    assertTrue("Expected non-zero magnitude at injected bin", magnitudes[12] > 0f)
  }

  @Test
  fun process_keepsWaveformCenteredForSilence() {
    val processor = VisualizerSignalProcessor()
    val rawFft = ByteArray(256)
    val rawWaveform = ByteArray(256) { 0 } // signed 0 maps to unsigned 128 center
    val outFrequency = ByteArray(VisualizerSignalProcessor.OUTPUT_FREQUENCY_BIN_COUNT)
    val outWaveform = ByteArray(VisualizerSignalProcessor.OUTPUT_WAVEFORM_SAMPLE_COUNT)

    processor.process(rawFft, rawWaveform, outFrequency, outWaveform)

    val centerValue = outWaveform[0].toInt() and 0xFF
    assertEquals(VisualizerSignalProcessor.WAVEFORM_CENTER, centerValue)
  }

  @Test
  fun process_usesFastAttackAndSlowDecay() {
    val processor =
      VisualizerSignalProcessor(
        attack = 0.8f,
        decay = 0.15f,
      )
    val rawWaveform = ByteArray(256) { 0 }
    val outFrequency = ByteArray(VisualizerSignalProcessor.OUTPUT_FREQUENCY_BIN_COUNT)
    val outWaveform = ByteArray(VisualizerSignalProcessor.OUTPUT_WAVEFORM_SAMPLE_COUNT)

    val highFft =
      ByteArray(256).apply {
        // Max-ish magnitude at bin 20 (real + imag).
        this[40] = 127.toByte()
        this[41] = 127.toByte()
      }
    processor.process(highFft, rawWaveform, outFrequency, outWaveform)
    val attackValue = outFrequency[20].toInt() and 0xFF

    val lowFft = ByteArray(256)
    processor.process(lowFft, rawWaveform, outFrequency, outWaveform)
    val decayValue = outFrequency[20].toInt() and 0xFF

    assertTrue("Attack should lift value quickly", attackValue in 180..220)
    assertTrue("Decay should be slower than immediate drop to zero", decayValue in 150..190)
  }

  @Test
  fun process_writesFixedOutputSizes() {
    val processor = VisualizerSignalProcessor()
    val rawFft = ByteArray(1024)
    val rawWaveform = ByteArray(1024)
    val outFrequency = ByteArray(VisualizerSignalProcessor.OUTPUT_FREQUENCY_BIN_COUNT)
    val outWaveform = ByteArray(VisualizerSignalProcessor.OUTPUT_WAVEFORM_SAMPLE_COUNT)

    processor.process(rawFft, rawWaveform, outFrequency, outWaveform)

    assertEquals(128, outFrequency.size)
    assertEquals(256, outWaveform.size)
  }
}
