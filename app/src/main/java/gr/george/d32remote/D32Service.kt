package gr.george.d32remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service: κρατάει τη BLE σύνδεση και τον HTTP server ζωντανούς. */
class D32Service : Service() {
    private val http = HttpServer(8080)
    private var watcher: TvVolumeWatcher? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "D32 Remote", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("D32 Remote")
            .setContentText("Έλεγχος έντασης Edifier D32")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }

        D32.init(this)
        D32.start()
        http.start()
        watcher = TvVolumeWatcher(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        D32.start()          // αν ήδη συνδεδεμένο, δεν κάνει τίποτα
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.stop()
        http.stop()
        D32.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "d32"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, D32Service::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
