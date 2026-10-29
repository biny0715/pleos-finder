package com.EOKYEONGBIN.pleosfinder.pickup

import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation

/** 차량 화면의 픽업 패널이 그리는 상태 */
sealed interface PickupState {
    data object Idle : PickupState

    /** 일시적 허용 상대에게 위치 공유를 요청하고 수락을 기다리는 중 */
    data class WaitingForAcceptance(val target: Member) : PickupState

    data class OnTheWay(
        val target: Member,
        val targetLocation: MemberLocation?,
        val remainDistanceMeters: Int?,
        val remainTimeSeconds: Int?,
    ) : PickupState

    data class Completed(val target: Member) : PickupState
}
