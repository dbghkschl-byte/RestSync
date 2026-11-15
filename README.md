# RestSync — SYNCLAB

목표 도착 시각과 내비 예상 주행 시간을 바탕으로 휴게소와 휴식 시간을 계획하고, 출발 알림 후 기본 내비 안내로 복귀하는 Pleos Connect 앱입니다.

## 제출 APK

- 위치: [`apk/SYNCLAB_RestSync_release.apk`](apk/SYNCLAB_RestSync_release.apk)
- 패키지: `com.restsync.demo`
- 버전: `0.16.0` / versionCode `16`
- Android Studio의 `release` 빌드로 생성하고 서명한 APK입니다.
- SHA-256: `cface7101cf566b81114bc723c53fae5d170e56a8964410ace4f5fe6f536986d`

## 실행 방법

1. Pleos Connect 에뮬레이터에 제출 APK를 설치하고 RestSync를 실행합니다.
2. 목적지를 검색·선택하면 Pleos 기본 내비에 목적지 등록을 요청하고 내비 화면으로 전환합니다.
3. RestSync를 다시 열어 목적지를 다시 설정하거나 **휴식 · 휴게소 찾아줘**를 선택합니다.
4. 실제 휴게소 후보를 선택해 경유지를 등록하고 기본 내비로 돌아갑니다.
5. 휴게소에 도착하면 RestSync에서 도착을 수동 확인하고, 휴식 시간과 정차·P단 확인을 거쳐 휴식을 시작합니다.
6. 휴식 중 예상 주행 시간을 확인하며, 출발 알림을 확인하고 완료 화면에서 내비 안내를 계속합니다.

도착·P단은 사용자가 직접 확인하며 실제 기어를 자동 판별하지 않습니다. 수동 도착 확인은 에뮬레이터 GPS 위치를 이동시키지 않습니다.

## 테스트 환경과 필요한 설정

| 항목 | 개발·시연 환경 |
|---|---|
| Pleos Connect 이미지 | **v2.1.0** |
| Android | **API 34**, Automotive / x86_64 |
| 화면 규격 | **2560 × 1440**, density **160 dpi** |
| Pleos SDK | `ai.pleos.playground:NaviHelper:3.0.5` |
| compileSdk / targetSdk / minSdk | 35 / 34 / 28 |
| Gradle / Android Gradle Plugin | 9.6.0 / 9.4.0 |
| Gradle daemon JDK | 25 (`gradle/gradle-daemon-jvm.properties`) |

- 인터넷 연결과 동작 중인 Pleos 기본 내비가 필요합니다.
- 목적지 검색은 Photon/OpenStreetMap을 사용합니다.
- 실제 휴게소 비교에는 앱 설정의 **휴게소 검색 설정 · 저장된 키 관리**에서 유효한 카카오 REST API 키를 저장해야 합니다. 필요한 카카오 서비스 사용 설정도 활성화해야 합니다.
- 개인 API 키는 소스 및 APK에 포함하지 않았습니다. 심사 환경에서는 별도로 설정해야 합니다.
- 출발 알림을 사용하려면 RestSync 알림을 허용합니다. 차량 OS의 창 크기와 화면 배치는 시스템 정책을 따릅니다.
- 오버레이 권한을 강제로 변경하는 절차는 필요하지 않습니다.

## 소스 빌드

이 저장소 루트를 Android Studio에서 열고 SDK 35를 설치한 뒤 Gradle Sync를 실행합니다. Gradle wrapper 및 JDK 설정은 저장소에 포함되어 있습니다.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

`assembleRelease`만 실행하면 별도 서명 설정이 없는 환경에서는 unsigned APK가 생성될 수 있습니다. 배포용은 Android Studio의 **Build → Generate Signed App Bundle or APK → APK → release**에서 본인의 서명 키로 생성합니다. 팀 서명 키와 비밀번호는 공개하지 않습니다.

## 시연과 실제 기능의 구분

- 실제 목적지 검색, Pleos 목적지·경유지 등록/확인, 휴식 타이머, 알림 및 내비 복귀는 앱의 실제 흐름을 사용합니다.
- **정체 +20분**은 명시적인 가상 교통 상황입니다. 녹화 시연에서는 도착 목표 조정과 시간 가속을 사용합니다.
- Gleo와 대화하는 발표 영상의 음성·자막은 **가상 대화**이며 실제 Gleo 음성 인식·명령 연동을 뜻하지 않습니다. 기능은 앱 버튼으로 실행됩니다.
- PC ADB 자동 클릭 실행기는 `tools/restsync_click_runner/`에 있습니다. 이 기능은 개발용 에뮬레이터 및 **debug 빌드 전용**이고, 제출 release APK에는 자동 클릭 시작 버튼을 표시하지 않습니다.
- SDK가 경유지 좌표를 0,0으로 반환하는 경우 등록 이름과 최종 목적지로 확인합니다. 좌표 일치 검증으로 표시하지 않습니다.

## 확인한 범위

- 개발 빌드에서 실제 Pleos 연동 자동 클릭 전체 흐름: 목적지 설정·재설정 → 휴게소·경유지 선택 → 도착·정차 확인 → 휴식 30분 → 35분 연장 → 정체 반영 약15분 → 알림·완료·지도 복귀.
- 제출 release APK: Android Studio Gradle 빌드 성공 및 APK 서명 검사 통과.
- release APK의 별도 설치 실행 검증은 아직 완료되지 않았으며, 위 전체 흐름의 실행 검증은 개발 빌드 기준입니다.

## 데이터 출처

휴게소 데이터는 [공공데이터포털 전국휴게소정보표준데이터](https://www.data.go.kr/data/15025446/standard.do)를 사용하며, 앱 내 데이터와 실제 내비 경로는 상황에 따라 차이가 날 수 있습니다.
