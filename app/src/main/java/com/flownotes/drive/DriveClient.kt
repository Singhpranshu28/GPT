package com.flownotes.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class DriveException(val code: Int, message: String) : IOException(message)

/** Minimal Drive v3 REST client: just what's needed to mirror notes as Google Docs. */
class DriveClient(private val token: String, private val http: OkHttpClient = OkHttpClient()) {

    suspend fun findOrCreateFolder(name: String): String = withContext(Dispatchers.IO) {
        val q = "mimeType='$FOLDER_MIME' and name='${name.replace("'", "\\'")}' and trashed=false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val found = call(Request.Builder().url(url).get())["files"]?.jsonArray?.firstOrNull()
        found?.jsonObject?.get("id")?.jsonPrimitive?.content ?: run {
            val meta = buildJsonObject { put("name", name); put("mimeType", FOLDER_MIME) }
            val body = meta.toString().toRequestBody(JSON)
            call(Request.Builder().url("$API/files?fields=id").post(body))["id"]!!.jsonPrimitive.content
        }
    }

    /** Creates a Google Doc from plain text. Returns the new file id. */
    suspend fun createDoc(folderId: String, name: String, text: String): String = withContext(Dispatchers.IO) {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", DOC_MIME)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(folderId)) })
        }
        call(Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id").post(multipart(meta, text)))["id"]!!
            .jsonPrimitive.content
    }

    /** Replaces the doc's name and content. */
    suspend fun updateDoc(fileId: String, name: String, text: String) = withContext(Dispatchers.IO) {
        val meta = buildJsonObject { put("name", name) }
        call(Request.Builder().url("$UPLOAD/files/$fileId?uploadType=multipart&fields=id").patch(multipart(meta, text)))
        Unit
    }

    /** Moves the file to the Drive trash (recoverable for 30 days). */
    suspend fun trash(fileId: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("trashed", true) }.toString().toRequestBody(JSON)
        call(Request.Builder().url("$API/files/$fileId?fields=id").patch(body))
        Unit
    }

    private fun multipart(meta: JsonObject, text: String) = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(meta.toString().toRequestBody(JSON))
        .addPart(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        .build()

    private fun call(builder: Request.Builder): JsonObject {
        val request = builder.header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveException(response.code, "Drive error ${response.code}: ${text.take(300)}")
            return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DOC_MIME = "application/vnd.google-apps.document"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
