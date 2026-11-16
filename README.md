# 런메이트 (RunMate)

지도 없이 달리기 경로·속도·높이를 기록하고, QR로 맺은 그룹원과 기록을 주고받는 안드로이드 앱.

## 설치
`dist/runmate-1.7.0.apk`를 휴대폰에 내려받아 실행 (Android 8.0 이상, "출처를 알 수 없는 앱 설치" 허용 필요).

## 구조
- `app/src/main/java/app/runmate/core` — 안드로이드 비의존 로직 (기록 필터, 암호화, 릴레이, 동기화, 고도, 통계)
- `app/src/main/java/app/runmate` — 포그라운드 GPS 서비스, 백그라운드 동기화(WorkManager), 알림
- `app/src/main/java/app/runmate/ui` — Compose 화면 (달리기/기록/분석/순위/그룹)

## 공유 방식 (서버·로그인 없음)
- 그룹 = 무작위 256비트 키. QR 코드로만 전달.
- 기록은 그룹 키로 AES-256-GCM 암호화한 뒤 공개 중계 서버로 전달: ntfy.sh(빠른 경로, 12시간 보관) + Nostr 공개 릴레이(보관 경로).
- 위경도는 휴대폰 밖으로 나가지 않음. 시작점 기준 상대좌표만 공유.

## 고도
OpenTopoData SRTM 30m → 실패 시 Open-Meteo(Copernicus 90m). 달리기를 마친 뒤 한 번만 조회. GPS 고도도 함께 저장.

## 지도
기록 상세·분석 화면에서 지도/격자를 고를 수 있다. 지도는 OpenStreetMap 타일(키 불필요, 디스크 캐시)이며 위경도가 있는 내 기록만 표시된다. 달리는 중 화면은 지도를 쓰지 않는다.

## 언어 / 후원 버튼
- 지원 언어 10개: 한국어, English, 中文(简体), 日本語, Español, Português, Deutsch, Français, Русский, Bahasa Indonesia. 휴대폰 언어를 따르고(미지원 언어는 영어) 설정 탭에서 바꿀 수 있다.
- 문구는 코드에서 `tr("한국어", "English")`로 쓰고, 나머지 8개 언어는 영어 문구를 열쇠로 `core/I18n.kt` 번역표에서 찾는다.
- 문구를 추가/수정하면 `tools/i18n/trans1.py`·`trans2.py`에 번역을 넣고 아래를 실행해 `I18n.kt`를 다시 만든다(누락이 있으면 실패한다).
```bash
python3 tools/i18n/extract.py && python3 tools/i18n/gen.py
```
- 각 탭 아래의 "커피 선물하기"는 Google Play 인앱결제(`Coffee.kt`, 소모성 상품 ID `coffee`)다. Play 스토어에서 설치했고 Play Console에 상품이 등록되어 가격이 조회될 때만 보인다. 스토어 밖에서 설치한 APK에서는 보이지 않는다.
- Play 업로드용 번들: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`

## 빌드 / 테스트
```bash
./gradlew :app:assembleRelease
```
```bash
./gradlew :app:testDebugUnitTest
```
실제 서버(ntfy.sh, 고도 API)까지 포함한 테스트:
```bash
./gradlew :app:testDebugUnitTest -Dlive=1
```
서명 키(`keystore/`)는 저장소에 올리지 않는다. `keystore/signing.properties`와 키 파일이 있으면 그 키로, 없으면 디버그 키로 서명된다.
