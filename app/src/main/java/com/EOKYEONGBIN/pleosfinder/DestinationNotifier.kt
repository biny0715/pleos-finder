package com.EOKYEONGBIN.pleosfinder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.iGa
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR

/**
 * 그룹 목적지 공유 알림. 운전 중에는 Finder 화면 대신 내비가 떠 있을 때가 많아,
 * 화면의 팝업과 함께 알림(안내 시작 / 거절)으로도 묻는다.
 */
object DestinationNotifier {

    private const val CHANNEL_ID = "destination_share"
    const val ACTION_RESPOND = "com.EOKYEONGBIN.pleosfinder.DESTINATION_RESPOND"
    const val EXTRA_GROUP_ID = "groupId"
    const val EXTRA_SHARE_ID = "shareId"
    const val EXTRA_ACCEPT = "accept"

    fun notify(context: Context, group: FinderGroup, share: DestinationShare, fromName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "그룹 목적지 공유", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_launcher_monochrome)
            .setContentTitle("${fromName.iGa} '${group.name}' 그룹에 목적지를 공유했어요")
            .setContentText("${share.name} · 이 목적지로 안내를 시작할까요?")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "안내 시작", respond(context, share, accept = true))
            .addAction(0, "거절", respond(context, share, accept = false))
            .build()
        manager.notify(share.id.hashCode(), notification)
    }

    fun cancel(context: Context, share: DestinationShare) {
        NotificationManagerCompat.from(context).cancel(share.id.hashCode())
    }

    private fun respond(context: Context, share: DestinationShare, accept: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (share.id + accept).hashCode(),
            Intent(context, DestinationActionReceiver::class.java)
                .setAction(ACTION_RESPOND)
                .putExtra(EXTRA_GROUP_ID, share.groupId)
                .putExtra(EXTRA_SHARE_ID, share.id)
                .putExtra(EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** 알림의 '안내 시작' / '거절' */
class DestinationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = (context.applicationContext as PleosFinderApp).session
        val groupId = intent.getStringExtra(DestinationNotifier.EXTRA_GROUP_ID) ?: return
        val shareId = intent.getStringExtra(DestinationNotifier.EXTRA_SHARE_ID) ?: return
        val share = session.destinationShares[groupId]?.takeIf { it.id == shareId } ?: return
        if (intent.getBooleanExtra(DestinationNotifier.EXTRA_ACCEPT, false)) session.acceptDestination(share) else session.declineDestination(share)
        DestinationNotifier.cancel(context, share)
    }
}
