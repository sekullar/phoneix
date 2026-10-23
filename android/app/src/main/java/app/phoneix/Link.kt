package app.phoneix

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Bilgisayardaki Dynamic Bar'a şifreli mesaj yollar. Adresleri sırayla dener; yanıt doğru anahtarla çözülemezse reddeder. */
object Link {
    class Result(val ok: Boolean, val host: String?, val reply: JSONObject?, val error: String?)

    fun send(key: ByteArray, hosts: List<String>, port: Int, msg: JSONObject, readMs: Int = 4000): Result {
        msg.put("t", System.currentTimeMillis())
        val body = Crypto.seal(key, msg.toString())
        var err = "Bilgisayara ulaşılamadı"
        for (h in hosts) {
            try {
                val c = URL("http://$h:$port/phoneix/v1").openConnection() as HttpURLConnection
                c.connectTimeout = 1500; c.readTimeout = readMs; c.requestMethod = "POST"; c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode != 200) { err = "Bilgisayar isteği kabul etmedi (${c.responseCode}). Eşleştirmeyi yenile."; c.disconnect(); continue }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val plain = Crypto.open(key, text)
                if (plain == null) { err = "Yanıt doğrulanamadı"; continue }
                return Result(true, h, JSONObject(plain), null)
            } catch (e: Exception) { err = "Ulaşılamadı: ${e.javaClass.simpleName}" }
        }
        return Result(false, null, null, err)
    }
}
