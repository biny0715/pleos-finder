package com.EOKYEONGBIN.pleosfinder

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.Invite
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ui.PleosToast
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState

class MainActivity : AppCompatActivity() {

    private val session get() = (application as PleosFinderApp).session
    private val son get() = session.family.first()

    private lateinit var toast: PleosToast
    private lateinit var memberDot: View
    private lateinit var memberStatus: TextView
    private lateinit var inviteButton: Button
    private lateinit var startPickupButton: Button
    private lateinit var pickupHeadline: TextView
    private lateinit var pickupDetail: TextView
    private lateinit var pickupProgress: View
    private lateinit var pickupMetrics: View
    private lateinit var remainDistance: TextView
    private lateinit var remainTime: TextView
    private lateinit var pickupActionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toast = PleosToast(findViewById(R.id.toast))
        findViewById<TextView>(R.id.accountText).text = getString(R.string.account_format, session.me.name)
        findViewById<TextView>(R.id.memberAvatar).text = son.name.take(1)
        findViewById<TextView>(R.id.memberName).text = son.name
        memberDot = findViewById(R.id.memberDot)
        memberStatus = findViewById(R.id.memberStatus)
        inviteButton = findViewById(R.id.inviteButton)
        startPickupButton = findViewById(R.id.startPickupButton)
        pickupHeadline = findViewById(R.id.pickupHeadline)
        pickupDetail = findViewById(R.id.pickupDetail)
        pickupProgress = findViewById(R.id.pickupProgress)
        pickupMetrics = findViewById(R.id.pickupMetrics)
        remainDistance = findViewById(R.id.remainDistance)
        remainTime = findViewById(R.id.remainTime)
        pickupActionButton = findViewById(R.id.pickupActionButton)
        startPickupButton.text = "${son.name} ${getString(R.string.action_start_pickup)}"

        inviteButton.setOnClickListener { session.invite(son) }
        startPickupButton.setOnClickListener { session.startPickup(son) }
        pickupActionButton.setOnClickListener { session.stopPickup() }
    }

    override fun onStart() {
        super.onStart()
        session.onChanged = ::render
        session.onMessage = toast::show
        render()
    }

    override fun onStop() {
        session.onChanged = null
        session.onMessage = null
        super.onStop()
    }

    private fun render() {
        renderMember()
        renderPickup()
    }

    private fun renderMember() {
        val invite = session.inviteOf(son)
        val joined = invite?.status == Invite.Status.ACCEPTED
        val (status, dotColor) = when {
            invite == null -> "아직 가족 그룹에 초대하지 않았어요" to CoreR.color.pleos_text_quaternary
            invite.status == Invite.Status.PENDING -> "초대 수락을 기다리는 중" to CoreR.color.pleos_text_quaternary
            invite.status == Invite.Status.DECLINED -> "초대를 거절했어요" to CoreR.color.pleos_informative_negative
            else -> when (session.permissionOf(son)) {
                SharePermission.ALWAYS -> "위치 공유 · 항상 허용" to CoreR.color.pleos_informative_active
                SharePermission.TEMPORARY -> "위치 공유 · 일시적 허용 (픽업 때 요청)" to CoreR.color.pleos_informative_positive
                SharePermission.OFF -> "위치 공유 꺼짐" to CoreR.color.pleos_text_quaternary
            }
        }
        memberStatus.text = status
        memberDot.backgroundTintList = colorList(dotColor)

        inviteButton.visibility = if (joined) View.GONE else View.VISIBLE
        inviteButton.isEnabled = invite?.status != Invite.Status.PENDING
        inviteButton.setText(if (invite?.status == Invite.Status.PENDING) R.string.action_invite_pending else R.string.action_invite)

        startPickupButton.visibility = if (joined) View.VISIBLE else View.GONE
        startPickupButton.isEnabled = session.naviConnected && !session.isPickupBusy
    }

    private fun renderPickup() {
        val state = session.pickupState
        pickupProgress.visibility = if (state is PickupState.WaitingForAcceptance) View.VISIBLE else View.GONE
        pickupMetrics.visibility = if (state is PickupState.OnTheWay) View.VISIBLE else View.GONE
        pickupActionButton.visibility = when (state) {
            is PickupState.WaitingForAcceptance -> View.VISIBLE.also { pickupActionButton.setText(R.string.action_cancel_request) }
            is PickupState.OnTheWay -> View.VISIBLE.also { pickupActionButton.setText(R.string.action_stop_pickup) }
            else -> View.GONE
        }
        when (state) {
            PickupState.Idle -> {
                pickupHeadline.text = "진행 중인 픽업 없음"
                pickupDetail.text = "픽업을 시작하면 가족의 위치로 바로 길 안내를 시작해요."
            }
            is PickupState.WaitingForAcceptance -> {
                pickupHeadline.text = "${state.target.name}의 수락을 기다리는 중"
                pickupDetail.text = "${state.target.name} 폰에 위치 공유를 요청했어요. 수락하면 바로 길 안내를 시작해요."
            }
            is PickupState.OnTheWay -> {
                pickupHeadline.text = "${state.target.name}에게 가는 중"
                val location = state.targetLocation
                pickupDetail.text = if (location == null) {
                    "${state.target.name}의 위치를 받는 중이에요."
                } else {
                    "${state.target.name}의 위치로 안내 중 · ${timeAgo(location.updatedAt)} 갱신"
                }
                val distance = state.remainDistanceMeters
                val time = state.remainTimeSeconds
                remainDistance.text = if (distance == null) "–" else "%.1fkm".format(distance / 1000.0)
                remainTime.text = if (time == null) "–" else "${(time + 59) / 60}분"
            }
            is PickupState.Completed -> {
                pickupHeadline.text = "${state.target.name} 픽업 완료"
                pickupDetail.text = "${state.target.name}에게 도착 알림을 보냈어요."
            }
        }
    }

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private fun timeAgo(at: Long): String {
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 10 -> "방금"
            seconds < 60 -> "${seconds}초 전"
            else -> "${seconds / 60}분 전"
        }
    }
}
