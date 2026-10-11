// JNI bridge between Kotlin (com.flownotes.stt.WhisperNative) and whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "FlowNotesWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct ProgressSink {
    JNIEnv *env;
    jobject listener;   // com.flownotes.stt.WhisperNative.ProgressListener (may be null)
    jmethodID method;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *sink = static_cast<ProgressSink *>(user_data);
    if (sink && sink->listener) sink->env->CallVoidMethod(sink->listener, sink->method, progress);
}

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_flownotes_stt_WhisperNative_init(JNIEnv *env, jobject, jstring model_path) {
    std::string path = to_string(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    LOGI("loaded model %s: %s", path.c_str(), ctx ? "ok" : "FAILED");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_flownotes_stt_WhisperNative_free(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

/**
 * Transcribes 16 kHz mono float PCM. Returns UTF-8 bytes, or null if whisper failed.
 * (Bytes rather than a jstring: NewStringUTF aborts on 4-byte UTF-8 such as emoji.)
 * language: ISO-639-1 code, or null/empty for auto-detect. prompt: optional spelling hints.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_flownotes_stt_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray samples, jstring language,
        jstring prompt, jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (!ctx) return nullptr;

    std::string lang = to_string(env, language);
    std::string initial_prompt = to_string(env, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    if (!initial_prompt.empty()) params.initial_prompt = initial_prompt.c_str();

    ProgressSink sink{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        sink.method = env->GetMethodID(cls, "onProgress", "(I)V");
        params.progress_callback = on_progress;
        params.progress_callback_user_data = &sink;
    }

    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

} // extern "C"
