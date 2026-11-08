# 차량 주치의 (Car Doctor)

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작의 **기획 문서와 검증 코드** 저장소입니다. (비공개)

> 경고등이 켜지기 전, 운전자가 느낀 이상 증상을 차 안에서 선택하면 차량 데이터·주행거리·교체 이력으로
> **즉시 정차 / 센터 점검 / 간단 조치 / 경과 관찰** 4단계로 가려 주고, 센터에는 사전 진단서를 보내는 서비스.

## 저장소 구성
| 경로 | 내용 |
|---|---|
| `docs/기획서.md` | 서비스 기획서 초안 |
| `docs/에뮬레이터_검증결과.md` | Pleos Connect 에뮬레이터에서 확인한 SDK 지원 여부·값 읽기·주행 상태 결과 |
| `docs/차별점_비교표.md` | 기존 서비스와의 비교 |
| `docs/시연영상_대본.md` | 시연 영상 구성 |
| `src/explorer/CapabilityScanner.kt` | SDK 지원 여부 일괄 확인 도구 (116건 스캔) |
| `src/explorer/DrivingStateProbe.kt` | 주행 상태(기어·주차 브레이크) 읽기 검증 코드 |

## 검증 코드 사용 방법
이 저장소에는 **공식 샘플 앱 코드를 포함하지 않습니다.** 공식 샘플 앱
(`ai.pleos.playground.sample.vehicle`, Vehicle SDK 2.3.2)에 위 두 파일을 `explorer` 패키지에 추가해 사용합니다.

1. 두 파일을 샘플 앱의 `.../vehicle/explorer/` 에 복사
2. `AndroidManifest.xml`에 `android.car.permission.CAR_SPEED`, `android.car.permission.CAR_POWERTRAIN`, `<uses-library android:name="android.car" android:required="false"/>` 선언
3. `MainActivity.onCreate`에 추가: `if (intent.getBooleanExtra("probe", false)) DrivingStateProbe(applicationContext).start()`
4. 실행: `adb shell am start -n ai.pleos.playground.sample.vehicle/.MainActivity --ez probe true`
5. 결과: `adb logcat -d -s PleosProbe:I`

## 확인된 것과 한계 (2026-11, Pleos_Connect v2.1.0)
- 기어와 주차 브레이크는 Android Automotive 표준 `CarPropertyManager`로 읽히고 변경 이벤트도 받을 수 있음. 속도는 권한 문제로 읽을 수 없음.
- 기어 D에서는 Pleos 플랫폼이 앱 실행을 막음("주행 중에는 사용할 수 없습니다.").
- 자세한 내용과 미확인 항목은 `docs/에뮬레이터_검증결과.md` 참고.

## 출처·라이선스
- Pleos Playground 공식 샘플 앱은 별도 저장소의 것이며 라이선스 파일이 없어 이 저장소에 포함하지 않았습니다.
- `src/`와 `docs/`는 작성자의 저작물입니다. 별도 라이선스를 지정하지 않았으므로 공모전 심사 목적 외 사용·배포를 허용하지 않습니다.
