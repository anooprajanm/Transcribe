package com.anooprajan.transcribe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anooprajan.transcribe.Diagnostics
import com.anooprajan.transcribe.audio.RecorderService
import com.anooprajan.transcribe.audio.RecorderState
import com.anooprajan.transcribe.data.Recording
import com.anooprajan.transcribe.data.Recordings
import com.anooprajan.transcribe.data.Settings
import com.anooprajan.transcribe.data.Status
import com.anooprajan.transcribe.models.ModelManager
import com.anooprajan.transcribe.pipeline.ProcessingState
import com.anooprajan.transcribe.pipeline.TranscriptFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val recordings by Recordings.get(context).all.collectAsState()
    val live by RecorderState.live.collectAsState()
    val jobs by ProcessingState.jobs.collectAsState()

    var crash by remember { mutableStateOf(Diagnostics.unseenCrash(context)) }

    val missingModels = remember(jobs) {
        val models = ModelManager(context)
        Settings(context).requiredModels().filterNot { models.isInstalled(it) }
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(context)
    }
    val startRecording: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) RecorderService.start(context) else launcher.launch(permissions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcribe") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            val current = live
            if (current == null) {
                ExtendedFloatingActionButton(
                    onClick = startRecording,
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text("Record") },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { RecorderService.stop(context) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    icon = { Icon(Icons.Default.Stop, null) },
                    text = { Text("Stop  ${TranscriptFormatter.timestamp(current.elapsedSec.toFloat())}") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            live?.let { rec ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Recording", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { rec.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Keep the phone in the middle of the table. Screen can be off.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            crash?.let { description ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("The app stopped unexpectedly last time", style = MaterialTheme.typography.titleMedium)
                            Text(description, style = MaterialTheme.typography.bodySmall)
                            Row {
                                ShareDiagnosticsButton()
                                TextButton(onClick = {
                                    Diagnostics.dismissCrash(context)
                                    crash = null
                                }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
            if (missingModels.isNotEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Speech models needed", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "One-time download (~${missingModels.sumOf { it.diskMb }} MB on disk, larger over the " +
                                    "network). After this everything runs offline.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            jobs[ProcessingState.MODELS]?.let { ProgressLine(it.stage, it.fraction) }
                            TextButton(onClick = onSettings) { Text("Open settings") }
                        }
                    }
                }
            }
            if (recordings.isEmpty() && live == null) {
                item {
                    Text(
                        "No meetings yet. Tap Record when the meeting starts.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
            items(recordings.filter { it.status != Status.RECORDING }, key = { it.id }) { rec ->
                RecordingCard(rec, jobs[rec.id]?.let { it.stage to it.fraction }) { onOpen(rec.id) }
            }
        }
    }
}

@Composable
private fun RecordingCard(rec: Recording, job: Pair<String, Float?>?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(rec.title, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(rec.createdAt)) +
                        " · " + TranscriptFormatter.timestamp(rec.durationSec.toFloat()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(rec.status), style = MaterialTheme.typography.labelMedium, color = statusColor(rec.status))
            }
            job?.let { ProgressLine(it.first, it.second) }
        }
    }
}

@Composable
fun ProgressLine(stage: String, fraction: Float?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stage, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

fun statusLabel(s: Status) = when (s) {
    Status.RECORDING -> "Recording"
    Status.RECORDED -> "Not transcribed"
    Status.QUEUED -> "Queued"
    Status.PROCESSING -> "Transcribing"
    Status.DONE -> "Ready"
    Status.ERROR -> "Failed"
}

@Composable
fun statusColor(s: Status) = when (s) {
    Status.DONE -> MaterialTheme.colorScheme.primary
    Status.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun ShareDiagnosticsButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val intent = withContext(Dispatchers.IO) { runCatching { Diagnostics.shareIntent(context) }.getOrNull() }
            busy = false
            intent?.let { context.startActivity(it) }
        }
    }) { Text(if (busy) "Collecting…" else "Share diagnostics") }
}
