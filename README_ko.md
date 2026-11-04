# Proximity SDK Android 가이드

## 개요
이 문서는 OpenDID **근접(proximity) SDK** 안드로이드 라이브러리에 대한 가이드입니다.
홀더 단말이 온라인 상태가 아니어도 근접 전송으로 크리덴셜을 제시·검증할 수 있도록,
홀더(mdoc) 쪽과 Verifier(리더) 쪽을 모두 담습니다.

OpenDID 에서 `did-ca-aos`(지갑)는 홀더 표면을, `did-proximity-verifier-android`(근접 검증앱)는
리더 표면을 소비합니다.

> 근접 프로토콜 구현이 진행 중이며, 공개 API 는 아직 안정화되지 않았습니다.

## S/W 사양
| 구분             | 내용                                                      |
| ---------------- | --------------------------------------------------------- |
| OS               | Android 16 (API Level 36)                                 |
| 언어             | Java 21                                                   |
| IDE              | Android Studio 2025.3 이상                                |
| 빌드 시스템      | Gradle 9.4.1 / Android Gradle Plugin 9.1.0                |
| 호환성           | compileSdk 36, minSdk 26                                  |

**근접 기능은 실기기가 필요합니다.** 에뮬레이터는 BLE advertise/scan 을 지원하지 않으므로,
종단 간 검증에는 홀더 앱 1대 + 리더 앱 1대, 총 실기기 2대가 필요합니다.

## 프로젝트 클론 및 체크아웃
```git
git clone https://github.com/OmniOneID/did-proximity-sdk-android.git
```

## 빌드 방법
1. Android Studio 를 설치합니다.
2. File > Open 으로 `source/did-proximity-sdk-android` 폴더를 엽니다.

커맨드라인:
```bash
cd source/did-proximity-sdk-android
./gradlew assembleRelease
```

AAR 은 `<module>/build/outputs/aar/<module>-release.aar` 에 생성됩니다.

CI 와 동일한 검사를 실행하려면:
```bash
./gradlew build
```

## 프로젝트 구조
```
source/did-proximity-sdk-android/
├── proximity-core/      홀더·리더 공용 (org.omnione.did.proximity.sdk.core)
├── proximity-holder/    홀더 표면 (org.omnione.did.proximity.sdk.holder)
├── proximity-reader/    리더 표면 (org.omnione.did.proximity.sdk.reader)
├── proximity-holder-fused/  단일 AAR: proximity-holder + proximity-core
└── proximity-reader-fused/  단일 AAR: proximity-reader + proximity-core
```

### proximity 모듈
`proximity-core` 는 근접 교환의 양쪽이 공유하는 것을 담는 안드로이드 라이브러리 모듈입니다 —
BLE 전송, 세션 암호화, device engagement, CBOR/COSE 인코딩.
`proximity-holder` 와 `proximity-reader` 는 그 위에 얹는 얇은 역할 래퍼로,
각자에게 필요한 권한만 선언해 소비 애플리케이션이 매니페스트 병합으로 물려받게 합니다.
R8 규칙은 세 모듈이 각자의 `consumer-rules.pro` 로 전달합니다.

의존은 단방향입니다 — 역할 모듈 → `proximity-core`. 역할 모듈끼리 서로 참조하지 않습니다.

### AAR 소비
`proximity-holder-fused` 와 `proximity-reader-fused` 는 각 역할 모듈과 `proximity-core` 를
AAR 하나로 묶습니다 (Android Gradle Plugin fused library).

```bash
./gradlew :proximity-holder-fused:bundle :proximity-reader-fused:bundle
```

AAR 은 `proximity-<role>-fused/build/outputs/aar/proximity-<role>-fused.aar` 에 생성됩니다.
홀더 앱은 홀더 AAR 을, 리더 앱은 리더 AAR 을 가져갑니다. AAR 파일에는 POM 이 없으므로
`proximity-core` 의 의존성을 소비 앱이 직접 선언해야 합니다.

```groovy
implementation files('libs/proximity-reader-<version>.aar')
implementation 'androidx.annotation:annotation:1.9.1'
implementation 'com.upokecenter:cbor:4.5.6'
implementation 'com.madgag.spongycastle:core:1.54.0.0'
implementation 'com.madgag.spongycastle:prov:1.54.0.0'
```

SpongyCastle 을 빠뜨려도 컴파일은 되지만, 세션 암호화 시점에 `NoClassDefFoundError` 로 실패합니다.

## 변경 이력

- [Proximity SDK Android](CHANGELOG.md)

## OpenDID 시연 영상 <br>
OpenDID 시스템의 시연 영상은 [Demo Repository](https://github.com/OmniOneID/did-demo-server) 에서 확인할 수 있습니다. <br>

## 기여

행동 강령과 Pull Request 제출 절차는 [CONTRIBUTING.md](CONTRIBUTING.md) 와
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) 를 참고하십시오.

## 라이선스
[Apache 2.0](LICENSE)
