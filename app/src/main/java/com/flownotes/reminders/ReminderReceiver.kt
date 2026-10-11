package com.flownotes.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flownotes.FlowNotesApp
import com.flownotes.R
import com.flownotes.ui.MainActivity
import kotlinx.coroutines.launch

/** Re-arms reminders after a reboot, an app update, or when exact-alarm permission changes. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> Reminders.rescheduleAll(context)
        }
    }
}

/** Shows due reminders and handles "Done". Not exported: only this app's own alarms reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> fire(context, intent.getStringExtra(EXTRA_NOTE_ID) ?: return)
            ACTION_DONE -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                markDone(id)
            }
        }
    }

    private fun fire(context: Context, noteId: String) {
        val note = FlowNotesApp.instance.notes.get(noteId) ?: return
        if (note.reminderDone || note.reminderText == null) return

        val open = PendingIntent.getActivity(
            context, noteId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val done = PendingIntent.getBroadcast(
            context, noteId.hashCode() + 1,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(note.reminderText)
            .setContentText(note.title.ifBlank { "Voice note" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body.take(500)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_mic, context.getString(R.string.reminder_done), done)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33
        ) {
            context.getSystemService(NotificationManager::class.java).notify(noteId.hashCode(), notification)
        }
        markDone(noteId)
    }

    private fun markDone(noteId: String) {
        val pending = goAsync()
        FlowNotesApp.instance.scope.launch {
            try {
                FlowNotesApp.instance.notes.modify(noteId) { it.copy(reminderDone = true) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.flownotes.REMINDER_FIRE"
        const val ACTION_DONE = "com.flownotes.REMINDER_DONE"
        const val EXTRA_NOTE_ID = "note_id"
    }
}
