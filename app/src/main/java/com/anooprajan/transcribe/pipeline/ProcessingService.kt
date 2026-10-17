package com.anooprajan.transcribe.pipeline

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anooprajan.transcribe.MainActivity
import com.anooprajan.transcribe.R
import com.anooprajan.transcribe.TranscribeApp
import com.anooprajan.transcribe.data.Recordings
import com.anooprajan.transcribe.data.Settings
import com.anooprajan.transcribe.data.Status
import com.anooprajan.transcribe.models.ModelCatalog
import com.anooprajan.transcribe.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Progress of the job currently running for a key (recording id or [ProcessingState.MODELS]). */
data class JobProgress(val stage: String, val fraction: Float?)

object ProcessingState {
    const val MODELS = "models"
    private val _jobs = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    val jobs: StateFlow<Map<String, JobProgress>> = _jobs

    internal fun set(key: String, p: JobProgress?) = _jobs.update { if (p == null) it - key else it + (key to p) }
}

/**
 * Runs model downloads, transcription and action-item extraction one at a time in a
 * foreground service, so work continues with the screen off or the app in the background.
 */
class ProcessingService : Service() {
    private sealed interface Job {
        data class Transcribe(val id: String) : Job
        data class Actions(val id: String) : Job
        data class Download(val ids: List<String>) : Job
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private var pending = 0
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Preparing…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcribe:process")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch { for (job in queue) runJob(job) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val job = when (intent?.action) {
            ACTION_TRANSCRIBE -> id?.let { Job.Transcribe(it) }
            ACTION_ACTIONS -> id?.let { Job.Actions(it) }
            ACTION_DOWNLOAD -> intent?.getStringArrayExtra(EXTRA_MODELS)?.let { Job.Download(it.toList()) }
            else -> null
        }
        if (job != null) {
            synchronized(this) { pending++ }
            if (job is Job.Transcribe) Recordings.get(this).update(job.id) { it.copy(status = Status.QUEUED, error = null) }
            queue.trySend(job)
        } else if (synchronized(this) { pending == 0 }) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runJob(job: Job) {
        try {
            when (job) {
                is Job.Transcribe -> transcribe(job.id, extractActions = true)
                is Job.Actions -> actions(job.id)
                is Job.Download -> download(job.ids)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Job $job failed", t)
        } finally {
            val done = synchronized(this) { --pending == 0 }
            if (done) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun transcribe(id: String, extractActions: Boolean) {
        val recordings = Recordings.get(this)
        val settings = Settings(this)
        val models = ModelManager(this)
        val missing = settings.requiredModels().filterNot { models.isInstalled(it) }
        if (missing.isNotEmpty()) {
            recordings.update(id) {
                it.copy(status = Status.ERROR, error = "Models not downloaded yet: open Settings → Download models.")
            }
            return
        }
        recordings.update(id) { it.copy(status = Status.PROCESSING, error = null) }
        try {
            val transcript = TranscriptionPipeline(settings, models).run(recordings.audio(id)) { stage, f ->
                progress(id, stage, f)
            }
            recordings.saveTranscript(id, transcript)
            recordings.update(id) { it.copy(status = Status.DONE) }
            if (extractActions && ActionItemExtractor(this).isAvailable()) actions(id)
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed", t)
            recordings.update(id) { it.copy(status = Status.ERROR, error = t.message ?: t.toString()) }
        } finally {
            ProcessingState.set(id, null)
        }
    }

    private fun actions(id: String) {
        val recordings = Recordings.get(this)
        val rec = recordings.get(id) ?: return
        val transcript = recordings.transcript(id) ?: return
        val extractor = ActionItemExtractor(this)
        if (!extractor.isAvailable()) return
        try {
            val result = extractor.extract(rec, transcript) { progress(id, it, null) }
            recordings.saveActions(id, result)
            recordings.refresh()
        } catch (t: Throwable) {
            Log.e(TAG, "Action items failed", t)
            recordings.saveActions(id, "Action item extraction failed: ${t.message}")
            recordings.refresh()
        } finally {
            ProcessingState.set(id, null)
        }
    }

    private fun download(ids: List<String>) {
        val models = ModelManager(this)
        val specs = ModelCatalog.all.filter { it.id in ids && !models.isInstalled(it) }
        try {
            specs.forEachIndexed { i, spec ->
                val label = "Downloading ${spec.title} (${i + 1}/${specs.size})"
                progress(ProcessingState.MODELS, label, null)
                models.download(spec) { done, total ->
                    val mb = done / (1024 * 1024)
                    if (total > 0) progress(ProcessingState.MODELS, "$label · $mb MB", done.toFloat() / total)
                    else progress(ProcessingState.MODELS, "$label · $mb MB", null)
                }
            }
            ProcessingState.set(ProcessingState.MODELS, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed", t)
            ProcessingState.set(ProcessingState.MODELS, JobProgress("Download failed: ${t.message}", null))
        }
    }

    private var lastNotify = 0L

    private fun progress(key: String, stage: String, fraction: Float?) {
        ProcessingState.set(key, JobProgress(stage, fraction))
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(stage, fraction))
        }
    }

    private fun notification(text: String, fraction: Float?) =
        NotificationCompat.Builder(this, TranscribeApp.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transcribe")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProcessingService"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_TRANSCRIBE = "transcribe"
        private const val ACTION_ACTIONS = "actions"
        private const val ACTION_DOWNLOAD = "download"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MODELS = "models"

        fun process(context: Context, id: String) = start(context, ACTION_TRANSCRIBE) { putExtra(EXTRA_ID, id) }

        fun extractActions(context: Context, id: String) = start(context, ACTION_ACTIONS) { putExtra(EXTRA_ID, id) }

        fun download(context: Context, ids: List<String>) =
            start(context, ACTION_DOWNLOAD) { putExtra(EXTRA_MODELS, ids.toTypedArray()) }

        private fun start(context: Context, action: String, extras: Intent.() -> Unit) {
            context.startForegroundService(
                Intent(context, ProcessingService::class.java).setAction(action).apply(extras),
            )
        }
    }
}
