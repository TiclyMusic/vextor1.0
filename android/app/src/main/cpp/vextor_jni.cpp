// Ponte JNI minimale tra l'app Kotlin e llama.cpp (solo API C di llama.h).
//
// - un "Engine" per modello caricato (handle = puntatore passato a Kotlin)
// - riuso della KV cache: tra un messaggio e l'altro si ricalcola solo la
//   parte di prompt che cambia (prefisso comune)
// - i token vengono inviati a Kotlin come byte UTF-8 completi (le emoji a
//   4 byte non passano da NewStringUTF)

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "VextorNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int ERR_TEMPLATE = -1;
constexpr int ERR_TOO_LONG = -2;
constexpr int ERR_DECODE = -3;
constexpr int ERR_TOKENIZE = -4;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;  // token attualmente nella KV cache
    int n_ctx = 0;
    int n_batch = 512;
};

void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "llama.cpp", text);
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string jbytes_to_std(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

// Lunghezza del prefisso che termina con un carattere UTF-8 completo.
size_t utf8_complete_prefix(const std::string &s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // byte di continuazione
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

bool format_chat(const llama_model *model, const std::vector<std::string> &roles,
                 const std::vector<std::string> &contents, std::string &out) {
    std::vector<llama_chat_message> msgs(roles.size());
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); ++i) {
        msgs[i] = {roles[i].c_str(), contents[i].c_str()};
        total += roles[i].size() + contents[i].size();
    }
    const char *tmpl = llama_model_chat_template(model, nullptr);
    const char *candidates[] = {tmpl, "chatml"};
    for (const char *t : candidates) {
        if (!t) continue;
        std::vector<char> buf(total * 2 + 1024);
        int32_t n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(),
                                              static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(n) + 1);
            n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(),
                                          static_cast<int32_t>(buf.size()));
        }
        if (n > 0) {
            out.assign(buf.data(), static_cast<size_t>(n));
            return true;
        }
        LOGW("chat template non supportato, provo il successivo");
    }
    return false;
}

bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    const bool add_bos = llama_vocab_get_add_bos(vocab);
    int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0,
                                add_bos, true);
    if (n <= 0) return false;
    out.resize(static_cast<size_t>(n));
    n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), out.data(), n,
                       add_bos, true);
    if (n < 0) return false;
    out.resize(static_cast<size_t>(n));
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_vextor_app_llm_LlamaNative_init(JNIEnv *env, jobject, jstring jlib_dir) {
    llama_log_set(log_callback, nullptr);
    const std::string dir = jstring_to_std(env, jlib_dir);
    LOGI("Carico i backend ggml da %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_vextor_app_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_vextor_app_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx,
                                         jint n_threads) {
    const std::string path = jstring_to_std(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("impossibile caricare il modello %s", path.c_str());
        return 0;
    }
    const int train_ctx = llama_model_n_ctx_train(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(train_ctx > 0 ? std::min<int>(n_ctx, train_ctx) : n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("impossibile creare il contesto");
        llama_model_free(model);
        return 0;
    }
    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    e->n_batch = static_cast<int>(cp.n_batch);
    LOGI("modello caricato, n_ctx=%d threads=%d", e->n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_vextor_app_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT jint JNICALL
Java_com_vextor_app_llm_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    return e ? e->n_ctx : 0;
}

// Ritorna il numero di token generati (>= 0) oppure un codice di errore (< 0).
JNIEXPORT jint JNICALL
Java_com_vextor_app_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong handle,
                                             jobjectArray jroles, jobjectArray jcontents,
                                             jint max_tokens, jfloat temperature, jfloat top_p,
                                             jobject callback) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return ERR_DECODE;

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
    jmethodID on_progress = env->GetMethodID(cb_cls, "onPromptProgress", "(II)V");

    // 1. prompt formattato con il chat template del modello
    const jsize n_msg = env->GetArrayLength(jroles);
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < n_msg; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        roles.push_back(jstring_to_std(env, r));
        contents.push_back(jbytes_to_std(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::string prompt;
    if (!format_chat(e->model, roles, contents, prompt)) return ERR_TEMPLATE;

    std::vector<llama_token> tokens;
    if (!tokenize(e->vocab, prompt, tokens)) return ERR_TOKENIZE;
    const int n_prompt = static_cast<int>(tokens.size());
    if (n_prompt + 64 > e->n_ctx) return ERR_TOO_LONG;

    // 2. riuso della KV cache: tengo il prefisso comune con la richiesta precedente
    llama_memory_t mem = llama_get_memory(e->ctx);
    size_t n_keep = 0;
    while (n_keep < e->cached.size() && n_keep < tokens.size() &&
           e->cached[n_keep] == tokens[n_keep]) {
        ++n_keep;
    }
    if (n_keep == tokens.size()) --n_keep;  // serve almeno un token per avere i logits
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_keep), -1)) {
        llama_memory_clear(mem, true);
        n_keep = 0;
    }
    e->cached.resize(n_keep);
    LOGI("prompt: %d token, riusati dalla cache: %zu", n_prompt, n_keep);

    // 3. elaborazione del prompt a blocchi
    for (size_t i = n_keep; i < tokens.size(); i += static_cast<size_t>(e->n_batch)) {
        const int n = std::min<int>(e->n_batch, static_cast<int>(tokens.size() - i));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            llama_memory_clear(mem, true);
            e->cached.clear();
            return ERR_DECODE;
        }
        e->cached.insert(e->cached.end(), tokens.begin() + static_cast<long>(i),
                         tokens.begin() + static_cast<long>(i) + n);
        if (on_progress) env->CallVoidMethod(callback, on_progress, static_cast<jint>(i + n), n_prompt);
    }

    // 4. sampler
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(time(nullptr))));
    }

    // 5. generazione
    std::string pending;
    int generated = 0;
    char piece[256];
    bool keep_going = true;
    while (keep_going && generated < max_tokens &&
           static_cast<int>(e->cached.size()) < e->n_ctx - 1) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;

        const int n = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, static_cast<size_t>(n));

        const size_t ready = utf8_complete_prefix(pending);
        if (ready > 0) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(ready));
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(ready),
                                    reinterpret_cast<const jbyte *>(pending.data()));
            keep_going = env->CallBooleanMethod(callback, on_token, arr) == JNI_TRUE;
            env->DeleteLocalRef(arr);
            pending.erase(0, ready);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                keep_going = false;
            }
        }

        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("decode fallito durante la generazione");
            break;
        }
        e->cached.push_back(tok);
        ++generated;
    }
    llama_sampler_free(smpl);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_vextor_app_llm_LlamaNative_resetCache(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
}

}  // extern "C"
