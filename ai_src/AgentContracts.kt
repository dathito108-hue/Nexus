package com.hypernexus.nit.router
import org.json.JSONObject
object AgentContracts {
    fun normalize(tool: String, result: String): String = JSONObject().apply {
        put("contract", "NIT_RESULT_V1"); put("tool", tool.take(96)); put("status", if (result.startsWith("LỖI:")) "error" else "ok"); put("data", result.take(6000))
    }.toString()
    fun isSuccess(contract: String): Boolean = try { val j=JSONObject(contract); j.optString("contract")=="NIT_RESULT_V1" && j.optString("status")=="ok" } catch (_: Throwable) { false }
}