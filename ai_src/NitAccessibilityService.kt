package com.hypernexus.nit.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class NitAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); NitAccessibilityController.attach(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { NitAccessibilityController.detach(this); super.onDestroy() }
}
