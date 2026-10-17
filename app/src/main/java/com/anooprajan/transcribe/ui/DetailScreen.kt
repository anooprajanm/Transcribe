package com.anooprajan.transcribe.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anooprajan.transcribe.data.Recordings
import com.anooprajan.transcribe.data.Status
import com.anooprajan.transcribe.pipeline.ActionItemExtractor
import com.anooprajan.transcribe.pipeline.ProcessingService
import com.anooprajan.transcribe.pipeline.ProcessingState
import com.anooprajan.transcribe.pipeline.TranscriptFormatter
import java.io.File

private val speakerColors = listOf(
    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFF4511E),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Recordings.get(context)
    val all by store.all.collectAsState()
    val jobs by ProcessingState.jobs.collectAsState()
    val rec = all.firstOrNull { it.id == id } ?: run { onBack(); return }
    val job = jobs[id]
    // Reload derived files whenever the recording's metadata changes (status, names, refresh()).
    val transcript = remember(rec, all) { store.transcript(id) }
    val actions = remember(rec, all) { store.actions(id) }
    val llmAvailable = remember { ActionItemExtractor(context).isAvailable() }
    val player = remember { SegmentPlayer(store.audio(id)) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rec.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = {
                        val text = TranscriptFormatter.shareText(rec, transcript, actions)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, rec.title)
                                    .putExtra(Intent.EXTRA_TEXT, text),
                                "Share transcript",
                            ),
                        )
                    }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${statusLabel(rec.status)} · ${TranscriptFormatter.timestamp(rec.durationSec.toFloat())}",
                    color = statusColor(rec.status),
                )
                rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                job?.let { ProgressLine(it.stage, it.fraction) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val busy = rec.status == Status.PROCESSING || rec.status == Status.QUEUED || job != null
                    Button(enabled = !busy, onClick = { ProcessingService.process(context, id) }) {
                        Text(if (transcript == null) "Transcribe" else "Re-transcribe")
                    }
                    if (transcript != null) {
                        OutlinedButton(
                            enabled = !busy && llmAvailable,
                            onClick = { ProcessingService.extractActions(context, id) },
                        ) { Text(if (actions == null) "Action items" else "Redo action items") }
                    }
                }
                if (transcript != null && !llmAvailable) {
                    Text(
                        "Import an on-device LLM in Settings to generate action items.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (transcript != null && transcript.speakers.isNotEmpty()) {
                item { SectionTitle("Speakers") }
                items(transcript.speakers, key = { "spk$it" }) { spk ->
                    SpeakerNameField(
                        color = speakerColors[spk % speakerColors.size],
                        initial = rec.speakerNames[spk] ?: "",
                        placeholder = "Speaker ${spk + 1}",
                    ) { name ->
                        store.update(id) { it.copy(speakerNames = it.speakerNames + (spk to name)) }
                    }
                }
            }
            if (!actions.isNullOrBlank()) {
                item { SectionTitle("Action items") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer { Text(actions.trim(), Modifier.padding(16.dp)) }
                    }
                }
            }
            if (transcript != null) {
                item { SectionTitle("Transcript  (tap a line to play it)") }
                items(transcript.segments) { seg ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { player.play(seg.start, seg.end) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row {
                            Text(
                                rec.speakerName(seg.speaker),
                                color = speakerColors[seg.speaker % speakerColors.size],
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                TranscriptFormatter.timestamp(seg.start) +
                                    (if (seg.lang.isNotEmpty()) " · ${seg.lang}" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(rec.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename meeting") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) store.update(id) { it.copy(title = title.trim()) }
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this meeting?") },
            text = { Text("The audio, transcript and action items will be removed from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.delete(id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SpeakerNameField(color: Color, initial: String, placeholder: String, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onSave(it)
        },
        label = { Text(placeholder, color = color) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Plays a time range of the recording so transcript lines can be checked against the audio. */
private class SegmentPlayer(private val file: File) {
    private var mp: MediaPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun play(startSec: Float, endSec: Float) {
        val p = mp ?: runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }.getOrNull()?.also { mp = it } ?: return
        handler.removeCallbacksAndMessages(null)
        p.seekTo((startSec * 1000).toInt())
        p.start()
        handler.postDelayed({ if (p.isPlaying) p.pause() }, ((endSec - startSec) * 1000).toLong() + 300)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        mp?.release()
        mp = null
    }
}
