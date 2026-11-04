package com.EOKYEONGBIN.pleosfinder.core

/**
 * 이름 뒤 조사. 받침이 있으면 '남편이 · 남편과 · 남편으로', 없으면 '아빠가 · 아빠와 · 아빠로'.
 * 'ㄹ' 받침 뒤의 '으로'는 '로'(예: 서울로). 한글이 아니면 받침 없음으로 본다.
 */
private fun String.finalConsonant(): Int? {
    val last = lastOrNull() ?: return null
    if (last !in '가'..'힣') return null
    return (last - '가') % 28
}

private fun String.hasFinalConsonant() = (finalConsonant() ?: 0) != 0

/** 이/가 */
val String.iGa: String get() = this + if (hasFinalConsonant()) "이" else "가"

/** 과/와 */
val String.gwaWa: String get() = this + if (hasFinalConsonant()) "과" else "와"

/** 을/를 */
val String.eulReul: String get() = this + if (hasFinalConsonant()) "을" else "를"

/** 으로/로 */
val String.euro: String get() = this + if (hasFinalConsonant() && finalConsonant() != 8) "으로" else "로"
