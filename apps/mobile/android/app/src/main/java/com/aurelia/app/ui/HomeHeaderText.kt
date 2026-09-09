package com.aurelia.app.ui

/** Process lifetime: navigation, recomposition, and activity recreation keep the same greeting. */
internal object HomeHeaderText {
  val options = listOf("Tune in", "Hit play", "Your music", "Find a vibe", "Good tunes")
  val current: String = options.random()
}
