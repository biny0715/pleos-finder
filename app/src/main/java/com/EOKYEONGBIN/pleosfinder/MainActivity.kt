package com.EOKYEONGBIN.pleosfinder

import android.content.res.ColorStateList
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ScrollView
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare
import com.EOKYEONGBIN.pleosfinder.core.euro
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.gwaWa
import com.EOKYEONGBIN.pleosfinder.core.iGa
import com.EOKYEONGBIN.pleosfinder.core.progressOf
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ui.DestinationPromptDialog
import com.EOKYEONGBIN.pleosfinder.core.ui.Favorites
import com.EOKYEONGBIN.pleosfinder.core.ui.FinderMap
import com.EOKYEONGBIN.pleosfinder.core.ui.MapMarker
import com.EOKYEONGBIN.pleosfinder.core.ui.PleosTextInputDialog
import com.EOKYEONGBIN.pleosfinder.core.ui.PleosToast
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {

    private val session get() = (application as PleosFinderApp).session
    private val inflater get() = LayoutInflater.from(this)

    private lateinit var toast: PleosToast
    private lateinit var profileAvatar: TextView
    private lateinit var profileName: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var mapView: MapView
    private lateinit var map: FinderMap
    private lateinit var listScroll: ScrollView
    private val favorites by lazy { Favorites(this) }

    /** 접어 둔 패널과 '더 보기'로 모두 펼친 패널 */
    private val collapsedSections = mutableSetOf<String>()
    private val expandedSections = mutableSetOf<String>()

    /** 같은 줄을 두 번 누르면 지도에서 그 사람을 가깝게 보여 준다. */
    private var lastTap: Pair<String, Long>? = null

    private lateinit var pickupStrip: View
    private lateinit var contentRow: ViewGroup
    private lateinit var pickupHeadline: TextView
    private lateinit var pickupDetail: TextView
    private lateinit var pickupProgress: View
    private lateinit var pickupMetrics: View
    private lateinit var remainDistance: TextView
    private lateinit var remainTime: TextView
    private lateinit var pickupActionButton: Button

    private lateinit var groupPanel: View
    private lateinit var groupName: TextView
    private lateinit var groupMembers: LinearLayout
    private lateinit var groupInviteBox: View
    private lateinit var groupShareBox: View
    private lateinit var groupShareTitle: TextView
    private lateinit var groupShareGroup: RadioGroup
    private lateinit var groupShareDetail: TextView
    private lateinit var groupExtendButton: Button

    /** 선택한 목록 항목(상대 id). 선택한 줄의 오른쪽에 버튼이 나타난다. */
    private var selectedId: String? = null

    /** 오른쪽에 열린 그룹 */
    private var openGroupId: String? = null

    // 화면을 다시 그리면서 세그먼트를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        mapView = findViewById(CoreR.id.mapView)
        mapView.onCreate(savedInstanceState)
        map = FinderMap(mapView)
        listScroll = findViewById(R.id.listScroll)

        toast = PleosToast(findViewById(R.id.toast))
        profileAvatar = findViewById(R.id.profileAvatar)
        profileName = findViewById(R.id.profileName)
        listContainer = findViewById(R.id.listContainer)
        pickupStrip = findViewById(R.id.pickupStrip)
        contentRow = findViewById(R.id.contentRow)
        pickupHeadline = findViewById(R.id.pickupHeadline)
        pickupDetail = findViewById(R.id.pickupDetail)
        pickupProgress = findViewById(R.id.pickupProgress)
        pickupMetrics = findViewById(R.id.pickupMetrics)
        remainDistance = findViewById(R.id.remainDistance)
        remainTime = findViewById(R.id.remainTime)
        pickupActionButton = findViewById(R.id.pickupActionButton)
        groupPanel = findViewById(R.id.groupPanel)
        groupName = findViewById(R.id.groupName)
        groupMembers = findViewById(R.id.groupMembers)
        groupInviteBox = findViewById(R.id.groupInviteBox)
        groupShareBox = findViewById(R.id.groupShareBox)
        groupShareTitle = findViewById(R.id.groupShareTitle)
        groupShareGroup = findViewById(R.id.groupShareGroup)
        groupShareDetail = findViewById(R.id.groupShareDetail)
        groupExtendButton = findViewById(R.id.groupExtendButton)

        findViewById<View>(R.id.profileChip).setOnClickListener { showProfileMenu() }
        pickupActionButton.setOnClickListener { session.stopPickup() }
        findViewById<Button>(R.id.groupRenameButton).setOnClickListener { openGroup()?.let(::showRenameGroup) }
        findViewById<Button>(R.id.groupCloseButton).setOnClickListener {
            openGroupId = null
            render()
        }
        findViewById<Button>(R.id.groupAcceptButton).setOnClickListener { openGroup()?.let { session.respondGroupInvite(it, accept = true) } }
        findViewById<Button>(R.id.groupDeclineButton).setOnClickListener {
            openGroup()?.let { session.respondGroupInvite(it, accept = false) }
            openGroupId = null
        }
        findViewById<Button>(R.id.groupInviteButton).setOnClickListener { openGroup()?.let(::showGroupInvite) }
        findViewById<Button>(R.id.groupDestinationShareButton).setOnClickListener { openGroup()?.let(::showShareDestination) }
        findViewById<Button>(R.id.groupDestinationGoButton).setOnClickListener {
            openGroup()?.let(session::destinationOf)?.let(session::acceptDestination)
        }
        findViewById<Button>(R.id.groupDestinationClearButton).setOnClickListener { openGroup()?.let(session::clearDestination) }
        // 그룹 목적지 공유를 알림으로도 받는다.
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        groupExtendButton.setOnClickListener { openGroup()?.let(session::extendGroupShare) }
        groupShareGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val group = openGroup() ?: return@setOnCheckedChangeListener
            val share = when (checkedId) {
                R.id.groupShareAlways -> SharePermission.ALWAYS
                R.id.groupShareTemporary -> SharePermission.TEMPORARY
                else -> SharePermission.OFF
            }
            session.setGroupShare(group, share)
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        session.onChanged = ::render
        session.onMessage = toast::show
        session.onLocationsChanged = ::renderMap
        session.onDestinationShared = ::showDestinationPrompt
        render()
    }

    override fun onStop() {
        session.onChanged = null
        session.onMessage = null
        session.onLocationsChanged = null
        session.onDestinationShared = null
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

    private fun render() {
        profileAvatar.text = session.me.name.take(1)
        profileName.text = "${session.me.name} · ${session.vehicle.name} 운전 중"
        renderList()
        renderPickup()
        renderMap()
        val group = openGroup()
        if (group == null) openGroupId = null
        val split = group != null
        // 내용을 먼저 채워야 열릴 때 빈 패널이 늘어나며 줄이 하나씩 튀어나오지 않는다.
        if (group != null) renderGroup(group)
        if (split != (groupPanel.visibility == View.VISIBLE)) {
            // 한 칸 ↔ 두 칸 전환 (ivi_motion_duration_medium1 = 250ms)
            groupPanel.animate().cancel()
            if (split) {
                // 다 채운 패널이 오른쪽에서 살짝 밀려 들어오며 나타난다.
                groupPanel.alpha = 0f
                groupPanel.translationX = dp(64).toFloat()
                groupPanel.visibility = View.VISIBLE
                groupPanel.animate().alpha(1f).translationX(0f)
                    .setDuration(250).setInterpolator(DecelerateInterpolator()).start()
            } else {
                groupPanel.alpha = 1f
                groupPanel.translationX = 0f
                TransitionManager.beginDelayedTransition(contentRow, AutoTransition().setDuration(250))
                groupPanel.visibility = View.GONE
            }
        }
    }

    /** 내 차, 나에게 위치를 보여 주는 사람과 차량. 픽업 중이면 내 차와 상대를 선으로 잇는다. */
    private fun renderMap() {
        val now = System.currentTimeMillis()
        val destinations = session.activeDestinations()
        // 그룹 목적지로 가는 차량은 남은 시간을 함께 보여 준다(예: "김민수 · GV70 · 12분").
        fun eta(driverId: String) = destinations.progressOf(driverId, now)?.let { " · ${it.shortText(now)}" } ?: ""

        // 겹치면 뒤에 넣은 것이 위에 그려진다: 목적지 → 다른 차량 → 사람 → 내 차
        val markers = mutableListOf<MapMarker>()
        for (share in destinations) {
            markers += MapMarker("dest:${share.groupId}", share.name, MapMarker.Kind.DESTINATION, share.latitude, share.longitude)
        }
        for (vehicle in DemoDirectory.vehicles) {
            if (vehicle.id == session.vehicle.id) continue
            val location = session.visibleVehicleLocation(vehicle) ?: continue
            markers += MapMarker(
                "car:${vehicle.id}", "${session.person(location.driverId).name} · ${vehicle.name}${eta(location.driverId)}",
                MapMarker.Kind.CAR, location.latitude, location.longitude,
            )
        }
        for ((member, location) in session.visiblePeopleLocations()) {
            markers += MapMarker(member.id, member.name, MapMarker.Kind.PERSON, location.latitude, location.longitude)
        }
        // 내 차는 맨 위에 보이도록 마지막에 넣는다.
        val mine = session.myPosition
        if (mine != null) markers += MapMarker("me", "내 차${eta(session.me.id)}", MapMarker.Kind.MY_CAR, mine.first, mine.second)

        // 픽업 중이면 기다리는 사람까지, 그룹 목적지로 가는 중이면 목적지까지 점선을 긋는다.
        val target = (session.pickupState as? PickupState.OnTheWay)?.targetLocation?.let { it.latitude to it.longitude }
            ?: session.followingDestination()?.let { it.latitude to it.longitude }
        val route = if (mine != null && target != null) {
            listOf(
                MapMarker("from", "", MapMarker.Kind.MY_CAR, mine.first, mine.second),
                MapMarker("to", "", MapMarker.Kind.DESTINATION, target.first, target.second),
            )
        } else null
        map.show(markers, route)
    }

    private fun renderGroupDestination(share: DestinationShare?, pending: Boolean) {
        val box = findViewById<View>(R.id.groupDestinationBox)
        box.visibility = if (share != null && !pending) View.VISIBLE else View.GONE
        if (share == null || pending) return
        val mine = share.fromId == session.me.id
        val following = session.isFollowing(share)
        val going = share.responses.count { it.value }
        findViewById<TextView>(R.id.groupDestinationTitle).text =
            if (mine) "내가 공유한 목적지" else "${session.person(share.fromId).name.iGa} 공유한 목적지"
        findViewById<TextView>(R.id.groupDestinationName).text = share.name
        findViewById<TextView>(R.id.groupDestinationDetail).text =
            listOfNotNull(share.address.ifBlank { null }, "${going}명 함께 가는 중", if (following) "안내 중" else null).joinToString(" · ")
        findViewById<View>(R.id.groupDestinationGoButton).visibility = if (following) View.GONE else View.VISIBLE
        findViewById<View>(R.id.groupDestinationClearButton).visibility = if (mine) View.VISIBLE else View.GONE
    }

    /** 공유할 목적지 고르기: 지금 안내 중인 목적지, 최근 목적지, 지금 내 차 위치 */
    private fun showShareDestination(group: FinderGroup) {
        session.loadSharePlaces { places ->
            if (isFinishing) return@loadSharePlaces
            if (places.isEmpty()) {
                toast.show("공유할 목적지가 없어요. 내비에서 목적지를 정한 뒤 다시 시도해 주세요.")
                return@loadSharePlaces
            }
            val labels = places.map {
                when (it.kind) {
                    SharePlace.Kind.CURRENT -> "지금 안내 중 · ${it.name}"
                    SharePlace.Kind.RECENT -> "최근 목적지 · ${it.name}"
                    SharePlace.Kind.HERE -> "지금 내 차 위치 (여기로 모이기)"
                }
            }
            AlertDialog.Builder(this)
                .setTitle("'${group.name}' 그룹에 공유할 목적지")
                .setItems(labels.toTypedArray()) { _, which -> session.shareDestination(group, places[which]) }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    /** 같은 그룹 구성원이 공유한 목적지로 갈지 묻는다. */
    private fun showDestinationPrompt(group: FinderGroup, share: DestinationShare) {
        if (isFinishing) return
        DestinationPromptDialog.show(
            this, fromName = session.person(share.fromId).name, groupName = group.name, share = share,
            acceptLabel = getString(R.string.action_start_navigation), question = "이 목적지로 안내를 시작할까요?",
            onAccept = { session.acceptDestination(share) }, onDecline = { session.declineDestination(share) },
        )
    }

    private fun openGroup(): FinderGroup? = openGroupId?.let { id -> session.myGroups().firstOrNull { it.id == id } }

    // ---- 왼쪽 목록 ----

    private fun renderList() {
        listContainer.removeAllViews()
        if (addSection(SECTION_FAMILY, getString(R.string.section_family), getString(R.string.action_add_family)) { showAddContact(Relation.FAMILY) }) {
            addContacts(SECTION_FAMILY, Relation.FAMILY, "가족을 초대하면 여기에 보여요.")
        }
        if (addSection(SECTION_FRIENDS, getString(R.string.section_friends), getString(R.string.action_add_friend)) { showAddContact(Relation.FRIEND) }) {
            addContacts(SECTION_FRIENDS, Relation.FRIEND, "친구나 직장 동료의 폰·차량을 추가해 보세요.")
        }
        if (addSection(SECTION_GROUPS, getString(R.string.section_groups), getString(R.string.action_add_group)) { showCreateGroup() }) {
            addGroups()
        }
    }

    /** 패널 제목. 제목을 누르면 목록을 접고 펼친다. 펼쳐져 있으면 true */
    private fun addSection(key: String, title: String, action: String, onAction: () -> Unit): Boolean {
        val header = inflater.inflate(R.layout.item_section_header, listContainer, false)
        val expanded = key !in collapsedSections
        header.findViewById<TextView>(R.id.sectionTitle).text = title
        header.findViewById<ImageView>(R.id.sectionChevron).apply {
            rotation = if (expanded) 0f else -90f
            contentDescription = if (expanded) "접기" else "펼치기"
        }
        header.findViewById<View>(R.id.sectionToggle).setOnClickListener {
            if (expanded) collapsedSections += key else collapsedSections -= key
            render()
        }
        header.findViewById<TextView>(R.id.sectionAction).apply {
            text = action
            setOnClickListener { onAction() }
        }
        listContainer.addView(header)
        return expanded
    }

    private fun isFavorite(key: String) = favorites.contains(session.me.id, key)

    /** 한 패널은 MAX_ROWS 줄까지 보여 주고, 나머지는 '더 보기'로 펼친다. */
    private fun <T> visibleRows(key: String, items: List<T>): List<T> =
        if (key in expandedSections || items.size <= Favorites.MAX_ROWS) items else items.take(Favorites.MAX_ROWS)

    private fun addMoreRow(card: LinearLayout, key: String, total: Int) {
        if (total <= Favorites.MAX_ROWS) return
        val expanded = key in expandedSections
        card.addView(TextView(this).apply {
            text = if (expanded) "접기" else "더 보기 (${total - Favorites.MAX_ROWS})"
            gravity = android.view.Gravity.CENTER
            minHeight = dp(80)
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
        val location = session.visiblePeopleLocations().firstOrNull { it.first.id == member.id }?.second?.let { it.latitude to it.longitude }
            ?: session.drivingVehicleOf(member)?.second?.let { it.latitude to it.longitude }
        if (location == null) {
            toast.show("${member.name}의 위치는 지금 볼 수 없어요.")
            return
        }
        listScroll.smoothScrollTo(0, 0)
        map.focus(location.first, location.second)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(CoreR.drawable.pleos_bg_card)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        listContainer.addView(this)
    }

    private fun addEmpty(card: LinearLayout, message: String) {
        card.addView(TextView(this).apply {
            text = message
            setTextAppearance(CoreR.style.TextAppearance_Pleos_BodySmall)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        })
    }

    private fun addContacts(key: String, relation: Relation, emptyMessage: String) {
        val card = card()
        // 즐겨찾기한 사람이 맨 위에 온다.
        val contacts = session.contacts(relation).sortedByDescending { isFavorite(Favorites.person(it.member.id)) }
        if (contacts.isEmpty()) addEmpty(card, emptyMessage)
        for (contact in visibleRows(key, contacts)) {
            val row = contactRow(card, contact.member.name, contact.hasFinderPhone, contact.vehicles.isNotEmpty())
            row.favorite(isFavorite(Favorites.person(contact.member.id)))
            val (status, dot) = contactStatus(contact)
            row.status(status, dot)
            val selected = selectedId == contact.member.id
            row.view.isActivated = selected
            row.view.setOnClickListener {
                if (isDoubleTap(contact.member.id)) {
                    focusOn(contact.member)
                    return@setOnClickListener
                }
                selectedId = if (selected) null else contact.member.id
                render()
            }
            if (selected) addContactActions(row.actions, contact)
        }
        addMoreRow(card, key, contacts.size)
    }

    private fun contactStatus(contact: Contact): Pair<String, Int> {
        val state = session.pickupState
        val shareUntil = session.myVehicleShareUntil(contact.member)
        val carText = (session.drivingVehicleOf(contact.member)?.let { (v, location) -> " · ${v.name} 운전 중 ${ago(location.updatedAt)}" } ?: "") +
            (shareUntil?.let { " · 차량 위치 서로 공유 ~${TIME.format(Date(it))}" } ?: "")
        return when {
            contact.connection.isIncomingInvite -> "초대를 받았어요" to CoreR.color.pleos_informative_positive
            !contact.isAccepted -> "초대 수락을 기다리는 중" to CoreR.color.pleos_text_quaternary
            state is PickupState.OnTheWay && state.target == contact.member -> "픽업하러 가는 중" to CoreR.color.pleos_informative_active
            state is PickupState.WaitingForAcceptance && state.target == contact.member -> "위치 공유 수락을 기다리는 중" to CoreR.color.pleos_informative_positive
            else -> when (contact.permission) {
                SharePermission.ALWAYS -> "항상 허용$carText" to CoreR.color.pleos_informative_active
                SharePermission.TEMPORARY -> "일시적 허용 (픽업 때 요청)$carText" to CoreR.color.pleos_informative_positive
                SharePermission.OFF -> (if (carText.isEmpty()) "위치 공유 꺼짐" else "차량만 공유$carText") to CoreR.color.pleos_text_quaternary
            }
        }
    }

    private fun addContactActions(actions: LinearLayout, contact: Contact) {
        actions.visibility = View.VISIBLE
        if (contact.connection.isIncomingInvite) {
            actions.addAction(getString(R.string.action_accept), filled = true) { session.respondInvite(contact, accept = true) }
            actions.addAction(getString(R.string.action_decline), filled = false) { session.respondInvite(contact, accept = false) }
            return
        }
        if (!contact.isAccepted) {
            actions.addAction("초대 수락을 기다리는 중", filled = false, enabled = false) {}
            return
        }
        val state = session.pickupState
        val pickingUpThis = (state is PickupState.OnTheWay && state.target == contact.member) ||
            (state is PickupState.WaitingForAcceptance && state.target == contact.member)
        if (contact.hasFinderPhone) {
            if (pickingUpThis) {
                actions.addAction(getString(R.string.action_stop_pickup), filled = false) { session.stopPickup() }
            } else {
                actions.addAction(getString(R.string.action_pickup), filled = true, enabled = session.naviConnected && !session.isPickupBusy) {
                    session.startPickup(contact.member)
                }
            }
        }
        if (contact.vehicles.isNotEmpty()) {
            val until = session.myVehicleShareUntil(contact.member)
            val label = if (until == null) getString(R.string.action_share_vehicle) else "공유 중 ~${TIME.format(Date(until))}"
            actions.addAction(label, filled = !contact.hasFinderPhone && until == null) { showVehicleShare(contact.member) }
        }
        if (actions.childCount == 0) actions.addAction("공유된 위치가 없어요", filled = false, enabled = false) {}
        actions.addFavorite(Favorites.person(contact.member.id))
    }

    /** 즐겨찾기 별 버튼. 즐겨찾기한 항목은 패널 맨 위로 올라간다. */
    private fun LinearLayout.addFavorite(key: String) {
        val on = isFavorite(key)
        val button = inflater.inflate(CoreR.layout.pleos_action_icon, this, false) as ImageButton
        button.setImageResource(if (on) CoreR.drawable.pleos_ic_star else CoreR.drawable.pleos_ic_star_outline)
        button.contentDescription = if (on) "즐겨찾기 해제" else "즐겨찾기"
        button.setOnClickListener {
            favorites.toggle(session.me.id, key)
            render()
        }
        if (childCount > 0) (button.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(24)
        visibility = View.VISIBLE
        addView(button)
    }

    private fun addGroups() {
        val card = card()
        val groups = session.myGroups().sortedByDescending { isFavorite(Favorites.group(it.id)) }
        if (groups.isEmpty()) addEmpty(card, "그룹을 만들어 여러 사람·차량과 함께 위치를 나눠 보세요.")
        val now = System.currentTimeMillis()
        for (group in visibleRows(SECTION_GROUPS, groups)) {
            val row = contactRow(card, group.name, showPerson = false, showCar = false)
            row.favorite(isFavorite(Favorites.group(group.id)))
            row.avatar.text = ""
            row.avatar.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            row.avatar.foreground = ContextCompat.getDrawable(this, CoreR.drawable.pleos_ic_group)
            row.avatar.foregroundGravity = android.view.Gravity.CENTER
            val mine = group.memberOf(session.me.id)
            val joined = group.members.count { it.status == InviteStatus.ACCEPTED }
            val sharing = group.members.count { it.isSharing(now) }
            if (mine?.status == InviteStatus.PENDING) {
                row.status("그룹 초대를 받았어요", CoreR.color.pleos_informative_positive)
            } else {
                val myShare = mine?.shareText(now) ?: ""
                row.status("구성원 ${joined}명 · ${sharing}명 공유 중 · 나: $myShare", if (mine?.isSharing(now) == true) CoreR.color.pleos_informative_active else CoreR.color.pleos_text_quaternary)
            }
            row.view.isActivated = openGroupId == group.id
            row.view.setOnClickListener {
                openGroupId = group.id
                render()
            }
            // 연 그룹은 오른쪽에 즐겨찾기 별을 보여 준다.
            if (openGroupId == group.id) row.actions.addFavorite(Favorites.group(group.id))
        }
        addMoreRow(card, SECTION_GROUPS, groups.size)
    }

    private class Row(val view: View, val avatar: TextView, val statusView: TextView, val dot: View, val actions: LinearLayout)

    private fun contactRow(parent: LinearLayout, name: String, showPerson: Boolean, showCar: Boolean): Row {
        val view = inflater.inflate(R.layout.item_contact, parent, false)
        parent.addView(view)
        val avatar = view.findViewById<TextView>(R.id.avatar).apply { text = name.take(1) }
        view.findViewById<TextView>(R.id.name).text = name
        view.findViewById<ImageView>(R.id.personIcon).visibility = if (showPerson) View.VISIBLE else View.GONE
        view.findViewById<ImageView>(R.id.carIcon).visibility = if (showCar) View.VISIBLE else View.GONE
        return Row(view, avatar, view.findViewById(R.id.status), view.findViewById(R.id.dot), view.findViewById(R.id.actions))
    }

    private fun Row.favorite(on: Boolean) {
        view.findViewById<View>(R.id.favoriteIcon).visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun Row.status(text: String, @ColorRes dotColor: Int) {
        statusView.text = text
        dot.backgroundTintList = colorList(dotColor)
    }

    private fun LinearLayout.addAction(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        val button = inflater.inflate(if (filled) R.layout.item_action_filled else R.layout.item_action_basic, this, false) as Button
        button.text = label
        button.isEnabled = enabled
        button.setOnClickListener { onClick() }
        if (childCount > 0) (button.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(24)
        addView(button)
    }

    // ---- 오른쪽: 픽업 ----

    private fun renderPickup() {
        val state = session.pickupState
        // 픽업 줄은 픽업을 기다리거나 가는 중일 때만 보인다. 완료는 토스트로 알린다.
        val visible = state is PickupState.WaitingForAcceptance || state is PickupState.OnTheWay
        pickupStrip.visibility = if (visible) View.VISIBLE else View.GONE
        pickupProgress.visibility = if (state is PickupState.WaitingForAcceptance) View.VISIBLE else View.GONE
        pickupMetrics.visibility = if (state is PickupState.OnTheWay) View.VISIBLE else View.GONE
        when (state) {
            is PickupState.WaitingForAcceptance -> {
                pickupHeadline.text = "${state.target.name}의 수락을 기다리는 중"
                pickupDetail.text = "${state.target.name} 폰에 위치 공유를 요청했어요. 수락하면 바로 길 안내를 시작해요."
                pickupActionButton.setText(R.string.action_cancel_request)
            }
            is PickupState.OnTheWay -> {
                pickupHeadline.text = "${state.target.name} 픽업하러 가는 중"
                val location = state.targetLocation
                pickupDetail.text = if (location == null) "${state.target.name}의 위치를 받는 중이에요."
                else "${state.target.name}의 위치로 안내 중 · ${ago(location.updatedAt)} 갱신"
                val distance = state.remainDistanceMeters
                val time = state.remainTimeSeconds
                remainDistance.text = if (distance == null) "–" else "%.1fkm".format(distance / 1000.0)
                remainTime.text = if (time == null) "–" else "${(time + 59) / 60}분"
                pickupActionButton.setText(R.string.action_stop_pickup)
            }
            else -> Unit
        }
    }

    // ---- 그룹 상세(두 칸일 때 오른쪽) ----

    private fun renderGroup(group: FinderGroup) {
        val now = System.currentTimeMillis()
        val destination = session.destinationOf(group)
        groupName.text = group.name
        // 이름은 그룹을 만든 사람만 바꾼다.
        findViewById<View>(R.id.groupRenameButton).visibility = if (group.ownerId == session.me.id) View.VISIBLE else View.GONE
        groupMembers.removeAllViews()
        val members = group.members.filter { it.status != InviteStatus.DECLINED }
            .sortedWith(compareBy({ it.accountId != session.me.id }, { it.status != InviteStatus.ACCEPTED }))
        for (m in members) {
            val member = session.person(m.accountId)
            val isMe = m.accountId == session.me.id
            val vehicles = DemoDirectory.vehiclesDrivenBy(m.accountId)
            val row = contactRow(groupMembers, if (isMe) "${member.name} (나)" else member.name, DemoDirectory.hasFinderPhone(m.accountId), vehicles.isNotEmpty())
            val carText = vehicles.firstNotNullOfOrNull { v -> session.vehicleLocations[v.id]?.takeIf { it.driverId == m.accountId }?.let { " · ${v.name} 운전 중" } } ?: ""
            // 공유 목적지로 가는 차량은 남은 거리·시간과 도착 예정 시각을 함께 보여 준다.
            val progressText = destination?.progress?.get(m.accountId)?.takeUnless { it.isStale(now) }?.let { " · ${it.describe(now)}" } ?: ""
            row.status(m.shareText(now) + carText + progressText, if (m.isSharing(now)) CoreR.color.pleos_informative_active else CoreR.color.pleos_text_quaternary)
            row.view.isClickable = false
        }

        val mine = group.memberOf(session.me.id)
        val pending = mine?.status == InviteStatus.PENDING
        groupInviteBox.visibility = if (pending) View.VISIBLE else View.GONE
        renderGroupDestination(destination, pending)
        groupShareBox.visibility = if (pending) View.GONE else View.VISIBLE
        if (mine == null || pending) return

        groupShareTitle.text = "내 위치 공유 · 운전 중인 ${session.vehicle.name} 포함"
        val active = mine.isSharing(now)
        val shown = if (mine.share == SharePermission.TEMPORARY && !active) SharePermission.OFF else mine.share
        rendering = true
        groupShareGroup.check(
            when (shown) {
                SharePermission.ALWAYS -> R.id.groupShareAlways
                SharePermission.TEMPORARY -> R.id.groupShareTemporary
                SharePermission.OFF -> R.id.groupShareOff
            }
        )
        rendering = false
        groupExtendButton.isEnabled = shown != SharePermission.ALWAYS
        groupShareDetail.text = when (shown) {
            SharePermission.ALWAYS -> "그룹 구성원 모두가 내 위치와 내가 운전하는 차량 위치를 계속 볼 수 있어요."
            SharePermission.TEMPORARY -> "${TIME.format(Date(mine.shareUntil))}까지 공유해요. +30분으로 늘릴 수 있어요."
            SharePermission.OFF -> "공유하지 않아요. 일시적 허용이나 +30분을 누르면 지금부터 30분 동안 공유해요."
        }
    }

    // ---- 대화상자 ----

    private fun showProfileMenu() {
        val drivers = session.drivers
        val items = drivers.map { if (it == session.me) "${it.name} (지금 운전 중)" else "${it.name.euro} 전환" } +
            "다른 차량으로 시연" + "시연 데이터 초기화"
        AlertDialog.Builder(this)
            .setTitle("${session.vehicle.name} 운전자")
            .setItems(items.toTypedArray()) { _, which ->
                selectedId = null
                openGroupId = null
                when (which) {
                    in drivers.indices -> session.switchDriver(drivers[which])
                    drivers.size -> showVehiclePicker()
                    else -> session.resetDemo()
                }
            }
            .show()
    }

    /** 에뮬레이터 두 대로 차량끼리 시연할 때 이 에뮬레이터가 어느 차량인지 고른다. */
    private fun showVehiclePicker() {
        val vehicles = DemoDirectory.vehicles
        val labels = vehicles.map { v ->
            val owner = DemoDirectory.member(v.ownerId).name
            if (v == session.vehicle) "${v.name} · $owner (지금 이 차량)" else "${v.name} · $owner"
        }
        AlertDialog.Builder(this)
            .setTitle("시연할 차량")
            .setItems(labels.toTypedArray()) { _, which -> session.switchVehicle(vehicles[which]) }
            .show()
    }

    private fun showAddContact(relation: Relation) {
        val candidates = session.addableContacts()
        if (candidates.isEmpty()) {
            toast.show("연락처의 모든 사람이 이미 연결되어 있어요.")
            return
        }
        val label = if (relation == Relation.FAMILY) "가족으로 초대" else "친구로 추가"
        AlertDialog.Builder(this)
            .setTitle("연락처에서 $label")
            .setItems(candidates.map(::contactLabel).toTypedArray()) { _, which -> session.invite(candidates[which], relation) }
            .show()
    }

    private fun showGroupInvite(group: FinderGroup) {
        val candidates = session.invitableToGroup(group)
        if (candidates.isEmpty()) {
            toast.show("연락처의 모든 사람이 이미 그룹에 있어요.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("'${group.name}'에 초대")
            .setItems(candidates.map(::contactLabel).toTypedArray()) { _, which -> session.inviteToGroup(group, candidates[which]) }
            .show()
    }

    private fun showCreateGroup() {
        PleosTextInputDialog.show(
            this, title = "그룹 만들기", label = "그룹 이름", hint = "예: 주말 캠핑", initial = "", confirmLabel = "만들기",
        ) { name ->
            openGroupId = session.createGroup(name)
            render()
        }
    }

    private fun showRenameGroup(group: FinderGroup) {
        PleosTextInputDialog.show(
            this, title = "그룹 이름 변경", label = "그룹 이름", hint = "예: 주말 캠핑", initial = group.name, confirmLabel = "변경",
        ) { name -> session.renameGroup(group, name) }
    }

    /** 차량끼리 위치 공유는 항상 일시적: 오늘 하루만, 또는 30분 단위로 최대 24시간 */
    private fun showVehicleShare(member: Member) {
        val until = session.myVehicleShareUntil(member)
        val endOfToday = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val items = mutableListOf("오늘 하루만 공유 (오늘 밤 12시까지)", "시간 설정 (30분 단위, 최대 24시간)")
        if (until != null) items += "공유 중지"
        AlertDialog.Builder(this)
            .setTitle("${member.name.gwaWa} 차량 위치 공유")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> session.shareVehicle(member, endOfToday)
                    1 -> showShareDuration(member)
                    else -> session.stopVehicleShare(member)
                }
            }
            .show()
    }

    private fun showShareDuration(member: Member) {
        val view = inflater.inflate(R.layout.dialog_share_duration, null)
        val value = view.findViewById<TextView>(R.id.durationValue)
        val endText = view.findViewById<TextView>(R.id.durationEnd)
        var steps = 2 // 30분 × 2 = 1시간부터
        fun update() {
            val minutes = steps * 30
            value.text = if (minutes % 60 == 0) "${minutes / 60}시간" else if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
            endText.text = "${TIME.format(Date(System.currentTimeMillis() + minutes * 60_000L))}까지 서로 공유"
            view.findViewById<View>(R.id.durationMinus).isEnabled = steps > 1
            view.findViewById<View>(R.id.durationPlus).isEnabled = steps < MAX_SHARE_STEPS
        }
        view.findViewById<View>(R.id.durationMinus).setOnClickListener { steps--; update() }
        view.findViewById<View>(R.id.durationPlus).setOnClickListener { steps++; update() }
        update()
        AlertDialog.Builder(this)
            .setTitle("${member.name.gwaWa} 공유할 시간")
            .setView(view)
            .setPositiveButton("공유 시작") { _, _ -> session.shareVehicle(member, System.currentTimeMillis() + steps * VehicleSession.TEMPORARY_STEP_MS) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 연락처 목록 한 줄: 이름 · 설명 · 사람/차량 */
    private fun contactLabel(member: Member): String {
        val account = DemoDirectory.account(member.id)
        val kinds = listOfNotNull(
            "사람".takeIf { DemoDirectory.hasFinderPhone(member.id) },
            DemoDirectory.vehiclesDrivenBy(member.id).takeIf { it.isNotEmpty() }?.joinToString { "차량 ${it.name}" },
        ).joinToString(" + ")
        return "${member.name} · ${account?.note ?: ""} · $kinds"
    }

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun ago(at: Long): String {
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 10 -> "방금"
            seconds < 60 -> "${seconds}초 전"
            seconds < 3600 -> "${seconds / 60}분 전"
            else -> "${seconds / 3600}시간 전"
        }
    }

    private companion object {
        val TIME = SimpleDateFormat("a h:mm", Locale.KOREA)
        const val MAX_SHARE_STEPS = 48 // 30분 × 48 = 24시간
        const val DOUBLE_TAP_MS = 400L
        const val SECTION_FAMILY = "family"
        const val SECTION_FRIENDS = "friends"
        const val SECTION_GROUPS = "groups"
    }
}
