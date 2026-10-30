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
| 가족·친구·그룹 패널, 이름 옆 사람·차량 아이콘 | ○ | ○ |
| 항목을 누르면 펼쳐지는 '픽업'(사람) / '위치 공유'(차량) 버튼 | ○ | - |
| 차량끼리 위치 공유: 오늘 하루만 또는 30분 단위 최대 24시간 | ○ | - |
| 화면: 기본 한 칸, 그룹을 열면 두 칸, 픽업 중에만 맨 위 픽업 줄 | ○ | - |
| 연락처에서 가족·친구 초대, 받은 초대 수락·거절 | ○ | ○ |
| 상대별 위치 공유 허용: 항상 허용 / 일시적 허용 / 끄기 | ○ (상태 표시) | ○ (사람을 누르면 아래에서 선택) |
| 픽업: 항상 허용이면 바로 길 안내, 일시적 허용이면 폰에 공유 요청 | ○ | ○ |
| 구성원이 움직이면 목적지 자동 갱신, 차 50m 안이면 자동 완료 | ○ | ○ (도착 알림) |
| 그룹 만들기·연락처 초대, 그룹별 공유(일시적 = 30분 단위 +30분) | ○ | ○ (초대 참여, 그룹 상세, 그룹별 공유) |
| 그룹 이름 변경(그룹을 만든 사람) | ○ | ○ |
| 지도: 나에게 공유된 사람·차량 마커, 픽업 중 차와 나를 잇는 선 | 예정 | ○ |
| 차량 위치는 지금 운전하는 사람의 설정으로만 공유 | ○ | ○ (운전 중인 가족·친구 줄에 차량 표시) |
| Gleo AI 음성 명령: 픽업, 사람·그룹에 시간 제한 위치 공유, 공유 끄기 | ○ | - |

호칭은 보는 사람 기준입니다(아빠에게 엄마는 '와이프', 엄마에게 아빠는 '남편'). 계정은 기획상 Pleos 계정으로 로그인하지만, 출품본은 차량 앱 = 아빠 또는 엄마(프로필에서 전환), 폰 앱 = 아들로 로그인되어 있다고 가정합니다. 연락처는 시연용 고정 목록(아빠, 엄마, 아들, 친구 김민수, 직장 동료 박지훈, 친구 이서연)입니다. 폰 앱 지도는 구현했고, 차량 앱 지도와 차량 무전은 2차 PT 심사 전까지 구현할 예정입니다.

## Gleo AI 음성 연동

차량 앱은 `app/src/main/assets/schema.json`으로 기능 3개(Gleo 최대 개수)를 등록합니다. Pleos Connect Emulator의 Gleo AI가 설치 즉시 이 파일을 읽는 것을 로그로 확인했습니다(`functionCount=3`).

| 기능 | 예시 발화 | 동작 |
|---|---|---|
| `PICKUP(action, memberName)` | "와이프 픽업 가줘", "픽업 그만할래", "아들 지금 어디쯤이야?" | 픽업 시작·종료, 남은 거리·시간 답변 |
| `SHARE_LOCATION(targetName, durationMinutes, untilEndOfToday)` | "박지훈한테 1시간만 위치 공유해줘", "주말 캠핑 그룹에 2시간만 공유해줘" | 사람이면 차량끼리 서로 공유, 그룹이면 그룹에 일시적 공유(30분 단위, 최대 24시간) |
| `STOP_LOCATION_SHARING(targetName, category)` | "친구 목록 중에 위치 공유된 것 모두 꺼줘" | 한 사람·그룹, 또는 가족·친구·그룹·전체에서 내가 켠 공유를 끔 |

- 세 기능 모두 화면 없이 처리하는 `service` 방식입니다(`gleo/GleoActionReceiver.kt`). Pleos 문서 예시는 인텐트 action이 함수 이름(`PICKUP`)이지만, 에뮬레이터의 Gleo AI(ambientai 4.2)는 `com.EOKYEONGBIN.pleosfinder.PICKUP`처럼 패키지 이름을 붙여 보내므로 두 형식을 모두 받습니다. 결과는 Gleo Callback 규격(`success` / `asking` / `fail`)으로 7초 안에 돌려줍니다.
- 이름은 운전자가 부르는 호칭(와이프·남편·아들)과 계정 이름으로 찾고, 모르는 이름이나 빠진 시간은 `asking`으로 되묻습니다.
- 설치 직후에는 Android가 앱을 '중지됨' 상태로 두어 Gleo 호출을 받지 못하므로, 차량 앱을 한 번 열어 주세요.
- 음성 없이 시험하려면 Gleo가 보내는 것과 같은 신호를 보냅니다.
  ```
  adb -s <Pleos 에뮬레이터> shell am broadcast --user current -p com.EOKYEONGBIN.pleosfinder -a com.EOKYEONGBIN.pleosfinder.PICKUP --es ACTION_PARAM '{"action":"start","memberName":"와이프"}'
  adb -s <Pleos 에뮬레이터> shell am broadcast --user current -p com.EOKYEONGBIN.pleosfinder -a com.EOKYEONGBIN.pleosfinder.SHARE_LOCATION --es ACTION_PARAM '{"targetName":"주말 캠핑","durationMinutes":120}'
  ```

## 디자인

- [Pleos Connect 디자인 시스템](https://document.pleos.ai/en/docs/connect/guide/docs-design/Introduction/intro)의 색상 토큰(주간·야간), 글꼴(Asta Sans), 버튼·세그먼트·토스트 규격을 `core/src/main/res`에 옮겨 두 앱이 함께 씁니다.
- 차량 앱은 운전 중에도 쓰는 화면이라 `distractionOptimized`로 등록했습니다. 주행(D단) 중에도 화면이 가려지지 않는 것을 에뮬레이터에서 확인했습니다.
- 폰 앱에 넣은 Asta Sans 글꼴은 SIL Open Font License 1.1을 따릅니다. 라이선스 전문은 `phone/src/main/assets/licenses/AstaSans-OFL.txt`에 있습니다.
- 폰 앱 지도는 MapLibre Native(BSD 2-Clause)와 OpenFreeMap 지도를 씁니다. 지도 자료는 © OpenStreetMap contributors이며, 지도 화면의 ⓘ 버튼에 출처가 표시됩니다.

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
3. 차량 앱 오른쪽 위 프로필 → **시연 데이터 초기화**를 누릅니다. 가족(와이프·아들), 친구(김민수: 사람+차량, 박지훈: 차량만), 그룹(주말 캠핑)이 준비됩니다.
4. 폰 앱(Finder, 아들로 로그인)을 열고 위치·알림 권한을 허용합니다. 차량 앱과 같이 가족(아빠·엄마), 친구(이서연), 그룹(주말 캠핑 초대: 참여·거절) 패널이 보입니다. 사람을 누르면 그 사람에게 내 위치를 항상 허용 / 일시적 허용 / 끄기 중 무엇으로 줄지 고릅니다. 시연 데이터는 아빠에게 항상 허용, 엄마에게 일시적 허용입니다.
5. 차량 앱에서 **아들**을 누르면 오른쪽에 **픽업** 버튼이 나옵니다. 누르면 바로 픽업이 시작됩니다.
   - 아빠에게는 항상 허용이므로 차량 내비게이션이 곧바로 아들의 위치로 길 안내를 시작하고, 폰에는 "아빠가 오고 있어요"와 남은 거리·시간이 나옵니다.
6. 프로필에서 **엄마(으)로 전환**한 뒤 **아들 → 픽업**을 누르면, 엄마에게는 일시적 허용이므로 폰에 "엄마가 픽업하러 가려고 해요" 알림이 뜹니다. **공유하기**를 누르면 길 안내가 시작됩니다.
   - 폰 위치를 바꾸면 경로가 갱신되고, 차량 위치 근처(위도 37.41275, 경도 127.09470)로 옮기면 픽업이 자동 완료됩니다.
7. 친구 패널에서 **김민수**를 누르면 **픽업**과 **위치 공유**가, **박지훈**(차량만)을 누르면 **위치 공유**가 나옵니다. 위치 공유는 **오늘 하루만 공유** 또는 **시간 설정**(−30분 / +30분, 최대 24시간)으로 정한 시간 동안만 서로 차량 위치를 공유합니다.
8. 그룹 패널의 **주말 캠핑**을 누르면 화면이 두 칸으로 나뉘며 오른쪽에 구성원과 내 공유 설정이 나옵니다(닫기를 누르면 한 칸으로 돌아옴). **+30분**을 누를 때마다 공유 시간이 30분씩 늘어납니다.
9. 엄마로 전환하면 목록이 엄마 기준으로 바뀌고, 아이오닉5의 위치는 엄마의 설정으로만 공유됩니다(아빠 그룹에는 보이지 않음).

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
