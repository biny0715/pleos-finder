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
            val state = snapshot.child("state").getValue(String::class.java)
                ?.let { name -> PickupStatus.State.entries.firstOrNull { it.name == name } }
                ?: return@observe
            onUpdate(
                PickupStatus(
                    targetMemberId = memberId,
                    driverName = snapshot.child("driverName").getValue(String::class.java) ?: "",
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

    companion object {
        private const val TAG = "FinderRepository"
        const val DATABASE_URL = "https://pleos-finder-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val DEMO_GROUP_ID = "demo-family"
    }
}
