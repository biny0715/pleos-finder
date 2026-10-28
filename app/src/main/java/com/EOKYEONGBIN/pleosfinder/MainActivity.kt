package com.EOKYEONGBIN.pleosfinder

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.EOKYEONGBIN.pleosfinder.core.DemoGroup
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository
import com.EOKYEONGBIN.pleosfinder.pickup.PickupController
import com.EOKYEONGBIN.pleosfinder.pickup.RepositoryMemberLocationSource

class MainActivity : AppCompatActivity() {

    private lateinit var naviHelper: NaviHelper
    private lateinit var pickupController: PickupController
    private lateinit var statusText: TextView
    private lateinit var actionButtons: List<Button>

    // NaviHelper 콜백은 메인 스레드가 아닐 수 있어서 화면 갱신은 showStatus 에서 UI 스레드로 넘긴다.
    private val naviEventListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = showStatus(
            "차량 위치: ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude}\n${currentLocationInfo.address ?: ""}"
        )
        override fun onError(naviErrorCode: NaviErrorCode) = showStatus("오류: $naviErrorCode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startPickupButton = findViewById<Button>(R.id.startPickupButton)
        val stopPickupButton = findViewById<Button>(R.id.stopPickupButton)
        val locationButton = findViewById<Button>(R.id.locationButton)
        actionButtons = listOf(startPickupButton, stopPickupButton, locationButton)

        naviHelper = NaviHelper(applicationContext)
        val repository = FirebaseFinderRepository()
        pickupController = PickupController(
            naviHelper = naviHelper,
            locationSource = RepositoryMemberLocationSource(repository),
            driver = DemoGroup.DAD,
            publishStatus = repository::publishPickupStatus,
            onStatus = ::showStatus,
        )
        naviHelper.initialize(
            onConnected = {
                naviHelper.addListener(naviEventListener)
                naviHelper.addListener(pickupController)
                runOnUiThread { actionButtons.forEach { it.isEnabled = true } }
                showStatus("NaviHelper 연결됨")
            },
            onFailed = { throwable -> showStatus("NaviHelper 연결 실패: ${throwable.message}") }
        )

        startPickupButton.setOnClickListener { pickupController.start(DemoGroup.SON) }
        stopPickupButton.setOnClickListener {
            pickupController.stop()
            showStatus("픽업 종료")
        }
        locationButton.setOnClickListener { naviHelper.getCurrentLocationInfo() }
    }

    override fun onDestroy() {
        pickupController.stop()
        naviHelper.removeListener(pickupController)
        naviHelper.removeListener(naviEventListener)
        naviHelper.release()
        super.onDestroy()
    }

    private fun showStatus(message: String) {
        Log.d(TAG, message)
        runOnUiThread { statusText.text = message }
    }

    companion object {
        private const val TAG = "PleosFinder"
    }
}
