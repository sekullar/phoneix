package app.phoneix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import java.net.Inet4Address
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.Executors

/** Yalnızca Phoneix'te seçilen uygulamaların bildirimlerini bilgisayara yollar. */
class NotificationService : NotificationListenerService() {
    private val io = Executors.newSingleThreadExecutor()
    private val seen = HashMap<String, Pair<Int, Long>>()
    @Volatile private var polling = false

    // Bilgisayardan telefona dosya: bu servis sistem tarafından canlı tutulduğu için "bekleyen dosya var mı" sorusu da burada sorulur.
    override fun onListenerConnected() {
        if (polling) return
        polling = true
        Thread { pollLoop() }.apply { isDaemon = true }.start()
    }
    override fun onListenerDisconnected() { polling = false }

    private fun pollLoop() {
        var fails = 0
        while (polling) {
            val store = Store(this)
            val key = store.key
            if (key == null) { nap(5000); continue }
            Calls.ensureWatcher(this)
            val (level, plugged) = batteryInfo()
            val poll = JSONObject().put("type", "poll").put("battery", level).put("charging", plugged).put("secure", KeepAlive.canWriteSecure(this)).put("adbwifi", KeepAlive.adbWifiEnabled(this)).put("ip", localIp())
            if (KeepAlive.adbWifiEnabled(this)) poll.put("adbports", org.json.JSONArray(KeepAlive.adbPorts(this)))
            val r = Link.send(key, store.hosts, store.port, poll, 40000)
            if (!r.ok) { fails++; nap(minOf(60000L, 3000L * fails)); continue }
            fails = 0
            r.host?.let { store.remember(it) }
            val cmds = r.reply?.optJSONArray("cmds")
            if (cmds != null) for (i in 0 until cmds.length()) {
                val c = cmds.getJSONObject(i)
                when (c.optString("type")) {
                    "clip" -> if (store.clipSync) Clip.apply(this, c.optString("text"))
                    "ring" -> Ringer.start(this)
                    "stopring" -> Ringer.stop(this)
                    "getring" -> io.execute { Ringer.sendRingtone(this) }
                    "call" -> Calls.command(this, c.optString("action"))
                    "mode" -> ModeActivity.open(this, c.optString("mode"))
                    "adbwifi" -> {
                        val on = c.optBoolean("on", true)
                        KeepAlive.setAdbWifi(this, on)
                        if (on) io.execute { // portu bulunca hemen bildir: bilgisayar bir sonraki yoklamayı beklemesin
                            var ports = emptyList<Int>()
                            for (n in 0 until 6) { nap(700); ports = KeepAlive.adbPorts(this, true); if (ports.isNotEmpty()) break }
                            Link.send(key, store.hosts, store.port, JSONObject().put("type", "adbport").put("ports", org.json.JSONArray(ports)))
                        }
                    }
                    "keepalive" -> if (c.optBoolean("on", true)) KeepAlive.on(this) else KeepAlive.off()
                    "reply" -> {
                        val id = c.optString("id"); val text = c.optString("text")
                        io.execute { // yanıtı bildirime yaz, sonucu bilgisayara bildir
                            val err = Replies.send(this, id, text)
                            val res = JSONObject().put("type", "replyres").put("id", id).put("ok", err == null)
                            if (err != null) res.put("error", err)
                            Link.send(key, store.hosts, store.port, res)
                        }
                    }
                }
            }
            val arr = r.reply?.optJSONArray("files") ?: continue
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val name = f.optString("name", "dosya")
                val done = Transfers.receive(this, key, store.hosts, store.port, f.getString("id"), name) { }
                if (done) announce("Dosya geldi", name) else nap(5000)
            }
        }
    }
    /** Telefonun bu ağdaki gerçek IPv4 adresi: bilgisayar bağlantıyı NAT'lı görebildiği için (Windows mobil erişim noktası) kendisi bildirir. */
    private fun localIp(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress.orEmpty()
    } catch (e: Exception) { "" }

    private fun batteryInfo(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val p = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return Pair(if (l < 0 || s <= 0) -1 else l * 100 / s, p != 0)
    }
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { /* kapanıyor */ } }

    private fun announce(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("files", "Gelen dosyalar", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(text.hashCode(), Notification.Builder(this, "files").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text + " · İndirilenler/Phoneix").setAutoCancel(true).build())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = Store(this)
        val key = store.key ?: return
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL && sbn.packageName != packageName) { Calls.onNotification(this, sbn); return } // aramalar ayrı akış
        val own = sbn.packageName == packageName
        if (own && n.channelId != "phx_test") return // yalnızca yanıt testi bildirimi kendi paketimizden geçer
        if (!own && sbn.packageName !in store.apps) return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        if (store.skipSilent && !own) { // sistem bildirimleri ("bağlantınız kontrol ediliyor") düşük önemli kanallardan gelir; mesajlar yüksek önemlidir
            val r = Ranking()
            if (currentRanking.getRanking(sbn.key, r) && r.importance <= NotificationManager.IMPORTANCE_LOW) return
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        // Aynı bildirimin güncellemesi (ilerleme çubuğu vb.) tekrar gitmesin
        val h = (title + "\u0000" + text).hashCode()
        val now = System.currentTimeMillis()
        val prev = seen.put(sbn.key, h to now)
        if (prev != null && prev.first == h && now - prev.second < 5000) return // aynı bildirimin hızlı güncellemesi; sonradan gelen aynı metin yine geçer
        if (seen.size > 200) seen.clear()
        val app = label(sbn.packageName)
        val hide = store.hideText
        val msg = JSONObject().put("type", "notif").put("app", app.take(40))
            .put("title", if (hide) app else title.take(80))
            .put("text", if (hide) "Yeni bildirim" else text.take(200))
        if (!hide) Replies.register(sbn)?.let { msg.put("reply", it) } // yanıtlanabilir bildirim: bilgisayarda "Yanıtla" çıkar
        val hosts = store.hosts; val port = store.port
        io.execute { Link.send(key, hosts, port, msg) }
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    override fun onDestroy() { polling = false; io.shutdown(); super.onDestroy() }
}
