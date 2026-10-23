package app.phoneix

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM, düzen: base64( nonce(12) | şifreli metin | etiket(16) ), AAD = "phoneix1". Bilgisayar tarafı: dynamic-bar/phoneix.js */
object Crypto {
    private val aad = "phoneix1".toByteArray()
    private val rnd = SecureRandom()

    fun seal(key: ByteArray, json: String): String {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return Base64.encodeToString(nonce + c.doFinal(json.toByteArray()), Base64.NO_WRAP)
    }

    /** Çözülemezse (yanlış anahtar, bozuk veri) null. Etiket doğrulaması bilgisayarı da doğrular. */
    fun open(key: ByteArray, b64: String): String? = try {
        val b = Base64.decode(b64, Base64.DEFAULT)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12))
        c.updateAAD(aad)
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { null }
}
