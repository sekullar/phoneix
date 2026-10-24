package app.phoneix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings

/**
 * Ekran yansıtma sürekliliği. Yansıtma açıkken Wi‑Fi ve işlemci uykuya geçmesin diye kilit tutulur; ayrıca (tek seferlik adb izniyle)
 * kablosuz hata ayıklama kapanırsa yeniden açılır. İzin: adb shell pm grant app.phoneix android.permission.WRITE_SECURE_SETTINGS
 */
object KeepAlive {
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null
    @Volatile private var watching = false
    private const val ADB_WIFI = "adb_wifi_enabled"

    fun canWriteSecure(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun adbWifiEnabled(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, ADB_WIFI, 0) == 1 } catch (e: Exception) { false }

    fun setAdbWifi(ctx: Context, on: Boolean): Boolean {
        if (!canWriteSecure(ctx)) return false
        return try { Settings.Global.putInt(ctx.contentResolver, ADB_WIFI, if (on) 1 else 0) } catch (e: Exception) { false }
    }

    @Volatile private var portCache: List<Int> = emptyList()

    private fun isOpen(port: Int, ms: Int = 150) = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), ms); true }
    } catch (e: Exception) { false }

    /** Kablosuz hata ayıklamanın dinlediği port adayları (geçici port aralığındaki açık portlar). Bilgisayar 65 bin portu ağdan taramak yerine bunları dener. */
    @Synchronized
    fun adbPorts(ctx: Context, fresh: Boolean = false): List<Int> {
        if (!adbWifiEnabled(ctx)) { portCache = emptyList(); return portCache }
        if (!fresh && portCache.isNotEmpty() && portCache.all { isOpen(it) }) return portCache
        val found = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(96)
        for (p in 32768..60999) pool.execute { if (found.size < 12 && isOpen(p, 120)) found.add(p) }
        pool.shutdown()
        pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
        portCache = found.sorted().take(8)
        return portCache
    }

    @Synchronized
    fun on(ctx0: Context) {
        val ctx = ctx0.applicationContext
        off()
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phoneix:mirror").apply { setReferenceCounted(false); acquire() }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phoneix:mirror").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        } catch (e: Exception) { /* kilit alınamadı: yansıtma yine çalışır, yalnızca uykuda kopabilir */ }
        if (canWriteSecure(ctx)) { // yansıtma boyunca kablosuz hata ayıklama kapanırsa geri aç
            watching = true
            Thread {
                while (watching) {
                    if (!adbWifiEnabled(ctx)) setAdbWifi(ctx, true)
                    try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    @Synchronized
    fun off() {
        watching = false
        try { wifi?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* zaten bırakılmış */ }
        try { cpu?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* zaten bırakılmış */ }
        wifi = null; cpu = null
    }
}
