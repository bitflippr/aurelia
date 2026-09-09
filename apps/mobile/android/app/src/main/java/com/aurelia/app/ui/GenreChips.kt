package com.aurelia.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

private const val GENRE_FONT_PATH = "fonts/google_sans_flex_regular.ttf"

private data class FittedGenreRow(
  val row: GenreRow,
  val styles: List<TextStyle>,
)

@OptIn(ExperimentalTextApi::class)
@Composable
internal fun GenreChips(
  genres: List<String>,
  onPlayGenre: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val assets = LocalContext.current.assets
  val density = LocalDensity.current
  val typeface = remember(assets) { Typeface.createFromAsset(assets, GENRE_FONT_PATH) }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val width = constraints.maxWidth.toFloat()
    val fitted =
      remember(genres, width, density, assets, typeface) {
        val gap = with(density) { 8.dp.toPx() }
        // Reserve border space as well as horizontal padding around the type.
        val padding = with(density) { 22.dp.toPx() }
        val weights = genres.map { listOf(500, 580, 660, 740)[it.sumOf { char -> char.code } % 4] }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }

        fun measure(
          index: Int,
          size: Float,
          axis: Float,
        ): Float {
          paint.textSize = with(density) { size.sp.toPx() }
          paint.fontVariationSettings = "'wdth' $axis, 'wght' ${weights[index]}, 'ROND' 100, 'opsz' 24"
          return paint.measureText(genres[index])
        }
        val natural = genres.indices.map { measure(it, 23f, 100f) + padding }
        val minimum = genres.indices.map { measure(it, 16f, 72f) + padding }
        fitGenreRows(natural, minimum, width, gap).map { row ->
          val styles =
            row.widths.mapIndexed { column, cell ->
              val index = row.start + column
              val room = (cell - padding).coerceAtLeast(0f)
              var size = (23f * sqrt(row.ratio).coerceIn(0.88f, 1.12f)).coerceAtLeast(16f)
              while (size > 16f && measure(index, size, 72f) > room) size = (size - 0.25f).coerceAtLeast(16f)
              var low = 72f
              var high = 138f
              repeat(8) {
                val mid = (low + high) / 2
                if (measure(index, size, mid) > room) high = mid else low = mid
              }
              val weight = FontWeight(weights[index])
              TextStyle(
                fontFamily =
                  FontFamily(
                    Font(
                      path = GENRE_FONT_PATH,
                      assetManager = assets,
                      weight = weight,
                      variationSettings =
                        FontVariation.Settings(
                          FontVariation.weight(weight.weight),
                          FontVariation.width(low),
                          FontVariation.Setting("ROND", 100f),
                          FontVariation.Setting("opsz", 24f),
                        ),
                    ),
                  ),
                fontWeight = weight,
                fontSize = size.sp,
                lineHeight = (size * 1.15f).sp,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
              )
            }
          FittedGenreRow(row, styles)
        }
      }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
      fitted.forEach { (row, styles) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.widths.forEachIndexed { column, cell ->
            val genre = genres[row.start + column]
            Surface(
              onClick = { onPlayGenre(genre) },
              modifier = Modifier.weight(cell),
              shape = RoundedCornerShape(17.dp),
              color = MaterialTheme.colorScheme.surfaceContainer,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              Box(
                Modifier.heightIn(min = 57.dp).padding(horizontal = 11.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  genre,
                  style = styles[column],
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
    }
  }
}
