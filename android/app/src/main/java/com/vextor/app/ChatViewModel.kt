package com.vextor.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vextor.app.llm.ChatTurn
import com.vextor.app.llm.GenParams
import com.vextor.app.llm.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val id: Long,
    val role: String, // "user" | "assistant"
    val text: String,
    val error: Boolean = false,
    val stats: String? = null,
)

sealed interface ModelStatus {
    data object None : ModelStatus
    data class Loading(val name: String) : ModelStatus
    data class Ready(val name: String) : ModelStatus
    data class Failed(val error: String) : ModelStatus
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val repo = ModelRepository(app)
    private val engine = LlamaEngine(app)
    private val historyFile = File(app.filesDir, "chat.json")

    val messages = mutableStateListOf<Message>()
    val generating = mutableStateOf(false)
    val promptProgress = mutableStateOf<Float?>(null)
    val modelStatus = mutableStateOf<ModelStatus>(ModelStatus.None)
    val localModels = mutableStateOf(repo.localModels())
    val download = mutableStateOf<DownloadState?>(null)
    val importProgress = mutableStateOf<Float?>(null)
    val systemInfo = mutableStateOf("")
    val previewHtml = mutableStateOf<String?>(null)

    private var genJob: Job? = null
    private var nextId = System.currentTimeMillis()

    init {
        loadHistory()
        settings.activeModel?.let { path -> if (File(path).exists()) loadModel(path) }
        viewModelScope.launch { systemInfo.value = runCatching { engine.systemInfo() }.getOrDefault("") }
    }

    // ------------------------------------------------------------------ modello

    fun loadModel(path: String) {
        val name = File(path).name
        modelStatus.value = ModelStatus.Loading(name)
        viewModelScope.launch {
            try {
                engine.load(path, settings.contextSize, settings.threads)
                settings.activeModel = path
                modelStatus.value = ModelStatus.Ready(name)
            } catch (e: Exception) {
                modelStatus.value = ModelStatus.Failed(e.message ?: "errore")
            }
        }
    }

    fun reloadModel() {
        settings.activeModel?.let { loadModel(it) }
    }

    fun refreshLocal() {
        localModels.value = repo.localModels()
    }

    fun deleteModel(m: LocalModel) {
        viewModelScope.launch {
            if (engine.loadedPath == m.file.absolutePath) {
                engine.unload()
                modelStatus.value = ModelStatus.None
                settings.activeModel = null
            }
            repo.delete(m)
            refreshLocal()
        }
    }

    fun startDownload(url: String, fileName: String) {
        val id = repo.startDownload(url, fileName)
        download.value = DownloadState(id, fileName, 0, -1, android.app.DownloadManager.STATUS_PENDING)
        viewModelScope.launch {
            while (isActive) {
                val st = repo.query(id, fileName)
                if (st == null) {
                    download.value = null
                    break
                }
                download.value = st
                if (!st.running) {
                    if (st.status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        refreshLocal()
                        download.value = null
                        loadModel(repo.fileFor(fileName).absolutePath)
                    }
                    break
                }
                delay(500)
            }
        }
    }

    fun cancelDownload() {
        download.value?.let { repo.cancel(it.id) }
        download.value = null
    }

    fun importModel(uri: Uri) {
        importProgress.value = 0f
        viewModelScope.launch {
            try {
                val f = repo.import(uri) { p -> importProgress.value = p }
                refreshLocal()
                loadModel(f.absolutePath)
            } catch (e: Exception) {
                modelStatus.value = ModelStatus.Failed("Import fallito: ${e.message}")
            } finally {
                importProgress.value = null
            }
        }
    }

    // ------------------------------------------------------------------ chat

    fun send(text: String) {
        if (text.isBlank() || generating.value) return
        if (modelStatus.value !is ModelStatus.Ready) {
            messages += Message(nextId++, "assistant", "Prima scarica o importa un modello dalla schermata Modelli.", error = true)
            return
        }
        messages += Message(nextId++, "user", text.trim())
        val replyId = nextId++
        messages += Message(replyId, "assistant", "")
        generating.value = true

        val turns = buildList {
            add(ChatTurn("system", settings.systemPrompt))
            messages.filter { !it.error && it.id != replyId && it.text.isNotBlank() }
                .forEach { add(ChatTurn(it.role, it.text)) }
        }
        genJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            var firstToken = 0L
            var last = ""
            try {
                val result = engine.generate(
                    turns,
                    GenParams(settings.maxTokens, settings.temperature, settings.topP),
                    onPrompt = { done, total -> promptProgress.value = done.toFloat() / total },
                    onText = { t ->
                        if (firstToken == 0L) {
                            firstToken = System.currentTimeMillis()
                            promptProgress.value = null
                        }
                        last = t
                        viewModelScope.launch(Dispatchers.Main) { update(replyId, t) }
                    },
                )
                val secs = (System.currentTimeMillis() - (if (firstToken > 0) firstToken else start)) / 1000.0
                val approxTokens = result.length / 3.5
                val stats = "%.1f s · ~%.0f tok/s".format(secs, if (secs > 0) approxTokens / secs else 0.0)
                update(replyId, result, stats = stats)
            } catch (e: Exception) {
                update(replyId, (last + "\n\n⚠️ " + (e.message ?: "errore")).trim(), error = last.isEmpty())
            } finally {
                promptProgress.value = null
                generating.value = false
                saveHistory()
            }
        }
    }

    private fun update(id: Long, text: String, stats: String? = null, error: Boolean = false) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = text, stats = stats, error = error)
    }

    fun stop() {
        engine.stop()
    }

    fun newChat() {
        if (generating.value) return
        messages.clear()
        saveHistory()
    }

    // ------------------------------------------------------------------ persistenza

    private fun saveHistory() {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("error", it.error))
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { historyFile.writeText(arr.toString()) } }
    }

    private fun loadHistory() {
        runCatching {
            if (!historyFile.exists()) return
            val arr = JSONArray(historyFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages += Message(o.getLong("id"), o.getString("role"), o.getString("text"), o.optBoolean("error"))
            }
        }
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }

    suspend fun saveHtmlToDownloads(html: String): String = withContext(Dispatchers.IO) {
        HtmlFiles.saveToDownloads(getApplication(), html)
    }
}
