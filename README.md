# Proximity SDK Android Guide

## Overview
This document is a guide for the OpenDID **proximity SDK** for Android.
The SDK carries both halves of a credential presentation exchanged over a proximity
transport — the holder (mdoc) side and the Verifier (reader) side — without requiring
the holder device to be online.

In OpenDID, `did-ca-aos` (the wallet) consumes the holder surface and
`did-proximity-verifier-android` (the proximity Verifier app) consumes the reader surface.

> The proximity protocol implementation is in progress. The public API is not yet stable.

## S/W Specifications
| Category         | Details                                                 |
| ---------------- | ------------------------------------------------------- |
| OS               | Android 16 (API Level 36)                               |
| Language         | Java 21                                                 |
| IDE              | Android Studio 2025.3 or later                          |
| Build System     | Gradle 9.4.1 / Android Gradle Plugin 9.1.0              |
| Compatibility    | compileSdk 36, minSdk 26                                |

**Proximity features require physical devices.** Emulators do not support BLE
advertising or scanning, so end-to-end verification needs two real devices —
one running a holder application and one running a reader application.

## Project Clone and Checkout
```git
git clone https://github.com/OmniOneID/did-proximity-sdk-android.git
```

## Build Method
1. Install Android Studio.
2. Open the project folder `source/did-proximity-sdk-android` via File > Open.

Command line equivalent:
```bash
cd source/did-proximity-sdk-android
./gradlew assembleRelease
```

The AARs are written to `<module>/build/outputs/aar/<module>-release.aar`.

To run the same checks as CI:
```bash
./gradlew build
```

## Project Structure
```
source/did-proximity-sdk-android/
├── proximity-core/      Shared by holder and reader (org.omnione.did.proximity.sdk.core)
├── proximity-holder/    Holder surface (org.omnione.did.proximity.sdk.holder)
├── proximity-reader/    Reader surface (org.omnione.did.proximity.sdk.reader)
├── proximity-holder-fused/  Single AAR: proximity-holder + proximity-core
└── proximity-reader-fused/  Single AAR: proximity-reader + proximity-core
```

### The proximity modules
`proximity-core` is an Android library module that carries what both sides of a proximity
exchange share — BLE transport, session encryption, device engagement and CBOR/COSE
encoding. `proximity-holder` and `proximity-reader` are thin role wrappers on top of it,
each declaring only the permissions its own role needs so that consuming applications
inherit them through manifest merging. All three contribute R8 rules through their own
`consumer-rules.pro`.

Dependencies run one way only: role module → `proximity-core`. The role modules never
reference each other.

### Consuming the AARs
`proximity-holder-fused` and `proximity-reader-fused` package each role module together with
`proximity-core` into a single AAR (Android Gradle Plugin fused library):

```bash
./gradlew :proximity-holder-fused:bundle :proximity-reader-fused:bundle
```

The AARs are written to `proximity-<role>-fused/build/outputs/aar/proximity-<role>-fused.aar`.
A holder application takes the holder AAR and a reader application takes the reader AAR.
AAR files carry no POM, so the consuming application must declare the dependencies of
`proximity-core` itself:

```groovy
implementation files('libs/proximity-reader-<version>.aar')
implementation 'androidx.annotation:annotation:1.9.1'
implementation 'com.upokecenter:cbor:4.5.6'
implementation 'com.madgag.spongycastle:core:1.54.0.0'
implementation 'com.madgag.spongycastle:prov:1.54.0.0'
```

Omitting the SpongyCastle artifacts still compiles, but session encryption fails at run time
with `NoClassDefFoundError`.

## Change Log

ChangeLog can be found :
<br>
- [Proximity SDK Android](CHANGELOG.md)

## OpenDID Demonstration Videos <br>
To watch our demonstration videos of the OpenDID system in action, please visit our [Demo Repository](https://github.com/OmniOneID/did-demo-server). <br>

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for details on our code of conduct, and the process for submitting pull requests to us.

## License
[Apache 2.0](LICENSE)
