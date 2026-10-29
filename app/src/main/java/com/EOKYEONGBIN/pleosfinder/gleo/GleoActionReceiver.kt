package com.EOKYEONGBIN.pleosfinder.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.EOKYEONGBIN.pleosfinder.PleosFinderApp
import com.EOKYEONGBIN.pleosfinder.VehicleSession
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.SharePermission
import com.EOKYEONGBIN.pleosfinder.gleo.GleoResponder.Status
import com.EOKYEONGBIN.pleosfinder.pickup.PickupState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Gleo AI 음성 명령을 받는다(assets/schema.json 의 component = "service").
 * Gleo 는 sendBroadcast 로 action = 함수 이름, ACTION_PARAM = 파라미터 JSON 을 보낸다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = parseParams(intent)
        Log.d(TAG, "action $action params $params")
        val session = (context.applicationContext as PleosFinderApp).session
        val pending = goAsync()
        // 앱이 Gleo 호출로 막 시작됐다면 서버에서 가족 정보를 받을 때까지 기다린다(Gleo 제한 7초 안).
        session.whenReady(READY_TIMEOUT_MS) {
            try {
                when (action) {
                    START_PICKUP -> startPickup(context, session, params)
                    STOP_PICKUP -> stopPickup(context, session)
                    GET_MEMBER_LOCATION -> getMemberLocation(context, session, params)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun startPickup(context: Context, session: VehicleSession, params: JSONObject) {
        val member = resolveMember(context, session, START_PICKUP, params) ?: return
        val reply = { status: Status, message: String -> GleoResponder.respond(context, START_PICKUP, status, message) }
        val state = session.pickupState
        when {
            !session.isJoined(member) -> reply(Status.FAIL, "${member.name} has not joined your family group yet.")
            state is PickupState.OnTheWay && state.target == member ->
                reply(Status.SUCCESS, "You are already on the way to pick up ${member.name}.")
            state is PickupState.WaitingForAcceptance && state.target == member ->
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

    private fun stopPickup(context: Context, session: VehicleSession) {
        val message = when (val state = session.pickupState) {
            is PickupState.OnTheWay -> "Stopped the pickup for ${state.target.name} and ended navigation."
            is PickupState.WaitingForAcceptance -> "Cancelled the location sharing request to ${state.target.name}."
            else -> "There is no pickup in progress."
        }
        session.stopPickup()
        GleoResponder.respond(context, STOP_PICKUP, Status.SUCCESS, message)
    }

    private fun getMemberLocation(context: Context, session: VehicleSession, params: JSONObject) {
        val member = resolveMember(context, session, GET_MEMBER_LOCATION, params) ?: return
        val reply = { status: Status, message: String, data: JSONArray? ->
            GleoResponder.respond(context, GET_MEMBER_LOCATION, status, message, data)
        }
        val state = session.pickupState
        val location = session.lastLocationOf(member)
        val ageMinutes = location?.let { (System.currentTimeMillis() - it.updatedAt) / 60_000 }
        val data = location?.let {
            JSONArray().put(
                JSONObject()
                    .put("memberName", member.name)
                    .put("latitude", it.latitude)
                    .put("longitude", it.longitude)
                    .put("updatedMinutesAgo", ageMinutes)
            )
        }
        when {
            !session.isJoined(member) -> reply(Status.FAIL, "${member.name} has not joined your family group yet.", null)
            state is PickupState.OnTheWay && state.target == member && state.remainDistanceMeters != null && state.remainTimeSeconds != null -> {
                val km = "%.1f".format(state.remainDistanceMeters / 1000.0)
                val minutes = (state.remainTimeSeconds + 59) / 60
                reply(Status.SUCCESS, "${member.name} is $km km away, about $minutes minutes by car.", data)
            }
            session.permissionOf(member) == SharePermission.OFF ->
                reply(Status.FAIL, "${member.name} has turned off location sharing.", null)
            session.permissionOf(member) == SharePermission.TEMPORARY && state !is PickupState.OnTheWay ->
                reply(Status.FAIL, "${member.name} shares location only during a pickup. Start a pickup to request it.", null)
            location == null || ageMinutes == null || ageMinutes > LOCATION_FRESH_MINUTES ->
                reply(Status.FAIL, "${member.name}'s current location is not available right now.", null)
            else -> reply(Status.SUCCESS, "${member.name}'s location was updated ${ageText(ageMinutes)}. It is shown on the Finder screen.", data)
        }
    }

    /** 이름으로 가족 구성원을 찾는다. 이름이 없거나 모르면 asking 으로 되묻고 null 을 돌려준다. */
    private fun resolveMember(context: Context, session: VehicleSession, functionName: String, params: JSONObject): Member? {
        val spoken = params.optString(PARAM_MEMBER_NAME).trim().lowercase()
        val names = session.family.joinToString(", ") { it.name }
        val member = session.family.firstOrNull { member ->
            spoken.isNotEmpty() && (aliasesOf(member) + member.name.lowercase()).any { spoken == it || spoken.contains(it) }
        } ?: session.family.singleOrNull()?.takeIf { spoken in GENERIC_CHILD_WORDS }
        if (member == null) {
            val message = if (spoken.isEmpty()) "Who should I look for? Your family group has: $names."
            else "I couldn't find '$spoken' in your family group. Your family group has: $names."
            GleoResponder.respond(context, functionName, Status.ASKING, message)
        }
        return member
    }

    private fun aliasesOf(member: Member): List<String> = when (member.id) {
        "son" -> listOf("son", "my son", "아들", "우리 아들")
        "daughter" -> listOf("daughter", "my daughter", "딸", "우리 딸")
        else -> listOf(member.id)
    }

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

    private fun ageText(minutes: Long) = if (minutes < 1) "just now" else "$minutes minutes ago"

    companion object {
        const val START_PICKUP = "START_PICKUP"
        const val STOP_PICKUP = "STOP_PICKUP"
        const val GET_MEMBER_LOCATION = "GET_MEMBER_LOCATION"
        private const val ACTION_PARAM = "ACTION_PARAM"
        private const val PARAM_MEMBER_NAME = "memberName"
        private const val READY_TIMEOUT_MS = 4_000L
        private const val LOCATION_FRESH_MINUTES = 10L
        private const val TAG = "PleosFinderGleo"
        private val GENERIC_CHILD_WORDS = setOf("child", "kid", "my kid", "my child", "애", "아이", "우리 애")
    }
}
