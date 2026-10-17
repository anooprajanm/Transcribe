package com.anooprajan.transcribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.anooprajan.transcribe.models.ModelManager
import kotlin.concurrent.thread

class TranscribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "prune-models") { ModelManager(this).pruneUnknown() }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROCESSING, "Transcription", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_PROCESSING = "processing"
    }
}
