package com.EOKYEONGBIN.pleosfinder.pickup

import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription

/**
 * 상대의 공유 허용 방식에 따라 픽업을 시작한다.
 * 항상 허용이면 바로 길 안내, 일시적 허용이면 상대 폰에 위치 공유를 요청하고 수락하면 길 안내를 시작한다.
 * 콜백은 모두 메인 스레드에서 호출된다(Firebase 리스너 기준).
 */
class PickupRequester(
    private val repository: FinderRepository,
    private val pickupController: PickupController,
    private val driver: Member,
    private val onState: (PickupState) -> Unit,
    /** 잠깐 보여 줄 안내(토스트) */
    private val onMessage: (String) -> Unit,
) {

    private var waitingFor: Member? = null
    private var requestSubscription: Subscription? = null

    val isWaiting: Boolean get() = waitingFor != null

    fun request(target: Member, permission: SharePermission) {
        cancel()
        when (permission) {
            SharePermission.ALWAYS -> pickupController.start(target)
            SharePermission.TEMPORARY -> {
                val requestedAt = System.currentTimeMillis()
                waitingFor = target
                repository.publishShareRequest(
                    ShareRequest(target.id, driver.id, driver.name, ShareRequest.State.PENDING, requestedAt)
                )
                requestSubscription = repository.observeShareRequest(target.id) { onShareRequest(target, it, requestedAt) }
                onState(PickupState.WaitingForAcceptance(target))
            }
            SharePermission.OFF -> onMessage("${target.name}이 위치 공유를 꺼 두었어요.")
        }
    }

    /** 수락을 기다리는 중이면 요청을 거둬들인다. */
    fun cancel() {
        val target = waitingFor ?: return
        endShare(target)
        stopWaiting()
        onState(PickupState.Idle)
    }

    /** 일시적 허용 공유를 끝낸다. 픽업이 완료·취소되면 PickupController 가 호출한다. */
    fun endShare(target: Member) {
        repository.publishShareRequest(
            ShareRequest(target.id, driver.id, driver.name, ShareRequest.State.ENDED, System.currentTimeMillis())
        )
    }

    private fun onShareRequest(target: Member, request: ShareRequest?, requestedAt: Long) {
        // 내가 보낸 이번 요청에 대한 응답만 처리한다.
        if (waitingFor != target || request == null || request.requesterId != driver.id || request.updatedAt < requestedAt) return
        when (request.state) {
            ShareRequest.State.ACCEPTED -> {
                stopWaiting()
                pickupController.start(target)
            }
            ShareRequest.State.DECLINED -> {
                stopWaiting()
                onState(PickupState.Idle)
                onMessage("${target.name}이 위치 공유를 거절했어요.")
            }
            ShareRequest.State.PENDING, ShareRequest.State.ENDED -> Unit
        }
    }

    private fun stopWaiting() {
        requestSubscription?.cancel()
        requestSubscription = null
        waitingFor = null
    }
}
