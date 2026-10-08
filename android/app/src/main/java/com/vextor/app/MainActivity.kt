package com.vextor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vextor.app.ui.ChatScreen
import com.vextor.app.ui.ModelsScreen
import com.vextor.app.ui.PreviewScreen
import com.vextor.app.ui.SettingsScreen
import com.vextor.app.ui.VextorTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VextorTheme {
                var screen by rememberSaveable { mutableStateOf("chat") }
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                when (screen) {
                    "models" -> ModelsScreen(vm, onBack = { screen = "chat" })
                    "settings" -> SettingsScreen(vm, onBack = { screen = "chat" })
                    "preview" -> PreviewScreen(vm.previewHtml.value ?: "", vm, onBack = { screen = "chat" })
                    else -> ChatScreen(
                        vm,
                        onOpenModels = { screen = "models" },
                        onOpenSettings = { screen = "settings" },
                        onPreview = { html ->
                            vm.previewHtml.value = html
                            screen = "preview"
                        },
                    )
                }
            }
        }
    }
}
