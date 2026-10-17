package gr.george.d32remote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * Μπάρα έντασης D32 σε accessibility overlay (πάνω από τα toasts της TCL).
 * Το παράθυρο προστίθεται ΜΙΑ φορά και μένει αόρατο, ώστε να εμφανίζεται αμέσως
 * (χωρίς καθυστέρηση addView) και να προλαβαίνει το μήνυμα "optical fibers…".
 */
class VolumeOverlay(private val ctx: Context, private val a11y: Boolean = true) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: PillView? = null
    private val hide = Runnable { view?.fadeOut() }

    fun attach() = main.post {
        if (view != null) return@post
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val s = dm.heightPixels / 1080f
        // Το μήνυμα της TCL: x 524-1396, y 901-1016 (σε 1080p). Το καλύπτουμε με περιθώριο.
        val w = (960 * s).toInt(); val h = (150 * s).toInt()
        val v = PillView(ctx, s).apply { alpha = 0f }
        val lp = WindowManager.LayoutParams(
            w, h,
            if (a11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w) / 2
            y = (883 * s).toInt()
        }
        try { wm.addView(v, lp); view = v } catch (_: Exception) {}
    }

    fun detach() = main.post {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    fun show(vol: Int, max: Int) = main.post {
        if (view == null) attach()
        main.post {
            view?.set(vol, max)
            view?.fadeIn()
            main.removeCallbacks(hide)
            main.postDelayed(hide, 3700)     // το μήνυμα της TCL μένει ~3.5s μετά το τελευταίο πάτημα
        }
    }

    /** Σχεδιάζει: [εικονίδιο]  D32  ━━━━━━━━━━━━━━────────  12 */
    private class PillView(ctx: Context, private val s: Float) : View(ctx) {
        private var shown = 0f           // ζωγραφισμένη θέση της μπάρας (για animation)
        private var target = 0f
        private var vol = 0
        private var max = 16
        private var anim: ValueAnimator? = null

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF15171C") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * s; color = Color.parseColor("#26FFFFFF")
        }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFFFFF") }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4.5f * s
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 44f * s
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.RIGHT
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF"); textSize = 26f * s; letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rect = RectF()
        private val path = Path()

        fun set(v: Int, m: Int) {
            vol = v.coerceAtLeast(0); max = if (m > 0) m else 16
            target = vol.toFloat() / max
            anim?.cancel()
            anim = ValueAnimator.ofFloat(shown, target).apply {
                duration = 160; interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
            invalidate()
        }

        fun fadeIn() { animate().cancel(); if (alpha < 1f) animate().alpha(1f).setDuration(90).start() }
        fun fadeOut() { animate().cancel(); animate().alpha(0f).setDuration(260).start() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val pad = 8f * s
            // pill
            rect.set(pad, pad, w - pad, h - pad)
            val r = rect.height() / 2
            c.drawRoundRect(rect, r, r, bg)
            c.drawRoundRect(rect, r, r, border)

            val cy = h / 2
            val muted = vol == 0
            // εικονίδιο ηχείου
            val ix = rect.left + 60f * s
            path.reset()
            path.moveTo(ix - 16 * s, cy - 10 * s); path.lineTo(ix - 6 * s, cy - 10 * s)
            path.lineTo(ix + 8 * s, cy - 22 * s); path.lineTo(ix + 8 * s, cy + 22 * s)
            path.lineTo(ix - 6 * s, cy + 10 * s); path.lineTo(ix - 16 * s, cy + 10 * s); path.close()
            c.drawPath(path, iconFill)
            if (muted) {
                c.drawLine(ix + 18 * s, cy - 10 * s, ix + 38 * s, cy + 10 * s, icon)
                c.drawLine(ix + 38 * s, cy - 10 * s, ix + 18 * s, cy + 10 * s, icon)
            } else {
                val arcs = if (target < 0.34f) 1 else if (target < 0.67f) 2 else 3
                for (i in 1..arcs) {
                    val rr = (8 + i * 10) * s
                    rect.set(ix + 4 * s - rr, cy - rr, ix + 4 * s + rr, cy + rr)
                    c.drawArc(rect, -45f, 90f, false, icon)
                }
            }

            // "D32"
            val lx = ix + 70f * s
            c.drawText("D32", lx, cy + 9f * s, label)

            // μπάρα
            val bx0 = lx + 80f * s
            val bx1 = w - pad - 120f * s
            val bh = 12f * s
            rect.set(bx0, cy - bh / 2, bx1, cy + bh / 2)
            c.drawRoundRect(rect, bh / 2, bh / 2, track)
            if (!muted && shown > 0f) {
                val fx = bx0 + (bx1 - bx0) * shown.coerceIn(0f, 1f)
                fill.shader = LinearGradient(bx0, 0f, bx1, 0f,
                    Color.parseColor("#7FD3A0"), Color.parseColor("#4FC3F7"), Shader.TileMode.CLAMP)
                rect.set(bx0, cy - bh / 2, fx.coerceAtLeast(bx0 + bh), cy + bh / 2)
                c.drawRoundRect(rect, bh / 2, bh / 2, fill)
                // λαβή
                c.drawCircle(fx.coerceAtLeast(bx0 + bh / 2), cy, 11f * s, iconFill)
            }

            // αριθμός
            num.color = if (muted) Color.parseColor("#99FFFFFF") else Color.WHITE
            c.drawText(if (muted) "—" else "$vol", w - pad - 44f * s, cy + 15f * s, num)
        }
    }
}
