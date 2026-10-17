package com.anooprajan.transcribe.audio

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anooprajan.transcribe.MainActivity
import com.anooprajan.transcribe.R
import com.anooprajan.transcribe.TranscribeApp
import com.anooprajan.transcribe.data.Recordings
import com.anooprajan.transcribe.data.Status
import com.anooprajan.transcribe.pipeline.ProcessingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

data class LiveRecording(val id: String, val elapsedSec: Long, val level: Float)

object RecorderState {
    internal val _live = MutableStateFlow<LiveRecording?>(null)
    val live: StateFlow<LiveRecording?> = _live
}

/** Records 16 kHz mono PCM straight to WAV in a foreground service so the screen can be off. */
class RecorderService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked by the UI before starting the service
    private fun start() {
        val recordings = Recordings.get(this)
        val rec = recordings.create()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Recording in progress"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcribe:record")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        val minBuf = AudioRecord.getMinBufferSize(
            Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Wav.SAMPLE_RATE),
        )
        running = true
        RecorderState._live.value = LiveRecording(rec.id, 0, 0f)
        thread = Thread({ loop(record, recordings.audio(rec.id), rec.id) }, "recorder").apply { start() }
    }

    private fun loop(record: AudioRecord, file: File, id: String) {
        val buf = ShortArray(Wav.SAMPLE_RATE / 10)
        val bytes = ByteArray(buf.size * 2)
        var dataBytes = 0L
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            Wav.writeHeader(raf, 0)
            raf.seek(44)
            record.startRecording()
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        bytes[2 * i] = (s and 0xff).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                        peak = maxOf(peak, abs(s))
                    }
                    raf.write(bytes, 0, n * 2)
                    dataBytes += n * 2
                    RecorderState._live.value =
                        LiveRecording(id, dataBytes / 2 / Wav.SAMPLE_RATE, peak / 32768f)
                }
            } finally {
                record.stop()
                record.release()
                Wav.writeHeader(raf, dataBytes)
            }
        }
    }

    private fun stop() {
        val id = RecorderState.live.value?.id
        running = false
        thread?.join(3000)
        thread = null
        RecorderState._live.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        if (id != null) {
            val recordings = Recordings.get(this)
            recordings.update(id) {
                it.copy(status = Status.RECORDED, durationSec = Wav.durationSeconds(recordings.audio(id)))
            }
            // Can be refused if the OS considers the app backgrounded; the user can still tap Transcribe.
            runCatching { ProcessingService.process(this, id) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stop()
        super.onDestroy()
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, TranscribeApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transcribe")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0, "Stop",
                PendingIntent.getService(
                    this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        fun start(context: Context) = context.startForegroundService(
            Intent(context, RecorderService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) = context.startService(
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP),
        )
    }
}
