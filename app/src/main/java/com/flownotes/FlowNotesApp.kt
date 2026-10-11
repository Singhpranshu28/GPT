package com.flownotes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.flownotes.data.NotesRepository
import com.flownotes.data.SettingsStore

class FlowNotesApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var notes: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        notes = NotesRepository(this)

        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        lateinit var instance: FlowNotesApp
            private set
    }
}
