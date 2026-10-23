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
