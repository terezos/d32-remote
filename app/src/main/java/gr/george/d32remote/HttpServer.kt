package gr.george.d32remote

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Μικρός HTTP server (χωρίς dependencies).
 *   GET /            απλή σελίδα με κουμπιά (για δοκιμή από browser)
 *   GET /vol/up      /vol/down     /mute (toggle)
 *   GET /vol/set?v=0..16
 *   GET /status
 */
class HttpServer(private val port: Int = 8080) {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread({
            try {
                ServerSocket(port).use { s ->
                    server = s
                    while (!s.isClosed) {
                        val c = s.accept()
                        pool.execute { handle(c) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                server = null
            }
        }, "d32-http").start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    /** Τρέχει την εντολή στο main thread και περιμένει το αποτέλεσμα. */
    private fun onMain(block: () -> Boolean): Boolean {
        var r = false
        val latch = CountDownLatch(1)
        main.post { r = block(); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        return r
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val line = reader.readLine() ?: return
            // διάβασε (και αγνόησε) τα headers
            while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }

            val target = line.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore("?")
            val query = target.substringAfter("?", "").split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()

            val (code, type, body) = when (path) {
                "/" -> Triple(200, "text/html; charset=utf-8", PAGE)
                "/vol/up" -> json(onMain { D32.up() })
                "/vol/down" -> json(onMain { D32.down() })
                "/mute" -> json(onMain { D32.toggleMute() })
                "/vol/set" -> {
                    val v = query["v"]?.toIntOrNull()
                    if (v == null) Triple(400, "application/json", """{"ok":false,"error":"missing v"}""")
                    else json(onMain { D32.setVolume(v) })
                }
                "/status" -> json(D32.connected)
                "/enable" -> { D32.enabled = true; json(true) }
                "/disable" -> { D32.enabled = false; json(true) }
                else -> Triple(404, "application/json", """{"ok":false,"error":"not found"}""")
            }

            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(("HTTP/1.1 $code OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun json(ok: Boolean): Triple<Int, String, String> {
        // μικρή αναμονή ώστε να επιστρέψει την ένταση μετά το ack του ηχείου
        Thread.sleep(120)
        val body = """{"ok":$ok,"connected":${D32.connected},"volume":${D32.volume},"max":${D32.maxVol},"enabled":${D32.enabled},"status":"${D32.status}"}"""
        return Triple(if (ok) 200 else 503, "application/json; charset=utf-8", body)
    }

    companion object {
        private val PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>D32</title>
            <style>
              body{font-family:-apple-system,system-ui,sans-serif;background:#111;color:#eee;
                   display:flex;flex-direction:column;align-items:center;gap:16px;padding:32px 16px}
              #v{font-size:56px;font-weight:600}
              .row{display:flex;gap:12px}
              button{font-size:28px;width:96px;height:72px;border-radius:16px;border:0;background:#2a2f3a;color:#fff}
              button:active{background:#3e4656}
              #s{opacity:.6;font-size:14px}
            </style></head><body>
            <div id="v">–</div>
            <div class="row"><button onclick="go('/vol/down')">−</button>
            <button onclick="go('/mute')">🔇</button>
            <button onclick="go('/vol/up')">+</button></div>
            <div id="s"></div>
            <script>
              async function go(p){try{const r=await fetch(p);show(await r.json())}catch(e){s.textContent='offline'}}
              function show(j){v.textContent=j.volume<0?'–':j.volume+' / '+j.max;s.textContent=j.status}
              go('/status');setInterval(()=>go('/status'),3000)
            </script></body></html>
        """.trimIndent()
    }
}
