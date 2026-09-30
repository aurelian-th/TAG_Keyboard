//
// QwenTextGenerator JNI bridge
// Provides text-generation inference using Qwen GGUF models through llama.cpp.
// Unlike the existing LanguageModel (which uses SentencePiece for next-word prediction),
// this uses llama.cpp's built-in BPE tokenizer and runs autoregressive text generation.
//

#define LOG_TAG "LatinIME: jni: QwenTextGenerator"

#include "jni.h"
#include "jni_common.h"
#include "src/ggml/llama.h"
#include "defines.h"

#include <cstring>
#include <string>
#include <vector>

#define QWEN_LOG_TAG "QwenTextGenerator"

// ---- Per-model state ----
struct QwenState {
    llama_model *model;
    llama_context *context;
    int n_ctx;
};

namespace latinime {

// ---- JNI: openQwenNative ----
static jlong QwenTextGenerator_open(JNIEnv *env, jobject, jstring jModelPath, jint contextSize) {
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    if (!modelPath) {
        AKLOGE("QwenTextGenerator: modelPath is null");
        return 0;
    }

    AKLOGI("QwenTextGenerator: Loading model from %s with ctx=%d", modelPath, contextSize);

    llama_model_params model_params = llama_model_default_params();
    // Use mmap for fast loading
    model_params.use_mmap = true;

    llama_model *model = llama_load_model_from_file(modelPath, model_params);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        AKLOGE("QwenTextGenerator: Failed to load model");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_threads = 2;       // Use 2 threads for generation on mobile
    ctx_params.n_threads_batch = 2;

    llama_context *ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        AKLOGE("QwenTextGenerator: Failed to create context");
        llama_free_model(model);
        return 0;
    }

    auto *state = new QwenState();
    state->model = model;
    state->context = ctx;
    state->n_ctx = contextSize;

    AKLOGI("QwenTextGenerator: Model loaded successfully, vocab_size=%d", llama_n_vocab(model));
    return reinterpret_cast<jlong>(state);
}

// ---- JNI: closeQwenNative ----
static void QwenTextGenerator_close(JNIEnv *, jobject, jlong handle) {
    auto *state = reinterpret_cast<QwenState *>(handle);
    if (!state) return;

    if (state->context) llama_free(state->context);
    if (state->model) llama_free_model(state->model);
    delete state;
    AKLOGI("QwenTextGenerator: Model closed");
}

// ---- Helper: decode a single token to a string ----
static std::string token_to_piece(const llama_model *model, llama_token token) {
    char buf[128];
    int n = llama_token_to_piece(model, token, buf, sizeof(buf));
    if (n < 0) {
        // Buffer too small — allocate dynamically
        std::vector<char> large_buf(-n);
        int n2 = llama_token_to_piece(model, token, large_buf.data(), large_buf.size());
        if (n2 < 0) return "";
        return std::string(large_buf.data(), n2);
    }
    return std::string(buf, n);
}

// ---- JNI: generateTextNative ----
static jstring QwenTextGenerator_generate(
        JNIEnv *env, jobject,
        jlong handle,
        jstring jPrompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jfloat repetitionPenalty) {

    auto *state = reinterpret_cast<QwenState *>(handle);
    if (!state || !state->model || !state->context) {
        AKLOGE("QwenTextGenerator: Invalid state");
        return env->NewStringUTF("");
    }

    const char *prompt = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt) {
        AKLOGE("QwenTextGenerator: prompt is null");
        return env->NewStringUTF("");
    }

    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Tokenize the prompt using llama.cpp's built-in tokenizer (works with Qwen2 BPE)
    std::vector<llama_token> tokens(state->n_ctx);
    int n_tokens = llama_tokenize(state->model, promptStr.c_str(), promptStr.size(),
                                   tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        AKLOGE("QwenTextGenerator: Tokenization failed (needed %d tokens)", -n_tokens);
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);
    AKLOGI("QwenTextGenerator: Prompt tokenized to %d tokens", n_tokens);

    // Reset the KV cache for a fresh generation
    llama_kv_cache_clear(state->context);

    // Evaluate prompt tokens in batch
    if (llama_eval(state->context, tokens.data(), n_tokens, 0) != 0) {
        AKLOGE("QwenTextGenerator: Prompt evaluation failed");
        return env->NewStringUTF("");
    }

    // Autoregressive generation loop
    std::string output;
    int n_past = n_tokens;
    llama_token eos_token = llama_token_eos(state->model);
    int vocab_size = llama_n_vocab(state->model);

    // Keep track of recent tokens for repetition penalty
    std::vector<llama_token> last_tokens;
    // Seed the last_tokens with prompt tokens for repetition penalty context
    for (int i = std::max(0, n_tokens - 64); i < n_tokens; i++) {
        last_tokens.push_back(tokens[i]);
    }

    for (int i = 0; i < maxTokens; i++) {
        // Get logits
        float *logits = llama_get_logits(state->context);

        // Build token data array for sampling
        std::vector<llama_token_data> candidates;
        candidates.reserve(vocab_size);
        for (int j = 0; j < vocab_size; j++) {
            candidates.push_back({j, logits[j], 0.0f});
        }
        llama_token_data_array candidates_p = {
            candidates.data(), (size_t)vocab_size, false
        };

        // Apply repetition penalty
        if (repetitionPenalty > 1.0f && !last_tokens.empty()) {
            llama_sample_repetition_penalties(
                state->context, &candidates_p,
                last_tokens.data(), last_tokens.size(),
                repetitionPenalty, // penalty_repeat
                0.0f,              // penalty_freq
                0.0f               // penalty_present
            );
        }

        llama_token new_token;

        if (temperature <= 0.01f) {
            // Greedy sampling
            new_token = llama_sample_token_greedy(state->context, &candidates_p);
        } else {
            // Temperature + top-p (nucleus) sampling
            llama_sample_top_k(state->context, &candidates_p, 40, 1);
            llama_sample_top_p(state->context, &candidates_p, topP, 1);
            llama_sample_temp(state->context, &candidates_p, temperature);
            new_token = llama_sample_token(state->context, &candidates_p);
        }

        // Check for EOS or special stop tokens
        if (new_token == eos_token) {
            AKLOGI("QwenTextGenerator: Hit EOS token at step %d", i);
            break;
        }

        // Decode token to text
        std::string piece = token_to_piece(state->model, new_token);

        // Check for ChatML end marker <|im_end|>
        if (output.size() + piece.size() >= 10) {
            std::string check = output + piece;
            if (check.find("<|im_end|>") != std::string::npos) {
                // Remove the end marker from output
                size_t pos = check.find("<|im_end|>");
                output = check.substr(0, pos);
                AKLOGI("QwenTextGenerator: Hit <|im_end|> at step %d", i);
                break;
            }
        }

        output += piece;
        last_tokens.push_back(new_token);

        // Evaluate the new token
        if (llama_eval(state->context, &new_token, 1, n_past) != 0) {
            AKLOGE("QwenTextGenerator: Eval failed at step %d", i);
            break;
        }
        n_past++;

        // Safety: don't exceed context
        if (n_past >= state->n_ctx - 1) {
            AKLOGI("QwenTextGenerator: Context limit reached at step %d", i);
            break;
        }
    }

    AKLOGI("QwenTextGenerator: Generated %d chars of text", (int)output.size());
    return env->NewStringUTF(output.c_str());
}

// ---- JNI registration ----
static const JNINativeMethod sQwenMethods[] = {
    {
        const_cast<char *>("openQwenNative"),
        const_cast<char *>("(Ljava/lang/String;I)J"),
        reinterpret_cast<void *>(QwenTextGenerator_open)
    },
    {
        const_cast<char *>("closeQwenNative"),
        const_cast<char *>("(J)V"),
        reinterpret_cast<void *>(QwenTextGenerator_close)
    },
    {
        const_cast<char *>("generateTextNative"),
        const_cast<char *>("(JLjava/lang/String;IFFF)Ljava/lang/String;"),
        reinterpret_cast<void *>(QwenTextGenerator_generate)
    }
};

int register_QwenTextGenerator(JNIEnv *env) {
    const char *const kClassPathName = "org/futo/inputmethod/latin/xlm/QwenTextGenerator";
    return registerNativeMethods(env, kClassPathName, sQwenMethods, NELEMS(sQwenMethods));
}

} // namespace latinime
