package app.phoneix

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.security.SecureRandom

/** Yanıtlanabilir bildirimler (WhatsApp, SMS vb.): bildirimin kendi "Yanıtla" eylemini bellekte tutar, bilgisayardan gelen metni oraya yazar. */
object Replies {
    private class Entry(val key: String, val action: Notification.Action, val inputs: Array<RemoteInput>, val free: RemoteInput)
    private val map = LinkedHashMap<String, Entry>()
    private val rnd = SecureRandom()

    /** Bildirimde serbest metinli bir yanıt eylemi varsa kimliğini verir, yoksa null. */
    @Synchronized
    fun register(sbn: StatusBarNotification): String? {
        val acts = sbn.notification.actions ?: return null
        for (a in acts) {
            val ins = a.remoteInputs ?: continue
            val free = ins.firstOrNull { it.allowFreeFormInput } ?: continue
            map.entries.removeIf { it.value.key == sbn.key } // aynı sohbetin eski kaydı düşer
            val id = ByteArray(6).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            map[id] = Entry(sbn.key, a, ins, free)
            while (map.size > 40) map.remove(map.keys.first())
            return id
        }
        return null
    }

    /** Yanıtı bildirimin eylemine yollar. Bildirim kapanmış veya uygulama kabul etmemişse hata metni döner, başarılıysa null. */
    @Synchronized
    fun send(ctx: Context, id: String, text: String): String? {
        val e = map[id] ?: return "Bildirim artık yok"
        return try {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(e.free.resultKey, text) }
            RemoteInput.addResultsToIntent(e.inputs, intent, results)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            e.action.actionIntent.send(ctx, 0, intent)
            null
        } catch (ex: Exception) { "Uygulama yanıtı kabul etmedi" }
    }

    @Synchronized fun forget(key: String) { map.entries.removeIf { it.value.key == key } }
}
