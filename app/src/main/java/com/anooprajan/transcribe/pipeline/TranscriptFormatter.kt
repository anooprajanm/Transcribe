package com.anooprajan.transcribe.pipeline

import com.anooprajan.transcribe.data.Recording
import com.anooprajan.transcribe.data.Transcript

object TranscriptFormatter {
    fun timestamp(sec: Float): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    fun lines(recording: Recording, transcript: Transcript): List<String> =
        transcript.segments.map { "[${timestamp(it.start)}] ${recording.speakerName(it.speaker)}: ${it.text}" }

    fun shareText(recording: Recording, transcript: Transcript?, actions: String?): String = buildString {
        appendLine(recording.title)
        appendLine()
        if (!actions.isNullOrBlank()) {
            appendLine("ACTION ITEMS")
            appendLine(actions.trim())
            appendLine()
        }
        if (transcript != null) {
            appendLine("TRANSCRIPT")
            lines(recording, transcript).forEach { appendLine(it) }
        }
    }
}
