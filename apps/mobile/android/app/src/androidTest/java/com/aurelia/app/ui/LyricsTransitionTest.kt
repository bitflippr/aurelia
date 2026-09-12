package com.aurelia.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aurelia.app.data.model.Lyrics
import com.aurelia.app.data.model.SyncedLine
import org.junit.Rule
import org.junit.Test

class LyricsTransitionTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun nextSongPositionResetBeforeLyricsRecompositionDoesNotCrash() {
    val position = mutableLongStateOf(5000L)
    val lyrics = Lyrics(synced = List(12) { SyncedLine(time = 1000 + it * 1000, line = "Lyric $it") })
    compose.setContent {
      MaterialTheme {
        LyricsView(lyrics, position, {}, Color.White, Modifier.fillMaxSize())
      }
    }
    compose.waitForIdle()
    // A track transition resets playback time before the next frame replaces its lyrics.
    // Deliver the position snapshot while retaining the previous composition/effect.
    compose.mainClock.autoAdvance = false
    compose.runOnIdle {
      position.longValue = 0L
      Snapshot.sendApplyNotifications()
    }
    compose.waitForIdle()
    compose.mainClock.autoAdvance = true
    compose.waitForIdle()
    compose.runOnIdle { position.longValue = 1000L }
    compose.waitForIdle()
    compose.onNodeWithText("Lyric 0").assertExists()
  }
}
