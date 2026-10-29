package com.EOKYEONGBIN.pleosfinder.phone

import com.EOKYEONGBIN.pleosfinder.core.DemoGroup
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository

/** 폰 앱 전역 의존성 */
object FinderServices {
    val repository: FinderRepository by lazy { FirebaseFinderRepository() }

    // 기획상 Pleos 계정으로 로그인하지만, 출품본은 이 폰이 '아들'로 로그인되어 있다고 가정한다.
    val me = DemoGroup.SON

    // 가족 그룹에서 내 위치를 보는 상대(픽업하러 오는 아빠)
    val viewer = DemoGroup.DAD
}
