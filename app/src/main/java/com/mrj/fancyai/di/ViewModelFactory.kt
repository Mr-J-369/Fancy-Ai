package com.mrj.fancyai.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Builds a [ViewModelProvider.Factory] from a plain initializer lambda, so ViewModels
 * constructed manually via [ServiceLocator] can still be obtained through `viewModel()`
 * and therefore be scoped to (and cleared with) their owner — e.g. a NavBackStackEntry.
 */
fun <VM : ViewModel> viewModelFactory(initializer: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = initializer() as T
    }
