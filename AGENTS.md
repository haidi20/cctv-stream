# AGENTS.md - cctv-stream

Panduan kerja untuk agen di repositori ini. Isinya mengikat: setiap tugas di repo ini
wajib mengikuti aturan skill, pustaka, dan gate di bawah, kecuali instruksi langsung
pengguna menyatakan lain.

## 1. Konteks repo

`cctv-stream` adalah sistem **table occupancy monitoring** offline-first untuk
warung/restoran. Kamera CCTV EZVIZ (RTSP) diproses di perangkat Android (edge); VPS hanya
menerima metadata JSON.

- `rust-core/` - workspace Cargo: `stream-engine` (RTSP + ring buffer),
  `spatial-engine` (poligon + debounce), `ai-engine/` (YOLOv11n via `ort`,
  preprocess/postprocess/NMS), `core-ffi/` (UniFFI, engine + listener + watchdog).
- `android/` - aplikasi Kotlin single-activity (Compose). Modul `app`,
  `core:model`, `core:network`, `core:ui`, `core:database`, `feature:cameras`,
  `feature:tables`, `feature:auth`, `feature:home`, `feature:onboarding` ada.
  Alur utama: diagnostik perangkat -> login PIN -> Home adaptif -> tombol Scan
  CCTV -> preview Media3/demo + total meja kosong/terisi/unknown. Mapping poligon
  per `camera_id`, Room v3 + outbox lokal, dan binding FFI tersedia. Discovery CCTV
  (WS-Discovery + probe port /24) sudah diimplementasikan di `core:network` dan
  baru teruji di host, bukan di jaringan nyata. Foreground service sudah ada;
  outbox lokal + `TableEventRecorder` sudah ada, tetapi worker sync HTTP belum;
  model ONNX belum dibundel, sehingga warm-up engine berjalan hanya bila model
  terpasang (saat ini laporan berhenti di `skipped` + profil `lite`).
  `:app:assembleDebug` menghasilkan APK di host dan
  `:app:lintDebug` 0 error (gate 2026-11-26); APK belum dijalankan di perangkat.
- `server/` - Axum + SQLite: /health, /v1/sync, /v1/heartbeat, /v1/snapshot, /v1/ws; dashboard web belum dibuat. Lihat docs/server-implementation.md dan ADR 0010.
- `contracts/` - enam JSON Schema draft 2020-12, fixture kanonik, dan crate validasi standalone. Gate: `cd contracts; cargo test`.
- `docs/` - acuan desain. Baca dulu sebelum mengubah perilaku.

Dokumen yang **wajib dibaca** sesuai topik:

| Topik | Dokumen |
| --- | --- |
| Alur UI/UX Kotlin, wireframe, urutan fase | `docs/android-app-plan.md` |
| Mapping meja per kamera, profil banyak CCTV, satu sesi aktif | `docs/table-mapping-plan.md`, `docs/multi-camera-plan.md` |
| Batas UI vs core, peta file test, urutan TDD | `docs/tdd-plan.md` |
| Status implementasi dan bukti | `docs/status.md` |
| Prioritas MVP, urutan P0/P1/P2 Kotlin & Rust, gate resource | `docs/mvp-priority.md` |
| Simulasi & rencana uji 24 FPS di perangkat 4 GB | `docs/fps-24-4gb-plan.md` |
| File produksi Rust, baseline versi crate | `docs/rust-scaffold.md` |
| Skema poligon, status, heartbeat, ACK | `docs/data-contracts.md` |
| Room, outbox, retensi, WorkManager | `docs/android-sync.md` |
| Jaringan CCTV -> Android -> VPS | `docs/network-topology.md` |
| Setup kamera EZVIZ, RTSP, auth, QR | `docs/camera-setup.md` |
| Diagnostik perangkat dan profil runtime | `docs/device-compatibility.md` |
| Kriteria akurasi dan uji lapangan | `docs/acceptance.md` |
| Target FPS, anggaran frame, protokol uji resource berbasis video | `docs/performance.md` |
| Kontrak API server metadata | `docs/server-api.md` |
| Signing, distribusi, model, rilis | `docs/release.md` |
| Struktur folder | `docs/structure.md` |
| Keputusan arsitektur | `docs/adr/` |

## 2. Skill wajib

Skill berikut sudah terpasang global di `~/.agents/skills/` (Codex). **Muat skill yang
relevan sebelum menulis kode**, bukan setelah. Untuk pekerjaan Kotlin/Compose yang
mencakup beberapa concern, mulai dari router `using-chrisbanes-skills`, lalu muat satu
skill fokus per keputusan.

**Langkah 0 - wajib untuk SETIAP tugas** (termasuk yang terlihat hanya dokumentasi):

1. Buka daftar di bawah dan tentukan skill yang menyentuh tugas ini.
2. Muat skill tersebut sebelum membaca kode mendalam atau menulis perubahan.
3. Sebutkan di ringkasan akhir skill mana yang dipakai, dan bila tidak ada yang cocok,
   sebutkan alasannya. Melewati langkah ini tanpa alasan dianggap pelanggaran aturan repo.

Peta cepat permintaan produk ke skill:

| Kebutuhan | Skill utama |
| --- | --- |
| Login, form PIN, navigasi layar | `navigation-3`, `kotlin-api-design`, `compose-state-and-effects` |
| Tombol Scan CCTV, state sheet, koneksi kamera | `compose-state-and-effects`, `kotlin-concurrency-and-flow`, `kotlin-control-flow` |
| Stream CCTV + overlay kanvas, pinch/pan, koordinat 0..1 | `compose-component-design`, `compose-performance`, `compose-ui-testing-patterns` |
| Kartu total meja kosong/terisi di bawah stream | `compose-performance`, `compose-state-and-effects`, `adaptive` |
| Portrait & landscape, HP/tablet | `adaptive`, `edge-to-edge` |
| Room, WorkManager, outbox | `kotlin-concurrency-and-flow`, `testing-setup` |
| Build APK, lint, install ke perangkat | `gradle-run`, `android-cli` |
| RTSP, ring buffer, FFI, panic boundary | `rust-skills`, `rust-async-patterns`, `android-jni-security` |
| ONNX/YOLO, geometri poligon | `rust-skills`, `rust-best-practices`, `android-profiler` |

### Rust

| Skill | Wajib dipakai saat |
| --- | --- |
| `rust-skills` | Setiap menulis, mengubah, atau me-review kode Rust (265 aturan) |
| `rust-best-practices` | Menulis fungsi baru, refactor, review idiom dan error handling |
| `rust-async-patterns` | Tokio, task, channel, cancellation, trait async, FFI callback |
| `android-jni-security` | Menyentuh batas JNI/UniFFI, `extern "C"`, `System.loadLibrary`, panic boundary |

### Kotlin / Android

| Skill | Wajib dipakai saat |
| --- | --- |
| `using-chrisbanes-skills` | Router: pekerjaan Kotlin/Compose dengan >1 concern |
| `compose-state-and-effects` | State ownership, `remember`, hoisting, `LaunchedEffect`, `DisposableEffect`, koleksi Flow |
| `compose-performance` | Recomposition, stability compiler, `derivedStateOf`, state read per frame, jank |
| `jetpack-compose-performance` | Audit performa Compose menyeluruh (7 kategori + baseline profile) |
| `compose-component-design` | API composable reusable, parameter `modifier`, slot konten |
| `compose-ui-testing-patterns` | Test Compose, screenshot, semantics, assertion fokus/keyboard |
| `kotlin-concurrency-and-flow` | Coroutine scope, `StateFlow`/`SharedFlow`/`Channel`, `stateIn`, cancellation |
| `kotlin-api-design` | Kepemilikan fungsi, value class, data class, seam platform/FFI |
| `kotlin-control-flow` | `when`, guard, sealed exhaustiveness, smart cast |
| `adaptive` | Layout responsif HP/tablet/foldable, window size class, input pointer |
| `edge-to-edge` | Inset system bar, IME, status bar legibility |
| `navigation-3` | Navigasi, back stack, deep link, scene dua panel |
| `android-permissions-security` | `AndroidManifest.xml`, permission runtime, service/receiver/provider |
| `android-intent-security` | Intent masuk, komponen exported, redirect |
| `testing-setup` | Menyiapkan harness unit/UI/screenshot/E2E |
| `android-profiler` | Trace Perfetto, jank, ANR, memori, soak test |
| `android-cli` | `android create`, emulator, install APK, inspeksi artefak, render preview |
| `gradle-run` | Setiap eksekusi Gradle (`./gradlew`) dan diagnosis kegagalan build |

Catatan tool: `android` CLI sudah terpasang di
`C:\Users\nhaid\AppData\AndroidCLI\android.exe` (versi 1.1.16919734, SDK
`D:\app\sdk`). Jalankan dengan `--no-metrics`. Skill `android-cli` dimuat ulang oleh
`android init`; jangan menyalin isinya ke repo.

### Lintas tugas

| Skill | Wajib dipakai saat |
| --- | --- |
| `gitpush` | **Setiap task selesai**: satu task = satu commit (lihat §4 butir 10) |
| `find-skills` | Butuh skill baru yang belum terpasang |

Aturan pemakaian:

1. Muat skill **sebelum** menulis kode pada area tersebut; jangan mengandalkan ingatan.
2. Bila dua skill bertentangan, ikuti yang paling spesifik ke file yang disentuh.
3. Bila skill meminta bukti (trace, screenshot, compiler report) dan bukti belum ada,
   tulis sebagai risiko/verifikasi tertunda - jangan mengarang angka.
4. Skill tidak menggantikan `docs/`; keduanya berlaku bersamaan.

## 3. Pustaka dan tool acuan wajib

Gunakan pustaka di bawah. Menambah/mengganti pustaka inti butuh alasan tertulis di
`docs/` dan, bila keputusan arsitektural, ADR baru di `docs/adr/`.

### Kotlin / Android

- **Jetpack Compose + Material 3** - UDF, `StateFlow`, pemisahan composable
  stateful/stateless, target tanpa jank 60/120 FPS. Acuan: `android/compose-samples`,
  `skydoves/compose-stability-analyzer`.
- **Compose `PointerInput` + gesture** - pinch-zoom, pan, konversi sentuhan layar ke
  koordinat ternormalisasi `0.0..1.0` (ADR 0008). Efek visual opsional: `chrisbanes/haze`.
- **Room 2.8.x + KSP** - konfigurasi meja, status terkini, outbox.
- **WorkManager 2.12.x** - sinkronisasi outbox, heartbeat, pruning.
- **Media3 1.9.x** (`media3-exoplayer-rtsp`, `media3-ui-compose`) - kandidat preview
  RTSP; pilihan decoder menunggu keputusan ADR 0009.
- **Hilt**, **DataStore**, **androidx.biometric**, **kotlinx.serialization**,
  **kotlinx.coroutines** - sesuai `docs/android-app-plan.md` §11.
- Build lewat CLI (`./gradlew`), bukan Android Studio 2023.3 (AGP 8.13 belum didukung).

### Rust

- **`cargo-ndk` 4.1.2** (terpasang) - cross-compile ke `arm64-v8a`/`armeabi-v7a`.
  Sudah diverifikasi untuk `core-ffi` pada tiga ABI lewat
  `tools/android-build/build-rust.ps1`.
- **UniFFI 0.32.2** (`mozilla/uniffi-rs`) - bindings Kotlin, tanpa JNI manual.
- **`ort` `=2.0.0-rc.15`** (`pykeio/ort`) - inferensi YOLOv11n ONNX di perangkat.
  Masih RC: pin exact, naikkan versi secara sadar.
- **`geo` 0.33** (`georust/geo`) - `Polygon`, `Contains` untuk point-in-polygon.
- **`ringbuf` 0.6.0** - ring buffer frame; API berbeda dari 0.3.
- **`retina` 0.4.22** - klien RTSP, di balik feature `rtsp` yang opt-in.
- **`pretty-hex` `=0.4.2`** - pin wajib; 0.4.3 merusak build `retina` 0.4.22.
- **`tokio`, `futures`, `url`, `serde`, `serde_json`, `thiserror`, `tracing`** - sesuai
  `docs/rust-scaffold.md` §2.

Catatan: `spatial-engine` saat ini mengimplementasikan geometri sendiri (tanpa `geo`) dan
ring buffer sendiri (tanpa `ringbuf`). Jangan menambah dependensi hanya agar "sesuai
daftar": pakai pustaka di atas ketika kode baru memang membutuhkannya, dan jaga
`spatial-engine` tetap ringan.

## 4. Aturan kerja

1. **Test dulu** (red-green-refactor). Satu commit berisi test + implementasi untuk
   perilaku yang sama. Nama test menyebut perilaku, bukan nama fungsi.
2. **UI tidak menghitung status meja.** Poligon, debounce, dan status final ada di Rust;
   Kotlin hanya memetakan event ke tampilan (`docs/tdd-plan.md` §2).
3. **Offline-first.** Jangan menambah ketergantungan jaringan pada alur login, scan, atau
   tampilan status. VPS opsional.
4. **Tanpa bottom navigation/drawer.** Alur: login -> home -> tombol Scan CCTV -> stream
   + total meja kosong/terisi/unknown. Layout adaptif berdasarkan lebar, bukan orientasi.
5. **`unknown` tidak boleh dilebur menjadi kosong.** Kamera mati tidak sama dengan meja
   kosong.
6. **Jangan commit** media besar (`*.mp4`, `*.mkv`, frame), model (`*.onnx`),
   `jniLibs/`, `local.properties`, atau kredensial. Sudah ada di `.gitignore`; jangan
   melemahkannya.
7. **Jangan log kredensial.** URL RTSP lengkap, password, PIN, dan token tidak boleh
   muncul di log, pesan error, atau crash report. Gunakan redaksi terpusat.
8. **Jangan menurunkan versi pin** (`ort`, `pretty-hex`, MSRV 1.96) tanpa menjalankan
   seluruh gate dan mencatat alasannya.
9. **Jangan mengubah dokumen keputusan** (ADR, `android-app-plan.md`) tanpa menyebut
   perubahan itu ke pengguna.
10. **Setiap task selesai di-commit lewat skill `gitpush`.** Satu task = satu commit
    berformat `[Tipe] deskripsi`, supaya riwayat tidak menumpuk menjadi satu commit
    raksasa. Jalankan alur skill: `git status --short` -> verifikasi -> stage path milik
    task itu saja (`git add <path>`, **jangan** `git add -A`, karena working tree sering
    memuat WIP lain) -> `git push` ke branch aktif. Jangan commit bila gate belum hijau,
    perubahan belum lengkap, atau pengguna menyatakan "jangan commit" pada giliran itu;
    laporkan apa yang tertunda beserta alasannya.
11. **PowerShell**: gunakan `shell: powershell`; `Remove-Item` diblokir kebijakan - hapus
    file lewat `apply_patch` atau `[System.IO.File]::Delete()`. File `.md` UTF-8 tanpa BOM;
    verifikasi dengan `[System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)`.
12. Cargo/rustup menulis progres ke stderr; exit code PowerShell bisa menyesatkan. Cek
    `$LASTEXITCODE` setelah perintah.
13. **Jangan membuat atau memuat gambar.** Agen tidak boleh menghasilkan gambar (mockup,
    diagram raster, screenshot sintetis) dan tidak boleh membuka file gambar ke konteks.
    Bila analisis visual benar-benar diperlukan, lakukan lewat skrip Python (mis. Pillow
    atau OpenCV) yang mencetak angka/teks - bukan dengan menampilkan gambar. Diagram
    teknis ditulis sebagai ASCII/markdown, bukan berkas gambar.

## 5. Gate verifikasi

Jalankan sebelum menyatakan pekerjaan selesai. Gate yang tidak bisa dijalankan (mis.
tidak ada perangkat) harus disebutkan sebagai belum diverifikasi, bukan dianggap lulus.

Kontrak (dari `contracts/`, crate standalone dengan `[workspace]` sendiri):

```powershell
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo audit
cargo deny check
```

Rust (dari `rust-core/`):

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo clippy -p stream-engine --all-targets --features rtsp -- -D warnings
cargo test -p stream-engine --features rtsp
cargo clippy -p core-ffi --features cli --all-targets -- -D warnings
cargo ndk -t arm64-v8a -P 26 build -p core-ffi --release
cargo ndk -t armv7-linux-androideabi -P 26 build -p core-ffi --release
cargo ndk -t x86_64 -P 26 build -p core-ffi --release
cargo audit
cargo deny check
powershell -ExecutionPolicy Bypass -File tools/android-build/build-rust.ps1
```

Uji ONNX nyata (opsional, host-only; model fixture dibuat lokal dan tidak di-commit):

```powershell
python tools/onnx-test/gen_synthetic_model.py --size 640 --output "$env:TEMP\cctv-synthetic-640.onnx"
python tools/onnx-test/gen_synthetic_model.py --size 32  --output "$env:TEMP\cctv-synthetic-32.onnx"
cmd /c "set CCTV_ONNX_TEST_MODEL=%TEMP%\cctv-synthetic-32.onnx&& cargo test -p ai-engine --test onnx_inference"
cmd /c "set CCTV_ONNX_TEST_MODEL_640=%TEMP%\cctv-synthetic-640.onnx&& cargo test -p core-ffi --test onnx_engine"
```

Kotlin (dari `android/`):

```powershell
./gradlew :core:model:test :core:network:test :core:ui:test
./gradlew :core:database:testDebugUnitTest
./gradlew :feature:cameras:testDebugUnitTest :feature:tables:testDebugUnitTest
./gradlew :feature:auth:testDebugUnitTest :feature:home:testDebugUnitTest
./gradlew :feature:onboarding:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Status gate pada 2026-11-26 (lewat wrapper `gradle-run`): 383 test hijau
(`:core:model:test` 44 - termasuk `ContractSchemaTest` terhadap schema bersama,
`:core:network:test` 43 - termasuk `ConnectionTesterTest` dan
`SocketRtspSessionProbeTest` terhadap server RTSP loopback,
`:core:ui:test` 6 (`TimeFormat`), `:core:database:testDebugUnitTest` 11, `:feature:cameras:testDebugUnitTest` 37,
`:feature:tables:testDebugUnitTest` 53, `:feature:auth:testDebugUnitTest` 36,
`:feature:home:testDebugUnitTest` 56, `:feature:onboarding:testDebugUnitTest` 41,
`:app:testDebugUnitTest` 56), `:app:assembleDebug` menghasilkan APK 143 MB (tiga ABI resmi), dan
`:app:lintDebug` 0 error.
Warning lint yang tersisa: 25 soal versi dependensi yang sengaja dipin
(`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`).
Tidak ada warning kode; `InflateParams` di `CameraPreviewSurface` sudah diperbaiki
dengan parent `FrameLayout` pada `inflate`, dan haptic/biometrik login sudah diuji
di host (`PinKeypadHapticTest`, `BiometricUnlockerTest`, `LoginScreenTest`).

Gate satu perintah (opsional, disarankan sebelum merge):

```powershell
powershell -ExecutionPolicy Bypass -File tools/ci/verify.ps1
```

`-SkipNdk`, `-SkipAndroid`, atau `-SkipAudit` dipakai di mesin tanpa toolchain terkait;
script mencetak daftar tahap yang gagal dan exit 1. Instrumented test, kamera nyata, dan
soak tetap di luar script. Script memanggil `gradlew.bat` langsung untuk kebutuhan CI;
dalam sesi agen, jalankan Gradle lewat wrapper `gradle-run` dan pakai `-SkipAndroid`.

Uji performa dan resource (baterai, RAM, ROM, CPU, suhu) adalah **gate MVP**, bukan
pekerjaan akhir. Urutan prioritas ada di `docs/mvp-priority.md`; protokol pengukuran
berbasis video ada di `docs/performance.md` §10. Kamera nyata belum diperlukan untuk gate
ini, tetapi perangkat fisik diperlukan: tanpa `adb devices` yang terisi, item resource
tetap ditulis **belum diverifikasi**.

Gate merge: semua di atas hijau, tidak ada `TODO` baru di `feature/*/src/main`, dan
tidak ada kredensial di diff. Perubahan sudah ter-commit per task lewat `gitpush`
(butir 10 §4), bukan menumpuk sebagai satu diff besar yang belum di-commit.

## 6. Status terakhir yang tercatat

- Rust: `stream-engine`, `spatial-engine`, `ai-engine`, dan `core-ffi` aktif.
  Laporan 2026-11-26 mencatat 214 test workspace hijau, 66 di antaranya dengan
  feature `rtsp`; 2 test ONNX tambahan berjalan bila fixture
  `CCTV_ONNX_TEST_MODEL[_640]` disetel; `core-ffi` cross-compile hijau untuk
  `aarch64-linux-android`, `armv7-linux-androideabi`, dan
  `x86_64-linux-android` (API 26); `cargo audit` + `cargo deny check` hijau
  dengan satu pengecualian build-time (`basic-toml`, tidak ikut APK).
  Seluruh perintah Rust di bagian gate dijalankan ulang 2026-11-26 setelah
  `camera_id` ditambahkan ke `EngineConfig`/`EngineHealth`, dan hijau.
- Android: aplikasi Compose terbangun. Alur diagnostik perangkat -> login PIN -> Home
  adaptif -> Scan CCTV -> preview Media3/demo + total meja kosong/terisi/unknown
  sudah ada, begitu juga editor polygon per `camera_id`, Room v3 + outbox lokal,
  `EngineStartPlanner`, dan `EngineFramePump` (frame `TextureView` 2 FPS ke Rust).
  `feature:onboarding` menilai platform/memori/engine/penyimpanan, memanggil
  warm-up Rust bila model terpasang, dan menyimpan hasil per `versionCode`; profil
  `blocked` tidak pernah membuka Home. Gate 2026-11-26: 555 test hijau dari 83
  `TEST-*.xml`,
  `:app:lintDebug` 0 error, `:app:assembleDebug` menghasilkan APK 143 MB
  (tiga ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`; `abiFilters` menolak `x86`/`mips` bawaan dependensi). Provider telemetri bawaan
  `onnxruntime-android` dihapus lewat `tools:node="remove"` agar tetap offline-first.
  Discovery CCTV ada di `core:network` (teruji host, belum di jaringan nyata).
  Diagnostik koneksi empat lapis (Wi-Fi lokal -> TCP -> RTSP DESCRIBE dengan
  Basic/Digest -> paket video pertama) ada di `core:network` dan dipakai editor
  kamera; `NetworkPolicy` memblokir RTSP saat seluler/offline tanpa menyamar
  sebagai "kamera rusak". Kegagalan preview dilaporkan ke Rust lewat
  `StreamFailureReporter`/`RustStreamFailureReporter` (`reportStreamError`) supaya
  tabel langsung `unknown`, bukan menunggu watchdog 5 dtk; poll status tidak boleh
  mengembalikan `Live`/total basi selama video mati.
  Foreground service `mediaPlayback` + notifikasi pemantauan sudah ada (kanal `stream`,
  aksi Hentikan, `POST_NOTIFICATIONS` diminta saat stream pertama, `WAKE_LOCK`
  supaya RTSP tetap jalan saat layar mati). Outbox lokal + `TableEventRecorder`
  sudah ada (detail di `docs/android-sync.md`), dan pipeline sync HTTP-nya juga:
  `SyncWorker` (`@HiltWorker`), `SyncScheduler` (debounce 30 dtk, clamp 10 dtk,
  `restart` untuk "sync now"), `KtorSyncTransport`, `SyncRunner` dengan retry
  ladder persisten, dan modul `core:sync` (coordinator/backoff/endpoint). Heartbeat
  memakai rantai `OneTimeWorkRequest` 30 dtk (live) / 60 dtk (idle) lewat
  `HeartbeatScheduler` + `HeartbeatRunner` + `KtorHeartbeatTransport`, dimulai dan
  dihentikan oleh `MonitoringHub`. Layar "Sinkronisasi server" di `feature:settings`
  menyimpan base URL + token Bearer (masih DataStore, belum Keystore). Yang belum ada:
  server nyata, `/v1/snapshot`, `table_logs`, model ONNX yang dibundel, dan verifikasi
  perangkat.
  Rencana lengkap ada di `docs/android-app-plan.md`.
  Binding Kotlin UniFFI dihasilkan ke `android/app/build/generated/uniffi/kotlin`
  oleh `tools/android-build/build-rust.ps1`.
- Toolchain lokal: JDK 17 (`D:\app\android-studio\jbr`), SDK `D:\app\sdk` (platform 36,
  build-tools 35.0.0), NDK `D:\app\sdk\ndk\28.2.13676358`, `cargo-ndk 4.1.2`,
  `android` CLI 1.1.16919734.
- Perangkat fisik terpasang sejak 2026-11-27: `RRCY8074D3M` (SM-A176B, API 36,
  `arm64-v8a`), `MemTotal` 7 641 104 kB (~7,3 GiB) - 8 GB, bukan 4 GB.
  `tools/device-bench.ps1` sudah dijalankan dua kali: p50 `pushFrame` 209-239 ms
  (640x360) dan 222-256 ms (640x640), laju analisis 3-4 FPS, status termal `NONE`.
  Terverifikasi 2026-11-27: `tools/device-e2e.ps1` (10/15/24 FPS diminta ->
  4,5/3,8/3,8 capture & analysis FPS, `tables=2`, `modelReady=true`) dan
  `tools/device-soak.ps1` 30 menit (30 sampel, PSS 158 122->246 160 kB, RSS
  279 392->372 608 kB, suhu 33,7->34,9 C, thermal `NONE`, 3 561 frame masuk /
  2 786 sampel; baterai 100%->100% selama USB powered sehingga **bukan** bukti
  drain). Perbaikan jadwal sampler `core-ffi` (toleransi 10% interval, maks
  50 ms) menurunkan drop 10 menit pembanding dari 214 menjadi 0. Belum
  terverifikasi: soak 8 jam layar mati, kamera CCTV nyata, dan klaim RAM pada
  perangkat 4 GB. Tetap tidak ada AVD yang memadai, `ffmpeg`, `mediamtx`,
  `vlc`, atau `docker`.
