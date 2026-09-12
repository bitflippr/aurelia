package com.aurelia.app.ui

import com.aurelia.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongNavigationTest {
  @Test
  fun collaborationOffersEachArtistWithTheirOwnNameAndId() {
    assertEquals(
      listOf(Screen.ArtistDetail("glaive-id", "glaive"), Screen.ArtistDetail("ericdoa-id", "ericdoa")),
      artistDestinations(listOf("glaive-id", "ericdoa-id"), listOf("glaive", "ericdoa")),
    )
  }

  @Test
  fun commasInsideOneArtistNameArePreserved() {
    assertEquals(
      listOf(Screen.ArtistDetail("artist-id", "Earth, Wind & Fire")),
      artistDestinations(listOf("artist-id"), listOf("Earth, Wind & Fire")),
    )
  }

  @Test
  fun missingIdsAreSkippedWithoutShiftingTheOtherArtistsNames() {
    assertTrue(artistDestinations(null, listOf("glaive")).isEmpty())
    assertEquals(
      listOf(Screen.ArtistDetail("ericdoa-id", "ericdoa")),
      artistDestinations(listOf("", "ericdoa-id"), listOf("glaive", "ericdoa")),
    )
  }

  @Test
  fun duplicateIdsAppearOnceAndMissingNamesHaveAFallback() {
    assertEquals(
      listOf(Screen.ArtistDetail("a", "glaive"), Screen.ArtistDetail("b", "Unknown Artist")),
      artistDestinations(listOf("a", "a", "b"), listOf("glaive", "glaive")),
    )
  }
}
