# OpenText for Android

오프라인 Android 텍스트 뷰어 겸 편집기입니다. 확장자가 아니라 실제 텍스트 형식을 기준으로 TXT, JSON, XML, YAML, MD, CSV, LOG, INI, 코드 등 텍스트 파일을 열고 편집하여 저장할 수 있습니다.

## 기능

- Android 시스템 파일 선택기(SAF): 로컬 저장소 및 지원되는 문서 공급자에서 파일 열기 / 새 파일 만들기
- 원본 저장과 다른 이름으로 저장, 미저장 변경사항 확인
- UTF-8, UTF-8 BOM, UTF-16LE/BE, EUC-KR 인코딩. 해독·인코딩에 실패한 문자를 자동 치환하지 않습니다.
- 줄바꿈(LF/CRLF/CR) 유지, 문자 수와 현재 줄/열 표시
- 찾기, 줄 이동, 자동 줄바꿈, 글꼴 크기
- 파일 관리자에서 텍스트 MIME 유형의 파일을 직접 열기
- 네트워크 불필요, 인터넷 권한/전체 저장공간 권한 없음

## 제한 사항

파일 본문은 안드로이드 편집기 메모리에 로드하므로 8 MiB 이하를 지원합니다. 이미지, PDF, Word 및 기타 바이너리는 텍스트 문서가 아니므로 지원하지 않습니다. JSON 문법 검증/포맷 및 코드 구문 강조는 포함하지 않습니다. 클라우드 문서 공급자의 경우 파일 쓰기와 저장 검증이 공급자의 권한과 구현에 따라 제한될 수 있습니다.

## Android 빌드

- Android 8.0(API 26) 이상
- Java 17 이상, Android Gradle Plugin 9.3.1, Gradle 9.8.1, compileSdk 36
- Android Studio 또는 Gradle에서 app:assembleDebug 태스크 실행
- 디버그 설치 식별자: com.shaterguy.opentext.dev. 정식 식별자 com.shaterguy.opentext와 구별됩니다.
- Termux/aarch64에서는 시스템 aapt2 사용: gradle -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" :app:assembleDebug

## 호스트 측 인코딩 회귀 검사

javac -d /tmp/opentext-tests app/src/main/java/com/shaterguy/opentext/TextFileCodec.java tools/TestTextFileCodec.java

java -cp /tmp/opentext-tests TestTextFileCodec

## 개인정보

파일은 기기와 선택한 문서 공급자 안에서만 처리합니다. 앱은 인터넷 권한을 선언하지 않습니다.

## 사용자 키 서명 준비

검증한 정식 구성의 미서명 APK를 GitHub Secrets로 서명하는 수동 워크플로를 준비했습니다. source·빌드 입력·파일 해시·앱 버전과 예상 인증서 지문을 확인하며, 서명 단계에서는 재빌드하지 않습니다. [서명 준비 안내](docs/release-signing.md)를 참고하세요. 키 등록과 실제 사용자키 서명은 별도 단계이고, 미서명 APK는 설치용 완성품이 아닙니다.
