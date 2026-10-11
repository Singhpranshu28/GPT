package com.flownotes.drive

import android.content.Context
import com.flownotes.FlowNotesApp
import com.flownotes.data.Note
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
import java.text.DateFormat
import java.util.Date

/**
 * Mirrors every finished note to Google Drive as a Google Doc inside a "Flow Notes" folder.
 * Notes stay on the phone either way; Drive is a backup you can read and search anywhere.
 */
object DriveSync {
    sealed interface Status {
        data object Off : Status
        data object Idle : Status
        data object Syncing : Status
        data class Synced(val at: Long) : Status
        /** Google wants the user to sign in again (e.g. access was revoked). */
        data object NeedsSignIn : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()
    private const val FOLDER_NAME = "Flow Notes"

    /** Watches notes and pushes changes a few seconds after they settle. */
    @OptIn(FlowPreview::class)
    fun start(app: FlowNotesApp, scope: CoroutineScope) {
        scope.launch {
            combine(app.notes.notes, app.settings.settings) { notes, s -> s.googleAccount to notes }
                .debounce(3_000)
                .collect { (account, notes) ->
                    if (account.isBlank()) {
                        _status.value = Status.Off
                    } else if (notes.any { it.needsDriveSync }) {
                        syncAll(app)
                    } else if (_status.value == Status.Off) {
                        _status.value = Status.Idle
                    }
                }
        }
    }

    suspend fun syncAll(context: Context) = mutex.withLock {
        val app = FlowNotesApp.instance
        if (app.settings.current.googleAccount.isBlank()) return@withLock
        _status.value = Status.Syncing
        try {
            val drive = client(context) ?: return@withLock
            for (note in app.notes.notes.value.filter { it.needsDriveSync }) {
                push(drive, note)
            }
            _status.value = Status.Synced(System.currentTimeMillis())
        } catch (e: DriveException) {
            _status.value = if (e.code == 401 || e.code == 403) Status.NeedsSignIn else Status.Error(e.message.orEmpty())
        } catch (e: Exception) {
            _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Called when a note is deleted in the app: its Doc goes to the Drive trash. */
    fun onDeleted(note: Note, scope: CoroutineScope) {
        val fileId = note.driveFileId ?: return
        if (FlowNotesApp.instance.settings.current.googleAccount.isBlank()) return
        scope.launch {
            runCatching { client(FlowNotesApp.instance)?.trash(fileId) }
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
            FlowNotesApp.instance.notes.modifyAll { it.copy(driveFileId = null, driveSyncedHash = null) }
        }
        FlowNotesApp.instance.settings.update { it.copy(googleAccount = email) }
    }

    /** Stops syncing. Docs already in Drive stay there; signing back in to the same account resumes. */
    fun signOut() {
        FlowNotesApp.instance.settings.update { it.copy(googleAccount = "") }
        _status.value = Status.Off
    }

    private suspend fun push(drive: DriveClient, note: Note) {
        val name = note.title.ifBlank { "Voice note" }
        val text = render(note)
        val hash = note.contentHash
        val fileId = note.driveFileId
        val newId = if (fileId == null) {
            create(drive, name, text)
        } else {
            try {
                drive.updateDoc(fileId, name, text); fileId
            } catch (e: DriveException) {
                // Deleted on Drive: recreate it.
                if (e.code == 404) create(drive, name, text) else throw e
            }
        }
        FlowNotesApp.instance.notes.modify(note.id) { it.copy(driveFileId = newId, driveSyncedHash = hash) }
    }

    private suspend fun client(context: Context): DriveClient? =
        when (val auth = GoogleAuth.authorize(context)) {
            is GoogleAuth.Result.Authorized -> DriveClient(auth.accessToken)
            is GoogleAuth.Result.NeedsConsent -> {
                _status.value = Status.NeedsSignIn
                null
            }
        }

    private suspend fun create(drive: DriveClient, name: String, text: String): String = try {
        drive.createDoc(folderId(drive), name, text)
    } catch (e: DriveException) {
        if (e.code != 404) throw e
        // The cached folder was deleted on Drive; find or make a new one.
        prefs().edit().remove("folderId").apply()
        drive.createDoc(folderId(drive), name, text)
    }

    private fun prefs() = FlowNotesApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private suspend fun folderId(drive: DriveClient): String {
        val prefs = prefs()
        prefs.getString("folderId", null)?.let { return it }
        return drive.findOrCreateFolder(FOLDER_NAME).also { prefs.edit().putString("folderId", it).apply() }
    }

    private fun render(note: Note): String = buildString {
        appendLine(note.title)
        appendLine()
        appendLine(note.body)
        appendLine()
        appendLine("—")
        append(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(note.createdAt)))
        if (note.tags.isNotEmpty()) append("  ·  " + note.tags.joinToString(" ") { "#$it" })
        appendLine()
    }

    private const val PREFS = "drive"
}
