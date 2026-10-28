# 플레오스 Finder

차와 사람을 잇는 '나의 찾기' — 공유한 사람끼리, 필요한 동안만.

가족이 위치를 공유하면, 운전자는 폰을 만지지 않고 차량 화면에서 상대 위치로 바로 길 안내를 받습니다. 기다리는 사람은 폰으로 차가 어디쯤 오는지 확인합니다.

- 공모 분야: 패밀리·동승자 케어
- 플랫폼: Pleos Connect (차량 앱) + Android (폰 앱)

## APK

| 파일 | 설치 대상 | 역할 |
|---|---|---|
| [`apk/EOKYEONGBIN_PleosFinder_release.apk`](apk/EOKYEONGBIN_PleosFinder_release.apk) | Pleos Connect Emulator | 차량 앱 (심사 대상) |
| [`apk/EOKYEONGBIN_PleosFinderPhone_release.apk`](apk/EOKYEONGBIN_PleosFinderPhone_release.apk) | Android 폰 또는 폰 에뮬레이터 | 픽업 대상(가족)이 쓰는 폰 앱 |

두 APK 모두 release 빌드이며, 같은 Firebase 서버에 연결되어 있어 설치만 하면 바로 서로 통신합니다.

## 현재 구현된 기능 (1차 접수 기준)

| 기능 | 차량 앱 | 폰 앱 |
|---|---|---|
| 픽업 모드: 구성원 위치를 목적지로 자동 길 안내 | ○ | - |
| 구성원이 움직이면 목적지 자동 갱신 (30m 이상 이동, 최소 10초 간격) | ○ | - |
| 남은 거리·도착 예정 시간을 폰으로 전송 | ○ | ○ (표시) |
| 내 위치 공유 (앱을 닫아도 백그라운드에서 전송) | - | ○ |
| "아빠가 픽업하러 오고 있어요" / "도착했어요" 알림 | - | ○ |
| 버튼 하나로 위치 공유 끄기 | - | ○ |

그룹 관리, 앱 자체 지도, Gleo AI 음성 연동, 차량 무전은 2차 PT 심사 전까지 구현할 예정입니다.

## 테스트 환경

| 항목 | 버전 |
|---|---|
| 차량 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64) |
| 폰 | Android Emulator, Medium Phone API 37 (Google Play) |
| 개발 도구 | Android Studio 2026.2.1, Kotlin, Android Gradle Plugin 9.4.1 |
| Pleos SDK | NaviHelper 3.0.5 |
| 서버 | Firebase Realtime Database (asia-southeast1) |

필요한 설정:

- 두 에뮬레이터 모두 인터넷에 연결되어 있어야 합니다.
- 폰 앱 첫 실행 시 위치 권한과 알림 권한을 허용해야 합니다.
- 에뮬레이터에서 폰 위치는 Extended Controls(⋯) → Location 에서 지정합니다.

## 실행 방법 (가족 픽업 시나리오)

1. Pleos Connect Emulator 에 차량 앱을 설치합니다.
   ```
   adb -s <Pleos 에뮬레이터> install apk/EOKYEONGBIN_PleosFinder_release.apk
   ```
2. 폰 에뮬레이터에 폰 앱을 설치합니다.
   ```
   adb -s <폰 에뮬레이터> install apk/EOKYEONGBIN_PleosFinderPhone_release.apk
   ```
3. 폰 앱(Finder)에서 **내 위치 공유 시작**을 누르고 권한을 허용합니다. 시연용으로 이 폰은 '아들'입니다.
4. 차량 앱(PleosFinder)에서 **아들 픽업 시작**을 누릅니다.
   - 차량 내비게이션이 아들의 위치를 목적지("아들")로 길 안내를 시작합니다.
   - 폰에 "아빠가 픽업하러 오고 있어요" 알림과 남은 거리·도착 예정 시간이 표시됩니다.
5. 폰 에뮬레이터의 위치를 다른 곳으로 바꾸면, 차량 내비게이션의 목적지와 경로가 새 위치로 갱신됩니다.
6. 차량 앱에서 **픽업 종료**를 누르면 길 안내가 끝나고 폰의 픽업 표시가 사라집니다.

> Pleos Connect Emulator 의 기본 내비게이션은 지도 바탕(도로·건물)을 그리지 않는 빈 스타일(`connect_empty`)을 사용합니다. 경로선, 회전 안내, 남은 거리·시간은 정상 표시됩니다.

## 프로젝트 구조

```
app/    차량 앱 (Pleos Connect). 픽업 모드: pickup/PickupController.kt
phone/  폰 앱. 위치 공유 서비스: LocationShareService.kt
core/   두 앱이 함께 쓰는 데이터 모델과 Firebase 연동 (FirebaseFinderRepository.kt)
apk/    제출용 release APK
```

서버 데이터 구조 (구성원별 최신 값만 저장하고 이동 기록은 남기지 않습니다):

```
groups/{groupId}/locations/{memberId}  위도, 경도, 갱신 시각
groups/{groupId}/pickups/{memberId}    운전자, 상태, 남은 거리·시간, 갱신 시각
```

## 소스에서 빌드하기

보안을 위해 Firebase 설정 파일과 서명 키는 저장소에 포함하지 않았습니다. 소스로 직접 빌드하려면:

1. 본인 Firebase 프로젝트에서 Realtime Database 를 만들고, Android 앱 두 개(`com.EOKYEONGBIN.pleosfinder`, `com.EOKYEONGBIN.pleosfinder.phone`)를 등록합니다.
2. 내려받은 `google-services.json` 을 `app/` 과 `phone/` 에 각각 넣습니다.
3. `core/src/main/java/com/EOKYEONGBIN/pleosfinder/core/FirebaseFinderRepository.kt` 의 `DATABASE_URL` 을 본인 데이터베이스 주소로 바꿉니다.
4. 디버그 빌드: `./gradlew :app:assembleDebug :phone:assembleDebug`
