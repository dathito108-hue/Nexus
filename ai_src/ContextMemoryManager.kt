package com.hypernexus.nit.evolution

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.hypernexus.nit.engine.LlamaEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

/**
 * P6.1: bounded hybrid semantic memory.
 * New memories use real on-device llama.cpp hidden-state embeddings when the model is loaded,
 * with P6 lexical/hashed features retained as a deterministic fallback.
 */
class ContextMemoryManager(context: Context) : SQLiteOpenHelper(context, "hyper_nexus_memory.db", null, 4) {
    companion object {
        const val MAX_SLIDING_WINDOW_TURNS = 10
        private const val TAG = "ContextMemoryManager"
        private const val TABLE = "episodic_memory"
        private const val FEATURE_DIM = 256
        private const val NEURAL_DIMS = 384
        private const val EMBEDDING_MODEL = "llama_hidden_last_v1_384"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, role TEXT NOT NULL, " +
                "content TEXT NOT NULL, summary_tag TEXT NOT NULL, embedding_blob BLOB, embedding_model TEXT)"
        )
        db.execSQL("CREATE INDEX idx_memory_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_memory_role ON $TABLE(role)")
        db.execSQL("CREATE INDEX idx_memory_tag ON $TABLE(summary_tag)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_timestamp ON $TABLE(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_role ON $TABLE(role)")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_tag ON $TABLE(summary_tag)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN embedding_blob BLOB")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN embedding_model TEXT")
        }
    }

    fun saveInteraction(role: String, content: String, tag: String = "GENERAL") {
        val clean = content.trim()
        if (clean.isEmpty()) return
        try {
            val embedding = neuralEmbedding(clean)
            writableDatabase.insert(TABLE, null, ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("role", role.take(32))
                put("content", clean.take(8000))
                put("summary_tag", tag.take(64).uppercase(Locale.ROOT))
                if (embedding != null) {
                    put("embedding_blob", encodeEmbedding(embedding))
                    put("embedding_model", EMBEDDING_MODEL)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "save memory failed", e)
        }
    }

    fun getRecentSlidingWindowContext(limit: Int = MAX_SLIDING_WINDOW_TURNS): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            readableDatabase.query(
                TABLE, arrayOf("role", "content"), null, null, null, null,
                "id DESC", limit.coerceIn(1, 20).toString()
            ).use { c ->
                while (c.moveToNext()) result.add(c.getString(0) to c.getString(1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "read memory failed", e)
        }
        return result.asReversed()
    }

    /** Hybrid retrieval: neural cosine when persisted embeddings exist, otherwise P6 local features. */
    fun retrieveRelevantMemories(query: String, limit: Int = 6): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        data class Candidate(val score: Double, val id: Long, val text: String)
        data class Backfill(val id: Long, val text: String)

        val qTokens = tokenize(q)
        val qHashVec = featureVector(q)
        val qNeural = neuralEmbedding(q)
        val candidates = mutableListOf<Candidate>()
        val backfill = mutableListOf<Backfill>()

        try {
            readableDatabase.query(
                TABLE,
                arrayOf("id", "role", "content", "summary_tag", "timestamp", "embedding_blob", "embedding_model"),
                null, null, null, null, "id DESC", "180"
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val role = c.getString(1)
                    val content = c.getString(2)
                    val tag = c.getString(3)
                    val text = "$role: $content"
                    val n = normalize(text)

                    var lexical = 0.0
                    for (term in qTokens) {
                        if (n.contains(term)) lexical += if (term.length >= 6) 1.8 else 0.8
                    }
                    if (tag != "GENERAL" && normalize(q).contains(tag.lowercase(Locale.ROOT))) lexical += 1.0

                    val stored = if (!c.isNull(5) && c.getString(6) == EMBEDDING_MODEL) {
                        decodeEmbedding(c.getBlob(5))
                    } else null

                    val semantic = when {
                        qNeural != null && stored != null && stored.size == qNeural.size ->
                            cosineFloat(qNeural, stored) * 3.4
                        else -> cosine(qHashVec, featureVector(text)) * 2.2
                    }

                    if (qNeural != null && stored == null && backfill.size < 2) {
                        backfill.add(Backfill(id, content.take(8000)))
                    }

                    val ageHours = (
                        (System.currentTimeMillis() - c.getLong(4)).coerceAtLeast(0L) / 3600000.0
                    )
                    val recency = 1.0 / (1.0 + ageHours / 72.0)
                    val score = lexical * 0.40 + semantic + recency * 0.18
                    if (score > 0.18) candidates.add(Candidate(score, id, text.take(1600)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "retrieve semantic memory failed", e)
        }

        // Gradually upgrades legacy P6 rows without a large one-shot mobile cost.
        if (qNeural != null) {
            for (item in backfill) {
                val emb = neuralEmbedding(item.text) ?: continue
                try {
                    writableDatabase.update(TABLE, ContentValues().apply {
                        put("embedding_blob", encodeEmbedding(emb))
                        put("embedding_model", EMBEDDING_MODEL)
                    }, "id = ?", arrayOf(item.id.toString()))
                } catch (e: Exception) {
                    Log.w(TAG, "embedding backfill failed", e)
                }
            }
        }

        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.id })
            .take(limit.coerceIn(1, 8))
            .map { it.text }
    }

    fun buildMemoryContext(query: String, maxChars: Int = 5000): String {
        val seen = LinkedHashSet<String>()
        for (item in getRecentSlidingWindowContext(6)) seen.add(item.first + ": " + item.second)
        for (item in retrieveRelevantMemories(query, 8)) seen.add(item)
        return seen.take(12).joinToString("\n") { "- " + it }.take(maxChars)
    }

    fun pruneOldMemories(days: Int = 30) {
        try {
            val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * 86400000L
            writableDatabase.delete(TABLE, "timestamp < ?", arrayOf(cutoff.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "prune memory failed", e)
        }
    }

    private fun neuralEmbedding(text: String): FloatArray? = try {
            if (!LlamaEngine.isModelLoaded()) null
            else LlamaEngine.embedText(text.take(2400), NEURAL_DIMS).takeIf { it.size >= 32 }
        } catch (t: Throwable) {
            Log.w(TAG, "neural embedding unavailable; using local fallback", t)
            null
        }

    private fun encodeEmbedding(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun decodeEmbedding(bytes: ByteArray): FloatArray? = try {
            if (bytes.isEmpty() || bytes.size % 4 != 0) null
            else {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bytes.size / 4) { buffer.float }
            }
        } catch (_: Exception) {
            null
        }

    private fun cosineFloat(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            aa += av * av
            bb += bv * bv
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun tokenize(text: String): List<String> =
        normalize(text).split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 2 }.distinct().take(48)

    private fun featureVector(text: String): DoubleArray {
        val v = DoubleArray(FEATURE_DIM)
        val n = normalize(text)
        val tokens = tokenize(n)
        for (token in tokens) {
            val h = stableHash(token)
            v[h and (FEATURE_DIM - 1)] += 1.0 + (token.length.coerceAtMost(12) / 12.0)
        }
        val padded = " $n "
        for (i in 0 until (padded.length - 2).coerceAtLeast(0)) {
            val gram = padded.substring(i, i + 3)
            v[stableHash(gram) and (FEATURE_DIM - 1)] += 0.35
        }
        return v
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun stableHash(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 16777619
        }
        return h
    }

    private fun normalize(text: String) =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
