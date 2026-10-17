package gr.george.d32remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast

/**
 * Η TCL δεν δίνει τα πλήκτρα έντασης σε εφαρμογές, αλλά αλλάζει την ένταση STREAM_MUSIC
 * (0-100) ακόμα και σε οπτική έξοδο (η πραγματική έξοδος μένει στα 0 dB, σταθερή).
 * Άρα παρακολουθούμε αυτή την αλλαγή και τη μεταφέρουμε ως ±1 βήμα στο D32.
 * Όταν η τιμή της TV πλησιάσει τα όρια 0/100, την ξαναφέρνουμε στο 50 για να μην "κολλήσει".
 */
class TvVolumeWatcher(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var ignoreUntil = 0L
    private var toast: Toast? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    VOLUME_CHANGED -> onVolume(i)
                    MUTE_CHANGED -> onMute(i)
                }
            } catch (_: Exception) {
                // ποτέ crash από ένα πάτημα
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply { addAction(VOLUME_CHANGED); addAction(MUTE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        recenter()
    }

    fun stop() = try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}

    private fun onVolume(i: Intent) {
        if (i.getIntExtra(EXTRA_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val now = i.getIntExtra(EXTRA_VALUE, -1)
        val prev = i.getIntExtra(EXTRA_PREV, -1)
        if (now < 0 || prev < 0 || now == prev) return
        if (SystemClock.uptimeMillis() < ignoreUntil) return       // δικό μας recenter
        if (!D32.enabled) return
        if (!D32.connected) { D32.start(); return }

        if (now > prev) D32.up() else D32.down()
        show()
        if (now <= 15 || now >= 85) recenter()
    }

    private fun onMute(i: Intent) {
        if (i.getIntExtra(EXTRA_TYPE, AudioManager.STREAM_MUSIC) != AudioManager.STREAM_MUSIC) return
        if (SystemClock.uptimeMillis() < ignoreUntil) return
        if (!D32.enabled) return
        val muted = i.getBooleanExtra(EXTRA_MUTED, false)
        if (!D32.connected) return
        if (muted && D32.volume > 0) D32.toggleMute()
        else if (!muted && D32.volume == 0) D32.toggleMute()
        show()
    }

    private fun recenter() {
        ignoreUntil = SystemClock.uptimeMillis() + 800
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 50, 0) } catch (_: Exception) {}
    }

    private fun show() {
        // Η ένταση ενημερώνεται optimistically στο D32.up()/down(), άρα είναι ήδη η νέα τιμή
        VolumeKeyService.overlay?.let { it.show(D32.volume, D32.maxVol); return }
        val v = D32.volume
        val text = if (v <= 0) "🔇  D32" else "🔊  D32  ${"▮".repeat(v)}${"▯".repeat((D32.maxVol - v).coerceAtLeast(0))}  $v"
        toast?.cancel()
        toast = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED"
    }
}
