package com.EOKYEONGBIN.pleosfinder.pickup

import android.location.Location
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus

/**
 * 픽업 모드: 구성원 위치를 목적지로 길 안내하고, 구성원이 움직이면 목적지를 갱신한다.
 * 진행 상황은 publishStatus 로 픽업 대상의 폰에 전달한다.
 * NaviHelper 콜백은 바인더 스레드에서 오므로 상태 변경은 모두 메인 스레드로 넘겨 처리한다.
 */
class PickupController(
    private val naviHelper: NaviHelper,
    private val locationSource: MemberLocationSource,
    private val driver: Member,
    private val publishStatus: (PickupStatus) -> Unit,
    private val onStatus: (String) -> Unit,
) : NaviHelperEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var member: Member? = null
    private var latestLocation: MemberLocation? = null
    private var routedLocation: MemberLocation? = null
    private var lastRouteRequestAt = 0L
    private var remainDistanceMeters: Int? = null
    private var remainTimeSeconds: Int? = null

    val isActive: Boolean get() = member != null

    fun start(target: Member) {
        stop()
        member = target
        sendStatus(target, PickupStatus.State.ON_THE_WAY)
        locationSource.start(target, ::onMemberLocation)
        render()
    }

    fun stop() {
        locationSource.stop()
        member?.let { sendStatus(it, PickupStatus.State.CANCELLED) }
        if (routedLocation != null) naviHelper.cancelRoute()
        reset()
    }

    private fun reset() {
        member = null
        latestLocation = null
        routedLocation = null
        lastRouteRequestAt = 0L
        remainDistanceMeters = null
        remainTimeSeconds = null
    }

    private fun onMemberLocation(location: MemberLocation) {
        val target = member ?: return
        latestLocation = location
        if (shouldReroute(location)) {
            naviHelper.requestRoute(
                RouteInfo(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    poiName = target.name,
                    poiId = "",
                    poiSubId = "0",
                    address = "",
                )
            )
            routedLocation = location
            lastRouteRequestAt = System.currentTimeMillis()
        }
        render()
    }

    private fun shouldReroute(location: MemberLocation): Boolean {
        val routed = routedLocation ?: return true
        if (System.currentTimeMillis() - lastRouteRequestAt < MIN_REROUTE_INTERVAL_MS) return false
        val moved = FloatArray(1)
        Location.distanceBetween(routed.latitude, routed.longitude, location.latitude, location.longitude, moved)
        return moved[0] >= REROUTE_DISTANCE_M
    }

    private fun sendStatus(target: Member, state: PickupStatus.State) {
        publishStatus(
            PickupStatus(
                targetMemberId = target.id,
                driverName = driver.name,
                state = state,
                vehicleLatitude = null,
                vehicleLongitude = null,
                remainDistanceMeters = remainDistanceMeters,
                remainTimeSeconds = remainTimeSeconds,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun render() {
        val target = member ?: return
        val location = latestLocation
        val position = if (location == null) "위치 받는 중" else "%.5f, %.5f".format(location.latitude, location.longitude)
        val distance = remainDistanceMeters
        val time = remainTimeSeconds
        val remain = if (distance == null || time == null) "경로 탐색 중" else
            "남은 거리 %.1fkm · 약 %d분".format(distance / 1000.0, (time + 59) / 60)
        onStatus("${target.name} 픽업 중\n${target.name} 위치: $position\n$remain")
    }

    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
        val destination = drivingInfo.destination
        mainHandler.post {
            // 경로 재탐색 중에는 0 이 들어오므로 직전 값을 유지한다.
            val target = member
            if (target == null || destination.distance <= 0) return@post
            remainDistanceMeters = destination.distance
            remainTimeSeconds = destination.duration
            sendStatus(target, PickupStatus.State.ON_THE_WAY)
            render()
        }
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        mainHandler.post {
            val target = member ?: return@post
            locationSource.stop()
            sendStatus(target, PickupStatus.State.ARRIVED)
            reset()
            onStatus("${target.name} 픽업 완료")
        }
    }

    private companion object {
        const val REROUTE_DISTANCE_M = 30f
        const val MIN_REROUTE_INTERVAL_MS = 10_000L
    }
}
