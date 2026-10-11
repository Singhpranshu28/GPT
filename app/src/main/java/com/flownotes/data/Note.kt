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
    /** [contentHash] at the time of the last successful Drive upload. */
    val driveSyncedHash: String? = null,
) {
    val contentHash: String get() = (title + "\u0000" + body + "\u0000" + tags.joinToString(",")).hashCode().toString()
    val needsDriveSync: Boolean get() = status == NoteStatus.READY && body.isNotBlank() && driveSyncedHash != contentHash
}

@Serializable
enum class NoteStatus { PROCESSING, READY, FAILED }
