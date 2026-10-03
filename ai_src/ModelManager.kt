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
    const val DEFAULT_MODEL_NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    private const val MODEL_DOWNLOAD_URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    fun getModelFile(context: Context): File = File(context.filesDir, DEFAULT_MODEL_NAME)
    fun isModelReady(context: Context): Boolean {
        val file = getModelFile(context)
        return file.isFile && file.length() > 100L * 1024L * 1024L && hasGgufMagic(file)
    }
    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }
    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = getModelFile(context)
        if (isModelReady(context)) { onProgress(100); return@withContext true }
        val partial = File(target.parentFile, target.name + ".part")
        if (partial.exists()) partial.delete()
        try {
            val connection = (URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
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
            if (!hasGgufMagic(partial) || partial.length() < 100L * 1024L * 1024L)
                throw IllegalStateException("Downloaded file is not a valid GGUF model")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IllegalStateException("Cannot atomically install model")
            onProgress(100)
            Log.i(TAG, "GGUF model ready: " + target.length() + " bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            partial.delete()
            false
        }
    }
}
