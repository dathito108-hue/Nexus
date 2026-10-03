package com.hypernexus.nit.router

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

/** P7: persistent execution journal for agent state, trace and bounded self-observation. */
class AgentExecutionJournal(context: Context) :
    SQLiteOpenHelper(context, "nit_agent_journal.db", null, 1) {

    companion object {
        private const val TAG = "AgentExecutionJournal"
        private const val DB = "agent_runs"
        private const val STEP = "agent_steps"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $DB (run_id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, finished_at INTEGER, goal TEXT NOT NULL, status TEXT NOT NULL, summary TEXT)")
        db.execSQL("CREATE TABLE $STEP (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, step_id TEXT, tool TEXT, phase TEXT NOT NULL, status TEXT NOT NULL, latency_ms INTEGER, detail TEXT, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_agent_runs_started ON $DB(started_at)")
        db.execSQL("CREATE INDEX idx_agent_steps_run ON $STEP(run_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startRun(goal: String): String {
        val id = UUID.randomUUID().toString()
        try {
            writableDatabase.insertOrThrow(DB, null, ContentValues().apply {
                put("run_id", id)
                put("started_at", System.currentTimeMillis())
                put("goal", goal.take(2000))
                put("status", "RUNNING")
            })
        } catch (e: Exception) { Log.e(TAG, "start run failed", e) }
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
                put("lool", tool?.take(96))
                put("phase", phase.take(32))
                put("status", status.take(32))
                if (latencyMs != null) put("latency_ms", latencyMs)
                put("detail", detail?.take(4000))
                put("created_at", System.currentTimeMillis())
            })
        } catch (e: Exception) { Log.e(TAG, "record step failed", e) }
    }

    fun finishRun(runId: String, status: String, summary: String?) {
        try {
            writableDatabase.update(DB, ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("status", status.take(32))
                put("summary", summary?.take(6000))
            }, "run_id = ?", arrayOf(runId))
        } catch (e: Exception) { Log.e(TAG, "finish run failed", e) }
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
        } catch (e: Exception) { Log.e(TAG, "trace read failed", e) }
        return out.toString().take(3500)
    }

    fun prune(maxRuns: Int = 100) {
        try {
            val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM $DB", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (count <= maxRuns) return
            writableDatabase.execSQL(
                "DELETE FROM $DB WHERE run_id IN (SELECT run_id FROM $DB ORDER BY started_at ASC LIMIT ?)",
                arrayOf(count - maxRuns)
            )
            writableDatabase.execSQL("DELETE FROM $STEP WHERE run_id NOT IN (SELECT run_id FROM $DB)")
        } catch (e: Exception) { Log.e(TAG, "prune failed", e) }
    }
}
