# 런메이트 (RunMate)

지도 없이 달리기 경로·속도·높이를 기록하고, QR로 맺은 그룹원과 기록을 주고받는 안드로이드 앱.

## 설치
`dist/runmate-1.1.0.apk`를 휴대폰에 내려받아 실행 (Android 8.0 이상, "출처를 알 수 없는 앱 설치" 허용 필요).

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
