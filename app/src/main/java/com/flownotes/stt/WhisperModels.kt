package com.flownotes.stt

import android.content.Context

/** Whisper models that run on a phone. All multilingual (Hindi, Hinglish, English, ...). */
enum class WhisperModel(
    override val label: String,
    override val fileName: String,
    override val sizeMb: Int,
    override val description: String,
) : DownloadableModel {
    TINY("Tiny", "ggml-tiny-q5_1.bin", 31, "Fastest, rougher accuracy"),
    BASE("Base", "ggml-base-q5_1.bin", 57, "Good balance for most phones"),
    SMALL("Small", "ggml-small-q5_1.bin", 181, "Most accurate, especially for Hindi; 3-4x slower than Base"),
    ;

    override val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperModels {
    fun file(context: Context, model: WhisperModel) = ModelDownloads.file(context, model)
    fun isReady(context: Context, model: WhisperModel) = ModelDownloads.isReady(context, model)

    fun delete(context: Context, model: WhisperModel) {
        WhisperEngine.release(model)
        ModelDownloads.delete(context, model)
    }
}
