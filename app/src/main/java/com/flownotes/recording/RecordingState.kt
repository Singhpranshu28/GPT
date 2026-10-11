package com.flownotes.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val startedAt: Long, val level: Float = 0f) : RecordingState
    data class Processing(val noteId: String, val stage: String) : RecordingState
    data class Done(
        val noteId: String,
        val title: String,
        val preview: String,
        val reminderText: String? = null,
        val reminderAt: Long? = null,
        val hint: String? = null,
    ) : RecordingState
    data class Failed(val noteId: String?, val message: String) : RecordingState
}

/** Shared between the recording service and whatever UI is showing the overlay. */
object RecordingBus {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Non-null while the overlay should show a setup prompt instead of recording. */
    private val _needsSetup = MutableStateFlow<String?>(null)
    val needsSetup: StateFlow<String?> = _needsSetup.asStateFlow()

    internal fun set(state: RecordingState) {
        _state.value = state
        if (state is RecordingState.Recording) _needsSetup.value = null
    }

    fun setNeedsSetup(problem: String) {
        _needsSetup.value = problem
    }

    fun clearNeedsSetup() {
        _needsSetup.value = null
    }
}
