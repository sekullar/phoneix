package app.phoneix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** "Paylaş → Phoneix": seçilen dosyaları bilgisayara yollar. Aktarım bitene kadar açık kalmalı (paylaşılan dosyaya erişim bu ekrana bağlı). */
class ShareActivity : Activity() {
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @Suppress("DEPRECATION")
    private fun uris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        i.clipData?.let { c -> for (n in 0 until c.itemCount) c.getItemAt(n).uri?.let { out.add(it) } }
        if (out.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
            } else {
                (i.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { out.add(it) }
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            }
        }
        return out.toList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(24), dp(80), dp(24), dp(24))
        }
        root.addView(TextView(this).apply { text = "Bilgisayara gönderiliyor"; textSize = 22f; setTextColor(Color.WHITE) })
        val line = TextView(this).apply { textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, dp(16), 0, dp(12)) }
        root.addView(line)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        val store = Store(this)
        val list = uris(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            store.paired && list.isEmpty() && !sharedText.isNullOrEmpty() -> {
                line.text = "Metin gönderiliyor…"
                Clip.send(this, sharedText, true) { ok -> runOnUiThread { line.text = if (ok) "Metin bilgisayarın panosunda ✓" else "Gönderilemedi: bilgisayar yok mu?"; if (ok) finish() } }
            }
            !store.paired -> line.text = "Önce Phoneix'i bilgisayarla eşleştir."
            list.isEmpty() -> line.text = "Bu içerik gönderilemez (yalnızca dosyalar)."
            else -> {
                line.text = "Hazırlanıyor…"
                Transfers.upload(this, list) { text, frac ->
                    runOnUiThread { line.text = text; bar.progress = (frac * 1000).toInt() }
                }
            }
        }
    }
}
