package com.vextor.app.llm

/** Callback chiamata dal codice nativo durante la generazione. */
interface TokenCallback {
    /** Byte UTF-8 completi del nuovo testo. Ritorna false per interrompere. */
    fun onToken(bytes: ByteArray): Boolean

    /** Avanzamento dell'elaborazione del prompt. */
    fun onPromptProgress(done: Int, total: Int)
}

/** Binding JNI verso llama.cpp (vedi src/main/cpp/vextor_jni.cpp). */
object LlamaNative {
    const val ERR_TEMPLATE = -1
    const val ERR_TOO_LONG = -2
    const val ERR_DECODE = -3
    const val ERR_TOKENIZE = -4

    init {
        System.loadLibrary("vextor")
    }

    external fun init(nativeLibDir: String)
    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun resetCache(handle: Long)
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: TokenCallback,
    ): Int
}
