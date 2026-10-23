package app.phoneix

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Pano eşitleme. Bilgisayar → telefon: gelen metin panoya yazılır (arka planda yazmak serbest).
 * Telefon → bilgisayar: Android 10'dan beri yalnızca ön plandaki uygulama panoyu okuyabilir; bu yüzden gönderme
 * Hızlı Ayarlar karosundan, Paylaş menüsünden veya Phoneix açıkken yapılır.
 */
object Clip {
    @Volatile private var last = ""   // son eşitlenen metin: yankıyı (gönderdiğini geri almayı) önler
    private const val MAX = 200_000

    fun apply(ctx: Context, text: String) {
        if (text.isEmpty() || text.length > MAX) return
        last = text; Store(ctx).clipSeen = text.hashCode()
        Handler(Looper.getMainLooper()).post {
            try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Phoneix", text)) } catch (e: Exception) { /* pano kullanılamıyor */ }
        }
    }

    /** Yalnızca uygulama odaktayken çağır. Parola yöneticisi gibi "hassas" işaretli içerik gönderilmez. */
    fun read(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val extras = clip.description.extras
        if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return null
        if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return null
        val t = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
        return if (t.isEmpty() || t.length > MAX) null else t
    }

    /** Phoneix odaktayken çağrılır: pano son görülenden farklıysa yollar. İlk çalışmada yalnızca başlangıç değeri kaydedilir (eski bir panoyu bilgisayara basmamak için). */
    fun autoSend(ctx: Context) {
        val store = Store(ctx)
        val t = read(ctx) ?: return
        val h = t.hashCode()
        if (store.clipSeen == Int.MIN_VALUE) { store.clipSeen = h; return }
        if (h == store.clipSeen) return
        store.clipSeen = h
        send(ctx, t, false)
    }

    /** Bilgisayara yollar. force=false ise son eşitlenenle aynıysa yollamaz. onDone(başarılı mı) arka planda çağrılır. */
    fun send(ctx0: Context, text: String, force: Boolean, onDone: (Boolean) -> Unit = {}) {
        val ctx = ctx0.applicationContext
        if (!force && text == last) { onDone(true); return }
        thread {
            val store = Store(ctx)
            val key = store.key
            if (key == null) { onDone(false); return@thread }
            val r = Link.send(key, store.hosts, store.port, JSONObject().put("type", "clip").put("text", text), 8000)
            if (r.ok) last = text
            onDone(r.ok)
        }
    }
}
