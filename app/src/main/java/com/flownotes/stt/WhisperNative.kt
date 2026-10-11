package com.flownotes.stt

/** Thin JNI surface over whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
internal object WhisperNative {
    init {
        System.loadLibrary("flownotes_whisper")
    }

    fun interface ProgressListener {
        /** 0..100 */
        fun onProgress(percent: Int)
    }

    external fun init(modelPath: String): Long
    external fun free(context: Long)
    external fun transcribe(
        context: Long,
        samples: FloatArray,
        language: String?,
        prompt: String?,
        threads: Int,
        listener: ProgressListener?,
    ): ByteArray?
}
