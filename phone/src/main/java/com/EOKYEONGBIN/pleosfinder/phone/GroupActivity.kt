package com.EOKYEONGBIN.pleosfinder.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.GroupMember
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.core.Subscription
import com.EOKYEONGBIN.pleosfinder.core.VehicleLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 폰 그룹 상세. 구성원마다 사람·차량 아이콘과 공유 상태를 보여 주고,
 * 공유 중인 구성원이 운전하는 차량은 '○○ 운전 중 · n분 전 위치'로 보여 준다(지도는 다음 단계).
 * 아래에서 이 그룹에 내 위치를 보여 주는 방식(항상 / 일시적 30분 단위 / 끄기)을 정한다.
 */
class GroupActivity : AppCompatActivity() {

    private val repository get() = FinderServices.repository
    private val me = FinderServices.me
    private val groupId get() = intent.getStringExtra(EXTRA_GROUP_ID)!!

    private lateinit var groupName: TextView
    private lateinit var membersTitle: TextView
    private lateinit var memberList: LinearLayout
    private lateinit var shareGroup: RadioGroup
    private lateinit var shareDetail: TextView
    private lateinit var extendButton: Button

    private var group: FinderGroup? = null
    private var vehicleLocations: Map<String, VehicleLocation> = emptyMap()
    private val subscriptions = mutableListOf<Subscription>()
    private val handler = Handler(Looper.getMainLooper())

    // 화면을 다시 그리면서 세그먼트를 바꿀 때는 사용자 선택으로 처리하지 않는다.
    private var rendering = false

    /** 위치 권한을 받은 뒤 적용할 내 구성원 정보 */
    private var pendingMember: GroupMember? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val member = pendingMember
        pendingMember = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && member != null) {
            save(member)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            render()
        }
    }

    /** 일시적 허용이 끝나는 시각이 지나면 화면을 바꾸도록 30초마다 다시 그린다. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group)
        groupName = findViewById(R.id.groupName)
        membersTitle = findViewById(R.id.membersTitle)
        memberList = findViewById(R.id.memberList)
        shareGroup = findViewById(R.id.shareGroup)
        shareDetail = findViewById(R.id.shareDetail)
        extendButton = findViewById(R.id.extendButton)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        shareGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val mine = group?.memberOf(me.id) ?: return@setOnCheckedChangeListener
            val value = when (checkedId) {
                R.id.shareAlways -> SharePermission.ALWAYS
                R.id.shareTemporary -> SharePermission.TEMPORARY
                else -> SharePermission.OFF
            }
            if (value != mine.effectiveShare()) apply(mine.withShare(value))
        }
        extendButton.setOnClickListener { group?.memberOf(me.id)?.let { apply(it.extended()) } }
    }

    override fun onStart() {
        super.onStart()
        subscriptions += repository.observeGroups { groups ->
            group = groups.firstOrNull { it.id == groupId }
            render()
        }
        subscriptions += repository.observeVehicleLocations {
            vehicleLocations = it
            render()
        }
        handler.post(tick)
    }

    override fun onStop() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        handler.removeCallbacks(tick)
        super.onStop()
    }

    /** 공유를 켤 때는 위치 권한부터 확인한다. */
    private fun apply(member: GroupMember) {
        if (member.share == SharePermission.OFF ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            save(member)
            return
        }
        pendingMember = member
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun save(member: GroupMember) {
        repository.setGroupMember(groupId, member)
        // 꺼짐이면 서비스가 공유 상태 변화를 보고 스스로 멈춘다.
        if (member.share != SharePermission.OFF) LocationShareService.start(this)
    }

    private fun render() {
        val current = group ?: return
        val now = System.currentTimeMillis()
        groupName.text = current.name
        val members = current.members.filter { it.status != InviteStatus.DECLINED }
            .sortedWith(compareBy({ it.accountId != me.id }, { it.status != InviteStatus.ACCEPTED }))
        membersTitle.text = "구성원 ${members.count { it.status == InviteStatus.ACCEPTED }}명 · ${members.count { it.isSharing(now) }}명 공유 중"
        memberList.removeAllViews()
        for (m in members) {
            val member = DemoDirectory.member(m.accountId)
            val vehicles = DemoDirectory.vehiclesDrivenBy(m.accountId)
            val car = vehicles.firstNotNullOfOrNull { v ->
                vehicleLocations[v.id]?.takeIf { it.driverId == m.accountId && m.isSharing(now) }?.let { v to it }
            }
            val carText = car?.let { (v, location) -> " · ${v.name} 운전 중 · ${ago(location.updatedAt)} 위치" } ?: ""
            Row.add(memberList).bind(
                title = if (m.accountId == me.id) "${member.name} (나)" else member.name,
                avatarText = member.name.take(1),
                showPerson = DemoDirectory.hasFinderPhone(m.accountId),
                showCar = vehicles.isNotEmpty(),
                statusText = m.shareText(now) + carText,
                dotColor = if (m.isSharing(now)) CoreR.color.pleos_informative_active else CoreR.color.pleos_text_quaternary,
            )
        }

        val mine = current.memberOf(me.id) ?: return
        val shown = mine.effectiveShare(now)
        rendering = true
        shareGroup.check(
            when (shown) {
                SharePermission.ALWAYS -> R.id.shareAlways
                SharePermission.TEMPORARY -> R.id.shareTemporary
                SharePermission.OFF -> R.id.shareOff
            }
        )
        rendering = false
        extendButton.isEnabled = shown != SharePermission.ALWAYS
        shareDetail.text = when (shown) {
            SharePermission.ALWAYS -> "그룹 구성원 모두가 내 위치를 계속 볼 수 있어요."
            SharePermission.TEMPORARY -> "${TIME.format(Date(mine.shareUntil))}까지 공유해요. 끝나면 자동으로 멈춰요."
            SharePermission.OFF -> "공유하지 않아요. 일시적 허용이나 +30분 연장을 누르면 지금부터 30분 동안 공유해요."
        }
    }

    private fun ago(at: Long): String {
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 60 -> "방금"
            seconds < 3600 -> "${seconds / 60}분 전"
            else -> "${seconds / 3600}시간 전"
        }
    }

    companion object {
        private const val EXTRA_GROUP_ID = "groupId"
        private val TIME = SimpleDateFormat("a h:mm", Locale.KOREA)

        fun open(context: Context, groupId: String) {
            context.startActivity(Intent(context, GroupActivity::class.java).putExtra(EXTRA_GROUP_ID, groupId))
        }
    }
}
