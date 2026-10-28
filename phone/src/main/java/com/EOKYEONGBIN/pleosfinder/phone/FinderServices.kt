package com.EOKYEONGBIN.pleosfinder.phone

import com.EOKYEONGBIN.pleosfinder.core.DemoGroup
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository

/** 폰 앱 전역 의존성 */
object FinderServices {
    val repository: FinderRepository by lazy { FirebaseFinderRepository() }

    // 계정·그룹 기능 전까지는 이 폰을 시연용 '아들'로 고정한다.
    val me = DemoGroup.SON
}
