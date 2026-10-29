package com.EOKYEONGBIN.pleosfinder.core

fun interface Subscription {
    fun cancel()
}

/** 차량 앱과 폰 앱이 위치·초대·공유 요청·픽업 상태를 주고받는 서버 창구. 구현은 FirebaseFinderRepository. */
interface FinderRepository {
    fun publishMemberLocation(location: MemberLocation)
    fun observeMemberLocation(memberId: String, onUpdate: (MemberLocation) -> Unit): Subscription

    fun publishInvite(invite: Invite)
    fun observeInvite(memberId: String, onUpdate: (Invite?) -> Unit): Subscription

    fun setPermission(ownerId: String, viewerId: String, permission: SharePermission)

    /** 값이 없으면 OFF 로 전달한다. */
    fun observePermission(ownerId: String, viewerId: String, onUpdate: (SharePermission) -> Unit): Subscription

    fun publishShareRequest(request: ShareRequest)
    fun observeShareRequest(ownerId: String, onUpdate: (ShareRequest?) -> Unit): Subscription

    fun publishPickupStatus(status: PickupStatus)
    fun observePickupStatus(memberId: String, onUpdate: (PickupStatus) -> Unit): Subscription
}
