package com.EOKYEONGBIN.pleosfinder

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.DemoGroup
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository
import com.EOKYEONGBIN.pleosfinder.core.Invite
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.pickup.PickupController
import com.EOKYEONGBIN.pleosfinder.pickup.PickupRequester
import com.EOKYEONGBIN.pleosfinder.pickup.RepositoryMemberLocationSource

class MainActivity : AppCompatActivity() {

    private val repository = FirebaseFinderRepository()
    private val me = DemoGroup.DAD
    private val son = DemoGroup.SON

    private lateinit var naviHelper: NaviHelper
    private lateinit var pickupController: PickupController
    private lateinit var pickupRequester: PickupRequester
    private lateinit var statusText: TextView
    private lateinit var memberText: TextView
    private lateinit var actionButtons: List<Button>

    private var sonInvite: Invite? = null
    private var sonPermission = SharePermission.OFF
    private val subscriptions = mutableListOf<Subscription>()

    // NaviHelper 콜백은 메인 스레드가 아닐 수 있어서 화면 갱신은 showStatus 에서 UI 스레드로 넘긴다.
    private val naviEventListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            // 픽업 중에는 PickupController 가 주기적으로 조회하므로 화면을 덮어쓰지 않는다.
            if (pickupController.isActive) return
            showStatus("차량 위치: ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude}\n${currentLocationInfo.address ?: ""}")
        }
        override fun onError(naviErrorCode: NaviErrorCode) = showStatus("오류: $naviErrorCode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        memberText = findViewById(R.id.memberText)
        findViewById<TextView>(R.id.accountText).text = getString(R.string.account_format, me.name)
        val inviteButton = findViewById<Button>(R.id.inviteButton)
        val startPickupButton = findViewById<Button>(R.id.startPickupButton)
        val stopPickupButton = findViewById<Button>(R.id.stopPickupButton)
        val locationButton = findViewById<Button>(R.id.locationButton)
        actionButtons = listOf(startPickupButton, stopPickupButton, locationButton)

        naviHelper = NaviHelper(applicationContext)
        pickupController = PickupController(
            naviHelper = naviHelper,
            locationSource = RepositoryMemberLocationSource(repository),
            driver = me,
            publishStatus = repository::publishPickupStatus,
            onStatus = ::showStatus,
            onFinished = { pickupRequester.endShare(it) },
        )
        pickupRequester = PickupRequester(repository, pickupController, me, ::showStatus)
        naviHelper.initialize(
            onConnected = {
                naviHelper.addListener(naviEventListener)
                naviHelper.addListener(pickupController)
                runOnUiThread { actionButtons.forEach { it.isEnabled = true } }
                showStatus("NaviHelper 연결됨")
            },
            onFailed = { throwable -> showStatus("NaviHelper 연결 실패: ${throwable.message}") }
        )

        subscriptions += repository.observeInvite(son.id) {
            sonInvite = it
            renderMember()
        }
        subscriptions += repository.observePermission(son.id, me.id) {
            sonPermission = it
            renderMember()
        }

        inviteButton.setOnClickListener {
            repository.publishInvite(Invite(son.id, me.id, me.name, Invite.Status.PENDING, System.currentTimeMillis()))
            showStatus("${son.name}에게 가족 그룹 초대를 보냈어요.")
        }
        startPickupButton.setOnClickListener {
            if (sonInvite?.status == Invite.Status.ACCEPTED) {
                pickupRequester.request(son, sonPermission)
            } else {
                showStatus("먼저 ${son.name}을 가족 그룹에 초대하고, ${son.name}이 수락해야 해요.")
            }
        }
        stopPickupButton.setOnClickListener {
            pickupRequester.cancel()
            pickupController.stop()
            showStatus("픽업 종료")
        }
        locationButton.setOnClickListener { naviHelper.getCurrentLocationInfo() }
    }

    override fun onDestroy() {
        subscriptions.forEach { it.cancel() }
        pickupRequester.cancel()
        pickupController.stop()
        naviHelper.removeListener(pickupController)
        naviHelper.removeListener(naviEventListener)
        naviHelper.release()
        super.onDestroy()
    }

    private fun renderMember() {
        val invite = sonInvite
        val state = when {
            invite == null -> "초대 안 함"
            invite.status == Invite.Status.PENDING -> "초대 수락 대기 중"
            invite.status == Invite.Status.DECLINED -> "초대를 거절했어요"
            else -> when (sonPermission) {
                SharePermission.ALWAYS -> "가족 · 위치 공유 항상 허용"
                SharePermission.TEMPORARY -> "가족 · 위치 공유 일시적 허용 (픽업 때 요청)"
                SharePermission.OFF -> "가족 · 위치 공유 꺼짐"
            }
        }
        memberText.text = "${son.name}: $state"
    }

    private fun showStatus(message: String) {
        Log.d(TAG, message)
        runOnUiThread { statusText.text = message }
    }

    companion object {
        private const val TAG = "PleosFinder"
    }
}
