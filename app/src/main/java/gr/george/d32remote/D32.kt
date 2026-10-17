package gr.george.d32remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE client για το Edifier D32.
 *
 * Πρωτόκολλο (επιβεβαιωμένο live):
 *   service 48098601-1a48-11e9-ab14-d663bd873d93
 *   notify  48090001-...  write 48090002-... (write-without-response)
 *   frame   AA EC <cmd> <len_hi> <len_lo> <payload> <sum & 0xFF>
 *   init    enable notify -> D8 (αλλιώς οι εντολές αγνοούνται)
 *   0x67 <vol> = set volume, 0x66 = get volume -> BB EC 66 00 02 <max> <vol>
 *
 * Όλη η κατάσταση αλλάζει μόνο στο main thread.
 */
@SuppressLint("MissingPermission")
object D32 {

    private val SVC: UUID = UUID.fromString("48098601-1a48-11e9-ab14-d663bd873d93")
    private val NOTIFY: UUID = UUID.fromString("48090001-1a48-11e9-ab14-d663bd873d93")
    private val WRITE: UUID = UUID.fromString("48090002-1a48-11e9-ab14-d663bd873d93")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ADV_SVC: ParcelUuid = ParcelUuid.fromString("00008600-0000-1000-8000-00805f9b34fb")
    private const val EDIFIER_MFR = 0x07E0
    private const val PREFS = "d32"
    private const val KEY_ADDR = "addr"

    @Volatile var volume = -1; private set
    @Volatile var maxVol = 16; private set
    @Volatile var connected = false; private set
    @Volatile var status = "Ανενεργό"; private set
    private var lastNonZero = 6

    /** Καλείται (στο main thread) όταν το ηχείο αναφέρει νέα ένταση. */
    var onVolume: ((Int, Int) -> Unit)? = null

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var wChr: BluetoothGattCharacteristic? = null
    private var wanted = false
    private var scanning = false
    private var connecting = false

    private val queue = ArrayDeque<ByteArray>()
    private var writing = false

    /** Γενικός διακόπτης: όταν είναι false, η εφαρμογή δεν αγγίζει την ένταση. */
    var enabled: Boolean
        get() = !::app.isInitialized || prefs().getBoolean("enabled", true)
        set(v) { prefs().edit().putBoolean("enabled", v).apply(); status = if (v) status else "Σε παύση" }

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    // ---------------------------------------------------------------- public API

    fun start() = main.post { wanted = true; connect() }

    fun stop() = main.post {
        wanted = false
        gatt?.disconnect(); gatt?.close(); gatt = null
        wChr = null; connected = false; connecting = false
        status = "Σταματημένο"
    }

    /** Επιστρέφει false αν δεν υπάρχει σύνδεση (τότε ξεκινάει επανασύνδεση). */
    fun setVolume(v: Int): Boolean {
        if (!connected) { main.post { connect() }; return false }
        val c = v.coerceIn(0, maxVol)
        volume = c                        // optimistic: γρήγορα συνεχόμενα πατήματα
        if (c > 0) lastNonZero = c
        send(0x67, byteArrayOf(c.toByte()))
        return true
    }

    fun up() = setVolume((if (volume < 0) lastNonZero else volume) + 1)
    fun down() = setVolume((if (volume < 0) lastNonZero else volume) - 1)
    fun toggleMute() = setVolume(if (volume > 0) 0 else lastNonZero)

    // ---------------------------------------------------------------- connection

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun connect() {
        if (!wanted || connected || connecting || scanning) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status = "Το Bluetooth είναι κλειστό"; retryLater(); return }

        val saved = prefs().getString(KEY_ADDR, null)
        if (saved == null) { scan(); return }

        connecting = true
        status = "Σύνδεση με $saved…"
        val dev: BluetoothDevice = adapter.getRemoteDevice(saved)
        gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        // αν δεν απαντήσει σε 12s, ξαναψάξε (μπορεί να άλλαξε διεύθυνση)
        main.postDelayed({
            if (connecting && !connected) {
                gatt?.close(); gatt = null; connecting = false
                prefs().edit().remove(KEY_ADDR).apply()
                connect()
            }
        }, 12_000)
    }

    private fun scan() {
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: run { retryLater(); return }
        scanning = true
        status = "Αναζήτηση D32…"
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ADV_SVC).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if (r.scanRecord?.getManufacturerSpecificData(EDIFIER_MFR) == null) return
                main.post {
                    if (!scanning) return@post
                    scanning = false
                    scanner.stopScan(this)
                    prefs().edit().putString(KEY_ADDR, r.device.address).apply()
                    connect()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                main.post { scanning = false; status = "Scan error $errorCode"; retryLater() }
            }
        }
        scanner.startScan(filters, settings, cb)
        main.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(cb)
                status = "Δεν βρέθηκε το D32"
                retryLater()
            }
        }, 10_000)
    }

    private fun retryLater() {
        if (wanted) main.postDelayed({ connect() }, 5_000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    status = "Ανακάλυψη υπηρεσιών…"
                    g.discoverServices()
                } else {
                    g.close()
                    if (gatt === g) gatt = null
                    wChr = null; connected = false; connecting = false
                    queue.clear(); writing = false
                    status = "Αποσυνδέθηκε"
                    retryLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                val svc = g.getService(SVC)
                val n = svc?.getCharacteristic(NOTIFY)
                val w = svc?.getCharacteristic(WRITE)
                if (n == null || w == null) {
                    status = "Λάθος συσκευή (δεν βρέθηκε η υπηρεσία)"
                    prefs().edit().remove(KEY_ADDR).apply()
                    g.disconnect(); return@post
                }
                wChr = w
                g.setCharacteristicNotification(n, true)
                val d = n.getDescriptor(CCCD)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post {
                connected = true; connecting = false
                status = "Συνδεδεμένο"
                send(0xD8)   // connect ritual
                send(0x66)   // τρέχουσα ένταση
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post { writing = false; pump() }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(value)
        }

        // Android 12 και παλιότερα
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { handle(it.copyOf()) }
        }
    }

    private fun handle(d: ByteArray) {
        if (d.size < 7 || d[0] != 0xBB.toByte() || d[1] != 0xEC.toByte()) return
        val cmd = d[2].toInt() and 0xFF
        val n = ((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)
        if ((cmd == 0x66 || cmd == 0x67) && n >= 2) {
            val max = d[5].toInt() and 0xFF
            val vol = d[6].toInt() and 0xFF
            main.post {
                if (max > 0) maxVol = max
                volume = vol
                if (vol > 0) lastNonZero = vol
                onVolume?.invoke(vol, maxVol)
            }
        }
    }

    // ---------------------------------------------------------------- write queue

    private fun packet(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = payload.size
        val body = byteArrayOf(0xAA.toByte(), 0xEC.toByte(), cmd.toByte(),
            (n shr 8).toByte(), n.toByte()) + payload
        val sum = body.sumOf { it.toInt() and 0xFF } and 0xFF
        return body + sum.toByte()
    }

    private fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val p = packet(cmd, payload)
        main.post {
            // Για set volume κράτα μόνο την τελευταία τιμή στην ουρά
            if (cmd == 0x67) queue.removeAll { it.size > 2 && it[2] == 0x67.toByte() }
            queue.addLast(p)
            pump()
        }
    }

    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val w = wChr ?: return
        val p = queue.removeFirstOrNull() ?: return
        writing = true
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(w, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            w.value = p
            @Suppress("DEPRECATION")
            g.writeCharacteristic(w)
        }
        // fallback: αν δεν έρθει onCharacteristicWrite, συνέχισε σε 150ms
        main.postDelayed({ if (writing) { writing = false; pump() } }, 150)
    }
}
