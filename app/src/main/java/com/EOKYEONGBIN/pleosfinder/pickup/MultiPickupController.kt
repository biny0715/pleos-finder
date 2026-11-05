package com.EOKYEONGBIN.pleosfinder.pickup

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.core.iGa

/** 여러 명 픽업의 최종 목적지 */
data class MultiDestination(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String = "",
    val poiSubId: String = "",
)

/** 여러 명 픽업에서 한 사람의 현재 상태(화면 표시용) */
data class MultiStop(
    val member: Member,
    /** 운전자가 '반드시 먼저 픽업'으로 고정한 사람 */
    val pinned: Boolean,
    val consent: Consent,
    val location: MemberLocation?,
    val pickedUp: Boolean,
    /** 내비 경로 기준으로 이 사람까지 남은 거리·시간. 이번 구간에 없으면 null */
    val remainDistanceMeters: Int?,
    val remainTimeSeconds: Int?,
) {
    /** READY = 위치 사용 가능, WAITING = 일시적 허용이라 수락 대기, DECLINED = 거절, OFF = 공유 꺼짐 */
    enum class Consent { READY, WAITING, DECLINED, OFF }

    /** 위치가 2분보다 오래됐으면 화면에 경고한다(30분까지는 경로에 쓴다). */
    fun hasOldLocation(now: Long = System.currentTimeMillis()) =
        location != null && now - location.updatedAt > MultiPickupController.LOCATION_FRESH_MS
}

data class MultiPickupView(
    val stops: List<MultiStop>,
    val destination: MultiDestination?,
    /** 다음에 태울 사람(경로에 들어간 사람 중 첫 번째) */
    val next: MultiStop?,
    /** 모두 태우고 최종 목적지로 가는 중 */
    val headingToDestination: Boolean,
    val destinationRemainDistanceMeters: Int?,
    val destinationRemainTimeSeconds: Int?,
)

/**
 * 스마트 멀티 픽업 1단계: 운전자가 고른 순서(고정한 사람은 맨 앞) 그대로 여러 명을 태우고 최종 목적지로 간다.
 * - 사람별 공유 허용을 확인한다. 항상 허용이면 바로, 일시적 허용이면 폰에 요청하고 수락한 사람만 경로에 넣는다.
 * - NaviHelper 경유지는 FIRST·SECOND 두 곳뿐이라, 세 명 이상이면 '경유지 두 곳 + 다음 사람을 목적지'로 구간을 나눠
 *   안내하고 한 명을 태울 때마다 남은 경로를 다시 요청한다(순서는 바꾸지 않는다).
 * - 내비의 경유지별 남은 거리·시간을 그 사람의 폰으로 보낸다. 이번 구간 밖의 사람은 앞사람을 태운 뒤 계산한다.
 * - 탑승은 차 50m 안 도착, 내비의 경유지·목적지 도착, 운전자의 '탑승 완료' 중 하나로 판정한다.
 * 더 빠른 순서 추천(2단계)은 아직 없다. NaviHelper 콜백은 바인더 스레드에서 오므로 메인 스레드로 넘겨 처리한다.
 */
class MultiPickupController(
    private val naviHelper: NaviHelper,
    private val repository: FinderRepository,
    private val driver: Member,
    private val permissionOf: (Member) -> SharePermission,
    private val onState: (PickupState) -> Unit,
    private val onMessage: (String) -> Unit,
) : NaviHelperEventListener {

    private class Stop(val member: Member, val pinned: Boolean, val temporary: Boolean) {
        var consent = MultiStop.Consent.READY
        var location: MemberLocation? = null
        var pickedUp = false
        var remainDistanceMeters: Int? = null
        var remainTimeSeconds: Int? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stops = mutableListOf<Stop>()
    private val subscriptions = mutableListOf<Subscription>()
    private var destination: MultiDestination? = null
    private var vehicleLatitude: Double? = null
    private var vehicleLongitude: Double? = null
    private var startedAt = 0L

    /** 지금 경로에 넣은 사람(경유지 순서, 목적지가 사람이면 마지막) */
    private var leg: List<Stop> = emptyList()
    private var legEndsAtDestination = false
    private var routedLocations: Map<String, MemberLocation> = emptyMap()
    private var lastRouteAt = 0L
    private var lastPublishAt = 0L
    private var destinationRemainDistanceMeters: Int? = null
    private var destinationRemainTimeSeconds: Int? = null

    /**
     * 경로 요청을 잠깐 모아서 한 번만 보낸다. 시작할 때 여러 사람의 위치가 거의 동시에 도착하는데,
     * 내비는 연달아 온 요청 중 하나만 반영해 앱이 생각하는 경로와 실제 경로가 달라질 수 있다.
     */
    private var pendingForce = false
    private val routeLater = Runnable {
        val force = pendingForce
        pendingForce = false
        routeNow(force)
    }

    private val pollVehicleLocation = object : Runnable {
        override fun run() {
            if (!isActive) return
            naviHelper.getCurrentLocationInfo()
            mainHandler.postDelayed(this, VEHICLE_POLL_MS)
        }
    }

    val isActive: Boolean get() = stops.isNotEmpty()

    /** pinnedId 의 사람은 순서 맨 앞에 고정한다. */
    fun start(members: List<Member>, pinnedId: String?, finalDestination: MultiDestination?) {
        stop(notify = false)
        val ordered = members.sortedBy { if (it.id == pinnedId) 0 else 1 }
        startedAt = System.currentTimeMillis()
        destination = finalDestination
        for (member in ordered) {
            val permission = permissionOf(member)
            val stop = Stop(member, pinned = member.id == pinnedId, temporary = permission == SharePermission.TEMPORARY)
            stop.consent = when (permission) {
                SharePermission.ALWAYS -> MultiStop.Consent.READY
                SharePermission.TEMPORARY -> MultiStop.Consent.WAITING
                SharePermission.OFF -> MultiStop.Consent.OFF
            }
            stops += stop
            if (stop.consent == MultiStop.Consent.OFF) {
                onMessage("${member.name.iGa} 위치 공유를 꺼 두어 이번 픽업에서 빠져요.")
                continue
            }
            subscriptions += repository.observeMemberLocation(member.id) { onLocation(stop, it) }
            if (stop.temporary) {
                repository.publishShareRequest(ShareRequest(member.id, driver.id, driver.name, ShareRequest.State.PENDING, startedAt))
                subscriptions += repository.observeShareRequest(member.id) { onShareRequest(stop, it) }
            }
        }
        mainHandler.post(pollVehicleLocation)
        route()
        render()
    }

    /** 운전자가 끝낸다. 아직 타지 않은 사람에게 취소를 알리고, 일시적 허용 공유를 끝낸다. */
    fun stop(notify: Boolean = true) {
        if (!isActive) return
        for (stop in stops) {
            if (!stop.pickedUp && stop.consent == MultiStop.Consent.READY) sendStatus(stop, PickupStatus.State.CANCELLED)
            if (stop.temporary) endShare(stop)
        }
        if (leg.isNotEmpty() || legEndsAtDestination) naviHelper.cancelRoute()
        reset()
        if (notify) onState(PickupState.Idle)
    }

    /** 운전자가 '탑승 완료'를 눌렀다(다음 사람). */
    fun confirmNextPickedUp() {
        nextStop()?.let(::markPickedUp)
    }

    private fun reset() {
        mainHandler.removeCallbacks(routeLater)
        pendingForce = false
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        mainHandler.removeCallbacks(pollVehicleLocation)
        stops.clear()
        destination = null
        leg = emptyList()
        legEndsAtDestination = false
        routedLocations = emptyMap()
        lastRouteAt = 0L
        lastPublishAt = 0L
        destinationRemainDistanceMeters = null
        destinationRemainTimeSeconds = null
        vehicleLatitude = null
        vehicleLongitude = null
    }

    // ---- 동의·위치 ----

    private fun onShareRequest(stop: Stop, request: ShareRequest?) {
        // 이번 픽업에서 보낸 요청에 대한 응답만 본다.
        if (!isActive || request == null || request.requesterId != driver.id || request.updatedAt < startedAt) return
        when (request.state) {
            ShareRequest.State.ACCEPTED -> if (stop.consent == MultiStop.Consent.WAITING) {
                stop.consent = MultiStop.Consent.READY
                onMessage("${stop.member.name.iGa} 위치 공유를 수락했어요. 원래 순서에 넣어 안내해요.")
                route()
            }
            ShareRequest.State.DECLINED -> if (stop.consent == MultiStop.Consent.WAITING) {
                stop.consent = MultiStop.Consent.DECLINED
                onMessage("${stop.member.name.iGa} 위치 공유를 거절해 이번 픽업에서 빠져요.")
                finishIfDone()
            }
            ShareRequest.State.PENDING, ShareRequest.State.ENDED -> Unit
        }
        render()
    }

    private fun onLocation(stop: Stop, location: MemberLocation) {
        if (!isActive) return
        stop.location = location
        if (checkArrivals()) return
        // 경로에 들어간 사람이 30m 넘게 움직이면 같은 순서로 경로를 다시 요청한다.
        val routed = routedLocations[stop.member.id]
        if (routed != null && System.currentTimeMillis() - lastRouteAt >= MIN_REROUTE_INTERVAL_MS &&
            distanceMeters(routed.latitude, routed.longitude, location.latitude, location.longitude) >= REROUTE_DISTANCE_M
        ) {
            route(force = true)
        } else if (routed == null && stop.consent == MultiStop.Consent.READY && !stop.pickedUp) {
            // 위치가 늦게 도착한 사람을 경로에 넣는다.
            route()
        }
        render()
    }

    private fun usable(stop: Stop, now: Long = System.currentTimeMillis()): Boolean {
        val location = stop.location ?: return false
        return stop.consent == MultiStop.Consent.READY && !stop.pickedUp && now - location.updatedAt <= LOCATION_MAX_AGE_MS
    }

    private fun nextStop(): Stop? = leg.firstOrNull { !it.pickedUp }

    // ---- 경로 ----

    private fun route(force: Boolean = false) {
        if (!isActive) return
        pendingForce = pendingForce || force
        mainHandler.removeCallbacks(routeLater)
        mainHandler.postDelayed(routeLater, ROUTE_DEBOUNCE_MS)
    }

    /** 고른 순서 그대로 남은 사람을 경유지로 넣는다. 경유지는 두 곳까지라 세 번째 사람을 이번 구간 목적지로 둔다. */
    private fun routeNow(force: Boolean) {
        if (!isActive) return
        val remaining = stops.filter { usable(it) }
        val finalDestination = destination
        val newLeg: List<Stop>
        val endsAtDestination: Boolean
        when {
            remaining.isEmpty() -> {
                if (stops.any { it.consent == MultiStop.Consent.WAITING } || finalDestination == null || stops.none { it.pickedUp }) {
                    finishIfDone()
                    return
                }
                newLeg = emptyList()
                endsAtDestination = true
            }
            remaining.size > MAX_WAYPOINTS -> {
                newLeg = remaining.take(MAX_WAYPOINTS + 1)
                endsAtDestination = false
            }
            finalDestination != null -> {
                newLeg = remaining
                endsAtDestination = true
            }
            else -> {
                newLeg = remaining
                endsAtDestination = false
            }
        }
        val sameLeg = newLeg.map { it.member.id } == leg.map { it.member.id } && endsAtDestination == legEndsAtDestination
        if (sameLeg && !force) return

        val waypoints = if (endsAtDestination) newLeg else newLeg.dropLast(1)
        val target = if (endsAtDestination) {
            val d = finalDestination!!
            RouteInfo(latitude = d.latitude, longitude = d.longitude, poiName = d.name, poiId = d.poiId, poiSubId = d.poiSubId.ifEmpty { "0" }, address = d.address)
        } else {
            val last = newLeg.last()
            val location = last.location!!
            RouteInfo(latitude = location.latitude, longitude = location.longitude, poiName = last.member.name, poiId = "", poiSubId = "0", address = "")
        }
        if (waypoints.isEmpty()) {
            naviHelper.requestRoute(target)
        } else {
            naviHelper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    target,
                    waypoints.mapIndexed { index, stop ->
                        val location = stop.location!!
                        RequestWaypointInfo(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            poiName = stop.member.name,
                            waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                            poiId = "",
                            address = "",
                            poiSubId = "0",
                        )
                    },
                )
            )
        }
        leg = newLeg
        legEndsAtDestination = endsAtDestination
        routedLocations = newLeg.associate { it.member.id to it.location!! }
        lastRouteAt = System.currentTimeMillis()
        // 새 경로의 남은 거리·시간은 다음 주행 정보에서 받는다.
        stops.forEach { it.remainDistanceMeters = null; it.remainTimeSeconds = null }
        destinationRemainDistanceMeters = null
        destinationRemainTimeSeconds = null
        publishAll()
        render()
    }

    private fun markPickedUp(stop: Stop) {
        if (stop.pickedUp) return
        stop.pickedUp = true
        stop.remainDistanceMeters = null
        stop.remainTimeSeconds = null
        sendStatus(stop, PickupStatus.State.ARRIVED)
        if (stop.temporary) endShare(stop)
        onMessage("${stop.member.name} 탑승 완료")
        // 남은 사람 순서는 그대로 두고 다음 구간을 요청한다.
        if (!finishIfDone()) route(force = true)
        publishAll()
        render()
    }

    /** 태울 사람이 더 없고 최종 목적지도 없으면 끝낸다. 끝냈으면 true */
    private fun finishIfDone(): Boolean {
        if (stops.any { it.consent == MultiStop.Consent.WAITING }) return false
        if (stops.any { usable(it) || (it.consent == MultiStop.Consent.READY && !it.pickedUp) }) return false
        if (destination != null && stops.any { it.pickedUp }) return false
        complete()
        return true
    }

    private fun complete() {
        val picked = stops.count { it.pickedUp }
        if (leg.isNotEmpty() || legEndsAtDestination) naviHelper.cancelRoute()
        stops.filter { it.temporary }.forEach(::endShare)
        reset()
        onMessage(if (picked > 0) "여러 명 픽업을 마쳤어요 · ${picked}명 탑승" else "태울 사람이 없어 여러 명 픽업을 끝냈어요.")
        onState(PickupState.Idle)
    }

    /** 차 50m 안에 들어온 사람을 태운 것으로 본다. 처리했으면 true */
    private fun checkArrivals(): Boolean {
        val latitude = vehicleLatitude ?: return false
        val longitude = vehicleLongitude ?: return false
        val arrived = leg.firstOrNull { stop ->
            val location = stop.location
            !stop.pickedUp && location != null &&
                distanceMeters(latitude, longitude, location.latitude, location.longitude) <= ARRIVAL_RADIUS_M
        } ?: return false
        markPickedUp(arrived)
        return true
    }

    // ---- 진행 상황 전달 ----

    /** 탑승 전인 사람마다 남은 거리·시간과 내 차례, 앞사람을 폰으로 보낸다. */
    private fun publishAll() {
        lastPublishAt = System.currentTimeMillis()
        stops.filter { it.consent == MultiStop.Consent.READY && !it.pickedUp }.forEach { sendStatus(it, PickupStatus.State.ON_THE_WAY) }
    }

    private fun sendStatus(stop: Stop, state: PickupStatus.State) {
        val riders = stops.filter { it.consent == MultiStop.Consent.READY || it.consent == MultiStop.Consent.WAITING }
        val index = riders.indexOf(stop)
        val ahead = riders.take(maxOf(index, 0)).filter { !it.pickedUp }.joinToString(", ") { it.member.name }
        repository.publishPickupStatus(
            PickupStatus(
                targetMemberId = stop.member.id,
                driverName = driver.name,
                state = state,
                vehicleLatitude = vehicleLatitude,
                vehicleLongitude = vehicleLongitude,
                remainDistanceMeters = stop.remainDistanceMeters,
                remainTimeSeconds = stop.remainTimeSeconds,
                updatedAt = System.currentTimeMillis(),
                order = index + 1,
                total = riders.size,
                ahead = ahead,
            )
        )
    }

    private fun endShare(stop: Stop) {
        repository.publishShareRequest(ShareRequest(stop.member.id, driver.id, driver.name, ShareRequest.State.ENDED, System.currentTimeMillis()))
    }

    private fun render() {
        if (!isActive) return
        val view = MultiPickupView(
            stops = stops.map {
                MultiStop(it.member, it.pinned, it.consent, it.location, it.pickedUp, it.remainDistanceMeters, it.remainTimeSeconds)
            },
            destination = destination,
            next = nextStop()?.let { s -> MultiStop(s.member, s.pinned, s.consent, s.location, s.pickedUp, s.remainDistanceMeters, s.remainTimeSeconds) },
            headingToDestination = legEndsAtDestination && leg.isEmpty(),
            destinationRemainDistanceMeters = destinationRemainDistanceMeters,
            destinationRemainTimeSeconds = destinationRemainTimeSeconds,
        )
        onState(PickupState.Multi(view))
    }

    // ---- NaviHelper ----

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        mainHandler.post {
            if (!isActive) return@post
            vehicleLatitude = currentLocationInfo.latitude
            vehicleLongitude = currentLocationInfo.longitude
            checkArrivals()
        }
    }

    /** 경유지별 남은 거리·시간을 이번 구간의 사람에게 나눠 준다(내비 경로 기준, 직선거리 아님). */
    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
        val destinationState = drivingInfo.destination
        val waypointStates = drivingInfo.waypoints
        mainHandler.post {
            if (!isActive || leg.isEmpty() && !legEndsAtDestination) return@post
            // 새 경로의 첫 값은 바로 보낸다(정차 중에는 내비가 다시 보내지 않을 수 있다).
            val firstValues = stops.none { it.remainTimeSeconds != null } && destinationRemainTimeSeconds == null
            val waypointStops = if (legEndsAtDestination) leg else leg.dropLast(1)
            waypointStops.forEachIndexed { index, stop ->
                val state = waypointStates.getOrNull(index) ?: return@forEachIndexed
                // 경로 재탐색 중에는 0 이 들어오므로 직전 값을 유지한다.
                if (state.distance > 0) {
                    stop.remainDistanceMeters = state.distance
                    stop.remainTimeSeconds = state.duration
                }
            }
            if (destinationState.distance > 0) {
                if (legEndsAtDestination) {
                    destinationRemainDistanceMeters = destinationState.distance
                    destinationRemainTimeSeconds = destinationState.duration
                } else {
                    leg.lastOrNull()?.let {
                        it.remainDistanceMeters = destinationState.distance
                        it.remainTimeSeconds = destinationState.duration
                    }
                }
            }
            if (firstValues || System.currentTimeMillis() - lastPublishAt >= PUBLISH_INTERVAL_MS) publishAll()
            render()
        }
    }

    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
        val index = if (waypointArrivedInfo.index == WaypointIndex.SECOND) 1 else 0
        mainHandler.post {
            if (!isActive) return@post
            val waypointStops = if (legEndsAtDestination) leg else leg.dropLast(1)
            waypointStops.getOrNull(index)?.let(::markPickedUp)
        }
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        mainHandler.post {
            if (!isActive) return@post
            if (legEndsAtDestination) {
                if (stops.none { usable(it) }) complete()
            } else {
                leg.lastOrNull()?.let(::markPickedUp)
            }
        }
    }

    private fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, result)
        return result[0]
    }

    companion object {
        /** NaviHelper 3.0.5 의 경유지 순번은 FIRST·SECOND 뿐이다. */
        const val MAX_WAYPOINTS = 2
        const val LOCATION_FRESH_MS = 2 * 60_000L
        const val LOCATION_MAX_AGE_MS = 30 * 60_000L
        private const val REROUTE_DISTANCE_M = 30f
        private const val MIN_REROUTE_INTERVAL_MS = 15_000L
        private const val ARRIVAL_RADIUS_M = 50f
        private const val VEHICLE_POLL_MS = 5_000L
        private const val PUBLISH_INTERVAL_MS = 10_000L
        private const val ROUTE_DEBOUNCE_MS = 1_500L
    }
}
