package com.vextor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vextor.app.ChatViewModel
import com.vextor.app.DEFAULT_SYSTEM_PROMPT
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var temp by remember { mutableFloatStateOf(s.temperature) }
    var topP by remember { mutableFloatStateOf(s.topP) }
    var maxTok by remember { mutableFloatStateOf(s.maxTokens.toFloat()) }
    var ctx by remember { mutableFloatStateOf(s.contextSize.toFloat()) }
    var threads by remember { mutableFloatStateOf(s.threads.toFloat()) }
    var system by remember { mutableStateOf(s.systemPrompt) }
    val cores = Runtime.getRuntime().availableProcessors()

    fun save() {
        s.temperature = temp
        s.topP = topP
        s.maxTokens = maxTok.roundToInt()
        s.systemPrompt = system
        val reload = s.contextSize != ctx.roundToInt() || s.threads != threads.roundToInt()
        s.contextSize = ctx.roundToInt()
        s.threads = threads.roundToInt()
        if (reload) vm.reloadModel()
    }

    BackHandler { save(); onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Temperatura: ${"%.2f".format(temp)} (bassa = più preciso)")
            Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1.2f)
            Text("Top-p: ${"%.2f".format(topP)}")
            Slider(value = topP, onValueChange = { topP = it }, valueRange = 0.5f..1f)
            Text("Token massimi per risposta: ${maxTok.roundToInt()}")
            Slider(value = maxTok, onValueChange = { maxTok = (it / 256).roundToInt() * 256f }, valueRange = 512f..12288f)
            Text("Contesto: ${ctx.roundToInt()} token (ricarica il modello)")
            Slider(value = ctx, onValueChange = { ctx = (it / 1024).roundToInt() * 1024f }, valueRange = 4096f..32768f)
            Text("Thread CPU: ${threads.roundToInt()} su $cores core (ricarica il modello)")
            Slider(value = threads, onValueChange = { threads = it.roundToInt().toFloat() }, valueRange = 1f..cores.toFloat())
            OutlinedTextField(
                value = system,
                onValueChange = { system = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            TextButton(onClick = { system = DEFAULT_SYSTEM_PROMPT }) { Text("Ripristina system prompt") }
            Button(onClick = { save(); onBack() }) { Text("Salva") }
            Text("Info sistema (llama.cpp)", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(vm.systemInfo.value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
