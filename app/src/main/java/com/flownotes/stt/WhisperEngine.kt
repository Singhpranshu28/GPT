package com.flownotes.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Free, offline speech-to-text: runs a Whisper model on the phone's CPU via whisper.cpp.
 * The loaded model is kept in memory between notes so back-to-back dictations start fast.
 */
object WhisperEngine {
    private val mutex = Mutex()
    private var loaded: WhisperModel? = null
    private var context: Long = 0

    suspend fun transcribe(
        appContext: Context,
        audio: File,
        model: WhisperModel,
        language: String,
        dictionary: List<String>,
        onProgress: (Int) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val modelFile = WhisperModels.file(appContext, model)
        if (!modelFile.exists()) {
            throw IOException("Download the ${model.label} speech model in Settings first.")
        }
        val pcm = AudioDecoder.decodeToWhisperPcm(audio)
        mutex.withLock {
            if (loaded != model || context == 0L) {
                if (context != 0L) WhisperNative.free(context)
                context = WhisperNative.init(modelFile.absolutePath)
                loaded = model.takeIf { context != 0L }
                if (context == 0L) throw IOException("Couldn't load the speech model; try downloading it again.")
            }
            val prompt = dictionary.takeIf { it.isNotEmpty() }?.joinToString(", ")
            val bytes = WhisperNative.transcribe(
                context, pcm, language.ifBlank { null }, prompt, threads(),
                WhisperNative.ProgressListener { onProgress(it) },
            ) ?: throw IOException("On-device transcription failed")
            bytes.toString(Charsets.UTF_8).trim()
        }
    }

    /** Frees the model's memory (e.g. when its file is deleted). */
    fun release(model: WhisperModel? = null) {
        if (!mutex.tryLock()) return // busy transcribing; it'll be replaced on next load
        try {
            if (context != 0L && (model == null || model == loaded)) {
                WhisperNative.free(context)
                context = 0
                loaded = null
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Big cores do the work; using every core (incl. little ones) is usually slower. */
    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
}
