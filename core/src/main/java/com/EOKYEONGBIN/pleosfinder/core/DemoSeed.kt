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
 * - 그룹: '강릉 여행' = 아빠·엄마·김민수(1시간 일시적 허용) + 박지훈·아들(초대 대기). 집에서 강릉 경포해변으로 간다.
 * 아빠·엄마는 아들에게 자기 위치(운전 중이면 차량)를 항상 보여 준다.
 * 위치: 아빠 차 = 회사(판교 제2테크노밸리, 에뮬레이터 고정), 아들 = 판교역(폰 GPS), 엄마 = 봇들마을(판교역 북쪽 0.8km),
 * 김민수 = 정자역(GV70), 박지훈 = 서현역(K8), 이서연 = 판교역 남쪽 백현마을, 집 = 판교원마을 5단지.
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
            MOM.id to location(37.40102, 127.11480, now),
            MINSU.id to location(37.36665, 127.10844, now),
            SEOYEON.id to location(37.39296, 127.11494, now),
        ),
        "vehicleLocations" to mapOf(
            DemoDirectory.GV70.id to location(37.36690, 127.10800, now) + ("driverId" to MINSU.id),
            DemoDirectory.K8.id to location(37.38491, 127.12333, now) + ("driverId" to JIHOON.id),
        ),
        "finderGroups" to mapOf(
            "camping" to mapOf(
                "name" to "강릉 여행",
                "ownerId" to DAD.id,
                "members" to mapOf(
                    DAD.id to member(InviteStatus.ACCEPTED, SharePermission.OFF, 0L),
                    // 엄마도 참여해 있어, 같은 차량에서 운전자를 바꿔 목적지 공유를 받아 볼 수 있다.
                    MOM.id to member(InviteStatus.ACCEPTED, SharePermission.OFF, 0L),
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
