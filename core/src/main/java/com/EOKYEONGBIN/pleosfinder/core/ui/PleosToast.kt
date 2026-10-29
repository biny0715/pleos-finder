package com.EOKYEONGBIN.pleosfinder.core.ui

import android.view.View
import android.widget.TextView

/**
 * Pleos 토스트: 앱 패널 하단 가운데에 잠깐 떴다가 사라진다.
 * 레이아웃에 Widget.Pleos.Toast 스타일의 TextView(visibility=gone)를 두고 넘겨준다.
 */
class PleosToast(private val view: TextView) {

    private val hide = Runnable {
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { view.visibility = View.GONE }
    }

    fun show(message: CharSequence) {
        view.removeCallbacks(hide)
        view.animate().cancel()
        view.text = message
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(FADE_MS)
        view.postDelayed(hide, SHOW_MS)
    }

    private companion object {
        // ivi_motion_duration_medium1 = 250ms. 표시 시간은 문서에 없어 Android 토스트(짧게) 수준으로 둔다.
        const val FADE_MS = 250L
        const val SHOW_MS = 3_000L
    }
}
