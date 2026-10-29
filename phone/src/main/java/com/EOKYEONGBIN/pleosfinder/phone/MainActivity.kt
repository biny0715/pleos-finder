package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.Invite
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
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
    private lateinit var inviteTitle: TextView
    private lateinit var inviteDetail: TextView
    private lateinit var requestCard: View
    private lateinit var requestTitle: TextView
    private lateinit var requestDetail: TextView
    private lateinit var pickupHeadline: TextView
    private lateinit var pickupDetail: TextView
    private lateinit var pickupMetrics: View
    private lateinit var remainDistance: TextView
    private lateinit var remainTime: TextView
    private lateinit var permissionTitle: TextView
    private lateinit var permissionGroup: RadioGroup
    private lateinit var permissionDetail: TextView
    private lateinit var shareDot: View
    private lateinit var shareText: TextView

    private var invite: Invite? = null
    private var permission = SharePermission.OFF
    private var request: ShareRequest? = null
    private val subscriptions = mutableListOf<Subscription>()

    // 화면을 다시 그리면서 세그먼트를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    // 위치 권한을 받은 뒤 이어서 적용할 선택(초대 수락이면 acceptInvite = true)
    private var pendingChoice: Pair<SharePermission, Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val choice = pendingChoice
        pendingChoice = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && choice != null) {
            applyChoice(choice.first, choice.second)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.accountText).text = getString(R.string.account_format, me.name)
        inviteCard = findViewById(R.id.inviteCard)
        inviteTitle = findViewById(R.id.inviteTitle)
        inviteDetail = findViewById(R.id.inviteDetail)
        requestCard = findViewById(R.id.requestCard)
        requestTitle = findViewById(R.id.requestTitle)
        requestDetail = findViewById(R.id.requestDetail)
        pickupHeadline = findViewById(R.id.pickupHeadline)
        pickupDetail = findViewById(R.id.pickupDetail)
        pickupMetrics = findViewById(R.id.pickupMetrics)
        remainDistance = findViewById(R.id.remainDistance)
        remainTime = findViewById(R.id.remainTime)
        permissionTitle = findViewById(R.id.permissionTitle)
        permissionGroup = findViewById(R.id.permissionGroup)
        permissionDetail = findViewById(R.id.permissionDetail)
        shareDot = findViewById(R.id.shareDot)
        shareText = findViewById(R.id.shareText)

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
        permissionGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val value = when (checkedId) {
                R.id.permissionAlways -> SharePermission.ALWAYS
                R.id.permissionTemporary -> SharePermission.TEMPORARY
                else -> SharePermission.OFF
            }
            if (value != permission) choose(value, acceptInvite = false)
        }
        renderPickup(null)
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
            inviteTitle.text = "${currentInvite.inviterName}가 가족 그룹에 초대했어요"
            inviteDetail.text = "${currentInvite.inviterName}에게 내 위치를 어떻게 공유할까요?"
        }

        val pending = request?.takeIf { it.isPendingFresh() }
        requestCard.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            requestTitle.text = "${pending.requesterName}가 픽업하러 가려고 해요"
            requestDetail.text = "${pending.requesterName}에게 위치를 공유할까요? 픽업이 끝나면 공유도 끝나요."
        }

        val joined = currentInvite?.status == Invite.Status.ACCEPTED
        permissionTitle.text = if (joined) "${viewer.name}에게 내 위치" else "가족 그룹 초대를 기다리는 중"
        permissionGroup.visibility = if (joined) View.VISIBLE else View.GONE
        permissionDetail.visibility = if (joined) View.VISIBLE else View.GONE
        rendering = true
        permissionGroup.check(
            when (permission) {
                SharePermission.ALWAYS -> R.id.permissionAlways
                SharePermission.TEMPORARY -> R.id.permissionTemporary
                SharePermission.OFF -> R.id.permissionOff
            }
        )
        rendering = false
        permissionDetail.setText(
            when (permission) {
                SharePermission.ALWAYS -> R.string.permission_always_detail
                SharePermission.TEMPORARY -> R.string.permission_temporary_detail
                SharePermission.OFF -> R.string.permission_off_detail
            }
        )
        renderShare()
    }

    private fun renderShare() {
        val last = LocationShareService.lastShared
        val (text, dot) = when {
            !LocationShareService.isRunning -> getString(R.string.share_off) to CoreR.color.pleos_text_quaternary
            !LocationShareService.isSharing -> getString(R.string.share_standby) to CoreR.color.pleos_informative_positive
            last == null -> "위치 공유 중 · 위치 확인 중" to CoreR.color.pleos_informative_active
            else -> "위치 공유 중 · 마지막 전송 ${TIME_FORMAT.format(Date(last.updatedAt))}" to CoreR.color.pleos_informative_active
        }
        shareText.text = text
        shareDot.backgroundTintList = colorList(dot)
    }

    private fun renderPickup(status: PickupStatus?) {
        val current = status?.takeIf { !it.isStale() }
        pickupMetrics.visibility = if (current?.state == PickupStatus.State.ON_THE_WAY) View.VISIBLE else View.GONE
        when (current?.state) {
            PickupStatus.State.ON_THE_WAY -> {
                pickupHeadline.text = "${current.driverName}가 오고 있어요"
                pickupDetail.text = "내 위치가 바뀌면 길 안내도 따라 바뀌어요."
                val distance = current.remainDistanceMeters
                val time = current.remainTimeSeconds
                remainDistance.text = if (distance == null) "–" else "%.1fkm".format(distance / 1000.0)
                remainTime.text = if (time == null) "–" else "${(time + 59) / 60}분"
            }
            PickupStatus.State.ARRIVED -> {
                pickupHeadline.text = "${current.driverName}가 도착했어요"
                pickupDetail.text = "차가 근처에 있어요."
            }
            PickupStatus.State.CANCELLED, null -> {
                pickupHeadline.setText(R.string.pickup_none)
                pickupDetail.text = "${viewer.name}가 픽업을 시작하면 여기에 표시돼요."
            }
        }
    }

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }
}
