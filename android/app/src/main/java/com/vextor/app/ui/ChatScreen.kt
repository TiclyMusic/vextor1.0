package com.vextor.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vextor.app.ChatViewModel
import com.vextor.app.HtmlExtract
import com.vextor.app.HtmlFiles
import com.vextor.app.Message
import com.vextor.app.ModelStatus
import com.vextor.app.Segment

private val EXAMPLES = listOf(
    "Crea una landing page per un'app di meditazione, tema scuro, palette viola",
    "Portfolio minimal per un fotografo con galleria a griglia",
    "Sito per una pizzeria con menu, orari e mappa",
    "Landing SaaS con hero, features, prezzi e FAQ",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val messages = vm.messages
    val generating by vm.generating
    val status by vm.modelStatus
    val progress by vm.promptProgress
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vextor", fontWeight = FontWeight.Bold)
                        Text(
                            when (val s = status) {
                                ModelStatus.None -> "Nessun modello · tocca il chip"
                                is ModelStatus.Loading -> "Caricamento ${s.name}…"
                                is ModelStatus.Ready -> s.name
                                is ModelStatus.Failed -> "Errore: ${s.error}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (status is ModelStatus.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModels) { Icon(Icons.Default.Memory, "Modelli") }
                    IconButton(onClick = { vm.newChat() }, enabled = !generating) {
                        Icon(Icons.Outlined.AddComment, "Nuova chat")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Impostazioni") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
        ) {
            if (status is ModelStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(
                        hasModel = status is ModelStatus.Ready,
                        onOpenModels = onOpenModels,
                        onExample = { input = it },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages, key = { it.id }) { m ->
                            MessageBubble(
                                m,
                                streaming = generating && m.id == messages.last().id,
                                onPreview = onPreview,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(progress != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Leggo la richiesta…", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 160.dp),
                    placeholder = { Text("Descrivi il sito che vuoi…") },
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                if (generating) {
                    FilledIconButton(onClick = { vm.stop() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Invia") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasModel: Boolean, onOpenModels: () -> Unit, onExample: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Cosa costruiamo oggi?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(
            "Descrivi un sito: Vextor genera HTML, CSS e JS che puoi vedere in anteprima e salvare.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        if (!hasModel) {
            AssistChip(onClick = onOpenModels, label = { Text("Scarica o importa un modello") },
                leadingIcon = { Icon(Icons.Default.Memory, null) })
            Spacer(Modifier.size(12.dp))
        }
        EXAMPLES.forEach {
            SuggestionChip(onClick = { onExample(it) }, label = { Text(it, maxLines = 2) })
        }
    }
}

@Composable
private fun MessageBubble(m: Message, streaming: Boolean, onPreview: (String) -> Unit) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    m.error -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (isUser || m.error) {
                    SelectionContainer { Text(m.text) }
                } else if (m.text.isEmpty() && streaming) {
                    Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    HtmlExtract.segments(m.text).forEach { seg ->
                        when (seg) {
                            is Segment.Text -> SelectionContainer { Text(seg.text) }
                            is Segment.Code -> CodeCard(seg, streaming, onPreview)
                        }
                    }
                }
                m.stats?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CodeCard(seg: Segment.Code, streaming: Boolean, onPreview: (String) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val lines = seg.code.lines()
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .background(Color(0xFF0B0D12), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(
            "${if (seg.isHtml) "index.html" else seg.lang.ifEmpty { "codice" }} · ${lines.size} righe · ${seg.code.length / 1024} KB" +
                if (!seg.complete && streaming) " · in scrittura…" else "",
            color = Color(0xFF9AA4B2),
            fontSize = 12.sp,
        )
        val shown = when {
            expanded -> seg.code
            streaming && !seg.complete -> lines.takeLast(12).joinToString("\n")
            else -> lines.take(8).joinToString("\n") + if (lines.size > 8) "\n…" else ""
        }
        SelectionContainer {
            Text(
                shown,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFD7DEE8),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                softWrap = false,
            )
        }
        if (!streaming || seg.complete) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (seg.isHtml) {
                    TextButton(onClick = { onPreview(seg.code) }) {
                        Icon(Icons.Default.OpenInFull, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Anteprima")
                    }
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(Icons.Default.Code, "Mostra codice", tint = Color(0xFF9AA4B2)) }
                IconButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("codice", seg.code))
                    Toast.makeText(ctx, "Copiato", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, "Copia", tint = Color(0xFF9AA4B2)) }
                if (seg.isHtml) {
                    IconButton(onClick = { HtmlFiles.share(ctx, seg.code) }) {
                        Icon(Icons.Default.Share, "Condividi", tint = Color(0xFF9AA4B2))
                    }
                }
            }
        }
    }
}
