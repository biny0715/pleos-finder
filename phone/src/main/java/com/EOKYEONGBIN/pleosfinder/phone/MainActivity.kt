package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.Invite
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val repository get() = FinderServices.repository
    private val me = FinderServices.me
    private val viewer = FinderServices.viewer

    private lateinit var inviteCard: View
    private lateinit var inviteText: TextView
    private lateinit var requestCard: View
    private lateinit var requestText: TextView
    private lateinit var pickupText: TextView
    private lateinit var permissionText: TextView
    private lateinit var shareText: TextView
    private lateinit var permissionButtons: Map<SharePermission, Button>

    private var invite: Invite? = null
    private var permission = SharePermission.OFF
    private var request: ShareRequest? = null
    private val subscriptions = mutableListOf<Subscription>()

    // 위치 권한을 받은 뒤 이어서 적용할 선택(초대 수락이면 acceptInvite = true)
    private var pendingChoice: Pair<SharePermission, Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val choice = pendingChoice
        pendingChoice = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && choice != null) {
            applyChoice(choice.first, choice.second)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.accountText).text = getString(R.string.account_format, me.name)
        inviteCard = findViewById(R.id.inviteCard)
        inviteText = findViewById(R.id.inviteText)
        requestCard = findViewById(R.id.requestCard)
        requestText = findViewById(R.id.requestText)
        pickupText = findViewById(R.id.pickupText)
        permissionText = findViewById(R.id.permissionText)
        shareText = findViewById(R.id.shareText)
        permissionButtons = mapOf(
            SharePermission.ALWAYS to findViewById(R.id.alwaysButton),
            SharePermission.TEMPORARY to findViewById(R.id.temporaryButton),
            SharePermission.OFF to findViewById(R.id.offButton),
        )

        findViewById<Button>(R.id.inviteAlwaysButton).setOnClickListener { choose(SharePermission.ALWAYS, acceptInvite = true) }
        findViewById<Button>(R.id.inviteTemporaryButton).setOnClickListener { choose(SharePermission.TEMPORARY, acceptInvite = true) }
        findViewById<Button>(R.id.inviteDeclineButton).setOnClickListener {
            invite?.let { repository.publishInvite(it.copy(status = Invite.Status.DECLINED, updatedAt = System.currentTimeMillis())) }
        }
        findViewById<Button>(R.id.acceptRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = true, pending = request)
        }
        findViewById<Button>(R.id.declineRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = false, pending = request)
        }
        permissionButtons.forEach { (value, button) -> button.setOnClickListener { choose(value, acceptInvite = false) } }
    }

    override fun onStart() {
        super.onStart()
        LocationShareService.onStateChanged = ::renderShare
        subscriptions += repository.observeInvite(me.id) {
            invite = it
            render()
        }
        subscriptions += repository.observePermission(me.id, viewer.id) {
            permission = it
            // 앱을 다시 열었을 때 허용 상태인데 서비스가 꺼져 있으면 다시 켠다.
            if (it != SharePermission.OFF && !LocationShareService.isRunning && hasLocationPermission()) {
                LocationShareService.start(this)
            }
            render()
        }
        subscriptions += repository.observeShareRequest(me.id) {
            request = it
            render()
        }
        subscriptions += repository.observePickupStatus(me.id, ::renderPickup)
        render()
    }

    override fun onStop() {
        LocationShareService.onStateChanged = null
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        super.onStop()
    }

    private fun choose(value: SharePermission, acceptInvite: Boolean) {
        if (value == SharePermission.OFF || hasLocationPermission()) {
            applyChoice(value, acceptInvite)
            return
        }
        pendingChoice = value to acceptInvite
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun applyChoice(value: SharePermission, acceptInvite: Boolean) {
        if (acceptInvite) {
            invite?.let { repository.publishInvite(it.copy(status = Invite.Status.ACCEPTED, updatedAt = System.currentTimeMillis())) }
        }
        repository.setPermission(me.id, viewer.id, value)
        // 꺼짐이면 서비스가 허용 상태 변화를 보고 스스로 멈춘다.
        if (value != SharePermission.OFF) LocationShareService.start(this)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val currentInvite = invite
        inviteCard.visibility = if (currentInvite?.status == Invite.Status.PENDING) View.VISIBLE else View.GONE
        if (currentInvite != null) {
            inviteText.text = "${currentInvite.inviterName}가 가족 그룹에 초대했어요.\n${currentInvite.inviterName}에게 내 위치를 어떻게 공유할까요?"
        }

        val pending = request?.takeIf { it.isPendingFresh() }
        requestCard.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            requestText.text = "${pending.requesterName}가 픽업하러 가려고 해요.\n${pending.requesterName}에게 위치를 공유할까요? 픽업이 끝나면 공유도 끝나요."
        }

        val joined = currentInvite?.status == Invite.Status.ACCEPTED
        val label = when (permission) {
            SharePermission.ALWAYS -> getString(R.string.permission_always)
            SharePermission.TEMPORARY -> getString(R.string.permission_temporary)
            SharePermission.OFF -> getString(R.string.permission_off)
        }
        permissionText.text = if (joined) "${viewer.name}에게 내 위치: $label" else "가족 그룹 초대를 기다리는 중"
        permissionButtons.forEach { (value, button) ->
            button.visibility = if (joined) View.VISIBLE else View.GONE
            button.isEnabled = value != permission
        }
        renderShare()
    }

    private fun renderShare() {
        val last = LocationShareService.lastShared
        shareText.text = when {
            !LocationShareService.isRunning -> getString(R.string.share_off)
            !LocationShareService.isSharing -> "대기 중 · 공유 요청이 오면 알려 드릴게요"
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
