package app.phoneix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/** "Telefonumu çaldır": alarm sesiyle, sessiz moddayken bile, en yüksek ses seviyesinde çalar. 60 sn sonra, bildirime dokununca veya bilgisayardan durdurulunca susar. */
object Ringer {
    private var ringtone: Ringtone? = null
    private var oldVolume = -1
    private val main = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop(appCtx) }
    private lateinit var appCtx: Context

    /** Telefonun varsayılan zil sesini bilgisayara gönderir (bir kez; 2 MB üstü ya da okunamayan dosya gönderilmez, bilgisayar kendi melodisine döner). */
    fun sendRingtone(ctx: Context) {
        try {
            val store = Store(ctx); val key = store.key ?: return
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE) ?: return
            val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return
            val msg = org.json.JSONObject().put("type", "ringtone").put("mime", mime).put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            Link.send(key, store.hosts, store.port, msg, 15000)
        } catch (e: Exception) { }
    }

    fun start(ctx0: Context) {
        val ctx = ctx0.applicationContext
        appCtx = ctx
        main.post {
            stop(ctx)
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true; volume = 1f; play()
                }
            } catch (e: Exception) { /* ses çalınamadı */ }
            announce(ctx)
            main.postDelayed(stopper, 60_000)
        }
    }

    fun stop(ctx: Context) {
        main.removeCallbacks(stopper)
        try { ringtone?.stop() } catch (e: Exception) { /* zaten durmuş */ }
        ringtone = null
        if (oldVolume >= 0) {
            try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0) } catch (e: Exception) { /* izin yok */ }
            oldVolume = -1
        }
        try { ctx.getSystemService(NotificationManager::class.java).cancel(RING_ID) } catch (e: Exception) { /* yok */ }
    }

    private const val RING_ID = 4242
    private fun announce(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ring", "Telefonumu çaldır", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("stopRing", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(RING_ID, Notification.Builder(ctx, "ring").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Telefon çalıyor")
            .setContentText("Durdurmak için dokun").setContentIntent(tap).setOngoing(true).setCategory(Notification.CATEGORY_ALARM).build())
    }
}
