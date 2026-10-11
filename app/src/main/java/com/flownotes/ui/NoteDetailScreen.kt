package com.flownotes.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flownotes.FlowNotesApp
import com.flownotes.data.NoteStatus
import com.flownotes.recording.NoteProcessor
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
@Composable
fun NoteDetailScreen(id: String, onBack: () -> Unit) {
    val app = FlowNotesApp.instance
    val notes by app.notes.notes.collectAsStateWithLifecycle()
    val note = notes.firstOrNull { it.id == id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (note == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Local edit buffers; re-seeded whenever processing produces new text.
    var title by rememberSaveable(note.id, note.status) { mutableStateOf(note.title) }
    var body by rememberSaveable(note.id, note.status) { mutableStateOf(note.body) }
    var showRaw by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = note.status == NoteStatus.PROCESSING

    // Persist edits when leaving the screen. GlobalScope so the write outlives the composition.
    val latest by rememberUpdatedState(Triple(note, title, body))
    DisposableEffect(note.id) {
        onDispose {
            val (n, t, b) = latest
            val stored = app.notes.get(n.id) // null if the note was just deleted
            if (stored != null && stored.status != NoteStatus.PROCESSING && (t != stored.title || b != stored.body)) {
                GlobalScope.launch { app.notes.upsert(stored.copy(title = t, body = b)) }
            }
        }
    }

    fun reprocess(retranscribe: Boolean) {
        // Run outside the screen's scope so leaving the screen doesn't cancel it.
        GlobalScope.launch {
            app.notes.get(id)?.let { app.notes.upsert(it.copy(title = title, body = body)) }
            NoteProcessor.process(id, retranscribe = retranscribe)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        NoteProcessor.copy(context, body)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, title)
                            .putExtra(Intent.EXTRA_TEXT, body)
                        context.startActivity(Intent.createChooser(send, "Share note"))
                    }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val plain = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            )
            TextField(
                value = title,
                onValueChange = { title = it },
                enabled = !busy,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                placeholder = { Text("Title") },
                colors = plain,
                modifier = Modifier.fillMaxWidth(),
            )
            note.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            TextField(
                value = body,
                onValueChange = { body = it },
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(if (busy) "Working on it…" else "Empty note") },
                colors = plain,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note.rawTranscript.isNotBlank()) {
                    OutlinedButton(enabled = !busy, onClick = { reprocess(retranscribe = false) }) { Text("Re-polish") }
                }
                if (note.audioPath != null) {
                    OutlinedButton(enabled = !busy, onClick = { reprocess(retranscribe = true) }) {
                        Text(if (note.status == NoteStatus.FAILED) "Retry" else "Re-transcribe")
                    }
                }
            }

            if (note.rawTranscript.isNotBlank()) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Hide what you actually said" else "Show what you actually said")
                }
                if (showRaw) {
                    Text(
                        note.rawTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("The text and its audio recording will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { app.notes.delete(id); onBack() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
