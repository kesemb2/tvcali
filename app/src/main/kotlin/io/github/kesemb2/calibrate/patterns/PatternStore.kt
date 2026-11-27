package io.github.kesemb2.calibrate.patterns

import android.content.Context
import android.util.Log
import io.github.kesemb2.calibrate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The test pattern videos: downloaded once from the GitHub release `patterns-v<N>` (N fixed at build time), checked
 * against the SHA-256 in its manifest, and kept in the app's private storage (no storage permission). Older pattern
 * versions are deleted when a new one is complete.
 */
class PatternStore(context: Context) {

    sealed interface State {
        data object Idle : State
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : State
        data class Ready(val manifest: Manifest) : State
        data class Failed(val message: String) : State
    }

    private companion object {
        const val TAG = "Calibrate"
    }

    private val root = File(context.filesDir, "patterns")
    private val dir = File(root, "v${BuildConfig.PATTERNS_VERSION}")
    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun file(p: PatternFile): File = File(dir, p.file)

    /** Makes every pattern available; safe to call repeatedly (a running or finished download is reused). */
    suspend fun ensure() = mutex.withLock {
        if (_state.value is State.Ready) return@withLock
        _state.value = State.Downloading(0, 0)
        _state.value = try {
            withContext(Dispatchers.IO) { download() }
        } catch (e: Exception) {
            Log.i(TAG, "patterns: download failed", e)
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
        Log.i(TAG, "patterns: ${_state.value.let { if (it is State.Ready) "ready, ${it.manifest.patterns.size} files" else it.toString() }}")
    }

    private fun download(): State {
        dir.mkdirs()
        val manifestFile = File(dir, "manifest.json")
        Log.i(TAG, "patterns: v${BuildConfig.PATTERNS_VERSION} from ${BuildConfig.PATTERNS_URL}")
        val manifest = if (manifestFile.exists()) {
            Manifest.parse(manifestFile.readText())
        } else {
            val text = String(fetch("manifest.json"), Charsets.UTF_8)
            Manifest.parse(text).also { manifestFile.writeText(text) }
        }
        val total = manifest.patterns.sumOf { it.size }
        var done = manifest.patterns.filter { isComplete(it) }.sumOf { it.size }
        for (p in manifest.patterns) {
            if (isComplete(p)) continue
            Log.i(TAG, "patterns: downloading ${p.file} (${p.size} bytes)")
            val part = File(dir, p.file + ".part")
            fetchTo(p.file, part) { bytes -> _state.value = State.Downloading(done + bytes, total) }
            val actual = sha256(part)
            if (!actual.equals(p.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("${p.file}: checksum mismatch")
            }
            if (!part.renameTo(file(p))) throw IOException("${p.file}: cannot save")
            done += p.size
            _state.value = State.Downloading(done, total)
        }
        root.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
        return State.Ready(manifest)
    }

    /** A file is trusted once it was renamed from .part after its checksum matched; the size guards truncation. */
    private fun isComplete(p: PatternFile) = file(p).let { it.exists() && it.length() == p.size }

    private fun open(name: String): HttpURLConnection {
        val conn = URL(BuildConfig.PATTERNS_URL + name).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true // GitHub redirects release assets to its download host.
        val code = conn.responseCode
        Log.i(TAG, "patterns: GET $name -> HTTP $code (${conn.url.host})")
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("$name: HTTP $code")
        }
        return conn
    }

    private fun fetch(name: String): ByteArray = open(name).let { c -> try { c.inputStream.readBytes() } finally { c.disconnect() } }

    private fun fetchTo(name: String, target: File, progress: (Long) -> Unit) {
        val conn = open(name)
        try {
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
