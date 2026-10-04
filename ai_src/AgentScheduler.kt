package com.hypernexus.nit.router
object AgentScheduler {
    fun readyBatch(stepIds: List<String>, dependencies: Map<String, Set<String>>, completed: Set<String>): List<String> =
        stepIds.filter { it !in completed && (dependencies[it].orEmpty() - completed).isEmpty() }.take(4)
}