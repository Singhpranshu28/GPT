package com.flownotes.ai

import com.flownotes.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text through any OpenAI-compatible `/audio/transcriptions` endpoint
 * (OpenAI gpt-4o-transcribe / whisper-1, Groq whisper-large-v3-turbo, a self-hosted Whisper, ...).
 */
class Transcriber(private val http: OkHttpClient = defaultClient) {

    suspend fun transcribe(audio: File, settings: Settings): String = withContext(Dispatchers.IO) {
        require(settings.sttApiKey.isNotBlank()) { "Add a speech-to-text API key in Settings." }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.sttModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            .apply {
                if (settings.language.isNotBlank()) addFormDataPart("language", settings.language)
                // The prompt biases spelling of names/jargon, like Wispr Flow's personal dictionary.
                if (settings.dictionary.isNotEmpty()) {
                    addFormDataPart("prompt", "Vocabulary: " + settings.dictionary.joinToString(", "))
                }
            }
            .build()

        val request = Request.Builder()
            .url(settings.sttBaseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer ${settings.sttApiKey}")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Transcription failed (${response.code}): ${text.take(300)}")
            }
            Json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
