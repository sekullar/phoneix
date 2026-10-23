package app.phoneix

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Hızlı Ayarlar karosundan açılan saydam ekran: odak alınca panoyu okuyup bilgisayara yollar ve kapanır (Android panoyu yalnızca odaktaki uygulamaya verir). */
class ClipSendActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val store = Store(this)
        val text = if (store.paired) Clip.read(this) else null
        when {
            !store.paired -> toast("Phoneix bilgisayarla eşleşmemiş")
            text == null -> toast("Panoda gönderilecek metin yok")
            else -> { store.clipSeen = text.hashCode(); Clip.send(this, text, true) { ok -> toast(if (ok) "Panoyu bilgisayara gönderdim ✓" else "Gönderilemedi: bilgisayar yok mu?") } }
        }
        finish()
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(applicationContext, t, Toast.LENGTH_SHORT).show() }
}
