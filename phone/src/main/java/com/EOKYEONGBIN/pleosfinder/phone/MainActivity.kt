package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.EOKYEONGBIN.pleosfinder.core.Connection
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare
import com.EOKYEONGBIN.pleosfinder.core.euro
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.iGa
import com.EOKYEONGBIN.pleosfinder.core.progressOf
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.PickupStatus
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ShareRequest
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.core.ui.DestinationPromptDialog
import com.EOKYEONGBIN.pleosfinder.core.ui.Favorites
import com.EOKYEONGBIN.pleosfinder.core.ui.FinderMap
import com.EOKYEONGBIN.pleosfinder.core.ui.MapMarker
import com.EOKYEONGBIN.pleosfinder.core.ui.PleosTextInputDialog
import com.EOKYEONGBIN.pleosfinder.core.VehicleLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * 폰 앱 메인. 차량 앱과 같은 가족 · 친구 · 그룹 패널.
 * 사람을 누르면 그 사람에게 내 위치를 보여 주는 방식(항상 / 일시적 / 끄기)이 아래에 열린다.
 * 상대가 나에게 보여 주는 차량 위치(운전 중이면)도 그 사람 줄에 함께 보여 준다.
 */
class MainActivity : AppCompatActivity() {

    private val repository get() = FinderServices.repository
    private val me = FinderServices.me

    private lateinit var inviteCard: View
    private lateinit var inviteTitle: TextView
    private lateinit var inviteDetail: TextView
    private lateinit var requestCard: View
    private lateinit var requestTitle: TextView
    private lateinit var requestDetail: TextView
    private lateinit var pickupCard: View
    private lateinit var pickupHeadline: TextView
    private lateinit var pickupDetail: TextView
    private lateinit var pickupMetrics: View
    private lateinit var remainDistance: TextView
    private lateinit var remainTime: TextView
    private lateinit var familyList: LinearLayout
    private lateinit var friendList: LinearLayout
    private lateinit var groupList: LinearLayout
    private lateinit var shareDot: View
    private lateinit var shareText: TextView
    private lateinit var mapView: MapView
    private lateinit var map: FinderMap
    private lateinit var mainScroll: ScrollView
    private val favorites by lazy { Favorites(this) }

    /** 접어 둔 패널과 '더 보기'로 모두 펼친 패널 */
    private val collapsedSections = mutableSetOf<String>()
    private val expandedSections = mutableSetOf<String>()

    /** 같은 줄을 두 번 누르면 지도에서 그 사람을 가깝게 보여 준다. */
    private var lastTap: Pair<String, Long>? = null

    private var connections: List<Connection> = emptyList()
    private var permissions: Map<String, SharePermission> = emptyMap()
    private val permissionsToMe = mutableMapOf<String, SharePermission>()
    private val vehicleSharesToMe = mutableMapOf<String, Long>()
    private val memberLocations = mutableMapOf<String, MemberLocation>()
    private var myLocation: MemberLocation? = null
    private var pickup: PickupStatus? = null
    private var vehicleLocations: Map<String, VehicleLocation> = emptyMap()
    private var request: ShareRequest? = null
    private var groups: List<FinderGroup> = emptyList()
    private var destinationShares: Map<String, DestinationShare> = emptyMap()
    private val promptedShares = mutableSetOf<String>()
    private val subscriptions = mutableListOf<Subscription>()
    private val contactSubscriptions = mutableMapOf<String, List<Subscription>>()

    /** 눌러서 공유 설정을 연 사람 */
    private var selectedId: String? = null

    // 화면을 다시 그리면서 선택 상태를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    /** 위치 권한을 받은 뒤 이어서 적용할 선택 */
    private var pendingChoice: Choice? = null

    private data class Choice(val viewerId: String, val permission: SharePermission, val acceptInvite: Connection?)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val choice = pendingChoice
        pendingChoice = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && choice != null) {
            applyChoice(choice)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        mapView = findViewById(CoreR.id.mapView)
        mapView.onCreate(savedInstanceState)
        map = FinderMap(mapView, ResourcesCompat.getFont(this, R.font.asta_sans_family))
        mainScroll = findViewById(R.id.mainScroll)

        findViewById<TextView>(R.id.accountText).text = getString(R.string.account_format, me.name)
        inviteCard = findViewById(R.id.inviteCard)
        inviteTitle = findViewById(R.id.inviteTitle)
        inviteDetail = findViewById(R.id.inviteDetail)
        requestCard = findViewById(R.id.requestCard)
        requestTitle = findViewById(R.id.requestTitle)
        requestDetail = findViewById(R.id.requestDetail)
        pickupCard = findViewById(R.id.pickupCard)
        pickupHeadline = findViewById(R.id.pickupHeadline)
        pickupDetail = findViewById(R.id.pickupDetail)
        pickupMetrics = findViewById(R.id.pickupMetrics)
        remainDistance = findViewById(R.id.remainDistance)
        remainTime = findViewById(R.id.remainTime)
        familyList = findViewById(R.id.familyList)
        friendList = findViewById(R.id.friendList)
        groupList = findViewById(R.id.groupList)
        shareDot = findViewById(R.id.shareDot)
        shareText = findViewById(R.id.shareText)

        header(R.id.familyHeader, SECTION_FAMILY, R.string.section_family, R.string.action_add_family) { showInvite(Relation.FAMILY) }
        header(R.id.friendHeader, SECTION_FRIENDS, R.string.section_friends, R.string.action_add_friend) { showInvite(Relation.FRIEND) }
        header(R.id.groupHeader, SECTION_GROUPS, R.string.section_groups, R.string.action_add_group) { showCreateGroup() }

        findViewById<Button>(R.id.inviteAlwaysButton).setOnClickListener { incomingInvite()?.let { choose(Choice(it.otherId, SharePermission.ALWAYS, it)) } }
        findViewById<Button>(R.id.inviteTemporaryButton).setOnClickListener { incomingInvite()?.let { choose(Choice(it.otherId, SharePermission.TEMPORARY, it)) } }
        findViewById<Button>(R.id.inviteDeclineButton).setOnClickListener {
            incomingInvite()?.let { repository.respondConnection(me.id, it.otherId, it.relation, it.inviterId, accept = false) }
        }
        findViewById<Button>(R.id.acceptRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = true, pending = request)
        }
        findViewById<Button>(R.id.declineRequestButton).setOnClickListener {
            LocationShareService.respondToRequest(this, accept = false, pending = request)
        }
        renderPickup(null)
    }

    /** 패널 제목. 제목을 누르면 목록을 접고 펼친다(처음에는 펼침). */
    private fun header(id: Int, key: String, title: Int, action: Int, onAction: () -> Unit) {
        val view = findViewById<View>(id)
        view.findViewById<TextView>(R.id.sectionTitle).setText(title)
        view.findViewById<View>(R.id.sectionToggle).setOnClickListener {
            if (key in collapsedSections) collapsedSections -= key else collapsedSections += key
            render()
        }
        view.findViewById<TextView>(R.id.sectionAction).apply {
            setText(action)
            setOnClickListener { onAction() }
        }
    }

    private fun renderSection(headerId: Int, key: String, list: View) {
        val expanded = key !in collapsedSections
        list.visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<View>(headerId).findViewById<ImageView>(R.id.sectionChevron).apply {
            rotation = if (expanded) 0f else -90f
            contentDescription = if (expanded) "접기" else "펼치기"
        }
    }

    private fun isFavorite(key: String) = favorites.contains(me.id, key)

    /** 한 패널은 MAX_ROWS 줄까지 보여 주고, 나머지는 '더 보기'로 펼친다. */
    private fun <T> visibleRows(key: String, items: List<T>): List<T> =
        if (key in expandedSections || items.size <= Favorites.MAX_ROWS) items else items.take(Favorites.MAX_ROWS)

    private fun addMoreRow(list: LinearLayout, key: String, total: Int) {
        if (total <= Favorites.MAX_ROWS) return
        val expanded = key in expandedSections
        list.addView(TextView(this).apply {
            text = if (expanded) "접기" else "더 보기 (${total - Favorites.MAX_ROWS})"
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setTextAppearance(CoreR.style.TextAppearance_Pleos_Label)
            setTextColor(ContextCompat.getColor(this@MainActivity, CoreR.color.pleos_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(CoreR.drawable.pleos_bg_row)
            setOnClickListener {
                if (expanded) expandedSections -= key else expandedSections += key
                render()
            }
        })
    }

    private fun isDoubleTap(id: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val double = lastTap?.let { (lastId, at) -> lastId == id && now - at < DOUBLE_TAP_MS } == true
        lastTap = if (double) null else id to now
        return double
    }

    /** 지도를 이 사람(폰 위치, 없으면 운전 중인 차량)에게 가깝게 맞춘다. */
    private fun focusOn(member: Member) {
        val now = System.currentTimeMillis()
        val location = memberLocations[member.id]?.takeIf { sharesWithMe(member.id, now) }?.let { it.latitude to it.longitude }
            ?: visibleDrivingVehicle(member.id, now)?.second?.let { it.latitude to it.longitude }
        if (location == null) {
            Toast.makeText(this, "${member.name}의 위치는 지금 볼 수 없어요.", Toast.LENGTH_SHORT).show()
            return
        }
        mainScroll.smoothScrollTo(0, 0)
        map.focus(location.first, location.second)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        LocationShareService.onStateChanged = ::renderShare
        LocationShareService.appVisible = true
        subscriptions += repository.observeConnections(me.id) {
            connections = it
            syncContactSubscriptions()
            render()
        }
        subscriptions += repository.observePermissions(me.id) {
            permissions = it
            // 앱을 다시 열었을 때 누군가에게 허용 상태인데 서비스가 꺼져 있으면 다시 켠다.
            if (it.values.any { p -> p != SharePermission.OFF }) ensureService()
            render()
        }
        subscriptions += repository.observeShareRequest(me.id) {
            request = it
            render()
        }
        subscriptions += repository.observePickupStatus(me.id, ::renderPickup)
        subscriptions += repository.observeMemberLocation(me.id) {
            myLocation = it
            render()
        }
        subscriptions += repository.observeVehicleLocations {
            vehicleLocations = it
            render()
        }
        subscriptions += repository.observeGroups { all ->
            groups = all.filter { it.memberOf(me.id)?.let { m -> m.status != InviteStatus.DECLINED } == true }
            syncContactSubscriptions()
            if (groups.any { it.memberOf(me.id)?.isSharing() == true }) ensureService()
            render()
            promptDestinations()
        }
        subscriptions += repository.observeDestinationShares {
            destinationShares = it
            promptDestinations()
            render()
        }
        render()
    }

    override fun onStop() {
        LocationShareService.onStateChanged = null
        LocationShareService.appVisible = false
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        contactSubscriptions.values.flatten().forEach { it.cancel() }
        contactSubscriptions.clear()
        mapView.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    private fun ensureService() {
        if (!LocationShareService.isRunning && hasLocationPermission()) LocationShareService.start(this)
    }

    /** 연결된 상대와 함께 있는 그룹 구성원마다, 나에게 정한 허용 방식·차량 공유·위치를 구독한다(목록과 지도 표시용). */
    private fun syncContactSubscriptions() {
        val ids = (connections.map { it.otherId } + groups.flatMap { g -> g.members.map { it.accountId } }).toSet() - me.id
        (contactSubscriptions.keys - ids).forEach { id ->
            contactSubscriptions.remove(id)?.forEach { it.cancel() }
            permissionsToMe.remove(id)
            vehicleSharesToMe.remove(id)
            memberLocations.remove(id)
        }
        (ids - contactSubscriptions.keys).forEach { id ->
            contactSubscriptions[id] = listOf(
                repository.observePermission(id, me.id) {
                    permissionsToMe[id] = it
                    render()
                },
                repository.observeVehicleShares(id) { shares ->
                    val until = shares[me.id]
                    if (until == null) vehicleSharesToMe.remove(id) else vehicleSharesToMe[id] = until
                    render()
                },
                repository.observeMemberLocation(id) {
                    memberLocations[id] = it
                    render()
                },
            )
        }
    }

    private fun incomingInvite(): Connection? = connections.firstOrNull { it.isIncomingInvite }

    private fun person(id: String): Member = DemoDirectory.memberFor(me.id, id)

    private fun choose(choice: Choice) {
        if (choice.permission == SharePermission.OFF || hasLocationPermission()) {
            applyChoice(choice)
            return
        }
        pendingChoice = choice
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun applyChoice(choice: Choice) {
        choice.acceptInvite?.let { repository.respondConnection(me.id, it.otherId, it.relation, it.inviterId, accept = true) }
        repository.setPermission(me.id, choice.viewerId, choice.permission)
        // 모두 꺼짐이면 서비스가 허용 상태 변화를 보고 스스로 멈춘다.
        if (choice.permission != SharePermission.OFF) LocationShareService.start(this)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val invite = incomingInvite()
        inviteCard.visibility = if (invite != null) View.VISIBLE else View.GONE
        if (invite != null) {
            val inviter = person(invite.otherId).name
            val label = if (invite.relation == Relation.FAMILY) "가족" else "친구"
            inviteTitle.text = "${inviter.iGa} ${label.euro} 초대했어요"
            inviteDetail.text = "${inviter}에게 내 위치를 어떻게 공유할까요?"
        }

        val pending = request?.takeIf { it.isPendingFresh() }
        requestCard.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            requestTitle.text = "${pending.requesterName.iGa} 픽업하러 가려고 해요"
            requestDetail.text = "${pending.requesterName}에게 위치를 공유할까요? 픽업이 끝나면 공유도 끝나요."
        }

        renderSection(R.id.familyHeader, SECTION_FAMILY, familyList)
        renderSection(R.id.friendHeader, SECTION_FRIENDS, friendList)
        renderSection(R.id.groupHeader, SECTION_GROUPS, groupList)
        renderPeople(familyList, SECTION_FAMILY, Relation.FAMILY, "가족이 초대하면 여기에 보여요.")
        renderPeople(friendList, SECTION_FRIENDS, Relation.FRIEND, "친구를 추가하면 여기에 보여요.")
        renderGroups()
        renderShare()
        renderMap()
    }

    // ---- 가족 · 친구 ----

    private fun renderPeople(list: LinearLayout, key: String, relation: Relation, emptyMessage: String) {
        list.removeAllViews()
        // 즐겨찾기한 사람이 맨 위에 온다.
        val people = connections.filter { it.relation == relation && it.status != InviteStatus.DECLINED && !it.isIncomingInvite }
            .sortedWith(compareBy({ !isFavorite(Favorites.person(it.otherId)) }, { person(it.otherId).name }))
        if (people.isEmpty()) {
            addEmpty(list, emptyMessage)
            return
        }
        val now = System.currentTimeMillis()
        for (connection in visibleRows(key, people)) {
            val member = person(connection.otherId)
            val permission = permissions[member.id] ?: SharePermission.OFF
            val (status, dot) = if (connection.status == InviteStatus.PENDING) {
                "초대 수락을 기다리는 중" to CoreR.color.pleos_text_quaternary
            } else {
                val mine = "내 위치: " + when (permission) {
                    SharePermission.ALWAYS -> "항상 허용"
                    SharePermission.TEMPORARY -> "일시적 허용"
                    SharePermission.OFF -> "끔"
                }
                val car = visibleDrivingVehicle(member.id, now)?.let { (name, location) -> " · $name 운전 중 ${ago(location.updatedAt)}" } ?: ""
                (mine + car) to when (permission) {
                    SharePermission.ALWAYS -> CoreR.color.pleos_informative_active
                    SharePermission.TEMPORARY -> CoreR.color.pleos_informative_positive
                    SharePermission.OFF -> CoreR.color.pleos_text_quaternary
                }
            }
            val row = Row.add(list).bind(
                member.name, member.name.take(1),
                DemoDirectory.hasFinderPhone(member.id), DemoDirectory.vehiclesDrivenBy(member.id).isNotEmpty(),
                status, dot,
            )
            val favoriteKey = Favorites.person(member.id)
            row.favorite(isFavorite(favoriteKey))
            val selected = selectedId == member.id && connection.status == InviteStatus.ACCEPTED
            row.view.isActivated = selected
            row.view.setOnClickListener {
                if (isDoubleTap(member.id)) {
                    focusOn(member)
                    return@setOnClickListener
                }
                selectedId = if (selected) null else member.id
                render()
            }
            if (selected) {
                row.favoriteButton(isFavorite(favoriteKey)) {
                    favorites.toggle(me.id, favoriteKey)
                    render()
                }
                list.addView(permissionEditor(member, permission))
            }
        }
        addMoreRow(list, key, people.size)
    }

    /** 상대가 항상 허용했거나, 함께 참여한 그룹에 공유 중이면 상대 위치를 볼 수 있다. */
    private fun sharesWithMe(id: String, now: Long): Boolean =
        permissionsToMe[id] == SharePermission.ALWAYS ||
            groups.any { g -> g.memberOf(me.id)?.status == InviteStatus.ACCEPTED && g.memberOf(id)?.isSharing(now) == true }

    /** 상대가 지금 운전 중이고 나에게 보여 주는 차량(이름, 위치). 차량 위치는 운전자의 설정을 따른다. */
    private fun visibleDrivingVehicle(driverId: String, now: Long): Pair<String, VehicleLocation>? {
        val visible = sharesWithMe(driverId, now) || (vehicleSharesToMe[driverId] ?: 0L) > now
        if (!visible) return null
        return DemoDirectory.vehiclesDrivenBy(driverId).firstNotNullOfOrNull { v ->
            vehicleLocations[v.id]?.takeIf { it.driverId == driverId }?.let { v.name to it }
        }
    }

    /** '○○에게 내 위치' 항상 / 일시적 / 끄기 */
    private fun permissionEditor(viewer: Member, permission: SharePermission): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_permission, null, false)
        view.setPadding(dp(8), 0, dp(8), dp(12))
        view.findViewById<TextView>(R.id.permissionTitle).text = "${viewer.name}에게 내 위치"
        val group = view.findViewById<RadioGroup>(R.id.permissionGroup)
        rendering = true
        group.check(
            when (permission) {
                SharePermission.ALWAYS -> R.id.permissionAlways
                SharePermission.TEMPORARY -> R.id.permissionTemporary
                SharePermission.OFF -> R.id.permissionOff
            }
        )
        rendering = false
        view.findViewById<TextView>(R.id.permissionDetail).setText(
            when (permission) {
                SharePermission.ALWAYS -> R.string.permission_always_detail
                SharePermission.TEMPORARY -> R.string.permission_temporary_detail
                SharePermission.OFF -> R.string.permission_off_detail
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val value = when (checkedId) {
                R.id.permissionAlways -> SharePermission.ALWAYS
                R.id.permissionTemporary -> SharePermission.TEMPORARY
                else -> SharePermission.OFF
            }
            if (value != permission) choose(Choice(viewer.id, value, null))
        }
        return view
    }

    // ---- 그룹 ----

    /** 받은 그룹 초대(참여·거절)와 참여한 그룹(누르면 상세) */
    private fun renderGroups() {
        groupList.removeAllViews()
        if (groups.isEmpty()) {
            addEmpty(groupList, "아직 참여한 그룹이 없어요.")
            return
        }
        val now = System.currentTimeMillis()
        // 받은 초대가 먼저, 그다음 즐겨찾기한 그룹
        val sorted = groups.sortedWith(compareBy({ it.memberOf(me.id)?.status != InviteStatus.PENDING }, { !isFavorite(Favorites.group(it.id)) }))
        for (group in visibleRows(SECTION_GROUPS, sorted)) {
            val mine = group.memberOf(me.id) ?: continue
            val joined = group.members.count { it.status == InviteStatus.ACCEPTED }
            val owner = person(group.ownerId).name
            if (mine.status == InviteStatus.PENDING) {
                val row = Row.add(groupList).bind(group.name, "", false, false, "${owner.iGa} 초대했어요 · 구성원 ${joined}명", CoreR.color.pleos_informative_positive)
                row.groupIcon()
                row.addAction(getString(R.string.action_join_group), filled = true) {
                    repository.setGroupMember(group.id, mine.copy(status = InviteStatus.ACCEPTED))
                }
                row.addAction(getString(R.string.action_decline_invite), filled = false) {
                    repository.setGroupMember(group.id, mine.copy(status = InviteStatus.DECLINED))
                }
            } else {
                val sharing = group.members.count { it.isSharing(now) }
                val row = Row.add(groupList).bind(
                    group.name, "", false, false,
                    "구성원 ${joined}명 · ${sharing}명 공유 중 · 나: ${mine.shareText(now)}",
                    if (mine.isSharing(now)) CoreR.color.pleos_informative_active else CoreR.color.pleos_text_quaternary,
                    chevron = true,
                )
                row.groupIcon()
                row.favorite(isFavorite(Favorites.group(group.id)))
                row.view.setOnClickListener { GroupActivity.open(this, group.id) }
            }
        }
        addMoreRow(groupList, SECTION_GROUPS, sorted.size)
    }

    private fun Row.groupIcon() {
        view.findViewById<TextView>(R.id.avatar).apply {
            foreground = ContextCompat.getDrawable(this@MainActivity, CoreR.drawable.pleos_ic_group)
            foregroundGravity = Gravity.CENTER
        }
    }

    // ---- 그룹 목적지 공유 ----

    /** 참여한 그룹에 새로 공유된 목적지로 갈지 한 번씩 묻는다. */
    private fun promptDestinations() {
        for (share in destinationShares.values) {
            val group = groups.firstOrNull { it.id == share.groupId && it.memberOf(me.id)?.status == InviteStatus.ACCEPTED } ?: continue
            if (!share.awaits(me.id) || !share.isPromptable() || !promptedShares.add(share.id)) continue
            NotificationManagerCompat.from(this).cancel(Directions.notificationId(share))
            DestinationPromptDialog.show(
                this, fromName = person(share.fromId).name, groupName = group.name, share = share,
                acceptLabel = "길 안내", question = "이 목적지로 길 안내를 시작할까요?",
                onAccept = { respondDestination(share, accept = true) }, onDecline = { respondDestination(share, accept = false) },
            )
        }
    }

    private fun respondDestination(share: DestinationShare, accept: Boolean) {
        repository.respondDestination(share.groupId, share.id, me.id, accept)
        NotificationManagerCompat.from(this).cancel(Directions.notificationId(share))
        if (accept) Directions.open(this, share)
    }

    // ---- 추가 · 만들기 ----

    private fun showInvite(relation: Relation) {
        val linked = connections.filter { it.status != InviteStatus.DECLINED }.map { it.otherId }.toSet()
        val candidates = DemoDirectory.accounts.map { person(it.member.id) }.filter { it.id != me.id && it.id !in linked }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "연락처의 모든 사람과 이미 연결되어 있어요.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (relation == Relation.FAMILY) "연락처에서 가족 초대" else "연락처에서 친구 추가")
            .setItems(candidates.map { "${it.name} · ${DemoDirectory.account(it.id)?.note.orEmpty()}" }.toTypedArray()) { _, which ->
                repository.inviteConnection(me.id, candidates[which].id, relation)
                Toast.makeText(this, "${candidates[which].name}에게 초대를 보냈어요.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCreateGroup() {
        PleosTextInputDialog.show(
            this, title = "그룹 만들기", label = "그룹 이름", hint = "예: 주말 캠핑", initial = "", confirmLabel = "만들기",
        ) { name -> GroupActivity.open(this, repository.createGroup(name, me.id)) }
    }

    // ---- 상태 ----

    private fun renderShare() {
        val last = LocationShareService.lastShared
        val (text, dot) = when {
            !LocationShareService.isRunning -> getString(R.string.share_off) to CoreR.color.pleos_text_quaternary
            !LocationShareService.isSharing -> getString(R.string.share_standby) to CoreR.color.pleos_informative_positive
            last == null -> "위치 공유 중 · 위치 확인 중" to CoreR.color.pleos_informative_active
            else -> "위치 공유 중 · 마지막 전송 ${TIME_FORMAT.format(Date(last.updatedAt))}" to CoreR.color.pleos_informative_active
        }
        shareText.text = text
        shareDot.backgroundTintList = colorList(dot)
    }

    /** 누군가 픽업하러 오는 동안만 픽업 카드를 보여 준다. */
    private fun renderPickup(status: PickupStatus?) {
        val current = status?.takeIf { !it.isStale() && it.state != PickupStatus.State.CANCELLED }
        pickup = current
        if (::mapView.isInitialized) renderMap()
        pickupCard.visibility = if (current != null) View.VISIBLE else View.GONE
        pickupMetrics.visibility = if (current?.state == PickupStatus.State.ON_THE_WAY) View.VISIBLE else View.GONE
        when (current?.state) {
            PickupStatus.State.ON_THE_WAY -> {
                pickupHeadline.text = "${current.driverName.iGa} 오고 있어요"
                pickupDetail.text = "내 위치가 바뀌면 길 안내도 따라 바뀌어요."
                val distance = current.remainDistanceMeters
                val time = current.remainTimeSeconds
                remainDistance.text = if (distance == null) "–" else "%.1fkm".format(distance / 1000.0)
                remainTime.text = if (time == null) "–" else "${(time + 59) / 60}분"
            }
            PickupStatus.State.ARRIVED -> {
                pickupHeadline.text = "${current.driverName.iGa} 도착했어요"
                pickupDetail.text = "차가 근처에 있어요."
            }
            else -> Unit
        }
    }

    // ---- 지도 ----

    /** 나, 나에게 위치를 보여 주는 사람, 운전 중인 차량. 픽업 중이면 오는 차와 나를 선으로 잇는다. */
    private fun renderMap() {
        val now = System.currentTimeMillis()
        // 참여한 그룹에 공유된 목적지
        val destinations = destinationShares.values.filter { share ->
            share.isActive(now) && groups.any { it.id == share.groupId && it.memberOf(me.id)?.status == InviteStatus.ACCEPTED }
        }
        val markers = mutableListOf<MapMarker>()
        // 겹치면 뒤에 넣은 것이 위에 그려진다: 목적지 → 차량 → 사람 → 나
        for (share in destinations) {
            markers += MapMarker("dest:${share.groupId}", share.name, MapMarker.Kind.DESTINATION, share.latitude, share.longitude)
        }
        val others = contactSubscriptions.keys
        for (id in others) {
            val (vehicleName, location) = visibleDrivingVehicle(id, now) ?: continue
            // 그룹 목적지로 가는 차량은 남은 시간을 함께 보여 준다(예: "아빠 · 아이오닉5 · 12분").
            val eta = destinations.progressOf(id, now)?.let { " · ${it.shortText(now)}" } ?: ""
            markers += MapMarker("car:${location.vehicleId}", "${person(id).name} · $vehicleName$eta", MapMarker.Kind.CAR, location.latitude, location.longitude)
        }
        for (id in others) {
            if (!sharesWithMe(id, now)) continue
            val location = memberLocations[id] ?: continue
            markers += MapMarker(id, person(id).name, MapMarker.Kind.PERSON, location.latitude, location.longitude)
        }
        val current = pickup
        val mine = myLocation
        val vehicleLat = current?.vehicleLatitude
        val vehicleLng = current?.vehicleLongitude
        val route = if (current?.state == PickupStatus.State.ON_THE_WAY && mine != null && vehicleLat != null && vehicleLng != null) {
            if (markers.none { it.kind == MapMarker.Kind.CAR }) {
                markers += MapMarker("car:pickup", current.driverName, MapMarker.Kind.CAR, vehicleLat, vehicleLng)
            }
            listOf(
                MapMarker("from", "", MapMarker.Kind.CAR, vehicleLat, vehicleLng),
                MapMarker("to", "", MapMarker.Kind.ME, mine.latitude, mine.longitude),
            )
        } else {
            // 길 안내를 수락한 그룹 목적지가 있으면 나와 목적지를 잇는다.
            val going = destinations.firstOrNull { it.responses[me.id] == true }
            if (going != null && mine != null) {
                listOf(
                    MapMarker("from", "", MapMarker.Kind.ME, mine.latitude, mine.longitude),
                    MapMarker("to", "", MapMarker.Kind.DESTINATION, going.latitude, going.longitude),
                )
            } else null
        }
        // 나는 맨 위에 보이도록 마지막에 넣는다.
        myLocation?.let { markers += MapMarker(me.id, "나", MapMarker.Kind.ME, it.latitude, it.longitude) }
        map.show(markers, route)
    }

    private fun addEmpty(list: LinearLayout, message: String) {
        list.addView(TextView(this).apply {
            text = message
            setTextAppearance(CoreR.style.TextAppearance_Pleos_BodySmall)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        })
    }

    private fun ago(at: Long): String {
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 60 -> "방금"
            seconds < 3600 -> "${seconds / 60}분 전"
            else -> "${seconds / 3600}시간 전"
        }
    }

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        const val DOUBLE_TAP_MS = 400L
        const val SECTION_FAMILY = "family"
        const val SECTION_FRIENDS = "friends"
        const val SECTION_GROUPS = "groups"
    }
}
