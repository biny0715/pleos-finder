package com.EOKYEONGBIN.pleosfinder.core

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database 구현. 시연 공간(groups/demo-family) 아래 데이터 구조:
 *   locations/{accountId} = { latitude, longitude, updatedAt }                       폰 위치
 *   vehicleLocations/{vehicleId} = { driverId, latitude, longitude, updatedAt }      차량 위치(운전자 기준)
 *   connections/{accountId}/{otherId} = { relation, status, inviterId, updatedAt }    가족·친구 연결
 *   permissions/{ownerId}/{viewerId} = "ALWAYS" | "TEMPORARY" | "OFF"                 1:1 허용 방식
 *   vehicleShares/{ownerId}/{viewerId} = until                                       차량끼리 위치 공유(시간 제한)
 *   shareRequests/{ownerId} = { requesterId, requesterName, state, updatedAt }       일시적 허용 요청
 *   pickups/{targetMemberId} = { driverName, state, vehicleLatitude, ..., updatedAt }
 *   finderGroups/{groupId} = { name, ownerId, members/{accountId} = { status, share, shareUntil } }
 * 서버에는 최신 값만 덮어쓰고 이동 기록은 남기지 않는다(기획서 7장 '최소 저장').
 */
class FirebaseFinderRepository(
    databaseUrl: String = DATABASE_URL,
    spaceId: String = DEMO_SPACE_ID,
) : FinderRepository {

    private val root = FirebaseDatabase.getInstance(databaseUrl).reference.child("groups").child(spaceId)

    override fun publishMemberLocation(location: MemberLocation) {
        root.child("locations").child(location.memberId).setValue(
            mapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "updatedAt" to location.updatedAt,
            )
        )
    }

    override fun observeMemberLocation(memberId: String, onUpdate: (MemberLocation) -> Unit): Subscription =
        observe(root.child("locations").child(memberId)) { snapshot ->
            val latitude = snapshot.double("latitude") ?: return@observe
            val longitude = snapshot.double("longitude") ?: return@observe
            onUpdate(MemberLocation(memberId, latitude, longitude, snapshot.long("updatedAt") ?: 0L))
        }

    override fun publishVehicleLocation(location: VehicleLocation) {
        root.child("vehicleLocations").child(location.vehicleId).setValue(
            mapOf(
                "driverId" to location.driverId,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "updatedAt" to location.updatedAt,
            )
        )
    }

    override fun observeVehicleLocations(onUpdate: (Map<String, VehicleLocation>) -> Unit): Subscription =
        observe(root.child("vehicleLocations")) { snapshot ->
            onUpdate(
                snapshot.children.mapNotNull { child ->
                    val vehicleId = child.key ?: return@mapNotNull null
                    val latitude = child.double("latitude") ?: return@mapNotNull null
                    val longitude = child.double("longitude") ?: return@mapNotNull null
                    vehicleId to VehicleLocation(vehicleId, child.string("driverId"), latitude, longitude, child.long("updatedAt") ?: 0L)
                }.toMap()
            )
        }

    override fun inviteConnection(inviterId: String, otherId: String, relation: Relation) {
        writeConnectionPair(inviterId, otherId, relation, InviteStatus.PENDING, inviterId)
    }

    override fun respondConnection(ownerId: String, otherId: String, relation: Relation, inviterId: String, accept: Boolean) {
        writeConnectionPair(ownerId, otherId, relation, if (accept) InviteStatus.ACCEPTED else InviteStatus.DECLINED, inviterId)
    }

    private fun writeConnectionPair(a: String, b: String, relation: Relation, status: InviteStatus, inviterId: String) {
        val value = mapOf(
            "relation" to relation.name,
            "status" to status.name,
            "inviterId" to inviterId,
            "updatedAt" to System.currentTimeMillis(),
        )
        root.child("connections").updateChildren(mapOf("$a/$b" to value, "$b/$a" to value))
    }

    override fun observeConnections(accountId: String, onUpdate: (List<Connection>) -> Unit): Subscription =
        observe(root.child("connections").child(accountId)) { snapshot ->
            onUpdate(
                snapshot.children.mapNotNull { child ->
                    val otherId = child.key ?: return@mapNotNull null
                    Connection(
                        ownerId = accountId,
                        otherId = otherId,
                        relation = child.enum<Relation>("relation") ?: return@mapNotNull null,
                        status = child.enum<InviteStatus>("status") ?: return@mapNotNull null,
                        inviterId = child.string("inviterId"),
                        updatedAt = child.long("updatedAt") ?: 0L,
                    )
                }
            )
        }

    override fun setPermission(ownerId: String, viewerId: String, permission: SharePermission) {
        root.child("permissions").child(ownerId).child(viewerId).setValue(permission.name)
    }

    override fun observePermission(ownerId: String, viewerId: String, onUpdate: (SharePermission) -> Unit): Subscription =
        observe(root.child("permissions").child(ownerId).child(viewerId)) { snapshot ->
            onUpdate(permissionOf(snapshot.getValue(String::class.java)))
        }

    override fun observePermissions(ownerId: String, onUpdate: (Map<String, SharePermission>) -> Unit): Subscription =
        observe(root.child("permissions").child(ownerId)) { snapshot ->
            onUpdate(snapshot.children.mapNotNull { child -> child.key?.let { it to permissionOf(child.getValue(String::class.java)) } }.toMap())
        }

    private fun permissionOf(name: String?) = SharePermission.entries.firstOrNull { it.name == name } ?: SharePermission.OFF

    override fun setVehicleShare(ownerId: String, viewerId: String, until: Long) {
        val ref = root.child("vehicleShares").child(ownerId).child(viewerId)
        if (until <= 0L) ref.removeValue() else ref.setValue(until)
    }

    override fun observeVehicleShares(ownerId: String, onUpdate: (Map<String, Long>) -> Unit): Subscription =
        observe(root.child("vehicleShares").child(ownerId)) { snapshot ->
            onUpdate(snapshot.children.mapNotNull { child -> child.key?.let { key -> (child.value as? Number)?.toLong()?.let { key to it } } }.toMap())
        }

    override fun publishShareRequest(request: ShareRequest) {
        root.child("shareRequests").child(request.ownerId).setValue(
            mapOf(
                "requesterId" to request.requesterId,
                "requesterName" to request.requesterName,
                "state" to request.state.name,
                "updatedAt" to request.updatedAt,
            )
        )
    }

    override fun observeShareRequest(ownerId: String, onUpdate: (ShareRequest?) -> Unit): Subscription =
        observe(root.child("shareRequests").child(ownerId)) { snapshot ->
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
        root.child("pickups").child(status.targetMemberId).setValue(
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
        observe(root.child("pickups").child(memberId)) { snapshot ->
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

    override fun createGroup(name: String, ownerId: String): String {
        val ref = root.child("finderGroups").push()
        ref.setValue(
            mapOf(
                "name" to name,
                "ownerId" to ownerId,
                "members" to mapOf(ownerId to groupMemberValue(GroupMember(ownerId, InviteStatus.ACCEPTED, SharePermission.OFF, 0L))),
            )
        )
        return ref.key!!
    }

    override fun inviteToGroup(groupId: String, accountId: String) {
        setGroupMember(groupId, GroupMember(accountId, InviteStatus.PENDING, SharePermission.OFF, 0L))
    }

    override fun setGroupMember(groupId: String, member: GroupMember) {
        root.child("finderGroups").child(groupId).child("members").child(member.accountId).setValue(groupMemberValue(member))
    }

    private fun groupMemberValue(member: GroupMember) = mapOf(
        "status" to member.status.name,
        "share" to member.share.name,
        "shareUntil" to member.shareUntil,
    )

    override fun observeGroups(onUpdate: (List<FinderGroup>) -> Unit): Subscription =
        observe(root.child("finderGroups")) { snapshot ->
            onUpdate(
                snapshot.children.mapNotNull { group ->
                    val id = group.key ?: return@mapNotNull null
                    FinderGroup(
                        id = id,
                        name = group.string("name"),
                        ownerId = group.string("ownerId"),
                        members = group.child("members").children.mapNotNull { m ->
                            GroupMember(
                                accountId = m.key ?: return@mapNotNull null,
                                status = m.enum<InviteStatus>("status") ?: return@mapNotNull null,
                                share = m.enum<SharePermission>("share") ?: SharePermission.OFF,
                                shareUntil = m.long("shareUntil") ?: 0L,
                            )
                        },
                    )
                }
            )
        }

    override fun resetDemo() {
        root.setValue(DemoSeed.build(System.currentTimeMillis()))
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
        const val DEMO_SPACE_ID = "demo-family"
    }
}
