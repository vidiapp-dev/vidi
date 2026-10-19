package com.example.ytclient.core.models

/**
 * Generic presentation state wrapper used by every screen's ViewModel.
 *
 * Lives in `core/models` because more than one UI package consumes it
 * (search, player, home, subscriptions in later milestones).
 */
sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
