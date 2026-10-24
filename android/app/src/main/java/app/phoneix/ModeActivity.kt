package app.phoneix

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Telefonu ikinci ekran yapan modlar (yatay, tam ekran): sistem monitörü (bilgisayarın CPU/GPU/RAM/ağ durumu, saniyede bir)
 * ve deck (bilgisayarda tanımlanan tuşlar). Telefon yalnızca veri ister / "şu tuşa basıldı" der; ne çalışacağını bilgisayar bilir.
 */
class ModeActivity : Activity() {
    private lateinit var store: Store
    private var mode = "monitor"
    @Volatile private var running = false
    private lateinit var dot: View
    private lateinit var statusTxt: TextView
    private lateinit var monitor: LinearLayout
    private lateinit var deckBox: LinearLayout
    private lateinit var tabMon: TextView
    private lateinit var tabDeck: TextView
    private lateinit var cpu: GaugeView
    private lateinit var gpu: GaugeView
    private lateinit var ram: StatView
    private lateinit var vram: StatView
    private lateinit var temp: TempView
    private lateinit var net: StatView
    private lateinit var pageTabs: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var deckMsg: TextView
    private var pages: List<Page> = emptyList()
    private var page = 0

    companion object {
        /** Bilgisayardan gelen "şu modu aç" isteği. Android arka plandan etkinlik açmayı engelleyebilir; bu yüzden dokunulabilir bir bildirim de bırakılır. */
        fun open(ctx: android.content.Context, mode: String) {
            if (mode != "monitor" && mode != "deck") return
            val i = Intent(ctx, ModeActivity::class.java).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (e: Exception) { /* engellendi: bildirim kalır */ }
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("modes", "Modlar", android.app.NotificationManager.IMPORTANCE_HIGH))
            val pi = android.app.PendingIntent.getActivity(ctx, if (mode == "deck") 2 else 1, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7000, android.app.Notification.Builder(ctx, "modes").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(if (mode == "deck") "Deck" else "Sistem monitörü").setContentText("Açmak için dokun")
                .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).setTimeoutAfter(20000).build())
        }
    }

    private class Btn(val id: String, val label: String, val icon: String, val color: Int, val img: android.graphics.Bitmap?)
    private class Page(val name: String, val buttons: List<Btn>)

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Ui.init(this)
        store = Store(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        mode = intent.getStringExtra("mode") ?: "monitor"

        val root = FrameLayout(this)
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44), dp(14), dp(44), dp(14)) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // üst çubuk
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabMon = pill("📈  Monitör") { setMode("monitor") }
        tabDeck = pill("🎛️  Deck") { setMode("deck") }
        top.addView(tabMon); top.addView(tabDeck, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        dot = View(this).apply { background = Ui.round(this@ModeActivity, Ui.DIM, 6) }
        top.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        statusTxt = TextView(this).apply { textSize = 13f; setTextColor(Ui.DIM); text = "Bağlanılıyor…" }
        top.addView(statusTxt)
        val close = pill("✕") { finish() }
        top.addView(close, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // monitör gövdesi
        monitor = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cpu = GaugeView(this, "CPU"); gpu = GaugeView(this, "GPU")
        ram = StatView(this, "Bellek"); vram = StatView(this, "Ekran belleği"); temp = TempView(this); net = StatView(this, "Ağ", false)
        fun cardOf(v: View): LinearLayout = Ui.card(this).apply { addView(v, LinearLayout.LayoutParams(-1, -1)) }
        val gap = dp(10)
        monitor.addView(cardOf(cpu), LinearLayout.LayoutParams(0, -1, 1.15f))
        monitor.addView(cardOf(gpu), LinearLayout.LayoutParams(0, -1, 1.15f).apply { leftMargin = gap })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(a: View, b: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardOf(a), LinearLayout.LayoutParams(0, -1, 1f)); addView(cardOf(b), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap })
        }
        right.addView(row(ram, vram), LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(row(temp, net), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        monitor.addView(right, LinearLayout.LayoutParams(0, -1, 2f).apply { leftMargin = gap })
        col.addView(monitor, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        // deck gövdesi
        deckBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pageTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        deckBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pageTabs) }, LinearLayout.LayoutParams(-1, -2))
        grid = GridLayout(this).apply { columnCount = 4 }
        deckMsg = TextView(this).apply { textSize = 15f; setTextColor(Ui.DIM); gravity = Gravity.CENTER; text = "Deck yükleniyor…" }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid, FrameLayout.LayoutParams(-1, -2)) }
        deckBox.addView(FrameLayout(this).apply { addView(sv, FrameLayout.LayoutParams(-1, -1)); addView(deckMsg, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        col.addView(deckBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        setContentView(root); Ui.window(this)
        styleTabs()
        Ui.enter(listOf(monitor))
        hideBars()
    }

    private fun pill(text: String, on: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Ui.INK); gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9)); isClickable = true
        setOnClickListener { on() }; Ui.pressable(this)
    }

    private fun styleTabs() {
        fun st(t: TextView, active: Boolean) {
            t.background = if (active) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.A1, Ui.A2)).apply { cornerRadius = Ui.dpf(this@ModeActivity, 18f) }
            else Ui.round(this, Ui.GLASS2, 18, Ui.LINE)
            t.setTextColor(if (active) Color.WHITE else Ui.INK)
        }
        st(tabMon, mode == "monitor"); st(tabDeck, mode == "deck")
    }

    private fun setMode(m: String) {
        if (m == mode) return
        mode = m; styleTabs()
        val show = if (m == "monitor") monitor else deckBox; val hide = if (m == "monitor") deckBox else monitor
        hide.visibility = View.GONE; show.visibility = View.VISIBLE; Ui.enter(listOf(show))
        if (m == "deck") loadDeck()
    }

    private fun hideBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getStringExtra("mode")?.let { setMode(it) }
    }

    override fun onResume() {
        super.onResume(); DebugShot.track(this)
        if (mode == "deck") { monitor.visibility = View.GONE; deckBox.visibility = View.VISIBLE; loadDeck() }
        running = true
        thread { loop() }
    }
    override fun onPause() { running = false; super.onPause() }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun online(ok: Boolean, text: String) {
        dot.background = Ui.round(this, if (ok) Ui.OK else Ui.BAD, 6); statusTxt.text = text
    }

    /** Monitör modunda saniyede bir istatistik ister. */
    private fun loop() {
        var fails = 0
        while (running) {
            val key = store.key
            if (key == null) { ui { online(false, "Eşleşme yok") }; Thread.sleep(1500); continue }
            val t0 = System.currentTimeMillis()
            if (mode == "monitor") {
                val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "stats"), 6000)
                val s = r.reply?.optJSONObject("stats")
                if (r.ok && s != null) { fails = 0; r.host?.let { store.remember(it) }; ui { apply(s); online(true, store.pcName.ifEmpty { "Bilgisayar" }) } }
                else { fails++; ui { online(false, if (r.ok) "Veri bekleniyor…" else "Bilgisayara ulaşılamıyor, deneniyor…") } }
            } else ui { online(true, store.pcName.ifEmpty { "Bilgisayar" }) }
            val wait = if (fails > 0) 2000 else 1000 - (System.currentTimeMillis() - t0)
            if (wait > 0) try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
        }
    }

    private fun short(s: String, n: Int) = s.substringBefore(" w/").replace("(R)", "").replace("(TM)", "").replace("NVIDIA GeForce ", "").replace("AMD ", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }
    private fun gb(b: Double) = String.format("%.1f GB", b / 1073741824.0)
    private fun rate(b: Double) = when { b >= 1048576 -> String.format("%.1f MB/s", b / 1048576); b >= 1024 -> String.format("%.0f KB/s", b / 1024); else -> String.format("%.0f B/s", b) }

    private fun apply(s: JSONObject) {
        val cores = s.optJSONArray("cores")
        cpu.cores = if (cores == null) emptyList() else (0 until cores.length()).map { cores.optInt(it) }
        cpu.update(s.optInt("cpu").toFloat(), short(s.optString("cpuName"), 30), "${cpu.cores.size} çekirdek")
        val g = s.optJSONObject("gpu")
        if (g != null) {
            val u = if (g.isNull("util")) null else g.optDouble("util").toFloat()
            gpu.update(u, short(g.optString("name", "GPU"), 30), if (g.isNull("clock")) "" else "${g.optInt("clock")} MHz")
            if (g.isNull("vramTotal") || g.isNull("vramUsed")) vram.update("—", "Bilgi yok")
            else { val used = g.optDouble("vramUsed") * 1048576; val tot = g.optDouble("vramTotal") * 1048576; vram.update(gb(used), "/ ${gb(tot)}", (used / tot).toFloat()) }
        }
        val ct = if (s.isNull("cpuTemp") || !s.has("cpuTemp")) null else s.optDouble("cpuTemp").toFloat()
        val gt = if (g == null || g.isNull("temp")) null else g.optDouble("temp").toFloat()
        temp.update(ct, gt, if (ct == null) "CPU sensörü yok" else if (g != null && !g.isNull("power")) "GPU gücü ${String.format("%.0f", g.optDouble("power"))} W" else "")
        val r = s.optJSONObject("ram")
        if (r != null) { val used = r.optDouble("used"); val tot = r.optDouble("total"); ram.update(gb(used), "/ ${gb(tot)} · %${(used / tot * 100).toInt()}", (used / tot).toFloat()) }
        val n = s.optJSONObject("net")
        if (n != null) net.update("↓ " + rate(n.optDouble("down")), "↑ " + rate(n.optDouble("up")), null, n.optDouble("down").toFloat(), n.optDouble("up").toFloat())
    }

    // ---------- deck ----------
    private fun loadDeck() {
        val key = store.key ?: return
        thread {
            val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "deck"))
            val d = r.reply?.optJSONObject("deck")
            if (!r.ok || d == null) { ui { deckMsg.visibility = View.VISIBLE; deckMsg.text = "Bilgisayara ulaşılamadı" }; return@thread }
            val ps = d.optJSONArray("pages")
            val list = (0 until (ps?.length() ?: 0)).map { i ->
                val p = ps!!.getJSONObject(i); val bs = p.optJSONArray("buttons")
                Page(p.optString("name", "Sayfa"), (0 until (bs?.length() ?: 0)).map { j ->
                    val b = bs!!.getJSONObject(j)
                    Btn(b.optString("id"), b.optString("label"), b.optString("icon"), try { Color.parseColor(b.optString("color", "#2f8cff")) } catch (e: Exception) { Ui.A1 }, decodeImg(b.optString("img")))
                })
            }
            ui { pages = list; if (page >= list.size) page = 0; renderDeck() }
        }
    }

    /** "data:image/png;base64,..." biçimindeki uygulama simgesi. */
    private fun decodeImg(s: String): android.graphics.Bitmap? = try {
        if (s.isEmpty()) null else android.util.Base64.decode(s.substringAfter(","), android.util.Base64.DEFAULT).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (e: Exception) { null }

    private fun renderDeck() {
        pageTabs.removeAllViews()
        pages.forEachIndexed { i, p ->
            val t = TextView(this).apply {
                text = p.name; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (i == page) Color.WHITE else Ui.INK)
                background = if (i == page) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.A1, Ui.A2)).apply { cornerRadius = Ui.dpf(this@ModeActivity, 16f) } else Ui.round(this@ModeActivity, Ui.GLASS, 16)
                setOnClickListener { page = i; renderDeck() }; Ui.pressable(this)
            }
            pageTabs.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        grid.removeAllViews()
        val btns = pages.getOrNull(page)?.buttons ?: emptyList()
        deckMsg.visibility = if (btns.isEmpty()) View.VISIBLE else View.GONE
        deckMsg.text = if (pages.isEmpty()) "Deck yükleniyor…" else "Bu sayfada tuş yok. Bilgisayarda Phoneix → Deck'ten ekle."
        val tiles = ArrayList<View>()
        btns.forEach { b ->
            val tile = FrameLayout(this).apply {
                val dark = Color.rgb((Color.red(b.color) * 0.78f).toInt(), (Color.green(b.color) * 0.78f).toInt(), (Color.blue(b.color) * 0.82f).toInt())
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x55FFFFFF),
                    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(b.color, dark)).apply { cornerRadius = Ui.dpf(this@ModeActivity, 22f) }, null)
                elevation = Ui.dpf(this@ModeActivity, 4f)
                isClickable = true
                val box = LinearLayout(this@ModeActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                if (b.img != null) box.addView(android.widget.ImageView(this@ModeActivity).apply { setImageBitmap(b.img) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                else box.addView(TextView(this@ModeActivity).apply { text = b.icon.ifEmpty { "▫️" }; textSize = 34f; gravity = Gravity.CENTER })
                box.addView(TextView(this@ModeActivity).apply { text = b.label; textSize = 13f; setTextColor(Color.WHITE); paint.isFakeBoldText = true; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0) })
                addView(box, FrameLayout.LayoutParams(-1, -1))
                Ui.pressable(this, 0.92f)
                setOnClickListener { press(this, b) }
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(104); setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(tile, lp); tiles.add(tile)
        }
        Ui.enter(tiles)
    }

    private fun press(tile: View, b: Btn) {
        tile.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val key = store.key ?: return
        thread {
            val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "press").put("id", b.id), 6000)
            val ok = r.ok && r.reply?.optBoolean("ok", false) == true
            val err = if (!r.ok) r.error else r.reply?.optString("error")
            ui { feedback(tile, ok, err) }
        }
    }

    /** Başarılıysa tuş kısa süre parlar; değilse sallanır ve nedeni söylenir. */
    private fun feedback(tile: View, ok: Boolean, msg: String?) {
        if (ok) {
            tile.animate().alpha(0.55f).setDuration(80).withEndAction { tile.animate().alpha(1f).setDuration(280).start() }.start()
        } else {
            tile.animate().translationX(14f).setDuration(60).withEndAction {
                tile.animate().translationX(-14f).setDuration(80).withEndAction { tile.animate().translationX(0f).setDuration(80).start() }.start()
            }.start()
            Toast.makeText(this, msg ?: "Çalışmadı", Toast.LENGTH_SHORT).show()
        }
    }
}
