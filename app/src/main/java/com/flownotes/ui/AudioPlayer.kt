package com.flownotes.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private val SPEEDS = listOf(1f, 1.5f, 2f)

/** Compact player for a note's recording: play/pause, seek, and playback speed. */
@Composable
fun AudioPlayer(path: String, fallbackDurationMs: Long, modifier: Modifier = Modifier) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableLongStateOf(0L) }
    var total by remember(path) { mutableLongStateOf(fallbackDurationMs) }
    var dragging by remember(path) { mutableStateOf<Float?>(null) }
    var speed by remember(path) { mutableFloatStateOf(1f) }
    var error by remember(path) { mutableStateOf<String?>(null) }

    fun applySpeed(mp: MediaPlayer) {
        // Setting params on a paused player starts playback on some Android versions, so only do it while playing.
        if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(speed)
    }

    fun ensurePlayer(): MediaPlayer? = player ?: runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(path)
            prepare() // local file: fast enough to do synchronously
            setOnCompletionListener {
                playing = false
                position = 0L
                it.seekTo(0)
            }
            total = duration.toLong().takeIf { it > 0 } ?: fallbackDurationMs
        }
    }.onFailure { error = "Can't play this recording" }.getOrNull()?.also { player = it }

    fun toggle() {
        val mp = ensurePlayer() ?: return
        if (mp.isPlaying) {
            mp.pause()
            playing = false
        } else {
            mp.start()
            applySpeed(mp)
            playing = true
        }
    }

    // Track progress while playing.
    LaunchedEffect(playing, player) {
        val mp = player ?: return@LaunchedEffect
        while (playing) {
            if (dragging == null) position = mp.currentPosition.toLong()
            delay(100)
        }
    }

    // Pause when the app goes to the background; release when leaving the screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, path) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && player?.isPlaying == true) {
                player?.pause()
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.release()
            player = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = ::toggle, enabled = error == null) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play recording",
                )
            }
            val shown = dragging?.toLong() ?: position
            Text(
                formatDuration(shown),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Slider(
                value = shown.toFloat().coerceIn(0f, total.coerceAtLeast(1L).toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val target = dragging?.toLong() ?: 0L
                    ensurePlayer()?.seekTo(target.toInt())
                    position = target
                    dragging = null
                },
                valueRange = 0f..total.coerceAtLeast(1L).toFloat(),
                enabled = error == null,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatDuration(total), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = {
                speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
                player?.let(::applySpeed)
            }) {
                Text(if (speed == 1f) "1×" else "${speed}×")
            }
        }
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
