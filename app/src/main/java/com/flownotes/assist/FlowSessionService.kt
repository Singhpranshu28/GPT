package com.flownotes.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.flownotes.ui.RecordActivity

class FlowSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FlowSession(this)
}

/** Invoked by the side-button long press: immediately hand off to the recording overlay. */
class FlowSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, RecordActivity::class.java)
            .setAction(RecordActivity.ACTION_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        finish()
    }
}
