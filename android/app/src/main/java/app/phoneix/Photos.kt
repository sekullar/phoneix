package app.phoneix

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Fotoğrafları bilgisayarda gösterme (PROTOCOL.md "Photos"). Bilgisayar poll yanıtında komut yollar, telefon aynı adlı mesajla yanıtlar.
 * Yalnızca Ayarlar'daki "Fotoğrafları bilgisayarda göster" açıksa ve galeri izni varsa çalışır; yoksa `error` ile haber verir.
 */
object Photos {
    private val io = Executors.newSingleThreadExecutor()
    private val uriBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(ctx: Context) = perms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Poll döngüsünden çağrılır; iş kendi iş parçacığında yürür, poll'u bekletmez. */
    fun handle(ctx0: Context, key: ByteArray, store: Store, cmd: JSONObject) {
        val ctx = ctx0.applicationContext
        val type = cmd.optString("type"); val req = cmd.optString("req")
        io.execute {
            val reply = JSONObject().put("type", if (type == "photosave") "photosave" else type).put("req", req)
            fun fail(code: String) { if (type != "photosave") Link.send(key, store.hosts, store.port, reply.put("error", code)) }
            if (!store.photos) return@execute fail("off")
            if (!granted(ctx)) return@execute fail("perm")
            try {
                when (type) {
                    "photolist" -> sendList(ctx, key, store, reply, cmd.optInt("offset", 0).coerceAtLeast(0), cmd.optInt("limit", 48).coerceIn(1, 60))
                    "photofull" -> sendFull(ctx, key, store, reply, cmd.optString("id"))
                    "photosave" -> idUri(cmd.optString("id"))?.let { Transfers.upload(ctx, listOf(it)) { _, _ -> } }
                }
            } catch (e: Exception) { fail("fail") }
        }
    }

    private fun idUri(id: String) = id.toLongOrNull()?.takeIf { it >= 0 }?.let { ContentUris.withAppendedId(uriBase, it) }

    private fun jpeg(bmp: Bitmap, q: Int): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendList(ctx: Context, key: ByteArray, store: Store, reply: JSONObject, offset: Int, limit: Int) {
        val items = JSONArray(); var more = false
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE)
        ctx.contentResolver.query(uriBase, cols, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
            if (c.moveToPosition(offset)) {
                do {
                    if (items.length() >= limit) { more = true; break }
                    val id = c.getLong(0)
                    val o = JSONObject().put("id", id.toString()).put("name", c.getString(1) ?: "").put("t", c.getLong(2)).put("size", c.getLong(3))
                    try {
                        val th = ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(uriBase, id), Size(240, 240), null)
                        o.put("thumb", jpeg(th, 70)); th.recycle()
                    } catch (e: Exception) { /* küçük resim yok: bilgisayar boş kutu gösterir */ }
                    items.put(o)
                } while (c.moveToNext())
            }
        }
        reply.put("items", items).put("more", more)
        Link.send(key, store.hosts, store.port, reply, 20000)
    }

    /** Görüntüleme için en uzun kenarı 1600 px'e indirilmiş JPEG (EXIF yönü uygulanır); orijinal için "Bilgisayara kaydet" kullanılır. */
    private fun sendFull(ctx: Context, key: ByteArray, store: Store, reply: JSONObject, id: String) {
        val uri = idUri(id) ?: throw IllegalArgumentException("id")
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > 1600) { val r = 1600f / big; d.setTargetSize((info.size.width * r).toInt().coerceAtLeast(1), (info.size.height * r).toInt().coerceAtLeast(1)) }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        reply.put("data", jpeg(bmp, 82)); bmp.recycle()
        Link.send(key, store.hosts, store.port, reply, 30000)
    }
}
