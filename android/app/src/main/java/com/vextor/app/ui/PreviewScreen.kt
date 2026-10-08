package com.vextor.app.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vextor.app.ChatViewModel
import com.vextor.app.HtmlFiles
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(html: String, vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var desktop by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anteprima") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { desktop = !desktop }) {
                        Icon(if (desktop) Icons.Default.Smartphone else Icons.Default.Computer, "Vista desktop/mobile")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val msg = runCatching { "Salvato in " + vm.saveHtmlToDownloads(html) }
                                .getOrElse { "Errore: ${it.message}" }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Default.Download, "Salva") }
                    IconButton(onClick = { HtmlFiles.share(ctx, html) }) { Icon(Icons.Default.Share, "Condividi") }
                },
            )
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                }
            },
            update = { wv ->
                wv.settings.useWideViewPort = desktop
                wv.settings.userAgentString = if (desktop)
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
                else null
                val key = html.hashCode() * 31 + desktop.hashCode()
                if (wv.tag != key) {
                    wv.tag = key
                    wv.loadDataWithBaseURL("https://preview.vextor.local/", html, "text/html", "UTF-8", null)
                }
            },
        )
    }
}
