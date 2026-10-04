package com.hypernexus.nit.router
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import java.util.UUID
class AgentLifecycle(context: Context) : SQLiteOpenHelper(context, "nit_lifecycle.db", null, 1) {
    enum class State { CREATED, PLANNING, READY, RUNNING, WAITING, VERIFYING, REPAIRING, COMPLETED, FAILED, STOPPED, PAUSED }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY, goal TEXT NOT NULL, state TEXT NOT NULL, cursor INTEGER NOT NULL, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deadline_ms INTEGER NOT NULL, budget_steps INTEGER NOT NULL, last_error TEXT)")
        db.execSQL("CREATE INDEX idx_runs_updated ON runs(updated_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun createRun(goal: String, deadlineMs: Long = System.currentTimeMillis() + 120_000, budgetSteps: Int = 8): String {
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("runs", null, ContentValues().apply {
            put("run_id", id); put("goal", goal.take(2000)); put("state", State.CREATED.name); put("cursor", 0)
            put("started_at", now); put("updated_at", now); put("deadline_ms", deadlineMs); put("budget_steps", budgetSteps.coerceIn(1, 16))
        }); return id
    }
    fun transition(runId: String, state: State, cursor: Int? = null, error: String? = null) {
        writableDatabase.update("runs", ContentValues().apply {
            put("state", state.name); put("updated_at", System.currentTimeMillis())
            if (cursor != null) put("cursor", cursor); if (error != null) put("last_error", error.take(1000))
        }, "run_id=?", arrayOf(runId))
    }
    fun checkpoint(runId: String, cursor: Int, state: State = State.WAITING) = transition(runId, state, cursor)
    fun resume(runId: String): ResumePoint? {
        readableDatabase.rawQuery("SELECT state,cursor,deadline_ms,budget_steps,goal FROM runs WHERE run_id=?", arrayOf(runId)).use { c ->
            if (!c.moveToFirst()) return null
            return ResumePoint(runId, State.valueOf(c.getString(0)), c.getInt(1), c.getLong(2), c.getInt(3), c.getString(4))
        }
    }
    fun isWithinBudget(runId: String, consumedSteps: Int): Boolean {
        val r = resume(runId) ?: return false
        return consumedSteps < r.budgetSteps && System.currentTimeMillis() <= r.deadlineMs
    }
    fun health(): String {
        val now = System.currentTimeMillis()
        val stale = readableDatabase.rawQuery("SELECT COUNT(*) FROM runs WHERE state IN ('RUNNING','WAITING','VERIFYING','REPAIRING') AND deadline_ms < ?", arrayOf(now.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM runs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return "lifecycle_runs=" + total + ";stale=" + stale + ";healthy=" + (stale == 0)
    }
    fun prune(maxRuns: Int = 100) {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM runs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count > maxRuns) writableDatabase.execSQL("DELETE FROM runs WHERE run_id IN (SELECT run_id FROM runs ORDER BY updated_at ASC LIMIT ?)", arrayOf(count - maxRuns))
    }
    data class ResumePoint(val runId: String, val state: State, val cursor: Int, val deadlineMs: Long, val budgetSteps: Int, val goal: String)
}