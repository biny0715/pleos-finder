package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var pickupText: TextView
    private lateinit var shareText: TextView
    private lateinit var shareButton: Button
    private var pickupSubscription: Subscription? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            LocationShareService.start(this)
            renderShare()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickupText = findViewById(R.id.pickupText)
        shareText = findViewById(R.id.shareText)
        shareButton = findViewById(R.id.shareButton)

        shareButton.setOnClickListener {
            if (LocationShareService.isRunning) {
                LocationShareService.stop(this)
                shareButton.post(::renderShare)
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocationShareService.onShared = { renderShare() }
        pickupSubscription = FinderServices.repository.observePickupStatus(FinderServices.me.id, ::renderPickup)
        renderShare()
    }

    override fun onStop() {
        LocationShareService.onShared = null
        pickupSubscription?.cancel()
        pickupSubscription = null
        super.onStop()
    }

    private fun renderShare() {
        val running = LocationShareService.isRunning
        shareButton.setText(if (running) R.string.action_stop_sharing else R.string.action_start_sharing)
        val last: MemberLocation? = LocationShareService.lastShared
        shareText.text = when {
            !running -> getString(R.string.share_off)
            last == null -> "위치 공유 중 · 위치 확인 중"
            else -> "위치 공유 중 · 마지막 전송 ${TIME_FORMAT.format(Date(last.updatedAt))}\n%.5f, %.5f".format(last.latitude, last.longitude)
        }
    }

    private fun renderPickup(status: PickupStatus) {
        if (status.isStale()) {
            pickupText.setText(R.string.pickup_none)
            return
        }
        pickupText.text = when (status.state) {
            PickupStatus.State.ON_THE_WAY -> {
                val distance = status.remainDistanceMeters
                val time = status.remainTimeSeconds
                val remain = if (distance == null || time == null) "" else
                    "\n남은 거리 %.1fkm · 약 %d분".format(distance / 1000.0, (time + 59) / 60)
                "${status.driverName}가 픽업하러 오고 있어요$remain"
            }
            PickupStatus.State.ARRIVED -> "${status.driverName}가 도착했어요"
            PickupStatus.State.CANCELLED -> getString(R.string.pickup_none)
        }
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }
}
