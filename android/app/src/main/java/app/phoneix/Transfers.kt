package app.phoneix

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Dosya aktarımı: telefondan bilgisayara `put` parçaları, bilgisayardan telefona `get` parçaları (PROTOCOL.md). */
object Transfers {
    private const val CHUNK = 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()
    private val rnd = SecureRandom()

    private fun newId() = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun ok(r: Link.Result) = r.ok && r.reply?.optBoolean("ok", false) == true

    /** Seçilen dosyaları sırayla bilgisayara yollar. onUpdate her adımda durum satırını verir (herhangi bir iş parçacığından çağrılır). */
    fun upload(ctx0: Context, uris: List<Uri>, onUpdate: (String, Float) -> Unit) {
        val ctx = ctx0.applicationContext
        io.execute {
            val store = Store(ctx)
            val key = store.key
            if (key == null) { onUpdate("Önce bilgisayarla eşleş", 0f); return@execute }
            var failed = 0
            for ((i, uri) in uris.withIndex()) {
                var name = uri.lastPathSegment ?: "dosya"; var size = 0L
                try {
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) {
                            it.getString(0)?.let { n -> name = n }
                            if (!it.isNull(1)) size = it.getLong(1)
                        }
                    }
                } catch (e: Exception) { /* ad kalır */ }
                val label = if (uris.size > 1) "(${i + 1}/${uris.size}) $name" else name
                val id = newId()
                try {
                    val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Dosya açılamadı")
                    stream.use { s ->
                        val buf = ByteArray(CHUNK)
                        var seq = 0; var sent = 0L
                        while (true) {
                            val n = readFully(s, buf)
                            val last = n < CHUNK
                            val msg = JSONObject().put("type", "put").put("id", id).put("name", name).put("size", size).put("seq", seq)
                                .put("last", last).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                            var r = Link.send(key, store.hosts, store.port, msg, 30000)
                            var tries = 1
                            while (!ok(r) && tries < 3 && r.reply == null) { r = Link.send(key, store.hosts, store.port, JSONObject(msg.toString()), 30000); tries++ }
                            if (!ok(r)) throw IllegalStateException(r.error ?: "Bilgisayar reddetti (${r.reply?.optString("error")})")
                            r.host?.let { store.remember(it) }
                            sent += n; seq++
                            onUpdate(label, if (size > 0) (sent.toFloat() / size).coerceIn(0f, 1f) else 0f)
                            if (last) break
                        }
                    }
                    onUpdate("$label · gitti ✓", 1f)
                } catch (e: Exception) { failed++; onUpdate("$label · hata: ${e.message}", 0f) }
            }
            if (uris.size > 1) onUpdate(if (failed == 0) "${uris.size} dosya gitti ✓" else "${uris.size - failed}/${uris.size} dosya gitti", 1f)
        }
    }

    /** Bilgisayarın hazırladığı bir dosyayı indirip İndirilenler/Phoneix içine kaydeder. Başarılıysa true. */
    fun receive(ctx: Context, key: ByteArray, hosts: List<String>, port: Int, id: String, name: String, onProgress: (Float) -> Unit): Boolean {
        val cr = ctx.contentResolver
        val ext = name.substringAfterLast('.', "").lowercase()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Phoneix")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            cr.openOutputStream(uri)!!.use { out ->
                var seq = 0
                while (true) {
                    var r = Link.send(key, hosts, port, JSONObject().put("type", "get").put("id", id).put("seq", seq), 30000)
                    if (!ok(r)) r = Link.send(key, hosts, port, JSONObject().put("type", "get").put("id", id).put("seq", seq), 30000)
                    if (!ok(r)) throw IllegalStateException(r.error ?: "indirilemedi")
                    val rep = r.reply!!
                    out.write(Base64.decode(rep.optString("data"), Base64.DEFAULT))
                    val size = rep.optLong("size", 0)
                    seq++
                    if (size > 0) onProgress(((seq.toLong() * CHUNK).toFloat() / size).coerceIn(0f, 1f))
                    if (rep.optBoolean("last", true)) break
                }
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Link.send(key, hosts, port, JSONObject().put("type", "done").put("id", id))
            return true
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (x: Exception) { /* yarım dosya kalırsa MediaStore temizler */ }
            return false
        }
    }
}
