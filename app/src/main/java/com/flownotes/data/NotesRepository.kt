package com.flownotes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Notes are small, so they live in a single JSON file. Writes go to a temp file and are
 * renamed into place so a crash mid-write never corrupts existing notes.
 */
class NotesRepository(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    suspend fun upsert(note: Note) = update { list ->
        val i = list.indexOfFirst { it.id == note.id }
        if (i >= 0) list.toMutableList().also { it[i] = note } else listOf(note) + list
    }

    suspend fun delete(id: String) = update { list ->
        list.firstOrNull { it.id == id }?.audioPath?.let { File(it).delete() }
        list.filterNot { it.id == id }
    }

    private suspend fun update(transform: (List<Note>) -> List<Note>) = mutex.withLock {
        val updated = transform(_notes.value).sortedByDescending { it.createdAt }
        _notes.value = updated
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "notes.json.tmp")
            tmp.writeText(json.encodeToString(updated))
            tmp.renameTo(file)
        }
    }

    private fun load(): List<Note> = runCatching {
        if (file.exists()) json.decodeFromString<List<Note>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())
}
