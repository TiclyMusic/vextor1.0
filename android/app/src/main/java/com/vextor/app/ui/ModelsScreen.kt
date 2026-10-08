package com.vextor.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vextor.app.ChatViewModel
import com.vextor.app.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val local by vm.localModels
    val dl by vm.download
    val importP by vm.importProgress
    val status by vm.modelStatus
    var customUrl by remember { mutableStateOf(vm.settings.customModelUrl) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    LaunchedEffect(Unit) { vm.refreshLocal() }
    val activePath = vm.settings.activeModel

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modelli") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status is ModelStatus.Failed) {
                Text((status as ModelStatus.Failed).error, color = MaterialTheme.colorScheme.error)
            }
            Text("Sul telefono", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (local.isEmpty()) Text("Nessun modello scaricato.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            local.forEach { m ->
                val active = m.file.absolutePath == activePath && status is ModelStatus.Ready
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Medium)
                            Text("${m.sizeMb} MB", style = MaterialTheme.typography.labelSmall)
                        }
                        if (active) {
                            Icon(Icons.Default.CheckCircle, "Attivo", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { vm.loadModel(m.file.absolutePath) }) { Text("Usa") }
                        }
                        IconButton(onClick = { vm.deleteModel(m) }) { Icon(Icons.Default.Delete, "Elimina") }
                    }
                }
            }

            dl?.let { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Download: ${d.fileName}")
                        Spacer(Modifier.size(6.dp))
                        if (d.total > 0) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${d.downloaded / 1_048_576} / ${if (d.total > 0) d.total / 1_048_576 else "?"} MB",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.cancelDownload() }) { Text("Annulla") }
                        }
                    }
                }
            }
            importP?.let {
                Text("Importazione…")
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()
            Text("Importa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Hai addestrato Vextor su Colab? Copia il file .gguf sul telefono e selezionalo qui.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = importP == null) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.size(8.dp)); Text("Scegli file .gguf")
            }
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Oppure link diretto al .gguf (Hugging Face, GitHub Release…)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    vm.settings.customModelUrl = customUrl.trim()
                    val name = customUrl.trim().substringAfterLast('/').substringBefore('?')
                        .ifBlank { "vextor.gguf" }.let { if (it.endsWith(".gguf")) it else "$it.gguf" }
                    vm.startDownload(customUrl.trim(), name)
                },
                enabled = customUrl.startsWith("http") && dl == null,
            ) { Text("Scarica dal link") }

            HorizontalDivider()
            Text("Catalogo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            vm.repo.catalog.forEach { c ->
                val have = local.any { it.name == c.fileName }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.name, fontWeight = FontWeight.Medium)
                        Text(c.description, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.sizeLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            if (have) Text("Scaricato", style = MaterialTheme.typography.labelMedium)
                            else TextButton(onClick = { vm.startDownload(c.url, c.fileName) }, enabled = dl == null) {
                                Icon(Icons.Default.Download, null); Spacer(Modifier.size(4.dp)); Text("Scarica")
                            }
                        }
                    }
                }
            }
        }
    }
}
