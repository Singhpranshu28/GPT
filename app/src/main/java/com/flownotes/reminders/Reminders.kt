package com.flownotes.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flownotes.FlowNotesApp
import com.flownotes.R
import com.flownotes.data.Note
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schedules note reminders with AlarmManager; [ReminderReceiver] shows them as notifications. */
object Reminders {
    const val CHANNEL = "reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Parses the model's "YYYY-MM-DDTHH:MM" local time; null if empty, malformed, or already past. */
    fun parse(localDateTime: String, zone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
        LocalDateTime.parse(localDateTime.trim()).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()?.takeIf { it > System.currentTimeMillis() + 30_000 }

    /** Exact alarms need a user-granted permission on Android 12+; without it reminders may be a few minutes late. */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, note: Note) {
        val at = note.reminderAt
        if (note.reminderText == null || note.reminderDone || at == null || at <= System.currentTimeMillis()) {
            cancel(context, note.id)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, note.id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: fall back to an inexact alarm.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, noteId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, noteId))
    }

    /** Alarms don't survive a reboot or app update; re-arm every pending reminder. */
    fun rescheduleAll(context: Context) {
        FlowNotesApp.instance.notes.notes.value.forEach { schedule(context, it) }
    }

    fun format(at: Long): String {
        val dt = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val day = when (dt.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> dt.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
        return day + ", " + dt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }

    private fun pendingIntent(context: Context, noteId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
        return PendingIntent.getBroadcast(
            context, noteId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
