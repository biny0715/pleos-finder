package com.EOKYEONGBIN.pleosfinder.phone

import com.EOKYEONGBIN.pleosfinder.core.DemoDirectory
import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.FirebaseFinderRepository

/** 폰 앱 전역 의존성 */
object FinderServices {
    val repository: FinderRepository by lazy { FirebaseFinderRepository() }

    // 기획상 Pleos 계정으로 로그인하지만, 출품본은 이 폰이 '아들'로 로그인되어 있다고 가정한다.
    val me = DemoDirectory.SON
}
