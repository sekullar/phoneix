package app.phoneix

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.lang.ref.WeakReference

/**
 * Yalnızca hata ayıklama derlemesinde: Phoneix'in KENDİ arayüzünü uygulama içi bir PNG'ye çizer (files/shot.png). Ekranın
 * geri kalanını (başka uygulamaları) görmez; geliştirici tasarımı kontrol ederken telefon ekranını kaydetmek zorunda kalmasın diye.
 */
class DebugShot : BroadcastReceiver() {
    companion object {
        private var current = WeakReference<Activity>(null)
        fun track(a: Activity) { current = WeakReference(a) }
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val a = current.get() ?: return
        val pending = goAsync()
        a.runOnUiThread {
            try {
                val v = a.window.decorView
                val bm = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bm))
                File(ctx.filesDir, "shot.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 90, it) }
            } catch (e: Exception) { /* sessiz: yalnızca geliştirici aracı */ }
            pending.finish()
        }
    }
}
