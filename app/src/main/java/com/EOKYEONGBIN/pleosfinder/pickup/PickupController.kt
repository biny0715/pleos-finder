package com.EOKYEONGBIN.pleosfinder.pickup

import android.location.Location
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.data.CurrentLocationInfo
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
 * 내비의 목적지 도착 이벤트 외에도, 구성원이 차 근처(ARRIVAL_RADIUS_M)로 오면 도착으로 처리한다.
 * 진행 상황은 publishStatus 로 픽업 대상의 폰에 전달한다.
 * NaviHelper 콜백은 바인더 스레드에서 오므로 상태 변경은 모두 메인 스레드로 넘겨 처리한다.
 */
class PickupController(
    private val naviHelper: NaviHelper,
    private val locationSource: MemberLocationSource,
    private val driver: Member,
    private val publishStatus: (PickupStatus) -> Unit,
    private val onState: (PickupState) -> Unit,
    /** 픽업이 완료되거나 취소되면 호출된다. 일시적 허용 공유를 끝내는 데 쓴다. */
    private val onFinished: (Member) -> Unit = {},
) : NaviHelperEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var member: Member? = null
    private var latestLocation: MemberLocation? = null
    private var routedLocation: MemberLocation? = null
    private var vehicleLatitude: Double? = null
    private var vehicleLongitude: Double? = null
    private var lastRouteRequestAt = 0L
    private var remainDistanceMeters: Int? = null
    private var remainTimeSeconds: Int? = null

    private val pollVehicleLocation = object : Runnable {
        override fun run() {
            if (member == null) return
            naviHelper.getCurrentLocationInfo()
            mainHandler.postDelayed(this, VEHICLE_POLL_MS)
        }
    }

    val isActive: Boolean get() = member != null

    fun start(target: Member) {
        stop()
        member = target
        sendStatus(target, PickupStatus.State.ON_THE_WAY)
        locationSource.start(target, ::onMemberLocation)
        mainHandler.post(pollVehicleLocation)
        render()
    }

    fun stop() {
        val target = member
        if (target != null) sendStatus(target, PickupStatus.State.CANCELLED)
        if (routedLocation != null) naviHelper.cancelRoute()
        reset()
        if (target != null) {
            onFinished(target)
            onState(PickupState.Idle)
        }
    }

    private fun reset() {
        locationSource.stop()
        mainHandler.removeCallbacks(pollVehicleLocation)
        member = null
        latestLocation = null
        routedLocation = null
        vehicleLatitude = null
        vehicleLongitude = null
        lastRouteRequestAt = 0L
        remainDistanceMeters = null
        remainTimeSeconds = null
    }

    private fun onMemberLocation(location: MemberLocation) {
        val target = member ?: return
        // 공유가 꺼진 동안 서버에 남아 있던 예전 위치로는 안내하지 않는다.
        if (System.currentTimeMillis() - location.updatedAt > LOCATION_STALE_MS) return
        latestLocation = location
        if (checkArrival()) return
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
        return distanceMeters(routed.latitude, routed.longitude, location.latitude, location.longitude) >= REROUTE_DISTANCE_M
    }

    /** 구성원이 차 근처에 있으면 픽업을 완료하고 true 를 돌려준다. */
    private fun checkArrival(): Boolean {
        val location = latestLocation ?: return false
        val latitude = vehicleLatitude ?: return false
        val longitude = vehicleLongitude ?: return false
        if (distanceMeters(latitude, longitude, location.latitude, location.longitude) > ARRIVAL_RADIUS_M) return false
        if (routedLocation != null) naviHelper.cancelRoute()
        complete()
        return true
    }

    private fun complete() {
        val target = member ?: return
        sendStatus(target, PickupStatus.State.ARRIVED)
        reset()
        onFinished(target)
        onState(PickupState.Completed(target))
    }

    private fun sendStatus(target: Member, state: PickupStatus.State) {
        publishStatus(
            PickupStatus(
                targetMemberId = target.id,
                driverName = driver.name,
                state = state,
                vehicleLatitude = vehicleLatitude,
                vehicleLongitude = vehicleLongitude,
                remainDistanceMeters = remainDistanceMeters,
                remainTimeSeconds = remainTimeSeconds,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun render() {
        val target = member ?: return
        onState(PickupState.OnTheWay(target, latestLocation, remainDistanceMeters, remainTimeSeconds))
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        mainHandler.post {
            if (member == null) return@post
            vehicleLatitude = currentLocationInfo.latitude
            vehicleLongitude = currentLocationInfo.longitude
            checkArrival()
        }
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
        mainHandler.post { complete() }
    }

    private fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, result)
        return result[0]
    }

    private companion object {
        const val REROUTE_DISTANCE_M = 30f
        const val MIN_REROUTE_INTERVAL_MS = 10_000L
        const val ARRIVAL_RADIUS_M = 50f
        const val VEHICLE_POLL_MS = 5_000L
        const val LOCATION_STALE_MS = 2 * 60_000L
    }
}
