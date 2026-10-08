package com.vextor.app.llm

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ChatTurn(val role: String, val content: String)

data class GenParams(
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
)

class GenerationException(message: String) : Exception(message)

/**
 * Wrapper thread-safe del modello: tutte le chiamate native avvengono su un
 * unico thread dedicato.
 */
class LlamaEngine(context: Context) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()
    private var handle = 0L
    private val stopFlag = AtomicBoolean(false)
    private val libDir = context.applicationInfo.nativeLibraryDir
    private var initialized = false

    var loadedPath: String? = null
        private set

    val isLoaded get() = handle != 0L

    suspend fun load(path: String, nCtx: Int, nThreads: Int) = withContext(dispatcher) {
        if (!initialized) {
            LlamaNative.init(libDir)
            initialized = true
        }
        unloadInternal()
        val h = LlamaNative.load(path, nCtx, nThreads)
        if (h == 0L) throw GenerationException("Impossibile caricare il modello (file .gguf valido?)")
        handle = h
        loadedPath = path
    }

    suspend fun unload() = withContext(dispatcher) { unloadInternal() }

    private fun unloadInternal() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
            loadedPath = null
        }
    }

    suspend fun systemInfo(): String = withContext(dispatcher) {
        if (!initialized) {
            LlamaNative.init(libDir)
            initialized = true
        }
        LlamaNative.systemInfo()
    }

    fun stop() = stopFlag.set(true)

    /**
     * Genera la risposta. [onText] riceve il testo accumulato finora.
     * Se la conversazione non entra nel contesto, i messaggi più vecchi
     * (esclusi system e ultimo messaggio) vengono scartati.
     */
    suspend fun generate(
        turns: List<ChatTurn>,
        params: GenParams,
        onPrompt: (done: Int, total: Int) -> Unit,
        onText: (String) -> Unit,
    ): String = withContext(dispatcher) {
        if (handle == 0L) throw GenerationException("Nessun modello caricato")
        stopFlag.set(false)
        var history = turns
        while (true) {
            val buffer = StringBuilder()
            var lastEmit = 0L
            val cb = object : TokenCallback {
                override fun onToken(bytes: ByteArray): Boolean {
                    buffer.append(String(bytes, Charsets.UTF_8))
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 60) {
                        lastEmit = now
                        onText(buffer.toString())
                    }
                    return !stopFlag.get()
                }

                override fun onPromptProgress(done: Int, total: Int) = onPrompt(done, total)
            }
            val ctx = LlamaNative.contextSize(handle)
            val maxTokens = params.maxTokens.coerceAtMost(ctx / 2)
            val rc = LlamaNative.generate(
                handle,
                history.map { it.role }.toTypedArray(),
                history.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                maxTokens, params.temperature, params.topP, cb,
            )
            when {
                rc >= 0 -> {
                    val text = buffer.toString()
                    onText(text)
                    return@withContext text
                }
                rc == LlamaNative.ERR_TOO_LONG && history.size > 2 -> {
                    // rimuovo il messaggio più vecchio dopo il system prompt
                    val first = if (history.first().role == "system") 1 else 0
                    if (history.size - first <= 1) throw GenerationException("Messaggio troppo lungo per il contesto")
                    history = history.toMutableList().also { it.removeAt(first) }
                }
                rc == LlamaNative.ERR_TOO_LONG -> throw GenerationException("Messaggio troppo lungo per il contesto")
                rc == LlamaNative.ERR_TEMPLATE -> throw GenerationException("Chat template del modello non supportato")
                else -> throw GenerationException("Errore di inferenza ($rc)")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }
}
