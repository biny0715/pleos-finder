package com.EOKYEONGBIN.pleosfinder.core

fun interface Subscription {
    fun cancel()
}

/** 차량 앱과 폰 앱이 위치·연결·그룹·픽업 상태를 주고받는 서버 창구. 구현은 FirebaseFinderRepository. */
interface FinderRepository {
    fun publishMemberLocation(location: MemberLocation)
    fun observeMemberLocation(memberId: String, onUpdate: (MemberLocation) -> Unit): Subscription

    fun publishVehicleLocation(location: VehicleLocation)
    fun observeVehicleLocations(onUpdate: (Map<String, VehicleLocation>) -> Unit): Subscription

    /** 가족·친구로 초대한다. 양쪽 연결을 PENDING 으로 만든다. */
    fun inviteConnection(inviterId: String, otherId: String, relation: Relation)

    /** 받은 초대에 답한다. */
    fun respondConnection(ownerId: String, otherId: String, relation: Relation, inviterId: String, accept: Boolean)
    fun observeConnections(accountId: String, onUpdate: (List<Connection>) -> Unit): Subscription

    fun setPermission(ownerId: String, viewerId: String, permission: SharePermission)

    /** 값이 없으면 OFF 로 전달한다. */
    fun observePermission(ownerId: String, viewerId: String, onUpdate: (SharePermission) -> Unit): Subscription

    /** ownerId 가 상대별로 정한 허용 방식 전체(viewerId → 허용 방식) */
    fun observePermissions(ownerId: String, onUpdate: (Map<String, SharePermission>) -> Unit): Subscription

    /**
     * 차량끼리 위치 공유: ownerId 가 운전하는 차량 위치를 viewerId 에게 until 시각까지 보여 준다(항상 일시적).
     * until = 0 이면 공유 중지.
     */
    fun setVehicleShare(ownerId: String, viewerId: String, until: Long)

    /** ownerId 가 상대별로 정한 차량 공유 종료 시각(viewerId → until) */
    fun observeVehicleShares(ownerId: String, onUpdate: (Map<String, Long>) -> Unit): Subscription

    fun publishShareRequest(request: ShareRequest)
    fun observeShareRequest(ownerId: String, onUpdate: (ShareRequest?) -> Unit): Subscription

    fun publishPickupStatus(status: PickupStatus)
    fun observePickupStatus(memberId: String, onUpdate: (PickupStatus) -> Unit): Subscription

    /** 그룹을 만들고 만든 사람을 구성원(공유 꺼짐)으로 넣는다. 새 그룹 id 를 돌려준다. */
    fun createGroup(name: String, ownerId: String): String
    fun renameGroup(groupId: String, name: String)
    fun inviteToGroup(groupId: String, accountId: String)
    fun setGroupMember(groupId: String, member: GroupMember)
    fun observeGroups(onUpdate: (List<FinderGroup>) -> Unit): Subscription

    /** 그룹 목적지 공유. 그룹의 이전 공유를 바꾸고 새 공유 id 를 돌려준다(share.id 는 무시). */
    fun shareDestination(share: DestinationShare): String
    fun clearDestination(groupId: String)
    fun respondDestination(groupId: String, shareId: String, accountId: String, accepted: Boolean)

    /** null 이면 지운다(안내를 끝냄). */
    fun publishDestinationProgress(groupId: String, shareId: String, accountId: String, progress: DestinationProgress?)

    /** 그룹 id → 지금 공유 중인 목적지 */
    fun observeDestinationShares(onUpdate: (Map<String, DestinationShare>) -> Unit): Subscription

    /** 시연 데이터로 되돌린다(가족: 아빠·엄마, 친구: 김민수·박지훈, 그룹: 주말 캠핑). */
    fun resetDemo()
}
