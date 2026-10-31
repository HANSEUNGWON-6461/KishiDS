# Razer Kishi V2 Pro 지원 분석 (Phase 1)

대상 코드: `electrotamp/KishiDS` `main` (`95339b0`), `KishiDS-Android/`.
이 문서의 "확인"은 저장소 코드를 읽어 확인했다는 뜻이다. 실기 측정 여부는 항목마다 따로 적었다.

## 요약

- 기존 앱은 Kishi V1 전용이다. USB 식별, 인터페이스 선택, 펌웨어 검증, DFU 절차가 모두 V1을 전제로 한다.
- `UsbLink.flash()`와 `DfuFlasher`에는 **기기 모델 검사가 없다.** VID/PID가 `27F8:0BC0`이면 V1 이미지를 보낸다. 다른 기기가 같은 ID를 쓴다면 막을 수단이 없다.
- `AppModel.needsOriginal()`과 `showApplyButton()`은 "CUSTOM이 아닌 연결 상태"를 V1 순정으로 취급한다. V2 Pro용 상태를 `State`에 추가만 하면 V1 적용 버튼이 그대로 노출된다.
- 따라서 Phase 2의 핵심은 V2 Pro를 인식하되 V1 경로로 들어가지 못하게 막는 것이다.
- upstream README는 Android 앱 자체도 실기 검증이 끝나지 않았다고 밝힌다(`KishiDS-Android/README.md`의 "Not yet verified on a phone"). 권한, 인터페이스 claim, 컨트롤 전송, HID 드라이버 반환이 해당한다. V1 기능도 Android에서는 "동작이 입증된 기능"이 아니다.

## 1. Android USB 장치 검색 구조

`usb/UsbLink.java`의 단일 스레드 `kishi-usb`가 담당한다.

- `start()`에서 ATTACHED, DETACHED, 권한 응답 브로드캐스트를 등록하고 `loop()`를 시작한다.
- `loop()`는 500ms마다, 또는 `wake()` 호출 시 `scan()`을 실행한다.
- `scan()`은 `busy`(플래싱 중)이면 건너뛰고, `active`가 아니면(앱이 백그라운드) 연결을 해제한다.
- `UsbManager.getDeviceList()`를 순회해 기기를 분류하고, 우선순위 `BOOTLOADER > CUSTOM > STOCK` 순으로 상태를 정한다.
- 어느 쪽도 없으면 `DISCONNECTED`로 돌아간다.

상태 `State { DISCONNECTED, BOOTLOADER, STOCK, CUSTOM_LOCKED, CUSTOM }`는 `AppModel.tick()`이 `Device.valueOf(link.state.name())`로 복사한다. **두 enum의 이름이 일치해야 하며 어긋나면 런타임 예외가 난다.**

## 2. VID/PID 필터링 방식

| 대상 | 식별 | 위치 |
|---|---|---|
| V1 순정 | `27F8:0BBF` | `UsbLink.PID_STOCK` |
| V1 부트로더 | `27F8:0BC0` | `UsbLink.PID_BOOT` |
| KishiDS 펌웨어 | 후보 목록 + 제조사 문자열 | `candidates`, `looksLikeKishiDs()` |

- 후보 목록 기본값은 `054C:05C4`이고, `addCandidate()`로 설정의 VID/PID가 추가된다.
- `looksLikeKishiDs()`는 제조사 문자열이 없거나 `KishiDS`를 포함하면 통과시킨다. 읽을 수 없는 경우 일단 허용한다.
- 텔레메트리에 응답하지 않으면 `foreign`에 넣고 Android로 돌려준다.
- 매니페스트에 `USB_DEVICE_ATTACHED` 인텐트 필터와 `device_filter`가 없다. 앱이 켜져 있을 때의 브로드캐스트와 폴링으로만 찾는다. `MainActivity.handleUsbIntent()`는 현재 매니페스트 기준으로 호출될 경로가 사실상 없다.
- `1532:0717`은 어떤 필터에도 없다. 지금 V2 Pro를 꽂으면 앱은 "No controller"를 표시한다.

## 3. 인터페이스 및 엔드포인트 선택 방식

`openCustom()`은 단일 인터페이스 장치를 전제로 한다.

- 항상 `d.getInterface(0)`을 사용한다.
- `claimInterface(iface, true)`로 커널 HID 드라이버를 강제로 분리한다.
- 인터럽트 IN 엔드포인트 중 마지막 것을 입력으로 쓴다.
- `getFeature()`, `setFeature()`는 `wIndex=0`으로 고정이다.
- `teardown()`과 `giveBack()`도 인터페이스 0만 반환한다.

인터페이스가 5개인 V2 Pro에는 이 경로를 재사용할 수 없다. 특히 `openCustom()`이 보내는 `GET_REPORT 0xAB`를 V2 Pro에 보내면 안 된다.

## 4. USB 권한 처리

- `askPermission()`은 `ACTION_PERMISSION` 브로드캐스트와 `FLAG_MUTABLE` PendingIntent를 쓴다.
- `asked`로 부착 단위 중복 요청을 막고, 거부되면 `accessDenied`를 세운다.
- 순정 V1에는 권한을 요청하지 않는다. 부트로더는 `wantBootloader`일 때만 요청한다. 필요할 때만 묻는 방식이다.
- 관찰 사항: `followPlain()`의 부트로더 분기는 `asked`를 확인하지 않고 스캔마다 `askPermission()`을 호출한다. V1 동작이라 이번 작업에서 고치지 않았다.

권한이 없어도 `UsbDevice`의 VID/PID, 인터페이스, 엔드포인트는 읽을 수 있다. 시리얼을 읽을 때는 `SecurityException`이 날 수 있다.

## 5. DFU 진입 및 플래싱 구현

- 진입은 사용자가 직접 한다. Y + B + 오른쪽 Function을 누른 채 연결한다. 앱이 재부팅으로 진입시키는 기능은 없다.
- 흐름: `AppModel.beginApply()` → `startFlow()` → `WAITING_FOR_BOOTLOADER` → `READY` → `flashNow()` → `UsbLink.flash()` → `DfuFlasher.flash()` → `VERIFYING`.
- `UsbLink.flash()`는 목록에서 `27F8:0BC0`을 찾아 인터페이스 0을 claim한다.
- `DfuFlasher`는 표준 DFU 1.1이다. `GET_STATUS`, 필요 시 `CLR_STATUS`/`ABORT`, 1024바이트 `DNLOAD`, 블록마다 상태 폴링, 길이 0 `DNLOAD`로 manifest를 시작한다.
- `Pipe` 인터페이스 덕분에 JVM에서 가짜 부트로더로 시험할 수 있다.

## 6. FirmwareImage 검증 방식

`FirmwareImage.validate()`는 V1 메모리 맵에 하드코딩되어 있다.

- 앱 영역 `0x08003000`~`0x0800E000`
- 스택 최상단 `0x20004000`
- 벡터 48개가 이미지 내부를 가리키는지
- 커스텀 이미지는 설정 블록의 유일성과 CRC

순정 이미지는 길이 28108바이트와 SHA-256으로 확인한다. **이미지 헤더에 대상 하드웨어 표식이 없어** 이미지 자체로는 대상 모델을 판별할 수 없다. V2 Pro용 검증은 이 클래스를 재사용하지 말고 별도로 만들어야 한다.

## 7. 펌웨어 파일 선택 및 업데이트 UI

- 내장 이미지: `assets/kishi_ds4.bin`을 `AppModel.loadBaseImage()`가 읽는다. 빌드 시 `firmware-research/ds4-firmware/`에서 복사한다.
- 사용자 파일 선택: 일반 플래싱용 파일 선택은 없다. 순정 복구용 `.bin`, `.apk`만 `importOriginalFirmware()`로 받는다.
- UI: `ui/FirmwarePage.java`. 적용 버튼은 `OverviewPage`, `FirmwarePage`, `SetupGuide`의 마지막 단계, 하단 플로팅 바에 있다.

## 8. 기존 V1 복구 기능

- `core/StockFirmware.java`가 사용자가 가져온 Razer 이미지를 SHA-256 검증 후 보관한다. Razer 앱이 설치되어 있으면 `ui/RazerApps.java`로 APK에서 직접 추출한다.
- `AppModel.beginRestore()`가 같은 DFU 경로로 되돌린다.
- 복구가 보장되는 이유는 부트로더를 건드리지 않고, 앱 영역만 쓰며, 버튼 조합으로 언제든 진입할 수 있기 때문이다. V2 Pro에는 이 전제 중 어느 것도 확인되지 않았다.

## 9. 이전 Python 프로토타입 검토

`C:\Users\hansw\Downloads\KishiDS-V2Pro`의 `v2pro_hid_parser.py`, `ds4_translator.py`, 테스트 3개를 읽었다.

- 가정한 입력 형식: Report ID 1, 13바이트. X, Y, Z, Rz, Hat+버튼 18개, 브레이크, 액셀, 벤더 2바이트.
- 테스트는 모두 **손으로 만든 패킷**을 쓴다. 파서의 가정을 그대로 되돌려 확인할 뿐, 실제 장치와 일치한다는 근거가 되지 않는다.
- 저장소에 실기 덤프나 Report Descriptor가 없어 이 형식은 **미확인 가설**로 취급한다.
- Phase 2 진단 보고서는 실제 Report Descriptor로 계산한 Report 1의 길이를 이 가설과 비교해 출력한다. 길이가 맞아도 필드 배치까지 맞다는 뜻은 아니다.
- 프로토타입 코드는 앱으로 옮기지 않았다.

## 10. V2 Pro 사실과 미확인 항목

사용자가 제공한 값이며, 이번 작업에서 실기로 재확인하지는 않았다.

| 항목 | 값 | 상태 |
|---|---|---|
| VID/PID | `1532:0717` | 사용자 제공 |
| 인터페이스 수 | 5 (Gamepad, Touch, 추가 HID, Vendor ×2) | 사용자 제공 |
| 엔드포인트 | IN `0x81 0x82 0x83`, OUT `0x02 0x03` | 사용자 제공 |
| MCU, 플래시 구조 | - | 미확인 |
| 업데이트 모드 진입 방법 | - | 미확인 |
| 부트로더 VID/PID | - | 미확인 |
| DFU 표준 지원 여부 | - | 미확인 |
| 펌웨어 형식, 서명 여부 | - | 미확인 |
| 원본 백업 가능 여부 | - | 미확인 |
| Report Descriptor 내용 | - | 미확인 (이번 진단 기능으로 수집 예정) |

## 11. Phase 2 설계 결정

| 결정 | 이유 |
|---|---|
| 새 패키지 `v2pro/`에 순수 Java 로직을 둔다 | V1 코드와 분리하고 JVM에서 시험할 수 있다 |
| Android 의존 코드는 `usb/V2ProLink.java`에만 둔다 | `UsbLink` 변경을 최소화한다 |
| `scan()` 분류에서 V2 Pro를 `isCandidate()`보다 앞에 둔다 | 설정 VID/PID가 어떤 값이어도 `openCustom()`에 닿지 않는다 |
| V1 우선순위 `BOOTLOADER > CUSTOM > STOCK`은 그대로 두고 그 뒤에 V2 Pro를 둔다 | V1 동작을 바꾸지 않는다 |
| 인식만으로는 claim, 권한 요청, 전송을 하지 않는다 | 게임 입력을 끊지 않는다. 플러그인 시 조용히 인식만 한다 |
| Report Descriptor 수집은 사용자가 눌렀을 때만 한다 | 수집 중에는 HID 드라이버가 분리되어 입력이 끊긴다 |
| 진단 전송은 `ReadOnlyPipe`가 `GET_DESCRIPTOR(Report)`만 통과시킨다 | SET_REPORT, 출력, DFU 요청을 코드 경로에서 원천 차단한다 |
| 플래싱 차단을 UI, `AppModel`, `UsbLink` 세 곳에 둔다 | V1 흐름 대기 중 V2 Pro를 꽂는 경우까지 막는다 |
| 연결된 USB 장치 전체 목록을 표시한다 | Phase 3에서 업데이트 모드의 VID/PID를 찾는 데 그대로 쓴다 |

디스크립터 요청에 인터페이스 claim이 필요한 이유: Linux usbfs는 인터페이스 대상 컨트롤 요청 전에 해당 인터페이스의 소유권을 확인한다. 이 내용은 Linux의 일반 동작이며, Legion Y700 Gen 2의 커널에서는 실기로 확인해야 한다.

## 12. Phase 2 구현 결과

브랜치 `v2pro-diagnostics`. 여기 적은 결과는 모두 **시뮬레이션 장치 기준**이다. 실기 V2 Pro로는 아무것도 확인하지 않았다.

### 구현 내용

| 파일 | 내용 |
|---|---|
| `v2pro/KishiModel.java` | VID/PID로 V1 순정, V1 부트로더, V2 Pro, 기타를 구분한다. `v1FlashAllowed()`는 V2 Pro가 연결되어 있으면 V1 플래싱을 거부한다 |
| `v2pro/UsbLayout.java` | 인터페이스와 엔드포인트 모델. 원시 구성 디스크립터를 파싱하고, HID 디스크립터의 Report 길이와 DFU 인터페이스 유무를 읽는다 |
| `v2pro/V2ProExpected.java` | 사용자가 제공한 V2 Pro 표(인터페이스 5개, 엔드포인트)와 실제 구성을 비교한다. 프로토타입 가설(Report 1, 13바이트) 상수를 둔다 |
| `v2pro/HidDescriptor.java` | HID Report Descriptor를 파싱한다. Report ID별 입력, 출력, 기능 길이와 Usage를 계산한다 |
| `v2pro/ReadOnlyPipe.java` | `GET_DESCRIPTOR`(Report, `0x81/0x06/0x2200`) 외 모든 컨트롤 요청을 거부한다 |
| `v2pro/V2ProProbe.java` | HID 인터페이스마다 claim, 디스크립터 읽기, release를 수행한다. 실패해도 다음 인터페이스로 넘어가고, 텍스트 보고서를 만든다 |
| `usb/V2ProLink.java` | Android `UsbDevice`를 `UsbLayout`으로 변환한다. 실제 연결로 Probe를 실행한 뒤 USB reset으로 드라이버를 재연결한다 |
| `usb/UsbLink.java` | 분류 단계에서 V2 Pro를 후보 목록보다 먼저 판정한다. `State.V2_PRO`를 추가하고, 인식만 할 뿐 권한 요청이나 claim은 하지 않는다. `flash()` 직전에 V2 Pro가 연결되어 있으면 거부한다 |
| `app/AppModel.java` | `Device.V2_PRO`를 추가했다. 적용, 복구, 진행 중인 흐름을 거부하고 진단 실행 상태를 관리한다 |
| `ui/DiagnosticsPage.java` 외 UI | 진단 페이지(장치 목록, 디스크립터 읽기, 복사, 저장)를 추가했다. V2 Pro가 연결되면 Firmware 페이지의 적용 버튼을 숨기고 복구 버튼을 비활성화한다 |
| `test/V2ProSelfTest.java`, `test/run.ps1` | V2 Pro 모의 장치 테스트와 V1 회귀 테스트를 추가하고 실행 순서에 넣었다 |

### 빌드 결과 (2026-10-31, 클라우드 Linux)

- Android SDK: platform `android-34`, build-tools `34.0.0`, JDK 17.0.20.
- `build.ps1`과 같은 순서(aapt2 → javac `--release 8` → d8 → zipalign → apksigner)를 Linux에서 실행했다.
- Android 의존 클래스(`UsbLink`, `V2ProLink`, `AppModel`, UI)는 이번에 처음 컴파일했고, 오류 없이 통과했다. 출력은 deprecated API 안내뿐이다.
- APK: 246,952바이트, `apksigner verify` 통과, minSdk 24, targetSdk 34. 로컬 임시 키로 서명했다.
- JDK 21의 `javac`로 빌드하면 build-tools 34의 `d8`이 V1 코드(`AppModel.startFlashing`의 익명 클래스)에서 내부 오류로 멈춘다. 코드 문제가 아니라 툴체인 조합 문제라서, README의 JDK 17 요구사항에 이 내용을 덧붙였다.

### 테스트 결과 (JVM, 모의 장치)

- `V2ProSelfTest`: 45개 PASS
- `SelfTest`: 43개 PASS. `kishi_ds4.bin`만 사용했고, 순정 이미지가 없어 해당 선택 항목은 실행하지 않았다.
- 합계 88개 PASS, 실패 0개로, 이전 세션의 보고와 같다. 처음에 "90개"로 적은 것은 `ALL PASSED` 줄 2개까지 센 오류였다.

### 실기에서 확인해야 할 것 (Legion Y700 Gen 2 + V2 Pro)

1. 연결하면 "Kishi V2 Pro detected"가 표시되고, 게임 입력이 끊기지 않는가
2. 진단 페이지의 장치 목록이 V2 Pro 표(인터페이스 5개, 엔드포인트)와 일치하는가
3. 권한 대화상자가 뜨고, 허용 후 각 HID 인터페이스의 Report Descriptor를 읽는가
4. 읽은 뒤 USB reset으로 컨트롤러가 다시 입력 장치로 돌아오는가. 실패하면 다시 꽂아야 한다
5. 보고서의 Report 1 길이가 프로토타입 가설(13바이트)과 맞는지. 길이가 맞아도 필드 배치는 별도로 확인해야 한다
6. V1이 있다면 V1 기능에 회귀가 없는가

## 13. 실기 결과 1차 (2026-11-01, Legion Y700 Gen 2 + V2 Pro, 사용자 측정)

**확인된 것 (실기)**
- `1532:0717`, 제조사 `Razer`, 제품명 `Razer Kishi V2 Pro`로 인식되며 "Kishi V2 Pro found"가 기록되었다.
- 인터페이스 5개가 모두 HID(class 03)이고, 기록해 둔 표와 일치한다.

| # | subclass:protocol | 엔드포인트 |
|---|---|---|
| 0 | 01:00 (boot, none) | `0x81` IN interrupt, 16바이트, interval 1 |
| 1 | 00:01 | `0x82` IN interrupt, 32바이트, interval 1 |
| 2 | 01:01 (boot keyboard) | `0x83` IN interrupt, 16바이트, interval 1 |
| 3 | 00:01 | `0x02` OUT interrupt, 16바이트, interval 1 |
| 4 | 00:01 | `0x03` OUT interrupt, 64바이트, interval 1 |

- 인터페이스 3과 4는 Android `UsbInterface`에 OUT 엔드포인트만 보고된다. HID 인터페이스에 IN 엔드포인트가 없는 구성이다. 원시 디스크립터로 다시 확인해야 한다.
- 권한 대화상자가 표시되었고 허용되었다.

**발견된 결함과 수정**
- 허용한 뒤에도 디스크립터 읽기가 시작되지 않았다. 로그가 "USB access granted"에서 끝났다.
- 원인: `UsbLink.followV2Pro()`가 권한 요청을 보내자마자, 사용자가 답하기도 전에 `accessDenied`를 true로 설정했다. `AppModel.diagnosticsTick()`이 이 값을 보고 대기 중인 요청을 취소했다.
- 수정: 요청은 한 번만 보낸다. `accessDenied`는 거부 브로드캐스트에서만 설정하고, 권한이 있으면 해제한다.

**아직 미확인**: bcdUSB, bcdDevice, 시리얼, Report Descriptor. 이 값들은 진단 실행 중에만 읽는다. 14절에서 확인했다.

## 14. 실기 결과 2차: Report Descriptor 수집 (2026-11-01, 사용자 측정)

13절의 수정을 반영한 APK로 진단을 실행해 **성공**했다. 원시 데이터와 바이트 단위 해석은 `firmware-research/v2pro/USB_DESCRIPTORS.md`에 있다.

**확인된 것 (실기)**
- 수정한 권한 흐름이 의도대로 동작했다. 허용하자 바로 읽기를 시작했다.
- 인터페이스 5개 모두 claim에 성공했고, 끝의 USB reset도 true를 반환했다.
- bcdUSB는 2.00, bcdDevice는 1.01이다. 권한이 있어도 시리얼은 보고되지 않는다.
- 일반 모드에는 DFU 인터페이스가 없다.
- 인터페이스 3과 4에 IN 엔드포인트가 없다는 점을 원시 구성 디스크립터에서도 확인했다.
- 인터페이스 0의 입력 Report 1은 13바이트다. 선언 순서는 X, Y, Z, Rz(각 8비트), Hat 4비트, 버튼 18개, 패딩 10비트, Brake, Accelerator(각 8비트), 벤더 `0xFF00:0x21` 2바이트다. 앱 파서와 별도 스크립트로 각각 디코딩했고, 결과가 같았다.
- 인터페이스 1은 5점 터치스크린 디지타이저다(Report 1, 40바이트).
- 인터페이스 2는 Report 7, 8바이트 배열이다. 인터페이스 디스크립터는 boot keyboard로 표기되어 있지만 Report Descriptor는 키보드가 아니다.
- 인터페이스 3은 출력 64바이트와 기능(Feature) 90바이트, 인터페이스 4는 출력 64바이트다. 둘 다 Report ID가 없다.

**프로토타입 가설과의 비교**
- Report 1의 길이(13바이트)와 필드 **선언 순서**는 Python 프로토타입의 가정과 같다.
- 다만 프로토타입의 비트 배치(Hat이 하위 니블인지 등)는 저장소에 코드가 없어 대조하지 못했다.
- 물리 조작(어느 스틱, 트리거, 버튼)과 필드의 대응은 아직 측정하지 않았다.
- 앱 보고서의 "Field order is still unverified" 문구는 이제 물리 대응이 미확인이라는 뜻으로 읽어야 한다.

**미확인 / 주의**
- 인터페이스 3의 90바이트 Feature는 OpenRazer가 구현한 Razer PC 주변기기 제어 프로토콜의 보고서 크기와 같다. 그래서 Razer 명령 채널일 **가능성**이 있다. 아무것도 보내지 않았고, 이 프로토콜을 쓰는지, 업데이트 모드로 들어가는 경로가 있는지는 모른다.
- 인터페이스 1(터치)과 2(Report 7)의 용도는 모른다.
- 진단 후 컨트롤러 입력이 복구되었는지는 아직 보고받지 않았다.
- 업데이트 모드, 부트로더, 펌웨어 형식은 여전히 미확인이다(Phase 3 대상, 미착수).

## 15. 입력 매핑 캡처 기능 (Phase 2 확장, 사용자 승인 2026-11-01)

**목적**: 물리 조작(어느 스틱, 트리거, 버튼)과 Report 필드의 대응표를 만든다. 디스크립터만으로는 알 수 없는 정보이며, DS4 변환 펌웨어를 만들려면 반드시 필요하다.

**구현**
- `v2pro/InputCapture.java`(순수 Java)
  - 손을 뗀 상태로 1.5초 동안 기준값(rest)을 측정한다.
  - 이어서 조작 24개(A~M2)를 하나씩 안내한다. 누를 때 반응한 필드를 기록하고, 손을 떼면 다음 단계로 넘어간다. 아무 반응이 없으면 "Next"로 건너뛴다.
  - 디코딩: 인터페이스 0의 Report 1은 실측 디스크립터의 배치로 읽는다. 인터페이스 0과 1의 Report 3은 Consumer 비트맵으로 읽는다. 그 밖의 Report는 처음 받은 사본과 바이트 단위로 비교한다.
  - 값이 바뀐 Report는 원시 바이트 그대로 최대 600개까지 보관한다. 디코딩이 틀려도 원시 데이터로 다시 분석할 수 있다.
- `usb/V2ProLink.capture()`
  - IN 인터럽트 엔드포인트가 있는 HID 인터페이스(0, 1, 2)만 claim한다.
  - 각 엔드포인트를 IN 전송으로만 읽는다. **컨트롤 요청도, OUT 전송도 하지 않는다.** OUT 엔드포인트만 있는 인터페이스 3과 4는 건드리지 않는다.
  - 끝나면 release한 뒤 USB reset으로 드라이버를 재연결한다. 컨트롤러가 분리되면 1초 안에 멈춘다.
- `AppModel` 및 진단 페이지: Start, Next, Stop 버튼과 실시간 디코딩 표시를 추가했다. 앱이 백그라운드로 가면 3초 뒤 캡처를 중단한다. 복사와 저장 결과에 캡처 보고서가 포함된다.

**검증**
- 디코더의 고정 오프셋(Hat bit 32, 버튼 bit 36, Brake bit 64)을 실측 디스크립터에서 계산한 위치와 비교하는 테스트를 넣었고, 일치했다.
- 모의 Report로 캡처 흐름 테스트 19개를 추가했다.
- 현재 `V2ProSelfTest` 64개, `SelfTest` 43개, 합계 107개가 PASS다.
- **실기 미검증**: 실제 V2 Pro가 Report를 주기적으로 보내는지 변화가 있을 때만 보내는지, 각 조작이 어느 필드에 대응하는지는 모른다.

## 16. 실기 결과 3차: 입력 매핑 (2026-11-01, 사용자 측정)

캡처를 끝까지 실행했다(Report 238개, 인터페이스 0~2 claim 성공, 종료 시 USB reset 성공). 대응표와 DS4 보고서 비교는 `firmware-research/v2pro/USB_DESCRIPTORS.md`에 있다.

**확인된 것 (실기)**
- 버튼 대응: ABXY → 버튼 1, 2, 4, 5. LB/RB → 7, 8. LT/RT → 9, 10(아날로그는 Brake, Accelerator 바이트). View/Menu → 11, 12. Razer → 13. L3/R3 → 14, 15.
- 방향키: Hat 0, 2, 4, 6. 중립값은 8이다.
- 스틱: 왼쪽은 X, Y, 오른쪽은 Z, Rz. 중심은 0x80이고, 위쪽이 낮은 값이다.
- 캡처 도구의 판정을 원시 바이트로 모두 검산했다.
- Report는 **값이 바뀔 때만** 전송된다.
- 스크린샷 버튼은 인터페이스 1에 Volume Down + Power(Android 스크린샷 조합)를 보내고, 인터페이스 2에 Report 7을 함께 보낸다.
- DS4 보고서와 비교하면 스틱 4바이트와 Hat 인코딩이 그대로 같다. 나머지는 비트 재배치만 하면 된다.

**캡처 도구의 한계 (결과 해석 시 주의)**
- 스틱이 중심으로 돌아오는 도중 임계값(64) 안에 들어오면 다음 단계로 넘어간다. 그래서 원시 로그의 단계 표시가 한 칸 앞서 보일 수 있다. 대응 결과에는 영향이 없다.
- 처음 보는 Report의 기준값을 첫 사본으로 삼는다. 그래서 M1/M2 줄의 인터페이스 2 항목은 스크린샷 버튼에서 넘어온 잔여값이다.
- M1/M2는 L3, A와 같은 비트를 냈다. 컨트롤러 내부 리매핑으로 보이지만 확인하지 않았다.

**미확인**: 업데이트 모드, 부트로더, MCU, 펌웨어 교체 가능 여부(Phase 3). V2 Pro를 그대로 iPhone에 연결했을 때의 동작.
