package com.aurelia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurelia.app.audio.EQBand
import com.aurelia.app.audio.EQPreset
import com.aurelia.app.audio.EQPresets
import com.aurelia.app.audio.EqualizerState
import com.aurelia.app.audio.VisualizerStyle
import kotlin.math.roundToInt

@Composable
fun EqualizerSection(
  state: EqualizerState,
  onEnabledChange: (Boolean) -> Unit,
  onBandGainChange: (Int, Float) -> Unit,
  onPresetSelected: (EQPreset) -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "Equalizer",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = colors.primary,
      modifier = Modifier.padding(horizontal = 8.dp),
    )

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = colors.surfaceVariant.copy(alpha = 0.5f),
      tonalElevation = 1.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        if (!state.available) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "Equalizer not available on this device",
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
            )
          }
        } else {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Equalizer,
              contentDescription = null,
              tint = colors.onSurfaceVariant,
              modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Enable equalizer",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
              )
              Text(
                text = "Adjust audio frequencies",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
              )
            }
            Switch(
              checked = state.enabled,
              onCheckedChange = onEnabledChange,
              colors =
                SwitchDefaults.colors(
                  checkedThumbColor = colors.primary,
                  checkedTrackColor = colors.primaryContainer,
                ),
            )
          }

          if (state.enabled) {
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
              PresetSelector(
                presets = EQPresets.all,
                currentPreset = state.currentPreset,
                onPresetSelected = onPresetSelected,
              )
            }

            EQBandSliders(
              bands = state.bands,
              onBandGainChange = onBandGainChange,
              enabled = state.enabled,
            )

            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.End,
            ) {
              IconButton(onClick = onReset) {
                Icon(
                  imageVector = Icons.Filled.Refresh,
                  contentDescription = "Reset to flat",
                  tint = colors.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSelector(
  presets: List<EQPreset>,
  currentPreset: String?,
  onPresetSelected: (EQPreset) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  var expanded by remember { mutableStateOf(false) }
  val selectedPreset = presets.find { it.name == currentPreset }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedPreset?.name ?: "Custom",
      onValueChange = {},
      readOnly = true,
      label = { Text("Preset") },
      trailingIcon = {
        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
      },
      colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
      modifier =
        Modifier
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth(),
    )

    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      presets.forEach { preset ->
        DropdownMenuItem(
          text = { Text(preset.name) },
          onClick = {
            onPresetSelected(preset)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}

@Composable
private fun EQBandSliders(
  bands: List<EQBand>,
  onBandGainChange: (Int, Float) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    bands.forEachIndexed { index, band ->
      VerticalEQSlider(
        value = band.gain,
        onValueChange = { gain -> onBandGainChange(index, gain) },
        valueRange = -20f..20f,
        frequency = band.frequency,
        enabled = enabled,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun VerticalEQSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  frequency: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val normalizedValue = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
  val sliderHeight = 140.dp
  val sliderWidth = 32.dp
  val thumbDiameter = 14.dp
  val density = LocalDensity.current
  var sliderHeightPx by remember { mutableStateOf(0f) }

  val gradientBrush =
    Brush.verticalGradient(
      colors =
        listOf(
          colors.primary,
          colors.primary.copy(alpha = 0.5f),
        ),
    )
  val rangeSpan = valueRange.endInclusive - valueRange.start
  val valueFromY: (Float) -> Float =
    remember(valueRange, sliderHeightPx, value) {
      { y ->
        if (sliderHeightPx <= 0f) {
          value
        } else {
          val normalized = 1f - (y.coerceIn(0f, sliderHeightPx) / sliderHeightPx)
          (valueRange.start + normalized * (valueRange.endInclusive - valueRange.start))
            .coerceIn(valueRange.start, valueRange.endInclusive)
        }
      }
    }
  val thumbOffsetYPx =
    remember(normalizedValue, sliderHeightPx, density) {
      if (sliderHeightPx <= 0f) {
        0
      } else {
        val thumbDiameterPx = with(density) { thumbDiameter.toPx() }
        val rawTop = (1f - normalizedValue) * sliderHeightPx - (thumbDiameterPx / 2f)
        rawTop.coerceIn(0f, sliderHeightPx - thumbDiameterPx).roundToInt()
      }
    }
  val dragState =
    rememberDraggableState { deltaY ->
      if (sliderHeightPx <= 0f) return@rememberDraggableState
      val valueDelta = -(deltaY / sliderHeightPx) * rangeSpan
      onValueChange((value + valueDelta).coerceIn(valueRange.start, valueRange.endInclusive))
    }

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "+${valueRange.endInclusive.toInt()}",
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant.copy(alpha = 0.6f),
      fontSize = 10.sp,
    )

    Spacer(modifier = Modifier.height(4.dp))

    Box(
      modifier =
        Modifier
          .height(sliderHeight)
          .width(sliderWidth)
          .onSizeChanged { sliderHeightPx = it.height.toFloat() }
          .pointerInput(enabled, valueRange, sliderHeightPx) {
            if (!enabled) return@pointerInput
            detectTapGestures { offset ->
              onValueChange(valueFromY(offset.y))
            }
          }.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            enabled = enabled,
          ),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .background(
              colors.surfaceVariant.copy(alpha = 0.3f),
              RoundedCornerShape(4.dp),
            ),
      )

      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(sliderHeight * normalizedValue)
            .background(gradientBrush, RoundedCornerShape(4.dp)),
      )

      Box(
        modifier =
          Modifier
            .size(thumbDiameter)
            .align(Alignment.TopCenter)
            .offset { IntOffset(0, thumbOffsetYPx) }
            .background(
              color = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
              shape = RoundedCornerShape(999.dp),
            ),
      )
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = "${valueRange.start.toInt()}",
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant.copy(alpha = 0.6f),
      fontSize = 10.sp,
    )

    Spacer(modifier = Modifier.height(8.dp))

    val freqText =
      when {
        frequency >= 1000 -> "${frequency / 1000}k"
        else -> frequency.toString()
      }
    Text(
      text = freqText,
      style = MaterialTheme.typography.labelSmall,
      color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
    )
    Text(
      text = "Hz",
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant.copy(alpha = 0.6f),
      fontSize = 9.sp,
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = String.format("%+.1f", value),
      style = MaterialTheme.typography.labelSmall,
      color = colors.primary,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
fun VisualizerSection(
  enabled: Boolean,
  style: String,
  onEnabledChange: (Boolean) -> Unit,
  onStyleChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "Visualizer",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = colors.primary,
      modifier = Modifier.padding(horizontal = 8.dp),
    )

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = colors.surfaceVariant.copy(alpha = 0.5f),
      tonalElevation = 1.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Equalizer,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Enable visualizer",
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Medium,
              color = colors.onSurface,
            )
            Text(
              text = "Show audio visualization during playback",
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
            )
          }
          Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = colors.primary,
                checkedTrackColor = colors.primaryContainer,
              ),
          )
        }

        if (enabled) {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            Text(
              text = "Style",
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              VisualizerStyle.values().forEach { styleOption ->
                Button(
                  onClick = { onStyleChange(styleOption.name) },
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor =
                        if (style == styleOption.name) {
                          colors.primary
                        } else {
                          colors.surfaceVariant
                        },
                      contentColor =
                        if (style == styleOption.name) {
                          colors.onPrimary
                        } else {
                          colors.onSurfaceVariant
                        },
                    ),
                  modifier = Modifier.weight(1f),
                ) {
                  Text(
                    text = styleOption.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
