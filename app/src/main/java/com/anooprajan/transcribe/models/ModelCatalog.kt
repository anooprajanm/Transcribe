package com.anooprajan.transcribe.models

import java.io.File

/**
 * A downloadable model. Archives are streamed and only the entries accepted by [keep] are
 * written to disk, so the fp32 copies bundled in sherpa-onnx archives never land on the phone.
 */
data class ModelSpec(
    val id: String,
    val title: String,
    val url: String,
    /** Approximate size on disk after extraction. */
    val diskMb: Int,
    val isArchive: Boolean,
    val keep: (String) -> Boolean = { true },
)

/** Resolved file paths for a Whisper-style encoder/decoder model. */
data class WhisperFiles(val encoder: String, val decoder: String, val tokens: String)

/** Resolved file paths for a single-file CTC model. */
data class CtcFiles(val model: String, val tokens: String)

object ModelCatalog {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    private val int8OrTokens: (String) -> Boolean =
        { it.endsWith(".int8.onnx") || it.endsWith("tokens.txt") }

    // Larger models may keep weights in an external data file next to the .onnx.
    private val int8TokensOrWeights: (String) -> Boolean =
        { int8OrTokens(it) || it.endsWith(".weights") || it.endsWith(".data") }

    val SEGMENTATION = ModelSpec(
        id = "pyannote-seg-3.0",
        title = "Speaker segmentation (pyannote 3.0)",
        url = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        diskMb = 6,
        isArchive = true,
        keep = { it.endsWith(".onnx") },
    )

    val EMBEDDING = ModelSpec(
        id = "3dspeaker-eres2net",
        title = "Speaker embeddings (3D-Speaker ERes2Net)",
        url = "$GH/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        diskMb = 38,
        isArchive = false,
    )

    val WHISPER_SMALL = ModelSpec(
        id = "whisper-small",
        title = "Whisper small (fast)",
        url = "$GH/asr-models/sherpa-onnx-whisper-small.tar.bz2",
        diskMb = 360,
        isArchive = true,
        keep = int8OrTokens,
    )

    val WHISPER_MEDIUM = ModelSpec(
        id = "whisper-medium",
        title = "Whisper medium (better English, ~3x slower)",
        url = "$GH/asr-models/sherpa-onnx-whisper-medium.tar.bz2",
        diskMb = 950,
        isArchive = true,
        keep = int8OrTokens,
    )

    val WHISPER_TURBO = ModelSpec(
        id = "whisper-turbo",
        title = "Whisper large-v3 turbo (best English, slowest)",
        url = "$GH/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
        diskMb = 1000,
        isArchive = true,
        keep = int8OrTokens,
    )

    val OMNILINGUAL_300M = ModelSpec(
        id = "omnilingual-300m-v2",
        title = "Omnilingual ASR 300M v2 (fast)",
        url = "$GH/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2",
        diskMb = 350,
        isArchive = true,
        keep = int8TokensOrWeights,
    )

    val OMNILINGUAL_1B = ModelSpec(
        id = "omnilingual-1b-v2",
        title = "Omnilingual ASR 1B v2 (more accurate Malayalam, ~3x slower)",
        url = "$GH/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05.tar.bz2",
        diskMb = 1050,
        isArchive = true,
        keep = int8TokensOrWeights,
    )

    val whisperModels = listOf(WHISPER_SMALL, WHISPER_MEDIUM, WHISPER_TURBO)
    val malayalamModels = listOf(OMNILINGUAL_300M, OMNILINGUAL_1B)
    val all = listOf(SEGMENTATION, EMBEDDING) + whisperModels + malayalamModels

    fun segmentationModel(dir: File): String =
        dir.findFile { it.name == "model.onnx" }
            ?: dir.findFile { it.name.endsWith(".onnx") }
            ?: error("Segmentation model missing in $dir")

    fun embeddingModel(dir: File): String =
        dir.findFile { it.name.endsWith(".onnx") } ?: error("Embedding model missing in $dir")

    fun whisperFiles(dir: File) = WhisperFiles(
        encoder = dir.findFile { "encoder" in it.name && it.name.endsWith(".int8.onnx") }
            ?: error("Whisper encoder missing in $dir"),
        decoder = dir.findFile { "decoder" in it.name && it.name.endsWith(".int8.onnx") }
            ?: error("Whisper decoder missing in $dir"),
        tokens = dir.findFile { it.name.endsWith("tokens.txt") }
            ?: error("Whisper tokens missing in $dir"),
    )

    fun ctcFiles(dir: File) = CtcFiles(
        model = dir.findFile { it.name.endsWith(".int8.onnx") } ?: error("Model missing in $dir"),
        tokens = dir.findFile { it.name.endsWith("tokens.txt") } ?: error("Tokens missing in $dir"),
    )

    private fun File.findFile(predicate: (File) -> Boolean): String? =
        walkTopDown().filter { it.isFile && predicate(it) }.firstOrNull()?.absolutePath
}
