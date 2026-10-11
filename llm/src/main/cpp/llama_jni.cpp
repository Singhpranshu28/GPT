// JNI bridge between Kotlin (com.flownotes.llm.LlamaNative) and llama.cpp.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "FlowNotesLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
};

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

std::once_flag backend_once;

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_flownotes_llm_LlamaNative_load(JNIEnv *env, jobject, jstring model_path, jint n_ctx, jint threads) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    std::string path = to_string(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGI("failed to load %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto *session = new Session{model, ctx};
    LOGI("loaded %s (ctx %d, %d threads)", path.c_str(), n_ctx, threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_flownotes_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session) return;
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

/**
 * Runs one chat turn (system + user) through the model's own chat template and returns the reply as
 * UTF-8 bytes. If grammar is non-empty, output is constrained to it (GBNF), which guarantees valid
 * JSON from a small model. Returns null on failure; throws IllegalStateException with a reason.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_flownotes_llm_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring system_prompt, jstring user_prompt, jstring grammar,
        jint max_tokens, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    auto fail = [&](const char *msg) -> jbyteArray {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg);
        return nullptr;
    };
    if (!s) return fail("Model not loaded");

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    std::string sys = to_string(env, system_prompt);
    std::string usr = to_string(env, user_prompt);
    std::string gbnf = to_string(env, grammar);

    // 1. Chat template
    llama_chat_message msgs[2] = {{"system", sys.c_str()}, {"user", usr.c_str()}};
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(sys.size() * 2 + usr.size() * 2 + 512);
    int32_t len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return fail("This model has no supported chat template");
    std::string prompt(buf.data(), len);

    // 2. Tokenize
    int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_prompt, true, true) < 0) {
        return fail("Tokenization failed");
    }
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (n_prompt + 16 >= n_ctx) return fail("This note is too long for the on-device model");
    const int32_t budget = std::min(static_cast<int32_t>(max_tokens), n_ctx - n_prompt - 1);

    // 3. Prompt processing, in batches
    llama_memory_clear(llama_get_memory(s->ctx), true);
    for (int32_t i = 0; i < n_prompt; i += 512) {
        int32_t n = std::min(512, n_prompt - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return fail("Decoding the prompt failed");
    }

    // 4. Sampling: grammar constraint, then greedy (faithful, deterministic rewriting)
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!gbnf.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
        if (!g) {
            llama_sampler_free(smpl);
            return fail("Invalid output grammar");
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    jmethodID on_token = nullptr;
    if (listener) on_token = env->GetMethodID(env->GetObjectClass(listener), "onToken", "(I)Z");

    std::string out;
    char piece[256];
    for (int32_t i = 0; i < budget; ++i) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int32_t n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        if (on_token && !env->CallBooleanMethod(listener, on_token, i + 1)) break; // cancelled
        llama_token next = tok;
        if (llama_decode(s->ctx, llama_batch_get_one(&next, 1)) != 0) break;
    }
    llama_sampler_free(smpl);
    return to_bytes(env, out);
}

} // extern "C"
