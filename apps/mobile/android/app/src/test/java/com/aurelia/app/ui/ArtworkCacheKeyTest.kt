package com.aurelia.app.ui

import com.aurelia.app.ui.components.artworkCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkCacheKeyTest {
  @Test
  fun artworkCacheSeparatesAccountsServersAndReplacedFiles() {
    val original = artworkCacheKey("https://music.example/jellyfin", "alice", "first")
    assertEquals(original, artworkCacheKey("https://music.example/jellyfin", "alice", "first"))
    assertNotEquals(original, artworkCacheKey("https://other.example/jellyfin", "alice", "first"))
    assertNotEquals(original, artworkCacheKey("https://music.example/jellyfin", "bob", "first"))
    assertNotEquals(original, artworkCacheKey("https://music.example/jellyfin", "alice", "replacement"))
  }
}
