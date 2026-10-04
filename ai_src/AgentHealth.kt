package com.hypernexus.nit.router
object AgentHealth {
    fun selfTest(): String {
        val checks=listOf("registry" to (SkillRegistry.all().size>=8), "contracts" to AgentContracts.isSuccess(AgentContracts.normalize("self_test","ok")), "scheduler" to (AgentScheduler.readyBatch(listOf("a"),emptyMap(),emptySet())==listOf("a")), "budget" to AgentBudget.allow(System.currentTimeMillis(),0))
        val failed=checks.filterNot{it.second}.map{it.first}; return if(failed.isEmpty()) "P15_HEALTH=OK" else "P15_HEALTH=FAIL:"+failed.joinToString(",")
    }
}