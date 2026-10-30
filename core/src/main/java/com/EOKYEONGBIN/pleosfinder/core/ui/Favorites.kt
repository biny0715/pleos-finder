package com.EOKYEONGBIN.pleosfinder.core.ui

import android.content.Context

/**
 * 즐겨찾기. 사람마다(차량은 지금 운전자, 폰은 로그인한 사람) 따로 이 기기에 저장한다.
 * 키는 "person:<계정 id>" 또는 "group:<그룹 id>".
 */
class Favorites(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun contains(ownerId: String, key: String): Boolean = key in keys(ownerId)

    /** 바꾼 뒤 즐겨찾기 상태를 돌려준다. */
    fun toggle(ownerId: String, key: String): Boolean {
        val keys = keys(ownerId).toMutableSet()
        val added = if (key in keys) !keys.remove(key) else keys.add(key)
        prefs.edit().putStringSet(ownerId, keys).apply()
        return added
    }

    private fun keys(ownerId: String): Set<String> = prefs.getStringSet(ownerId, emptySet()) ?: emptySet()

    companion object {
        fun person(id: String) = "person:$id"
        fun group(id: String) = "group:$id"

        /** 한 패널에 처음 보여 주는 줄 수. 더 있으면 '더 보기'로 펼친다. */
        const val MAX_ROWS = 4
    }
}
