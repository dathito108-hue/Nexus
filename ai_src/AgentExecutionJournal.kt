package com.hypernexus.nit.router

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * P7 + P9: persistent execution journal plus bounded skill telemetry.
 * P9 learns execution reliability only from observed local outcomes; it never grants new permissions.
 */
class AgentExecutionJournal(context: Context) :
    SQLiteOpenHelper(context, "nit_agent_journal.db", null, 2) {

    companion object {
        private const val TAG = "AgentExecutionJournal"
        private const val DB = "agent_runs"
        private const val STEP = "agent_steps"
        private const val STATS = "skill_stats"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $DB (run_id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, finished_at INTEGER, goal TEXT NOT NULL, status TEXT NOT NULL, summary TEXT)")
        db.execSQL("CREATE TABLE $STEP (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, step_id TEXT, tool TEXT, phase TEXT NOT NULL, status TEXT NOT NULL, latency_ms INTEGER, detail TEXT, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $STATS (tool TEXT PRIMARY KEY, success_count INTEGER NOT NULL DEFAULT 0, failure_count INTEGER NOT NULL DEFAULT 0, total_latency_ms INTEGER NOT NULL DEFAULT 0, last_used_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_agent_runs_started ON $DB(started_at)")
        db.execSQL("CREATE INDEX idx_agent_steps_run ON $STEP(run_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $STATS (tool TEXT PRIMARY KEY, success_count INTEGER NOT NULL DEFAULT 0, failure_count INTEGER NOT NULL DEFAULT 0, total_latency_ms INTEGER NOT NULL DEFAULT 0, last_used_at INTEGER NOT NULL DEFAULT 0)")
        }
    }

    fun startRun(goal: String): String {
        val id = UUID.randomUUID().toString()
        try {
            writableDatabase.insertOrThrow(DB, null, ContentValues().apply {
                put("run_id", id)
                put("started_at", System.currentTimeMillis())
                put("goal", goal.take(2000))
                put("status", "RUNNING")
            })
        } catch (e: Exception) {
            Log.e(TAG, "start run failed", e)
        }
        return id
    }

    fun recordStep(
        runId: String,
        stepId: String?,
        tool: String?,
        phase: String,
        status: String,
        latencyMs: Long? = null,
        detail: String? = null
    ) {
        try {
            writableDatabase.insert(STEP, null, ContentValues().apply {
                put("run_id", runId)
                put("step_id", stepId?.take(64))
                put("tool", tool?.take(96))
                put("phase", phase.take(32))
                put("status", status.take(32))
                if (latencyMs != null) put("latency_ms", latencyMs)
                put("detail", detail?.take(4000))
                put("created_at", System.currentTimeMillis())
            })
        } catch (e: Exception) {
            Log.e(TAG, "record step failed", e)
        }
    }

    fun recordSkillOutcome(tool: String, success: Boolean, latencyMs: Long) {
        val id = tool.take(96)
        try {
            val db = writableDatabase
            db.execSQL(
                "INSERT OR IGNORE INTO $STATS(tool, success_count, failure_count, total_latency_ms, last_used_at) VALUES(?,0,0,0,0)",
                arrayOf(id)
            )
            val successDelta = if (success) 1 else 0
            val failureDelta = if (success) 0 else 1
            db.execSQL(
                "UPDATE $STATS SET success_count = success_count + ?, failure_count = failure_count + ?, total_latency_ms = total_latency_ms + ?, last_used_at = ? WHERE tool = ?",
                arrayOf(successDelta, failureDelta, latencyMs.coerceAtLeast(0L), System.currentTimeMillis(), id)
            )
        } catch (e: Exception) {
            Log.e(TAG, "skill outcome failed", e)
        }
    }

    /** Compact P9 signal for planning. Statistics inform tool choice but never override permission policy. */
    fun skillReliabilityContext(): String {
        val out = StringBuilder()
        try {
            readableDatabase.rawQuery(
                "SELECT tool, success_count, failure_count, total_latency_ms FROM $STATS ORDER BY last_used_at DESC LIMIT 12",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val tool = c.getString(0)
                    val success = c.getInt(1)
                    val failure = c.getInt(2)
                    val total = success + failure
                    if (total <= 0) continue
                    val rate = success.toDouble() / total.toDouble()
                    val avgLatency = c.getLong(3) / total
                    out.append(tool)
                        .append(": success=")
                        .append(String.format(Locale.US, "%.2f", rate))
                        .append(", samples=").append(total)
                        .append(", avg_ms=").append(avgLatency)
                        .append('\n')
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "skill stats read failed", e)
        }
        return out.toString().take(2200)
    }

    fun finishRun(runId: String, status: String, summary: String?) {
        try {
            writableDatabase.update(DB, ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("status", status.take(32))
                put("summary", summary?.take(6000))
            }, "run_id = ?", arrayOf(runId))
        } catch (e: Exception) {
            Log.e(TAG, "finish run failed", e)
        }
    }

    fun recentTrace(goal: String, limit: Int = 3): String {
        val out = StringBuilder()
        try {
            readableDatabase.rawQuery(
                "SELECT run_id, status, summary FROM $DB WHERE goal LIKE ? ORDER BY started_at DESC LIMIT ?",
                arrayOf("%${goal.take(80).replace("%", "")}%", limit.coerceIn(1, 5).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.append("run=").append(c.getString(0))
                        .append(" status=").append(c.getString(1))
                        .append(" summary=").append(c.getString(2) ?: "")
                        .append('\n')
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "trace read failed", e)
        }
        return out.toString().take(3500)
    }

    fun prune(maxRuns: Int = 100) {
        try {
            val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM $DB", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (count <= maxRuns) return
            writableDatabase.execSQL(
                "DELETE FROM $DB WHERE run_id IN (SELECT run_id FROM $DB ORDER BY started_at ASC LIMIT ?)",
                arrayOf(count - maxRuns)
            )
            writableDatabase.execSQL("DELETE FROM $STEP WHERE run_id NOT IN (SELECT run_id FROM $DB)")
        } catch (e: Exception) {
            Log.e(TAG, "prune failed", e)
        }
    }
}
