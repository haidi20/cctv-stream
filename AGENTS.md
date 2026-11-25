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
- `android/` - aplikasi Kotlin (belum di-scaffold), Compose + Room + WorkManager.
- `server/` - penerima metadata + dashboard (belum dibuat).
- `contracts/` - JSON Schema + fixture kanonik.
- `docs/` - acuan desain. Baca dulu sebelum mengubah perilaku.

Dokumen yang **wajib dibaca** sesuai topik:

| Topik | Dokumen |
| --- | --- |
| Alur UI/UX Kotlin, wireframe, urutan fase | `docs/android-app-plan.md` |
| Batas UI vs core, peta file test, urutan TDD | `docs/tdd-plan.md` |
| File produksi Rust, baseline versi crate | `docs/rust-scaffold.md` |
| Skema poligon, status, heartbeat, ACK | `docs/data-contracts.md` |
| Room, outbox, retensi, WorkManager | `docs/android-sync.md` |
| Jaringan CCTV -> Android -> VPS | `docs/network-topology.md` |
| Target FPS dan anggaran frame | `docs/performance.md` |
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
| `gitpush` | **Hanya** bila pengguna eksplisit minta commit/push |
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
- **Media3 1.9.x** (`media3-exoplayer-rtsp`, `media3-ui-compose`) - preview RTSP MVP.
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
10. **Commit/push hanya bila diminta eksplisit**, mengikuti skill `gitpush`.
11. **PowerShell**: gunakan `shell: powershell`; `Remove-Item` diblokir kebijakan - hapus
    file lewat `apply_patch` atau `[System.IO.File]::Delete()`. File `.md` UTF-8 tanpa BOM;
    verifikasi dengan `[System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)`.
12. Cargo/rustup menulis progres ke stderr; exit code PowerShell bisa menyesatkan. Cek
    `$LASTEXITCODE` setelah perintah.

## 5. Gate verifikasi

Jalankan sebelum menyatakan pekerjaan selesai. Gate yang tidak bisa dijalankan (mis.
tidak ada perangkat) harus disebutkan sebagai belum diverifikasi, bukan dianggap lulus.

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

Kotlin (dari `android/`, setelah scaffold ada):

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Gate merge: semua di atas hijau, tidak ada `TODO` baru di `feature/*/src/main`, dan
tidak ada kredensial di diff.

## 6. Status saat ini

- Rust: `stream-engine`, `spatial-engine`, `ai-engine`, dan `core-ffi` aktif.
  183 test workspace hijau, 66 di antaranya dengan feature `rtsp`; `core-ffi`
  cross-compile hijau untuk `aarch64-linux-android`, `armv7-linux-androideabi`,
  dan `x86_64-linux-android` (API 26); `cargo audit` + `cargo deny check` hijau
  dengan satu pengecualian build-time (`basic-toml`, tidak ikut APK).
- Android: baru scaffold folder; rencana lengkap ada di `docs/android-app-plan.md`.
  Binding Kotlin UniFFI dihasilkan ke `android/app/build/generated/uniffi/kotlin`
  oleh `tools/android-build/build-rust.ps1`; belum dikompilasi Gradle.
- Toolchain lokal: JDK 17 (`D:\app\android-studio\jbr`), SDK `D:\app\sdk` (platform 36,
  build-tools 35.0.0), NDK `D:\app\sdk\ndk\28.2.13676358`, `cargo-ndk 4.1.2`,
  `android` CLI 1.1.16919734.
- Belum ada AVD/perangkat terhubung (`adb devices` kosong), `ffmpeg`, `mediamtx`,
  `vlc`, atau `docker`. Instrumented test, verifikasi kamera nyata, dan akurasi
  model ONNX di perangkat belum bisa dijalankan.
