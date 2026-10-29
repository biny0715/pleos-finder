package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription

/**
 * 공유를 허용한 동안 떠 있는 포그라운드 서비스.
 * - 항상 허용: 위치를 계속 서버로 보낸다.
 * - 일시적 허용: 대기하다가 공유 요청이 오면 알림을 띄우고, 수락한 요청이 끝날 때까지만 위치를 보낸다.
 * - 꺼짐: 서비스를 멈춘다.
 * 픽업 상태가 바뀌면 "아빠가 픽업하러 오고 있어요" / "도착했어요" 알림도 띄운다.
 */
class LocationShareService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private val subscriptions = mutableListOf<Subscription>()
    private var lastPickupState: PickupStatus.State? = null
    private var notifiedRequestAt = 0L
    private var receivingUpdates = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.share_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(PICKUP_CHANNEL_ID, getString(R.string.pickup_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        when (intent?.action) {
            ACTION_ACCEPT_REQUEST -> respondToRequest(this, accept = true)
            ACTION_DECLINE_REQUEST -> respondToRequest(this, accept = false)
        }
        if (subscriptions.isEmpty()) {
            val repository = FinderServices.repository
            val me = FinderServices.me
            subscriptions += repository.observePermission(me.id, FinderServices.viewer.id, ::onPermission)
            subscriptions += repository.observeShareRequest(me.id, ::onShareRequest)
            subscriptions += repository.observePickupStatus(me.id, ::onPickupStatus)
        }
        isRunning = true
        notifyStateChanged()
        return START_STICKY
    }

    override fun onDestroy() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        stopLocationUpdates()
        notificationManager.cancel(REQUEST_NOTIFICATION_ID)
        isRunning = false
        isSharing = false
        notifyStateChanged()
        super.onDestroy()
    }

    private fun onPermission(value: SharePermission) {
        permission = value
        if (value == SharePermission.OFF) {
            stopSelf()
            return
        }
        refreshSharing()
    }

    private fun onShareRequest(request: ShareRequest?) {
        shareRequest = request
        if (request != null && request.isPendingFresh() && request.updatedAt != notifiedRequestAt) {
            notifiedRequestAt = request.updatedAt
            showRequestNotification(request)
        } else if (request?.state != ShareRequest.State.PENDING) {
            notificationManager.cancel(REQUEST_NOTIFICATION_ID)
        }
        refreshSharing()
    }

    /** 항상 허용이거나, 일시적 허용에서 수락한 요청이 진행 중일 때만 위치를 보낸다. */
    private fun refreshSharing() {
        val share = permission == SharePermission.ALWAYS ||
            (permission == SharePermission.TEMPORARY && shareRequest?.isActiveShare() == true)
        if (share) startLocationUpdates() else stopLocationUpdates()
        isSharing = share
        notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification())
        notifyStateChanged()
    }

    private fun startLocationUpdates() {
        if (receivingUpdates) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.isProviderEnabled(provider)) {
                // 멈춰 있어도 주기적으로 보내야 차량 앱이 '최신 위치'로 인정한다(MIN_DISTANCE_M = 0).
                locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
            }
        }
        receivingUpdates = true
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(this)
        receivingUpdates = false
    }

    override fun onLocationChanged(location: Location) {
        if (!isSharing) return
        val shared = MemberLocation(
            memberId = FinderServices.me.id,
            latitude = location.latitude,
            longitude = location.longitude,
            updatedAt = System.currentTimeMillis(),
        )
        FinderServices.repository.publishMemberLocation(shared)
        lastShared = shared
        notifyStateChanged()
    }

    /** 픽업 상태가 바뀌면 알림을 띄운다. 서비스 시작 직후 받는 오래된 값은 무시한다. */
    private fun onPickupStatus(status: PickupStatus) {
        if (status.state == lastPickupState) return
        lastPickupState = status.state
        if (status.isStale()) return
        val text = when (status.state) {
            PickupStatus.State.ON_THE_WAY -> "${status.driverName}가 픽업하러 오고 있어요"
            PickupStatus.State.ARRIVED -> "${status.driverName}가 도착했어요"
            PickupStatus.State.CANCELLED -> return
        }
        notificationManager.notify(
            PICKUP_NOTIFICATION_ID,
            Notification.Builder(this, PICKUP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showRequestNotification(request: ShareRequest) {
        val accept = PendingIntent.getService(
            this, 1, Intent(this, LocationShareService::class.java).setAction(ACTION_ACCEPT_REQUEST), PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getService(
            this, 2, Intent(this, LocationShareService::class.java).setAction(ACTION_DECLINE_REQUEST), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_mylocation)
        notificationManager.notify(
            REQUEST_NOTIFICATION_ID,
            Notification.Builder(this, PICKUP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("${request.requesterName}가 픽업하러 가려고 해요")
                .setContentText("${request.requesterName}에게 위치를 공유할까요? 픽업이 끝나면 공유도 끝나요.")
                .setContentIntent(openAppIntent())
                .addAction(Notification.Action.Builder(icon, getString(R.string.action_accept_request), accept).build())
                .addAction(Notification.Action.Builder(icon, getString(R.string.action_decline_request), decline).build())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun startInForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val viewer = FinderServices.viewer.name
        val text = if (isSharing) "${viewer}에게 내 위치를 공유하고 있어요." else "공유 요청이 오면 알려 드릴게요."
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(if (isSharing) R.string.share_notification_title else R.string.standby_notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "location_share"
        private const val PICKUP_CHANNEL_ID = "pickup"
        private const val NOTIFICATION_ID = 1
        private const val PICKUP_NOTIFICATION_ID = 2
        private const val REQUEST_NOTIFICATION_ID = 3
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MIN_DISTANCE_M = 0f
        private const val ACTION_ACCEPT_REQUEST = "com.EOKYEONGBIN.pleosfinder.phone.ACCEPT_REQUEST"
        private const val ACTION_DECLINE_REQUEST = "com.EOKYEONGBIN.pleosfinder.phone.DECLINE_REQUEST"

        var isRunning = false
            private set
        var isSharing = false
            private set
        var permission = SharePermission.OFF
            private set
        var shareRequest: ShareRequest? = null
            private set
        var lastShared: MemberLocation? = null
            private set

        /** 화면이 떠 있는 동안만 MainActivity 가 등록한다. 메인 스레드에서 호출된다. */
        var onStateChanged: (() -> Unit)? = null

        private fun notifyStateChanged() = onStateChanged?.invoke()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationShareService::class.java))
        }

        /** 화면 카드나 알림 버튼에서 공유 요청에 답한다. */
        fun respondToRequest(context: Context, accept: Boolean, pending: ShareRequest? = shareRequest) {
            val request = pending?.takeIf { it.isPendingFresh() } ?: return
            val state = if (accept) ShareRequest.State.ACCEPTED else ShareRequest.State.DECLINED
            FinderServices.repository.publishShareRequest(request.copy(state = state, updatedAt = System.currentTimeMillis()))
            context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION_ID)
        }
    }
}
