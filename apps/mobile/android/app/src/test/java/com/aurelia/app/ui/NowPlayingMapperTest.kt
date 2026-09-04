package com.aurelia.app.ui

import com.aurelia.app.player.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMapperTest {
  @Test
  fun equalTitlesDoNotHideIdentityArtworkBufferingOrNavigationChanges() {
    val mapper = NowPlayingMapper()
    var snapshot = PlayerSnapshot(currentSongId = "first", title = "Same", artist = "Artist")
    assertTrue(mapper.shouldUpdate(snapshot))
    assertFalse(mapper.shouldUpdate(snapshot.copy(positionMs = 1000)))
    snapshot = snapshot.copy(currentSongId = "second")
    assertTrue(mapper.shouldUpdate(snapshot))
    assertEquals("second", mapper.mapToNowPlaying(snapshot).second)
    snapshot = snapshot.copy(isBuffering = true)
    assertTrue(mapper.shouldUpdate(snapshot))
    snapshot = snapshot.copy(albumArtUrl = "new-art")
    assertTrue(mapper.shouldUpdate(snapshot))
    assertTrue(mapper.shouldUpdate(snapshot.copy(hasNext = true)))
    assertEquals(null to null, mapper.mapToNowPlaying(PlayerSnapshot(title = "Same")))
  }
}
