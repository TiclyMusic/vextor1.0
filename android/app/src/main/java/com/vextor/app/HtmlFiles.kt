package com.vextor.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HtmlFiles {
    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Salva in Download/Vextor/ e ritorna il percorso visibile all'utente. */
    fun saveToDownloads(context: Context, html: String): String {
        val name = "vextor-${stamp()}.html"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vextor")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("impossibile creare il file")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(html.toByteArray()) }
        return "Download/Vextor/$name"
    }

    fun share(context: Context, html: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "vextor-${stamp()}.html").apply { writeText(html) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Condividi sito"))
    }
}
