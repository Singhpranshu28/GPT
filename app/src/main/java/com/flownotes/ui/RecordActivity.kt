package com.flownotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flownotes.FlowNotesApp
import com.flownotes.data.setupProblem
import com.flownotes.recording.NoteProcessor
import com.flownotes.recording.RecordingBus
import com.flownotes.recording.RecordingService
import com.flownotes.recording.RecordingState
import com.flownotes.ui.theme.FlowNotesTheme
import kotlinx.coroutines.delay

/**
 * Translucent overlay shown over whatever app (or the lock screen) the user is in.
 * Opened by: side-button long press (assistant), Quick Settings tile, launcher shortcut, or the in-app mic.
 *
 * While recording: press the side button again, press either volume key, or tap ✓ to stop and save.
 */
class RecordActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else {
            Toast.makeText(this, "Microphone permission is needed to record notes", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            FlowNotesTheme {
                RecordOverlay(
                    onStop = ::stopRecording,
                    onCancel = {
                        RecordingService.send(this, RecordingService.ACTION_CANCEL)
                        finish()
                    },
                    onOpen = { id ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_NOTE_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                    onSettings = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val isRecordRequest = intent?.action in RECORD_ACTIONS
        val state = RecordingBus.state.value
        when {
            // Long-press the side button again while talking = stop and save.
            state is RecordingState.Recording -> if (isRecordRequest) stopRecording()
            isRecordRequest -> startRecording()
            state is RecordingState.Idle -> finish()
        }
    }

    private fun startRecording() {
        val problem = FlowNotesApp.instance.settings.current.setupProblem(this)
        if (problem != null) {
            // The overlay shows a "Set up" prompt instead.
            RecordingBus.setNeedsSetup(problem)
            return
        }
        RecordingBus.clearNeedsSetup()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (RecordingBus.state.value !is RecordingState.Processing) RecordingBus.set(RecordingState.Idle)
        RecordingService.send(this, RecordingService.ACTION_START)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) RecordingBus.clearNeedsSetup()
    }

    private fun stopRecording() {
        RecordingService.send(this, RecordingService.ACTION_STOP)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            RecordingBus.state.value is RecordingState.Recording
        ) {
            stopRecording()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val ACTION_RECORD = "com.flownotes.RECORD"
        private val RECORD_ACTIONS = setOf(
            ACTION_RECORD,
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND,
            "android.intent.action.VOICE_ASSIST",
        )
    }
}

@Composable
private fun RecordOverlay(
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by RecordingBus.state.collectAsStateWithLifecycle()
    val setupProblem by RecordingBus.needsSetup.collectAsStateWithLifecycle()
    val dismissible = state !is RecordingState.Recording && state !is RecordingState.Processing

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (dismissible) onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    setupProblem != null -> SetupPrompt(setupProblem!!, onSettings)
                    else -> when (val s = state) {
                        is RecordingState.Recording -> Recording(s, onStop, onCancel)
                        is RecordingState.Processing -> Processing(s.stage)
                        is RecordingState.Done -> Done(s, onOpen, onDismiss)
                        is RecordingState.Failed -> Failed(s, onOpen, onDismiss)
                        RecordingState.Idle -> Processing("Starting…")
                    }
                }
            }
        }
    }
}

@Composable
private fun Recording(state: RecordingState.Recording, onStop: () -> Unit, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis(); delay(250)
        }
    }
    val elapsed = ((now - state.startedAt) / 1000).coerceAtLeast(0)
    val levels = remember { mutableStateListOf<Float>().apply { repeat(32) { add(0f) } } }
    LaunchedEffect(state.level) {
        levels.removeAt(0); levels.add(state.level)
    }

    Text("Listening…", style = MaterialTheme.typography.titleMedium)
    Text(
        "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Waveform(levels)
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        IconButton(onClick = onCancel, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Discard")
        }
        FilledIconButton(onClick = onStop, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Default.Check, contentDescription = "Stop and save", modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.size(56.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Tap ✓, press a volume key, or long-press the side button to finish",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Waveform(levels: List<Float>) {
    Row(
        Modifier.height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        levels.forEach { level ->
            val h by animateFloatAsState(targetValue = 6f + 50f * level, label = "bar")
            Box(
                Modifier
                    .width(5.dp)
                    .height(h.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun Processing(stage: String) {
    Text(stage, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(
        "You can close this, the note will still be saved.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Done(state: RecordingState.Done, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val copied = FlowNotesApp.instance.settings.current.copyToClipboard
    Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        state.preview,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDismiss) { Text("Done") }
        if (!copied) {
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = { NoteProcessor.copy(context, state.preview) }) { Text("Copy") }
        }
        Button(onClick = { onOpen(state.noteId) }) { Text("Open note") }
    }
    if (copied) {
        Text(
            "Saved and copied to clipboard",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Failed(state: RecordingState.Failed, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    Text("Couldn't finish this note", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDismiss) { Text("Close") }
        state.noteId?.let { id -> Button(onClick = { onOpen(id) }) { Text("Open & retry") } }
    }
}

@Composable
private fun SetupPrompt(problem: String, onSettings: () -> Unit) {
    Text("Almost ready", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(problem, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onSettings) { Text("Open settings") }
}
