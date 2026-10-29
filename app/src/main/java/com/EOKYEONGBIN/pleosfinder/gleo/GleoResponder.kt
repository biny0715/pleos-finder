package com.EOKYEONGBIN.pleosfinder.gleo

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo AI 에 Callback 을 보낸다. 형식은 Pleos 'Code Details with Examples' 의 Callback 규격을 따른다.
 * message 는 영어 한 문장(이름 같은 고유명사는 한국어 가능), Gleo 는 7초 안에 응답이 없으면 실패로 본다.
 */
object GleoResponder {

    enum class Status(val value: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

    private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    private const val TAG = "PleosFinderGleo"

    fun respond(context: Context, functionName: String, status: Status, message: String, data: JSONArray? = null) {
        val response = JSONObject().apply {
            put("functionName", functionName)
            put("status", status.value)
            put("message", message)
            data?.let { put("data", it.toString()) }
        }
        context.sendBroadcast(
            Intent(ACTION_RESPONSE).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", response.toString())
            }
        )
        Log.d(TAG, "response $response")
    }
}
