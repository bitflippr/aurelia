package com.aurelia.app.ui

import android.app.ActivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurelia.app.data.model.Lyrics
import com.aurelia.app.data.model.SyncedLine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

internal const val LYRIC_UNHIGHLIGHTED_ALPHA = 0.45f

@Composable
internal fun LyricsView(
  lyrics: Lyrics?,
  positionState: State<Long>,
  onLineClick: (Int) -> Unit,
  @Suppress("UNUSED_PARAMETER") primaryColor: Color,
  modifier: Modifier = Modifier,
) {
  val lines = lyrics?.synced
  if (lines.isNullOrEmpty()) {
    val plain = lyrics?.plain
    if (plain.isNullOrEmpty()) {
      Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No lyrics available", color = Color.White.copy(alpha = 0.55f))
      }
    } else {
      LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 200.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        itemsIndexed(plain) { _, text ->
          Text(
            text,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
    return
  }

  val lyricData = requireNotNull(lyrics)
  val motion =
    remember(lines) {
      LyricListMotion(lines, Snapshot.withoutReadObservation { positionState.value })
    }
  val context = LocalContext.current
  val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
  var animationsEnabled by remember { mutableStateOf(true) }
  val sectionLabels =
    remember(lyricData.sections, lines) {
      lyricData.sections
        .orEmpty()
        .mapNotNull { section ->
          val first = section.lines.firstOrNull() ?: return@mapNotNull null
          val index = lines.indexOfFirst { it.time == first.time }
          if (index >= 0 && section.name.isNotBlank()) index to section.name else null
        }.toMap()
    }
  val scrollState = rememberScrollableState { delta -> motion.scroll(delta) }

  LaunchedEffect(motion, positionState) {
    val durationScale = currentCoroutineContext()[MotionDurationScale]
    var lastFrame = 0L
    while (currentCoroutineContext().isActive) {
      withFrameNanos { frame ->
        val scale = durationScale?.scaleFactor ?: 1f
        animationsEnabled = scale > 0f
        val elapsed = if (lastFrame == 0L) 0f else ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrame = frame
        motion.advance(positionState.value, elapsed, scale, scrollState.isScrollInProgress)
      }
    }
  }

  Layout(
    modifier =
      modifier
        .fillMaxSize()
        .clipToBounds()
        .scrollable(scrollState, Orientation.Vertical)
        .semantics {
          verticalScrollAxisRange =
            ScrollAxisRange(
              value = { motion.scrollPosition },
              maxValue = { motion.scrollExtent },
            )
          scrollBy { _, y -> motion.scroll(-y) != 0f }
        },
    content = {
      lines.forEachIndexed { index, line ->
        key(index, line.time) {
          val secondary = lyricData.isSecondaryVocalist(line.agentId)
          val background = lyricData.isBackgroundVocal(line.agentId)
          Column(
            horizontalAlignment = if (secondary) Alignment.End else Alignment.Start,
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                ) {
                  motion.releaseScroll()
                  onLineClick(line.time)
                }.padding(horizontal = 8.dp, vertical = 12.dp),
          ) {
            sectionLabels[index]?.let { label ->
              Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 8.dp),
              )
            }
            ExpressiveLyricLine(
              line = line,
              positionState = positionState,
              activeUntilMs = motion.ends[index] + 250L,
              lineOpacity = {
                motion.revision
                motion.opacities[index]
              },
              isBackground = background,
              isSecondary = secondary,
              expressive = animationsEnabled && !lowRam,
            )
            line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
              Text(
                translation,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = if (secondary) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
              )
            }
          }
        }
      }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val rowConstraints = Constraints(maxWidth = width)
    val rows = measurables.map { it.measure(rowConstraints) }
    motion.measure(rows.map { it.height }, height)
    layout(width, height) {
      rows.forEachIndexed { index, row ->
        row.placeWithLayer(0, 0) {
          // Observe frame changes in the layer phase, without remeasuring text.
          motion.revision
          val y = motion.positions[index].value + motion.userScroll.value
          translationY = y
          val visible = y + row.height >= -height * 0.25f && y <= height * 1.25f
          alpha = if (visible) motion.opacities[index] else 0f
          scaleX = motion.scales[index].value
          scaleY = scaleX
          transformOrigin = TransformOrigin(if (lyricData.isSecondaryVocalist(lines[index].agentId)) 1f else 0f, 0.5f)
        }
      }
    }
  }
}

private class LyricListMotion(
  private val lines: List<SyncedLine>,
  private var currentTime: Long,
) {
  val ends =
    LongArray(lines.size) { index ->
      val line = lines[index]
      max(
        line.time.toLong(),
        line.endTime?.toLong()
          ?: line.words
            ?.lastOrNull()
            ?.endTime
            ?.toLong()
          ?: lines.firstOrNull { it.time > line.time }?.time?.toLong()
          ?: (line.time.toLong() + 5000L),
      )
    }
  val positions = Array(lines.size) { LyricMotionSpring(0f, 200f, 28f) }
  val scales = Array(lines.size) { LyricMotionSpring(0.94f, 120f, 22f, 0.0005f, 0.005f) }
  val opacities = FloatArray(lines.size) { LYRIC_UNHIGHLIGHTED_ALPHA }
  val userScroll = LyricMotionSpring(0f, 240f, 28f)
  var revision by mutableIntStateOf(0)
    private set
  private var heights = emptyList<Int>()
  private var viewport = 0
  private var pin = -1
  private var manualSeconds = 0f
  private var followNeedsUpdate = false
  private var minimumOffset = 0f
  private var maximumOffset = 0f
  val scrollPosition: Float get() {
    revision
    return maximumOffset - userScroll.value
  }
  val scrollExtent: Float get() = maximumOffset - minimumOffset

  fun isActive(
    index: Int,
    time: Long,
  ): Boolean = time >= lines[index].time && time <= ends[index] + 250L

  fun measure(
    newHeights: List<Int>,
    height: Int,
  ) {
    if (heights == newHeights && viewport == height) return
    heights = newHeights
    viewport = height
    updateTargets(currentTime, snap = true)
  }

  private fun updateTargets(
    time: Long,
    snap: Boolean = false,
  ) {
    if (heights.isEmpty()) return
    val newPin = lines.indexOfLast { it.time <= time }.coerceAtLeast(0)
    if (!snap && newPin == pin && !followNeedsUpdate) return
    followNeedsUpdate = false
    pin = newPin
    val center = heights.take(pin).sum() + heights[pin] / 2f
    var top = viewport * 0.38f - center
    maximumOffset = max(0f, -top)
    var delay = 0f
    var increment = 0.05f
    positions.forEachIndexed { index, spring ->
      if (snap) {
        spring.settle(top)
        scales[index].settle(if (isActive(index, time)) 1f else 0.94f)
        opacities[index] = opacity(index, time)
      } else {
        spring.value = spring.value.coerceIn(top - viewport * 1.5f, top + viewport * 1.5f)
        spring.target = top
        spring.delay = if (index > pin) delay else 0f
      }
      if (index >= pin) {
        delay += increment
        increment /= 1.05f
      }
      top += heights[index]
    }
    minimumOffset = min(0f, viewport - top)
    val offset = userScroll.value.coerceIn(minimumOffset, maximumOffset)
    if (snap || offset != userScroll.value) userScroll.settle(offset)
  }

  private fun opacity(
    index: Int,
    time: Long,
  ): Float =
    when {
      isActive(index, time) -> 1f
      time > ends[index] + 250L -> if (manualSeconds > 0f) 0.5f else 0.3f
      else -> LYRIC_UNHIGHLIGHTED_ALPHA
    }

  fun scroll(delta: Float): Float {
    if (heights.isEmpty()) return 0f
    if (manualSeconds == 0f) {
      positions.forEach { it.settle(it.value) }
      maximumOffset = max(0f, -positions.first().value)
      minimumOffset = min(0f, viewport - positions.last().value - heights.last())
    }
    val old = userScroll.value.coerceIn(minimumOffset, maximumOffset)
    val next = (old + delta).coerceIn(minimumOffset, maximumOffset)
    userScroll.settle(next)
    manualSeconds = 2f
    followNeedsUpdate = true
    revision++
    return next - old
  }

  fun releaseScroll() {
    manualSeconds = 0f
    userScroll.target = 0f
  }

  fun advance(
    time: Long,
    elapsed: Float,
    durationScale: Float,
    scrolling: Boolean,
  ) {
    currentTime = time
    if (heights.isEmpty()) return
    if (!scrolling) manualSeconds = (manualSeconds - elapsed).coerceAtLeast(0f)
    if (manualSeconds == 0f) {
      updateTargets(time)
      userScroll.target = 0f
    }
    val dt = if (durationScale > 0f) (elapsed / durationScale).coerceAtMost(0.1f) else 0f
    var changed = false
    positions.indices.forEach { index ->
      val active = isActive(index, time)
      scales[index].target = if (active) 1f else 0.94f
      val targetOpacity = opacity(index, time)
      val oldOpacity = opacities[index]
      if (durationScale == 0f) {
        changed =
          changed ||
          positions[index].value != positions[index].target ||
          scales[index].value != scales[index].target
        positions[index].settle(positions[index].target)
        scales[index].settle(scales[index].target)
        opacities[index] = targetOpacity
      } else {
        changed = positions[index].step(dt) || changed
        changed = scales[index].step(dt, if (active) 1f else 0.6f) || changed
        val fadeTime = if (targetOpacity > oldOpacity) 0.12f else 0.4f
        opacities[index] =
          if (abs(targetOpacity - oldOpacity) < 0.001f) {
            targetOpacity
          } else {
            oldOpacity + (targetOpacity - oldOpacity) * (1f - exp(-dt / fadeTime))
          }
      }
      changed = changed || oldOpacity != opacities[index]
    }
    if (durationScale == 0f) {
      changed = changed || userScroll.value != userScroll.target
      userScroll.settle(userScroll.target)
    } else {
      changed = userScroll.step(dt) || changed
    }
    if (changed) revision++
  }
}

private class LyricMotionSpring(
  var value: Float,
  private val stiffness: Float,
  private val damping: Float,
  private val positionTolerance: Float = 0.05f,
  private val velocityTolerance: Float = 0.5f,
) {
  var target = value
  var delay = 0f
  private var velocity = 0f

  fun settle(position: Float) {
    value = position
    target = position
    velocity = 0f
    delay = 0f
  }

  fun step(
    elapsed: Float,
    speed: Float = 1f,
  ): Boolean {
    if (delay > 0f) {
      delay = (delay - elapsed).coerceAtLeast(0f)
      if (delay > 0f) return false
    }
    if (abs(target - value) < positionTolerance && abs(velocity) < velocityTolerance) {
      val changed = value != target
      settle(target)
      return changed
    }
    var remaining = elapsed
    while (remaining > 0f) {
      val dt = min(remaining, 1f / 240f)
      velocity += ((target - value) * stiffness * speed * speed - velocity * damping * speed) * dt
      value += velocity * dt
      remaining -= dt
    }
    return elapsed > 0f
  }
}
