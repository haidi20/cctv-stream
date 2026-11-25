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
│   ├── structure.md
│   └── adr/                     # catatan keputusan arsitektur (0007 beda jaringan, 0008 koordinat 0..1)
├── rust-core/                   # workspace Cargo
│   ├── Cargo.toml               # workspace manifest (belum dibuat)
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
│   ├── gradle/
│   ├── core/                    # model, network, ui (modul bersama)
│   ├── feature/                 # auth, home, tables, settings
│   └── app/
│       ├── src/
│       │   ├── main/
│       │   │   ├── kotlin/      # UI, service, mapping
│       │   │   ├── res/
│       │   │   ├── assets/
│       │   │   │   └── models/  # yolov11n.onnx
│       │   │   └── jniLibs/
│       │   │       └── arm64-v8a/  # libcore_ffi.so hasil build
│       │   ├── test/kotlin/
│       │   └── androidTest/kotlin/
├── server/                      # penerima metadata + dashboard
│   ├── src/
│   ├── migrations/
│   └── tests/
├── contracts/
│   ├── schemas/                 # JSON Schema untuk konfigurasi & payload
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
    └── bench/                   # benchmark latensi & memori
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

- Direktori saat ini sengaja kosong: fase ini hanya menyiapkan kerangka.
- File biner besar (video, model ONNX) tidak di-commit; sediakan skrip unduh/ekspor.
- Rencana monitoring dan sinkronisasi ada di `docs/monitoring.md`; keputusan di ADR 0004.
