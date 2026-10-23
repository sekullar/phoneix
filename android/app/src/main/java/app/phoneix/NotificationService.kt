package app.phoneix

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.Executors

/** Yalnızca Phoneix'te seçilen uygulamaların bildirimlerini bilgisayara yollar. */
class NotificationService : NotificationListenerService() {
    private val io = Executors.newSingleThreadExecutor()
    private val seen = HashMap<String, Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = Store(this)
        val key = store.key ?: return
        if (sbn.packageName == packageName || sbn.packageName !in store.apps) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        // Aynı bildirimin güncellemesi (ilerleme çubuğu vb.) tekrar gitmesin
        val h = (title + "\u0000" + text).hashCode()
        if (seen.put(sbn.key, h) == h) return
        if (seen.size > 200) seen.clear()
        val app = label(sbn.packageName)
        val hide = store.hideText
        val msg = JSONObject().put("type", "notif").put("app", app.take(40))
            .put("title", if (hide) app else title.take(80))
            .put("text", if (hide) "Yeni bildirim" else text.take(200))
        val hosts = store.hosts; val port = store.port
        io.execute { Link.send(key, hosts, port, msg) }
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
