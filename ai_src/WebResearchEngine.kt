package com.hypernexus.nit.web

import android.util.Log
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** CAP3: lightweight on-device web research. Search results are untrusted evidence. */
object WebResearchEngine {
    private const val TAG = "WebResearchEngine"
    private const val TIMEOUT_MS = 9000

    fun search(query: String): String {
        val q = query.trim().take(240)
        if (q.isBlank()) return "LỖI: truy vấn web trống."
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val connection = URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Hyper-Nexus-Nit/2.0 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) return "LỖI: web trả HTTP $code."
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val marker = "result__a"
            val out = StringBuilder("WEB_RESEARCH query=").append(q).append('\n')
            var cursor = 0
            var count = 0
            while (count < 5) {
                val pos = html.indexOf(marker, cursor, ignoreCase = true)
                if (pos < 0) break
                val hrefPos = html.lastIndexOf("href=", pos, ignoreCase = true)
                val hrefStart = if (hrefPos >= 0) hrefPos + 6 else -1
                val hrefEnd = if (hrefStart >= 0) html.indexOf('"', hrefStart + 1) else -1
                val close = html.indexOf("</a>", pos, ignoreCase = true)
                if (hrefStart < 0 || hrefEnd < 0 || close < 0) break
                val url = decodeHtml(html.substring(hrefStart + 1, hrefEnd))
                val title = stripTags(decodeHtml(html.substring(pos + marker.length + 2, close))).trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    count++
                    out.append(count).append(". ").append(title.take(220)).append('\n')
                    out.append("URL: ").append(url.take(500)).append('\n')
                }
                cursor = close + 4
            }
            if (count == 0) "Không tìm thấy kết quả web cho: $q"
            else out.append("Lưu ý: dữ liệu web là nguồn không tin cậy; cần kiểm chứng trước quyết định quan trọng.").toString()
        } catch (e: Exception) {
            Log.e(TAG, "web research failed", e)
            "LỖI: không thể truy cập web: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(value: String): String =
        value.replace("&amp;", "&").replace("&quot;", """).replace("&#x27;", "'")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
