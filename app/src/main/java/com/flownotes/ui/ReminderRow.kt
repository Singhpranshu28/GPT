package com.flownotes.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flownotes.FlowNotesApp
import com.flownotes.data.Note
import com.flownotes.reminders.Reminders
import kotlinx.coroutines.launch
import java.util.Calendar

/** Shows a note's reminder with Change / Remove, or an "Add reminder" button. */
@Composable
fun ReminderRow(note: Note, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = FlowNotesApp.instance

    fun update(transform: (Note) -> Note) {
        app.scope.launch {
            app.notes.modify(note.id, transform)
            app.notes.get(note.id)?.let { Reminders.schedule(context, it) }
        }
    }

    fun pickTime() = pickDateTime(context, note.reminderAt ?: defaultTime()) { at ->
        update { it.copy(reminderText = it.reminderText ?: it.title.ifBlank { "Voice note" }, reminderAt = at, reminderDone = false) }
    }

    val text = note.reminderText
    if (text == null) {
        TextButton(onClick = ::pickTime, enabled = enabled, modifier = modifier) {
            Icon(Icons.Default.Alarm, contentDescription = null)
            Text("  Add reminder")
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (note.reminderDone) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Alarm, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(text, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        note.reminderDone -> "Done" + (note.reminderAt?.let { " · " + Reminders.format(it) } ?: "")
                        note.reminderAt == null -> "No time yet: tap Set time"
                        else -> Reminders.format(note.reminderAt)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = ::pickTime, enabled = enabled) { Text(if (note.reminderAt == null) "Set time" else "Change") }
            TextButton(onClick = {
                Reminders.cancel(context, note.id)
                update { it.copy(reminderText = null, reminderAt = null, reminderDone = false) }
            }, enabled = enabled) { Text("Remove") }
        }
    }
}

private fun defaultTime(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.MINUTE, 0)
}.timeInMillis

/** Android's own date then time pickers. */
fun pickDateTime(context: Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val picked = Calendar.getInstance().apply {
                set(y, m, d, h, min, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(picked.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
    }.show()
}
