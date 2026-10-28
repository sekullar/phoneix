package app.phoneix

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Gelen/giden aramalar: durum telefon servisinden (READ_PHONE_STATE), arayan adı arama bildiriminden okunur.
 * Aç / Reddet / Kapat TelecomManager ile (ANSWER_PHONE_CALLS), olmazsa bildirimin kendi düğmeleriyle yapılır. Hoparlör: AudioManager.
 */
object Calls {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private lateinit var app: Context
    // Android dinleyiciyi yalnızca zayıf referansla tutar: burada saklanmazsa çöp toplayıcı siler ve arama olayları sessizce kesilir
    private var keepAlive: Any? = null
    @Volatile private var state = "idle"
    @Volatile private var who = ""
    private var answerPi: PendingIntent? = null
    private var declinePi: PendingIntent? = null
    private var hangPi: PendingIntent? = null
    private var lastSent = ""

    fun hasStatePermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    fun hasAnswerPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    /** Servis her döngüde çağırır: izin sonradan verilirse dinleyici o zaman kaydolur. */
    @Synchronized
    fun ensureWatcher(ctx: Context) {
        if (registered || !hasStatePermission(ctx)) return
        app = ctx.applicationContext
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(s: Int) { onState(s) }
                }
                keepAlive = cb
                tm.registerTelephonyCallback(io, cb)
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() { @Deprecated("Deprecated in Java") override fun onCallStateChanged(s: Int, n: String?) { onState(s) } }
                keepAlive = l
                main.post { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
            }
            registered = true
        } catch (e: SecurityException) { /* izin geri alındı */ }
    }

    private fun onState(s: Int) {
        val next = when (s) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "active"; else -> "idle" }
        if (next == state) return
        val was = state
        state = next
        if (next == "idle") { report("ended"); who = ""; answerPi = null; declinePi = null; hangPi = null; lastSent = ""; return }
        if (next == "ringing" && was == "idle") main.postDelayed({ if (state == "ringing") report("ringing") }, 250) // arayan adı bildirimi birazdan gelir
        else report(next)
    }

    /** Arama bildirimi geldi/güncellendi: arayan adı ve düğmeler. */
    fun onNotification(ctx: Context, sbn: StatusBarNotification) {
        app = ctx.applicationContext
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isNotEmpty()) who = title
        if (Build.VERSION.SDK_INT >= 31) {
            answerPi = n.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java) ?: answerPi
            declinePi = n.extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java) ?: declinePi
            hangPi = n.extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java) ?: hangPi
        }
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                t.contains("yanıtla") || t.contains("answer") || t.contains("kabul") -> answerPi = a.actionIntent
                t.contains("reddet") || t.contains("decline") || t.contains("reject") -> declinePi = a.actionIntent
                t.contains("kapat") || t.contains("bitir") || t.contains("hang") || t.contains("end") -> hangPi = a.actionIntent
            }
        }
        if (state != "idle") report(state, false)
    }

    private fun report(st: String, force: Boolean = true) {
        val key = st + "|" + who + "|" + speaker()
        if (!force && key == lastSent) return
        lastSent = key
        val ctx = app
        io.execute {
            val store = Store(ctx)
            val k = store.key ?: return@execute
            val msg = JSONObject().put("type", "call").put("state", st).put("who", who).put("speaker", speaker()).put("dnd", dndOn())
            Link.send(k, store.hosts, store.port, msg, 6000)
        }
    }

    /** Telefonda Rahatsız Etmeyin (ya da herhangi bir kesinti filtresi) açık mı: bilgisayar da aramayı göstermesin. */
    private fun dndOn(): Boolean = try {
        val f = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) { false }

    private fun speaker(): Boolean = try { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn } catch (e: Exception) { false }

    /** Bilgisayardan gelen komut: answer | reject | hangup. */
    fun command(ctx: Context, action: String) {
        app = ctx.applicationContext
        val tm = app.getSystemService(TelecomManager::class.java)
        when (action) {
            "answer" -> {
                var done = false
                if (hasAnswerPermission(app)) try { @Suppress("DEPRECATION") tm.acceptRingingCall(); done = true } catch (e: Exception) { /* bildirim düğmesine düş */ }
                if (!done) try { answerPi?.send() } catch (e: Exception) { /* yok */ }
            }
            "reject", "hangup" -> {
                var done = false
                if (hasAnswerPermission(app)) try { @Suppress("DEPRECATION") done = tm.endCall() } catch (e: Exception) { /* bildirim düğmesine düş */ }
                if (!done) try { (if (action == "reject") declinePi ?: hangPi else hangPi ?: declinePi)?.send() } catch (e: Exception) { /* yok */ }
            }
        }
    }
}
