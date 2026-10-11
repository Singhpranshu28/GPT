package com.flownotes.assist

import android.service.voice.VoiceInteractionService

/**
 * Registering a VoiceInteractionService lets the user choose FlowNotes as the phone's
 * "Digital assistant app". After that, a long press of the side/power button (or the
 * assistant gesture) starts a new voice note instead of Google Assistant / Gemini / Bixby.
 */
class FlowVoiceInteractionService : VoiceInteractionService()
