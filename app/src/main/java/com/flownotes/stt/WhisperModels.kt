package com.flownotes.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Whisper models that run on a phone. All multilingual (Hindi, Hinglish, English, ...). */
enum class WhisperModel(val label: String, val fileName: String, val sizeMb: Int, val description: String) {
    TINY("Tiny", "ggml-tiny-q5_1.bin", 31, "Fastest, rougher accuracy"),
    BASE("Base", "ggml-base-q5_1.bin", 57, "Good balance for most phones"),
    SMALL("Small", "ggml-small-q5_1.bin", 181, "Most accurate, especially for Hindi; 3-4x slower than Base"),
    ;

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/** Downloads, stores and deletes Whisper model files (kept in the app's private storage). */
object WhisperModels {
    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<WhisperModel, State>>(emptyMap())
    val states: StateFlow<Map<WhisperModel, State>> = _states.asStateFlow()
    private val http = OkHttpClient()

    fun file(context: Context, model: WhisperModel) = File(File(context.filesDir, "models"), model.fileName)

    fun isReady(context: Context, model: WhisperModel) = file(context, model).exists()

    fun refresh(context: Context) {
        _states.update { current ->
            WhisperModel.entries.associateWith { m ->
                val s = current[m]
                when {
                    s is State.Downloading -> s
                    isReady(context, m) -> State.Ready
                    s is State.Failed -> s
                    else -> State.NotDownloaded
                }
            }
        }
    }

    suspend fun download(context: Context, model: WhisperModel) = withContext(Dispatchers.IO) {
        if (_states.value[model] is State.Downloading) return@withContext
        set(model, State.Downloading(0))
        val target = file(context, model)
        val part = File(target.parentFile, target.name + ".part")
        try {
            target.parentFile?.mkdirs()
            http.newCall(Request.Builder().url(model.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body ?: throw IOException("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: (model.sizeMb * 1_048_576L)
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            if (!coroutineContext.isActive) throw IOException("Cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val percent = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                set(model, State.Downloading(percent))
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Couldn't save the model")
            set(model, State.Ready)
        } catch (e: Exception) {
            part.delete()
            set(model, State.Failed(e.message ?: "Download failed"))
        }
    }

    fun delete(context: Context, model: WhisperModel) {
        WhisperEngine.release(model)
        file(context, model).delete()
        set(model, State.NotDownloaded)
    }

    private fun set(model: WhisperModel, state: State) = _states.update { it + (model to state) }
}
