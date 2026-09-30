package org.futo.inputmethod.latin.xlm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.utils.JniUtils
import java.io.File

/**
 * QwenTextGenerator provides text-generation inference using Qwen GGUF models
 * through the existing llama.cpp infrastructure.
 *
 * Unlike the existing LanguageModel (which uses SentencePiece + llama.cpp for
 * next-word prediction/autocomplete), this class uses llama.cpp's built-in BPE
 * tokenizer (which Qwen2 GGUF files ship with) and runs a full autoregressive
 * generation loop with sampling.
 *
 * Usage:
 *   val generator = QwenTextGenerator(modelFile)
 *   val result = generator.generate("Clean this: um um hello world")
 *   generator.close()
 */
class QwenTextGenerator(private val modelFile: File) {
    companion object {
        private const val TAG = "QwenTextGenerator"
        private const val MAX_TOKENS = 512
        private const val CONTEXT_SIZE = 2048
    }

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var isLoaded = false

    /**
     * Load the Qwen model from disk. Must be called before generate().
     * Thread-safe: uses LanguageModelScope.
     */
    suspend fun loadModel() = withContext(LanguageModelScope) {
        if (isLoaded && nativeHandle != 0L) return@withContext

        withContext(Dispatchers.Main) { JniUtils.loadNativeLibrary() }

        if (!modelFile.exists() || modelFile.length() < 10 * 1024 * 1024) {
            Log.e(TAG, "Model file does not exist or is too small: ${modelFile.absolutePath}")
            throw ModelLoadingException("Qwen model file not found: ${modelFile.absolutePath}")
        }

        Log.i(TAG, "Loading Qwen model from: ${modelFile.absolutePath}")
        val handle = openQwenNative(modelFile.absolutePath, CONTEXT_SIZE)
        if (handle == 0L) {
            throw ModelLoadingException("Failed to load Qwen model from ${modelFile.absolutePath}")
        }
        nativeHandle = handle
        isLoaded = true
        Log.i(TAG, "Qwen model loaded successfully, handle=$handle")
    }

    /**
     * Generate text from a prompt using the Qwen model.
     * Runs autoregressive token-by-token generation with top-p + temperature sampling.
     *
     * @param prompt The full ChatML-formatted prompt
     * @param maxTokens Maximum number of tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy, higher = more random)
     * @param topP Nucleus sampling threshold
     * @param repetitionPenalty Penalty for repeated tokens
     * @return Generated text string
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = MAX_TOKENS,
        temperature: Float = 0.3f,
        topP: Float = 0.9f,
        repetitionPenalty: Float = 1.1f
    ): String = withContext(LanguageModelScope) {
        if (!isLoaded || nativeHandle == 0L) {
            loadModel()
        }

        Log.d(TAG, "Generating with prompt length=${prompt.length}, maxTokens=$maxTokens, temp=$temperature")
        val startTime = System.currentTimeMillis()

        val result = generateTextNative(
            nativeHandle,
            prompt,
            maxTokens,
            temperature,
            topP,
            repetitionPenalty
        )

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Generation completed in ${elapsed}ms, output length=${result.length}")
        Log.d(TAG, "Generated: $result")

        return@withContext result
    }

    /**
     * Release native resources.
     */
    fun close() {
        if (nativeHandle != 0L) {
            try {
                closeQwenNative(nativeHandle)
            } catch (t: Throwable) {
                Log.e(TAG, "Error closing Qwen model", t)
            }
            nativeHandle = 0L
            isLoaded = false
        }
    }

    fun isModelLoaded(): Boolean = isLoaded && nativeHandle != 0L

    // ---- Native methods ----

    /**
     * Load a GGUF model file and create a llama context for text generation.
     * Uses llama.cpp's built-in BPE tokenizer (compatible with Qwen2).
     *
     * @param modelPath Absolute path to the .gguf file
     * @param contextSize Context window size
     * @return Native handle (pointer), or 0 on failure
     */
    private external fun openQwenNative(modelPath: String, contextSize: Int): Long

    /**
     * Free all native resources associated with the handle.
     */
    private external fun closeQwenNative(handle: Long)

    /**
     * Run autoregressive text generation.
     *
     * @param handle Native handle from openQwenNative
     * @param prompt The full prompt text (will be tokenized using built-in BPE tokenizer)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature
     * @param topP Top-p (nucleus) sampling
     * @param repetitionPenalty Repetition penalty
     * @return Generated output text
     */
    private external fun generateTextNative(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float
    ): String
}
