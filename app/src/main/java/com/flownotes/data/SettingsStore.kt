package com.flownotes.data

import android.content.Context
import com.flownotes.ai.LlmModel
import com.flownotes.stt.ModelDownloads
import com.flownotes.stt.WhisperModel
import com.flownotes.stt.WhisperModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How aggressively the raw transcript is rewritten (mirrors Wispr Flow's "Auto Cleanup" levels). */
enum class CleanupLevel(val label: String, val description: String) {
    LIGHT("Light", "Remove fillers, fix punctuation. Your exact wording otherwise."),
    MEDIUM("Medium", "Also apply self-corrections, drop false starts, format lists."),
    HIGH("High", "Tighten into clear, well-structured writing. Same meaning, fewer words."),
}

/** Output shape, similar to Wispr Flow's per-context styles. */
enum class WritingStyle(val label: String, val description: String) {
    NOTE("Note", "Paragraphs and bullets, easy to scan later"),
    BULLETS("Bullets", "Everything as a concise bullet list"),
    MESSAGE("Message", "Casual, ready to paste into a chat"),
    EMAIL("Email", "Greeting, clear body, sign-off"),
}

/** Where speech is turned into text. */
enum class SttEngine(val label: String, val description: String) {
    ON_DEVICE("On this phone (free)", "Whisper runs offline on your phone. No API key, no cost, audio never leaves the device."),
    CLOUD("Cloud API", "OpenAI, Groq or any OpenAI-compatible API. Faster on slow phones; needs an API key."),
}

/** How the raw transcript becomes an organised note. */
enum class CleanupEngine(val label: String, val description: String) {
    ON_DEVICE(
        "On-device AI (free)",
        "A small AI model runs on your phone: organised notes, titles, tags and reminders. Offline, no cost.",
    ),
    CLAUDE("Claude (paid)", "Best quality rewrite through the Anthropic API. Needs an API key; paid per use."),
    BASIC("Basic rules (free, instant)", "Removes fillers and stutters only. No reorganising, no reminders."),
}

data class Settings(
    val sttEngine: SttEngine = SttEngine.ON_DEVICE,
    val whisperModel: WhisperModel = WhisperModel.BASE,
    val cleanupEngine: CleanupEngine = CleanupEngine.ON_DEVICE,
    val llmModel: LlmModel = LlmModel.QWEN_1_5B,
    val anthropicApiKey: String = "",
    val claudeModel: String = DEFAULT_CLAUDE_MODEL,
    val sttBaseUrl: String = OPENAI_BASE_URL,
    val sttApiKey: String = "",
    val sttModel: String = OPENAI_STT_MODEL,
    /** Optional ISO-639-1 hint ("en", "hi", ...). Blank = auto-detect. */
    val language: String = "",
    val cleanup: CleanupLevel = CleanupLevel.MEDIUM,
    val style: WritingStyle = WritingStyle.NOTE,
    /** Names, jargon and brands the models should spell exactly like this. */
    val dictionary: List<String> = emptyList(),
    /** Stop automatically after this many seconds of silence. 0 = only stop manually. */
    val autoStopSilenceSec: Int = 0,
    /** Put the finished text on the clipboard so it can be pasted anywhere right away. */
    val copyToClipboard: Boolean = true,
    /** Google account notes are mirrored to (as Google Docs in a "Flow Notes" Drive folder). Blank = off. */
    val googleAccount: String = "",
    /** Also upload the original recordings (about 0.5 MB per minute). */
    val driveBackupAudio: Boolean = true,
) {

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val OPENAI_STT_MODEL = "gpt-4o-transcribe"
        const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
        const val GROQ_STT_MODEL = "whisper-large-v3-turbo"
    }
}

/** What still has to be set up before the user can dictate, or null when ready. */
fun Settings.setupProblem(context: Context): String? = when (sttEngine) {
    SttEngine.ON_DEVICE ->
        if (WhisperModels.isReady(context, whisperModel)) null
        else "Download the free ${whisperModel.label} speech model (${whisperModel.sizeMb} MB) once, then dictate offline."
    SttEngine.CLOUD ->
        if (sttApiKey.isNotBlank()) null else "Add your speech-to-text API key, or switch to the free on-device engine."
}

/** Optional extra setup that unlocks organised notes and reminders; null when nothing is missing. */
fun Settings.organiseHint(context: Context): String? = when (cleanupEngine) {
    CleanupEngine.ON_DEVICE ->
        if (ModelDownloads.isReady(context, llmModel)) null
        else "Download the free on-device AI (${llmModel.sizeMb} MB, Wi-Fi recommended) to get organised notes and reminders."
    CleanupEngine.CLAUDE ->
        if (anthropicApiKey.isNotBlank()) null else "Add your Anthropic API key, or switch to the free on-device AI."
    CleanupEngine.BASIC -> null
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("sttEngine", s.sttEngine.name)
            .putString("whisperModel", s.whisperModel.name)
            .putString("cleanupEngine", s.cleanupEngine.name)
            .putString("llmModel", s.llmModel.name)
            .putString("anthropicApiKey", s.anthropicApiKey.trim())
            .putString("claudeModel", s.claudeModel.trim())
            .putString("sttBaseUrl", s.sttBaseUrl.trim().trimEnd('/'))
            .putString("sttApiKey", s.sttApiKey.trim())
            .putString("sttModel", s.sttModel.trim())
            .putString("language", s.language.trim())
            .putString("cleanup", s.cleanup.name)
            .putString("style", s.style.name)
            .putString("dictionary", s.dictionary.joinToString("\n"))
            .putInt("autoStopSilenceSec", s.autoStopSilenceSec)
            .putBoolean("copyToClipboard", s.copyToClipboard)
            .putString("googleAccount", s.googleAccount)
            .putBoolean("driveBackupAudio", s.driveBackupAudio)
            .apply()
        _settings.value = s
    }

    private fun read(): Settings {
        val d = Settings()
        val sttKey = prefs.getString("sttApiKey", d.sttApiKey)!!
        val anthropicKey = prefs.getString("anthropicApiKey", d.anthropicApiKey)!!
        return Settings(
            // Existing installs that already use a cloud key keep it; new installs default to on-device.
            sttEngine = enumOr(prefs.getString("sttEngine", null), if (sttKey.isNotBlank()) SttEngine.CLOUD else SttEngine.ON_DEVICE),
            whisperModel = enumOr(prefs.getString("whisperModel", null), d.whisperModel),
            // Existing installs with an Anthropic key keep Claude; new installs use the free on-device AI.
            cleanupEngine = enumOr(
                prefs.getString("cleanupEngine", null),
                if (anthropicKey.isNotBlank()) CleanupEngine.CLAUDE else CleanupEngine.ON_DEVICE,
            ),
            llmModel = enumOr(prefs.getString("llmModel", null), d.llmModel),
            anthropicApiKey = prefs.getString("anthropicApiKey", d.anthropicApiKey)!!,
            claudeModel = prefs.getString("claudeModel", d.claudeModel)!!.ifBlank { d.claudeModel },
            sttBaseUrl = prefs.getString("sttBaseUrl", d.sttBaseUrl)!!.ifBlank { d.sttBaseUrl },
            sttApiKey = prefs.getString("sttApiKey", d.sttApiKey)!!,
            sttModel = prefs.getString("sttModel", d.sttModel)!!.ifBlank { d.sttModel },
            language = prefs.getString("language", d.language)!!,
            cleanup = enumOr(prefs.getString("cleanup", null), d.cleanup),
            style = enumOr(prefs.getString("style", null), d.style),
            dictionary = prefs.getString("dictionary", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() },
            autoStopSilenceSec = prefs.getInt("autoStopSilenceSec", d.autoStopSilenceSec),
            copyToClipboard = prefs.getBoolean("copyToClipboard", d.copyToClipboard),
            googleAccount = prefs.getString("googleAccount", d.googleAccount)!!,
            driveBackupAudio = prefs.getBoolean("driveBackupAudio", d.driveBackupAudio),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
