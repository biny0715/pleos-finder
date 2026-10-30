plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.EOKYEONGBIN.pleosfinder.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Pleos 공용 스타일(pleos_styles.xml)이 Material 버튼 스타일을 상속한다.
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    // 차량·폰 앱 공용 지도(FinderMap). 앱이 레이아웃에서 MapView 를 바로 쓰므로 api 로 넘긴다.
    api(libs.maplibre.android)
}
