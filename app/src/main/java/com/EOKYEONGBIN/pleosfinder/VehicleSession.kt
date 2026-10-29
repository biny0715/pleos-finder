package com.EOKYEONGBIN.pleosfinder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.DemoGroup
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository
import com.EOKYEONGBIN.pleosfinder.core.Invite
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.pickup.PickupController
import com.EOKYEONGBIN.pleosfinder.pickup.PickupRequester
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import com.EOKYEONGBIN.pleosfinder.pickup.RepositoryMemberLocationSource

/**
 * 차량 앱의 픽업 상태를 앱 전체에서 하나로 들고 있는 세션.
 * 화면(MainActivity)과 Gleo 음성 명령(GleoActionReceiver)이 같은 세션을 쓰므로,
 * 화면이 꺼져 있어도 음성으로 픽업을 시작할 수 있다. 상태 변경과 리스너 호출은 모두 메인 스레드에서 한다.
 */
class VehicleSession(context: Context) {

    val me: Member = DemoGroup.DAD

    /** 시연용 가족 그룹. 기획상 여러 명이지만 출품본은 아들 한 명이다. */
    val family: List<Member> = listOf(DemoGroup.SON)

    private val repository: FinderRepository = FirebaseFinderRepository()
    private val naviHelper = NaviHelper(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickupController: PickupController = PickupController(
        naviHelper = naviHelper,
        locationSource = RepositoryMemberLocationSource(repository),
        driver = me,
        publishStatus = repository::publishPickupStatus,
        onState = ::onPickupState,
        onFinished = { pickupRequester.endShare(it) },
    )
    private val pickupRequester: PickupRequester = PickupRequester(repository, pickupController, me, ::onPickupState, ::showMessage)

    var naviConnected = false
        private set
    var pickupState: PickupState = PickupState.Idle
        private set

    private val invites = mutableMapOf<String, Invite?>()
    private val permissions = mutableMapOf<String, SharePermission>()
    private val locations = mutableMapOf<String, MemberLocation>()
    private var loaded = false
    private val readyCallbacks = mutableListOf<() -> Unit>()

    /** 화면이 떠 있는 동안 MainActivity 가 등록한다. */
    var onChanged: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onError(naviErrorCode: NaviErrorCode) = showMessage("길 안내 오류: $naviErrorCode")
    }

    init {
        naviHelper.initialize(
            onConnected = {
                naviHelper.addListener(naviEventListener)
                naviHelper.addListener(pickupController)
                mainHandler.post {
                    naviConnected = true
                    notifyChanged()
                }
            },
            onFailed = { throwable -> showMessage("내비게이션 연결 실패: ${throwable.message}") }
        )
        for (member in family) {
            repository.observeInvite(member.id) {
                invites[member.id] = it
                markLoaded()
                notifyChanged()
            }
            repository.observePermission(member.id, me.id) {
                permissions[member.id] = it
                markLoaded()
                notifyChanged()
            }
            repository.observeMemberLocation(member.id) { locations[member.id] = it }
        }
    }

    fun inviteOf(member: Member): Invite? = invites[member.id]

    fun permissionOf(member: Member): SharePermission = permissions[member.id] ?: SharePermission.OFF

    fun isJoined(member: Member) = inviteOf(member)?.status == Invite.Status.ACCEPTED

    /** 마지막으로 받은 위치. 공유가 꺼져 있으면 오래된 값일 수 있다. */
    fun lastLocationOf(member: Member): MemberLocation? = locations[member.id]

    val isPickupBusy: Boolean
        get() = pickupState is PickupState.WaitingForAcceptance || pickupState is PickupState.OnTheWay

    fun invite(member: Member) {
        repository.publishInvite(Invite(member.id, me.id, me.name, Invite.Status.PENDING, System.currentTimeMillis()))
        showMessage("${member.name}에게 가족 그룹 초대를 보냈어요.")
    }

    fun startPickup(member: Member) = pickupRequester.request(member, permissionOf(member))

    fun stopPickup() {
        when (pickupState) {
            is PickupState.WaitingForAcceptance -> pickupRequester.cancel()
            is PickupState.OnTheWay -> {
                pickupController.stop()
                showMessage("픽업을 종료했어요.")
            }
            else -> Unit
        }
    }

    /** 서버에서 가족 정보를 처음 받아 올 때까지 기다린다. 앱 프로세스가 Gleo 호출로 막 시작된 경우에 쓴다. */
    fun whenReady(timeoutMs: Long, action: () -> Unit) {
        if (loaded) {
            action()
            return
        }
        var done = false
        val once = {
            if (!done) {
                done = true
                action()
            }
        }
        readyCallbacks += once
        mainHandler.postDelayed({ once() }, timeoutMs)
    }

    private fun markLoaded() {
        // 구성원마다 초대와 허용 방식을 둘 다 받아야 판단할 수 있다.
        if (loaded || family.any { it.id !in invites || it.id !in permissions }) return
        loaded = true
        readyCallbacks.toList().forEach { it() }
        readyCallbacks.clear()
    }

    private fun onPickupState(state: PickupState) = mainHandler.post {
        pickupState = state
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    private fun showMessage(message: String) {
        Log.d(TAG, message)
        mainHandler.post { onMessage?.invoke(message) }
    }

    private companion object {
        const val TAG = "PleosFinder"
    }
}
