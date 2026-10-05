package com.hypernexus.nit.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val TAG = "ModelManager"
    const val COMPACT_MODEL_NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    const val QUALITY_MODEL_NAME = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
    private const val COMPACT_URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    private const val QUALITY_URL = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
    private const val MIN_GGUF_BYTES = 100L * 1024L * 1024L
    private const val QUALITY_RAM_BYTES = 8L * 1024L * 1024L * 1024L
    private const val QUALITY_AVAIL_RAM_BYTES = 2750L * 1024L * 1024L
    private const val QUALITY_FREE_BYTES = 3L * 1024L * 1024L * 1024L

    /**
     * Context-free callers must stay deterministic. Runtime selection should use
     * recommendedProfile(context), which accounts for current memory pressure.
     */
    val DEFAULT_MODEL_NAME: String get() = COMPACT_MODEL_NAME

    data class ModelProfile(val id: String, val fileName: String, val url: String, val quality: String)

    fun recommendedProfile(context: Context? = null): ModelProfile {
        if (context != null && canUseQualityModel(context)) {
            return ModelProfile("qwen25-3b-q4km", QUALITY_MODEL_NAME, QUALITY_URL, "HIGH")
        }
        return ModelProfile("qwen25-15b-q4km", COMPACT_MODEL_NAME, COMPACT_URL, "COMPACT")
    }

    fun canUseQualityModel(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        // Total RAM alone is not enough: a 3B Q4 model needs headroom for the
        // llama.cpp context, Android services, and the app UI while generating.
        !info.lowMemory &&
            info.totalMem >= QUALITY_RAM_BYTES &&
            info.availMem >= QUALITY_AVAIL_RAM_BYTES &&
            stat.availableBytes >= QUALITY_FREE_BYTES
    }
    fun getModelFile(context: Context): File = File(context.filesDir, recommendedProfile(context).fileName)
    fun isModelReady(context: Context): Boolean {
        val file = getModelFile(context)
        return file.isFile && file.length() > MIN_GGUF_BYTES && hasGgufMagic(file)
    }
    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }
    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val profile = recommendedProfile(context)
        val target = File(context.filesDir, profile.fileName)
        if (isModelReady(context)) { onProgress(100); return@withContext true }
        val partial = File(target.parentFile, target.name + ".part")
        if (partial.exists()) partial.delete()
        try {
            val connection = (URL(profile.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
            val length = connection.contentLengthLong
            BufferedInputStream(connection.inputStream, 1024 * 1024).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    var lastProgress = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        if (length > 0) {
                            val progress = ((total * 100L) / length).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) { lastProgress = progress; onProgress(progress) }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (!hasGgufMagic(partial) || partial.length() < MIN_GGUF_BYTES)
                throw IllegalStateException("Downloaded file is not a valid GGUF model")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IllegalStateException("Cannot atomically install model")
            onProgress(100)
            Log.i(TAG, "GGUF model ready: " + profile.id + ", " + target.length() + " bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed for " + profile.id, e)
            partial.delete()
            false
        }
    }
}
