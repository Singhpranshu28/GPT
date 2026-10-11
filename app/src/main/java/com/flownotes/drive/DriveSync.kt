package com.flownotes.drive

import android.content.Context
import com.flownotes.FlowNotesApp
import com.flownotes.data.Note
import com.flownotes.data.NoteStatus
import com.flownotes.data.Settings
import com.flownotes.reminders.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Backs notes up to Google Drive and restores them on a new phone.
 *
 * For each note, Drive holds:
 *  - a Google Doc in "Flow Notes" (readable and searchable anywhere),
 *  - the recording in "Flow Notes/Audio" (optional),
 *  - a full JSON copy in the app's hidden app-data folder, which is what restore reads.
 */
object DriveSync {
    sealed interface Status {
        data object Off : Status
        data object Idle : Status
        data object Syncing : Status
        data class Restoring(val done: Int, val total: Int) : Status
        data class Synced(val at: Long, val restored: Int = 0) : Status
        /** Google wants the user to sign in again (access revoked, or new permissions needed). */
        data object NeedsSignIn : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val FOLDER_NAME = "Flow Notes"
    private const val AUDIO_FOLDER_NAME = "Audio"
    private const val META_SUFFIX = ".flownote.json"
    private const val PREFS = "drive"

    /** Watches notes and pushes changes a few seconds after they settle. */
    @OptIn(FlowPreview::class)
    fun start(app: FlowNotesApp, scope: CoroutineScope) {
        scope.launch {
            combine(app.notes.notes, app.settings.settings) { notes, s -> s to notes }
                .debounce(3_000)
                .collect { (settings, notes) ->
                    if (settings.googleAccount.isBlank()) {
                        _status.value = Status.Off
                    } else if (notes.any { needsSync(it, settings) }) {
                        syncAll()
                    } else if (_status.value == Status.Off) {
                        _status.value = Status.Idle
                    }
                }
        }
    }

    fun needsSync(note: Note, s: Settings): Boolean =
        note.status == NoteStatus.READY && note.body.isNotBlank() &&
            (note.driveSyncedHash != note.contentHash || note.driveMetaId == null || audioPending(note, s))

    private fun audioPending(note: Note, s: Settings) =
        s.driveBackupAudio && note.driveAudioId == null && note.audioPath?.let { File(it).exists() } == true

    suspend fun syncAll(): Boolean = guarded(Status.Syncing) { drive ->
        val app = FlowNotesApp.instance
        val settings = app.settings.current
        for (note in app.notes.notes.value.filter { needsSync(it, settings) }) {
            push(drive, note, settings)
        }
        Status.Synced(System.currentTimeMillis())
    }

    /**
     * Downloads every note backed up to this Google account that isn't on the phone yet,
     * including its recording. Notes already on the phone are left untouched.
     */
    suspend fun restore(context: Context): Boolean = guarded(Status.Restoring(0, 0)) { drive ->
        val app = FlowNotesApp.instance
        val local = app.notes.notes.value.map { it.id }.toSet()
        val remote = drive.listAppData().filter { it.name.endsWith(META_SUFFIX) }
        val missing = remote.filter { it.name.removeSuffix(META_SUFFIX) !in local }
        var restored = 0
        missing.forEachIndexed { i, file ->
            _status.value = Status.Restoring(i, missing.size)
            val note = runCatching { json.decodeFromString<Note>(drive.downloadText(file.id)) }.getOrNull()
                ?: return@forEachIndexed
            val audio = note.driveAudioId?.let { audioId ->
                val target = File(File(context.filesDir, "recordings"), "${note.createdAt}-${note.id.take(8)}.m4a")
                runCatching { drive.downloadTo(audioId, target); target.absolutePath }.getOrNull()
            }
            val restoredNote = note.copy(
                audioPath = audio,
                status = NoteStatus.READY,
                error = null,
                driveMetaId = file.id,
            )
            app.notes.upsert(restoredNote.copy(driveSyncedHash = restoredNote.contentHash))
            restored++
        }
        Reminders.rescheduleAll(context)
        Status.Synced(System.currentTimeMillis(), restored)
    }

    /**
     * Fetches a note's recording from Drive (e.g. its download failed during a restore).
     * Returns null on success, or a message to show.
     */
    suspend fun downloadAudio(context: Context, noteId: String): String? {
        val note = FlowNotesApp.instance.notes.get(noteId) ?: return "Note not found"
        val audioId = note.driveAudioId ?: return "This note has no recording on Drive"
        if (FlowNotesApp.instance.settings.current.googleAccount.isBlank()) return "Sign in with Google in Settings first"
        return try {
            val drive = client(context) ?: return "Sign in with Google again in Settings"
            val target = File(File(context.filesDir, "recordings"), "${note.createdAt}-${note.id.take(8)}.m4a")
            drive.downloadTo(audioId, target)
            FlowNotesApp.instance.notes.modify(noteId) { it.copy(audioPath = target.absolutePath) }
            null
        } catch (e: Exception) {
            e.message ?: "Download failed"
        }
    }

    /** First sign-in on a phone with no notes (e.g. a new phone): bring everything back, then sync. */
    suspend fun afterSignIn(context: Context) {
        if (FlowNotesApp.instance.notes.notes.value.isEmpty()) restore(context)
        syncAll()
    }

    /** Called when a note is deleted in the app: its Doc and audio go to the Drive trash. */
    fun onDeleted(note: Note, scope: CoroutineScope) {
        if (FlowNotesApp.instance.settings.current.googleAccount.isBlank()) return
        if (note.driveFileId == null && note.driveAudioId == null && note.driveMetaId == null) return
        scope.launch {
            val drive = runCatching { client(FlowNotesApp.instance) }.getOrNull() ?: return@launch
            note.driveFileId?.let { runCatching { drive.trash(it) } }
            note.driveAudioId?.let { runCatching { drive.trash(it) } }
            // Without its restore copy the note won't come back on a restore.
            note.driveMetaId?.let { runCatching { drive.delete(it) } }
        }
    }

    /**
     * Records the signed-in account. If it's a different account from the one notes were last
     * uploaded to, forget the old file ids so everything is uploaded fresh to the new Drive.
     */
    suspend fun onSignedIn(email: String) {
        val prefs = prefs()
        if (prefs.getString("account", null) != email) {
            prefs.edit().clear().putString("account", email).apply()
            FlowNotesApp.instance.notes.modifyAll {
                it.copy(driveFileId = null, driveAudioId = null, driveMetaId = null, driveSyncedHash = null)
            }
        }
        FlowNotesApp.instance.settings.update { it.copy(googleAccount = email) }
    }

    /** Stops syncing. Files already in Drive stay there; signing back in to the same account resumes. */
    fun signOut() {
        FlowNotesApp.instance.settings.update { it.copy(googleAccount = "") }
        _status.value = Status.Off
    }

    // --- internals ---

    /** Runs [block] with an authorized client, one Drive job at a time, mapping failures to [status]. */
    private suspend fun guarded(running: Status, block: suspend (DriveClient) -> Status): Boolean = mutex.withLock {
        if (FlowNotesApp.instance.settings.current.googleAccount.isBlank()) return@withLock false
        _status.value = running
        try {
            val drive = client(FlowNotesApp.instance) ?: return@withLock false
            _status.value = block(drive)
            true
        } catch (e: DriveException) {
            _status.value = if (e.code == 401 || e.code == 403) Status.NeedsSignIn else Status.Error(e.message.orEmpty())
            false
        } catch (e: Exception) {
            _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private suspend fun push(drive: DriveClient, note: Note, settings: Settings) {
        val hash = note.contentHash
        var updated = note

        // 1. Readable Google Doc
        if (note.driveSyncedHash != hash || note.driveFileId == null) {
            val name = note.title.ifBlank { "Voice note" }
            val text = render(note)
            val docId = note.driveFileId?.let { id ->
                try {
                    drive.updateDoc(id, name, text); id
                } catch (e: DriveException) {
                    if (e.code == 404) null else throw e // deleted on Drive: recreate below
                }
            } ?: inFolder(drive) { folder -> drive.createDoc(folder, name, text) }
            updated = updated.copy(driveFileId = docId)
        }

        // 2. Recording
        if (audioPending(note, settings)) {
            val file = File(note.audioPath!!)
            val audioId = inFolder(drive) { folder ->
                drive.uploadFile(audioFolderId(drive, folder), "${fileStamp(note)} ${note.title.ifBlank { "Voice note" }}.m4a", "audio/mp4", file)
            }
            updated = updated.copy(driveAudioId = audioId)
        }

        // 3. Restore copy (local-only fields stripped)
        val meta = json.encodeToString(updated.copy(audioPath = null, error = null, driveMetaId = null, driveSyncedHash = null))
        val metaId = note.driveMetaId?.let { id ->
            try {
                drive.updateContent(id, meta); id
            } catch (e: DriveException) {
                if (e.code == 404) null else throw e
            }
        } ?: drive.createAppDataJson(note.id + META_SUFFIX, meta)

        val docId = updated.driveFileId
        val audioId = updated.driveAudioId
        FlowNotesApp.instance.notes.modify(note.id) {
            it.copy(driveFileId = docId, driveAudioId = audioId, driveMetaId = metaId, driveSyncedHash = hash)
        }
    }

    private suspend fun client(context: Context): DriveClient? =
        when (val auth = GoogleAuth.authorize(context)) {
            is GoogleAuth.Result.Authorized -> DriveClient(auth.accessToken)
            is GoogleAuth.Result.NeedsConsent -> {
                _status.value = Status.NeedsSignIn
                null
            }
        }

    /** Runs [action] with the "Flow Notes" folder id, recovering once if the cached folder was deleted. */
    private suspend fun <T> inFolder(drive: DriveClient, action: suspend (String) -> T): T = try {
        action(folderId(drive))
    } catch (e: DriveException) {
        if (e.code != 404) throw e
        prefs().edit().remove("folderId").remove("audioFolderId").apply()
        action(folderId(drive))
    }

    private fun prefs() = FlowNotesApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private suspend fun folderId(drive: DriveClient): String {
        val prefs = prefs()
        prefs.getString("folderId", null)?.let { return it }
        return drive.findOrCreateFolder(FOLDER_NAME).also { prefs.edit().putString("folderId", it).apply() }
    }

    private suspend fun audioFolderId(drive: DriveClient, parent: String): String {
        val prefs = prefs()
        prefs.getString("audioFolderId", null)?.let { return it }
        return drive.findOrCreateFolder(AUDIO_FOLDER_NAME, parent).also { prefs.edit().putString("audioFolderId", it).apply() }
    }

    private fun fileStamp(note: Note): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(Date(note.createdAt))

    private fun render(note: Note): String = buildString {
        appendLine(note.title)
        appendLine()
        appendLine(note.body)
        appendLine()
        appendLine("—")
        append(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(note.createdAt)))
        if (note.tags.isNotEmpty()) append("  ·  " + note.tags.joinToString(" ") { "#$it" })
        appendLine()
        note.reminderText?.let { text ->
            appendLine("⏰ Reminder: $text" + (note.reminderAt?.let { " · " + Reminders.format(it) } ?: ""))
        }
    }
}
