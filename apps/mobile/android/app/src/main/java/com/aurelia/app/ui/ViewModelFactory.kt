package com.aurelia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Generic factory for creating ViewModels with constructor parameters.
 * Replaces individual *ViewModelFactory classes.
 *
 * Usage: `viewModel(factory = viewModelFactory { MyViewModel(dep1, dep2) })`
 */
inline fun <reified VM : ViewModel> viewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
  object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
  }
