package com.aurelia.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aurelia.app.player.PlayerController

class SharedPlayerControllerViewModel(
  application: Application,
) : AndroidViewModel(application) {
  val playerController = PlayerController(application)

  override fun onCleared() {
    super.onCleared()
    playerController.release()
  }
}
