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
