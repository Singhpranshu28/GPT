package com.flownotes.recording

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.flownotes.FlowNotesApp
import com.flownotes.ai.LocalCleaner
import com.flownotes.ai.Polisher
import com.flownotes.ai.Transcriber
import com.flownotes.data.Note
import com.flownotes.data.NoteStatus
import com.flownotes.data.SttEngine
import com.flownotes.stt.WhisperEngine
import java.io.File

/** Audio file -> raw transcript -> polished note. Safe to re-run on a saved note (retry / re-polish). */
object NoteProcessor {
    private val transcriber = Transcriber()
    private val polisher = Polisher()

    /**
     * @param retranscribe force a new speech-to-text pass even if a transcript exists.
     * @param onStage progress callback for the overlay ("Transcribing…", "Polishing…").
     */
    suspend fun process(
        noteId: String,
        retranscribe: Boolean = false,
        onStage: (String) -> Unit = {},
    ): Note {
        val app = FlowNotesApp.instance
        val repo = app.notes
        val settings = app.settings.current
        var note = repo.get(noteId) ?: error("Note not found")
        note = note.copy(status = NoteStatus.PROCESSING, error = null).also { repo.upsert(it) }

        // 1. Speech to text
        if (retranscribe || note.rawTranscript.isBlank()) {
            val audio = note.audioPath?.let(::File)?.takeIf { it.exists() }
                ?: return fail(note, "The audio for this note is no longer available.")
            onStage("Transcribing…")
            val raw = runCatching {
                when (settings.sttEngine) {
                    SttEngine.ON_DEVICE -> WhisperEngine.transcribe(
                        app, audio, settings.whisperModel, settings.language, settings.dictionary,
                    ) { percent -> onStage("Transcribing on your phone… $percent%") }
                    SttEngine.CLOUD -> transcriber.transcribe(audio, settings)
                }
            }.getOrElse { return fail(note, it.message ?: "Transcription failed") }
            note = note.copy(rawTranscript = raw).also { repo.upsert(it) }
        }

        if (note.rawTranscript.isBlank()) {
            note = note.copy(title = "Empty note", body = "", status = NoteStatus.READY)
            repo.upsert(note)
            return note
        }

        // 2. Clean-up: Claude when a key is set, otherwise the free on-device rules.
        if (settings.anthropicApiKey.isBlank()) {
            val cleaned = LocalCleaner.clean(note.rawTranscript)
            note = note.copy(
                title = LocalCleaner.title(cleaned),
                body = cleaned,
                style = settings.style.name,
                status = NoteStatus.READY,
                error = null,
            )
        } else {
            onStage("Polishing…")
            note = runCatching { polisher.polish(note.rawTranscript, settings) }.fold(
                onSuccess = { p ->
                    note.copy(
                        title = p.title.ifBlank { LocalCleaner.title(p.body) },
                        body = p.body.trim(),
                        tags = p.tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct().take(3),
                        style = settings.style.name,
                        status = NoteStatus.READY,
                        error = null,
                    )
                },
                onFailure = { e ->
                    val cleaned = LocalCleaner.clean(note.rawTranscript)
                    note.copy(
                        title = note.title.ifBlank { LocalCleaner.title(cleaned) },
                        body = note.body.ifBlank { cleaned },
                        status = NoteStatus.READY,
                        error = "Claude clean-up failed, used the basic offline clean-up instead. ${e.message.orEmpty()}".trim(),
                    )
                },
            )
        }
        repo.upsert(note)
        if (settings.copyToClipboard && note.body.isNotBlank()) copy(app, note.body)
        return note
    }

    private suspend fun fail(note: Note, message: String): Note {
        val failed = note.copy(
            title = note.title.ifBlank { "Untranscribed recording" },
            status = NoteStatus.FAILED,
            error = message,
        )
        FlowNotesApp.instance.notes.upsert(failed)
        return failed
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Voice note", text))
    }
}
