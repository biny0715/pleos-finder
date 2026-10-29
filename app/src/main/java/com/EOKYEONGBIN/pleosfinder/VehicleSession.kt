package com.EOKYEONGBIN.pleosfinder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.Connection
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository
import com.EOKYEONGBIN.pleosfinder.core.GroupMember
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.core.VehicleInfo
import com.EOKYEONGBIN.pleosfinder.core.VehicleLocation
import com.EOKYEONGBIN.pleosfinder.pickup.PickupController
import com.EOKYEONGBIN.pleosfinder.pickup.PickupRequester
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
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
class VehicleSession(context: Context) {

    val vehicle: VehicleInfo = DemoDirectory.IONIQ5
    val drivers: List<Member> = vehicle.driverIds.map(DemoDirectory::member)

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    var me: Member = DemoDirectory.member(prefs.getString(KEY_DRIVER, vehicle.ownerId)!!)
        private set

    private val repository: FinderRepository = FirebaseFinderRepository()
    private val naviHelper = NaviHelper(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var pickupController: PickupController
    private lateinit var pickupRequester: PickupRequester

    var naviConnected = false
        private set
    var pickupState: PickupState = PickupState.Idle
        private set

    private var connections: List<Connection> = emptyList()
    private val permissionsToMe = mutableMapOf<String, SharePermission>()
    private val phoneLocations = mutableMapOf<String, MemberLocation>()
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

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onError(naviErrorCode: NaviErrorCode) = showMessage("길 안내 오류: $naviErrorCode")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            mainHandler.post { onVehiclePosition(currentLocationInfo.latitude, currentLocationInfo.longitude) }
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
            notifyChanged()
        }
        mainHandler.post(pollVehiclePosition)
    }

    // ---- 운전자(계정) ----

    /** 이 차량에 로그인한 운전자를 바꾼다. 진행 중인 픽업은 끝내고 새 운전자 기준으로 다시 구독한다. */
    fun switchDriver(driver: Member) {
        if (driver == me) return
        pickupRequester.cancel()
        pickupController.stop()
        if (naviConnected) naviHelper.removeListener(pickupController)
        accountSubscriptions.forEach { it.cancel() }
        accountSubscriptions.clear()
        contactSubscriptions.values.flatten().forEach { it.cancel() }
        contactSubscriptions.clear()
        connections = emptyList()
        permissionsToMe.clear()
        phoneLocations.clear()
        connectionsLoaded = false

        me = driver
        prefs.edit().putString(KEY_DRIVER, driver.id).apply()
        createPickup()
        if (naviConnected) naviHelper.addListener(pickupController)
        accountSubscriptions += subscribeAccount()
        myPosition?.let { (lat, lng) -> publishVehicle(lat, lng) }
        showMessage("${driver.name}(으)로 운전자를 바꿨어요. ${driver.name}의 공유 설정이 적용돼요.")
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
        pickupState = PickupState.Idle
    }

    private fun subscribeAccount(): Subscription = repository.observeConnections(me.id) { list ->
        connections = list
        syncContactSubscriptions()
        connectionsLoaded = true
        flushReady()
        notifyChanged()
    }

    /** 연결된 상대마다 허용 방식과 폰 위치를 구독한다. */
    private fun syncContactSubscriptions() {
        val ids = connections.map { it.otherId }.toSet()
        (contactSubscriptions.keys - ids).forEach { id ->
            contactSubscriptions.remove(id)?.forEach { it.cancel() }
            permissionsToMe.remove(id)
            phoneLocations.remove(id)
        }
        (ids - contactSubscriptions.keys).forEach { id ->
            contactSubscriptions[id] = listOf(
                repository.observePermission(id, me.id) {
                    permissionsToMe[id] = it
                    flushReady()
                    notifyChanged()
                },
                repository.observeMemberLocation(id) { phoneLocations[id] = it },
            )
        }
    }

    // ---- 목록 ----

    fun contacts(relation: Relation): List<Contact> = connections
        .filter { it.relation == relation && it.status != InviteStatus.DECLINED }
        .map { connection ->
            Contact(
                member = DemoDirectory.member(connection.otherId),
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
        return DemoDirectory.accounts.map { it.member }.filter { it != me && it.id !in linked }
    }

    /** 픽업할 수 있는 사람(수락한 가족·친구 중 폰 앱이 있는 사람). Gleo 이름 찾기에 쓴다. */
    fun pickupCandidates(): List<Member> = Relation.entries.flatMap(::contacts)
        .filter { it.isAccepted && it.hasFinderPhone }
        .map { it.member }

    fun isConnected(member: Member) = connections.any { it.otherId == member.id && it.status == InviteStatus.ACCEPTED }

    fun permissionOf(member: Member): SharePermission = permissionsToMe[member.id] ?: SharePermission.OFF

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
        val until = if (share == SharePermission.TEMPORARY) System.currentTimeMillis() + TEMPORARY_STEP_MS else 0L
        repository.setGroupMember(group.id, mine.copy(share = share, shareUntil = until))
    }

    /** 일시적 허용 시간을 30분 늘린다. 이미 끝났으면 지금부터 30분. */
    fun extendGroupShare(group: FinderGroup) {
        val mine = group.memberOf(me.id) ?: return
        val now = System.currentTimeMillis()
        val base = if (mine.share == SharePermission.TEMPORARY && mine.shareUntil > now) mine.shareUntil else now
        repository.setGroupMember(group.id, mine.copy(share = SharePermission.TEMPORARY, shareUntil = base + TEMPORARY_STEP_MS))
    }

    /** 그룹에 보낼 수 있는 연락처(아직 구성원이 아닌 사람) */
    fun invitableToGroup(group: FinderGroup): List<Member> {
        val inGroup = group.members.filter { it.status != InviteStatus.DECLINED }.map { it.accountId }.toSet()
        return DemoDirectory.accounts.map { it.member }.filter { it.id !in inGroup }
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
        get() = pickupState is PickupState.WaitingForAcceptance || pickupState is PickupState.OnTheWay

    fun startPickup(member: Member) = pickupRequester.request(member, permissionOf(member))

    fun stopPickup() {
        when (pickupState) {
            is PickupState.WaitingForAcceptance -> pickupRequester.cancel()
            is PickupState.OnTheWay -> {
                pickupController.stop()
                showMessage("픽업을 종료했어요.")
            }
            else -> Unit
        }
    }

    /** 상대 차량의 마지막 위치로 길 안내한다(합류). */
    fun navigateToVehicle(owner: Member, vehicle: VehicleInfo) {
        val location = visibleVehicleLocation(vehicle) ?: run {
            showMessage("${owner.name}의 ${vehicle.name} 위치가 공유되지 않았어요.")
            return
        }
        naviHelper.requestRoute(
            RouteInfo(
                latitude = location.latitude,
                longitude = location.longitude,
                poiName = "${owner.name}의 ${vehicle.name}",
                poiId = "",
                poiSubId = "0",
                address = "",
            )
        )
        showMessage("${owner.name}의 ${vehicle.name}(으)로 길 안내를 시작해요.")
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
        private const val VEHICLE_PUBLISH_MS = 15_000L
        const val TEMPORARY_STEP_MS = 30 * 60_000L
    }
}

/** 그룹 구성원이 지금 공유 중인지를 사람이 읽을 문장으로 */
fun GroupMember.shareText(now: Long = System.currentTimeMillis()): String = when {
    status == InviteStatus.PENDING -> "초대 수락 대기 중"
    share == SharePermission.ALWAYS -> "항상 공유 중"
    share == SharePermission.TEMPORARY && now < shareUntil -> "${java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(shareUntil))}까지 공유"
    else -> "공유 안 함"
}
