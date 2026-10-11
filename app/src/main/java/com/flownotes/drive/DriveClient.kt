package com.flownotes.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException(message)

/** Minimal Drive v3 REST client: just what's needed to back up and restore notes. */
class DriveClient(private val token: String, private val http: OkHttpClient = defaultHttp) {

    data class RemoteFile(val id: String, val name: String)

    suspend fun findOrCreateFolder(name: String, parentId: String? = null): String = withContext(Dispatchers.IO) {
        var q = "mimeType='$FOLDER_MIME' and name='${name.replace("'", "\\'")}' and trashed=false"
        if (parentId != null) q += " and '$parentId' in parents"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val found = json(Request.Builder().url(url).get())["files"]?.jsonArray?.firstOrNull()
        found?.jsonObject?.get("id")?.jsonPrimitive?.content ?: run {
            val meta = buildJsonObject {
                put("name", name)
                put("mimeType", FOLDER_MIME)
                if (parentId != null) put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
            }
            json(Request.Builder().url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON)))["id"]!!
                .jsonPrimitive.content
        }
    }

    /** Creates a Google Doc converted from plain text. Returns the new file id. */
    suspend fun createDoc(folderId: String, name: String, text: String): String =
        createSmall(name, folderId, DOC_MIME, text.toRequestBody(TEXT))

    /** Replaces the doc's name and content. */
    suspend fun updateDoc(fileId: String, name: String, text: String) = withContext(Dispatchers.IO) {
        val meta = buildJsonObject { put("name", name) }
        json(Request.Builder().url("$UPLOAD/files/$fileId?uploadType=multipart&fields=id").patch(multipart(meta, text.toRequestBody(TEXT))))
        Unit
    }

    /** Creates a JSON file in the hidden app-data folder. */
    suspend fun createAppDataJson(name: String, content: String): String =
        createSmall(name, APP_DATA, null, content.toRequestBody(JSON))

    suspend fun updateContent(fileId: String, content: String) = withContext(Dispatchers.IO) {
        json(Request.Builder().url("$UPLOAD/files/$fileId?uploadType=media&fields=id").patch(content.toRequestBody(JSON)))
        Unit
    }

    /**
     * Uploads a (possibly large) file with a resumable session, so recordings longer than the
     * 5 MB multipart limit work too. Returns the new file id.
     */
    suspend fun uploadFile(folderId: String, name: String, mime: String, file: File): String = withContext(Dispatchers.IO) {
        val meta = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive(folderId)) })
        }
        val session = Request.Builder()
            .url("$UPLOAD/files?uploadType=resumable&fields=id")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(JSON))
        val location = execute(session).use { it.header("Location") }
            ?: throw IOException("Drive didn't return an upload URL")
        json(Request.Builder().url(location).put(file.asRequestBody(mime.toMediaType())))["id"]!!.jsonPrimitive.content
    }

    /** Lists every file in the hidden app-data folder. */
    suspend fun listAppData(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", APP_DATA)
                .addQueryParameter("fields", "nextPageToken,files(id,name)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = json(Request.Builder().url(url).get())
            page["files"]?.jsonArray?.forEach { f ->
                val o = f.jsonObject
                files += RemoteFile(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content)
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        files
    }

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$API/files/$fileId?alt=media").get()).use { it.body!!.string() }
    }

    suspend fun downloadTo(fileId: String, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        execute(Request.Builder().url("$API/files/$fileId?alt=media").get()).use { response ->
            tmp.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        tmp.renameTo(target)
        Unit
    }

    /** Moves the file to the Drive trash (recoverable for 30 days). */
    suspend fun trash(fileId: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("trashed", true) }.toString().toRequestBody(JSON)
        json(Request.Builder().url("$API/files/$fileId?fields=id").patch(body))
        Unit
    }

    /** Permanently deletes a file (app-data files can't be trashed). */
    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$API/files/$fileId").delete()).close()
    }

    private suspend fun createSmall(name: String, parent: String, mime: String?, media: RequestBody): String =
        withContext(Dispatchers.IO) {
            val meta = buildJsonObject {
                put("name", name)
                if (mime != null) put("mimeType", mime)
                put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
            }
            json(Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id").post(multipart(meta, media)))["id"]!!
                .jsonPrimitive.content
        }

    private fun multipart(meta: JsonObject, media: RequestBody) = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(meta.toString().toRequestBody(JSON))
        .addPart(media)
        .build()

    private fun json(builder: Request.Builder): JsonObject = execute(builder).use { response ->
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
    }

    /** Runs the request with auth; throws [DriveException] on any non-2xx response. */
    private fun execute(builder: Request.Builder): Response {
        val response = http.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
        if (!response.isSuccessful) {
            val text = response.use { it.body?.string().orEmpty() }
            throw DriveException(response.code, "Drive error ${response.code}: ${text.take(300)}")
        }
        return response
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DOC_MIME = "application/vnd.google-apps.document"
        private const val APP_DATA = "appDataFolder"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val TEXT = "text/plain; charset=utf-8".toMediaType()
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
