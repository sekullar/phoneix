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
    fun clear() = p.edit().clear().apply()
}
