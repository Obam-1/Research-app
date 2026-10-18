package com.resellcomps.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.resellcomps.app.MainViewModel
import com.resellcomps.app.Screen

private val Green = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App(vm: MainViewModel) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF81C784))
    else lightColorScheme(primary = Green)

    MaterialTheme(colorScheme = colors) {
        val screen by vm.screen.collectAsState()
        val photos by vm.photos.collectAsState()
        val value by vm.value.collectAsState()
        var showSettings by rememberSaveable { mutableStateOf(!vm.settings.seenSettings) }
        var showGuide by rememberSaveable { mutableStateOf(false) }
        val canGoBack = showSettings || showGuide || screen !is Screen.Home || photos.isNotEmpty()
        val goBack = {
            when {
                showSettings -> showSettings = false
                showGuide -> showGuide = false
                else -> vm.reset()
            }
        }

        BackHandler(enabled = canGoBack) { goBack() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(when { showSettings -> "Settings"; showGuide -> "Tag Guide"; else -> "Resell Comps" }) },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        }
                    },
                    actions = {
                        if (!showSettings && !showGuide) {
                            IconButton(onClick = { showGuide = true }) {
                                Icon(Icons.Filled.LocalLibrary, contentDescription = "Tag Guide")
                            }
                        }
                        if (!showSettings) {
                            IconButton(onClick = { showGuide = false; showSettings = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // The app draws edge-to-edge, so the keyboard doesn't shrink the window by itself:
            // pad every screen by the keyboard's height so a focused field scrolls into view above it.
            val modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()
            when {
                showSettings -> SettingsScreen(vm, modifier) { showSettings = false }
                showGuide -> TagGuideScreen(modifier)
                else -> when (val s = screen) {
                    Screen.Home -> HomeScreen(vm, photos, modifier)
                    is Screen.Busy -> BusyScreen(s.message, modifier)
                    is Screen.Identified -> IdentifiedScreen(
                        s.item, photos, modifier,
                        tagCheck = s.tagCheck,
                        valueState = value,
                        onSoldScreenshots = { uris, q -> vm.valueFromScreenshots(uris, s.item, q) },
                        onToggleComp = vm::toggleComp,
                        onRetake = vm::reset,
                        onAddPhoto = vm::backToPhotos,
                        onOpenSettings = { showSettings = true },
                        onDigDeeper = if (vm.canDigDeeper) vm::digDeeper else null,
                        lensQuestion = vm.settings.lensQuestion,
                        onLensQuestionChange = { vm.settings.lensQuestion = it },
                    )
                    is Screen.Failed -> FailedScreen(
                        s, modifier,
                        onRetry = if (s.canRetryIdentify) vm::retryIdentify else null,
                        onBack = vm::reset,
                    )
                }
            }
        }
    }
}

@Composable
private fun BusyScreen(message: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FailedScreen(s: Screen.Failed, modifier: Modifier, onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(s.message)
        Spacer(Modifier.height(16.dp))
        if (onRetry != null) {
            Button(onClick = onRetry) { Text("Try again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("Start over") }
        } else {
            Button(onClick = onBack) { Text("Start over") }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, modifier: Modifier, onDone: () -> Unit) {
    val st = vm.settings
    var freeAiKey by remember { mutableStateOf(st.freeAiKey) }
    var autoDig by remember { mutableStateOf(st.autoDigDeeper) }
    var serverUrl by remember { mutableStateOf(st.serverUrl) }
    var phoneAi by remember { mutableStateOf(st.phoneAi) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Free AI identification", style = MaterialTheme.typography.titleMedium)
        Text(
            "Recognizes what's in your photos and writes the search for you. Paste ONE free key, no card needed:\n" +
                "• Groq: console.groq.com → API Keys → Create API Key\n" +
                "• OpenRouter: openrouter.ai → Keys → Create Key\n" +
                "The app recognizes which one it is. Without a key, it can only read barcodes and printed text.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = freeAiKey, onValueChange = { freeAiKey = it },
            label = { Text("Groq or OpenRouter key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On-phone AI backup", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "When the free limit runs out or there's no signal, use the AI built into your phone " +
                        "(Gemini Nano on supported phones like Pixels). Free, unlimited and offline, but slower " +
                        "and less sharp. The first use may download it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = phoneAi, onCheckedChange = { phoneAi = it })
        }
        Text("My own AI server (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your computer running Ollama, reached through Tailscale. Used first when it's on — unlimited " +
                "and private; when it's off, the key above is used automatically.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text("Server address, e.g. 100.101.102.103:11434") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always dig deeper on tags", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "After each scan that shows a tag, the AI takes a second, closer look to date it. " +
                        "More accurate vintage calls, but slower and uses the free limit twice as fast.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = autoDig, onCheckedChange = { autoDig = it })
        }
        Text(
            "The key is stored only on this phone and sent only to that service. " +
                "This app is not connected to your eBay account; its buttons just open eBay's own pages.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                st.freeAiKey = freeAiKey
                st.autoDigDeeper = autoDig
                st.serverUrl = serverUrl
                st.phoneAi = phoneAi
                st.seenSettings = true
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        OutlinedButton(onClick = { st.seenSettings = true; onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
