package com.aurelia.app.ui

import android.icu.text.BreakIterator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.aurelia.app.data.model.SyncedLine
import java.util.Locale

/** Shapes the complete line; playback state is observed only by the drawing phase. */
@Composable
internal fun ExpressiveLyricLine(
  line: SyncedLine,
  positionState: State<Long>,
  activeUntilMs: Long,
  lineOpacity: () -> Float,
  isBackground: Boolean,
  isSecondary: Boolean,
  expressive: Boolean,
  modifier: Modifier = Modifier,
) {
  val content = remember(line) { lyricContent(line) }
  val measurer = rememberTextMeasurer()
  val measured = remember(content.text) { mutableStateOf<TextLayoutResult?>(null) }
  val style =
    MaterialTheme.typography.titleLarge.copy(
      fontSize = if (isBackground) 22.sp else 28.sp,
      lineHeight = if (isBackground) 30.sp else 36.sp,
      fontWeight = FontWeight.Bold,
      fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
      textAlign = if (isSecondary) TextAlign.End else TextAlign.Start,
      color = Color.White,
    )

  Layout(
    content = {},
    modifier =
      modifier
        .semantics { text = AnnotatedString(content.text) }
        .drawWithCache {
          val layout = measured.value
          val em = style.fontSize.toPx()
          val glyphStyle = style.copy(textAlign = TextAlign.Start)
          // Keep geometry across activation/seek changes, but only shape individual
          // glyphs when this row is first drawn active.
          val groups by lazy(LazyThreadSafetyMode.NONE) {
            layout
              ?.let { fullLayout ->
                lyricGeometry(content, fullLayout) { grapheme ->
                  measurer.measure(grapheme, glyphStyle, softWrap = false)
                }
              }.orEmpty()
          }
          // Hide animated text by character range rather than cutting caret rectangles
          // out of a white paragraph. Glyph ink (for example a j's hook) can overhang
          // those rectangles and would otherwise remain prematurely highlighted.
          val backgroundLayout by lazy(LazyThreadSafetyMode.NONE) {
            layout?.takeIf { groups.isNotEmpty() }?.let { fullLayout ->
              val backgroundText =
                buildAnnotatedString {
                  append(content.text)
                  groups.forEach { group ->
                    addStyle(SpanStyle(color = Color.Transparent), group.start, group.end)
                  }
                }
              measurer.measure(backgroundText, style, constraints = fullLayout.layoutInput.constraints)
            }
          }
          onDrawBehind {
            if (layout == null) return@onDrawBehind
            // Membership and fill must sample the same clock in the same draw. A
            // composable active flag can otherwise lag one frame behind a seek.
            val now = positionState.value
            val active = now >= line.time && now <= activeUntilMs
            // Compensate for the parent's animated alpha: unsung text should keep
            // the same visible brightness while a row becomes active.
            val dim =
              Color.White.copy(
                alpha = (LYRIC_UNHIGHLIGHTED_ALPHA / lineOpacity().coerceAtLeast(0.001f)).coerceAtMost(1f),
              )
            if (!active) {
              drawText(layout, color = if (now < line.time) dim else Color.White)
              return@onDrawBehind
            }
            if (groups.isEmpty()) {
              drawText(layout, color = Color.White)
              return@onDrawBehind
            }
            backgroundLayout?.let { drawText(it) }
            groups.forEach { group ->
              drawLyricGroup(layout, group, now, em, dim, isBackground, expressive)
            }
          }
        },
  ) { _, constraints ->
    val result = measurer.measure(content.text, style, constraints = constraints)
    if (measured.value !== result) measured.value = result
    layout(result.size.width, result.size.height) {}
  }
}

private data class LyricTiming(
  val start: Int,
  val end: Int,
  val from: Long,
  val until: Long,
)

private data class LyricContent(
  val text: String,
  val timings: List<LyricTiming>,
)

private data class LyricCluster(
  val start: Int,
  val end: Int,
  val bounds: Rect,
  val row: Int,
  val rtl: Boolean,
)

private data class LyricFill(
  val start: Int,
  val end: Int,
  val path: Path,
  val bounds: Rect,
  val timing: LyricTiming,
  val precedingWidth: Float,
  val totalWidth: Float,
  val rtl: Boolean,
)

private data class LyricGlyph(
  val layout: TextLayoutResult,
  val origin: Offset,
  val fill: LyricFill?,
)

private data class LyricPiece(
  val path: Path?,
  val bounds: Rect,
  val order: Int,
  val glyph: LyricGlyph? = null,
)

private data class LyricGroup(
  val start: Int,
  val end: Int,
  val pieces: List<LyricPiece>,
  val fills: List<LyricFill>,
  val from: Long,
  val until: Long,
  val last: Boolean,
  val independent: Boolean,
)

private fun lyricContent(line: SyncedLine): LyricContent {
  val words = line.words.orEmpty()
  val text = line.line.ifEmpty { words.joinToString("") { it.word } }
  var cursor = 0
  val timings = ArrayList<LyricTiming>()
  words.forEachIndexed { index, word ->
    val token = word.word.trim()
    if (token.isEmpty()) return@forEachIndexed
    val start = text.indexOf(token, cursor)
    // Keep the authoritative display text intact if timing text cannot be reconciled.
    if (start < 0) return LyricContent(text, emptyList())
    val from = word.time.toLong()
    val until =
      word.endTime?.toLong()?.takeIf { it > from }
        ?: words
          .getOrNull(index + 1)
          ?.time
          ?.toLong()
          ?.takeIf { it > from }
        ?: line.endTime?.toLong()?.takeIf { it > from }
        ?: (from + 500L)
    cursor = start + token.length
    timings.add(LyricTiming(start, cursor, from, until))
  }
  return LyricContent(text, timings)
}

private fun lyricGeometry(
  content: LyricContent,
  layout: TextLayoutResult,
  measureGlyph: (String) -> TextLayoutResult,
): List<LyricGroup> {
  if (content.timings.isEmpty()) return emptyList()
  val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(content.text) }
  val clusters = ArrayList<LyricCluster>()
  var start = iterator.first()
  var end = iterator.next()
  while (end != BreakIterator.DONE) {
    var bounds = layout.getBoundingBox(start)
    // UTF-16 offsets are used only to query the already-shaped layout, never as glyphs.
    for (offset in start + 1 until end) bounds = enclosingRect(bounds, layout.getBoundingBox(offset))
    clusters.add(
      LyricCluster(
        start,
        end,
        bounds,
        layout.getLineForOffset(start),
        layout.getBidiRunDirection(start) == ResolvedTextDirection.Rtl,
      ),
    )
    start = end
    end = iterator.next()
  }
  val wordGroups = Regex("\\S+").findAll(content.text).toList()
  return wordGroups.mapIndexedNotNull { groupIndex, match ->
    val members = clusters.filter { it.start in match.range }
    val timings = content.timings.filter { it.start <= match.range.last && it.end > match.range.first }
    if (members.isEmpty() || timings.isEmpty()) return@mapIndexedNotNull null
    val independent = members.none { it.rtl } && canAnimateClusters(match.value) && !hasSharedGlyphBounds(members)
    val runs = visualRuns(members)
    val fills =
      timings.flatMap { timing ->
        // Snap malformed timing offsets to whole graphemes, including emoji/combining marks.
        val timedClusters = members.filter { it.start >= timing.start && it.start < timing.end }
        val timedRuns = visualRuns(timedClusters)
        val totalWidth = timedRuns.sumOf { runBounds(it).width.toDouble() }.toFloat().coerceAtLeast(1f)
        var preceding = 0f
        timedRuns.map { run ->
          val bounds = runBounds(run)
          LyricFill(
            run.first().start,
            run.last().end,
            rangePath(layout, run),
            bounds,
            timing,
            preceding,
            totalWidth,
            run.first().rtl,
          ).also { preceding += bounds.width }
        }
      }
    val pieces =
      if (independent) {
        members.mapIndexed { index, cluster ->
          val glyph = measureGlyph(content.text.substring(cluster.start, cluster.end))
          // Use full-line positions, preserving kerning and wrapping rather than summing widths.
          val origin = Offset(cluster.bounds.left, layout.getLineBaseline(cluster.row) - glyph.firstBaseline)
          LyricPiece(
            path = null,
            bounds = cluster.bounds,
            order = index,
            glyph = LyricGlyph(glyph, origin, fills.firstOrNull { cluster.start in it.start until it.end }),
          )
        }
      } else {
        runs.mapIndexed { index, run ->
          LyricPiece(rangePath(layout, run), runBounds(run), index)
        }
      }
    LyricGroup(
      match.range.first,
      match.range.last + 1,
      pieces,
      fills,
      timings.minOf { it.from },
      timings.maxOf { it.until },
      groupIndex == wordGroups.lastIndex,
      independent,
    )
  }
}

private fun visualRuns(clusters: List<LyricCluster>): List<List<LyricCluster>> {
  val runs = ArrayList<MutableList<LyricCluster>>()
  clusters.forEach { cluster ->
    val previous = runs.lastOrNull()?.lastOrNull()
    if (previous == null || previous.row != cluster.row || previous.rtl != cluster.rtl) {
      runs.add(arrayListOf(cluster))
    } else {
      runs.last().add(cluster)
    }
  }
  return runs
}

private fun rangePath(
  layout: TextLayoutResult,
  clusters: List<LyricCluster>,
): Path =
  Path().apply {
    visualRuns(clusters).forEach { run ->
      addPath(layout.getPathForRange(run.first().start, run.last().end))
    }
  }

private fun runBounds(clusters: List<LyricCluster>): Rect =
  clusters.drop(1).fold(clusters.first().bounds) { bounds, cluster -> enclosingRect(bounds, cluster.bounds) }

private fun enclosingRect(
  first: Rect,
  second: Rect,
): Rect =
  Rect(
    minOf(first.left, second.left),
    minOf(first.top, second.top),
    maxOf(first.right, second.right),
    maxOf(first.bottom, second.bottom),
  )

private fun hasSharedGlyphBounds(clusters: List<LyricCluster>): Boolean =
  clusters.any { it.bounds.width <= 0f } ||
    clusters.zipWithNext().any { (first, second) ->
      first.row == second.row && first.bounds.overlaps(second.bounds)
    }

/** Conservative allowlist: joining, Indic and unknown scripts retain their shaped runs. */
private fun canAnimateClusters(text: String): Boolean {
  // Common Latin ligatures also stay together, even on fonts reporting divided caret boxes.
  if (text.contains("fi", ignoreCase = true) || text.contains("fl", ignoreCase = true)) return false
  var offset = 0
  while (offset < text.length) {
    val codePoint = text.codePointAt(offset)
    when (Character.UnicodeScript.of(codePoint)) {
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.GREEK,
      Character.UnicodeScript.CYRILLIC,
      Character.UnicodeScript.HAN,
      Character.UnicodeScript.HIRAGANA,
      Character.UnicodeScript.KATAKANA,
      Character.UnicodeScript.HANGUL,
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.INHERITED,
      -> Unit
      else -> return false
    }
    offset += Character.charCount(codePoint)
  }
  return true
}

private fun DrawScope.drawLyricGroup(
  layout: TextLayoutResult,
  group: LyricGroup,
  now: Long,
  em: Float,
  dim: Color,
  isBackground: Boolean,
  expressive: Boolean,
) {
  val duration = (group.until - group.from).coerceAtLeast(1L)
  val held = smooth((now - group.from) / 160f) * (1f - smooth((now - group.until) / 220f))
  val lift = if (expressive) -em * (if (isBackground) 0.10f else 0.05f) * held else 0f
  val emphasis = expressive && group.independent && duration >= 1000L
  val strength = if (group.last) 1f else 0.75f
  group.pieces.forEach { piece ->
    val stagger = if (emphasis) minOf(duration * 0.35f, 250f) * piece.order / group.pieces.size else 0f
    val phase = ((now - group.from - stagger) / duration).coerceIn(0f, 1f)
    val release = 1f - smooth((now - group.until) / 250f)
    val pulse = if (emphasis) smooth(phase * 2f) * smooth((1f - phase) * 2f) * strength * release else 0f
    val scale = 1f + 0.12f * pulse
    val spread = (piece.order - (group.pieces.size - 1) / 2f) * em * 0.018f * pulse
    val direction = if (group.fills.firstOrNull()?.rtl == true) -1f else 1f
    withTransform({
      translate(spread * direction, lift - em * 0.06f * pulse)
      scale(scale, scale, piece.bounds.center)
    }) {
      val glyph = piece.glyph
      if (glyph != null) {
        // Neither the glyph nor its shadow is clipped to a selection/caret rectangle.
        withTransform({ translate(glyph.origin.x, glyph.origin.y) }) {
          val fill = glyph.fill
          val progress = fill?.let { fillProgress(it, now) } ?: 1f
          val shadow =
            if (pulse > 0.01f) {
              Shadow(Color.White.copy(alpha = 0.6f * pulse), Offset.Zero, em * 0.22f)
            } else {
              Shadow.None
            }
          if (fill == null || progress >= 1f) {
            drawText(glyph.layout, color = Color.White, shadow = shadow)
          } else if (progress <= 0f) {
            drawText(glyph.layout, color = dim, shadow = shadow)
          } else {
            drawText(
              glyph.layout,
              brush = sweepBrush(fill, progress, originX = glyph.origin.x, inactiveColor = dim),
              shadow = shadow,
            )
          }
        }
      } else {
        drawShapedPiece(layout, requireNotNull(piece.path), group.fills, now, dim)
      }
    }
  }
}

private fun DrawScope.drawShapedPiece(
  layout: TextLayoutResult,
  path: Path,
  fills: List<LyricFill>,
  now: Long,
  dim: Color,
) {
  clipPath(path) {
    drawText(layout, color = dim)
    fills.forEach { fill ->
      clipPath(fill.path) {
        val progress = fillProgress(fill, now)
        if (progress > 0f) {
          if (progress >= 1f) {
            drawText(layout, color = Color.White)
          } else {
            drawText(layout, brush = sweepBrush(fill, progress))
          }
        }
      }
    }
  }
}

private fun fillProgress(
  fill: LyricFill,
  now: Long,
): Float = ((now - fill.timing.from).toFloat() / (fill.timing.until - fill.timing.from)).coerceIn(0f, 1f)

private fun sweepBrush(
  fill: LyricFill,
  progress: Float,
  originX: Float = 0f,
  inactiveColor: Color = Color.White.copy(alpha = 0f),
): Brush {
  val feather = fill.totalWidth * 0.10f
  val distance = progress * (fill.totalWidth + feather) - feather / 2f - fill.precedingWidth
  val center = (if (fill.rtl) fill.bounds.right - distance else fill.bounds.left + distance) - originX
  val bright = Color.White
  return Brush.horizontalGradient(
    colors = if (fill.rtl) listOf(inactiveColor, bright) else listOf(bright, inactiveColor),
    startX = center - feather / 2f,
    endX = center + feather / 2f,
  )
}

private fun smooth(value: Float): Float {
  val t = value.coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}
