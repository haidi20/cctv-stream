# Skeleton Rust core

Dokumen ini menetapkan **file produksi** tiap crate, baseline dependensi, dan koreksi
terhadap draft kode pada percakapan perencanaan (link Gemini di README). Peta file test
ada di [`tdd-plan.md`](tdd-plan.md); dokumen ini hanya soal kode produksi dan build.

## 1. Status sumber

Draft di percakapan Gemini memakai versi crate dan API yang sudah tidak berlaku
(`ringbuf` 0.3, feature `ort/ndk`, `setup_scaffolding!("nama")`). Semua angka versi dan
bentuk API di bawah sudah dicek ke crates.io dan docs.rs pada **2026-11-25**. Bila
menulis kode, ikuti dokumen ini, bukan draft lama.

## 2. Baseline dependensi

| Crate | Versi | MSRV | Catatan |
| --- | --- | --- | --- |
| `tokio` | 1.54 | - | `features = ["rt-multi-thread", "time", "sync", "macros"]` |
| `retina` | 0.4.22 | 1.96 | Klien RTSP; default feature `h265` |
| `ringbuf` | 0.6.0 | - | API berubah dari 0.3; lihat §3 |
| `ort` | 2.0.0-rc.15 | 1.92 | **Belum stabil**; pin exact `=2.0.0-rc.15` |
| `ndarray` | 0.17 | 1.88 | Tensor input/output |
| `image` | 0.25 | 1.88 | Dekode JPEG/PNG untuk frame uji |
| `geo` | 0.33 | 1.88 | `Polygon`, `Contains` |
| `serde` | 1.0 | - | `derive` |
| `serde_json` | 1.0 | - | Kontrak JSON |
| `uniffi` | 0.32.2 | - | Proc-macro; UDL tidak wajib |
| `tracing` | 0.1.44 | - | Log terstruktur |
| `thiserror` | 2.0 | - | Error per crate |
| `tokio-util` | 0.7.20 | - | `CancellationToken` untuk stop bersih |

Aturan versi:

- `ort` memakai `=` karena masih RC; naikkan versi secara sadar, bukan otomatis.
- Crate stabil memakai caret (`"1.54"`), dan `Cargo.lock` **di-commit** untuk aplikasi.
- MSRV workspace mengikuti MSRV tertinggi (1.96 dari `retina`); tulis di
  `rust-toolchain.toml`.

## 3. Koreksi terhadap draft Gemini

| Hal | Draft lama | Yang benar di versi sekarang |
| --- | --- | --- |
| Tipe ring buffer | `HeapProd<T>` / `HeapCons<T>` | Tidak ada. `HeapRb<T>` adalah alias `SharedRb<Heap<T>>`; `split()` mengembalikan `(Prod, Cons)` |
| Push/pop | `push()` / `pop()` | `try_push()` / `try_pop()`; untuk menimpa frame lama pakai `push_overwrite()` |
| Baca frame | `consumer.pop()` per frame | `as_slices()` / `try_pop()`; hindari alokasi per frame |
| Feature `ort` | `features = ["ndk"]` | Tidak ada feature `ndk`. Pakai `nnapi` (Android), atau CPU default; `xnnpack` opsional |
| Versi `ort` | `"2.0"` | Belum ada rilis stabil; pakai `=2.0.0-rc.15` |
| Scaffolding UniFFI | `uniffi::setup_scaffolding!("restocore")` | 0.32: `setup_scaffolding!()` tanpa argumen |
| File UDL | Wajib `restocore.udl` | Mode proc-macro tidak butuh UDL; `#[uniffi::export]` cukup |
| `build-dependencies` uniffi | `features = ["build"]` | Tidak perlu untuk mode proc-macro |
| Panic boundary | `catch_unwind` + `panic = "abort"` | Keduanya tidak bisa dipakai bersamaan; pilih salah satu (lihat §6) |

## 4. Struktur file produksi

```text
rust-core/
├── Cargo.toml                 # workspace, resolver 2, shared deps
├── rust-toolchain.toml         # pin channel (MSRV 1.96)
├── .cargo/config.toml          # linker NDK per target
├── stream-engine/src/
│   ├── lib.rs                  # re-export, enum Source
│   ├── rtsp_client.rs          # retina: describe/setup/play/demuxed
│   ├── file_source.rs          # baca mp4 + pacing FPS
│   ├── ring_buffer.rs          # HeapRb<Frame>, push_overwrite
│   ├── frame_sampler.rs        # sampling 2-5 FPS
│   ├── reconnect.rs            # backoff + jitter
│   ├── metrics.rs              # fps_in, reconnect, frame_age
│   └── error.rs                # StreamError (thiserror) + StreamErrorKind
├── ai-engine/src/
│   ├── lib.rs
│   ├── preprocess.rs           # letterbox + normalisasi + NCHW
│   ├── postprocess.rs          # decode output YOLO
│   ├── nms.rs                  # IoU suppression
│   ├── detector.rs             # ort::Session, EP selection
│   ├── geometry.rs             # skala balik ke resolusi asli
│   └── error.rs
├── spatial-engine/src/
│   ├── lib.rs                  # SpatialEngine::update
│   ├── polygon.rs              # geo::Polygon, contains
│   ├── loader.rs               # validasi config JSON
│   ├── centroid.rs
│   ├── debounce.rs             # clock diinjeksi
│   ├── state_machine.rs        # available/occupied/unknown
│   ├── multi_table.rs          # assignment terdekat
│   └── error.rs
└── core-ffi/src/
    ├── lib.rs                  # setup_scaffolding!()
    ├── api.rs                  # start/stop/set_tables/health
    ├── callback.rs             # listener event
    ├── panic_guard.rs
    └── runtime.rs              # Tokio runtime + shutdown
```

File test tidak didaftar di sini. Lihat `tdd-plan.md` §3.

## 5. Potongan Cargo.toml

Workspace:

```toml
[workspace]
members = ["stream-engine", "ai-engine", "spatial-engine", "core-ffi"]
resolver = "2"

[workspace.package]
edition = "2021"
rust-version = "1.96"

[workspace.dependencies]
tokio = { version = "1.54", features = ["rt-multi-thread", "time", "sync", "macros"] }
tokio-util = "0.7.20"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
thiserror = "2.0"
tracing = "0.1.44"

[profile.release]
lto = "thin"
codegen-units = 1
strip = "symbols"
```

`core-ffi`:

```toml
[lib]
crate-type = ["cdylib", "staticlib"]

[dependencies]
stream-engine = { path = "../stream-engine" }
ai-engine = { path = "../ai-engine" }
spatial-engine = { path = "../spatial-engine" }
uniffi = { version = "0.32.2", features = ["tokio"] }
```

Tanpa `[build-dependencies]` dan tanpa UDL. Bindings Kotlin digenerate dari anotasi
`#[uniffi::export]`.

## 6. Batas panic dan shutdown

Dua opsi, pilih sadar:

| Opsi | Profil | Cara menjamin panic tidak menyeberang FFI |
| --- | --- | --- |
| Unwind | default | Bungkus setiap entry point dengan `catch_unwind`, ubah panic jadi `EngineError` |
| Abort | `panic = "abort"` | Panic mematikan proses; foreground service harus restart; tidak boleh ada panic di jalur produksi |

Rekomendasi awal: **unwind + `catch_unwind`** agar satu frame rusak tidak mematikan
monitoring, dengan `unwrap()` dilarang di jalur input.

Stop harus memakai `CancellationToken`, bukan `abort()` thread: `stop()` menunggu worker
selesai maksimal 5 detik, lalu melaporkan timeout ke Kotlin.

## 7. Permukaan FFI minimal

| Fungsi / tipe | Sifat |
| --- | --- |
| `start(config: EngineConfig)` | Idempoten; start ganda mengembalikan error, bukan panic |
| `stop()` | Aman dipanggil sebelum start; menunggu shutdown |
| `set_tables(json: String)` | Validasi di Rust; tolak poligon tidak valid |
| `subscribe(listener: EngineListener)` | Callback interface UniFFI; non-blocking |
| `health()` | Snapshot metrik (lihat `data-contracts.md` §4) |
| `EngineConfig`, `TableStatus`, `EngineEvent` | Record/enum UniFFI; stabil antar rilis |

Kotlin hanya memanggil fungsi ini. Tidak ada pointer mentah, `Bitmap`, atau `Context`
yang menyeberang.

## 8. Build dan packaging

```bash
export ANDROID_NDK_HOME=/path/to/ndk
rustup target add aarch64-linux-android
cargo build --release --target aarch64-linux-android -p core-ffi
```

`.cargo/config.toml` berisi linker NDK untuk target tersebut. Hasil build:

```text
rust-core/target/aarch64-linux-android/release/libcore_ffi.so
  -> android/app/src/main/jniLibs/arm64-v8a/libcore_ffi.so
```

`jniLibs` tidak di-commit (lihat `.gitignore`); skrip penyalinan ada di
`tools/android-build/`. Model ONNX diletakkan di
`android/app/src/main/assets/models/yolov11n.onnx` dan juga tidak di-commit.

## 9. Gate sebelum lanjut ke fase berikutnya

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build --release --target aarch64-linux-android -p core-ffi
```

Empat perintah itu harus hijau sebelum masuk Fase 1 (lihat `roadmap.md`).