package gr.george.d32remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Οθόνη ρύθμισης/κατάστασης. Χωρίς XML layouts, για να μείνει το project μικρό. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private var toggle: Button? = null

    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        D32.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(96, 64, 96, 64)
            setBackgroundColor(Color.parseColor("#14171F"))
        }
        TextView(this).apply {
            text = "D32 Remote"
            textSize = 32f
            setTextColor(Color.WHITE)
            root.addView(this)
        }
        info = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#C9D1E0"))
            setPadding(0, 24, 0, 32)
            root.addView(this)
        }

        val row1 = row(root)
        button(row1, "1. Άδειες Bluetooth") { requestPermissions(perms, 1) }
        button(row1, "2. Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(row1, "Επανασύνδεση") { D32Service.start(this); D32.start() }
        toggle = Button(this).also { b ->
            b.textSize = 18f; b.isAllCaps = false
            b.setOnClickListener { D32.enabled = !D32.enabled; tick() }
            row1.addView(b)
        }

        val row2 = row(root)
        button(row2, "−") { D32.down() }
        button(row2, "🔇") { D32.toggleMute() }
        button(row2, "+") { D32.up() }

        setContentView(root)
        if (hasPerms()) D32Service.start(this)
    }

    override fun onResume() { super.onResume(); tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasPerms()) D32Service.start(this)
    }

    private fun tick() {
        val vol = if (D32.volume < 0) "–" else "${D32.volume} / ${D32.maxVol}"
        info.text = buildString {
            appendLine("Λειτουργία: ${if (D32.enabled) "✅ Ενεργή" else "⏸ Σε παύση"}")
            appendLine("Ηχείο: ${D32.status}")
            appendLine("Ένταση: $vol")
            appendLine("Άδειες Bluetooth: ${if (hasPerms()) "✅" else "❌"}")
            appendLine("Τηλεχειριστήριο (Accessibility): ${if (a11yOn()) "✅" else "❌ ενεργοποίησέ το"}")
            appendLine()
            append("Χρησιμοποίησε τα κουμπιά έντασης του τηλεχειριστηρίου.")
        }
        toggle?.text = if (D32.enabled) "⏸ Παύση" else "▶ Ενεργοποίηση"
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ tick() }, 1000)
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun a11yOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = "$packageName/${VolumeKeyService::class.java.name}"
        val split = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return split.any { it.equals(me, true) || it.equals("$packageName/.VolumeKeyService", true) }
    }


    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, 8, 0, 8)
        parent.addView(this)
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 24 })
        }
    }
}
