package com.EOKYEONGBIN.pleosfinder.core

import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.DAD
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.JIHOON
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.MINSU
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.MOM
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.SEOYEON
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory.SON

/**
 * 시연 시작 상태.
 * - 가족: 아빠 · 엄마 · 아들. 아들은 아빠에게 항상 허용(바로 픽업), 엄마에게 일시적 허용(픽업 때 요청)
 * - 친구: 아빠 ↔ 김민수(사람 + 차량), 아빠 ↔ 박지훈(차량만, Finder 폰 앱 없음), 아들 ↔ 이서연
 * - 그룹: '주말 캠핑' = 아빠·김민수(1시간 일시적 허용) + 박지훈·아들(초대 대기)
 * 아빠·엄마는 아들에게 자기 위치(운전 중이면 차량)를 항상 보여 준다. 친구 차량·폰 위치는 판교 주변 고정 좌표다.
 */
object DemoSeed {

    fun build(now: Long): Map<String, Any> = mapOf(
        "connections" to mapOf(
            DAD.id to mapOf(
                MOM.id to connection(Relation.FAMILY, now),
                SON.id to connection(Relation.FAMILY, now),
                MINSU.id to connection(Relation.FRIEND, now),
                JIHOON.id to connection(Relation.FRIEND, now),
            ),
            MOM.id to mapOf(DAD.id to connection(Relation.FAMILY, now), SON.id to connection(Relation.FAMILY, now)),
            SON.id to mapOf(
                DAD.id to connection(Relation.FAMILY, now),
                MOM.id to connection(Relation.FAMILY, now),
                SEOYEON.id to connection(Relation.FRIEND, now),
            ),
            SEOYEON.id to mapOf(SON.id to connection(Relation.FRIEND, now)),
            MINSU.id to mapOf(DAD.id to connection(Relation.FRIEND, now)),
            JIHOON.id to mapOf(DAD.id to connection(Relation.FRIEND, now)),
        ),
        "permissions" to mapOf(
            DAD.id to mapOf(MOM.id to SharePermission.ALWAYS.name, SON.id to SharePermission.ALWAYS.name),
            MOM.id to mapOf(DAD.id to SharePermission.ALWAYS.name, SON.id to SharePermission.ALWAYS.name),
            SON.id to mapOf(DAD.id to SharePermission.ALWAYS.name, MOM.id to SharePermission.TEMPORARY.name),
            SEOYEON.id to mapOf(SON.id to SharePermission.ALWAYS.name),
            MINSU.id to mapOf(DAD.id to SharePermission.ALWAYS.name),
            JIHOON.id to mapOf(DAD.id to SharePermission.ALWAYS.name),
        ),
        "locations" to mapOf(
            MOM.id to location(37.3595, 127.1052, now),
            MINSU.id to location(37.4020, 127.1086, now),
            SEOYEON.id to location(37.3925, 127.1100, now),
        ),
        "vehicleLocations" to mapOf(
            DemoDirectory.GV70.id to location(37.4017, 127.1083, now) + ("driverId" to MINSU.id),
            DemoDirectory.K8.id to location(37.3860, 127.1215, now) + ("driverId" to JIHOON.id),
        ),
        "finderGroups" to mapOf(
            "camping" to mapOf(
                "name" to "주말 캠핑",
                "ownerId" to DAD.id,
                "members" to mapOf(
                    DAD.id to member(InviteStatus.ACCEPTED, SharePermission.OFF, 0L),
                    MINSU.id to member(InviteStatus.ACCEPTED, SharePermission.TEMPORARY, now + 60 * 60_000L),
                    JIHOON.id to member(InviteStatus.PENDING, SharePermission.OFF, 0L),
                    SON.id to member(InviteStatus.PENDING, SharePermission.OFF, 0L),
                ),
            ),
        ),
    )

    private fun connection(relation: Relation, now: Long) =
        mapOf(
            "relation" to relation.name,
            "status" to InviteStatus.ACCEPTED.name,
            "inviterId" to DAD.id,
            "updatedAt" to now,
        )

    private fun location(latitude: Double, longitude: Double, now: Long) =
        mapOf("latitude" to latitude, "longitude" to longitude, "updatedAt" to now)

    private fun member(status: InviteStatus, share: SharePermission, until: Long) =
        mapOf("status" to status.name, "share" to share.name, "shareUntil" to until)
}
