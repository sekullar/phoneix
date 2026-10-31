package app.phoneix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Bilgisayardaki Pyramid, telefondaki Pyramid ile eşleşmek için QR yerine Phoneix köprüsünü kullanır:
 * eşleşme bağlantısı Phoneix'in şifreli bağlantısından gelir ve yalnızca Pyramid uygulamasına verilir
 * (açık paket adıyla, başka uygulama bu bağlantıyı göremez). Pyramid yine telefonda "eşleşilsin mi?" diye sorar.
 */
object Links {
    private const val PYRAMID = "com.sekullar.pyramid"
    private val shape = Regex("^pyramid://pair\\?d=[A-Za-z0-9_-]+$")

    fun openPyramidPairing(ctx: Context, url: String) {
        if (url.length > 4000 || !shape.matches(url)) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(PYRAMID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("links", "Eşleşme", NotificationManager.IMPORTANCE_HIGH))
        val installed = i.resolveActivity(ctx.packageManager) != null
        if (!installed) {
            nm.notify(7001, Notification.Builder(ctx, "links").setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Pyramid yüklü değil").setContentText("Eşleşmek için telefona Pyramid'i kur").setAutoCancel(true).setTimeoutAfter(60000).build())
            return
        }
        try { ctx.startActivity(i) } catch (e: Exception) { /* Android arka plandan açmayı engelleyebilir: bildirim kalır */ }
        val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7001, Notification.Builder(ctx, "links").setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Pyramid eşleşmesi").setContentText("Eşleşmeyi onaylamak için dokun")
            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).setTimeoutAfter(60000).build())
    }
}
