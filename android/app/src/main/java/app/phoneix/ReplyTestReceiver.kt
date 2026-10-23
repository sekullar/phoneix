package app.phoneix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Yanıt testi": telefonun kendisine yanıtlanabilir bir bildirim düşürür. Bilgisayardan yazılan yanıt buraya gelir ve bildirimde görünür. */
class ReplyTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString() ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("files", "Gelen dosyalar", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(TEST_ID, Notification.Builder(ctx, "files").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Yanıt alındı").setContentText(text).setAutoCancel(true).build())
    }

    companion object {
        const val TEST_ID = 7001
        fun post(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("phx_test", "Yanıt testi", NotificationManager.IMPORTANCE_DEFAULT))
            val input = RemoteInput.Builder("reply").setLabel("Yanıtın").build()
            val pi = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, ReplyTestReceiver::class.java).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val action = Notification.Action.Builder(android.R.drawable.ic_menu_send, "Yanıtla", pi).addRemoteInput(input).build()
            nm.notify(TEST_ID, Notification.Builder(ctx, "phx_test").setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Phoneix Deneme").setContentText("Bu bir yanıt testidir, bilgisayardan yanıtla").addAction(action).build())
        }
    }
}
