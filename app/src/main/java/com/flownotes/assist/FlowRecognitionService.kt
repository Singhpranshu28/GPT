package com.flownotes.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires a digital-assistant app to declare a RecognitionService. FlowNotes does its
 * own transcription, so other apps asking it for live speech recognition simply get an error and
 * fall back to their default recognizer.
 */
class FlowRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
