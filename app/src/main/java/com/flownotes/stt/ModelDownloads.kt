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
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A model file the app can download once and then use offline. */
interface DownloadableModel {
    val label: String
    val fileName: String
    val sizeMb: Int
    val description: String
    val url: String
}

/**
 * Downloads, stores and deletes model files in the app's private storage. Downloads resume where
 * they stopped (HTTP Range), which matters for the ~1 GB on-device AI model.
 */
object ModelDownloads {
    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** Keyed by [DownloadableModel.fileName]. */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun file(context: Context, model: DownloadableModel) = File(File(context.filesDir, "models"), model.fileName)

    fun isReady(context: Context, model: DownloadableModel) = file(context, model).exists()

    fun state(model: DownloadableModel): State = _states.value[model.fileName] ?: State.NotDownloaded

    fun refresh(context: Context, models: List<DownloadableModel>) = _states.update { current ->
        current + models.associate { m ->
            val s = current[m.fileName]
            m.fileName to when {
                s is State.Downloading -> s
                isReady(context, m) -> State.Ready
                s is State.Failed -> s
                else -> State.NotDownloaded
            }
        }
    }

    suspend fun download(context: Context, model: DownloadableModel) = withContext(Dispatchers.IO) {
        if (_states.value[model.fileName] is State.Downloading) return@withContext
        set(model, State.Downloading(0))
        val target = file(context, model)
        val part = File(target.parentFile, target.name + ".part")
        try {
            target.parentFile?.mkdirs()
            val already = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(model.url)
                .apply { if (already > 0) header("Range", "bytes=$already-") }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body ?: throw IOException("Empty download")
                val resumed = response.code == 206
                val start = if (resumed) already else 0L
                val total = (body.contentLength().takeIf { it > 0 }?.plus(start)) ?: (model.sizeMb * 1_048_576L)
                body.byteStream().use { input ->
                    FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var read = start
                        var lastPercent = -1
                        while (true) {
                            if (!coroutineContext.isActive) throw IOException("Paused")
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
            // Keep the partial file so the next attempt resumes instead of starting over.
            set(model, State.Failed((e.message ?: "Download failed") + ". Tap Download to resume."))
        }
    }

    fun delete(context: Context, model: DownloadableModel) {
        file(context, model).delete()
        File(file(context, model).parentFile, model.fileName + ".part").delete()
        set(model, State.NotDownloaded)
    }

    private fun set(model: DownloadableModel, state: State) = _states.update { it + (model.fileName to state) }
}
