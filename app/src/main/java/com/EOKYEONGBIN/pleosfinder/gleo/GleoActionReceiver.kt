package com.EOKYEONGBIN.pleosfinder.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.EOKYEONGBIN.pleosfinder.PleosFinderApp
import com.EOKYEONGBIN.pleosfinder.SharePlace
import com.EOKYEONGBIN.pleosfinder.VehicleSession
import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.FinderGroup
import com.EOKYEONGBIN.pleosfinder.core.InviteStatus
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.Relation
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.gleo.GleoResponder.Status
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gleo AI 음성 명령을 받는다(assets/schema.json 의 component = "service"). Gleo 함수는 최대 3개라 다음처럼 묶었다.
 * - PICKUP(action = start | stop | status, memberName): "와이프 픽업 가줘", "픽업 그만", "아들 어디쯤이야?"
 * - SHARE_LOCATION(targetName, shareType, durationMinutes, untilEndOfToday): "박지훈한테 1시간만 위치 공유해줘", "강릉 여행 그룹에 2시간만 공유해줘",
 *   "강릉 여행 그룹에 지금 목적지 공유해줘"(shareType = current_destination), "강릉 여행 그룹에 여기로 모이자고 해줘"(car_position)
 * - STOP_LOCATION_SHARING(targetName, category): "친구 목록 중에 위치 공유된 것 모두 꺼줘"
 * Gleo 는 sendBroadcast 로 action = "패키지 이름.함수 이름", ACTION_PARAM = 파라미터 JSON 을 보낸다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Gleo 는 "패키지 이름.함수 이름"으로 보낸다(문서 예시는 함수 이름만). 함수 이름만 남긴다.
        val action = intent.action?.removePrefix("${context.packageName}.") ?: return
        val params = parseParams(intent)
        Log.d(TAG, "action $action params $params")
        val session = (context.applicationContext as PleosFinderApp).session
        val pending = goAsync()
        // 앱이 Gleo 호출로 막 시작됐다면 서버에서 연결 정보를 받을 때까지 기다린다(Gleo 제한 7초 안).
        session.whenReady(READY_TIMEOUT_MS) {
            try {
                val reply = Reply(context, action)
                when (action) {
                    PICKUP -> pickup(reply, session, params)
                    SHARE_LOCATION -> shareLocation(reply, session, params)
                    STOP_LOCATION_SHARING -> stopSharing(reply, session, params)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private class Reply(val context: Context, val functionName: String) {
        operator fun invoke(status: Status, message: String, data: JSONArray? = null) =
            GleoResponder.respond(context, functionName, status, message, data)
    }

    // ---- PICKUP ----

    private fun pickup(reply: Reply, session: VehicleSession, params: JSONObject) {
        when (params.optString(PARAM_ACTION, "start").lowercase()) {
            "stop" -> stopPickup(reply, session)
            "status" -> pickupStatus(reply, session, params)
            else -> startPickup(reply, session, params)
        }
    }

    private fun startPickup(reply: Reply, session: VehicleSession, params: JSONObject) {
        val candidates = session.pickupCandidates()
        val member = resolvePerson(reply, candidates, params.optString(PARAM_MEMBER_NAME), "Who should I pick up?") ?: return
        val state = session.pickupState
        when {
            state is PickupState.OnTheWay && state.target.id == member.id ->
                reply(Status.SUCCESS, "You are already on the way to pick up ${member.name}.")
            state is PickupState.WaitingForAcceptance && state.target.id == member.id ->
                reply(Status.SUCCESS, "Already waiting for ${member.name} to accept the location sharing request.")
            session.isPickupBusy -> reply(Status.FAIL, "Another pickup is in progress. Stop it first.")
            !session.naviConnected -> reply(Status.FAIL, "Navigation is not ready yet. Please try again in a moment.")
            else -> when (session.permissionOf(member)) {
                SharePermission.ALWAYS -> {
                    session.startPickup(member)
                    reply(Status.SUCCESS, "Started navigation to ${member.name}'s location and told ${member.name} you are on the way.")
                }
                SharePermission.TEMPORARY -> {
                    session.startPickup(member)
                    reply(Status.SUCCESS, "Asked ${member.name} to share location. Navigation will start as soon as ${member.name} accepts.")
                }
                SharePermission.OFF ->
                    reply(Status.FAIL, "${member.name} has turned off location sharing, so navigation cannot start.")
            }
        }
    }

    private fun stopPickup(reply: Reply, session: VehicleSession) {
        val message = when (val state = session.pickupState) {
            is PickupState.OnTheWay -> "Stopped the pickup for ${state.target.name} and ended navigation."
            is PickupState.WaitingForAcceptance -> "Cancelled the location sharing request to ${state.target.name}."
            else -> "There is no pickup in progress."
        }
        session.stopPickup()
        reply(Status.SUCCESS, message)
    }

    private fun pickupStatus(reply: Reply, session: VehicleSession, params: JSONObject) {
        val state = session.pickupState
        val spoken = params.optString(PARAM_MEMBER_NAME)
        // 이름 없이 "어디쯤이야?"라고 물으면 지금 픽업 중인 사람으로 답한다.
        val member = if (spoken.isBlank() && state is PickupState.OnTheWay) state.target
        else resolvePerson(reply, session.pickupCandidates(), spoken, "Whose location do you want to know?") ?: return
        val location = session.lastLocationOf(member)
        val ageMinutes = location?.let { (System.currentTimeMillis() - it.updatedAt) / 60_000 }
        val data = location?.let {
            JSONArray().put(JSONObject().put("memberName", member.name).put("latitude", it.latitude).put("longitude", it.longitude).put("updatedMinutesAgo", ageMinutes))
        }
        when {
            state is PickupState.OnTheWay && state.target.id == member.id && state.remainDistanceMeters != null && state.remainTimeSeconds != null -> {
                val km = "%.1f".format(state.remainDistanceMeters / 1000.0)
                reply(Status.SUCCESS, "${member.name} is $km km away, about ${(state.remainTimeSeconds + 59) / 60} minutes by car.", data)
            }
            session.permissionOf(member) == SharePermission.OFF -> reply(Status.FAIL, "${member.name} has turned off location sharing.")
            session.permissionOf(member) == SharePermission.TEMPORARY && state !is PickupState.OnTheWay ->
                reply(Status.FAIL, "${member.name} shares location only during a pickup. Start a pickup to request it.")
            location == null || ageMinutes == null || ageMinutes > LOCATION_FRESH_MINUTES ->
                reply(Status.FAIL, "${member.name}'s current location is not available right now.")
            else -> reply(Status.SUCCESS, "${member.name}'s location was updated ${if (ageMinutes < 1) "just now" else "$ageMinutes minutes ago"}. It is shown on the Finder screen.", data)
        }
    }

    // ---- SHARE_LOCATION ----

    /** 사람이면 차량끼리 위치 공유, 그룹이면 그룹에 일시적 공유. 시간은 30분 단위로 올려 최대 24시간. */
    private fun shareLocation(reply: Reply, session: VehicleSession, params: JSONObject) {
        val target = params.optString(PARAM_TARGET_NAME).trim()
        val shareType = params.optString(PARAM_SHARE_TYPE, SHARE_TYPE_LOCATION).lowercase()
        if (shareType == SHARE_TYPE_DESTINATION || shareType == SHARE_TYPE_CAR_POSITION) {
            shareDestination(reply, session, target, here = shareType == SHARE_TYPE_CAR_POSITION)
            return
        }
        if (target.isEmpty()) {
            reply(Status.ASKING, "Who or which group should I share my car's location with?")
            return
        }
        val now = System.currentTimeMillis()
        val until = when {
            params.optBoolean(PARAM_UNTIL_END_OF_TODAY) -> endOfToday()
            params.has(PARAM_DURATION_MINUTES) -> {
                val minutes = params.optDouble(PARAM_DURATION_MINUTES).toInt().coerceIn(STEP_MINUTES, MAX_MINUTES)
                val rounded = (minutes + STEP_MINUTES - 1) / STEP_MINUTES * STEP_MINUTES
                now + rounded * 60_000L
            }
            else -> {
                reply(Status.ASKING, "How long should I share? Choose from 30 minutes up to 24 hours, or just for today.")
                return
            }
        }
        val untilText = TIME.format(Date(until))
        val group = findGroup(session, target)
        if (group != null) {
            if (group.memberOf(session.me.id)?.status != InviteStatus.ACCEPTED) {
                reply(Status.FAIL, "You have not joined the '${group.name}' group yet.")
                return
            }
            session.setGroupShareUntil(group, until)
            reply(Status.SUCCESS, "Sharing your location with the '${group.name}' group until $untilText.")
            return
        }
        val people = Relation.entries.flatMap(session::contacts).filter { it.isAccepted }.map { it.member }
        val member = resolvePerson(reply, people, target, "Who should I share my car's location with?") ?: return
        session.shareVehicle(member, until)
        reply(Status.SUCCESS, "Sharing car locations with ${member.name} until $untilText.")
    }

    /**
     * 그룹 목적지 공유: 지금 내비가 안내 중인 목적지(here = false) 또는 지금 내 차 위치(here = true, 여기로 모이기).
     * 목적지는 그룹에만 공유한다. 구성원 차량에는 '목적지 공유' 팝업·알림이 뜬다.
     */
    private fun shareDestination(reply: Reply, session: VehicleSession, target: String, here: Boolean) {
        if (target.isEmpty()) {
            reply(Status.ASKING, "Which group should I share the destination with?")
            return
        }
        val group = findGroup(session, target)
        if (group == null) {
            reply(Status.FAIL, "Destinations can only be shared with a group. I couldn't find a group named '$target'.")
            return
        }
        if (group.memberOf(session.me.id)?.status != InviteStatus.ACCEPTED) {
            reply(Status.FAIL, "You have not joined the '${group.name}' group yet.")
            return
        }
        session.loadSharePlaces { places ->
            val kind = if (here) SharePlace.Kind.HERE else SharePlace.Kind.CURRENT
            val place = places.firstOrNull { it.kind == kind }
            when {
                place != null -> {
                    session.shareDestination(group, place)
                    val what = if (here) "your car's current location as the meeting point" else "'${place.name}'"
                    reply(Status.SUCCESS, "Shared $what with the '${group.name}' group. Members can start navigation from the pop-up.")
                }
                here -> reply(Status.FAIL, "I can't get your car's location right now.")
                else -> reply(Status.FAIL, "There is no destination set in the navigation. Set a destination first, or ask me to share where your car is now.")
            }
        }
    }

    // ---- STOP_LOCATION_SHARING ----

    /** 이름이 있으면 그 사람·그룹만, 없으면 category(가족·친구·그룹·전체)로 내가 켠 공유를 모두 끈다. */
    private fun stopSharing(reply: Reply, session: VehicleSession, params: JSONObject) {
        val target = params.optString(PARAM_TARGET_NAME).trim()
        if (target.isNotEmpty()) {
            val group = findGroup(session, target)
            if (group != null) {
                session.setGroupShare(group, SharePermission.OFF)
                reply(Status.SUCCESS, "Stopped sharing your location with the '${group.name}' group.")
                return
            }
            val people = Relation.entries.flatMap(session::contacts).filter { it.isAccepted }.map { it.member }
            val member = resolvePerson(reply, people, target, "Whose location sharing should I turn off?") ?: return
            if (session.myVehicleShareUntil(member) == null) {
                reply(Status.SUCCESS, "You are not sharing your location with ${member.name}.")
            } else {
                session.stopVehicleShare(member)
                reply(Status.SUCCESS, "Stopped sharing car locations with ${member.name}.")
            }
            return
        }
        val category = params.optString(PARAM_CATEGORY).uppercase()
        val relations = when (category) {
            "FAMILY" -> listOf(Relation.FAMILY)
            "FRIENDS" -> listOf(Relation.FRIEND)
            "GROUPS" -> emptyList()
            "ALL" -> Relation.entries
            else -> {
                reply(Status.ASKING, "Which sharing should I turn off: family, friends, groups, or all?")
                return
            }
        }
        val stoppedPeople = relations.flatMap(session::contacts).map { it.member }.filter { session.myVehicleShareUntil(it) != null }
        stoppedPeople.forEach(session::stopVehicleShare)
        val stoppedGroups = if (category == "GROUPS" || category == "ALL") {
            session.myGroups().filter { it.memberOf(session.me.id)?.isSharing() == true }.onEach { session.setGroupShare(it, SharePermission.OFF) }
        } else emptyList()
        val names = stoppedPeople.map { it.name } + stoppedGroups.map { "'${it.name}' group" }
        val label = when (category) { "FAMILY" -> "family"; "FRIENDS" -> "friends"; "GROUPS" -> "groups"; else -> "anyone" }
        if (names.isEmpty()) reply(Status.SUCCESS, "No location sharing with $label was on.")
        else reply(Status.SUCCESS, "Turned off location sharing with ${names.joinToString(", ")}.")
    }

    // ---- 이름 찾기 ----

    /** 부르는 이름(와이프), 계정 이름(엄마), 관계 단어(wife, son)로 찾는다. 없거나 모호하면 asking 으로 되묻는다. */
    private fun resolvePerson(reply: Reply, candidates: List<Member>, spokenRaw: String, question: String): Member? {
        val spoken = spokenRaw.trim().lowercase().removeSuffix("님")
        val names = candidates.joinToString(", ") { it.name }
        val member = if (spoken.isEmpty()) null else candidates.firstOrNull { m -> aliasesOf(m).any { spoken == it || spoken.contains(it) } }
            ?: candidates.filter { it.id == DemoDirectory.SON.id }.singleOrNull()?.takeIf { spoken in GENERIC_CHILD_WORDS }
        if (member == null) {
            val message = if (spoken.isEmpty()) "$question You can choose: $names."
            else "I couldn't find '$spokenRaw'. You can choose: $names."
            reply(Status.ASKING, message)
        }
        return member
    }

    private fun aliasesOf(member: Member): List<String> = listOf(member.name.lowercase(), DemoDirectory.member(member.id).name.lowercase()) +
        when (member.id) {
            DemoDirectory.MOM.id -> listOf("wife", "my wife", "와이프", "아내", "집사람", "mom")
            DemoDirectory.DAD.id -> listOf("husband", "my husband", "남편", "dad")
            DemoDirectory.SON.id -> listOf("son", "my son", "아들", "우리 아들")
            else -> emptyList()
        }

    private fun findGroup(session: VehicleSession, spokenRaw: String): FinderGroup? {
        val spoken = spokenRaw.lowercase().replace("그룹", "").replace("group", "").trim()
        if (spoken.isEmpty()) return null
        return session.myGroups().firstOrNull { g ->
            val name = g.name.lowercase()
            name == spoken || name.contains(spoken) || spoken.contains(name) || name.replace(" ", "") == spoken.replace(" ", "")
        }
    }

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Gleo 예시는 ACTION_PARAM 에 JSON 문자열(경우에 따라 한 번 더 따옴표로 감싼)을 넣는다. 각 값을 extra 로 넣는 경우도 받는다. */
    private fun parseParams(intent: Intent): JSONObject {
        val raw = intent.getStringExtra(ACTION_PARAM)
        if (raw != null) {
            runCatching {
                var value: Any? = JSONTokener(raw).nextValue()
                if (value is String) value = JSONTokener(value).nextValue()
                if (value is JSONObject) return value
            }.onFailure { Log.w(TAG, "ACTION_PARAM parse failed: $raw") }
        }
        return JSONObject().apply {
            intent.extras?.keySet()?.filter { it != ACTION_PARAM }?.forEach { key -> put(key, intent.extras?.get(key)?.toString()) }
        }
    }

    companion object {
        const val PICKUP = "PICKUP"
        const val SHARE_LOCATION = "SHARE_LOCATION"
        const val STOP_LOCATION_SHARING = "STOP_LOCATION_SHARING"
        private const val ACTION_PARAM = "ACTION_PARAM"
        private const val PARAM_ACTION = "action"
        private const val PARAM_MEMBER_NAME = "memberName"
        private const val PARAM_TARGET_NAME = "targetName"
        private const val PARAM_DURATION_MINUTES = "durationMinutes"
        private const val PARAM_UNTIL_END_OF_TODAY = "untilEndOfToday"
        private const val PARAM_SHARE_TYPE = "shareType"
        private const val SHARE_TYPE_LOCATION = "location"
        private const val SHARE_TYPE_DESTINATION = "current_destination"
        private const val SHARE_TYPE_CAR_POSITION = "car_position"
        private const val PARAM_CATEGORY = "category"
        private const val STEP_MINUTES = 30
        private const val MAX_MINUTES = 24 * 60
        private const val READY_TIMEOUT_MS = 4_000L
        private const val LOCATION_FRESH_MINUTES = 10L
        private const val TAG = "PleosFinderGleo"
        private val TIME = SimpleDateFormat("h:mm a", Locale.US)
        private val GENERIC_CHILD_WORDS = setOf("child", "kid", "my kid", "my child", "애", "아이", "우리 애")
    }
}
