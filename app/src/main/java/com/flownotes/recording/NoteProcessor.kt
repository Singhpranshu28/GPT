package com.flownotes.recording

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.flownotes.FlowNotesApp
import com.flownotes.ai.LocalCleaner
import com.flownotes.ai.LocalLlm
import com.flownotes.ai.PolishedNote
import com.flownotes.ai.Polisher
import com.flownotes.ai.Transcriber
import com.flownotes.data.CleanupEngine
import com.flownotes.data.Note
import com.flownotes.data.NoteStatus
import com.flownotes.data.SttEngine
import com.flownotes.reminders.Reminders
import com.flownotes.stt.WhisperEngine
import java.io.File
import java.time.ZonedDateTime

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

        // 2. Organise: on-device AI, Claude, or basic rules. Any AI failure falls back to the basic rules,
        //    so a note is never lost.
        val now = ZonedDateTime.now()
        val basic = LocalCleaner.clean(note.rawTranscript)
        var hint: String? = null
        val polished: PolishedNote? = when (settings.cleanupEngine) {
            CleanupEngine.BASIC -> null
            CleanupEngine.ON_DEVICE ->
                if (!LocalLlm.isReady(app, settings.llmModel)) {
                    hint = "Download the free on-device AI in Settings to get organised notes and reminders."
                    null
                } else {
                    onStage("Organising your note…")
                    runCatching {
                        LocalLlm.organise(app, note.rawTranscript, settings, now) { tokens ->
                            onStage("Organising your note… (~${tokens * 3 / 4} words written)")
                        }
                    }.onFailure { hint = "On-device AI failed (${it.message}); used the basic clean-up." }.getOrNull()
                }
            CleanupEngine.CLAUDE ->
                if (settings.anthropicApiKey.isBlank()) {
                    hint = "Add your Anthropic API key in Settings, or switch to the free on-device AI."
                    null
                } else {
                    onStage("Polishing…")
                    runCatching { polisher.polish(note.rawTranscript, settings, now) }
                        .onFailure { hint = "Claude clean-up failed (${it.message}); used the basic clean-up." }
                        .getOrNull()
                }
        }

        note = if (polished != null && polished.body.isNotBlank()) {
            val reminderText = polished.reminderText.trim().takeIf { it.isNotEmpty() }
            note.copy(
                title = polished.title.trim().ifBlank { LocalCleaner.title(polished.body) },
                body = polished.body.trim(),
                tags = polished.tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct().take(3),
                style = settings.style.name,
                status = NoteStatus.READY,
                error = null,
                reminderText = reminderText,
                reminderAt = reminderText?.let { Reminders.parse(polished.reminderAt) },
                reminderDone = false,
            )
        } else {
            note.copy(
                title = LocalCleaner.title(basic),
                body = basic,
                style = settings.style.name,
                status = NoteStatus.READY,
                error = hint,
            )
        }
        Reminders.schedule(app, note)
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
