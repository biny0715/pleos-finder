package com.EOKYEONGBIN.pleosfinder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.Connection
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.DestinationProgress
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare
import com.EOKYEONGBIN.pleosfinder.core.eulReul
import com.EOKYEONGBIN.pleosfinder.core.euro
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository
import com.EOKYEONGBIN.pleosfinder.core.GroupMember
import com.EOKYEONGBIN.pleosfinder.core.gwaWa
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.core.VehicleInfo
import com.EOKYEONGBIN.pleosfinder.core.VehicleLocation
import com.EOKYEONGBIN.pleosfinder.pickup.MultiDestination
import com.EOKYEONGBIN.pleosfinder.pickup.MultiPickupController
import com.EOKYEONGBIN.pleosfinder.pickup.MultiStop
import com.EOKYEONGBIN.pleosfinder.pickup.PickupController
import com.EOKYEONGBIN.pleosfinder.pickup.PickupRequester
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import com.EOKYEONGBIN.pleosfinder.pickup.Recommendation
import com.EOKYEONGBIN.pleosfinder.pickup.RepositoryMemberLocationSource

/** 목록 한 줄에 그릴 상대(가족·친구). 아이콘은 hasFinderPhone(사람)과 vehicles(차량)로 정한다. */
data class Contact(
    val member: Member,
    val connection: Connection,
    val hasFinderPhone: Boolean,
    val vehicles: List<VehicleInfo>,
    /** 상대가 나에게 정한 허용 방식 */
    val permission: SharePermission,
) {
    val isAccepted: Boolean get() = connection.status == InviteStatus.ACCEPTED
}

/**
 * 차량 앱의 상태를 앱 전체에서 하나로 들고 있는 세션.
 * 화면(MainActivity)과 Gleo 음성 명령(GleoActionReceiver)이 같은 세션을 쓴다.
 * 이 차량(아이오닉5)에 로그인한 계정이 곧 운전자이고, 운전자를 바꾸면 연결·그룹·차량 위치 공유가 그 사람 기준으로 바뀐다.
 * 상태 변경과 리스너 호출은 모두 메인 스레드에서 한다.
 */
/** 그룹에 공유할 수 있는 목적지 후보 */
data class SharePlace(
    val kind: Kind,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String = "",
    val poiSubId: String = "",
) {
    /** CURRENT = 지금 안내 중인 목적지, SAVED = 저장한 장소(집·여행지), RECENT = 최근 목적지, HERE = 지금 내 차 위치(여기로 모이기) */
    enum class Kind { CURRENT, SAVED, RECENT, HERE }
}

class VehicleSession(private val context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /**
     * 이 차량. 기획상 차량에 로그인한 Pleos 계정으로 정해지고, 출품본은 프로필 메뉴에서 바꾼다
     * (에뮬레이터 두 대를 각각 다른 차량으로 시연).
     */
    var vehicle: VehicleInfo = prefs.getString(KEY_VEHICLE, null)?.let(DemoDirectory::vehicle) ?: DemoDirectory.IONIQ5
        private set
    val drivers: List<Member> get() = vehicle.driverIds.map(DemoDirectory::member)

    var me: Member = DemoDirectory.member(prefs.getString(KEY_DRIVER, null)?.takeIf { it in vehicle.driverIds } ?: vehicle.ownerId)
        private set

    private val repository: FinderRepository = FirebaseFinderRepository()
    private val naviHelper = NaviHelper(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 주행 중이면 입력이 필요한 작업(초대·그룹 만들기·이름 변경)과 운전자 전환을 막는다. */
    private val drivingRestriction = DrivingRestriction(context) { mainHandler.post { notifyChanged() } }
    val isDriving: Boolean get() = drivingRestriction.isDriving

    private lateinit var pickupController: PickupController
    private lateinit var pickupRequester: PickupRequester
    private lateinit var multiPickup: MultiPickupController

    var naviConnected = false
        private set
    var pickupState: PickupState = PickupState.Idle
        private set

    private var connections: List<Connection> = emptyList()
    private val permissionsToMe = mutableMapOf<String, SharePermission>()
    private val phoneLocations = mutableMapOf<String, MemberLocation>()

    /** 상대가 나에게 차량 위치를 보여 주는 종료 시각(상대 id → until) */
    private val vehicleSharesToMe = mutableMapOf<String, Long>()

    /** 내가 상대에게 내 차량 위치를 보여 주는 종료 시각(상대 id → until) */
    private var myVehicleShares: Map<String, Long> = emptyMap()
    var vehicleLocations: Map<String, VehicleLocation> = emptyMap()
        private set
    private var allGroups: List<FinderGroup> = emptyList()

    /** 내 차량의 현재 위치(NaviHelper) */
    var myPosition: Pair<Double, Double>? = null
        private set

    private val accountSubscriptions = mutableListOf<Subscription>()
    private val contactSubscriptions = mutableMapOf<String, List<Subscription>>()
    private var connectionsLoaded = false
    private val readyCallbacks = mutableListOf<() -> Unit>()

    /** 화면이 떠 있는 동안 MainActivity 가 등록한다. */
    var onChanged: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    /** 폰 위치처럼 목록은 그대로이고 지도만 바뀌는 변화 */
    var onLocationsChanged: (() -> Unit)? = null

    // ---- 그룹 목적지 공유 ----

    /** 그룹 id → 공유 중인 목적지 */
    var destinationShares: Map<String, DestinationShare> = emptyMap()
        private set

    /** 같은 그룹 구성원이 목적지를 공유했을 때(화면이 열려 있으면 수락 팝업) */
    var onDestinationShared: ((FinderGroup, DestinationShare) -> Unit)? = null
    private val promptedShares = mutableSetOf<String>()

    /** 지금 길 안내 중인 그룹 목적지(그룹 id, 공유 id). 남은 거리·시간을 그룹에 올린다. */
    private var activeDestination: Pair<String, String>? = null
        set(value) {
            field = value
            // 앱 프로세스가 다시 시작돼도 이어서 남은 거리·시간을 올리도록 운전자와 함께 저장한다.
            prefs.edit().putString(KEY_ACTIVE_DESTINATION, value?.let { (g, s) -> "${me.id}|$g|$s" }).apply()
        }
    private var lastProgressAt = 0L

    /** 지금 목적지를 따라가기 시작한 시각. 이보다 오래된 공유가 잠깐 보이는 것은 무시한다. */
    private var followingSince = 0L
    private var routeRequestedAt = 0L
    private var pendingPlaces: ((List<SharePlace>) -> Unit)? = null
    private var currentDestination: DestinationInfo? = null
    private var recentDestinations: List<RecentDestinationInfo>? = null

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onError(naviErrorCode: NaviErrorCode) = showMessage("길 안내 오류: $naviErrorCode")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            mainHandler.post { onVehiclePosition(currentLocationInfo.latitude, currentLocationInfo.longitude) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            mainHandler.post {
                currentDestination = destinationInfo
                deliverPlacesIfReady()
            }
        }
        override fun onRecentDestinationInfo(recentDestinationInfo: List<RecentDestinationInfo>) {
            mainHandler.post {
                recentDestinations = recentDestinationInfo
                deliverPlacesIfReady()
            }
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val destination = drivingInfo.destination
            mainHandler.post { onDestinationProgress(destination.distance, destination.duration) }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            mainHandler.post { onDestinationReached() }
        }
        override fun onRouteCancelled() {
            mainHandler.post {
                // 새 경로를 요청하면 내비가 이전 경로를 '취소'로 알린다. 방금 요청한 직후의 취소는 사용자가 끈 것이 아니다.
                if (System.currentTimeMillis() - routeRequestedAt > ROUTE_REPLACE_GRACE_MS) endDestination()
            }
        }
    }

    private val pollVehiclePosition = object : Runnable {
        override fun run() {
            if (naviConnected) naviHelper.getCurrentLocationInfo()
            mainHandler.postDelayed(this, VEHICLE_PUBLISH_MS)
        }
    }

    init {
        createPickup()
        naviHelper.initialize(
            onConnected = {
                naviHelper.addListener(naviEventListener)
                naviHelper.addListener(pickupController)
                naviHelper.addListener(multiPickup)
                mainHandler.post {
                    naviConnected = true
                    notifyChanged()
                }
            },
            onFailed = { throwable -> showMessage("내비게이션 연결 실패: ${throwable.message}") }
        )
        accountSubscriptions += subscribeAccount()
        repository.observeVehicleLocations {
            vehicleLocations = it
            notifyChanged()
        }
        repository.observeGroups {
            allGroups = it
            checkIncomingDestinations()
            notifyChanged()
        }
        restoreActiveDestination()
        repository.observeDestinationShares {
            destinationShares = it
            // 따라가던 공유가 끝났거나 바뀌었으면 그만둔다.
            // 따라가던 공유가 끝났거나 더 새 공유로 바뀌었으면 그만둔다.
            // 공유를 바꾸는 순간에는 바뀌기 전 데이터가 먼저 올 수 있어, 따라가기 시작한 뒤에 생긴 공유일 때만 본다.
            activeDestination?.let { (groupId, shareId) ->
                val current = it[groupId]
                if (current == null || (current.id != shareId && current.createdAt > followingSince)) endDestination()
            }
            checkIncomingDestinations()
            notifyChanged()
        }
        mainHandler.post(pollVehiclePosition)
    }

    // ---- 운전자(계정) ----

    /** 이 차량에 로그인한 운전자를 바꾼다. 진행 중인 픽업은 끝내고 새 운전자 기준으로 다시 구독한다. */
    /** 이 에뮬레이터가 시연하는 차량을 바꾼다. 운전자는 그 차량의 주인으로 바뀐다. */
    fun switchVehicle(next: VehicleInfo) {
        if (next == vehicle) return
        vehicle = next
        prefs.edit().putString(KEY_VEHICLE, next.id).apply()
        switchDriver(DemoDirectory.member(next.ownerId), force = true)
    }

    fun switchDriver(driver: Member, force: Boolean = false) {
        if (driver == me && !force) return
        pickupRequester.cancel()
        pickupController.stop()
        multiPickup.stop()
        endDestination()
        if (naviConnected) {
            naviHelper.removeListener(pickupController)
            naviHelper.removeListener(multiPickup)
        }
        accountSubscriptions.forEach { it.cancel() }
        accountSubscriptions.clear()
        contactSubscriptions.values.flatten().forEach { it.cancel() }
        contactSubscriptions.clear()
        connections = emptyList()
        permissionsToMe.clear()
        phoneLocations.clear()
        vehicleSharesToMe.clear()
        myVehicleShares = emptyMap()
        connectionsLoaded = false

        me = driver
        prefs.edit().putString(KEY_DRIVER, driver.id).apply()
        createPickup()
        if (naviConnected) {
            naviHelper.addListener(pickupController)
            naviHelper.addListener(multiPickup)
        }
        accountSubscriptions += subscribeAccount()
        myPosition?.let { (lat, lng) -> publishVehicle(lat, lng) }
        showMessage("${driver.name} · ${vehicle.name.euro} 시연해요. ${driver.name}의 공유 설정이 적용돼요.")
        checkIncomingDestinations()
        notifyChanged()
    }

    fun resetDemo() {
        pickupRequester.cancel()
        pickupController.stop()
        repository.resetDemo()
        showMessage("시연 데이터를 처음 상태로 되돌렸어요.")
    }

    private fun createPickup() {
        pickupController = PickupController(
            naviHelper = naviHelper,
            locationSource = RepositoryMemberLocationSource(repository),
            driver = me,
            publishStatus = repository::publishPickupStatus,
            onState = ::onPickupState,
            onFinished = { pickupRequester.endShare(it) },
        )
        pickupRequester = PickupRequester(repository, pickupController, me, ::onPickupState, ::showMessage)
        multiPickup = MultiPickupController(
            naviHelper, repository, me, ::permissionOf, ::onPickupState, ::showMessage,
            vehiclePosition = { myPosition },
            onRecommendation = ::onRecommendation,
        )
        pickupState = PickupState.Idle
    }

    private fun subscribeAccount(): List<Subscription> = listOf(
        repository.observeConnections(me.id) { list ->
            connections = list
            syncContactSubscriptions()
            connectionsLoaded = true
            flushReady()
            notifyChanged()
        },
        repository.observeVehicleShares(me.id) {
            myVehicleShares = it
            notifyChanged()
        },
    )

    /** 연결된 상대마다 허용 방식과 폰 위치를 구독한다. */
    private fun syncContactSubscriptions() {
        val ids = connections.map { it.otherId }.toSet()
        (contactSubscriptions.keys - ids).forEach { id ->
            contactSubscriptions.remove(id)?.forEach { it.cancel() }
            permissionsToMe.remove(id)
            phoneLocations.remove(id)
            vehicleSharesToMe.remove(id)
        }
        (ids - contactSubscriptions.keys).forEach { id ->
            contactSubscriptions[id] = listOf(
                repository.observePermission(id, me.id) {
                    permissionsToMe[id] = it
                    flushReady()
                    notifyChanged()
                },
                repository.observeMemberLocation(id) {
                    phoneLocations[id] = it
                    onLocationsChanged?.invoke()
                },
                repository.observeVehicleShares(id) { shares ->
                    val until = shares[me.id]
                    if (until == null) vehicleSharesToMe.remove(id) else vehicleSharesToMe[id] = until
                    notifyChanged()
                },
            )
        }
    }

    // ---- 목록 ----

    /** 지금 운전자가 부르는 이름(아빠에게 엄마는 '와이프')을 담은 Member. 앱 안의 모든 상대는 이걸로 만든다. */
    fun person(id: String): Member = DemoDirectory.memberFor(me.id, id)

    fun contacts(relation: Relation): List<Contact> = connections
        .filter { it.relation == relation && it.status != InviteStatus.DECLINED }
        .map { connection ->
            Contact(
                member = person(connection.otherId),
                connection = connection,
                hasFinderPhone = DemoDirectory.hasFinderPhone(connection.otherId),
                vehicles = DemoDirectory.vehiclesDrivenBy(connection.otherId),
                permission = permissionsToMe[connection.otherId] ?: SharePermission.OFF,
            )
        }
        .sortedWith(compareBy({ !it.isAccepted }, { it.member.name }))

    /** 연락처 중 아직 가족·친구가 아닌 사람 */
    fun addableContacts(): List<Member> {
        val linked = connections.filter { it.status != InviteStatus.DECLINED }.map { it.otherId }.toSet()
        return DemoDirectory.accounts.map { person(it.member.id) }.filter { it.id != me.id && it.id !in linked }
    }

    /** 픽업할 수 있는 사람(수락한 가족·친구 중 폰 앱이 있는 사람). Gleo 이름 찾기에 쓴다. */
    fun pickupCandidates(): List<Member> = Relation.entries.flatMap(::contacts)
        .filter { it.isAccepted && it.hasFinderPhone }
        .map { it.member }

    fun isConnected(member: Member) = connections.any { it.otherId == member.id && it.status == InviteStatus.ACCEPTED }

    fun permissionOf(member: Member): SharePermission = permissionsToMe[member.id] ?: SharePermission.OFF

    /**
     * 지도에 보일 사람 위치: 나에게 항상 허용이거나, 함께 참여한 그룹에 지금 공유 중인 사람.
     * 픽업 중인 상대는 공유를 수락했으므로 함께 넣는다.
     */
    fun visiblePeopleLocations(): List<Pair<Member, MemberLocation>> {
        val pickupTarget = (pickupState as? PickupState.OnTheWay)?.target?.id
        val multiRiders = (pickupState as? PickupState.Multi)?.view?.stops
            ?.filter { it.consent == MultiStop.Consent.READY && !it.pickedUp }?.map { it.member.id }.orEmpty()
        return phoneLocations.mapNotNull { (id, location) ->
            val shared = id == pickupTarget || id in multiRiders ||
                (permissionsToMe[id] == SharePermission.ALWAYS && isConnected(DemoDirectory.member(id))) ||
                myGroups().any { g -> g.memberOf(me.id)?.status == InviteStatus.ACCEPTED && g.memberOf(id)?.isSharing() == true }
            if (shared) person(id) to location else null
        }
    }

    /** 마지막으로 받은 폰 위치. 공유가 꺼져 있으면 오래된 값일 수 있다. */
    fun lastLocationOf(member: Member): MemberLocation? = phoneLocations[member.id]

    /**
     * 상대 차량의 위치 중 내가 볼 수 있는 것. 차량 위치는 그 차를 지금 운전하는 사람의 설정을 따른다:
     * 그 사람이 나에게 '항상 허용'이거나, 같은 그룹에서 지금 공유 중이면 보인다.
     */
    fun visibleVehicleLocation(vehicle: VehicleInfo): VehicleLocation? {
        val location = vehicleLocations[vehicle.id] ?: return null
        val driverId = location.driverId
        if (driverId == me.id) return location
        if (permissionsToMe[driverId] == SharePermission.ALWAYS && isConnected(DemoDirectory.member(driverId))) return location
        if ((vehicleSharesToMe[driverId] ?: 0L) > System.currentTimeMillis()) return location
        val sharedInGroup = myGroups().any { group ->
            group.memberOf(me.id)?.status == InviteStatus.ACCEPTED && group.memberOf(driverId)?.isSharing() == true
        }
        return if (sharedInGroup) location else null
    }

    /**
     * 이 사람이 지금 운전 중인 차량과 그 위치(내가 볼 수 있을 때만).
     * 같은 차를 여러 명이 운전해도 지금 운전자에게만 붙는다(엄마가 아빠 차를 운전하지 않으면 엄마 옆에 표시하지 않음).
     */
    fun drivingVehicleOf(member: Member): Pair<VehicleInfo, VehicleLocation>? =
        DemoDirectory.vehiclesDrivenBy(member.id).firstNotNullOfOrNull { vehicle ->
            visibleVehicleLocation(vehicle)?.takeIf { it.driverId == member.id }?.let { vehicle to it }
        }

    // ---- 그룹 ----

    fun myGroups(): List<FinderGroup> = allGroups.filter { it.memberOf(me.id) != null && it.memberOf(me.id)?.status != InviteStatus.DECLINED }

    fun group(id: String): FinderGroup? = allGroups.firstOrNull { it.id == id }

    fun createGroup(name: String): String {
        val id = repository.createGroup(name, me.id)
        showMessage("'$name' 그룹을 만들었어요.")
        return id
    }

    fun renameGroup(group: FinderGroup, name: String) {
        if (name == group.name) return
        repository.renameGroup(group.id, name)
        showMessage("그룹 이름을 '$name'(으)로 바꿨어요.")
    }

    fun inviteToGroup(group: FinderGroup, member: Member) {
        repository.inviteToGroup(group.id, member.id)
        showMessage("${member.name}에게 '${group.name}' 그룹 초대를 보냈어요.")
    }

    fun respondGroupInvite(group: FinderGroup, accept: Boolean) {
        val mine = group.memberOf(me.id) ?: return
        repository.setGroupMember(group.id, mine.copy(status = if (accept) InviteStatus.ACCEPTED else InviteStatus.DECLINED))
    }

    /** 그룹에 내(운전 중인 차량 포함) 위치를 보여 주는 방식. 일시적 허용을 고르면 30분부터 시작한다. */
    fun setGroupShare(group: FinderGroup, share: SharePermission) {
        val mine = group.memberOf(me.id) ?: return
        repository.setGroupMember(group.id, mine.withShare(share))
    }

    /** 그룹에 정한 시각까지 일시적으로 공유한다(Gleo: "강릉 여행 그룹에 2시간만 공유해줘"). */
    fun setGroupShareUntil(group: FinderGroup, until: Long) {
        val mine = group.memberOf(me.id) ?: return
        repository.setGroupMember(group.id, mine.copy(share = SharePermission.TEMPORARY, shareUntil = until))
        showMessage("'${group.name}' 그룹에 ${TIME.format(java.util.Date(until))}까지 내 위치를 공유해요.")
    }

    /** 일시적 허용 시간을 30분 늘린다. 이미 끝났으면 지금부터 30분. */
    fun extendGroupShare(group: FinderGroup) {
        val mine = group.memberOf(me.id) ?: return
        repository.setGroupMember(group.id, mine.extended())
    }

    /** 그룹에 보낼 수 있는 연락처(아직 구성원이 아닌 사람) */
    fun invitableToGroup(group: FinderGroup): List<Member> {
        val inGroup = group.members.filter { it.status != InviteStatus.DECLINED }.map { it.accountId }.toSet()
        return DemoDirectory.accounts.map { person(it.member.id) }.filter { it.id !in inGroup }
    }

    // ---- 가족·친구 ----

    fun invite(member: Member, relation: Relation) {
        repository.inviteConnection(me.id, member.id, relation)
        val label = if (relation == Relation.FAMILY) "가족" else "친구"
        showMessage("${member.name}에게 $label 초대를 보냈어요.")
    }

    fun respondInvite(contact: Contact, accept: Boolean) {
        val c = contact.connection
        repository.respondConnection(me.id, c.otherId, c.relation, c.inviterId, accept)
    }

    // ---- 픽업·길 안내 ----

    val isPickupBusy: Boolean
        get() = pickupState is PickupState.WaitingForAcceptance || pickupState is PickupState.OnTheWay || pickupState is PickupState.Multi

    /**
     * 스마트 멀티 픽업 1단계: 고른 순서 그대로(pinnedId 는 맨 앞 고정) 여러 명을 태우고 최종 목적지로 간다.
     * 한 명만 고르면 기존 단일 픽업으로 이어진다.
     */
    fun startMultiPickup(members: List<Member>, pinnedId: String?, finalDestination: SharePlace?) {
        if (members.isEmpty()) return
        if (members.size == 1) {
            startPickup(members.first())
            return
        }
        endDestination()
        pickupRequester.cancel()
        pickupController.stop()
        multiPickup.start(
            members, pinnedId,
            finalDestination?.let { MultiDestination(it.name, it.address, it.latitude, it.longitude, it.poiId, it.poiSubId) },
        )
    }

    /** 여러 명 픽업에서 다음 사람을 태웠다고 운전자가 확인한다. */
    fun confirmNextPickedUp() = multiPickup.confirmNextPickedUp()

    // ---- 스마트 멀티 픽업 2·3단계: 순서 추천 ----

    /** 화면이 열려 있으면 추천 팝업을 띄운다. */
    var onRecommendationShown: ((Recommendation) -> Unit)? = null

    /** Gleo 로 시작한 여러 명 픽업이 추천 결과를 기다리는 중(결과를 Gleo 음성 답으로 돌려준다) */
    private var gleoRecommendationWaiter: ((Recommendation?) -> Unit)? = null

    val currentRecommendation: Recommendation? get() = multiPickup.recommendation

    /** 순서 추천 결과를 기다린다. timeoutMs 안에 계산되지 않으면 null(추천 없이 진행)로 알린다. */
    fun awaitRecommendation(timeoutMs: Long, callback: (Recommendation?) -> Unit) {
        val waiter = object : (Recommendation?) -> Unit {
            var done = false
            override fun invoke(result: Recommendation?) {
                if (done) return
                done = true
                gleoRecommendationWaiter = null
                callback(result)
            }
        }
        gleoRecommendationWaiter = waiter
        mainHandler.postDelayed({ waiter(multiPickup.recommendation) }, timeoutMs)
    }

    fun acceptRecommendation(): Boolean = multiPickup.acceptRecommendation()

    fun keepOrder(): Boolean = multiPickup.keepOrder().also { if (it) showMessage("기존 순서를 유지해요.") }

    private fun onRecommendation(result: Recommendation?) {
        val waiter = gleoRecommendationWaiter
        if (waiter != null) {
            // Gleo 로 시작했으면 Gleo 가 음성으로 추천하고, 화면 팝업도 함께 띄운다.
            waiter(result)
            if (result != null) onRecommendationShown?.invoke(result)
            return
        }
        if (result == null) return
        // 화면에서 시작했으면 팝업과 함께 음성으로 추천한다.
        onRecommendationShown?.invoke(result)
        voice.speak(recommendationSpeech(result))
    }

    /** "아들을 먼저 태우면 약 8분 빠릅니다. 순서를 바꿀까요?" */
    fun recommendationSpeech(rec: Recommendation): String {
        val first = rec.recommended.first().name
        val changedFirst = rec.recommended.first() != rec.current.first()
        val lead = if (changedFirst) "${first.eulReul} 먼저 태우면" else "${rec.orderText(rec.recommended)} 순서로 가면"
        return "$lead 약 ${rec.savedMinutes}분 빠릅니다. 순서를 바꿀까요?"
    }

    /** 화면에서 시작한 추천을 읽어 주는 음성 안내(차량의 기본 음성 합성 엔진) */
    private val voice by lazy { VoiceGuide(context) }

    fun startPickup(member: Member) {
        // 픽업 안내가 그룹 목적지 안내를 대신한다.
        endDestination()
        pickupRequester.request(member, permissionOf(member))
    }

    // ---- 그룹 목적지 공유 ----

    /** 이 그룹에 지금 공유 중인 목적지(오래된 것은 빼고) */
    fun destinationOf(group: FinderGroup): DestinationShare? = destinationShares[group.id]?.takeIf { it.isActive() }

    /** 내가 참여한 그룹에 지금 공유 중인 목적지들(지도 표시용) */
    fun activeDestinations(): List<DestinationShare> =
        myGroups().filter { it.memberOf(me.id)?.status == InviteStatus.ACCEPTED }.mapNotNull(::destinationOf)

    /** 지금 길 안내 중인 그룹 목적지 */
    fun followingDestination(): DestinationShare? =
        activeDestination?.let { (groupId, shareId) -> destinationShares[groupId]?.takeIf { it.id == shareId } }

    /** 내가 지금 이 공유 목적지로 안내받고 있는지 */
    fun isFollowing(share: DestinationShare) = activeDestination == (share.groupId to share.id)

    /**
     * 공유할 목적지 후보: 지금 안내 중인 목적지, 최근 목적지, 지금 내 차 위치.
     * 내비 응답이 없을 수도 있어 1.5초 안에 받은 것만 쓴다.
     */
    fun loadSharePlaces(onLoaded: (List<SharePlace>) -> Unit) {
        currentDestination = null
        recentDestinations = null
        pendingPlaces = onLoaded
        if (naviConnected) {
            naviHelper.getDestinationInfo()
            naviHelper.getRecentDestinationInfo()
        }
        mainHandler.postDelayed({ deliverPlaces() }, PLACES_TIMEOUT_MS)
    }

    private fun deliverPlacesIfReady() {
        if (currentDestination != null && recentDestinations != null) deliverPlaces()
    }

    private fun deliverPlaces() {
        val callback = pendingPlaces ?: return
        pendingPlaces = null
        val places = mutableListOf<SharePlace>()
        currentDestination?.takeIf { it.name.isNotBlank() && (it.latitude != 0.0 || it.longitude != 0.0) }?.let {
            places += SharePlace(SharePlace.Kind.CURRENT, it.name, it.address.orEmpty(), it.latitude, it.longitude, it.poiId.orEmpty(), it.poiSubId.orEmpty())
        }
        DemoDirectory.savedPlaces.forEach { places += SharePlace(SharePlace.Kind.SAVED, it.name, it.address, it.latitude, it.longitude) }
        // 지난 픽업은 사람 위치가 '최근 목적지'로 남으므로 연락처 이름은 뺀다.
        val contactNames = DemoDirectory.contactNames
        recentDestinations.orEmpty()
            .filter { it.name.isNotBlank() && it.name !in contactNames && places.none { p -> p.name == it.name } }
            .take(MAX_RECENT_PLACES)
            .forEach { places += SharePlace(SharePlace.Kind.RECENT, it.name, it.address.orEmpty(), it.latitude, it.longitude, it.poiId.orEmpty(), it.poiSubId.orEmpty()) }
        myPosition?.let { (lat, lng) -> places += SharePlace(SharePlace.Kind.HERE, "${me.name}의 차 위치", "", lat, lng) }
        callback(places)
    }

    /**
     * 그룹에 목적지를 공유한다. 지금 안내 중이거나 최근 목적지를 고르면 나도 그 목적지로 함께 가고,
     * 내 차 위치(여기로 모이기)를 고르면 나는 안내받지 않는다.
     */
    fun shareDestination(group: FinderGroup, place: SharePlace) {
        endDestination()
        val goTogether = place.kind != SharePlace.Kind.HERE && !isPickupBusy
        val share = DestinationShare(
            groupId = group.id, id = "", fromId = me.id,
            name = place.name, address = place.address, latitude = place.latitude, longitude = place.longitude,
            poiId = place.poiId, poiSubId = place.poiSubId, createdAt = System.currentTimeMillis(),
            responses = if (goTogether) mapOf(me.id to true) else emptyMap(),
        )
        val id = repository.shareDestination(share)
        if (goTogether) {
            // 지금 안내 중인 목적지는 이미 안내 중이고, 저장한 장소·최근 목적지는 새로 안내를 시작한다.
            if (place.kind != SharePlace.Kind.CURRENT) requestRoute(share)
            followingSince = System.currentTimeMillis()
            activeDestination = group.id to id
        }
        showMessage("'${group.name}' 그룹에 ${place.name.eulReul} 목적지로 공유했어요.")
    }

    /** 받은 목적지로 내 차량 내비 길 안내를 시작한다. */
    fun acceptDestination(share: DestinationShare): Boolean {
        if (isPickupBusy) {
            showMessage("픽업 중에는 목적지 안내를 시작할 수 없어요. 픽업을 끝낸 뒤 그룹에서 다시 시작해 주세요.")
            return false
        }
        if (!naviConnected) {
            showMessage("내비게이션에 연결되지 않았어요.")
            return false
        }
        endDestination()
        requestRoute(share)
        followingSince = System.currentTimeMillis()
        activeDestination = share.groupId to share.id
        repository.respondDestination(share.groupId, share.id, me.id, true)
        DestinationNotifier.cancel(context, share)
        showMessage("'${share.name}'(으)로 안내를 시작했어요.")
        return true
    }

    fun declineDestination(share: DestinationShare) {
        repository.respondDestination(share.groupId, share.id, me.id, false)
        DestinationNotifier.cancel(context, share)
    }

    /** 공유한 사람이 공유를 끝낸다. */
    fun clearDestination(group: FinderGroup) {
        if (activeDestination?.first == group.id) endDestination()
        repository.clearDestination(group.id)
    }

    private fun requestRoute(share: DestinationShare) {
        routeRequestedAt = System.currentTimeMillis()
        naviHelper.requestRoute(
            RouteInfo(
                latitude = share.latitude,
                longitude = share.longitude,
                poiName = share.name,
                poiId = share.poiId,
                poiSubId = share.poiSubId.ifEmpty { "0" },
                address = share.address,
            )
        )
    }

    private fun restoreActiveDestination() {
        val saved = prefs.getString(KEY_ACTIVE_DESTINATION, null)?.split("|") ?: return
        if (saved.size == 3 && saved[0] == me.id) activeDestination = saved[1] to saved[2]
    }

    /** 내가 참여한 그룹에 새로 공유된, 아직 답하지 않은 목적지를 한 번씩 알린다. */
    private fun checkIncomingDestinations() {
        for (share in destinationShares.values) {
            val group = group(share.groupId) ?: continue
            if (group.memberOf(me.id)?.status != InviteStatus.ACCEPTED) continue
            if (!share.awaits(me.id) || !share.isPromptable()) continue
            if (!promptedShares.add("${me.id}:${share.id}")) continue
            // Finder 화면이 열려 있으면 화면 팝업만, 내비 등 다른 화면이면 알림으로 묻는다(둘이 겹치지 않게).
            val prompt = onDestinationShared
            if (prompt != null) prompt(group, share) else DestinationNotifier.notify(context, group, share, person(share.fromId).name)
        }
    }

    private fun onDestinationProgress(distance: Int, duration: Int) {
        val (groupId, shareId) = activeDestination ?: return
        // 경로 재탐색 중에는 0 이 들어온다. 서버에는 10초에 한 번만 올린다.
        val now = System.currentTimeMillis()
        if (distance <= 0 || now - lastProgressAt < PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        repository.publishDestinationProgress(groupId, shareId, me.id, DestinationProgress(distance, duration, arrived = false, updatedAt = now))
    }

    private fun onDestinationReached() {
        val (groupId, shareId) = activeDestination ?: return
        repository.publishDestinationProgress(groupId, shareId, me.id, DestinationProgress(0, 0, arrived = true, updatedAt = System.currentTimeMillis()))
        activeDestination = null
        showMessage("공유받은 목적지에 도착했어요.")
        notifyChanged()
    }

    /** 그룹 목적지 안내를 그만둔다(안내 취소, 픽업 시작, 운전자 변경). 그룹에서 내 남은 거리·시간을 지운다. */
    private fun endDestination() {
        val (groupId, shareId) = activeDestination ?: return
        activeDestination = null
        lastProgressAt = 0L
        repository.publishDestinationProgress(groupId, shareId, me.id, null)
        notifyChanged()
    }

    fun stopPickup() {
        when (pickupState) {
            is PickupState.WaitingForAcceptance -> pickupRequester.cancel()
            is PickupState.OnTheWay -> {
                pickupController.stop()
                showMessage("픽업을 종료했어요.")
            }
            is PickupState.Multi -> {
                multiPickup.stop()
                showMessage("여러 명 픽업을 종료했어요. 아직 타지 않은 사람에게 알렸어요.")
            }
            else -> Unit
        }
    }

    // ---- 차량끼리 위치 공유 ----

    /** 내 차량 위치를 이 상대에게 보여 주는 중이면 종료 시각, 아니면 null */
    fun myVehicleShareUntil(member: Member): Long? = myVehicleShares[member.id]?.takeIf { it > System.currentTimeMillis() }

    /** 상대가 자기 차량 위치를 나에게 보여 주는 중이면 종료 시각, 아니면 null */
    fun vehicleShareToMeUntil(member: Member): Long? = vehicleSharesToMe[member.id]?.takeIf { it > System.currentTimeMillis() }

    /**
     * 차량끼리 위치 공유(항상 일시적). 내가 운전하는 차량 위치를 until 시각까지 상대에게 보여 주고,
     * 상대도 같은 시간 동안 자기 차량 위치를 나에게 보여 주는 서로 공유다.
     * 출품본은 상대 차량을 시연 데이터로 두므로 상대 쪽 공유도 같은 시각으로 함께 기록한다.
     */
    fun shareVehicle(member: Member, until: Long) {
        repository.setVehicleShare(me.id, member.id, until)
        repository.setVehicleShare(member.id, me.id, until)
        showMessage("${member.name.gwaWa} ${TIME.format(java.util.Date(until))}까지 차량 위치를 서로 공유해요.")
    }

    fun stopVehicleShare(member: Member) {
        repository.setVehicleShare(me.id, member.id, 0L)
        repository.setVehicleShare(member.id, me.id, 0L)
        showMessage("${member.name.gwaWa}의 차량 위치 공유를 끝냈어요.")
    }

    /** 서버에서 연결 정보를 처음 받아 올 때까지 기다린다. 앱 프로세스가 Gleo 호출로 막 시작된 경우에 쓴다. */
    fun whenReady(timeoutMs: Long, action: () -> Unit) {
        if (isReady()) {
            action()
            return
        }
        var done = false
        val once = {
            if (!done) {
                done = true
                action()
            }
        }
        readyCallbacks += once
        mainHandler.postDelayed({ once() }, timeoutMs)
    }

    private fun isReady() = connectionsLoaded && connections.all { it.otherId in permissionsToMe }

    private fun flushReady() {
        if (!isReady()) return
        readyCallbacks.toList().forEach { it() }
        readyCallbacks.clear()
    }

    private fun onVehiclePosition(latitude: Double, longitude: Double) {
        myPosition = latitude to longitude
        publishVehicle(latitude, longitude)
    }

    /** 내 차량 위치를 '지금 운전자' 이름으로 올린다. 누가 볼 수 있는지는 그 운전자의 설정이 정한다. */
    private fun publishVehicle(latitude: Double, longitude: Double) {
        repository.publishVehicleLocation(VehicleLocation(vehicle.id, me.id, latitude, longitude, System.currentTimeMillis()))
    }

    private fun onPickupState(state: PickupState) = mainHandler.post {
        pickupState = state
        if (state is PickupState.Completed) showMessage("${state.target.name} 픽업 완료 · 도착 알림을 보냈어요.")
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    private fun showMessage(message: String) {
        Log.d(TAG, message)
        mainHandler.post { onMessage?.invoke(message) }
    }

    companion object {
        private const val TAG = "PleosFinder"
        private const val KEY_DRIVER = "driver"
        private const val KEY_VEHICLE = "vehicle"
        private const val KEY_ACTIVE_DESTINATION = "activeDestination"
        private const val VEHICLE_PUBLISH_MS = 15_000L
        private const val PLACES_TIMEOUT_MS = 1_500L
        private const val MAX_RECENT_PLACES = 5
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val ROUTE_REPLACE_GRACE_MS = 5_000L
        const val TEMPORARY_STEP_MS = GroupMember.SHARE_STEP_MS
        private val TIME = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA)
    }
}
