package com.anooprajan.transcribe.data

import android.content.Context
import com.anooprajan.transcribe.models.ModelCatalog
import com.anooprajan.transcribe.models.ModelSpec

/** How each speaker turn is transcribed. */
enum class AsrMode(val title: String, val description: String) {
    HYBRID(
        "Hybrid (recommended)",
        "Omnilingual ASR detects whether each turn is Malayalam or English. Malayalam turns are " +
            "transcribed by the Malayalam model below, English turns by Whisper.",
    ),
    WHISPER(
        "Whisper only",
        "Whisper with per-turn language detection. Good English, weak Malayalam.",
    ),
    OMNILINGUAL(
        "Malayalam model only",
        "Every turn goes to the Malayalam model below. English words come out in Malayalam script.",
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

    var malayalamModelId: String
        get() = prefs.getString("malayalamModelId", ModelCatalog.INDIC_ML.id)!!
        set(v) = prefs.edit().putString("malayalamModelId", v).apply()

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

    val malayalamModel: ModelSpec
        get() = ModelCatalog.malayalamModels.firstOrNull { it.id == malayalamModelId }
            ?: ModelCatalog.INDIC_ML

    /** Models the current settings need before a recording can be processed. */
    fun requiredModels(): List<ModelSpec> = buildList {
        add(ModelCatalog.SEGMENTATION)
        add(ModelCatalog.EMBEDDING)
        if (asrMode != AsrMode.OMNILINGUAL) add(whisperModel)
        // Omnilingual 300M is the Malayalam/English router in hybrid mode.
        if (asrMode == AsrMode.HYBRID) add(ModelCatalog.OMNILINGUAL_300M)
        if (asrMode != AsrMode.WHISPER) add(malayalamModel)
    }.distinct()

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
