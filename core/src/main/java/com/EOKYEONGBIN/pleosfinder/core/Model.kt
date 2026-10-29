package com.EOKYEONGBIN.pleosfinder.core

data class Member(val id: String, val name: String)

data class MemberLocation(
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

/** 내 위치를 특정 상대에게 보여줄지. 공유하는 사람이 상대별로 정한다. */
enum class SharePermission {
    /** 요청이 오면 확인 없이 바로 공유 */
    ALWAYS,

    /** 요청이 올 때마다 수락해야 공유하고, 픽업이 끝나면 공유도 끝남 */
    TEMPORARY,

    /** 공유하지 않음 */
    OFF,
}

/** 가족 그룹 초대. 초대받은 사람(memberId)이 수락하면서 SharePermission 을 고른다. */
data class Invite(
    val memberId: String,
    val inviterId: String,
    val inviterName: String,
    val status: Status,
    val updatedAt: Long,
) {
    enum class Status { PENDING, ACCEPTED, DECLINED }
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

/**
 * 시연용 가족 그룹. 기획상 모든 사용자는 Pleos 계정으로 로그인하지만,
 * 출품본은 차량 앱 = 아빠, 폰 앱 = 아들로 로그인되어 있다고 가정한다.
 */
object DemoGroup {
    val DAD = Member(id = "dad", name = "아빠")
    val SON = Member(id = "son", name = "아들")
}
