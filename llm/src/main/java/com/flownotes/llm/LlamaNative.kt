package com.flownotes.llm

/** Thin JNI surface over llama.cpp (see src/main/cpp/llama_jni.cpp). */
object LlamaNative {
    init {
        System.loadLibrary("flownotes_llama")
    }

    fun interface TokenListener {
        /** Called after each generated token. Return false to stop generating. */
        fun onToken(count: Int): Boolean
    }

    external fun load(modelPath: String, contextSize: Int, threads: Int): Long
    external fun free(handle: Long)

    /** @throws IllegalStateException with a user-readable reason. */
    external fun generate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        grammar: String,
        maxTokens: Int,
        listener: TokenListener?,
    ): ByteArray
}
