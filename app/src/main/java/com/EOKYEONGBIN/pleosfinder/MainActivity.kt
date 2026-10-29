package com.EOKYEONGBIN.pleosfinder

import android.content.res.ColorStateList
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.ui.PleosToast
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val session get() = (application as PleosFinderApp).session
    private val inflater get() = LayoutInflater.from(this)

    private lateinit var toast: PleosToast
    private lateinit var profileAvatar: TextView
    private lateinit var profileName: TextView
    private lateinit var listContainer: LinearLayout

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

    /** 펼쳐진 목록 항목(상대 id) */
    private var expandedId: String? = null

    /** 오른쪽에 열린 그룹 */
    private var openGroupId: String? = null

    // 화면을 다시 그리면서 세그먼트를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        session.onChanged = ::render
        session.onMessage = toast::show
        render()
    }

    override fun onStop() {
        session.onChanged = null
        session.onMessage = null
        super.onStop()
    }

    private fun render() {
        profileAvatar.text = session.me.name.take(1)
        profileName.text = "${session.me.name} · ${session.vehicle.name} 운전 중"
        renderList()
        renderPickup()
        val group = openGroup()
        if (group == null) openGroupId = null
        val split = group != null
        if (split != (groupPanel.visibility == View.VISIBLE)) {
            // 한 칸 ↔ 두 칸 전환을 부드럽게 (ivi_motion_duration_medium1 = 250ms)
            TransitionManager.beginDelayedTransition(contentRow, AutoTransition().setDuration(250))
            groupPanel.visibility = if (split) View.VISIBLE else View.GONE
        }
        if (group != null) renderGroup(group)
    }

    private fun openGroup(): FinderGroup? = openGroupId?.let { id -> session.myGroups().firstOrNull { it.id == id } }

    // ---- 왼쪽 목록 ----

    private fun renderList() {
        listContainer.removeAllViews()
        addSection(getString(R.string.section_family), getString(R.string.action_add_family)) { showAddContact(Relation.FAMILY) }
        addContacts(Relation.FAMILY, "가족을 초대하면 여기에 보여요.")
        addSection(getString(R.string.section_friends), getString(R.string.action_add_friend)) { showAddContact(Relation.FRIEND) }
        addContacts(Relation.FRIEND, "친구나 직장 동료의 폰·차량을 추가해 보세요.")
        addSection(getString(R.string.section_groups), getString(R.string.action_add_group)) { showCreateGroup() }
        addGroups()
    }

    private fun addSection(title: String, action: String, onAction: () -> Unit) {
        val header = inflater.inflate(R.layout.item_section_header, listContainer, false)
        header.findViewById<TextView>(R.id.sectionTitle).text = title
        header.findViewById<TextView>(R.id.sectionAction).apply {
            text = action
            setOnClickListener { onAction() }
        }
        listContainer.addView(header)
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

    private fun addContacts(relation: Relation, emptyMessage: String) {
        val card = card()
        val contacts = session.contacts(relation)
        if (contacts.isEmpty()) addEmpty(card, emptyMessage)
        for (contact in contacts) {
            val row = contactRow(card, contact.member.name, contact.hasFinderPhone, contact.vehicles.isNotEmpty())
            val (status, dot) = contactStatus(contact)
            row.status(status, dot)
            val expanded = expandedId == contact.member.id
            row.view.isActivated = expanded
            row.view.setOnClickListener {
                expandedId = if (expanded) null else contact.member.id
                render()
            }
            if (expanded) addContactActions(row.actions, contact)
        }
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
    }

    private fun addGroups() {
        val card = card()
        val groups = session.myGroups()
        if (groups.isEmpty()) addEmpty(card, "그룹을 만들어 여러 사람·차량과 함께 위치를 나눠 보세요.")
        val now = System.currentTimeMillis()
        for (group in groups) {
            val row = contactRow(card, group.name, showPerson = false, showCar = false)
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
        }
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
        groupName.text = group.name
        groupMembers.removeAllViews()
        val members = group.members.filter { it.status != InviteStatus.DECLINED }
            .sortedWith(compareBy({ it.accountId != session.me.id }, { it.status != InviteStatus.ACCEPTED }))
        for (m in members) {
            val member = DemoDirectory.member(m.accountId)
            val isMe = m.accountId == session.me.id
            val vehicles = DemoDirectory.vehiclesDrivenBy(m.accountId)
            val row = contactRow(groupMembers, if (isMe) "${member.name} (나)" else member.name, DemoDirectory.hasFinderPhone(m.accountId), vehicles.isNotEmpty())
            val carText = vehicles.firstNotNullOfOrNull { v -> session.vehicleLocations[v.id]?.takeIf { it.driverId == m.accountId }?.let { " · ${v.name} 운전 중" } } ?: ""
            row.status(m.shareText(now) + carText, if (m.isSharing(now)) CoreR.color.pleos_informative_active else CoreR.color.pleos_text_quaternary)
            row.view.isClickable = false
        }

        val mine = group.memberOf(session.me.id)
        val pending = mine?.status == InviteStatus.PENDING
        groupInviteBox.visibility = if (pending) View.VISIBLE else View.GONE
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
        val items = drivers.map { if (it == session.me) "${it.name} (지금 운전 중)" else "${it.name}(으)로 전환" } + "시연 데이터 초기화"
        AlertDialog.Builder(this)
            .setTitle("${session.vehicle.name} 운전자")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < drivers.size) {
                    expandedId = null
                    openGroupId = null
                    session.switchDriver(drivers[which])
                } else {
                    expandedId = null
                    openGroupId = null
                    session.resetDemo()
                }
            }
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
        val input = EditText(this).apply {
            setText("새 그룹")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("그룹 만들기")
            .setView(input)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "새 그룹" }
                openGroupId = session.createGroup(name)
                render()
            }
            .setNegativeButton("취소", null)
            .show()
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
            .setTitle("${member.name}와(과) 차량 위치 공유")
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
            .setTitle("${member.name}와(과) 공유할 시간")
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
    }
}
