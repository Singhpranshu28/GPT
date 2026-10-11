package com.flownotes.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val startedAt: Long, val level: Float = 0f) : RecordingState
    data class Processing(val noteId: String, val stage: String) : RecordingState
    data class Done(val noteId: String, val title: String, val preview: String) : RecordingState
    data class Failed(val noteId: String?, val message: String) : RecordingState
}

/** Shared between the recording service and whatever UI is showing the overlay. */
object RecordingBus {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _needsSetup = MutableStateFlow(false)
    val needsSetup: StateFlow<Boolean> = _needsSetup.asStateFlow()

    internal fun set(state: RecordingState) {
        _state.value = state
        if (state is RecordingState.Recording) _needsSetup.value = false
    }

    fun setNeedsSetup() {
        _needsSetup.value = true
    }

    fun clearNeedsSetup() {
        _needsSetup.value = false
    }
}
