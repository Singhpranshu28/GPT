package com.flownotes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.flownotes.data.NotesRepository
import com.flownotes.data.SettingsStore
import com.flownotes.drive.DriveSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlowNotesApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var notes: NotesRepository
        private set

    /** Lives as long as the process: background work that must outlive any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        DriveSync.start(this, scope)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        lateinit var instance: FlowNotesApp
            private set
    }
}
