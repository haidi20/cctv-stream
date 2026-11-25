# Struktur folder

```
cctv-stream/
├── README.md
├── AGENTS.md                    # aturan wajib: skill, pustaka, gate verifikasi
├── docs/
│   ├── architecture.md
│   ├── roadmap.md
│   ├── data-contracts.md
│   ├── monitoring.md            # kesehatan perangkat, sinkronisasi, alert
│   ├── tdd-plan.md              # peta file test & batas UI vs core
│   ├── rust-scaffold.md         # file produksi Rust + baseline versi
│   ├── testdata.md              # sumber & skenario video uji
│   ├── network-topology.md      # pilihan jalur CCTV → Android → VPS
│   ├── android-sync.md          # Room, outbox, retensi, DataStore
│   ├── android-app-plan.md      # rencana UI Kotlin: login, home, scan, portrait/landscape
│   ├── performance.md           # target FPS & anggaran waktu frame
│   ├── hybrid-mode.md           # analisis hybrid VPS + failover
│   ├── camera-setup.md          # model/path RTSP, QR, dan checklist kamera
│   ├── device-compatibility.md  # diagnostik perangkat dan profil runtime
│   ├── acceptance.md            # ground truth dan metrik akurasi lapangan
│   ├── status.md                # rencana vs bukti implementasi
│   ├── server-api.md            # kontrak API server (target)
│   ├── release.md               # signing, distribusi, model, dan rilis APK
│   ├── structure.md
│   └── adr/                     # catatan keputusan arsitektur (0007 beda jaringan, 0008 koordinat 0..1, 0009 pipeline video)
├── rust-core/                   # workspace Cargo
│   ├── Cargo.toml               # workspace manifest
│   ├── stream-engine/           # RTSP/file source + ring buffer
│   │   ├── src/
│   │   └── tests/
│   ├── ai-engine/               # YOLOv11n ONNX
│   │   ├── src/
│   │   ├── tests/
│   │   └── testdata/            # frame uji kecil
│   ├── spatial-engine/          # poligon + debounce
│   │   ├── src/
│   │   └── tests/
│   └── core-ffi/                # UniFFI bridge ke Kotlin
│       ├── src/
│       └── tests/
├── android/                     # proyek Android Studio
│   ├── build.gradle.kts, settings.gradle.kts, gradle/
│   ├── core/model/              # domain, geometri, JSON (JVM; sudah ada)
│   ├── core/database/           # Room v1 untuk revisi konfigurasi meja
│   ├── feature/tables/          # mapping + editor + ViewModel (47 test)
│   └── app/
│       ├── build/               # artefak build; tidak di-commit
│       │   └── generated/uniffi/kotlin/  # binding hasil generate
│       ├── src/
│       │   ├── main/
│       │   │   ├── kotlin/      # UI, service, mapping
│       │   │   ├── res/
│       │   │   ├── assets/
│       │   │   │   └── models/  # yolov11n.onnx
│       │   │   └── jniLibs/                  # hasil build, tidak di-commit
│       │   │       ├── arm64-v8a/             # didukung untuk AI
│       │   │       ├── armeabi-v7a/           # paket kompatibel, AI tidak didukung
│       │   │       └── x86_64/                # emulator
│       │   ├── test/kotlin/
│       │   └── androidTest/kotlin/
├── server/                      # penerima metadata + dashboard
│   └── (kosong)                 # target Fase 6; lihat server-api.md
├── contracts/
│   ├── schemas/                 # target JSON Schema; saat ini hanya .gitkeep
│   └── fixtures/
│       └── valid/
│           └── meja-mock.json   # poligon 0..1 kanonik untuk test Rust + Kotlin
├── testdata/
│   ├── videos/                  # klip uji (tidak di-commit bila besar)
│   ├── frames/                  # frame hasil ekstraksi untuk uji cepat
│   └── expected/                # hasil yang diharapkan per skenario
└── tools/
    ├── android-build/           # skrip cross-compile + packaging .so
    ├── model-export/            # ekspor/dummy-check YOLOv11n ke ONNX
    ├── onnx-test/               # model sintetis untuk test runtime
    └── bench/                   # benchmark latensi & memori (target)
```

## Aturan penempatan

| Isi | Lokasi |
| --- | --- |
| Logika video/RTSP | `rust-core/stream-engine` |
| Inferensi ONNX | `rust-core/ai-engine` |
| Geometri & status meja | `rust-core/spatial-engine` |
| API lintas bahasa | `rust-core/core-ffi` |
| UI, service, mapping poligon | `android/app/src/main/kotlin` |
| Model ONNX | `android/app/src/main/assets/models` |
| Pustaka native | `android/app/src/main/jniLibs/<abi>` |
| Metadata server & dashboard | `server` |
| Heartbeat & monitoring | `server` (endpoint), `android` (outbox + logger) |
| Skema bersama | `contracts` |
| Video/gambar uji | `testdata` |
| Skrip build & benchmark | `tools` |

## Catatan

- Workspace Rust sudah berisi empat crate dan test. Gradle root dan modul
  `core:model`, `core:database`, `feature:tables`, serta `app` sudah ada.
  `core:model` (29 test) dan `feature:tables` (47 test) lulus di host; Room v1,
  manifest, dan layar mapping sudah ada. Login/Home/Scan, foreground service,
  outbox, dan APK belum ada. `server/`, schema JSON, dan workflow CI juga belum ada.
- Binding Kotlin UniFFI berada di `android/app/build/generated/uniffi/kotlin` dan
  `.so` hasil build berada di `android/app/src/main/jniLibs`. Keduanya artefak build,
  bukan sumber yang diedit tangan.
- `arm64-v8a` adalah target AI. `armeabi-v7a` hanya untuk kompatibilitas paket dan
  tidak dijanjikan bisa menjalankan model.
- File biner besar (video, model ONNX) tidak di-commit; sediakan skrip unduh/ekspor.
- Rencana monitoring dan sinkronisasi ada di `docs/monitoring.md`; keputusan di ADR 0004.
