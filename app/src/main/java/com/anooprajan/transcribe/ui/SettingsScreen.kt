package com.anooprajan.transcribe.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anooprajan.transcribe.data.AsrMode
import com.anooprajan.transcribe.data.Settings
import com.anooprajan.transcribe.models.ModelCatalog
import com.anooprajan.transcribe.models.ModelManager
import com.anooprajan.transcribe.pipeline.ActionItemExtractor
import com.anooprajan.transcribe.pipeline.ProcessingService
import com.anooprajan.transcribe.pipeline.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val models = remember { ModelManager(context) }
    val jobs by ProcessingState.jobs.collectAsState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(settings.asrMode) }
    var whisperId by remember { mutableStateOf(settings.whisperModelId) }
    var speakers by remember { mutableIntStateOf(settings.numSpeakers) }
    var threshold by remember { mutableStateOf(settings.clusterThreshold) }
    var threads by remember { mutableIntStateOf(settings.numThreads) }
    // Bumped to re-read install state after deletes/imports.
    var revision by remember { mutableIntStateOf(0) }
    var llmStatus by remember { mutableStateOf<String?>(null) }

    val modelJob = jobs[ProcessingState.MODELS]
    val installed = remember(modelJob, revision) { ModelCatalog.all.associate { it.id to models.isInstalled(it) } }
    val required = remember(mode, whisperId, modelJob, revision) { settings.requiredModels() }
    val missing = required.filter { installed[it.id] != true }
    val llmInstalled = remember(revision) { ActionItemExtractor.modelFile(context).exists() }

    val importLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            llmStatus = "Copying model…"
            llmStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = ActionItemExtractor.modelFile(context)
                    dest.parentFile!!.mkdirs()
                    val tmp = java.io.File(dest.parentFile, "model.task.tmp")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    check(tmp.renameTo(dest)) { "Could not save model" }
                    null
                }.getOrElse { "Import failed: ${it.message}" }
            }
            revision++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Header("Transcription engine") }
            items(AsrMode.entries) { m ->
                Choice(m.title, m.description, selected = mode == m) {
                    mode = m
                    settings.asrMode = m
                }
            }
            if (mode != AsrMode.OMNILINGUAL) {
                item { Header("Whisper model") }
                items(ModelCatalog.whisperModels) { spec ->
                    Choice(spec.title, "~${spec.diskMb} MB on disk", selected = whisperId == spec.id) {
                        whisperId = spec.id
                        settings.whisperModelId = spec.id
                    }
                }
            }

            item { Header("Speakers") }
            item {
                Text(if (speakers == 0) "Number of speakers: automatic" else "Number of speakers: $speakers")
                Text(
                    "Setting the exact count when you know it gives much better speaker separation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = speakers.toFloat(),
                    onValueChange = { speakers = it.roundToInt() },
                    onValueChangeFinished = { settings.numSpeakers = speakers },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
            if (speakers == 0) {
                item {
                    Text("Automatic grouping threshold: ${"%.2f".format(threshold)}")
                    Text(
                        "Raise it if one person is split into several speakers; lower it if different people are merged.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { settings.clusterThreshold = threshold },
                        valueRange = 0.3f..0.9f,
                    )
                }
            }

            item { Header("Performance") }
            item {
                Text("CPU threads: $threads")
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.roundToInt() },
                    onValueChangeFinished = { settings.numThreads = threads },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }

            item { Header("Speech models") }
            item {
                Text(
                    if (missing.isEmpty()) "All models for the selected engine are installed. Transcription works offline."
                    else "Missing: ${missing.joinToString { it.title }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                modelJob?.let { ProgressLine(it.stage, it.fraction) }
                if (missing.isNotEmpty()) {
                    Button(
                        enabled = modelJob == null || modelJob.stage.startsWith("Download failed"),
                        onClick = { ProcessingService.download(context, missing.map { it.id }) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Download models (~${missing.sumOf { it.diskMb }} MB, use Wi-Fi)") }
                }
            }
            items(ModelCatalog.all.filter { installed[it.id] == true }) { spec ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(spec.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = modelJob == null, onClick = {
                        models.delete(spec)
                        revision++
                    }) { Text("Delete") }
                }
            }

            item { Header("Action items (on-device LLM)") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (llmInstalled) "LLM installed. Action items are generated after each transcription."
                            else "No LLM installed. Download a Gemma .task model on the phone (for example " +
                                "Gemma 3 1B IT int4 or Gemma 3n E2B from the litert-community page on Hugging Face), " +
                                "then import it here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        llmStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importLlm.launch(arrayOf("*/*")) }) {
                                Text(if (llmInstalled) "Replace model" else "Import .task model")
                            }
                            if (llmInstalled) {
                                TextButton(onClick = {
                                    ActionItemExtractor.modelFile(context).delete()
                                    revision++
                                }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun Choice(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
