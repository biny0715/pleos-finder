# 플레오스 Finder

차와 사람을 잇는 '나의 찾기' — 공유한 사람끼리, 필요한 동안만.

가족이 위치를 공유하면, 운전자는 폰을 만지지 않고 차량 화면에서 상대 위치로 바로 길 안내를 받습니다. 기다리는 사람은 폰으로 차가 어디쯤 오는지 확인합니다.

- 공모 분야: 패밀리·동승자 케어
- 플랫폼: Pleos Connect (차량 앱) + Android (폰 앱)
- 기획서: [docs/기획서.md](docs/기획서.md)

## APK

| 파일 | 설치 대상 | 역할 |
|---|---|---|
| [`apk/EOKYEONGBIN_PleosFinder_release.apk`](apk/EOKYEONGBIN_PleosFinder_release.apk) | Pleos Connect Emulator | 차량 앱 (심사 대상) |
| [`apk/EOKYEONGBIN_PleosFinderPhone_release.apk`](apk/EOKYEONGBIN_PleosFinderPhone_release.apk) | Android 폰 또는 폰 에뮬레이터 | 픽업 대상(가족)이 쓰는 폰 앱 |

두 APK 모두 release 빌드이며, 같은 Firebase 서버에 연결되어 있어 설치만 하면 바로 서로 통신합니다.

## 현재 구현된 기능 (1차 접수 기준)

| 기능 | 차량 앱 | 폰 앱 |
|---|---|---|
| 운전자(계정) 전환: 오른쪽 위 프로필에서 아빠 ↔ 엄마 | ○ | - |
| 가족·친구·그룹 패널, 이름 옆 사람·차량 아이콘 | ○ | - |
| 항목을 누르면 펼쳐지는 '픽업' / '차로 길 안내' 버튼 | ○ | - |
| 연락처에서 가족·친구 초대, 받은 초대 수락·거절 | ○ | ○ |
| 상대별 위치 공유 허용: 항상 허용 / 일시적 허용 / 끄기 | ○ (상태 표시) | ○ (선택) |
| 픽업: 항상 허용이면 바로 길 안내, 일시적 허용이면 폰에 공유 요청 | ○ | ○ |
| 구성원이 움직이면 목적지 자동 갱신, 차 50m 안이면 자동 완료 | ○ | ○ (도착 알림) |
| 그룹 만들기·연락처 초대, 그룹별 공유(일시적 = 30분 단위 +30분) | ○ | 다음 단계 |
| 차량 위치는 지금 운전하는 사람의 설정으로만 공유 | ○ | - |
| Gleo AI 음성 명령: 픽업 시작·종료, 구성원 위치 묻기 | ○ | - |

계정은 기획상 Pleos 계정으로 로그인하지만, 출품본은 차량 앱 = 아빠 또는 엄마(프로필에서 전환), 폰 앱 = 아들로 로그인되어 있다고 가정합니다. 연락처는 시연용 고정 목록(아빠, 엄마, 아들, 친구 김민수, 직장 동료 박지훈, 친구 이서연)입니다. 지도, 폰 앱의 그룹 화면, 차량 무전은 2차 PT 심사 전까지 구현할 예정입니다.

## 디자인

- [Pleos Connect 디자인 시스템](https://document.pleos.ai/en/docs/connect/guide/docs-design/Introduction/intro)의 색상 토큰(주간·야간), 글꼴(Asta Sans), 버튼·세그먼트·토스트 규격을 `core/src/main/res`에 옮겨 두 앱이 함께 씁니다.
- 차량 앱은 운전 중에도 쓰는 화면이라 `distractionOptimized`로 등록했습니다. 주행(D단) 중에도 화면이 가려지지 않는 것을 에뮬레이터에서 확인했습니다.
- 폰 앱에 넣은 Asta Sans 글꼴은 SIL Open Font License 1.1을 따릅니다. 라이선스 전문은 `phone/src/main/assets/licenses/AstaSans-OFL.txt`에 있습니다.

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
3. 차량 앱 오른쪽 위 프로필 → **시연 데이터 초기화**를 누릅니다. 가족(엄마), 친구(김민수: 사람+차량, 박지훈: 차량만), 그룹(주말 캠핑)이 준비됩니다.
4. 가족 패널의 **+ 가족 초대** → **아들**을 고릅니다.
5. 폰 앱(Finder, 아들로 로그인)에 "아빠가 가족으로 초대했어요" 카드가 뜹니다. **일시적 허용으로 수락 (픽업 때만)**을 누르고 위치·알림 권한을 허용합니다.
6. 차량 앱에서 **아들**을 누르면 아래에 **픽업** 버튼이 펼쳐집니다. 누르면 바로 픽업이 시작됩니다.
   - 일시적 허용이므로 폰에 "아빠가 픽업하러 가려고 해요" 알림이 뜹니다. **공유하기**를 누르면 차량 내비게이션이 아들의 위치로 길 안내를 시작합니다.
   - 폰 위치를 바꾸면 경로가 갱신되고, 차량 위치 근처(위도 37.41275, 경도 127.09470)로 옮기면 픽업이 자동 완료됩니다.
7. 친구 패널에서 **김민수**를 누르면 **픽업**과 **차로 길 안내**가, **박지훈**(차량만)을 누르면 **차로 길 안내**가 나옵니다.
8. 그룹 패널의 **주말 캠핑**을 누르면 오른쪽에 구성원과 내 공유 설정이 나옵니다. **+30분**을 누를 때마다 공유 시간이 30분씩 늘어납니다.
9. 프로필에서 **엄마(으)로 전환**하면 목록이 엄마 기준으로 바뀌고, 아이오닉5의 위치는 엄마의 설정으로만 공유됩니다(아빠 그룹에는 보이지 않음).

> Pleos Connect Emulator 의 기본 내비게이션은 지도 바탕(도로·건물)을 그리지 않는 빈 스타일(`connect_empty`)을 사용합니다. 경로선, 회전 안내, 남은 거리·시간은 정상 표시됩니다.
>
> 에뮬레이터의 차량 위치는 고정(성남시 수정구 시흥동)이라 차가 경로를 따라 이동하지 않습니다. 그래서 시연에서는 구성원이 차 쪽으로 이동하는 방식으로 도착을 보여 줍니다. 실차에서는 내비게이션의 목적지 도착 이벤트로도 픽업이 완료됩니다.

## 프로젝트 구조

```
app/    차량 앱 (Pleos Connect). 앱 전체 픽업 상태: VehicleSession.kt, Gleo 음성 명령: gleo/,
        허용 방식별 픽업 시작: pickup/PickupRequester.kt, 길 안내·도착: pickup/PickupController.kt
phone/  폰 앱. 허용 방식에 따른 위치 공유·요청 알림: LocationShareService.kt
core/   두 앱이 함께 쓰는 데이터 모델과 Firebase 연동 (FirebaseFinderRepository.kt)
apk/    제출용 release APK
```

서버 데이터 구조 (구성원별 최신 값만 저장하고 이동 기록은 남기지 않습니다):

```
groups/{groupId}/locations/{memberId}                위도, 경도, 갱신 시각
groups/{groupId}/invites/{memberId}                  초대한 사람, 상태(대기·수락·거절)
groups/{groupId}/permissions/{ownerId}/{viewerId}    항상 허용 / 일시적 허용 / 끄기
groups/{groupId}/shareRequests/{ownerId}             요청한 사람, 상태(대기·수락·거절·종료)
groups/{groupId}/pickups/{memberId}                  운전자, 상태, 남은 거리·시간, 차량 위치, 갱신 시각
```

## 소스에서 빌드하기

보안을 위해 Firebase 설정 파일과 서명 키는 저장소에 포함하지 않았습니다. 소스로 직접 빌드하려면:

1. 본인 Firebase 프로젝트에서 Realtime Database 를 만들고, Android 앱 두 개(`com.EOKYEONGBIN.pleosfinder`, `com.EOKYEONGBIN.pleosfinder.phone`)를 등록합니다.
2. 내려받은 `google-services.json` 을 `app/` 과 `phone/` 에 각각 넣습니다.
3. `core/src/main/java/com/EOKYEONGBIN/pleosfinder/core/FirebaseFinderRepository.kt` 의 `DATABASE_URL` 을 본인 데이터베이스 주소로 바꿉니다.
4. 디버그 빌드: `./gradlew :app:assembleDebug :phone:assembleDebug`
