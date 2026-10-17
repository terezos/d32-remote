package gr.george.d32remote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service. Η TCL δεν περνάει τα πλήκτρα έντασης, οπότε εδώ:
 *  - κρατάμε ένα overlay στο επίπεδο accessibility (πάνω από τα toasts της TCL)
 *  - μόλις εμφανιστεί toast του συστήματος (το "optical fibers…"), δείχνουμε αμέσως τη μπάρα μας
 */
class VolumeKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        D32.init(this)
        overlay = VolumeOverlay(this, a11y = true).also { it.attach() }
        D32Service.start(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        if (!D32.connected) D32Service.start(this)      // αν σταμάτησε για οποιονδήποτε λόγο, ξεκίνα ξανά
        if (e.className?.toString()?.contains("Toast") != true) return
        if (e.packageName?.toString() != "com.android.systemui") return
        if (!D32.enabled || !D32.connected) return
        overlay?.show(D32.volume, D32.maxVol)
    }

    override fun onInterrupt() {}

    override fun onDestroy() { overlay?.detach(); overlay = null; super.onDestroy() }

    companion object {
        @Volatile var overlay: VolumeOverlay? = null
    }
}
