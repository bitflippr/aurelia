package com.aurelia.app.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.utils.SessionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import uniffi.aurelia_core.reportPlaybackProgressEvent
import uniffi.aurelia_core.reportPlaybackStartEvent
import uniffi.aurelia_core.reportPlaybackStopEvent

/** Service-owned reporting survives screen changes and captures credentials for each play. */
internal class PlaybackReporting(
  private val player: Player,
  private val sessions: SessionStore,
) : Player.Listener {
  private data class Playing(
    val id: String,
    val session: SessionData,
  )

  private val reports = Channel<suspend () -> Unit>(32)
  private var current: Playing? = null
  private var positionMs = 0L
  private var lastReportedMs = 0L
  private var paused = true
  private val handler = Handler(Looper.getMainLooper())
  private val ticker =
    object : Runnable {
      override fun run() {
        update()
        handler.postDelayed(this, 10_000)
      }
    }

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      for (report in reports) {
        try {
          withTimeout(5_000) { report() }
        } catch (
          error: Exception,
        ) {
          Log.d("PlaybackReporting", "Report failed", error)
        }
      }
    }
    player.addListener(this)
    handler.post(ticker)
  }

  override fun onEvents(
    player: Player,
    events: Player.Events,
  ) = update()

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    if (current?.id == oldPosition.mediaItem?.mediaId) positionMs = oldPosition.positionMs
    if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
      oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId ||
      reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
    ) {
      finish()
    }
  }

  private fun update() {
    val id = player.currentMediaItem?.mediaId
    if (current?.id == id) positionMs = player.currentPosition
    if (current?.id != id || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
      finish()
    }
    if (current == null && player.isPlaying && !id.isNullOrBlank()) {
      val session = sessions.snapshot() ?: return
      current = Playing(id, session)
      lastReportedMs = player.currentPosition
      val startTicks = playerPositionTicks(lastReportedMs)
      enqueue { reportPlaybackStartEvent(session.serverUrl, session.token, id, startTicks) }
    }
    val playing = current ?: return
    positionMs = player.currentPosition
    val isPaused = !player.isPlaying
    if (isPaused != paused || kotlin.math.abs(positionMs - lastReportedMs) >= 10_000) {
      val ticks = playerPositionTicks(positionMs)
      enqueue {
        reportPlaybackProgressEvent(playing.session.serverUrl, playing.session.token, playing.id, ticks, isPaused)
      }
      lastReportedMs = positionMs
    }
    paused = isPaused
  }

  private fun finish() {
    val playing = current ?: return
    current = null
    val ticks = playerPositionTicks(positionMs)
    enqueue { reportPlaybackStopEvent(playing.session.serverUrl, playing.session.token, playing.id, ticks) }
  }

  fun close() {
    positionMs = player.currentPosition
    finish()
    player.removeListener(this)
    handler.removeCallbacks(ticker)
    reports.close() // Drain queued stop reports after the service releases local playback.
  }

  private fun enqueue(report: suspend () -> Unit) {
    if (reports.trySend(report).isFailure) Log.w("PlaybackReporting", "Report queue is full")
  }

  private fun playerPositionTicks(position: Long): Long = position.coerceAtLeast(0) * 10_000
}
