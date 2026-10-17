package com.anooprajan.transcribe.models

import java.io.File

/**
 * A downloadable model. Archives are streamed and only the entries accepted by [keep] are
 * written to disk, so the fp32 copies bundled in sherpa-onnx archives never land on the phone.
 */
data class ModelSpec(
    val id: String,
    val title: String,
    /** Archive or single file. Empty when [files] lists the files individually. */
    val url: String,
    /** Approximate size on disk after extraction. */
    val diskMb: Int,
    val isArchive: Boolean,
    val keep: (String) -> Boolean = { true },
    val files: List<RemoteFile> = emptyList(),
    val engine: Engine = Engine.OTHER,
)

/** A file downloaded as-is, verified against [sha256] when given. */
data class RemoteFile(val url: String, val name: String, val sha256: String? = null)

/** Which sherpa-onnx model family a CTC model belongs to. */
enum class Engine { OTHER, OMNILINGUAL, NEMO_CTC }

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
        engine = Engine.OMNILINGUAL,
    )

    val OMNILINGUAL_1B = ModelSpec(
        id = "omnilingual-1b-v2",
        title = "Omnilingual ASR 1B v2 (more accurate Malayalam, ~3x slower)",
        url = "$GH/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05.tar.bz2",
        diskMb = 1050,
        isArchive = true,
        keep = int8TokensOrWeights,
        engine = Engine.OMNILINGUAL,
    )

    // AI4Bharat IndicConformer (Malayalam, MIT), trained on conversational Indian speech.
    // Community sherpa-onnx export, pinned by checksum. In CI it scored 3.0% CER on FLEURS
    // Malayalam vs 5.2% for Omnilingual 300M and ran 2.5x faster (scripts/eval_malayalam.py).
    private const val INDIC_ML_REPO =
        "https://huggingface.co/csndhanasekar/sherpa-onnx-indicconformer-ml-int8/resolve/main"

    val INDIC_ML = ModelSpec(
        id = "indicconformer-ml",
        title = "IndicConformer Malayalam (most accurate, fastest)",
        url = "",
        diskMb = 135,
        isArchive = false,
        files = listOf(
            RemoteFile("$INDIC_ML_REPO/model.int8.onnx", "model.int8.onnx"),
            RemoteFile("$INDIC_ML_REPO/tokens.txt", "tokens.txt"),
        ),
        engine = Engine.NEMO_CTC,
    )

    val whisperModels = listOf(WHISPER_SMALL, WHISPER_MEDIUM, WHISPER_TURBO)
    val malayalamModels = listOf(INDIC_ML, OMNILINGUAL_1B, OMNILINGUAL_300M)
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
