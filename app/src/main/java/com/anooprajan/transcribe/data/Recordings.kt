package com.anooprajan.transcribe.data

import android.content.Context
import com.anooprajan.transcribe.audio.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status { RECORDING, RECORDED, QUEUED, PROCESSING, DONE, ERROR }

data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationSec: Double,
    val status: Status,
    val error: String? = null,
    /** Speaker index (0-based) to display name. */
    val speakerNames: Map<Int, String> = emptyMap(),
) {
    fun speakerName(index: Int) = speakerNames[index]?.takeIf { it.isNotBlank() } ?: "Speaker ${index + 1}"
}

data class TranscriptSegment(
    val start: Float,
    val end: Float,
    val speaker: Int,
    /** Language reported by Whisper ("en", "ml", ...), or empty when unknown. */
    val lang: String,
    /** "whisper" or "omnilingual". */
    val engine: String,
    val text: String,
)

data class Transcript(val segments: List<TranscriptSegment>) {
    val speakers: List<Int> get() = segments.map { it.speaker }.distinct().sorted()
}

/**
 * File-backed store. Each recording is a directory under files/recordings/<id>/ containing
 * audio.wav, meta.json, and (after processing) transcript.json and actions.md.
 */
class Recordings private constructor(context: Context) {
    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val _all = MutableStateFlow<List<Recording>>(emptyList())
    val all: StateFlow<List<Recording>> = _all

    init {
        // A crash mid-recording/processing leaves a stale status; make those recoverable.
        loadAll().forEach {
            when (it.status) {
                Status.RECORDING -> save(it.copy(status = Status.RECORDED, durationSec = Wav.durationSeconds(audio(it.id))))
                Status.PROCESSING, Status.QUEUED -> save(it.copy(status = Status.RECORDED))
                else -> Unit
            }
        }
        refresh()
    }

    fun dir(id: String) = File(root, id)
    fun audio(id: String) = File(dir(id), "audio.wav")
    private fun meta(id: String) = File(dir(id), "meta.json")
    private fun transcriptFile(id: String) = File(dir(id), "transcript.json")
    private fun actionsFile(id: String) = File(dir(id), "actions.md")

    fun create(): Recording {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        dir(id).mkdirs()
        val title = "Meeting " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(now))
        val rec = Recording(id, title, now, 0.0, Status.RECORDING)
        save(rec)
        return rec
    }

    fun get(id: String): Recording? = runCatching { parse(JSONObject(meta(id).readText())) }.getOrNull()

    fun update(id: String, transform: (Recording) -> Recording) {
        synchronized(this) {
            val current = get(id) ?: return
            save(transform(current))
        }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        refresh()
    }

    fun saveTranscript(id: String, t: Transcript) {
        val arr = JSONArray()
        t.segments.forEach {
            arr.put(
                JSONObject()
                    .put("start", it.start.toDouble())
                    .put("end", it.end.toDouble())
                    .put("speaker", it.speaker)
                    .put("lang", it.lang)
                    .put("engine", it.engine)
                    .put("text", it.text),
            )
        }
        transcriptFile(id).writeText(JSONObject().put("segments", arr).toString(2))
    }

    fun transcript(id: String): Transcript? = runCatching {
        val arr = JSONObject(transcriptFile(id).readText()).getJSONArray("segments")
        Transcript(
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TranscriptSegment(
                    start = o.getDouble("start").toFloat(),
                    end = o.getDouble("end").toFloat(),
                    speaker = o.getInt("speaker"),
                    lang = o.optString("lang"),
                    engine = o.optString("engine"),
                    text = o.getString("text"),
                )
            },
        )
    }.getOrNull()

    fun saveActions(id: String, markdown: String) = actionsFile(id).writeText(markdown)

    fun actions(id: String): String? = actionsFile(id).takeIf { it.exists() }?.readText()

    fun refresh() {
        _all.value = loadAll()
    }

    private fun loadAll(): List<Recording> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { get(it.name) }
            .sortedByDescending { it.createdAt }

    private fun save(r: Recording) {
        val names = JSONObject()
        r.speakerNames.forEach { (k, v) -> names.put(k.toString(), v) }
        val json = JSONObject()
            .put("id", r.id)
            .put("title", r.title)
            .put("createdAt", r.createdAt)
            .put("durationSec", r.durationSec)
            .put("status", r.status.name)
            .put("error", r.error ?: JSONObject.NULL)
            .put("speakerNames", names)
        val tmp = File(dir(r.id), "meta.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(meta(r.id))
        refresh()
    }

    private fun parse(o: JSONObject): Recording {
        val namesObj = o.optJSONObject("speakerNames") ?: JSONObject()
        val names = namesObj.keys().asSequence().associate { it.toInt() to namesObj.getString(it) }
        return Recording(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            durationSec = o.optDouble("durationSec", 0.0),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.RECORDED),
            error = if (o.isNull("error")) null else o.optString("error"),
            speakerNames = names,
        )
    }

    companion object {
        @Volatile private var instance: Recordings? = null
        fun get(context: Context): Recordings =
            instance ?: synchronized(this) {
                instance ?: Recordings(context.applicationContext).also { instance = it }
            }
    }
}
