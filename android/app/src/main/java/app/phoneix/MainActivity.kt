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
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import androidx.core.app.NotificationManagerCompat
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var test: Button
    private lateinit var unpair: Button
    private lateinit var access: Button
    private lateinit var upload: Button
    private lateinit var apps: Button
    private lateinit var battery: Button
    private var askedAccess = false
    private var askedBattery = false
    private var askedNotif = false
    private var askedTile = false
    private var askedPhone = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun deviceName() = Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(TextView(this).apply { text = "Phoneix"; textSize = 32f; setTextColor(Color.WHITE) })
        status = TextView(this).apply {
            textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, dp(16), 0, dp(24)); gravity = Gravity.CENTER
        }
        root.addView(status)
        fun btn(t: String, f: () -> Unit): Button {
            val b = Button(this).apply { text = t; setOnClickListener { f() } }
            root.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            return b
        }
        btn("QR ile eşleştir") { scanQr() }
        test = btn("Test bildirimi gönder") { sendTest() }
        unpair = btn("Eşleştirmeyi kaldır") { store.clear(); refresh("Eşleştirme kaldırıldı") }
        upload = btn("Bilgisayara dosya gönder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 7)
        }
        btn("Yanıt testi (telefona bildirim düşür)") { ReplyTestReceiver.post(this) }
        access = btn("Bildirim erişimi ver") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        battery = btn("Pil kısıtlamasını kaldır") { askBattery() }
        apps = btn("Hangi uygulamalar?") { startActivity(Intent(this, AppsActivity::class.java)) }
        root.addView(Switch(this).apply {
            text = "Bildirim metnini gizle (yalnızca uygulama adı gider)"; setTextColor(Color.LTGRAY)
            isChecked = store.hideText; setOnCheckedChangeListener { _, on -> store.hideText = on }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(Switch(this).apply {
            text = "Panoyu bilgisayarla eşitle"; setTextColor(Color.LTGRAY)
            isChecked = store.clipSync; setOnCheckedChangeListener { _, on -> store.clipSync = on }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(TextView(this).apply {
            text = "Bilgisayarda kopyaladığın kendiliğinden telefona gelir. Telefonda kopyaladığını göndermek için Hızlı Ayarlar'daki \"Panoyu gönder\" karosuna dokun (ya da Paylaş → Phoneix)."
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(6), 0, 0)
        })
        setContentView(root)
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
        upload.isEnabled = paired
        apps.text = "Hangi uygulamalar? (${store.apps.size} seçili)"
        if (paired && msg == null) status.text = status.text.toString() + "\n" + (if (granted) "Bildirim aktarma: açık" else "Bildirim aktarma için erişim gerekli")
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

    override fun onResume() { super.onResume(); refresh(); setupNext() }

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
