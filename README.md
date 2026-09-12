# Mini9Router Android

Mini9Router Android adalah router AI ringan untuk Android 5.0+ yang meniru konsep inti 9Router tanpa membutuhkan Termux, Python, Node, root, atau proot.

Target utamanya adalah perangkat lama seperti Android 5.1.1 / ARMv7 / RAM sekitar 1 GB.

## v0.5 — 9Router-style Router Core

Fitur utama:

- OpenAI-compatible gateway pada port `20128`
- `/v1/chat/completions`
- `/v1/responses`
- `/v1/models`
- `/v1/providers`
- SSE streaming asli dengan HTTP chunked transfer
- menerima request body `Content-Length` maupun `Transfer-Encoding: chunked`
- dukungan `Expect: 100-continue`
- multi-provider hingga 10 slot
- provider fallback otomatis
- cooldown provider setelah rate-limit/error sementara
- model alias seperti `AGY=p1/gpt-5.6-luna`
- combo/fallback chain seperti `coding=p1/model-a,p2/model-b,p3/model-c`
- direct routing memakai format `pN/model`
- beberapa local API key
- statistik request/failure/fallback tersimpan lokal
- recent request logs
- web dashboard ringan di `/dashboard`
- Codex OAuth adapter yang sudah ada tetap dipertahankan
- boot receiver + wakelock untuk mode server

## Routing

Contoh alias:

```text
AGY=p1/gpt-5.6-luna
FAST=p2/deepseek-v4.1-flash-free
```

Contoh combo:

```text
coding=p1/gpt-5.6-luna,p2/deepseek-v4.1-flash-free,p3/qwen-coder
```

Jika client meminta model `coding`, Mini9Router mencoba target dari kiri ke kanan. Jika model tidak cocok dengan alias/combo mana pun, semua provider aktif dicoba sesuai urutan slot.

## API

Base URL:

```text
http://IP-ANDROID:20128/v1
```

Header local key:

```text
Authorization: Bearer YOUR_LOCAL_KEY
```

## Build

GitHub Actions di `.github/workflows/build.yml` otomatis menjalankan `gradle assembleDebug` dan menghasilkan artifact `Mini9Router-APK`.

## Hubungan dengan 9Router

Project ini bukan port langsung source code 9Router. Ini implementasi Android-native yang mengambil konsep routing yang sama agar tetap realistis di Android 5 / ARMv7 / RAM rendah. 9Router asli memiliki fitur yang jauh lebih luas seperti banyak format translator, OAuth provider yang lebih lengkap, quota tracking mendalam, cloud sync, dan dashboard Next.js.
