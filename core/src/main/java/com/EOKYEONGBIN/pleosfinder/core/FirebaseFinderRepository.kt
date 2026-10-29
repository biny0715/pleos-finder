package com.EOKYEONGBIN.pleosfinder.core

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database 구현. 데이터 구조:
 *   groups/{groupId}/locations/{memberId} = { latitude, longitude, updatedAt }
 *   groups/{groupId}/invites/{memberId} = { inviterId, inviterName, status, updatedAt }
 *   groups/{groupId}/permissions/{ownerId}/{viewerId} = "ALWAYS" | "TEMPORARY" | "OFF"
 *   groups/{groupId}/shareRequests/{ownerId} = { requesterId, requesterName, state, updatedAt }
 *   groups/{groupId}/pickups/{targetMemberId} = { driverName, state, vehicleLatitude, ..., updatedAt }
 * 서버에는 구성원별 최신 값만 덮어쓰고 이동 기록은 남기지 않는다(기획서 7장 '최소 저장').
 */
class FirebaseFinderRepository(
    databaseUrl: String = DATABASE_URL,
    groupId: String = DEMO_GROUP_ID,
) : FinderRepository {

    private val group = FirebaseDatabase.getInstance(databaseUrl).reference.child("groups").child(groupId)

    override fun publishMemberLocation(location: MemberLocation) {
        group.child("locations").child(location.memberId).setValue(
            mapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "updatedAt" to location.updatedAt,
            )
        )
    }

    override fun observeMemberLocation(memberId: String, onUpdate: (MemberLocation) -> Unit): Subscription =
        observe(group.child("locations").child(memberId)) { snapshot ->
            val latitude = snapshot.double("latitude") ?: return@observe
            val longitude = snapshot.double("longitude") ?: return@observe
            onUpdate(
                MemberLocation(
                    memberId = memberId,
                    latitude = latitude,
                    longitude = longitude,
                    updatedAt = snapshot.long("updatedAt") ?: 0L,
                )
            )
        }

    override fun publishInvite(invite: Invite) {
        group.child("invites").child(invite.memberId).setValue(
            mapOf(
                "inviterId" to invite.inviterId,
                "inviterName" to invite.inviterName,
                "status" to invite.status.name,
                "updatedAt" to invite.updatedAt,
            )
        )
    }

    override fun observeInvite(memberId: String, onUpdate: (Invite?) -> Unit): Subscription =
        observe(group.child("invites").child(memberId)) { snapshot ->
            val status = snapshot.enum<Invite.Status>("status")
            onUpdate(
                status?.let {
                    Invite(
                        memberId = memberId,
                        inviterId = snapshot.string("inviterId"),
                        inviterName = snapshot.string("inviterName"),
                        status = it,
                        updatedAt = snapshot.long("updatedAt") ?: 0L,
                    )
                }
            )
        }

    override fun setPermission(ownerId: String, viewerId: String, permission: SharePermission) {
        group.child("permissions").child(ownerId).child(viewerId).setValue(permission.name)
    }

    override fun observePermission(ownerId: String, viewerId: String, onUpdate: (SharePermission) -> Unit): Subscription =
        observe(group.child("permissions").child(ownerId).child(viewerId)) { snapshot ->
            val name = snapshot.getValue(String::class.java)
            onUpdate(SharePermission.entries.firstOrNull { it.name == name } ?: SharePermission.OFF)
        }

    override fun publishShareRequest(request: ShareRequest) {
        group.child("shareRequests").child(request.ownerId).setValue(
            mapOf(
                "requesterId" to request.requesterId,
                "requesterName" to request.requesterName,
                "state" to request.state.name,
                "updatedAt" to request.updatedAt,
            )
        )
    }

    override fun observeShareRequest(ownerId: String, onUpdate: (ShareRequest?) -> Unit): Subscription =
        observe(group.child("shareRequests").child(ownerId)) { snapshot ->
            val state = snapshot.enum<ShareRequest.State>("state")
            onUpdate(
                state?.let {
                    ShareRequest(
                        ownerId = ownerId,
                        requesterId = snapshot.string("requesterId"),
                        requesterName = snapshot.string("requesterName"),
                        state = it,
                        updatedAt = snapshot.long("updatedAt") ?: 0L,
                    )
                }
            )
        }

    override fun publishPickupStatus(status: PickupStatus) {
        group.child("pickups").child(status.targetMemberId).setValue(
            mapOf(
                "driverName" to status.driverName,
                "state" to status.state.name,
                "vehicleLatitude" to status.vehicleLatitude,
                "vehicleLongitude" to status.vehicleLongitude,
                "remainDistanceMeters" to status.remainDistanceMeters,
                "remainTimeSeconds" to status.remainTimeSeconds,
                "updatedAt" to status.updatedAt,
            )
        )
    }

    override fun observePickupStatus(memberId: String, onUpdate: (PickupStatus) -> Unit): Subscription =
        observe(group.child("pickups").child(memberId)) { snapshot ->
            val state = snapshot.enum<PickupStatus.State>("state") ?: return@observe
            onUpdate(
                PickupStatus(
                    targetMemberId = memberId,
                    driverName = snapshot.string("driverName"),
                    state = state,
                    vehicleLatitude = snapshot.double("vehicleLatitude"),
                    vehicleLongitude = snapshot.double("vehicleLongitude"),
                    remainDistanceMeters = snapshot.long("remainDistanceMeters")?.toInt(),
                    remainTimeSeconds = snapshot.long("remainTimeSeconds")?.toInt(),
                    updatedAt = snapshot.long("updatedAt") ?: 0L,
                )
            )
        }

    private fun observe(ref: DatabaseReference, onData: (DataSnapshot) -> Unit): Subscription {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observe ${ref.path} cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        return Subscription { ref.removeEventListener(listener) }
    }

    // 정수로 저장된 좌표(예: 127)도 Double 로 읽히도록 Number 로 받아 변환한다.
    private fun DataSnapshot.double(key: String): Double? = (child(key).value as? Number)?.toDouble()
    private fun DataSnapshot.long(key: String): Long? = (child(key).value as? Number)?.toLong()
    private fun DataSnapshot.string(key: String): String = child(key).getValue(String::class.java) ?: ""
    private inline fun <reified E : Enum<E>> DataSnapshot.enum(key: String): E? {
        val name = child(key).getValue(String::class.java) ?: return null
        return enumValues<E>().firstOrNull { it.name == name }
    }

    companion object {
        private const val TAG = "FinderRepository"
        const val DATABASE_URL = "https://pleos-finder-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val DEMO_GROUP_ID = "demo-family"
    }
}
