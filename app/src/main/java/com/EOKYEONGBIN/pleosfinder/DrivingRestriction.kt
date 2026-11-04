package com.EOKYEONGBIN.pleosfinder

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/**
 * 운전 중 화면 제한. AAOS 의 CarUxRestrictions 로 지금 주행 중(주의 분산을 줄여야 하는 상태)인지 받는다.
 * 기획서 7장: 그룹 만들기·초대·이름 변경처럼 입력이 필요한 작업과 운전자 전환은 정차 중에만 한다.
 * Car 서비스가 없는 환경에서는 항상 정차 중으로 본다.
 */
class DrivingRestriction(context: Context, private val onChanged: () -> Unit) {

    var isDriving = false
        private set

    private var car: Car? = null

    init {
        try {
            val car = Car.createCar(context.applicationContext)
            this.car = car
            val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (manager != null) {
                update(manager.currentCarUxRestrictions)
                manager.registerListener { update(it) }
            }
        } catch (e: Throwable) {
            // Car 라이브러리가 없는 기기(NoClassDefFoundError 등)
            Log.w(TAG, "주행 상태를 받을 수 없어 정차 중으로 봅니다: ${e.message}")
        }
    }

    private fun update(restrictions: CarUxRestrictions?) {
        val driving = restrictions?.isRequiresDistractionOptimization == true
        if (driving == isDriving) return
        isDriving = driving
        onChanged()
    }

    private companion object {
        const val TAG = "DrivingRestriction"
    }
}
