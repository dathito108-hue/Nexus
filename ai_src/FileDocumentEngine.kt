package com.hypernexus.nit.file

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * CAP4: real local File/Document capability.
 * Files are sandboxed under filesDir/nexus_documents.
 */
object FileDocumentEngine {
    private const val ROOT_NAME = "nexus_documents"
    private const val MAX_READ_CHARS = 200_000
    private const val MAX_WRITE_CHARS = 200_000

    private fun root(context: Context): File =
        File(context.filesDir, ROOT_NAME).apply { mkdirs() }.canonicalFile

    private fun resolve(context: Context, relativePath: String): File {
        val root = root(context)
        val clean = relativePath.trim().removePrefix("/")
        val candidate = if (clean.isBlank() || clean == ".") root else File(root, clean).canonicalFile
        val rootPrefix = root.path + File.separator
        if (candidate != root && !candidate.path.startsWith(rootPrefix)) {
            throw SecurityException("Đường dẫn vượt khỏi vùng tài liệu của Nít.")
        }
        return candidate
    }

    fun read(context: Context, relativePath: String): String {
        val file = resolve(context, relativePath)
        if (!file.exists()) return "LỖI: không tìm thấy file " + relativePath.trim()
        if (!file.isFile) return "LỖI: đường dẫn không phải file."
        val text = file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val bounded = text.take(MAX_READ_CHARS)
        return buildString {
            append("FILE_READ path=").append(relativePath.trim()).append('\n')
            append(bounded)
            if (text.length > bounded.length) append("\n[TRUNCATED]")
        }
    }

    fun write(context: Context, relativePath: String, content: String, append: Boolean): String {
        if (content.length > MAX_WRITE_CHARS) return "LỖI: nội dung file vượt giới hạn."
        val file = resolve(context, relativePath)
        if (file == root(context)) return "LỖI: không thể ghi đè thư mục gốc."
        file.parentFile?.mkdirs()
        if (append) file.appendText(content, StandardCharsets.UTF_8)
        else file.writeText(content, StandardCharsets.UTF_8)
        val mode = if (append) "ghi thêm" else "ghi"
        return "Đã " + mode + " " + file.relativeTo(root(context)).path + " (" + file.length() + " bytes)."
    }

    fun list(context: Context, relativePath: String): String {
        val dir = resolve(context, relativePath)
        if (!dir.exists()) return "LỖI: không tìm thấy thư mục " + relativePath.trim()
        if (!dir.isDirectory) return "LỖI: đường dẫn không phải thư mục."
        val entries = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        return buildString {
            append("FILE_LIST path=").append(relativePath.trim()).append('\n')
            if (entries.isEmpty()) append("(trống)")
            else entries.forEach { entry ->
                append(if (entry.isDirectory) "[DIR] " else "[FILE] ")
                append(entry.name)
                if (entry.isFile) append(" (").append(entry.length()).append(" bytes)")
                append('\n')
            }
        }.trimEnd()
    }

    fun info(context: Context, relativePath: String): String {
        val file = resolve(context, relativePath)
        if (!file.exists()) return "LỖI: không tìm thấy " + relativePath.trim()
        return buildString {
            append("FILE_INFO path=").append(relativePath.trim()).append('\n')
            append("type=").append(if (file.isDirectory) "directory" else "file").append('\n')
            append("size_bytes=").append(if (file.isFile) file.length() else 0L).append('\n')
            append("modified_epoch_ms=").append(file.lastModified())
        }
    }
}
