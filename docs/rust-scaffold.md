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
| `futures` | 0.3 | - | `StreamExt` untuk `Demuxed::next()` |
| `url` | 2 | - | Parse URL kamera; kredensial dipisah dari endpoint |
| `pretty-hex` | `=0.4.2` | - | Pin transitif; 0.4.3 menambah `autoskip` yang tidak diisi `retina` |
| `ringbuf` | 0.6.0 | - | API berubah dari 0.3; lihat §3 |
| `ort` | 2.0.0-rc.15 | 1.92 | **Belum stabil**; pin exact `=2.0.0-rc.15` |
| `ndarray` | 0.17 | 1.88 | Tensor input/output |
| `libc` | 0.2 | - | Hanya Android: `setpriority`/`gettid` untuk worker ORT di nice 10 |
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
- `pretty-hex` juga dipin tepat: 0.4.3 menambah field `autoskip` yang belum diisi
  `retina` 0.4.22 sehingga build gagal. `stream-engine` mendeklarasikannya sebagai
  dependency opsional yang tidak pernah diaktifkan supaya `cargo update` menghormati
  pin. Hapus pin setelah `retina` naik versi.
- Crate stabil memakai caret (`"1.54"`), dan `Cargo.lock` **di-commit** untuk aplikasi.
- MSRV workspace mengikuti MSRV tertinggi (1.96 dari `retina`); tulis di
  `rust-toolchain.toml`.
- `stream-engine` memakai `default = []`. Fitur `rtsp` mengaktifkan `futures`,
  `retina`, `tokio`, dan `url`, sehingga build dan test non-RTSP tidak menarik crate
  jaringan.

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
│   ├── polygon.rs              # geo::Polygon, contains di ruang 0..1
│   ├── geometry.rs             # konversi piksel frame <-> 0..1, cek rasio aspek
│   ├── loader.rs               # validasi config JSON (tolak koordinat di luar 0..1)
│   ├── centroid.rs
│   ├── debounce.rs             # clock diinjeksi
│   ├── state_machine.rs        # available/occupied/unknown
│   ├── view.rs                 # sidik jari luminans: kamera bergeser -> unknown (ADR 0011)
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
futures = "0.3"
retina = "0.4.22"
pretty-hex = "=0.4.2"   # pin transitif, lihat bagian 2
url = "2"
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

Catatan `retina`: `Drop` untuk `Demuxed` memanggil `tokio::spawn` untuk mengirim
`TEARDOWN` ke kamera. Menjatuhkannya di luar runtime Tokio akan panic
("there is no reactor running"), misalnya saat `stop()` dipanggil dari thread Kotlin.
`RtspSource` karena itu menyimpan demuxer sebagai `Option` dan menjatuhkannya di dalam
`block_on`, lalu memberi beberapa `yield_now` agar `TEARDOWN` sempat mencapai socket.

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

### 7.1 Kontrak frame `push_frame`

Kontrak ini dikunci oleh test di `rust-core/core-ffi/src/api.rs`
(`a_frame_with_the_wrong_payload_length_is_rejected_without_marking_the_camera_alive`,
`a_zero_sized_frame_is_rejected_without_panicking`,
`dimensions_whose_byte_count_overflows_are_rejected_without_panicking`,
`a_valid_frame_without_a_detector_still_proves_the_camera_is_alive`,
`a_frame_after_stop_is_rejected_and_does_not_advance_the_counters`) dan oleh test
Kotlin `:app:testDebugUnitTest --tests "id.warung.monitor.engine.*"`.

| Pertanyaan | Keputusan |
| --- | --- |
| Format byte | Packed RGB888, row-major, `width * height * 3`. Alpha dibuang (`Rgb888.pack`). |
| Ownership | **Disalin.** UniFFI memarshal `ByteArray` menjadi `Vec<u8>` milik Rust. Kotlin boleh memakai ulang array setelah `pushFrame` kembali; tidak ada pointer atau `DirectByteBuffer` hari ini. |
| Siapa yang memanggil | Kotlin (`EngineFramePump.pushFrameOffMain`) di `Dispatchers.Default`, bukan Main. Inferensi ONNX berjalan sinkron di thread pemanggil. |
| Ukuran maksimum | Lebar frame sumber dibatasi `EngineFramePump.MAX_FRAME_WIDTH = 640` px sebelum packing (tinggi mengikuti rasio). FFI tidak punya batas byte tetap; yang divalidasi adalah `width != 0`, `height != 0`, dan `width * height * 3` tidak overflow `usize`. |
| Payload salah | `EngineError::Frame`; `frames_in` **tidak** bertambah dan `last_frame_age_ms` tetap `0`, jadi frame rusak tidak bisa membuat kamera tampak hidup. |
| Dimensi nol / overflow | `EngineError::Frame`, tanpa panic. |
| Sesi lama | Frame yang masuk setelah `stop` ditolak `EngineError::NotRunning`; `stop` menaikkan generation agar frame in-flight tidak menyentuh sesi baru. |
| Frame saat inferensi sibuk | Tidak diantrekan dan tidak di-drop oleh FFI. `push_frame` memegang lock detektor, jadi pemanggil kedua menunggu sampai inferensi pertama selesai; itulah sebabnya laju capture nyata dibatasi biaya inferensi (~4 FPS untuk YOLO11n input 640), bukan angka yang diminta. Pembatasan laju ada di `inference_fps` (0.5-24 sejak 2026-11-27) dan di `InferenceBudget`/`EngineFramePump`. Frame yang lebih cepat dari jadwal tetap menaikkan `frames_in` (bukti kamera hidup) tetapi tidak menambah `frames_sampled`. |
| Model belum siap | Frame valid tetap menaikkan `frames_in`, lalu `push_frame` mengembalikan `EngineError::NotRunning` karena tidak ada detektor. Total meja tetap `unknown`, bukan nol. |
| Dua decoder | Kotlin hanya mengirim frame hasil capture `TextureView`; Rust tidak membuka RTSP selama jalur hybrid ini dipakai. Satu stream, satu decoder (Media3). Ini belum menggantikan decoder Rust (ADR 0009 masih Diusulkan). |

Konsekuensi yang harus dijaga: karena pemanggilan **sinkron dan tidak punya batas
waktu**, Kotlin wajib memanggil dari dispatcher background. Memanggil dari Main akan
memblokir frame clock selama inferensi (lihat test
`a_frame_is_pushed_off_the_main_thread`).

## 8. Build dan packaging

Build Android memakai `cargo-ndk` (bukan `cargo build` langsung), karena hanya
`cargo-ndk` yang mengisi linker dan `-P` (API level) dengan benar untuk ketiga
ABI:

```powershell
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
powershell -ExecutionPolicy Bypass -File tools/android-build/build-rust.ps1
```

Skrip itu melakukan tiga hal:

1. `cargo ndk -t <abi> -P 26 build -p core-ffi --release` untuk `arm64-v8a`,
   `armeabi-v7a`, dan `x86_64`.
2. Menyalin `libcore_ffi.so` ke `android/app/src/main/jniLibs/<abi>/`.
3. Membuat binding Kotlin lewat `uniffi-bindgen` ke
   `android/app/build/generated/uniffi/kotlin/`.

Catatan strip: `[profile.release] strip = "symbols"` menghapus `.symtab`, dan
`uniffi-bindgen --library` membaca metadata dari simbol non-dinamis. Karena itu
binding selalu dibuat dari build `debug` (tanpa strip), sedangkan `.so` yang
di-ship tetap hasil `release`. Jangan mengarahkan `--library` ke artefak release.

`jniLibs/` dan `android/app/build/` tidak di-commit (lihat `.gitignore`). Model
ONNX diletakkan di `android/app/src/main/assets/models/yolov11n.onnx` dan juga
tidak di-commit.

## 9. Gate sebelum lanjut ke fase berikutnya

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo clippy -p core-ffi --features cli --all-targets -- -D warnings
cargo ndk -t arm64-v8a -P 26 build -p core-ffi --release
cargo ndk -t armv7-linux-androideabi -P 26 build -p core-ffi --release
cargo ndk -t x86_64 -P 26 build -p core-ffi --release
cargo audit
cargo deny check
```

### 9.1 Uji inferensi ONNX nyata (host)

`cargo test --workspace` tidak membutuhkan model. Untuk membuktikan preprocess,
ONNX Runtime, dekode tensor, NMS, dan FFI benar-benar tersambung, buat model
fixture deterministik dari Python (butuh paket `onnx`):

```powershell
python tools/onnx-test/gen_synthetic_model.py --size 640 --output "$env:TEMP\cctv-synthetic-640.onnx"
python tools/onnx-test/gen_synthetic_model.py --size 32  --output "$env:TEMP\cctv-synthetic-32.onnx"
cmd /c "set CCTV_ONNX_TEST_MODEL=%TEMP%\cctv-synthetic-32.onnx&& cargo test -p ai-engine --test onnx_inference"
cmd /c "set CCTV_ONNX_TEST_MODEL_640=%TEMP%\cctv-synthetic-640.onnx&& cargo test -p core-ffi --test onnx_engine"
```

Model ini hanya memancarkan satu kotak `person` di tengah frame. Ia menguji
kontrak runtime dan geometri, **bukan** akurasi YOLOv11n pada rekaman nyata.
Tanpa environment variable, kedua test di-skip agar `cargo test` tetap hermetis.

Kebijakan dependensi ada di `rust-core/deny.toml` (target graph Android-only)
dan `rust-core/.cargo/audit.toml` (host graph, satu pengecualian build-time
`basic-toml` yang tidak ikut APK). Semua perintah itu harus hijau sebelum masuk
Fase 1 (lihat `roadmap.md`).
