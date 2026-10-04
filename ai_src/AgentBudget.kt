package com.hypernexus.nit.router
object AgentBudget {
    const val MAX_STEPS=8; const val MAX_REPAIRS=1; const val MAX_RUNTIME_MS=120_000L
    fun allow(startedAt: Long, steps: Int): Boolean = steps < MAX_STEPS && System.currentTimeMillis()-startedAt <= MAX_RUNTIME_MS
}