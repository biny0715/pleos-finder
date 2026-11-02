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

    /** 화면에 보일 공유 방식. 시간이 지난 일시적 허용은 끄기로 본다. */
    fun effectiveShare(now: Long = System.currentTimeMillis()): SharePermission =
        if (share == SharePermission.TEMPORARY && now >= shareUntil) SharePermission.OFF else share

    /** 공유 방식을 바꾼다. 일시적 허용은 지금부터 30분으로 시작한다. */
    fun withShare(value: SharePermission, now: Long = System.currentTimeMillis()): GroupMember =
        copy(share = value, shareUntil = if (value == SharePermission.TEMPORARY) now + SHARE_STEP_MS else 0L)

    /** 일시적 허용 시간을 30분 늘린다. 이미 끝났으면 지금부터 30분. */
    fun extended(now: Long = System.currentTimeMillis()): GroupMember {
        val base = if (share == SharePermission.TEMPORARY && shareUntil > now) shareUntil else now
        return copy(share = SharePermission.TEMPORARY, shareUntil = base + SHARE_STEP_MS)
    }

    /** 지금 공유 상태를 사람이 읽을 문장으로 */
    fun shareText(now: Long = System.currentTimeMillis()): String = when {
        status == InviteStatus.PENDING -> "초대 수락 대기 중"
        share == SharePermission.ALWAYS -> "항상 공유 중"
        share == SharePermission.TEMPORARY && now < shareUntil ->
            "${java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(shareUntil))}까지 공유"
        else -> "공유 안 함"
    }

    companion object {
        const val SHARE_STEP_MS = 30 * 60_000L
    }
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

/**
 * 그룹에 공유한 목적지(그룹마다 마지막 하나). 받은 구성원이 수락하면 차량은 내비, 폰은 지도 앱으로 길 안내를 시작하고,
 * 차량은 목적지까지 남은 거리·시간(progress)을 올려 그룹이 서로의 도착 예정 시간을 본다.
 */
data class DestinationShare(
    val groupId: String,
    val id: String,
    val fromId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String,
    val poiSubId: String,
    val createdAt: Long,
    /** 계정 id → 수락(true) / 거절(false) */
    val responses: Map<String, Boolean> = emptyMap(),
    /** 계정 id(차량 운전자) → 목적지까지 남은 거리·시간 */
    val progress: Map<String, DestinationProgress> = emptyMap(),
) {
    /** 그룹 화면에 보여 주는 기간 */
    fun isActive(now: Long = System.currentTimeMillis()) = now - createdAt < ACTIVE_MS

    /** 받은 사람에게 수락을 묻는 기간. 오래된 공유로 갑자기 팝업이 뜨지 않게 한다. */
    fun isPromptable(now: Long = System.currentTimeMillis()) = now - createdAt < PROMPT_MS

    /** 아직 답하지 않은 받는 사람인지 */
    fun awaits(accountId: String) = accountId != fromId && accountId !in responses

    companion object {
        const val ACTIVE_MS = 6 * 60 * 60_000L
        const val PROMPT_MS = 30 * 60_000L
    }
}

data class DestinationProgress(
    val remainDistanceMeters: Int,
    val remainTimeSeconds: Int,
    val arrived: Boolean,
    val updatedAt: Long,
) {
    /** 마지막으로 받은 남은 시간으로 계산한 도착 예정 시각 */
    val arrivalAt: Long get() = updatedAt + remainTimeSeconds * 1000L

    fun isStale(now: Long = System.currentTimeMillis()) = !arrived && now - updatedAt > STALE_MS

    /** "목적지까지 3.2km · 12분 · 오후 3:40 도착 예정" / "목적지 도착" */
    fun describe(now: Long = System.currentTimeMillis()): String {
        if (arrived) return "목적지 도착"
        val km = "%.1fkm".format(remainDistanceMeters / 1000.0)
        val minutes = maxOf(1, ((arrivalAt - now) / 60_000L).toInt())
        val at = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(arrivalAt))
        return "목적지까지 $km · ${minutes}분 · $at 도착 예정"
    }

    companion object {
        const val STALE_MS = 5 * 60_000L
    }
}
