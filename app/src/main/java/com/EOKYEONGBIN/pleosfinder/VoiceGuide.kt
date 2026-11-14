package com.EOKYEONGBIN.pleosfinder

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 짧은 음성 안내. 차량의 기본 음성 합성 엔진(TextToSpeech)으로 읽는다.
 * Gleo 로 시작한 요청은 Gleo 가 답을 읽으므로 여기서는 화면에서 시작한 안내만 읽는다.
 * 엔진이 없거나 준비 전이면 조용히 건너뛴다(화면 팝업은 그대로 뜬다).
 */
class VoiceGuide(context: Context) {

    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            pending?.let(::speak)
        } else {
            Log.w(TAG, "음성 합성 엔진을 쓸 수 없어요: $status")
        }
        pending = null
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "finder-guide")
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}
