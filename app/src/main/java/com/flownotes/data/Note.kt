package com.flownotes.data

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val createdAt: Long,
    val title: String,
    /** Polished, "flow" text (markdown-ish: paragraphs, "- " bullets). */
    val body: String,
    /** What the speech-to-text model actually heard. */
    val rawTranscript: String = "",
    val tags: List<String> = emptyList(),
    val style: String = WritingStyle.NOTE.name,
    val durationMs: Long = 0,
    /** Kept so a failed or unsatisfying note can be re-processed. */
    val audioPath: String? = null,
    val status: NoteStatus = NoteStatus.READY,
    val error: String? = null,
    /** Google Doc this note is mirrored to, once synced. */
    val driveFileId: String? = null,
    /** The recording, uploaded to the "Flow Notes/Audio" Drive folder. */
    val driveAudioId: String? = null,
    /** Full JSON copy of this note in the app's hidden Drive storage, used for restore. */
    val driveMetaId: String? = null,
    /** [contentHash] at the time of the last successful Drive upload. */
    val driveSyncedHash: String? = null,
) {
    /** Everything that ends up on Drive; a change means the note has to be re-uploaded. */
    val contentHash: String
        get() = listOf(title, body, rawTranscript, tags.joinToString(","), style, durationMs.toString())
            .joinToString("\u0000").hashCode().toString()
}

@Serializable
enum class NoteStatus { PROCESSING, READY, FAILED }
