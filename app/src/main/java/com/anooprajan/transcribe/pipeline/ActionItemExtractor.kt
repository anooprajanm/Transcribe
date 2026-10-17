package com.anooprajan.transcribe.pipeline

import android.content.Context
import com.anooprajan.transcribe.data.Recording
import com.anooprajan.transcribe.data.Transcript
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Extracts action items with an on-device LLM (MediaPipe LLM Inference, e.g. a Gemma
 * `.task` bundle the user imports once). Long transcripts are processed in chunks that
 * fit the context window, then consolidated in a final pass.
 */
class ActionItemExtractor(private val context: Context) {

    fun isAvailable() = modelFile(context).exists()

    fun extract(recording: Recording, transcript: Transcript, onProgress: (String) -> Unit): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile(context).absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .build()
        val llm = LlmInference.createFromOptions(context, options)
        try {
            val lines = TranscriptFormatter.lines(recording, transcript)
            val chunks = chunk(lines, CHUNK_CHARS)
            val partials = chunks.mapIndexed { i, c ->
                onProgress("Finding action items (${i + 1}/${chunks.size})")
                llm.generateResponse(gemmaTurn(extractPrompt(c))).trim()
            }
            if (partials.size == 1) return partials.single()
            onProgress("Consolidating action items")
            return llm.generateResponse(gemmaTurn(consolidatePrompt(partials))).trim()
        } finally {
            llm.close()
        }
    }

    private fun extractPrompt(transcriptPart: String) = """
        You are given part of a transcript of a face-to-face office meeting. Speakers mix English
        and Malayalam (Malayalam may appear in Malayalam script). Transcription errors are likely.

        List every action item: a task someone agreed or was asked to do. For each, write one line:
        - [Owner] Task description (Due: date or "not stated")

        Use the speaker names exactly as shown. Write in English. If an owner is unclear, write
        [Unassigned]. Do not invent tasks that are not in the transcript. If there are none, write
        "No action items."

        Transcript:
        $transcriptPart
    """.trimIndent()

    private fun consolidatePrompt(partials: List<String>) = """
        Below are action items extracted from consecutive parts of one meeting. Merge them into a
        single list, removing duplicates and keeping the format:
        - [Owner] Task description (Due: date or "not stated")

        ${partials.joinToString("\n\n")}
    """.trimIndent()

    private fun gemmaTurn(prompt: String) =
        "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"

    companion object {
        private const val MAX_TOKENS = 4096
        // Malayalam script costs far more tokens per character than English; stay conservative.
        private const val CHUNK_CHARS = 3500

        fun modelFile(context: Context) = File(context.filesDir, "llm/model.task")

        internal fun chunk(lines: List<String>, maxChars: Int): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (line in lines) {
                if (cur.isNotEmpty() && cur.length + line.length + 1 > maxChars) {
                    out.add(cur.toString())
                    cur.clear()
                }
                cur.append(line).append('\n')
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}
