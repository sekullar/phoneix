package app.phoneix

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/** Eşleştirme bilgisi uygulamanın özel alanında durur (yedeklenmez: allowBackup=false). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("phoneix", Context.MODE_PRIVATE)

    val paired get() = p.getString("key", null) != null
    val key: ByteArray? get() = p.getString("key", null)?.let { Base64.decode(it, Base64.DEFAULT) }
    val pcName get() = p.getString("pc", "") ?: ""
    val port get() = p.getInt("port", 47810)
    val hosts: List<String> get() {
        val a = JSONArray(p.getString("hosts", "[]"))
        val list = (0 until a.length()).map { a.getString(it) }
        val last = p.getString("last", null)
        return if (last != null && last in list) listOf(last) + list.filter { it != last } else list
    }

    fun save(key: String, pc: String, hosts: List<String>, port: Int) {
        p.edit().putString("key", key).putString("pc", pc).putString("hosts", JSONArray(hosts).toString()).putInt("port", port).remove("last").apply()
    }
    fun remember(host: String) = p.edit().putString("last", host).apply()
    /** Eşleştirmeyi siler; uygulama seçimi ve gizlilik ayarı kalır. */
    fun clear() = p.edit().remove("key").remove("pc").remove("hosts").remove("port").remove("last").apply()

    var clipSync: Boolean
        get() = p.getBoolean("clip", true)
        set(v) = p.edit().putBoolean("clip", v).apply()

    /** Telefon panosunda en son görülen metnin özeti: yalnızca gerçekten değişen pano bilgisayara gider. */
    var clipSeen: Int
        get() = p.getInt("clipSeen", Int.MIN_VALUE)
        set(v) = p.edit().putInt("clipSeen", v).apply()

    val apps: Set<String> get() = p.getStringSet("apps", emptySet())!!.toSet()
    fun setApps(s: Set<String>) = p.edit().putStringSet("apps", HashSet(s)).apply()
    /** Arayüz teması: "dark" (varsayılan) | "light" | "system" */
    var theme: String
        get() = p.getString("theme", "dark") ?: "dark"
        set(v) = p.edit().putString("theme", v).apply()
    /** Düşük önemli (sessiz) bildirimleri gönderme: WhatsApp "bağlantınız kontrol ediliyor" gibi sistem bildirimleri */
    var otpCopy: Boolean // SMS doğrulama kodlarını bilgisayarın panosuna kopyala (varsayılan kapalı)
        get() = p.getBoolean("otpCopy", false)
        set(v) = p.edit().putBoolean("otpCopy", v).apply()
    var pcAlert: Boolean // bilgisayara uzun süre ulaşılamayınca telefonda haber ver
        get() = p.getBoolean("pcAlert", true)
        set(v) = p.edit().putBoolean("pcAlert", v).apply()
    var skipSilent: Boolean
        get() = p.getBoolean("skipSilent", true)
        set(v) = p.edit().putBoolean("skipSilent", v).apply()
    var hideText: Boolean
        get() = p.getBoolean("hide", false)
        set(v) = p.edit().putBoolean("hide", v).apply()
}
