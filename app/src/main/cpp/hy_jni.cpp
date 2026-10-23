#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
struct Engine {
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    Clock::time_point deadline;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::once_flag backend;
bool expired(void* opaque) { return Clock::now() >= static_cast<Engine*>(opaque)->deadline; }
std::string bytes(JNIEnv* env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Missing input");
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data()));
    if (result.find('\0') != std::string::npos) throw std::invalid_argument("Embedded NUL");
    return result;
}
void fail(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jd_rc_localtranslate_inference_HyNative_create(JNIEnv* env, jobject, jbyteArray path) {
    try {
        std::call_once(backend, [] {
            llama_log_set([](ggml_log_level, const char*, void*) {}, nullptr);
            llama_backend_init();
        });
        auto engine = std::make_unique<Engine>();
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        engine->model = llama_model_load_from_file(bytes(env, path).c_str(), params);
        if (!engine->model) throw std::runtime_error("Model load failed");
        auto context = llama_context_default_params();
        context.n_ctx = 1024;
        context.n_batch = 256;
        context.n_ubatch = 128;
        context.n_threads = 2;
        context.n_threads_batch = 2;
        context.abort_callback = expired;
        context.abort_callback_data = engine.get();
        engine->deadline = Clock::now() + std::chrono::seconds(60);
        engine->context = llama_init_from_model(engine->model, context);
        if (!engine->context) throw std::runtime_error("Context load failed");
        return reinterpret_cast<jlong>(engine.release());
    } catch (...) { fail(env, "Hy-MT2 initialization failed"); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jd_rc_localtranslate_inference_HyNative_setThreads(JNIEnv* env, jobject, jlong handle, jint generation, jint prefill) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine || !engine->context) throw std::runtime_error("Closed model");
        if (generation < 1 || generation > 4 || prefill < 1 || prefill > 6)
            throw std::invalid_argument("Invalid thread budget");
        // The translator mutex serializes configuration, generation and release.
        llama_set_n_threads(engine->context, generation, prefill);
    } catch (...) { fail(env, "Hy-MT2 thread configuration failed"); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jd_rc_localtranslate_inference_HyNative_generate(JNIEnv* env, jobject, jlong handle, jbyteArray input, jbyteArray targetInput) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine) throw std::runtime_error("Closed model");
        const auto text = bytes(env, input);
        if (text.size() > 4096 || text.find("<|") != std::string::npos || text.find("<｜") != std::string::npos)
            throw std::invalid_argument("Input exceeds limits or contains control markers");
        const auto target = bytes(env, targetInput);
        const std::string language = target == "zh" ? "简体中文" : target == "ja" ? "日语" : target == "en" ? "英语" :
            target == "ko" ? "韩语" : target == "yue" ? "粤语" : "";
        if (language.empty()) throw std::runtime_error("Unsupported target language");
        const auto instruction = std::string("将以下文本翻译为") + language + "，注意只需要输出翻译后的结果，不要额外解释：\n" + text;
        // Exact single-user template embedded in the fixed official GGUF. The older
        // built-in hunyuan-dense formatter places the user marker after the content.
        const std::string prompt = "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + instruction + "<｜hy_Assistant｜>";
        const auto* vocab = llama_model_get_vocab(engine->model);
        std::vector<llama_token> tokens(768);
        int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), static_cast<int>(tokens.size()), false, true);
        if (count <= 0 || count > 768) throw std::runtime_error("Translation input too long");
        llama_memory_clear(llama_get_memory(engine->context), true);
        engine->deadline = Clock::now() + std::chrono::seconds(60);
        for (int start = 0; start < count; start += 256) {
            const int batch_size = std::min(256, count - start);
            if (llama_decode(engine->context, llama_batch_get_one(tokens.data() + start, batch_size)) != 0)
                throw std::runtime_error("Prompt decode failed");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string output;
        bool ended = false;
        for (int generated = 0; generated < 256; ++generated) {
            if (expired(engine)) throw std::runtime_error("Translation deadline exceeded");
            auto token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(128);
            int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (length < 0) throw std::runtime_error("Invalid token output");
            output.append(piece.data(), length);
            if (llama_decode(engine->context, llama_batch_get_one(&token, 1)) != 0) throw std::runtime_error("Generation failed");
        }
        if (!ended || output.empty()) throw std::runtime_error("Translation did not finish within token limit");
        auto array = env->NewByteArray(static_cast<jsize>(output.size()));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
        return array;
    } catch (...) { fail(env, "Hy-MT2 translation failed or exceeded its limits"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jd_rc_localtranslate_inference_HyNative_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
