package com.aurelia.app.ui.components

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

@Composable
fun VisualizerFrameMetrics(
  tag: String,
  enabled: Boolean,
) {
  val context = LocalContext.current
  val isDebuggable =
    remember(context) {
      (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
  if (!isDebuggable || !enabled) return

  LaunchedEffect(tag, enabled) {
    var windowFrameCount = 0
    var windowSlowFrameCount = 0
    var lastFrameNanos = 0L
    var windowStartNanos = 0L

    while (enabled) {
      val nowNanos = withFrameNanos { it }
      if (windowStartNanos == 0L) {
        windowStartNanos = nowNanos
      }

      if (lastFrameNanos != 0L) {
        val frameDurationMs = (nowNanos - lastFrameNanos) / 1_000_000f
        windowFrameCount += 1
        if (frameDurationMs > SLOW_FRAME_THRESHOLD_MS) {
          windowSlowFrameCount += 1
        }
      }

      val elapsedWindowNanos = nowNanos - windowStartNanos
      if (elapsedWindowNanos >= REPORT_WINDOW_NANOS) {
        val slowPct =
          if (windowFrameCount > 0) {
            (windowSlowFrameCount * 100f) / windowFrameCount
          } else {
            0f
          }
        Log.d(
          LOG_TAG,
          "$tag frames=$windowFrameCount slowFrames=$windowSlowFrameCount slowPct=${"%.1f".format(slowPct)}",
        )
        windowFrameCount = 0
        windowSlowFrameCount = 0
        windowStartNanos = nowNanos
      }

      lastFrameNanos = nowNanos
    }
  }
}

private const val LOG_TAG = "VisualizerPerf"
private const val SLOW_FRAME_THRESHOLD_MS = 20f
private const val REPORT_WINDOW_NANOS = 5_000_000_000L
