# GlassesScanner 2.4

## 설치 및 사용

- 초기 설정은 위치·주변 기기·알림·다른 앱 위에 표시·배터리 최적화 제외까지 필수 항목 5개를 확인합니다. 모든 항목을 완료하면 앱 화면이 열립니다. 배터리 최적화 제외 상태는 시스템에서 확인하며, 제외가 해제되면 다시 설정해야 합니다.
- 스캔 시작 시 선택한 모드에 필요한 Bluetooth·Wi-Fi·위치 서비스가 꺼져 있으면 **스캔 필수 설정** 알림창에서 항목별 설정을 열 수 있습니다. 통합 관측은 Bluetooth와 Wi-Fi를 모두 확인합니다. 설정 후 **다시 확인하고 스캔 시작**을 누르면 상태를 재검사합니다.
- 백그라운드 스캔 중에는 시간 제한이 있는 CPU 절전 잠금을 갱신하고 종료 시 해제합니다. Android가 같은 부팅 내에서 종료된 서비스를 재시작하면 마지막 모드와 남은 시간을 새 관측 세션으로 복구합니다. 사용자 중지와 정상 완료 시에는 복구 요청을 지웁니다.

- Android Studio에서 프로젝트 루트를 엽니다. Gradle Wrapper 9.4.1, AGP 9.2.1, Android SDK 36.1을 사용합니다. Android 8.0(API 26) 이상에서 실행합니다.
- Windows 터미널에서 `.\gradlew.bat assembleDebug`를 실행하면 `app/build/outputs/apk/debug/app-debug.apk`가 생성됩니다. 기존 설치본을 업데이트할 때는 같은 앱 서명 키가 필요합니다.
- 기본 모드는 **통합 관측 · BLE + Classic + Wi-Fi AP**입니다. 기존 설치에도 한 번 적용되며 이후 선택한 모드는 유지됩니다. 처음 BLE 12초를 관측한 뒤 Classic과 Wi-Fi AP 검색을 진행하고, 이후 BLE 60초와 다른 검색을 반복합니다. 기본 시간은 무제한이며 BLE와 통합 관측에 시간 제한을 설정할 수 있습니다. Wi-Fi 요청 간격은 화면 사용 중 최소 2분, 백그라운드 최소 30분으로 제한합니다. 요청 거절이나 무선 기능 중단 시 나머지 수단으로 관측을 이어갑니다. 개별 BLE, Classic, BLE + Classic, Wi-Fi AP 모드도 설정에서 선택할 수 있습니다.
- 결과를 선택하면 후보 근거와 주소, RSSI, 제조사 ID, 광고 서비스 UUID를 볼 수 있습니다. 태블릿의 넓은 창에서는 결과와 상세를 함께 표시합니다.
- 백그라운드 스캔을 시작하면 알림 목록에 모드, 진행 단계, 경과 시간과 후보 수가 표시됩니다. 진행 알림에서 스캔 화면을 열거나 중지할 수 있습니다. 후보 알림은 스캔 중 새 후보가 처음 관측될 때 기기에서 생성되며 인터넷이 필요하지 않습니다.
- 진행 알림이 차단된 경우 앱에서 알림 설정으로 이동하거나 화면을 보는 동안만 스캔할 수 있습니다. 화면 잠금 중 BLE 수신 범위는 광고 유형과 등록한 필터에 따라 달라집니다. Wi-Fi AP는 Android의 스캔 호출 제한을 따릅니다.
- **관측 이력**에서 최근 세션의 결과와 종료 상태를 확인할 수 있습니다. 기본 보관 범위는 최근 30회이며, 세션마다 최대 100건의 상세를 저장합니다. 2.3의 기존 기록은 원문으로 가져옵니다. **설정**에서 기본 모드·시간, 후보 알림, 테마, 이력 보관 범위, 탐색 대상을 관리할 수 있습니다.
- **사진 확인**에서 사진을 촬영하거나 기기에서 골라 확대해 볼 수 있습니다. 미리보기를 닫으면 임시 촬영 파일이 정리됩니다.

## 검증 명령

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat bundleRelease
```

기기에 연결한 상태에서 이력 이관·중복 저장 방지·중단 복구 테스트를 실행합니다. 배포용 서명은 기존 배포 키와 배포 방식에 맞춥니다. Galaxy Tab A의 잠금 중 후보 탐지, 알림 갱신·중지, 무제한 BLE 지속 동작에 대한 실기기 절차는 `plan.md`에 있습니다.

## 기존 구현과 식별 근거

BleScannerPoC 1.10.1 APK의 동작을 참고해 Java로 다시 만든 Android Studio 프로젝트입니다. 패키지 이름은 `com.glassesscanner`이고, 기존 GlassesScanner 1.1의 등록 이름과 UUID 설정을 유지합니다. Android 8.0(API 26) 이상을 지원 대상으로 설정했습니다.

## 탐지 대상으로 등록

탐색 대상 관리는 디바이스·계열별 카드 목록으로 표시합니다. 이름, 모델, UUID, 제조사 ID를 검색하거나 기본 등록·사용자 등록 필터를 선택할 수 있습니다. 기본 대상 카드는 상세 정보와 출처, 삭제 버튼을 표시하고 사용자 대상 카드는 수정·삭제 화면을 엽니다. 삭제 확인 후 목록과 등록 개수를 갱신하며, 삭제한 항목의 판별 기준은 다음 스캔부터 제외합니다. 다른 등록 기준에도 일치하는 기기는 후보로 표시될 수 있습니다.

기본 카탈로그는 40개 제품·계열 항목을 포함합니다. **설정 → 탐색 대상 관리 → 기본 등록**에서 모델 목록, 식별 근거와 출처를 확인합니다. 공식 연결 이름, 공개 역공학 식별자, 제품명 기반 가설을 표시하며 실기 검증 상태를 구분합니다. 유선형 카탈로그 항목은 무선 식별자가 확보될 때까지 자동 판별 규칙이 없습니다. Bluetooth 이름에 `Glasses`가 포함되면 대소문자와 위치, 기기별 접미사에 관계없이 후보로 분류합니다. `Glasses_4097`은 관측 예시입니다. 기존 UUID 규칙은 이름과 독립적으로 적용됩니다.

결과 상세의 **탐색 대상으로 추가**에서 실제로 수신한 이름, 서비스 UUID, 제조사 ID를 선택할 수 있습니다. **설정 → 탐색 대상 관리**에서 직접 입력하거나 삭제할 수도 있습니다. 이름은 대소문자를 제외하고 전체가 일치해야 합니다. 제조사 ID나 공용 UUID는 다른 기기와도 일치할 수 있습니다.

## 판별 근거

공개 페어링 화면의 이름은 실제 BLE 광고나 Classic 검색 결과와 다를 수 있습니다. 제품명만 확인된 항목은 화면에 실기 검증 전으로 표시합니다. 광고를 받지 못하면 후보를 판별할 수 없습니다.

| 규칙 | 근거 |
| --- | --- |
| Rokid Glass3 기업용 | [Rokid 검색 문서](https://x-docs.rokid.com/docs/en/faq/%E8%93%9D%E7%89%99%E9%97%AE%E9%A2%98%E6%8E%92%E6%9F%A5.html)의 이름 |
| Rokid Glasses / Style | [Rokid Glasses](https://global.rokid.com/blogs/academy-glasses/2-4-connect-to-your-devices), [Style](https://global.rokid.com/en-hispano/blogs/academy-style/style-2-4-connect-to-your-devices) 페어링 목록 이름 |
| Even G1 / G2 | [G1 공식 데모](https://github.com/even-realities/evendemoapp/blob/main/docs/G1_BLE_CONNECTION.en.md) 이름 형식 및 [G2 자료](https://hub.evenrealities.com/docs/get-started/quickstart/hardware)의 페어링 이름 |
| Meta / Luxottica / Snap | [Bluetooth SIG](https://www.bluetooth.com/specifications/assigned-numbers/) 제조사 ID와 모델 이름 단서 |
| Brilliant Labs Frame | [Frame SDK](https://github.com/brilliantlabsAR/frame-sdk-python)의 BLE 서비스 UUID |
| HeyCyan | 기존 APK의 이름과 서비스 UUID |
| Solos AirGo 3 / V | [Solos 페어링 안내](https://solosglasses.com/pages/general-frequently-asked-questions) |
| Xiaomi Smart Audio Glasses | [Xiaomi 페어링 안내](https://www.mi.com/global/support/faq/details/KA-513031/) |
| Razer Anzu | [Razer 페어링 안내](https://mysupport.razer.com/app/answers/detail/a_id/4167) |
| Bose Frames | [Bose 사용자 매뉴얼](https://assets.bose.com/content/dam/Bose_DAM/Web/consumer_electronics/global/products/wearables/bose_frames_soprano/pdf/856770_og_frames-soprano-tenor_en.pdf) |
| Lucyd | [Lucyd 페어링 안내](https://help.lucyd.co/app-and-bluetooth/) |
| Vuzix Z100/Blade, RayNeo X2/X3 Pro/iO, HUAWEI Eyewear, Amazon Echo Frames, Soundcore Frames | 공개 제품명 기반 후보. 광고 이름과 식별자는 실기 검증 전 |

Android 11에서는 블루투스 또는 Wi-Fi, 위치 서비스, 앱 위치 권한을 켜고 스캔합니다. Rokid는 페어링 중과 평상시 연결 상태에서 각각 비교하세요. 실제 제품명과 관측된 이름, 제조사 ID, UUID가 있으면 `GlassesMatcher.java`에 규칙을 보강할 수 있습니다.


## 2.1 Rokid 광고 판별과 화면

광고 서비스 UUID 9100만 있으면 Rokid 계열 후보로 표시합니다. 같은 주소의 BLE 관측에 서비스 데이터 키 9A01도 들어오면 근거 2개로 표시합니다. 제조사 데이터와 서비스 데이터의 내용도 16진수로 수집하여 관측 이력에 저장합니다. 서비스 UUID와 서비스 데이터 UUID를 구분하며, 이전 이력도 계속 열 수 있습니다.

화면은 BleScannerPoC APK의 헤더, 검색 조작부, 요약 카드, 무선 관측/신호 분석/이력/설정 메뉴를 기준으로 재구성했습니다.


## 2.2 태블릿 화면 구성

Galaxy Tab A의 BleScannerPoC 화면을 기준으로 메인 관측 화면, 신호 분석 팝업, 설정 메뉴를 맞췄습니다. 설정의 카메라 검사 항목은 촬영과 사진 보기를 지원합니다.


## 2.3 일반 모드와 백그라운드 알림

일반 모드는 스캔 시작, 중지, 목록 지우기 버튼만 표시합니다. 고급 스위치에서 나머지 조작과 전체 관측을 볼 수 있습니다. 스캔을 시작하면 포그라운드 서비스에서 계속 실행되며, 후보 발견 시 기기별로 스캔당 한 번 로컬 알림을 표시합니다. Android 13 이상에서 알림 권한을 허용해야 표시됩니다. 화면이 꺼져 있을 때는 앱이 미리 설정한 BLE 필터와 일치하는 광고가 검색 대상입니다.
