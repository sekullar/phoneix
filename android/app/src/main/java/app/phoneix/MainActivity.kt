package app.phoneix

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import androidx.core.app.NotificationManagerCompat
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var pcState: TextView
    private lateinit var power: LinearLayout
    private var pcOnline = false
    private lateinit var test: Button
    private lateinit var unpair: Button
    private lateinit var access: Button
    private lateinit var upload: Button
    private lateinit var apps: Button
    private lateinit var battery: Button
    private lateinit var modeMon: Button
    private lateinit var modeDeck: Button
    private var askedAccess = false
    private var askedBattery = false
    private var askedNotif = false
    private var askedTile = false
    private var askedPhone = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun deviceName() = Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Ui.init(this)
        store = Store(this)
        val frame = FrameLayout(this)
        frame.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(56), dp(20), dp(28)) }
        frame.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(root) }, FrameLayout.LayoutParams(-1, -1))
        val parts = ArrayList<View>()

        // başlık: degrade logo + ad
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.A1, Ui.A2)).apply { cornerRadius = Ui.dpf(this@MainActivity, 16f) }
            elevation = Ui.dpf(this@MainActivity, 6f)
            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE); cornerRadius = Ui.dpf(this@MainActivity, 6f) } },
                FrameLayout.LayoutParams(dp(17), dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "Phoneix"; textSize = 28f; setTextColor(Ui.INK); paint.isFakeBoldText = true
            // Gizli kısayol: başlığa uzun bas → kablosuz hata ayıklamayı aç/kapat, açıkken portu göster
            setOnLongClickListener {
                if (!KeepAlive.canWriteSecure(this@MainActivity)) { Toast.makeText(this@MainActivity, "Bunun için WRITE_SECURE_SETTINGS izni gerekli", Toast.LENGTH_LONG).show(); return@setOnLongClickListener true }
                val on = !KeepAlive.adbWifiEnabled(this@MainActivity)
                KeepAlive.setAdbWifi(this@MainActivity, on)
                Toast.makeText(this@MainActivity, if (on) "Kablosuz hata ayıklama açılıyor…" else "Kablosuz hata ayıklama kapatıldı", Toast.LENGTH_SHORT).show()
                if (on) Thread {
                    var ports = emptyList<Int>()
                    for (n in 0 until 6) { Thread.sleep(700); ports = KeepAlive.adbPorts(this@MainActivity, true); if (ports.isNotEmpty()) break }
                    runOnUiThread { Toast.makeText(this@MainActivity, if (ports.isEmpty()) "Port bulunamadı" else "Kablosuz hata ayıklama açık · port " + ports.joinToString(", "), Toast.LENGTH_LONG).show() }
                }.start()
                true
            }
        })
        titles.addView(TextView(this).apply { text = "Telefonun, bilgisayarında."; textSize = 13f; setTextColor(Ui.DIM) })
        head.addView(titles)
        root.addView(head); parts.add(head)

        fun card(title: String): LinearLayout {
            val c = Ui.card(this)
            if (title.isNotEmpty()) c.addView(TextView(this).apply { text = title.uppercase(); textSize = 11f; setTextColor(Ui.A1); paint.isFakeBoldText = true; letterSpacing = 0.12f; setPadding(0, 0, 0, dp(10)) })
            root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }); parts.add(c)
            return c
        }
        fun btn(parent: LinearLayout, t: String, primary: Boolean = false, f: () -> Unit): Button {
            val b = Ui.button(this, t, primary).apply { setOnClickListener { f() } }
            parent.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            return b
        }
        fun sw(parent: LinearLayout, t: String, checked: Boolean, f: (Boolean) -> Unit) {
            parent.addView(Switch(this).apply {
                text = t; setTextColor(Ui.INK); isChecked = checked; setOnCheckedChangeListener { _, on -> f(on) }
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.A1, 0xFFFFFFFF.toInt()))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x662F8CFF, if (Ui.dark) 0x55FFFFFF else 0x55809AB5))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        val conn = card("Bağlantı")
        status = TextView(this).apply { textSize = 15f; setTextColor(Ui.INK); setPadding(0, 0, 0, dp(6)) }
        conn.addView(status)
        pcState = TextView(this).apply { textSize = 14f; setTextColor(Ui.DIM); setPadding(0, 0, 0, dp(6)) }
        conn.addView(pcState)
        btn(conn, "QR ile eşleştir", true) { scanQr() }
        test = btn(conn, "Test bildirimi gönder") { sendTest() }
        unpair = btn(conn, "Eşleştirmeyi kaldır") { store.clear(); refresh("Eşleştirme kaldırıldı") }

        power = card("Bilgisayar")
        val rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rowBot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pw(row: LinearLayout, label: String, id: String, confirm: String = "") {
            row.addView(Ui.button(this, label, false).apply {
                textSize = 13f; isAllCaps = false; tag = "pw"
                setOnClickListener { powerPress(this, label, id, confirm) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
        }
        pw(rowTop, "🔒 Kilitle", "pw_lock"); pw(rowTop, "🌙 Uyku", "pw_sleep", "Bilgisayar uykuya alınsın mı?"); pw(rowTop, "🖥️ Ekranı kapat", "pw_screen")
        pw(rowBot, "🔄 Yeniden başlat", "pw_restart", "Bilgisayar yeniden başlatılsın mı? Kaydedilmemiş işler kaybolabilir."); pw(rowBot, "🔌 Kapat", "pw_shutdown", "Bilgisayar kapatılsın mı? Kaydedilmemiş işler kaybolabilir.")
        power.addView(rowTop, LinearLayout.LayoutParams(-1, -2)); power.addView(rowBot, LinearLayout.LayoutParams(-1, -2))

        val modes = card("Modlar")
        modes.addView(TextView(this).apply { text = "Telefonu ikinci ekran yap: yatay, tam ekran."; textSize = 12.5f; setTextColor(Ui.DIM) })
        modeMon = btn(modes, "📈  Sistem monitörü", true) { startActivity(Intent(this, ModeActivity::class.java).putExtra("mode", "monitor")) }
        modeDeck = btn(modes, "🎛️  Deck") { startActivity(Intent(this, ModeActivity::class.java).putExtra("mode", "deck")) }

        val xfer = card("Aktarım")
        upload = btn(xfer, "Bilgisayara dosya gönder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 7)
        }
        btn(xfer, "Yanıt testi (telefona bildirim düşür)") { ReplyTestReceiver.post(this) }

        val perms = card("İzinler")
        access = btn(perms, "Bildirim erişimi ver") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        battery = btn(perms, "Pil kısıtlamasını kaldır") { askBattery() }
        apps = btn(perms, "Hangi uygulamalar?") { startActivity(Intent(this, AppsActivity::class.java)) }

        val prefs = card("Ayarlar")
        // tema seçimi: Koyu (varsayılan) / Açık / Sistem; seçince ekran yeniden kurulur
        prefs.addView(TextView(this).apply { text = "Tema"; textSize = 15f; setTextColor(Ui.INK) })
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("dark" to "🌙  Koyu", "light" to "☀️  Açık", "system" to "Sistem").forEach { (id, label) ->
            val on = store.theme == id
            themeRow.addView(TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; isClickable = true
                setTextColor(if (on) Color.WHITE else Ui.INK); setPadding(0, dp(11), 0, dp(11))
                background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.A1, Ui.A2)).apply { cornerRadius = Ui.dpf(this@MainActivity, 12f) }
                else Ui.round(this@MainActivity, Ui.GLASS, 12, Ui.LINE)
                setOnClickListener { if (store.theme != id) { store.theme = id; recreate() } }
                Ui.pressable(this)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        prefs.addView(themeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
        sw(prefs, "Bildirim metnini gizle (yalnızca uygulama adı gider)", store.hideText) { store.hideText = it }
        sw(prefs, "Sessiz bildirimleri gönderme (ör. WhatsApp \"bağlantınız kontrol ediliyor\")", store.skipSilent) { store.skipSilent = it }
        sw(prefs, "Panoyu bilgisayarla eşitle", store.clipSync) { store.clipSync = it }
        prefs.addView(TextView(this).apply {
            text = "Bilgisayarda kopyaladığın kendiliğinden telefona gelir. Telefonda kopyaladığını göndermek için Hızlı Ayarlar'daki \"Panoyu gönder\" karosuna dokun (ya da Paylaş → Phoneix)."
            textSize = 12f; setTextColor(Ui.DIM); setPadding(0, dp(8), 0, 0)
        })
        setContentView(frame); Ui.window(this)
        Ui.enter(parts)
        refresh()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun refresh(msg: String? = null) {
        val paired = store.paired
        status.text = msg ?: if (paired) "Eşleşti: ${store.pcName}"
        else "Henüz eşleşmedi.\nBilgisayarda Phoneix uygulamasını aç ve \"Telefonu eşleştir\" ile kodu çıkar."
        test.isEnabled = paired
        unpair.isEnabled = paired
        val granted = accessGranted()
        val free = batteryFree()
        battery.text = if (free) "Pil kısıtlaması yok ✓" else "Pil kısıtlamasını kaldır"
        battery.isEnabled = !free
        access.text = if (granted) "Bildirim erişimi açık ✓" else "Bildirim erişimi ver"
        apps.isEnabled = paired
        modeMon.isEnabled = paired; modeDeck.isEnabled = paired
        upload.isEnabled = paired
        apps.text = "Hangi uygulamalar? (${store.apps.size} seçili)"
        checkPc()
        if (paired && msg == null) status.text = status.text.toString() + "\n" + (if (granted) "Bildirim aktarma: açık" else "Bildirim aktarma için erişim gerekli")
    }

    /** Bilgisayar şu an ulaşılabilir mi? Kısa bir ping; sonuç durum satırına ve güç düğmelerine yansır. */
    private fun checkPc() {
        val key = store.key
        if (key == null) { pcOnline = false; pcState.visibility = View.GONE; setPower(false); return }
        pcState.visibility = View.VISIBLE; pcState.text = "Bilgisayar denetleniyor…"
        Thread {
            val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "ping"), 2500)
            val on = r.ok
            ui {
                pcOnline = on
                pcState.text = if (on) "🟢 Bilgisayar açık ve ulaşılabilir" else "🔴 Bilgisayara ulaşılamıyor (kapalı, uykuda ya da başka ağda)"
                setPower(on)
            }
        }.start()
    }

    private fun setPower(on: Boolean) {
        for (r in 0 until power.childCount) { val row = power.getChildAt(r) as? LinearLayout ?: continue
            for (i in 0 until row.childCount) row.getChildAt(i).apply { isEnabled = on; alpha = if (on) 1f else 0.45f } }
    }

    private fun powerPress(v: View, label: String, id: String, confirm: String) {
        val go = {
            val key = store.key
            if (key != null) Thread {
                val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "press").put("id", id), 6000)
                val ok = r.ok && r.reply?.optBoolean("ok", false) == true
                ui { Toast.makeText(this, if (ok) "$label gönderildi" else (r.reply?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Bilgisayara ulaşılamadı"), Toast.LENGTH_SHORT).show(); if (id != "pw_lock" && id != "pw_screen") v.postDelayed({ checkPc() }, 6000) }
            }.start()
        }
        if (confirm.isEmpty()) go() else AlertDialog.Builder(this).setTitle(label).setMessage(confirm).setPositiveButton("Evet") { _, _ -> go() }.setNegativeButton("Vazgeç", null).show()
    }

    private fun accessGranted() = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
    private fun batteryFree() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** Eşleşmeden sonra eksik izinleri sırayla ister; her adım oturum başına bir kez sorulur, kullanıcı reddederse ısrar edilmez. */
    private fun setupNext() {
        if (!store.paired) return
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true; requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8); return
        }
        if (!accessGranted() && !askedAccess) {
            askedAccess = true
            AlertDialog.Builder(this).setTitle("Bildirim erişimi")
                .setMessage("Telefon bildirimlerini bilgisayara aktarmak için Phoneix'e bildirim erişimi vermelisin. Açılan listede Phoneix'i aç.\n\nAnahtar gri ise: Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\", sonra tekrar dene.")
                .setPositiveButton("Ayarları aç") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .setNeutralButton("Uygulama bilgisi") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .setNegativeButton("Sonra", null).show()
        } else if (!askedTile && Build.VERSION.SDK_INT >= 33 && accessGranted() && batteryFree()) {
            askedTile = true; addTile()
        } else if (!askedPhone && accessGranted() && batteryFree() && (!Calls.hasStatePermission(this) || !Calls.hasAnswerPermission(this))) {
            askedPhone = true
            AlertDialog.Builder(this).setTitle("Arama izni")
                .setMessage("Gelen aramaları bilgisayarda görüp oradan açıp kapatabilmek için Phoneix'e telefon durumu ve arama yanıtlama izni gerekir. Arama geçmişin okunmaz.")
                .setPositiveButton("Devam") { _, _ -> requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS), 9) }
                .setNegativeButton("Sonra", null).show()
        } else if (accessGranted() && !batteryFree() && !askedBattery) {
            askedBattery = true
            AlertDialog.Builder(this).setTitle("Pil kısıtlaması")
                .setMessage("Android, Phoneix'i arka planda kapatırsa bildirimler gelmez. Bir sonraki pencerede \"İzin ver\"e bas.")
                .setPositiveButton("Devam") { _, _ -> askBattery() }
                .setNegativeButton("Sonra", null).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7 || res != RESULT_OK || data == null) return
        val list = ArrayList<Uri>()
        data.clipData?.let { c -> for (n in 0 until c.itemCount) list.add(c.getItemAt(n).uri) }
        data.data?.let { if (it !in list) list.add(it) }
        if (list.isNotEmpty()) Transfers.upload(this, list) { text, frac -> ui { status.text = text + if (frac in 0.001f..0.999f) " · %" + (frac * 100).toInt() else "" } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Phoneix ön plandayken Android panoyu okumaya izin verir: değiştiyse bilgisayara yolla
        if (hasFocus && store.paired && store.clipSync) Clip.autoSend(this)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(this, ClipTileService::class.java), "Panoyu gönder",
                Icon.createWithResource(this, R.drawable.ic_clip), mainExecutor) { }
        } catch (e: Exception) { /* sistem reddetti: kullanıcı karoyu elle ekleyebilir */ }
    }

    override fun onResume() { super.onResume(); DebugShot.track(this); refresh(); setupNext() }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let { askPair(Uri.parse(it)) } }
            .addOnFailureListener { refresh("QR okunamadı: ${it.message}") }
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("stopRing", false) == true) { Ringer.stop(this); i.removeExtra("stopRing") }
        val u = i?.data ?: return
        if (u.scheme == "phoneix" && u.host == "pair") askPair(u)
        i.data = null
    }

    /** Bağlantıyı doğrudan uygulamaya yönlendiren başka uygulamalara karşı: her zaman bilgisayar adı sorulur. */
    private fun askPair(u: Uri) {
        val d: JSONObject
        try {
            if (u.scheme != "phoneix" || u.host != "pair") throw IllegalArgumentException()
            d = JSONObject(String(Base64.decode(u.getQueryParameter("d"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        } catch (e: Exception) { refresh("Bu bir Phoneix kodu değil"); return }
        val name = d.optString("n", "Bilgisayar")
        val arr = d.optJSONArray("h")
        val hosts = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        val key = d.optString("k")
        val port = d.optInt("p", 47810)
        val ok = hosts.isNotEmpty() && try { Base64.decode(key, Base64.DEFAULT).size == 32 } catch (e: Exception) { false }
        if (!ok) { refresh("Kod geçersiz"); return }
        AlertDialog.Builder(this).setTitle("Eşleştir")
            .setMessage("\"$name\" bilgisayarıyla eşleşilsin mi?\n\n${hosts.joinToString(", ")}")
            .setPositiveButton("Eşleş") { _, _ -> pair(name, hosts, port, key) }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun pair(name: String, hosts: List<String>, port: Int, key: String) {
        refresh("Bağlanılıyor…")
        thread {
            val k = Base64.decode(key, Base64.DEFAULT)
            val r = Link.send(k, hosts, port, JSONObject().put("type", "hello").put("name", deviceName()))
            ui {
                if (r.ok) {
                    store.save(key, r.reply?.optString("name", name) ?: name, hosts, port)
                    r.host?.let { store.remember(it) }
                    refresh(); setupNext()
                } else refresh("Eşleşilemedi: ${r.error}\nTelefon ve bilgisayar aynı Wi-Fi'de mi? Windows güvenlik duvarı izin verdi mi?")
            }
        }
    }

    private fun sendTest() {
        val k = store.key ?: return
        status.text = "Gönderiliyor…"
        thread {
            val r = Link.send(k, store.hosts, store.port, JSONObject().put("type", "test").put("title", deviceName()).put("text", "Phoneix çalışıyor"))
            ui {
                if (r.ok) { r.host?.let { store.remember(it) }; refresh("Gönderildi ✓") }
                else refresh("Gönderilemedi: ${r.error}")
            }
        }
    }
}
