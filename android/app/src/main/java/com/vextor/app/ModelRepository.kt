package com.vextor.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val fileName: String,
    val sizeLabel: String,
)

data class LocalModel(val file: File) {
    val name: String get() = file.name
    val sizeMb: Long get() = file.length() / (1024 * 1024)
}

data class DownloadState(val id: Long, val fileName: String, val downloaded: Long, val total: Long, val status: Int) {
    val progress: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    val running get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING ||
        status == DownloadManager.STATUS_PAUSED
}

/** Gestione dei file .gguf: catalogo, download, import e cancellazione. */
class ModelRepository(private val context: Context) {
    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    private val dm = context.getSystemService(DownloadManager::class.java)

    val catalog = listOf(
        CatalogModel(
            id = "qwen-coder-1.5b",
            name = "Qwen2.5 Coder 1.5B (base)",
            description = "Veloce, ottimo per provare subito l'app. Modello base non ancora addestrato sui siti.",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeLabel = "1.1 GB",
        ),
        CatalogModel(
            id = "qwen-coder-3b",
            name = "Qwen2.5 Coder 3B (base)",
            description = "Qualità migliore, più lento. Il Galaxy S25 lo gestisce bene.",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            sizeLabel = "2.0 GB",
        ),
    )

    fun localModels(): List<LocalModel> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 0 }
            ?.sortedBy { it.name }
            ?.map { LocalModel(it) }
            ?: emptyList()

    fun fileFor(fileName: String) = File(modelsDir, fileName)

    fun startDownload(url: String, fileName: String): Long {
        val target = fileFor(fileName)
        if (target.exists()) target.delete()
        val req = DownloadManager.Request(url.toUri())
            .setTitle("Vextor: $fileName")
            .setDescription("Download modello")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
        return dm.enqueue(req)
    }

    fun query(id: Long, fileName: String): DownloadState? {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return DownloadState(id, fileName, done, total, status)
        }
    }

    fun cancel(id: Long) {
        dm.remove(id)
    }

    /** Copia un .gguf scelto dall'utente (es. dalla cartella Download) nella cartella modelli. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "modello.gguf"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (!name.endsWith(".gguf")) name += ".gguf"
        val target = fileFor(name)
        val tmp = File(modelsDir, "$name.part")
        resolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (size > 0) onProgress(copied.toFloat() / size)
                }
            }
        }
        tmp.renameTo(target)
        target
    }

    fun delete(model: LocalModel) {
        model.file.delete()
    }
}
