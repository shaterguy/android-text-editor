# 사용자 키 서명 준비

이 구성은 검증된 **미서명 APK에 서명만 하는 경로**를 기본으로 합니다. 키 생성·등록·실제 서명은 수행하지 않았으며, 서명 준비와 서명 성공은 별개입니다. AAB 생성이나 Play Store·GitHub Release 자동 공개는 포함하지 않습니다.

## 본인이 직접 설정할 값

[Actions Secrets](https://github.com/shaterguy/android-text-editor/settings/secrets/actions)에 다음 네 개를 직접 등록합니다.

| Secret | 내용 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 본인 keystore 파일의 Base64 값 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `ANDROID_KEY_ALIAS` | 서명할 키의 alias |
| `ANDROID_KEY_PASSWORD` | 해당 키 비밀번호 |

[Actions Variables](https://github.com/shaterguy/android-text-editor/settings/variables/actions)에는 `ANDROID_SIGNING_CERT_SHA256`으로 **사용하려는 인증서의 SHA-256 지문**을 등록합니다. 이 공개 지문은 키와 독립적으로 확인해야 합니다. 다른 지문이 나오면 서명 결과를 제공하지 않습니다. Base64는 암호화가 아니므로 원본 키와 똑같이 보호하세요. 키·비밀번호는 채팅·문서·저장소에 넣지 않고 GitHub 밖에도 안전하게 백업합니다.

## 기본 경로: 로컬 검증 APK → 원격 서명

1. 사용자에게 인계할 코드를 커밋하고 GitHub에 보존한 뒤 exact source SHA를 확인합니다. 서명할 소스는 신뢰하는 `main` 이력 안에 있어야 합니다. 서명 워크플로 자체도 `main`에서만 실행됩니다.
2. 그 SHA의 깨끗한 checkout에서 필요한 테스트를 마치고 `./gradlew :app:assembleRelease`로 정식 설치 ID의 **미서명** APK를 만듭니다. 이 Gradle 작업은 키를 읽지 않으며 APK는 아직 설치용 완성품이 아닙니다.
3. Android Build Tools 36.0.0의 `zipalign -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk`로 정렬을 검사합니다. 이어 다음 명령으로 source/build/package/version/hash 메타데이터를 만듭니다.

```sh
python tools/release_metadata.py create \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --metadata /tmp/release-metadata.json \
  --source "$(git rev-parse HEAD)" \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --environment '실제 OS, JDK, SDK 및 빌드 실행환경을 기록'
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk /tmp/release-metadata.json
```

4. 두 파일을 승인된 저장소 자산 전달 경로에 올리고 각 numeric asset ID를 기록합니다. 현재 서명 워크플로는 **이 저장소의 Release asset API만** 받습니다. 비공개 draft의 자산도 사용할 수 있으며 이 준비를 위해 정식 Release를 게시하거나 기존 자산을 덮어쓰지 않습니다. 파일 업로드·draft 생성은 별도 실제 작업이며 이 설정 자체가 수행하지 않습니다.
5. `Android signed release` 실행 입력에 source SHA, unsigned APK asset ID, metadata asset ID, 그리고 로컬에서 확인한 두 SHA-256을 지정합니다. 입력은 단순 파일명·최신 빌드가 아니라 검증한 정확한 바이트를 가리켜야 합니다.
6. 워크플로는 source의 `main` 포함 여부, 입력 파일 해시, 빌드 설정 해시, package/version, 미서명 여부와 ZIP 무결성을 먼저 검사합니다. 그 뒤에만 Secrets로 서명하고 예상 인증서, APK 서명 무결성, 정렬과 서명 후 SHA-256을 확인합니다. Gradle이나 대상 source의 코드는 서명 단계에서 실행하지 않습니다.
7. 성공 artifact에는 `OpenText-v<버전>-user-signed.apk`, 서명 확인 결과, 원본 미서명 메타데이터와 서명 후 `SHA256SUMS`가 들어 있습니다. 실행·재시도마다 별도 artifact를 사용하며 보관 기간은 14일입니다. APK를 회수한 뒤 이 체크섬과 다시 비교해 전달합니다.

메타데이터는 검증한 빌드 담당자의 출처 기록이며, 임의 APK가 해당 소스에서 생성됐다는 사실을 암호학적으로 증명하지는 않습니다. 따라서 출처를 모르는 APK·메타데이터를 함께 받아 해시만 입력해서는 안 됩니다. 신뢰하는 빌드 환경에서 확인한 원본과 두 해시를 사용해야 합니다. 소스·변형·빌드 입력이 같고 검증된 APK가 이미 있다면 다시 빌드하지 않습니다.

## 로컬 빌드가 불가능한 경우만 사용하는 대안

`Android unsigned release fallback`은 `main`에 포함된 exact source SHA를 받아 키 없이 정식 구성의 unsigned APK와 동일 메타데이터를 생성합니다. 일반 push/PR/tag에는 실행되지 않습니다. 이 결과도 서명 완료나 설치 가능한 결과로 표시하지 않으며, 회수·검증한 파일을 위의 서명 전용 경로에 전달합니다. 유효한 로컬 APK가 있으면 이 대안 빌드를 중복 실행하지 않습니다.

## 설치·업데이트 계보

- 개발/TEST: `com.shaterguy.opentext.dev`. 기존 dev1과 연속 업데이트하려면 **기존 개발 인증서와 동일한 키**가 필요합니다. 새 runner의 임시 debug key가 dev1과 같다고 가정하지 않습니다.
- 정식: `com.shaterguy.opentext`. 사용자 키로 서명하며 개발 앱과 별도로 설치됩니다. 개발 앱 내부 데이터는 자동 이동하지 않을 수 있으므로 문서를 먼저 안전하게 저장합니다.
- 같은 package를 업데이트하려면 호환되는 기존 서명과 증가한 versionCode가 필요합니다. 서명이 다르면 설치가 거절되며 앱 삭제·데이터 초기화로 자동 복구하지 않습니다.
- 현재 후보는 `0.1.0-dev2` / code `2`입니다. 이 후보를 사용자 키로 서명하는 것만으로 정식 버전 승격이나 기기 검증 완료가 되는 것은 아닙니다.

필수 Secret·지문 누락, 입력 해시·source·package·version 불일치 또는 서명 검증 실패 시 결과를 게시하지 않습니다. 개발용 키로 대체하지 않습니다. 임시 keystore는 종료 시 정리되고 artifact에는 포함되지 않습니다.

도구 규약: [Android apksigner](https://developer.android.com/tools/apksigner), [Android zipalign](https://developer.android.com/tools/zipalign).
