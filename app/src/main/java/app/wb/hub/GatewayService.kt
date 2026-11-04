package app.wb.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the WorkBuddy reverse-proxy gateway (pure-stdlib Python) with an embedded
 * CPython runtime extracted from assets.
 *
 * Startup hardening mirrors ofm-gateway: stale lock cleanup, blind port polling
 * plus log-based fallback, and a watchdog that restarts a dead process.
 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "wb_gateway"
        const val NOTIF_ID = 1001
        const val PREFERRED_PORT = 8788

        @Volatile var running = false
        @Volatile var actualPort = PREFERRED_PORT
        @Volatile var lastError: String? = null
        @Volatile var panelPassword: String = "admin"
    }

    private var process: Process? = null
    private var watchdog: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("正在启动本地网关…"))
        if (process == null) Thread { boot() }.start()
        startWatchdog()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "WorkBuddy 网关", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("WorkBuddy 本地网关")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) } catch (_: Exception) {}
    }

    @Synchronized
    private fun boot() {
        try {
            val root = File(filesDir, "wb-root")
            updateNotification("解压运行时（首次较慢，请稍候）…")
            AssetExtractor.ensureExtracted(this, root)

            val py = File(root, "bin/python3.14")
            py.setExecutable(true, false)
            File(root, "bin").listFiles()?.forEach { it.setExecutable(true, false) }

            // Data dirs the gateway persists (accounts/settings/usage logs).
            val dataDir = File(filesDir, "wb-data")
            File(dataDir, "accounts").mkdirs()
            File(dataDir, "usage").mkdirs()

            val libDir = File(root, "lib")
            val srcDir = File(root, "app")
            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }

            updateNotification("启动服务…")
            val pb = ProcessBuilder(
                py.absolutePath,
                "wb_proxy.py",
                "--host", "127.0.0.1",
                "--port", PREFERRED_PORT.toString(),
                "--accounts-dir", File(dataDir, "accounts").absolutePath,
                "--usage-dir", File(dataDir, "usage").absolutePath,
                "--panel-password", panelPassword
            )
            pb.directory(srcDir)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = libDir.absolutePath
            // Bundled CPython comes from Termux and has its OpenSSL CA dir compiled to
            // a Termux path that does not exist here. Point it at our bundled roots.
            val certFile = File(libDir, "cert.pem")
            if (certFile.exists()) env["SSL_CERT_FILE"] = certFile.absolutePath
            env["HOME"] = filesDir.canonicalPath
            env["TMPDIR"] = tmpDir.canonicalPath
            env["PYTHONUNBUFFERED"] = "1"
            env["PYTHONHOME"] = root.absolutePath
            env["PATH"] = libDir.absolutePath + ":" + (System.getenv("PATH") ?: "/system/bin")

            val log = File(filesDir, "gateway.log")
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))

            val p = pb.start()
            process = p
            running = true
            lastError = null
            Thread { pollPort(p) }.start()
            updateNotification("启动中…")
        } catch (e: Exception) {
            lastError = e.message
            running = false
            updateNotification("启动失败：${e.message}")
        }
    }

    private fun pollPort(p: Process) {
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline && p.isAlive) {
            for (port in PREFERRED_PORT until PREFERRED_PORT + 12) {
                if (healthOk(port)) { markRunning(port); return }
            }
            parsePortFromLog()?.let { if (healthOk(it)) { markRunning(it); return } }
            Thread.sleep(700)
        }
        parsePortFromLog()?.let { actualPort = it; running = true; lastError = null
            updateNotification("运行中 · http://127.0.0.1:$it/v1"); return }
        lastError = "服务未就绪（端口无响应），点“日志”排查"
        running = false
        updateNotification("启动失败，请查看日志")
    }

    private fun markRunning(port: Int) {
        actualPort = port; running = true; lastError = null
        updateNotification("运行中 · http://127.0.0.1:$port/v1")
    }

    private fun parsePortFromLog(): Int? = try {
        val log = File(filesDir, "gateway.log")
        if (!log.exists()) null else
            Regex("listening\\s*:\\s*http://[0-9.]+:(\\d+)").findAll(log.readText()).lastOrNull()
                ?.groupValues?.get(1)?.toIntOrNull()
    } catch (_: Exception) { null }

    private fun healthOk(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 1500
            conn.responseCode in 200..299
        } catch (_: Exception) { false }
    }

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread {
            while (true) {
                try {
                    Thread.sleep(8000)
                    val p = process
                    if (p == null || !p.isAlive) { running = false; boot() }
                } catch (e: InterruptedException) { return@Thread }
                catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        running = false
        super.onDestroy()
    }
}
