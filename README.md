# Mini9Router Android

Eksperimen AI gateway ringan untuk Android 5.0+ tanpa Termux, Python, Node, root, atau proot.

## Target perangkat
- Android 5.1.1 / API 22
- ARMv7 / armv7l
- RAM ~1 GB

## Fitur awal
- HTTP server lokal port 20128
- `/health`
- `/v1/models`
- `/v1/chat/completions`
- Forward request ke provider OpenAI-compatible
- Provider API key
- Local API key opsional
- Start setelah boot
- Wake lock agar service lebih susah tidur
- Tidak ada library eksternal

## Build
GitHub Actions pada `.github/workflows/build.yml` akan menghasilkan artifact APK debug.

## Catatan
Versi 0.1 belum punya multi-provider fallback dan streaming SSE. Fokus pertama: memastikan server HTTP + proxy dapat hidup stabil di Android 5 / ARMv7.
