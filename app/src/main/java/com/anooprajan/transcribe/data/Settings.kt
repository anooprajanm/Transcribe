package com.anooprajan.transcribe.data

import android.content.Context
import com.anooprajan.transcribe.models.ModelCatalog
import com.anooprajan.transcribe.models.ModelSpec

/** How each speaker turn is transcribed. */
enum class AsrMode(val title: String, val description: String) {
    HYBRID(
        "Hybrid (recommended)",
        "Whisper detects the language of each turn. English turns stay with Whisper, " +
            "Malayalam / mixed turns go to Omnilingual ASR, which is far stronger on Malayalam.",
    ),
    WHISPER(
        "Whisper only",
        "Whisper with per-turn language detection. Good English, weak Malayalam.",
    ),
    OMNILINGUAL(
        "Omnilingual only",
        "Meta Omnilingual ASR (1600 languages) for every turn. No punctuation or casing.",
    ),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var asrMode: AsrMode
        get() = runCatching { AsrMode.valueOf(prefs.getString("asrMode", null)!!) }
            .getOrDefault(AsrMode.HYBRID)
        set(v) = prefs.edit().putString("asrMode", v.name).apply()

    var whisperModelId: String
        get() = prefs.getString("whisperModelId", ModelCatalog.WHISPER_SMALL.id)!!
        set(v) = prefs.edit().putString("whisperModelId", v).apply()

    /** 0 = estimate automatically. */
    var numSpeakers: Int
        get() = prefs.getInt("numSpeakers", 0)
        set(v) = prefs.edit().putInt("numSpeakers", v).apply()

    /** Clustering threshold used when the speaker count is automatic. Higher = fewer speakers. */
    var clusterThreshold: Float
        get() = prefs.getFloat("clusterThreshold", 0.5f)
        set(v) = prefs.edit().putFloat("clusterThreshold", v).apply()

    var numThreads: Int
        get() = prefs.getInt("numThreads", defaultThreads())
        set(v) = prefs.edit().putInt("numThreads", v).apply()

    val whisperModel: ModelSpec
        get() = ModelCatalog.whisperModels.firstOrNull { it.id == whisperModelId }
            ?: ModelCatalog.WHISPER_SMALL

    /** Models the current settings need before a recording can be processed. */
    fun requiredModels(): List<ModelSpec> = buildList {
        add(ModelCatalog.SEGMENTATION)
        add(ModelCatalog.EMBEDDING)
        if (asrMode != AsrMode.OMNILINGUAL) add(whisperModel)
        if (asrMode != AsrMode.WHISPER) add(ModelCatalog.OMNILINGUAL)
    }

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
