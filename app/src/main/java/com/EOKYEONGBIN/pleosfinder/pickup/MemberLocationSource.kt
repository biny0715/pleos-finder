package com.EOKYEONGBIN.pleosfinder.pickup

import android.os.Handler
import android.os.Looper
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation

/** 그룹 구성원 위치를 받아 오는 창구. 지금은 가짜 구현, 서버가 붙으면 Firebase 구현으로 교체한다. */
interface MemberLocationSource {
    fun start(member: Member, onUpdate: (MemberLocation) -> Unit)
    fun stop()
}

/** 판교역 앞에서 출발해 동쪽으로 조금씩 걸어가는 구성원을 흉내 낸다. 콜백은 메인 스레드에서 호출된다. */
class FakeMemberLocationSource(
    private val stepIntervalMs: Long = 5_000L,
    private val stepCount: Int = 12,
) : MemberLocationSource {

    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private var tick: Runnable? = null

    override fun start(member: Member, onUpdate: (MemberLocation) -> Unit) {
        stop()
        step = 0
        tick = object : Runnable {
            override fun run() {
                onUpdate(
                    MemberLocation(
                        memberId = member.id,
                        latitude = START_LAT,
                        longitude = START_LNG + step * LNG_PER_STEP,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                if (++step < stepCount) handler.postDelayed(this, stepIntervalMs)
            }
        }.also { handler.post(it) }
    }

    override fun stop() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
    }

    private companion object {
        const val START_LAT = 37.3948
        const val START_LNG = 127.1112
        // 경도 0.0004도 ≈ 35m (위도 37도 기준)
        const val LNG_PER_STEP = 0.0004
    }
}
