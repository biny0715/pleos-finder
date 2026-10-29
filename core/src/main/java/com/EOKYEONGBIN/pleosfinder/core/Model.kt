package com.EOKYEONGBIN.pleosfinder.core

data class Member(val id: String, val name: String)

data class MemberLocation(
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

/** 차량 위치. 누가 운전 중이었는지(driverId)를 함께 남겨, 그 운전자의 공유 설정으로만 보여 준다. */
data class VehicleLocation(
    val vehicleId: String,
    val driverId: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

/** 내 위치를 특정 상대에게 보여줄지. 공유하는 사람이 상대별로 정한다. */
enum class SharePermission {
    /** 요청이 오면 확인 없이 바로 공유 */
    ALWAYS,

    /** 가족·친구: 요청 때마다 수락하고 픽업이 끝나면 종료 / 그룹: 정한 시간까지만 공유 */
    TEMPORARY,

    /** 공유하지 않음 */
    OFF,
}

enum class Relation { FAMILY, FRIEND }

enum class InviteStatus { PENDING, ACCEPTED, DECLINED }

/** 1:1 연결(가족·친구). 양쪽에 같은 내용이 owner/other 만 바뀌어 저장된다. */
data class Connection(
    val ownerId: String,
    val otherId: String,
    val relation: Relation,
    val status: InviteStatus,
    val inviterId: String,
    val updatedAt: Long,
) {
    val isIncomingInvite: Boolean get() = status == InviteStatus.PENDING && inviterId != ownerId
}

/** 그룹 구성원. share 는 이 구성원이 그룹에 자기 위치(운전 중이면 차량 위치)를 보여 주는 방식이다. */
data class GroupMember(
    val accountId: String,
    val status: InviteStatus,
    val share: SharePermission,
    /** share = TEMPORARY 일 때 공유가 끝나는 시각 */
    val shareUntil: Long,
) {
    fun isSharing(now: Long = System.currentTimeMillis()): Boolean = status == InviteStatus.ACCEPTED &&
        (share == SharePermission.ALWAYS || (share == SharePermission.TEMPORARY && now < shareUntil))
}

data class FinderGroup(
    val id: String,
    val name: String,
    val ownerId: String,
    val members: List<GroupMember>,
) {
    fun memberOf(accountId: String): GroupMember? = members.firstOrNull { it.accountId == accountId }
}

/** 일시적 허용 상대에게 보내는 위치 공유 요청. ownerId 는 위치를 공유할 사람. */
data class ShareRequest(
    val ownerId: String,
    val requesterId: String,
    val requesterName: String,
    val state: State,
    val updatedAt: Long,
) {
    enum class State { PENDING, ACCEPTED, DECLINED, ENDED }

    /** 응답 없는 요청은 10분, 수락된 공유는 2시간이 지나면 끝난 것으로 본다(차량 앱이 종료를 못 보낸 경우 대비). */
    fun isActiveShare(now: Long = System.currentTimeMillis()): Boolean =
        state == State.ACCEPTED && now - updatedAt <= TEMPORARY_SHARE_MAX_MS

    fun isPendingFresh(now: Long = System.currentTimeMillis()): Boolean =
        state == State.PENDING && now - updatedAt <= PickupStatus.STALE_MS

    companion object {
        const val TEMPORARY_SHARE_MAX_MS = 2 * 60 * 60_000L
    }
}

/** 차량 앱이 픽업 대상의 폰으로 보내는 진행 상황. */
data class PickupStatus(
    val targetMemberId: String,
    val driverName: String,
    val state: State,
    val vehicleLatitude: Double?,
    val vehicleLongitude: Double?,
    val remainDistanceMeters: Int?,
    val remainTimeSeconds: Int?,
    val updatedAt: Long,
) {
    enum class State { ON_THE_WAY, ARRIVED, CANCELLED }

    /** 지난 픽업의 결과가 서버에 남아 있어도 오래된 것은 화면·알림에 쓰지 않는다. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now - updatedAt > STALE_MS

    companion object {
        const val STALE_MS = 10 * 60_000L
    }
}
