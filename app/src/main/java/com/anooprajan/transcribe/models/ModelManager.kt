package com.anooprajan.transcribe.models

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

class ModelManager(context: Context) {
    private val root = File(context.filesDir, "models")

    fun dir(spec: ModelSpec) = File(root, spec.id)

    fun isInstalled(spec: ModelSpec) = File(dir(spec), MARKER).exists()

    fun delete(spec: ModelSpec) {
        dir(spec).deleteRecursively()
    }

    /** Removes models dropped from the catalog and leftover partial downloads. Call at process start. */
    fun pruneUnknown() {
        val known = ModelCatalog.all.map { it.id }.toSet()
        root.listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
    }

    /**
     * Downloads [spec] into its model directory. Reports progress as downloaded/total bytes
     * (total is -1 when the server does not send a length). Safe to call again after a failure.
     */
    fun download(spec: ModelSpec, onProgress: (downloaded: Long, total: Long) -> Unit) {
        val target = dir(spec)
        val staging = File(root, "${spec.id}.partial")
        staging.deleteRecursively()
        staging.mkdirs()

        if (spec.files.isNotEmpty()) {
            downloadFiles(spec, staging, onProgress)
        } else {
            downloadUrl(spec, staging, onProgress)
        }

        require(staging.walkTopDown().any { it.isFile }) { "Nothing was downloaded for ${spec.id}" }
        File(staging, MARKER).writeText(spec.url.ifEmpty { spec.files.joinToString { it.url } })
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Could not move model into place" }
    }

    private fun downloadFiles(spec: ModelSpec, staging: File, onProgress: (Long, Long) -> Unit) {
        var done = 0L
        for (f in spec.files) {
            val conn = openFollowingRedirects(f.url)
            try {
                val total = conn.contentLengthLong
                val base = done
                val digest = MessageDigest.getInstance("SHA-256")
                val input = DigestInputStream(
                    CountingInputStream(BufferedInputStream(conn.inputStream, 1 shl 16)) {
                        // Progress is per file; good enough for one large file plus small extras.
                        onProgress(base + it, if (total > 0) base + total else -1)
                    },
                    digest,
                )
                input.use { File(staging, f.name).outputStream().use { out -> it.copyTo(out, 1 shl 16) } }
                done += File(staging, f.name).length()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (f.sha256 != null && !actual.equals(f.sha256, ignoreCase = true)) {
                    staging.deleteRecursively()
                    error("Checksum mismatch for ${f.name}: the published model changed. Update the app.")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun downloadUrl(spec: ModelSpec, staging: File, onProgress: (Long, Long) -> Unit) {
        val conn = openFollowingRedirects(spec.url)
        try {
            val total = conn.contentLengthLong
            val counting = CountingInputStream(BufferedInputStream(conn.inputStream, 1 shl 16)) {
                onProgress(it, total)
            }
            counting.use { input ->
                if (spec.isArchive) {
                    extractTarBz2(input, staging, spec.keep)
                } else {
                    val name = spec.url.substringAfterLast('/')
                    File(staging, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTarBz2(input: InputStream, dest: File, keep: (String) -> Boolean) {
        TarArchiveInputStream(BZip2CompressorInputStream(input, true)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                // Flatten: models are resolved by file name, not path.
                val name = entry.name.substringAfterLast('/')
                if (name.isEmpty() || name.startsWith(".") || !keep(name)) continue
                File(dest, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
            }
            when (conn.responseCode) {
                in 200..299 -> return conn
                in 300..399 -> {
                    val next = conn.getHeaderField("Location")
                        ?: error("Redirect without Location from $current")
                    conn.disconnect()
                    current = URL(URL(current), next).toString()
                }
                else -> {
                    val code = conn.responseCode
                    conn.disconnect()
                    error("HTTP $code for $current")
                }
            }
        }
        error("Too many redirects for $url")
    }

    private class CountingInputStream(
        private val inner: InputStream,
        private val onCount: (Long) -> Unit,
    ) : InputStream() {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        override fun close() = inner.close()

        private fun advance(n: Long) {
            count += n
            if (count - lastReported >= 512 * 1024) {
                lastReported = count
                onCount(count)
            }
        }
    }

    companion object {
        private const val MARKER = ".installed"
    }
}
