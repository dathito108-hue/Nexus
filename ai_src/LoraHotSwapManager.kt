package com.hypernexus.nit.engine

import android.util.Log

object LoraHotSwapManager {
    private const val TAG = "LoraHotSwapManager"
    enum class LoraType(val fileName: String, val sizeMb: Int, val description: String) {
        NONE("none", 0, "Không dùng adapter"),
        FINANCE_EXPERT("finance_expert.lora", 20, "Finance"),
        CAPCUT_AUTOMATION("capcut_automation.lora", 20, "CapCut"),
        ANDROID_NAVIGATOR("android_navigator.lora", 15, "Android"),
        GAMING_TACTICS("gaming_tactics.lora", 15, "Gaming"),
        GEOMETRY_3D("geometry_3d.lora", 15, "Geometry")
    }
    private var activeLora = LoraType.NONE
    @Synchronized fun hotSwapLora(targetLora: LoraType): Boolean {
        if (targetLora == LoraType.NONE) { unloadAll(); return true }
        Log.w(TAG, "Adapter " + targetLora.fileName + " chưa được provision; không giả lập hot-swap.")
        return false
    }
    fun getActiveLora(): LoraType = activeLora
    fun getActiveRamOverheadMb(): Int = 0
    fun unloadAll() { activeLora = LoraType.NONE }
}
