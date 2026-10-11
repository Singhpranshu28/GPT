package com.flownotes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flownotes.ui.theme.FlowNotesTheme

sealed interface Screen {
    data object Notes : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Notes)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        route(intent)
        askForPermissions()
        setContent {
            FlowNotesTheme {
                BackHandler(enabled = screen != Screen.Notes) { screen = Screen.Notes }
                when (val s = screen) {
                    Screen.Notes -> NotesScreen(
                        onOpen = { screen = Screen.Detail(it) },
                        onSettings = { screen = Screen.Settings },
                        onRecord = { startRecording(this) },
                    )
                    is Screen.Detail -> NoteDetailScreen(s.id, onBack = { screen = Screen.Notes })
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Notes })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_NOTE_ID)?.let { screen = Screen.Detail(it) }
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) screen = Screen.Settings
    }

    private fun askForPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}

fun startRecording(context: Context) {
    context.startActivity(
        Intent(context, RecordActivity::class.java).setAction(RecordActivity.ACTION_RECORD),
    )
}

fun isDefaultAssistant(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

/** Opens the system screen where the user picks the "Digital assistant app". */
fun openAssistantSettings(context: Context) {
    val candidates = listOf(
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val launched = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (launched) return
    }
}
