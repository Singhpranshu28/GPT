package com.flownotes.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flownotes.FlowNotesApp
import com.flownotes.R
import com.flownotes.audio.AudioRecorder
import com.flownotes.data.Note
import com.flownotes.data.NoteStatus
import com.flownotes.data.WritingStyle
import com.flownotes.ui.RecordActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Owns the microphone while dictating, then finishes transcription + cleanup in the
 * foreground so the note is saved even if the overlay is dismissed or the screen turns off.
 */
class RecordingService : LifecycleService() {
    private var recorder: AudioRecorder? = null
    private var meterJob: Job? = null
    private var startedAt = 0L
    private var processing = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopAndProcess()
            ACTION_CANCEL -> cancel()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        goForeground(getString(R.string.notif_recording))
        val file = File(File(filesDir, "recordings"), "${System.currentTimeMillis()}.m4a")
        val r = AudioRecorder(this)
        try {
            r.start(file)
        } catch (e: Exception) {
            RecordingBus.set(RecordingState.Failed(null, "Couldn't start the microphone: ${e.message}"))
            stopIfIdle()
            return
        }
        recorder = r
        startedAt = System.currentTimeMillis()
        RecordingBus.set(RecordingState.Recording(startedAt))
        meterJob = lifecycleScope.launch { meter(r) }
    }

    /** Publishes the input level for the waveform and handles silence auto-stop. */
    private suspend fun meter(r: AudioRecorder) {
        val autoStopMs = FlowNotesApp.instance.settings.current.autoStopSilenceSec * 1000L
        var heardSpeech = false
        var lastLoud = System.currentTimeMillis()
        while (isActiveRecorder(r)) {
            val amp = r.maxAmplitude()
            val now = System.currentTimeMillis()
            if (amp > SPEECH_THRESHOLD) {
                heardSpeech = true
                lastLoud = now
            }
            RecordingBus.set(RecordingState.Recording(startedAt, (amp / 12_000f).coerceIn(0f, 1f)))
            if (autoStopMs > 0 && heardSpeech && now - lastLoud > autoStopMs) {
                stopAndProcess()
                return
            }
            delay(80)
        }
    }

    private suspend fun isActiveRecorder(r: AudioRecorder) = currentCoroutineContext().isActive && recorder === r

    private fun stopAndProcess() {
        val r = recorder ?: return
        recorder = null
        meterJob?.cancel()
        val audio = r.stop()
        val duration = System.currentTimeMillis() - startedAt
        if (audio == null || duration < MIN_DURATION_MS) {
            audio?.delete()
            RecordingBus.set(RecordingState.Idle)
            stopIfIdle()
            return
        }

        val app = FlowNotesApp.instance
        val note = Note(
            id = UUID.randomUUID().toString(),
            createdAt = startedAt,
            title = "",
            body = "",
            style = app.settings.current.style.name.ifBlank { WritingStyle.NOTE.name },
            durationMs = duration,
            audioPath = audio.absolutePath,
            status = NoteStatus.PROCESSING,
        )
        processing++
        goForeground(getString(R.string.notif_processing))
        RecordingBus.set(RecordingState.Processing(note.id, "Transcribing…"))
        lifecycleScope.launch {
            app.notes.upsert(note)
            val result = NoteProcessor.process(note.id) { stage ->
                if (recorder == null) RecordingBus.set(RecordingState.Processing(note.id, stage))
            }
            // A newer recording may already be running; don't cover its overlay with this result.
            if (recorder == null) RecordingBus.set(
                if (result.status == NoteStatus.FAILED) {
                    RecordingState.Failed(result.id, result.error ?: "Something went wrong")
                } else {
                    RecordingState.Done(result.id, result.title, result.body, result.reminderText, result.reminderAt, result.error)
                },
            )
            processing--
            stopIfIdle()
        }
    }

    private fun cancel() {
        meterJob?.cancel()
        recorder?.cancel()
        recorder = null
        RecordingBus.set(RecordingState.Idle)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (recorder == null && processing == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecordActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, FlowNotesApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .apply { if (recorder != null) addAction(R.drawable.ic_mic, getString(R.string.stop_and_save), stop) }
            .build()
    }

    companion object {
        const val ACTION_START = "com.flownotes.START"
        const val ACTION_STOP = "com.flownotes.STOP"
        const val ACTION_CANCEL = "com.flownotes.CANCEL"
        private const val NOTIFICATION_ID = 42
        private const val SPEECH_THRESHOLD = 1_800
        private const val MIN_DURATION_MS = 700L

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
