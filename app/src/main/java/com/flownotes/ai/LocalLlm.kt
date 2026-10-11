package com.flownotes.ai

import android.content.Context
import com.flownotes.FlowNotesApp
import com.flownotes.data.Settings
import com.flownotes.llm.LlamaNative
import com.flownotes.stt.DownloadableModel
import com.flownotes.stt.ModelDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.ZonedDateTime

/** Small instruction-tuned models (Apache-2.0, no login needed to download) that run on a phone. */
enum class LlmModel(
    override val label: String,
    override val fileName: String,
    override val sizeMb: Int,
    override val description: String,
    private val repo: String,
) : DownloadableModel {
    QWEN_1_5B(
        "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1066,
        "Recommended: well organised notes and reliable reminders. Needs ~2 GB free RAM.",
        "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
    ),
    QWEN_0_5B(
        "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 380,
        "About 3x faster, for older phones. Simpler rewrites; may miss some reminders.",
        "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
    ),
    ;

    override val url get() = "https://huggingface.co/$repo/resolve/main/$fileName"
}

/**
 * Free, offline note organising: runs a small LLM on the phone with llama.cpp. Output is forced into
 * the note JSON shape with a grammar, so even a small model always returns something parseable.
 * The model stays loaded for a couple of minutes after use so back-to-back notes are quick.
 */
object LocalLlm {
    private val mutex = Mutex()
    private var handle = 0L
    private var loaded: LlmModel? = null
    private var unloadJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    private const val CONTEXT = 8192
    private const val IDLE_UNLOAD_MS = 120_000L

    fun isReady(context: Context, model: LlmModel) = ModelDownloads.isReady(context, model)

    /**
     * @param onProgress number of tokens written so far, for the overlay.
     */
    suspend fun organise(
        context: Context,
        transcript: String,
        settings: Settings,
        now: ZonedDateTime,
        onProgress: (Int) -> Unit = {},
    ): PolishedNote = withContext(Dispatchers.Default) {
        val model = settings.llmModel
        val file = ModelDownloads.file(context, model)
        if (!file.exists()) throw IOException("Download the on-device AI model in Settings first.")
        mutex.withLock {
            unloadJob?.cancel()
            if (handle == 0L || loaded != model) {
                if (handle != 0L) LlamaNative.free(handle)
                handle = LlamaNative.load(file.absolutePath, CONTEXT, threads())
                loaded = model.takeIf { handle != 0L }
                if (handle == 0L) throw IOException("Couldn't load the AI model; try downloading it again.")
            }
            try {
                // Rough budget: rewrites are about as long as the input, plus JSON overhead.
                val maxTokens = (transcript.length / 2 + 400).coerceIn(400, 3000)
                val bytes = LlamaNative.generate(
                    handle, Prompts.LOCAL_SYSTEM, Prompts.localUser(transcript, settings, now), Prompts.NOTE_GRAMMAR,
                    maxTokens,
                ) { count -> onProgress(count); true }
                json.decodeFromString(PolishedNote.serializer(), bytes.toString(Charsets.UTF_8))
            } catch (e: IllegalStateException) {
                throw IOException(e.message ?: "On-device AI failed", e)
            } finally {
                scheduleUnload()
            }
        }
    }

    private fun scheduleUnload() {
        unloadJob = FlowNotesApp.instance.scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                if (handle != 0L) LlamaNative.free(handle)
                handle = 0
                loaded = null
            }
        }
    }

    /** Frees memory now (e.g. the model file is being deleted). */
    suspend fun release() = mutex.withLock {
        unloadJob?.cancel()
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0
        loaded = null
    }

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
}
