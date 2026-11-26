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
│   ├── core/model/              # domain, geometri, JSON (JVM; 44 test)
│   ├── core/database/           # Room v2: revisi meja + profil kamera
│   ├── core/network/            # discovery CCTV + tes koneksi 4 lapis (JVM; 43 test)
│   ├── core/ui/                 # theme M3 + format waktu bersama (JVM; 6 test)
│   ├── feature/auth/            # login PIN PBKDF2 + lockout + biometrik (36 test)
│   ├── feature/cameras/         # daftar/editor kamera + scan + tes koneksi (37 test)
│   ├── feature/home/            # Home adaptif + ScanSheet + NetworkPolicy + backoff (56 test)
│   ├── feature/onboarding/      # diagnostik perangkat sebelum PIN (41 test)
│   ├── feature/tables/          # mapping + editor + ViewModel (53 test)
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
├── contracts/                   # crate standalone; gate: cd contracts; cargo test
│   ├── schemas/                 # 5 JSON Schema draft 2020-12
│   ├── src/lib.rs               # validasi registry offline (jsonschema)
│   ├── tests/schema_test.rs     # semua fixture valid/invalid
│   └── fixtures/
│       ├── valid/               # meja-mock, status, sync, heartbeat
│       └── invalid/             # 15 kasus negatif
├── testdata/
│   ├── videos/                  # klip uji (tidak di-commit bila besar)
│   ├── frames/                  # frame hasil ekstraksi untuk uji cepat
│   └── expected/                # hasil yang diharapkan per skenario
└── tools/
    ├── android-build/           # skrip cross-compile + packaging .so
    ├── ci/verify.ps1            # gate satu perintah (contracts, rust, ndk, android)
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

- Workspace Rust sudah berisi empat crate dan test. Gradle root dan modul `app`,
  `core:model`, `core:network`, `core:database`, `feature:cameras`,
  `feature:tables`, `feature:auth`, `feature:home`, dan `feature:onboarding` sudah
  ada. Gate gabungan 2026-11-26 lulus 383 test host (model 44, network 43, core:ui 6,
  database 11, cameras 37, tables 53, auth 36, home 56, onboarding 41, app 56) dan
  `:app:assembleDebug` menghasilkan APK. Diagnostik perangkat, login PIN, Home
  adaptif, Scan CCTV, mapping poligon, Room v2, dan foreground service sudah ada; outbox/sync, `server/`, dan workflow CI belum ada. Diagnostik koneksi empat lapis
  (Wi-Fi -> TCP -> DESCRIBE Basic/Digest -> paket video) dan `NetworkPolicy`
  (`no_network`/`wrong_network` tanpa menyamar sebagai kegagalan kamera) sudah ada.
- Binding Kotlin UniFFI berada di `android/app/build/generated/uniffi/kotlin` dan
  `.so` hasil build berada di `android/app/src/main/jniLibs`. Keduanya artefak build,
  bukan sumber yang diedit tangan.
- `arm64-v8a` adalah target AI. `armeabi-v7a` hanya untuk kompatibilitas paket dan
  tidak dijanjikan bisa menjalankan model.
- File biner besar (video, model ONNX) tidak di-commit; sediakan skrip unduh/ekspor.
- Rencana monitoring dan sinkronisasi ada di `docs/monitoring.md`; keputusan di ADR 0004.
