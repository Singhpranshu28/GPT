package com.flownotes.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flownotes.FlowNotesApp
import com.flownotes.data.CleanupLevel
import com.flownotes.data.SttEngine
import com.flownotes.data.Settings
import com.flownotes.data.WritingStyle
import com.flownotes.drive.DriveSync
import com.flownotes.drive.GoogleAuth
import com.flownotes.stt.WhisperModel
import com.flownotes.stt.WhisperModels
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = FlowNotesApp.instance.settings
    val s by store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun set(t: (Settings) -> Settings) = store.update(t)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Side button")
            Text(
                "Make Flow Notes your Digital assistant app, then long-press the side/power button from anywhere " +
                    "(even the lock screen) to start a note. Long-press again, press a volume key, or tap ✓ to finish.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { openAssistantSettings(context) }) {
                Text(if (isDefaultAssistant(context)) "Flow Notes is your assistant ✓" else "Set as digital assistant")
            }

            HorizontalDivider()
            GoogleDriveSection(s.googleAccount, s.driveBackupAudio)

            HorizontalDivider()
            Section("Speech to text")
            SttEngine.entries.forEach { engine ->
                Choice(engine.label, engine.description, s.sttEngine == engine) { set { it.copy(sttEngine = engine) } }
            }
            if (s.sttEngine == SttEngine.ON_DEVICE) {
                OnDeviceModels(s.whisperModel) { m -> set { it.copy(whisperModel = m) } }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.sttBaseUrl == Settings.OPENAI_BASE_URL,
                        onClick = { set { it.copy(sttBaseUrl = Settings.OPENAI_BASE_URL, sttModel = Settings.OPENAI_STT_MODEL) } },
                        label = { Text("OpenAI") },
                    )
                    FilterChip(
                        selected = s.sttBaseUrl == Settings.GROQ_BASE_URL,
                        onClick = { set { it.copy(sttBaseUrl = Settings.GROQ_BASE_URL, sttModel = Settings.GROQ_STT_MODEL) } },
                        label = { Text("Groq") },
                    )
                }
                SecretField("Transcription API key", s.sttApiKey) { v -> set { it.copy(sttApiKey = v) } }
                TextSetting("Base URL", s.sttBaseUrl) { v -> set { it.copy(sttBaseUrl = v) } }
                TextSetting("Model", s.sttModel) { v -> set { it.copy(sttModel = v) } }
            }
            TextSetting("Language (optional, e.g. en, hi)", s.language) { v -> set { it.copy(language = v) } }
            Text(
                "Leave blank to auto-detect. Setting it (e.g. \"hi\" for Hindi) is faster and more accurate on-device.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Clean-up with Claude (optional)")
            Text(
                "Without a key, notes get a free basic clean-up on the phone: fillers, stutters and " +
                    "\"scratch that\" are removed and spoken \"new paragraph\" / \"bullet point\" are applied. " +
                    "Claude adds the full Wispr-style rewrite (self-corrections, lists, titles, tags) and is paid per use.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecretField("Anthropic API key", s.anthropicApiKey) { v -> set { it.copy(anthropicApiKey = v) } }
            TextSetting("Claude model", s.claudeModel) { v -> set { it.copy(claudeModel = v) } }

            Text("Clean-up level", fontWeight = FontWeight.Medium)
            CleanupLevel.entries.forEach { level ->
                Choice(level.label, level.description, s.cleanup == level) { set { it.copy(cleanup = level) } }
            }
            Text("Writing style", fontWeight = FontWeight.Medium)
            WritingStyle.entries.forEach { style ->
                Choice(style.label, style.description, s.style == style) { set { it.copy(style = style) } }
            }

            HorizontalDivider()
            Section("Personal dictionary")
            Text(
                "Names, brands and jargon to always spell correctly. One per line.",
                style = MaterialTheme.typography.bodySmall,
            )
            var dict by rememberSaveable { mutableStateOf(s.dictionary.joinToString("\n")) }
            OutlinedTextField(
                value = dict,
                onValueChange = { v ->
                    dict = v
                    set { it.copy(dictionary = v.lines().map(String::trim).filter(String::isNotEmpty)) }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("Pranshu\nJist\nKubernetes") },
            )

            HorizontalDivider()
            Section("Recording")
            Text(
                if (s.autoStopSilenceSec == 0) "Auto-stop on silence: off (stop manually)"
                else "Auto-stop after ${s.autoStopSilenceSec}s of silence",
            )
            Slider(
                value = s.autoStopSilenceSec.toFloat(),
                onValueChange = { v -> set { it.copy(autoStopSilenceSec = v.roundToInt()) } },
                valueRange = 0f..15f,
                steps = 14,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Copy to clipboard when done")
                    Text("Paste the note anywhere right after you speak.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.copyToClipboard, onCheckedChange = { v -> set { it.copy(copyToClipboard = v) } })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TextSetting(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoogleDriveSection(account: String, backupAudio: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by DriveSync.status.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    fun signedIn(email: String?) {
        scope.launch {
            DriveSync.onSignedIn(email ?: "Google account")
            DriveSync.afterSignIn(context)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        runCatching { GoogleAuth.fromConsentIntent(context, res.data) }
            .onSuccess { if (it is GoogleAuth.Result.Authorized) signedIn(it.email) }
            .onFailure { error = it.message }
    }

    fun signIn() {
        error = null
        scope.launch {
            runCatching { GoogleAuth.authorize(context) }
                .onSuccess { r ->
                    when (r) {
                        is GoogleAuth.Result.Authorized -> signedIn(r.email)
                        is GoogleAuth.Result.NeedsConsent ->
                            consent.launch(IntentSenderRequest.Builder(r.pendingIntent.intentSender).build())
                    }
                }
                .onFailure { error = "Google sign-in failed: ${it.message}" }
        }
    }

    Section("Google Drive backup")
    if (account.isBlank()) {
        Text(
            "Sign in with Google to save every note as a Google Doc in a \"Flow Notes\" folder in your Drive, " +
                "with its recording, and to restore your notes on a new phone. " +
                "The app can only see files it creates, nothing else in your Drive.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = ::signIn) { Text("Sign in with Google") }
    } else {
        val busy = status == DriveSync.Status.Syncing || status is DriveSync.Status.Restoring
        Text("Signed in as $account", fontWeight = FontWeight.Medium)
        Text(
            when (val st = status) {
                DriveSync.Status.Off, DriveSync.Status.Idle -> "Notes sync to Drive automatically."
                DriveSync.Status.Syncing -> "Syncing…"
                is DriveSync.Status.Restoring ->
                    if (st.total == 0) "Looking for notes on Drive…" else "Restoring ${st.done + 1} of ${st.total}…"
                is DriveSync.Status.Synced ->
                    (if (st.restored > 0) "Restored ${st.restored} notes · " else "") +
                        "Up to date · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.at))
                DriveSync.Status.NeedsSignIn -> "Google needs you to sign in again."
                is DriveSync.Status.Error -> "Last sync failed: ${st.message}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status is DriveSync.Status.Error || status == DriveSync.Status.NeedsSignIn) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Back up recordings")
                Text("Audio goes to Flow Notes/Audio, about 0.5 MB per minute.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = backupAudio,
                onCheckedChange = { v -> FlowNotesApp.instance.settings.update { it.copy(driveBackupAudio = v) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == DriveSync.Status.NeedsSignIn) {
                Button(onClick = ::signIn) { Text("Sign in again") }
            } else {
                OutlinedButton(enabled = !busy, onClick = { scope.launch { DriveSync.syncAll() } }) { Text("Sync now") }
                OutlinedButton(enabled = !busy, onClick = { scope.launch { DriveSync.restore(context) } }) {
                    Text("Restore")
                }
            }
            TextButton(onClick = { DriveSync.signOut() }) { Text("Sign out") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun OnDeviceModels(selected: WhisperModel, onSelect: (WhisperModel) -> Unit) {
    val context = LocalContext.current
    val states by WhisperModels.states.collectAsStateWithLifecycle()

    Text("Speech model", fontWeight = FontWeight.Medium)
    WhisperModel.entries.forEach { model ->
        val state = states[model] ?: WhisperModels.State.NotDownloaded
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == model, onClick = { onSelect(model) })
            Column(Modifier.weight(1f)) {
                Text("${model.label} · ${model.sizeMb} MB")
                Text(
                    when (state) {
                        is WhisperModels.State.Downloading -> "Downloading… ${state.percent}%"
                        is WhisperModels.State.Failed -> state.message
                        WhisperModels.State.Ready -> model.description + " · downloaded"
                        WhisperModels.State.NotDownloaded -> model.description
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is WhisperModels.State.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (state is WhisperModels.State.Downloading) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            when (state) {
                WhisperModels.State.Ready ->
                    TextButton(onClick = { WhisperModels.delete(context, model) }) { Text("Delete") }
                is WhisperModels.State.Downloading -> Unit
                else -> TextButton(onClick = {
                    onSelect(model)
                    // App scope: the download keeps going if you leave Settings.
                    FlowNotesApp.instance.scope.launch { WhisperModels.download(context.applicationContext, model) }
                }) { Text("Download") }
            }
        }
    }
    Text(
        "Models download once over the internet, then everything runs offline. Use Wi-Fi for Small.",
        style = MaterialTheme.typography.bodySmall,
    )
}
