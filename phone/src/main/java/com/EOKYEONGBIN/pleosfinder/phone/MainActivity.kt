package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.Connection
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val repository get() = FinderServices.repository
    private val me = FinderServices.me

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
    private lateinit var permissionList: LinearLayout
    private lateinit var shareDot: View
    private lateinit var shareText: TextView

    private var connections: List<Connection> = emptyList()
    private var permissions: Map<String, SharePermission> = emptyMap()
    private var request: ShareRequest? = null
    private val subscriptions = mutableListOf<Subscription>()

    // 화면을 다시 그리면서 세그먼트를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    /** 위치 권한을 받은 뒤 이어서 적용할 선택 */
    private var pendingChoice: Choice? = null

    private data class Choice(val viewerId: String, val permission: SharePermission, val acceptInvite: Connection?)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val choice = pendingChoice
        pendingChoice = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && choice != null) {
            applyChoice(choice)
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
        permissionList = findViewById(R.id.permissionList)
        shareDot = findViewById(R.id.shareDot)
        shareText = findViewById(R.id.shareText)

        findViewById<Button>(R.id.inviteAlwaysButton).setOnClickListener { incomingInvite()?.let { choose(Choice(it.otherId, SharePermission.ALWAYS, it)) } }
        findViewById<Button>(R.id.inviteTemporaryButton).setOnClickListener { incomingInvite()?.let { choose(Choice(it.otherId, SharePermission.TEMPORARY, it)) } }
        findViewById<Button>(R.id.inviteDeclineButton).setOnClickListener {
            incomingInvite()?.let { repository.respondConnection(me.id, it.otherId, it.relation, it.inviterId, accept = false) }
        }
        findViewById<Button>(R.id.acceptRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = true, pending = request)
        }
        findViewById<Button>(R.id.declineRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = false, pending = request)
        }
        renderPickup(null)
    }

    override fun onStart() {
        super.onStart()
        LocationShareService.onStateChanged = ::renderShare
        subscriptions += repository.observeConnections(me.id) {
            connections = it
            render()
        }
        subscriptions += repository.observePermissions(me.id) {
            permissions = it
            // 앱을 다시 열었을 때 누군가에게 허용 상태인데 서비스가 꺼져 있으면 다시 켠다.
            if (it.values.any { p -> p != SharePermission.OFF } && !LocationShareService.isRunning && hasLocationPermission()) {
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

    private fun incomingInvite(): Connection? = connections.firstOrNull { it.isIncomingInvite }

    private fun choose(choice: Choice) {
        if (choice.permission == SharePermission.OFF || hasLocationPermission()) {
            applyChoice(choice)
            return
        }
        pendingChoice = choice
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun applyChoice(choice: Choice) {
        choice.acceptInvite?.let { repository.respondConnection(me.id, it.otherId, it.relation, it.inviterId, accept = true) }
        repository.setPermission(me.id, choice.viewerId, choice.permission)
        // 모두 꺼짐이면 서비스가 허용 상태 변화를 보고 스스로 멈춘다.
        if (choice.permission != SharePermission.OFF) LocationShareService.start(this)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val invite = incomingInvite()
        inviteCard.visibility = if (invite != null) View.VISIBLE else View.GONE
        if (invite != null) {
            val inviter = DemoDirectory.member(invite.otherId).name
            val label = if (invite.relation == Relation.FAMILY) "가족" else "친구"
            inviteTitle.text = "${inviter}가 ${label}으로 초대했어요"
            inviteDetail.text = "${inviter}에게 내 위치를 어떻게 공유할까요?"
        }

        val pending = request?.takeIf { it.isPendingFresh() }
        requestCard.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            requestTitle.text = "${pending.requesterName}가 픽업하러 가려고 해요"
            requestDetail.text = "${pending.requesterName}에게 위치를 공유할까요? 픽업이 끝나면 공유도 끝나요."
        }

        renderPermissions()
        renderShare()
    }

    /** 수락한 가족·친구마다 '○○에게 내 위치' 세그먼트를 그린다. */
    private fun renderPermissions() {
        permissionList.removeAllViews()
        val accepted = connections.filter { it.status == InviteStatus.ACCEPTED }
        if (accepted.isEmpty()) {
            permissionList.addView(TextView(this).apply {
                text = "가족이나 친구의 초대를 기다리는 중이에요."
                setTextAppearance(CoreR.style.TextAppearance_Pleos_BodySmall)
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        rendering = true
        for (connection in accepted) {
            val viewer = DemoDirectory.member(connection.otherId)
            val permission = permissions[viewer.id] ?: SharePermission.OFF
            val row = LayoutInflater.from(this).inflate(R.layout.item_permission, permissionList, false)
            row.findViewById<TextView>(R.id.permissionTitle).text =
                "${viewer.name}에게 내 위치 · ${if (connection.relation == Relation.FAMILY) "가족" else "친구"}"
            val group = row.findViewById<RadioGroup>(R.id.permissionGroup)
            group.check(
                when (permission) {
                    SharePermission.ALWAYS -> R.id.permissionAlways
                    SharePermission.TEMPORARY -> R.id.permissionTemporary
                    SharePermission.OFF -> R.id.permissionOff
                }
            )
            row.findViewById<TextView>(R.id.permissionDetail).setText(
                when (permission) {
                    SharePermission.ALWAYS -> R.string.permission_always_detail
                    SharePermission.TEMPORARY -> R.string.permission_temporary_detail
                    SharePermission.OFF -> R.string.permission_off_detail
                }
            )
            group.setOnCheckedChangeListener { _, checkedId ->
                if (rendering) return@setOnCheckedChangeListener
                val value = when (checkedId) {
                    R.id.permissionAlways -> SharePermission.ALWAYS
                    R.id.permissionTemporary -> SharePermission.TEMPORARY
                    else -> SharePermission.OFF
                }
                if (value != permission) choose(Choice(viewer.id, value, null))
            }
            permissionList.addView(row)
        }
        rendering = false
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
                pickupDetail.text = "가족이나 친구가 픽업을 시작하면 여기에 표시돼요."
            }
        }
    }

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }
}
