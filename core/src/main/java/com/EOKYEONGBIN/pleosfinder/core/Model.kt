package com.EOKYEONGBIN.pleosfinder.core

data class Member(val id: String, val name: String)

data class MemberLocation(
    val memberId: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

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

/** 서버 연동 전까지 쓰는 시연용 가족 그룹 */
object DemoGroup {
    val DAD = Member(id = "dad", name = "아빠")
    val SON = Member(id = "son", name = "아들")
}
