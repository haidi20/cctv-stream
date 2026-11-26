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
│   ├── core/model/              # domain, geometri, JSON, heartbeat (63 test)
│   ├── core/database/           # Room v3: revisi meja + profil kamera + outbox (32 test)
│   ├── core/network/            # discovery CCTV + tes koneksi 4 lapis (JVM; 43 test)
│   ├── core/sync/               # coordinator, backoff, endpoint, interface settings (14 test)
│   ├── core/ui/                 # theme M3 + format waktu bersama (JVM; 12 test)
│   ├── feature/auth/            # login PIN PBKDF2 + lockout + biometrik (36 test)
│   ├── feature/cameras/         # daftar/editor kamera + scan + tes koneksi (37 test)
│   ├── feature/home/            # Home adaptif + ScanSheet + NetworkPolicy + backoff (66 test)
│   ├── feature/onboarding/      # diagnostik perangkat sebelum PIN (41 test)
│   ├── feature/settings/        # base URL + token Bearer untuk sync (28 test)
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
├── server/                      # penerima metadata (Axum + SQLite) + dashboard (belum)
│   ├── src/                     # router, sync, heartbeat, snapshot, ws, auth, store
│   ├── migrations/              # SQL migrasi 0001_init
│   └── tests/api.rs             # 17 test integrasi

├── contracts/                   # crate standalone; gate: cd contracts; cargo test
│   ├── schemas/                 # 6 JSON Schema draft 2020-12
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
  `core:model`, `core:network`, `core:database`, `core:sync`, `core:ui`,
  `feature:cameras`, `feature:tables`, `feature:auth`, `feature:home`,
  `feature:onboarding`, dan `feature:settings` sudah ada. Gate gabungan 2026-11-26
  lulus 555 test host dari 83 `TEST-*.xml` (app 130, database 32, model 63, network 43,
  sync 14, ui 12, auth 36, cameras 37, home 66, onboarding 41, settings 28, tables 53) dan
  `:app:assembleDebug` menghasilkan APK. Diagnostik perangkat, login PIN, Home
  adaptif, Scan CCTV, mapping poligon, Room v3, foreground service, serta outbox +
  `SyncWorker`/Ktor + heartbeat + layar pengaturan sinkronisasi sudah ada; `server/`
  dan workflow CI belum ada. Diagnostik koneksi empat lapis
  (Wi-Fi -> TCP -> DESCRIBE Basic/Digest -> paket video) dan `NetworkPolicy`
  (`no_network`/`wrong_network` tanpa menyamar sebagai kegagalan kamera) sudah ada.
- Binding Kotlin UniFFI berada di `android/app/build/generated/uniffi/kotlin` dan
  `.so` hasil build berada di `android/app/src/main/jniLibs`. Keduanya artefak build,
  bukan sumber yang diedit tangan.
- `arm64-v8a` adalah target AI. `armeabi-v7a` hanya untuk kompatibilitas paket dan
  tidak dijanjikan bisa menjalankan model.
- File biner besar (video, model ONNX) tidak di-commit; sediakan skrip unduh/ekspor.
- Rencana monitoring dan sinkronisasi ada di `docs/monitoring.md`; keputusan di ADR 0004.
