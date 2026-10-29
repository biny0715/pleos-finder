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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.Subscription

/** 앱이 백그라운드에 있어도 내 위치를 계속 서버로 보내는 포그라운드 서비스. */
class LocationShareService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var pickupSubscription: Subscription? = null
    private var lastPickupState: PickupStatus.State? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        locationManager.removeUpdates(this)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
            }
        }
        if (pickupSubscription == null) {
            pickupSubscription = FinderServices.repository.observePickupStatus(FinderServices.me.id, ::onPickupStatus)
        }
        isRunning = true
        return START_STICKY
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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(PICKUP_CHANNEL_ID, getString(R.string.pickup_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notificationManager.notify(
            PICKUP_NOTIFICATION_ID,
            Notification.Builder(this, PICKUP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onLocationChanged(location: Location) {
        val shared = MemberLocation(
            memberId = FinderServices.me.id,
            latitude = location.latitude,
            longitude = location.longitude,
            updatedAt = System.currentTimeMillis(),
        )
        FinderServices.repository.publishMemberLocation(shared)
        lastShared = shared
        onShared?.invoke(shared)
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        pickupSubscription?.cancel()
        pickupSubscription = null
        isRunning = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.share_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.share_notification_title))
            .setContentText(getString(R.string.share_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "location_share"
        private const val NOTIFICATION_ID = 1
        private const val PICKUP_CHANNEL_ID = "pickup"
        private const val PICKUP_NOTIFICATION_ID = 2
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MIN_DISTANCE_M = 10f

        var isRunning = false
            private set
        var lastShared: MemberLocation? = null
            private set

        /** 화면이 떠 있는 동안만 MainActivity 가 등록한다. 메인 스레드에서 호출된다. */
        var onShared: ((MemberLocation) -> Unit)? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationShareService::class.java))
        }
    }
}
