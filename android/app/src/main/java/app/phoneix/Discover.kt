package app.phoneix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bilgisayarın IP'si değişince (DHCP, başka Wi‑Fi, mobil erişim noktası) eski adresler işe yaramaz. Bilgisayar ulaşılamaz olunca telefon
 * kendi Wi‑Fi ağını Phoneix portu için tarar; açık bulduklarına eşleştirme anahtarıyla ping atar. Yalnızca anahtarı bilen yanıt verir,
 * o yüzden ağdaki başka cihazlar kabul edilmez. Bulunan adres kaydedilir.
 */
object Discover {
    private var last = 0L

    /** Bilgisayar bulunduysa true (adresler Store'a yazıldı). En çok 30 sn'de bir çalışır. */
    fun run(ctx: Context, store: Store, key: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        if (now - last < 30_000) return false
        last = now
        val range = localRange(ctx) ?: return false
        val known = store.hosts.toSet()
        val ports = listOf(store.port, 47810).distinct()
        val found = ConcurrentLinkedQueue<Pair<String, Int>>()
        val pool = Executors.newFixedThreadPool(64)
        for (ip in range) for (p in ports) {
            if (ip in known && p == store.port) continue // bilinen adresi zaten denedik
            pool.execute {
                try { Socket().use { it.connect(InetSocketAddress(ip, p), 300); found.add(ip to p) } } catch (e: Exception) { /* kapalı */ }
            }
        }
        pool.shutdown()
        pool.awaitTermination(25, TimeUnit.SECONDS)
        for ((ip, p) in found) {
            val r = Link.send(key, listOf(ip), p, JSONObject().put("type", "ping"), 2500)
            if (r.ok) { store.learn(listOf(ip), p, r.reply?.optJSONArray("h")); return true }
        }
        return false
    }

    /** Wi‑Fi'deki kendi alt ağımız (en çok /22 = 1022 adres); mobil veride ya da özel olmayan ağda taranmaz. */
    private fun localRange(ctx: Context): List<String>? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val wifi = net != null && cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val la = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        val b = (la?.address as? Inet4Address)?.address
        if (!wifi || la == null || b == null) null
        else {
            val ip = b.fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xff) }
            val priv = (b[0].toInt() and 0xff).let { it == 10 || (it == 172 && (b[1].toInt() and 0xf0) == 16) || (it == 192 && (b[1].toInt() and 0xff) == 168) }
            val prefix = la.prefixLength.coerceIn(22, 30)
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val base = ip and mask
            val count = (1L shl (32 - prefix)) - 2
            if (!priv) null else (1..count).map { base + it }.filter { it != ip }.map { "${(it shr 24) and 255}.${(it shr 16) and 255}.${(it shr 8) and 255}.${it and 255}" }
        }
    } catch (e: Exception) { null }
}
