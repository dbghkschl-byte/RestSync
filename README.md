# RestSync — SYNCLAB

목표 도착 시각과 내비 예상 주행 시간을 바탕으로 휴게소와 휴식 시간을 계획하고, 출발 알림 후 기본 내비 안내로 복귀하는 Pleos Connect 앱입니다.

## 제출 APK

- 소스와 APK 버전: **0.17.0 / versionCode 17**.
- 위치: [apk/SYNCLAB_RestSync_release.apk](apk/SYNCLAB_RestSync_release.apk).
- Android Studio에서 팀의 기존 서명 키로 생성한 **release APK**입니다.
- APK SHA-256: `4efcfdca77f23d8152791e62672120d3d9afb1f62fd9aa286a6e3a3c2c63254a`.
- 서명 검증 통과. 이전 v16 APK와 같은 서명 인증서임을 확인했습니다.
- Android 디버그 플래그가 꺼진 release 패키지이며 debug용 자동 클릭 시작 버튼은 표시하지 않습니다.

## 실행 방법

1. Pleos Connect 에뮬레이터에서 RestSync를 실행하고 목적지를 검색·선택합니다.
2. Pleos 내비에 목적지를 등록하고 기본 내비로 전환합니다.
3. RestSync를 다시 열어 목적지를 다시 설정하거나 **휴식 · 휴게소 찾아줘**를 선택합니다.
4. 실제 휴게소 후보를 확인해 경유지로 등록합니다.
5. 휴게소 도착을 직접 확인하고 휴식 시간·정차·P단 확인을 거쳐 휴식을 시작합니다.
6. 휴식 중 예상 주행 시간을 갱신하며 도착 목표에 맞춰 출발 알림을 표시합니다.
7. 완료 화면에서 기본 내비 안내를 계속합니다.

## 테스트 환경과 필요한 설정

| 항목 | 개발 환경 |
|---|---|
| Pleos Connect 에뮬레이터 | v2.1.0 |
| Android | API 34 / Automotive / x86_64 |
| 화면 | 2560 × 1440 / 160 dpi |
| NaviHelper | 3.0.5 |
| SpeechToText / TextToSpeech | 3.3.1 / 3.3.1 |
| FusedLocation | 1.1.3 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 28 |
| Gradle / AGP / JDK | 9.6.0 / 9.4.0 / 25 |

- Android Studio에서 저장소 루트를 열고 SDK 35 설치 후 Gradle Sync를 실행합니다.
- 인터넷과 동작 중인 Pleos 기본 내비가 필요합니다. 목적지 검색은 Photon/OpenStreetMap을 사용합니다.
- 실제 휴게소 경로 비교에는 설정의 **휴게소 검색 설정 · 저장된 키 관리**에서 유효한 카카오 REST API 키를 입력해야 합니다. 키는 저장소에 포함하지 않습니다.
- 출발 알림을 위해 알림을 허용합니다. 위치·차량 속도 권한은 앱이 일반 권한 요청 흐름으로 요청하며, 거부 시 사용자가 직접 확인하는 흐름을 유지합니다.
- 창 배치는 차량 OS 정책을 따릅니다. 오버레이 권한 강제 변경이나 비공개 API는 사용하지 않습니다.

## 공개 SDK·Gleo 연동

- 운영사무국 공지에 따라 공모전 참가팀의 Gleo App Schema 별도 검토·승인은 요구하지 않습니다. 실제 동작은 에뮬레이터의 Gleo 서비스 및 설정에 의존합니다.
- App Schema: app/src/main/assets/schema.json. 명령은 FIND_REST_STOP, SELECT_REST_STOP, START_REST, CHECK_REST_TIME, EXTEND_REST, END_REST입니다.
- Gleo 명령은 실제 검색·경유지 확인·휴식 기능으로 연결하고, 문서 규격에 맞는 Callback을 반환합니다. 검색·경유지 등록 성공은 결과 확인 후에만 응답합니다.
- 앱 마이크 버튼은 공식 Pleos SpeechToText의 ON_DEVICE 모드만 사용합니다. Android RecognizerIntent 및 외부 음성 인식 서비스는 호출하지 않습니다. 이 경로는 정해진 앱 명령을 해석하는 기능이며 Gleo LLM 대화 기능은 아닙니다.
- 음성 안내는 공식 Pleos TextToSpeech의 ON_DEVICE 모드를 사용합니다. STT/TTS 서버용 자격 증명은 필요하지 않습니다. 서비스·권한을 사용할 수 없으면 화면 버튼과 출발 알림으로 이용합니다.
- FusedLocation은 앱이 열려 있을 때 선택한 휴게소 근처 도착을 안내합니다. 위치가 없거나 오래된 경우 안내하지 않습니다. 근접 판정만으로 도착·주차·휴식 시작을 확정하지 않습니다.
- 속도·기어는 Pleos 공개 API 정책에 안내된 AAOS CarPropertyManager로 읽기만 합니다. 이동 중 또는 P단 아님이 확인되면 휴식 시작을 차단합니다. 신호를 확인할 수 없으면 자동 확인으로 표시하지 않고 사용자가 직접 정차·P단을 확인합니다.
- 문서에 공개되지 않은 LLM SDK 및 차량 제어 기능은 포함하지 않습니다.

## 빌드와 release APK 교체

Android Studio: **Build → Generate Signed App Bundle or APK → APK → 기존 팀 키 선택 → release**.

생성한 APK를 SYNCLAB_RestSync_release.apk로 이름 변경한 뒤 apk/ 폴더의 이전 파일을 교체합니다. 키 저장소(.jks), 비밀번호, local.properties는 올리지 않습니다.

명령줄 기본 빌드/테스트:

    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

assembleRelease만 실행하면 서명되지 않은 APK가 만들어질 수 있습니다. 제출용은 팀 키로 서명한 실행 가능한 release APK를 사용하세요.

## 구현과 검증 범위

- v17: 전체 Kotlin 컴파일, Android Studio debug APK 빌드, 주차 신호·위치 유효성·Gleo JSON 규격·음성 문장·교통 정체에 따른 휴식 단축에 관한 JVM 테스트 7개를 확인했습니다.
- v17에서 새로 추가한 Gleo 실제 발화, 차량 마이크 STT/TTS, 위치·차량 신호의 에뮬레이터 실동작은 아직 검증 전입니다. 소스 구현이나 빌드 성공을 실제 음성 연동 검증으로 표기하지 않습니다.
- 기존 전체 클릭 시연은 v16 개발 빌드 기준입니다. 발표 영상의 Gleo 대화·자막은 가상 대화이며 새 음성 연동의 실행 증거가 아닙니다.
- 정체 +20분은 명시적 시연 값입니다. 실제 교통 갱신은 NaviHelper 예상 주행 시간으로 계산합니다.
- PC ADB 시연 실행기는 tools/restsync_click_runner/에 있으며 debug 전용입니다.
- v17 release APK는 생성·버전·서명·패키지 규격을 확인했습니다. 별도 설치 실행과 신규 음성·위치·차량 신호의 에뮬레이터 실동작은 아직 검증 전입니다.

## 데이터 출처와 SDK 문서

- [전국휴게소정보표준데이터](https://www.data.go.kr/data/15025446/standard.do)
- [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [Gleo Callback 규격](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [SpeechToText](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [TextToSpeech](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)
- [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)
- [AAOS와 Pleos API 선택 기준](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)
