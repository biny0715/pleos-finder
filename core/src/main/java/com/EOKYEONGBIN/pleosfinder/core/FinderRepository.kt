package com.EOKYEONGBIN.pleosfinder.core

fun interface Subscription {
    fun cancel()
}

/** 차량 앱과 폰 앱이 위치·픽업 상태를 주고받는 서버 창구. 구현은 FirebaseFinderRepository. */
interface FinderRepository {
    fun publishMemberLocation(location: MemberLocation)
    fun observeMemberLocation(memberId: String, onUpdate: (MemberLocation) -> Unit): Subscription

    fun publishPickupStatus(status: PickupStatus)
    fun observePickupStatus(memberId: String, onUpdate: (PickupStatus) -> Unit): Subscription
}
