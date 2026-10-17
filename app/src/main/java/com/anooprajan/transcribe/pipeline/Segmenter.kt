package com.anooprajan.transcribe.pipeline

import kotlin.math.ceil

data class Turn(val start: Float, val end: Float, val speaker: Int) {
    val duration get() = end - start
}

/**
 * Turns raw diarization output into ASR-sized chunks: adjacent same-speaker segments are
 * merged (fewer, longer chunks give the recognizer more context), and anything longer than
 * [maxLen] is split because Whisper only accepts up to 30 s per call.
 */
object Segmenter {
    fun build(
        segments: List<Turn>,
        maxLen: Float = 28f,
        mergeGap: Float = 1.0f,
        minLen: Float = 0.4f,
    ): List<Turn> {
        val merged = ArrayList<Turn>()
        for (s in segments.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == s.speaker &&
                s.start - last.end <= mergeGap && s.end - last.start <= maxLen
            ) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, s.end))
            } else {
                merged.add(s)
            }
        }
        return merged.flatMap { split(it, maxLen) }.filter { it.duration >= minLen }
    }

    /** Diarization cluster ids are arbitrary; number speakers in order of first appearance. */
    fun renumberSpeakers(turns: List<Turn>): List<Turn> {
        val ids = HashMap<Int, Int>()
        return turns.map { t -> t.copy(speaker = ids.getOrPut(t.speaker) { ids.size }) }
    }

    /** Fixed windows for one speaker; used when diarization finds nothing. */
    fun windows(totalSec: Float, maxLen: Float = 28f): List<Turn> =
        if (totalSec <= 0f) emptyList() else split(Turn(0f, totalSec, 0), maxLen)

    private fun split(t: Turn, maxLen: Float): List<Turn> {
        if (t.duration <= maxLen) return listOf(t)
        val n = ceil(t.duration / maxLen).toInt()
        val step = t.duration / n
        return (0 until n).map { i ->
            Turn(t.start + i * step, if (i == n - 1) t.end else t.start + (i + 1) * step, t.speaker)
        }
    }
}
