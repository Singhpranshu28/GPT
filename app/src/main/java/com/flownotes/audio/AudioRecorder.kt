package com.flownotes.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records mono AAC/M4A at speech quality (~64 kbps, about 0.5 MB per minute), well under the
 * 25 MB upload limit of the transcription APIs even for long dictations.
 */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(output: File) {
        output.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(output.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = output
    }

    /** Peak amplitude since the last call, 0..32767. */
    fun maxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Returns the recorded file, or null if nothing usable was captured. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess // throws if stopped right after start
        r.release()
        return file?.takeIf { ok && it.length() > 0 }
    }

    fun cancel() {
        stop()?.delete()
        file = null
    }
}
