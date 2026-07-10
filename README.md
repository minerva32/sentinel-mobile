# Sentinel Mobile

Sentinel 음성 AI 비서의 Android WebView 클라이언트.

## 구성

| 파일 | 설명 |
|---|---|
| `mobile-demo.html` | 데모/캡처용 정적 UI (Orb 애니메이션만) |
| `apk/app/src/main/assets/index.html` | WebView에 로드되는 실제 모바일 UI (STT + TTS + Orb + 대화) |
| `apk/` | Android APK 소스 (MainActivity, OverlayPetService, Gradle) |
| `phone-*.png` | 개발/디버깅 중 캡처한 스크린샷 |
| `sentinel-mobile-demo.png` | 데모 스크린샷 |

## APK 빌드

APK 소스는 `apk/` 디렉토리에 있다.

```bash
cd apk
./gradlew assembleDebug
# 출력: app/build/outputs/apk/debug/app-debug.apk
```

## 배포

- 대상 기기: Galaxy Fold7 (`f7`)
- 설치: ADB 또는 직접 APK 전송
- 연결: Hermes 서버 relay (`sentinel_relay.py:9091`)에 WebSocket/HTTP 연결

## 서버 의존성

- `~/jarvis/sentinel_relay.py` — relay 서버 (port 9091)
- `~/jarvis/tts_api.py` — TTS 서버 (port 9092)
