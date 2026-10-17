package com.anooprajan.transcribe.pipeline

import com.anooprajan.transcribe.audio.Wav
import com.anooprajan.transcribe.data.AsrMode
import com.anooprajan.transcribe.data.Settings
import com.anooprajan.transcribe.data.Transcript
import com.anooprajan.transcribe.data.TranscriptSegment
import com.anooprajan.transcribe.models.ModelCatalog
import com.anooprajan.transcribe.models.Engine
import com.anooprajan.transcribe.models.ModelManager
import com.anooprajan.transcribe.models.ModelSpec
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Offline pipeline: speaker diarization over the whole recording, then ASR per speaker turn.
 *
 * Mixed language handling: in [AsrMode.HYBRID] each turn is first decoded by Whisper with
 * automatic language detection. If Whisper says the turn is English, its text is kept;
 * otherwise the turn is re-decoded by Omnilingual ASR, which handles Malayalam far better
 * than Whisper does. Switching happens per turn, so a meeting that moves between English
 * and Malayalam is transcribed in the right script for each turn.
 */
class TranscriptionPipeline(
    private val settings: Settings,
    private val models: ModelManager,
) {
    fun interface Progress {
        fun report(stage: String, fraction: Float)
    }

    fun run(audio: File, progress: Progress): Transcript {
        val threads = settings.numThreads
        progress.report("Loading audio", 0f)
        val samples = Wav.readFloats(audio)
        val totalSec = samples.size.toFloat() / Wav.SAMPLE_RATE

        progress.report("Identifying speakers", 0f)
        val turns = diarize(samples, threads, progress).let {
            if (it.isEmpty()) Segmenter.windows(totalSec) else Segmenter.build(it)
        }.let(Segmenter::renumberSpeakers)

        val mode = settings.asrMode
        // Hybrid only sends non-Malayalam turns to Whisper, so pin it to English there.
        val whisper = if (mode != AsrMode.OMNILINGUAL) {
            createWhisper(threads, language = if (mode == AsrMode.HYBRID) "en" else "")
        } else {
            null
        }
        val mlSpec = settings.malayalamModel
        // Router: Omnilingual writes each language in its own script, which tells Malayalam from
        // English far more reliably than Whisper's language ID.
        val router = if (mode == AsrMode.HYBRID) createCtc(ModelCatalog.OMNILINGUAL_300M, threads) else null
        val malayalam = when {
            mode == AsrMode.WHISPER -> null
            router != null && mlSpec == ModelCatalog.OMNILINGUAL_300M -> router
            else -> createCtc(mlSpec, threads)
        }
        try {
            val out = ArrayList<TranscriptSegment>(turns.size)
            turns.forEachIndexed { i, turn ->
                progress.report("Transcribing turn ${i + 1} of ${turns.size}", i.toFloat() / turns.size)
                val chunk = slice(samples, turn)
                val seg = when (mode) {
                    AsrMode.OMNILINGUAL -> TranscriptSegment(
                        turn.start, turn.end, turn.speaker, "ml", mlSpec.id, decode(malayalam!!, chunk).text,
                    )
                    AsrMode.WHISPER -> {
                        val r = decode(whisper!!, chunk)
                        TranscriptSegment(turn.start, turn.end, turn.speaker, r.lang, "whisper", r.text)
                    }
                    AsrMode.HYBRID -> {
                        // Whisper's language ID often tags Malayalam as Urdu/Tamil, or as English and
                        // then invents an English sentence; route by the router's output script instead.
                        val o = decode(router!!, chunk)
                        if (malayalamRatio(o.text) >= 0.4f) {
                            val text = if (malayalam === router) o.text else decode(malayalam!!, chunk).text
                            TranscriptSegment(turn.start, turn.end, turn.speaker, "ml", mlSpec.id, text)
                        } else {
                            val w = decode(whisper!!, chunk)
                            TranscriptSegment(turn.start, turn.end, turn.speaker, "en", "whisper", w.text)
                        }
                    }
                }
                if (seg.text.isNotBlank()) out.add(seg)
            }
            progress.report("Done", 1f)
            return Transcript(out)
        } finally {
            whisper?.release()
            router?.release()
            if (malayalam !== router) malayalam?.release()
        }
    }

    private fun diarize(samples: FloatArray, threads: Int, progress: Progress): List<Turn> {
        val n = settings.numSpeakers
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    ModelCatalog.segmentationModel(models.dir(ModelCatalog.SEGMENTATION)),
                ),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = ModelCatalog.embeddingModel(models.dir(ModelCatalog.EMBEDDING)),
                numThreads = threads,
            ),
            clustering = FastClusteringConfig(
                numClusters = if (n > 0) n else -1,
                threshold = settings.clusterThreshold,
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val sd = OfflineSpeakerDiarization(config = config)
        try {
            val segments = sd.processWithCallback(samples, DiarizationCallback(progress))
            return segments.map { Turn(it.start, it.end, it.speaker) }
        } finally {
            sd.release()
        }
    }

    /**
     * Must be a named class, not a lambda: the JNI layer looks up
     * `invoke(IIJ)Ljava/lang/Integer;`, which Kotlin 2 lambdas (compiled via invokedynamic)
     * do not have. A missing method leaves a pending JNI exception that aborts the process.
     */
    private class DiarizationCallback(private val progress: Progress) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            // An exception escaping into native code would also abort the process.
            runCatching { if (total > 0) progress.report("Identifying speakers", done.toFloat() / total) }
            return 0
        }
    }

    private fun createWhisper(threads: Int, language: String): OfflineRecognizer {
        val files = ModelCatalog.whisperFiles(models.dir(settings.whisperModel))
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Wav.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = files.encoder,
                        decoder = files.decoder,
                        language = language, // empty = detect per call
                        task = "transcribe",
                    ),
                    tokens = files.tokens,
                    modelType = "whisper",
                    numThreads = threads,
                ),
            ),
        )
    }

    private fun createCtc(spec: ModelSpec, threads: Int): OfflineRecognizer {
        val files = ModelCatalog.ctcFiles(models.dir(spec))
        val modelConfig = when (spec.engine) {
            Engine.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = files.model),
                tokens = files.tokens,
                numThreads = threads,
            )
            else -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = files.model),
                tokens = files.tokens,
                numThreads = threads,
            )
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Wav.SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
            ),
        )
    }

    private data class Decoded(val text: String, val lang: String)

    private fun decode(recognizer: OfflineRecognizer, chunk: FloatArray): Decoded {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(chunk, Wav.SAMPLE_RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return Decoded(cleanText(r.text), normalizeLang(r.lang))
        } finally {
            stream.release()
        }
    }

    private fun slice(samples: FloatArray, t: Turn): FloatArray {
        // A little padding avoids clipping the first/last syllable at diarization boundaries.
        val pad = (0.15f * Wav.SAMPLE_RATE).toInt()
        val from = ((t.start * Wav.SAMPLE_RATE).toInt() - pad).coerceIn(0, samples.size)
        val to = ((t.end * Wav.SAMPLE_RATE).toInt() + pad).coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }

    companion object {
        /** Whisper reports languages as "<|en|>" in some builds and "en" in others. */
        internal fun normalizeLang(lang: String) = lang.trim().removePrefix("<|").removeSuffix("|>").lowercase()

        /** Share of letters that are Malayalam script (U+0D00–U+0D7F). */
        internal fun malayalamRatio(text: String): Float {
            var letters = 0
            var ml = 0
            for (c in text) {
                if (c in '\u0D00'..'\u0D7F') { ml++; letters++ } else if (c.isLetter()) letters++
            }
            return if (letters == 0) 0f else ml.toFloat() / letters
        }

        /** Collapses Whisper's occasional repetition loops ("ok ok ok ok ok ..."). */
        internal fun cleanText(text: String): String {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val out = ArrayList<String>(words.size)
            var run = 0
            for (w in words) {
                run = if (out.isNotEmpty() && out.last().equals(w, ignoreCase = true)) run + 1 else 0
                if (run < 3) out.add(w)
            }
            return out.joinToString(" ")
        }
    }
}
