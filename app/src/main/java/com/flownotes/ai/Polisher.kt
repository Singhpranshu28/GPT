package com.flownotes.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.flownotes.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

@Serializable
data class PolishedNote(
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    /** What to be reminded of, if the speaker asked for a reminder; otherwise empty. */
    val reminderText: String = "",
    /** Local date-time "YYYY-MM-DDTHH:MM" for the reminder; empty if none or no time was given. */
    val reminderAt: String = "",
)

/** Turns a raw transcript into clean, intended text with Claude. */
class Polisher {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun polish(transcript: String, settings: Settings, now: ZonedDateTime): PolishedNote = withContext(Dispatchers.IO) {
        require(settings.anthropicApiKey.isNotBlank()) { "Add an Anthropic API key in Settings." }

        val params = MessageCreateParams.builder()
            .model(settings.claudeModel)
            .maxTokens(16_000L)
            .system(Prompts.SYSTEM)
            .addUserMessage(Prompts.user(transcript, settings, now))
            .outputConfig(
                OutputConfig.builder()
                    // Dictation cleanup is a light task; low effort keeps the wait short.
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            // If a safety classifier declines, let the API retry on a suitable fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = clientFor(settings.anthropicApiKey).messages().create(params)
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            error("Claude declined to rewrite this note; the raw transcript was kept.")
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        json.decodeFromString<PolishedNote>(text)
    }

    @Synchronized
    private fun clientFor(apiKey: String): AnthropicClient {
        if (client == null || clientKey != apiKey) {
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            clientKey = apiKey
        }
        return client!!
    }

    private companion object {
        val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "title" to mapOf("type" to "string"),
                        "body" to mapOf("type" to "string"),
                        "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "reminderText" to mapOf("type" to "string"),
                        "reminderAt" to mapOf("type" to "string"),
                    ),
                ),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("title", "body", "tags", "reminderText", "reminderAt")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }
}
