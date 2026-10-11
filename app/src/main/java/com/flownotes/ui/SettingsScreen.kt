package com.flownotes.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.flownotes.data.Settings
import com.flownotes.data.WritingStyle
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
            Section("Speech to text")
            Text("Any OpenAI-compatible transcription API.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.sttBaseUrl == Settings.OPENAI_BASE_URL,
                    onClick = { set { it.copy(sttBaseUrl = Settings.OPENAI_BASE_URL, sttModel = Settings.OPENAI_STT_MODEL) } },
                    label = { Text("OpenAI") },
                )
                FilterChip(
                    selected = s.sttBaseUrl == Settings.GROQ_BASE_URL,
                    onClick = { set { it.copy(sttBaseUrl = Settings.GROQ_BASE_URL, sttModel = Settings.GROQ_STT_MODEL) } },
                    label = { Text("Groq (fast, cheap)") },
                )
            }
            SecretField("Transcription API key", s.sttApiKey) { v -> set { it.copy(sttApiKey = v) } }
            TextSetting("Base URL", s.sttBaseUrl) { v -> set { it.copy(sttBaseUrl = v) } }
            TextSetting("Model", s.sttModel) { v -> set { it.copy(sttModel = v) } }
            TextSetting("Language (optional, e.g. en, hi)", s.language) { v -> set { it.copy(language = v) } }

            HorizontalDivider()
            Section("Clean-up with Claude")
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
