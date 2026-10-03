package com.hypernexus.nit.evolution

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.Locale

class ContextMemoryManager(context: Context) : SQLiteOpenHelper(context, "hyper_nexus_memory.db", null, 2) {
    companion object {
        const val MAX_SLIDING_WINDOW_TURNS = 10
        private const val TAG = "ContextMemoryManager"
        private const val TABLE = "episodic_memory"
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, summary_tag TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_memory_timestamp ON " + TABLE + "(timestamp)")
        db.execSQL("CREATE INDEX idx_memory_role ON " + TABLE + "(role)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_timestamp ON " + TABLE + "(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_role ON " + TABLE + "(role)")
        }
    }
    fun saveInteraction(role: String, content: String, tag: String = "GENERAL") {
        val clean = content.trim()
        if (clean.isEmpty()) return
        try {
            writableDatabase.insert(TABLE, null, ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("role", role)
                put("content", clean.take(8000))
                put("summary_tag", tag)
            })
        } catch (e: Exception) { Log.e(TAG, "save memory failed", e) }
    }
    fun getRecentSlidingWindowContext(limit: Int = MAX_SLIDING_WINDOW_TURNS): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            readableDatabase.query(TABLE, arrayOf("role", "content"), null, null, null, null,
                "id DESC", limit.coerceIn(1, 20).toString()).use { c ->
                while (c.moveToNext()) result.add(c.getString(0) to c.getString(1))
            }
        } catch (e: Exception) { Log.e(TAG, "read memory failed", e) }
        return result.asReversed()
    }
    fun retrieveRelevantMemories(query: String, limit: Int = 6): List<String> {
        val terms = normalize(query).split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 3 }.distinct().take(24)
        if (terms.isEmpty()) return emptyList()
        data class C(val score: Int, val id: Long, val text: String)
        val list = mutableListOf<C>()
        try {
            readableDatabase.query(TABLE, arrayOf("id", "role", "content"), null, null, null, null, "id DESC", "120").use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(1) + ": " + c.getString(2)
                    val n = normalize(text)
                    var score = 0
                    for (term in terms) if (n.contains(term)) score += if (term.length >= 5) 2 else 1
                    if (score > 0) list.add(C(score, c.getLong(0), text.take(1400)))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "retrieve memory failed", e) }
        return list.sortedWith(compareByDescending<C> { it.score }.thenByDescending { it.id })
            .take(limit.coerceIn(1, 6)).map { it.text }
    }
    fun buildMemoryContext(query: String, maxChars: Int = 5000): String {
        val seen = LinkedHashSet<String>()
        for (item in getRecentSlidingWindowContext(6)) seen.add(item.first + ": " + item.second)
        for (item in retrieveRelevantMemories(query)) seen.add(item)
        return seen.take(10).joinToString("\n") { "- " + it }.take(maxChars)
    }
    fun pruneOldMemories(days: Int = 30) {
        try {
            val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * 86400000L
            writableDatabase.delete(TABLE, "timestamp < ?", arrayOf(cutoff.toString()))
        } catch (e: Exception) { Log.e(TAG, "prune memory failed", e) }
    }
    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
