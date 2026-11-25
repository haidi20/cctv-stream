# Rencana TDD

Seluruh peta file di dokumen ini adalah **target**, kecuali artefak yang
dinyatakan sudah ada di [`status.md`](status.md) atau [`testdata.md`](testdata.md).
Nama test di sini tidak membuktikan test tersebut sudah dibuat atau lulus.

Dokumen ini memetakan **file test mana yang ditulis lebih dulu**, isi tiap test, dan
batas tanggung jawab antara UI (Kotlin) dan core (Rust). Aturannya mengikuti
[`roadmap.md`](roadmap.md): setiap fase dimulai dengan test yang gagal, bukan dengan
implementasi.

## 1. Aturan main

1. **Test dulu, kode kemudian.** Satu siklus red-green-refactor per perilaku. Commit
   berisi test + implementasi untuk perilaku yang sama; test tidak boleh menyusul di
   commit terpisah.
2. **Satu test = satu alasan gagal.** Nama test menyebut perilaku, bukan nama fungsi:
   `point_on_edge_is_inside`, bukan `test_polygon_1`.
3. **Unit test di dalam `src/`** untuk logika privat dan cepat:
   `#[cfg(test)] mod tests`. **Integration test di `tests/`** untuk API publik crate.
4. **Tidak ada dependensi ke perangkat keras di CI.** RTSP diganti mock server, kamera
   diganti file video, waktu diganti clock injeksi, model ONNX diganti fixture kecil
   atau test ditandai `#[ignore]`.
5. **Determinisme.** Tidak ada `sleep` di test; pakai `tokio::time::pause` atau trait
   `Clock`. Test yang butuh waktu nyata diberi label `#[ignore]` dan dijalankan di job
   soak mingguan.
6. **Golden test untuk kontrak.** JSON yang dikirim ke server diuji terhadap
   `contracts/schemas/` dan `contracts/fixtures/`, bukan terhadap string literal.
   Sejak 2026-11-26 schema itu ada di `contracts/`; Rust (`contracts/tests/schema_test.rs`)
   dan Kotlin (`ContractSchemaTest` di `:core:model:test`) sama-sama membacanya langsung,
   jadi klaim tervalidasi lintas bahasa sekarang punya bukti test.
7. **UI tidak menguji matematika.** Poligon, debounce, dan status meja diuji di Rust.
   UI hanya menguji pemetaan event ke tampilan dan alur pengguna.
8. **Tidak mengejar coverage angka.** Target awal: logika inti (spatial, ring buffer,
   NMS, outbox, idempotensi) >= 90%; UI >= 60%.

## 2. Batas UI dan core

| Tanggung jawab | Lokasi | Diuji dengan |
| --- | --- | --- |
| Buka RTSP/file, demux access unit, ring buffer, frame sampling | `rust-core/stream-engine` | Unit + integration Rust |
| Klasifikasi error koneksi (timeout/refused/auth) | `rust-core/stream-engine` | Unit Rust + mock TCP |
| Inferensi YOLOv11n, pra/pasca-proses, NMS | `rust-core/ai-engine` | Unit + golden frame |
| Poligon, centroid, point-in-polygon, debounce, state machine | `rust-core/spatial-engine` | Unit Rust murni |
| Tipe FFI, lifecycle start/stop, event callback, panic boundary | `rust-core/core-ffi` | Integration Rust + instrumented Android |
| Gambar poligon, preview, warna status, form, navigasi | `android/.../ui` | Compose UI test + screenshot |
| ViewModel, pemetaan event ke state, validasi input | `android/.../ui` + `.../domain` | JVM unit test + fake |
| Zoom/pan preview dan konversi layar ke 0..1 | `android/.../ui` + `.../domain` | JVM unit test (matriks) + Compose gesture |
| Klasifikasi jaringan dan pesan "beda jaringan" | `android/.../domain` | JVM unit test + fake |
| Foreground service, permission, notifikasi, lifecycle OS | `android/.../service` | Instrumented test |
| Outbox Room, WorkManager, retry, heartbeat builder | `android/.../data` | Room in-memory + fake server |
| Endpoint sync/heartbeat, idempotensi, gap `seq` | `server` | Integration Rust + DB test |
| Skema JSON & fixture valid/invalid | `contracts` | Schema test |

Aturan tegas:

- Rust **tidak** boleh bergantung pada tipe Android (`Context`, `Bitmap`, `Log`).
  Semua input berupa byte/struct; semua output berupa event/struct.
- Kotlin **tidak** menghitung status meja. Kotlin hanya menampilkan `TableStatus` yang
  datang dari FFI.
- Kotlin **tidak** melakukan ONNX atau menghitung status. Kontrak FFI menerima frame
  RGB melalui `push_frame`, tetapi aplikasi Kotlin yang membuka preview dan
  mengirim frame belum ada. Target "preview memakai surface milik engine" belum
  diputuskan; lihat ADR 0009.
- Validasi poligon di UI hanya untuk UX (misal minimal 3 titik); validasi otoritatif
  tetap di `spatial-engine`.

## 3. Peta file test Rust

Peta di bagian ini adalah **target**, bukan daftar test yang sudah ada. Saat audit
ini, test integration yang ada di `rust-core/stream-engine/tests/` adalah
`error_classification.rs`, `frame_sampler.rs`, `public_api.rs`,
`reconnect_policy.rs`, dan `rtsp_client_mock.rs`; test pacing, EOF, loop, dan PTS
`file_source` berada sebagai unit test di `src/file_source.rs` dengan fake decoder.
`tests/ring_buffer_integration.rs`, `tests/file_source.rs`, `tests/stream_metrics.rs`,
dan test soak belum ada. Jangan membaca nama test target di bawah sebagai bukti
implementasi.

### 3.1 Workspace

| File | Isi test |
| --- | --- |
| `rust-core/Cargo.toml` | Workspace manifest; tidak ada test, tetapi `cargo test --workspace` jadi gate |
| `rust-core/rust-toolchain.toml` | Pin toolchain; diverifikasi CI |
| `rust-core/clippy.toml` | Lint; `cargo clippy -- -D warnings` |

Gate Fase 0:

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all -- --check
```

### 3.2 stream-engine

File implementasi dan pasangan test target:

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/ring_buffer.rs` | unit di file + `tests/ring_buffer_integration.rs` | push/pop FIFO, overwrite saat penuh, tidak ada alokasi ulang, panic saat kapasitas 0 |
| `src/frame_sampler.rs` | `tests/frame_sampler.rs` | sampling 2-5 FPS, drop frame lama, timestamp monoton |
| `src/reconnect.rs` | `tests/reconnect_policy.rs` | backoff 1-2-4-8-16 detik, reset setelah sukses, batas maksimum, jitter deterministik |
| `src/file_source.rs` | `tests/file_source.rs` | baca video uji, EOF, file rusak, FPS terjadwal |
| `src/rtsp_client.rs` | `tests/rtsp_client_mock.rs` | connect, timeout, putus di tengah, kamera tak terjangkau, 401 tanpa retry storm, credential redaction, `Closed` saat kamera menutup stream |
| `src/error.rs` | `tests/error_classification.rs` | error I/O menjadi `Unreachable`, `Timeout`, `AuthFailed`, `Protocol`; tanpa URL/kredensial |
| `src/metrics.rs` | `tests/stream_metrics.rs` | hitung fps_in, reconnect count, last_frame_age |
| `src/lib.rs` | `tests/public_api.rs` | API publik stabil; `Source` enum |

Test yang ditandai `#[ignore]` (job soak, bukan CI biasa):

```text
tests/soak_ring_buffer.rs      # 8 jam, RSS stabil
tests/rtsp_live_ezviz.rs       # butuh kamera + env var RTSP_URL
```

Aturan mock RTSP: server TCP lokal yang mengirim respons RTSP minimal, bukan kamera
nyata. Test tidak boleh membuka port publik.

### 3.2.1 Perangkat belum satu jaringan dengan CCTV

Kasus nyata saat instalasi: HP masih di Wi-Fi lain, IP kamera salah, AP isolation aktif,
atau kamera berada di subnet yang berbeda. Engine **tidak** menebak dari prefix IP;
satu-satunya bukti adalah hasil koneksi TCP/RTSP.

| Skenario | Cara mensimulasikan | Harapan |
| --- | --- | --- |
| IP tidak terjangkau | `192.0.2.10:554` (TEST-NET-1) | `StreamErrorKind::Unreachable` dalam <= 5 detik, tanpa panic |
| Port tertutup | listener lokal yang langsung menutup koneksi | `Unreachable` (io `ConnectionRefused`), bukan timeout panjang |
| Host/IP salah ketik | nama host acak atau IP kosong | `Unreachable` (io DNS/route); UI menyarankan cek IP |
| Kredensial salah | mock RTSP membalas `401` | `AuthFailed`; **tidak** retry tiap detik agar akun tidak terkunci |
| AP isolation | dua host di Wi-Fi yang sama, port diblokir | `Unreachable`; UI menyarankan cek isolasi klien |
| Beda subnet tetapi dirutekan | mock server di host yang punya route | connect sukses; engine tidak menolak berdasarkan prefix |
| Tidak ada jaringan | `NetworkCapabilities` tanpa transport | Rust tidak dipanggil; Kotlin menampilkan `NoNetwork` |
| Hanya data seluler | `NetworkCapabilities` dengan transport `CELLULAR` saja | Rust tidak dipanggil; Kotlin menampilkan `WrongNetwork` |

Aturan:

- Timeout connect maksimum 5 detik; tidak ada retry tanpa backoff.
- Semua kegagalan koneksi memetakan meja ke `unknown`, **bukan** `available`.
- Error yang dikirim ke UI/server hanya enum + pesan generik; URL dan kredensial tidak
  pernah ikut.
- Kotlin yang memutuskan pesan ("tidak ada jaringan", "sambungkan ke Wi-Fi lokal",
  "kamera tidak terjangkau", "password salah") karena hanya Kotlin yang tahu status
  jaringan perangkat.
- `wrong_network` (hanya data seluler) tidak memanggil Rust sama sekali; tidak ada
  gunanya membuka koneksi RTSP ke IP privat dari jaringan seluler.

Test yang wajib ada:

```text
tests/rtsp_client_mock.rs      # loopback RTSP: 401, port tertutup, timeout, putus di tengah
tests/error_classification.rs  # io::Error -> StreamErrorKind
```

Mock RTSP memakai `127.0.0.1` dengan port ephemeral; kamera tak terjangkau disimulasikan
dengan port yang sudah ditutup, bukan alamat TEST-NET yang butuh rute keluar.

### 3.2.2 Zoom, pan, dan ruang koordinat mapping

Preview video hampir selalu lebih kecil dari frame asli, dan pengguna perlu memperbesar
area meja di pojok ruangan. Aturannya: **zoom hanya mengubah tampilan**. Data yang
disimpan selalu 0..1 terhadap frame (ADR 0008).

Tiga lapisan yang dipisah tegas:

| Lapisan | Tanggung jawab | Salah kaprah yang harus dihindari |
| --- | --- | --- |
| `ZoomState` (Kotlin) | skala 1..8x, offset pan, batas tepi | menyimpan koordinat meja di state zoom |
| `MappingTransform` (Kotlin) | matriks layar ke frame, koreksi letterbox | menganggap layar preview = frame |
| `spatial-engine` (Rust) | uji titik 0..1 terhadap poligon 0..1 | menerima koordinat layar atau piksel absolut |

Rumus konversi (dipakai di Kotlin, diuji unit). `zoom` adalah faktor di atas fit, bukan
faktor total:

    base      = min(viewport_w / frame_w, viewport_h / frame_h)
    content_x = (viewport_w - frame_w * base) / 2
    content_y = (viewport_h - frame_h * base) / 2

    screen_x = content_x + pan_x + zoom * base * frame_w * norm_x
    screen_y = content_y + pan_y + zoom * base * frame_h * norm_y

    norm_x = ((screen_x - content_x - pan_x) / (zoom * base * frame_w)).coerceIn(0, 1)
    norm_y = ((screen_y - content_y - pan_y) / (zoom * base * frame_h)).coerceIn(0, 1)

`content_x`/`content_y` adalah offset letterbox `ContentScale.Fit`; `base` adalah skala fit
yang sama. Dua hal ini wajib ikut dalam rumus. Kalau `base` dihilangkan, poligon tampak
benar hanya ketika viewport kebetulan seukuran frame.

`pan_x`/`pan_y` dalam **piksel layar**, bukan piksel frame, sehingga pan yang sama tidak
berubah makna saat zoom berubah. Konvensi ini yang dipakai semua test.

Bentuk alternatif yang **ditolak**: membagi `frame_w` lebih dulu lalu mengurangkan pan di
ruang ternormalisasi, atau memakai satu skala total tanpa memisahkan `base` dan `zoom`.
Ketiganya menghasilkan angka berbeda untuk gesture yang sama, jadi salah satunya akan
tampak "benar" di test dan salah di perangkat.

Test wajib:

| Test | Alasan gagal yang dijamin |
| --- | --- |
| `identity_mapping_at_zoom_1x` | transform tanpa zoom tidak mengubah koordinat |
| `touch_maps_to_same_table_at_2x` | zoom tidak menggeser poligon yang sudah ada |
| `round_trip_keeps_point` | layar ke frame ke layar dalam toleransi 0.5 px |
| `letterbox_offset_is_applied` | preview 16:9 di layar 20:9 tidak menggeser sumbu y |
| `pan_is_clamped_to_image_bounds` | gambar tidak bisa digeser keluar layar |
| `point_outside_frame_is_clamped` | sentuhan di area hitam tidak menghasilkan koordinat negatif |
| `zoom_reset_keeps_polygons` | reset zoom tidak mengubah data meja |

Aturan:

- Zoom tidak memanggil Rust; tidak ada round-trip FFI per gesture.
- Konversi terjadi sekali saat jari diangkat, bukan pada setiap `onTouch`.
- Titik di area letterbox di-klamp ke tepi frame, bukan dibuang diam-diam.
- Zoom level dan pan **tidak** disimpan ke konfigurasi; selalu mulai dari `fit`.
- Overlay poligon digambar di ruang layar hasil proyeksi, tetapi sumber datanya 0..1.

### 3.2.3 Uji end-to-end dengan video mock

Tanpa kamera, alur penuh tetap bisa diuji: file video menggantikan RTSP, poligon 0..1
dipasang statis, dan test memeriksa transisi status.

| Tingkat | Cara | Yang dibuktikan |
| --- | --- | --- |
| Rust integration (target) | `file_source` membaca klip pendek dari `testdata/videos/` | decode + sampling + debounce tanpa jaringan; decoder konkret dan test integrasi belum ada |
| Rust golden | frame hasil ekstraksi + model dummy | pra/pasca-proses deterministik |
| Android instrumented | engine asli + klip pendek, UI palsu | warna meja berubah sesuai `expected/*.json` |
| Manual | klip di `assets` lewat build `debug` | demo ke pemilik warung tanpa CCTV |

Aturan:

- Jalur mock hanya aktif di build `debug` atau bila `source.kind = "file"`; build
  `release` tidak boleh diam-diam jatuh ke file.
- URL RTSP asli **tidak** ditulis di kode (draft lama menaruh kredensial di sumber).
  Kredensial selalu dari pengaturan terenkripsi.
- Klip uji tidak di-commit; lihat `testdata.md` §2 dan §3.
- Path `app/src/main/assets/...` **tidak** bisa dibuka Rust sebagai file biasa; isinya
  berada di dalam APK. Test instrumented menyalin aset ke cache dir dulu, lalu
  meneruskan path filesystem ke `file_source`. Test Rust di host memakai path dari
  env var `TESTDATA_VIDEO`, bukan path aset Android.
- Test video tidak membuktikan akurasi lapangan; itu tetap Fase 8.

### 3.2.4 Dataset mock kanonik lintas bahasa

Rust dan Kotlin harus memakai angka yang **sama persis** untuk kasus acuan. Kalau tidak,
test hijau di satu sisi tidak membuktikan apa pun tentang sisi lain. Dataset ini disimpan
sebagai fixture dan dibaca oleh kedua test suite.

| Item | Nilai kanonik | Dipakai oleh |
| --- | --- | --- |
| Frame acuan | `1280x720`, `aspect_ratio` `16:9` | Rust golden, Kotlin `MappingTransformTest` |
| Poligon `meja-mock` | `(0.2,0.2) (0.4,0.2) (0.4,0.4) (0.2,0.4)` | `polygon_cases`, preview Android |
| Orang di dalam meja | piksel `(384,216)` -> normalisasi `(0.30,0.30)` | `state_machine`, expected `occupied` |
| Orang di luar meja | piksel `(1000,500)` -> normalisasi `(0.781,0.694)` | `state_machine`, expected `available` |
| Sentuhan setelah zoom | viewport `1280x720` (tanpa letterbox), layar `(740,410)`, `zoom=2`, `pan=(100,50)` -> `(0.25,0.25)` | `MappingTransformTest` |
| Klip mock | 10 detik, `960x540` (16:9), sudut tinggi | `file_source` integration + instrumented |

Aturan:

- Fixture poligon sudah ada di `contracts/fixtures/valid/meja-mock.json` dan dibaca Rust
  lewat `include_str!`; Kotlin membacanya dari `test/resources` yang sama (disalin build
  task), bukan diketik ulang di test.
- Angka di tabel ini tidak boleh "dibulatkan agar lulus". Kalau implementasi menghasilkan
  `0.2499`, perbaiki konversinya, bukan toleransinya.
- Toleransi yang diizinkan hanya `0.5 px` pada layar (sekitar `5e-4` di ruang 0..1 untuk
  lebar 1080 px); sisanya exact. Konversi satu arah diuji dengan `1e-4`.
- Menambah skenario baru berarti menambah baris di tabel ini **dan** fixture-nya pada
  commit yang sama (aturan §6).

### 3.3 ai-engine

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/preprocess.rs` | unit + `tests/preprocess_golden.rs` | letterbox resize, normalisasi 0-1, layout NCHW, padding benar |
| `src/postprocess.rs` | `tests/postprocess.rs` | decode output YOLO, filter confidence, hanya class `person` |
| `src/nms.rs` | `tests/nms.rs` | IoU threshold, suppress overlap, urutan confidence, kotak bersentuhan |
| `src/detector.rs` | `tests/detector_fixture.rs` | load model fixture, 0/1/2 orang pada frame uji |
| `src/geometry.rs` | unit | konversi kotak letterbox kembali ke koordinat asli |

Golden test:

- Input: frame kecil di `rust-core/ai-engine/testdata/frames/`.
- Expected: `testdata/expected/*.json` berisi kotak + confidence dengan toleransi
  (`±2 px`, `±0.02` confidence), bukan exact match.
- Model asli (`yolov11n.onnx`) tidak di-commit; CI memakai model dummy yang
  menghasilkan output tetap. Test model asli masuk job manual.

Test yang wajib ada sebelum implementasi:

```text
person_detected_when_one_person_in_frame
no_detection_for_empty_table
boxes_are_scaled_back_to_original_resolution
duplicate_boxes_are_suppressed
low_confidence_boxes_are_dropped
```

### 3.4 spatial-engine

Ini crate paling murni; seluruh logika bisnis status meja ada di sini.

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/polygon.rs` | unit + `tests/polygon_cases.rs` | titik di dalam, di luar, tepat di tepi, di vertex, poligon cekung |
| `src/geometry.rs` | `tests/normalized_coords.rs` | piksel frame ke 0..1, resolusi berubah tanpa ubah poligon, rasio aspek berubah menandai kalibrasi ulang |
| `src/loader.rs` | `tests/config_loader.rs` | JSON valid, versi tidak dikenal, poligon < 3 titik, self-intersect, id duplikat, koordinat di luar 0..1, `version: 1` ditolak dengan pesan migrasi |
| `src/centroid.rs` | `tests/centroid.rs` | titik tengah (center box), kotak terpotong tepi frame, kotak nol, konversi ke 0..1 (clamp tanpa panic). Nama modul `centroid.rs` tidak berarti foot anchor. |
| `src/debounce.rs` | `tests/debounce.rs` | butuh N frame konsisten, reset saat noise, waktu pakai clock injeksi |
| `src/state_machine.rs` | `tests/state_machine.rs` | transisi `available <-> occupied`, `unknown` saat stream mati, `since` hanya berubah saat transisi |
| `src/multi_table.rs` | `tests/multi_table.rs` | dua meja berdampingan, satu orang di area tumpang tindih, orang berpindah meja |
| `src/lib.rs` | `tests/public_api.rs` | API `SpatialEngine::update(frame_detections, now)` |

Kasus wajib (tabel keputusan):

| Skenario | Harapan |
| --- | --- |
| Titik tepat di garis poligon | Dianggap **di dalam** (konsisten, didokumentasikan) |
| Titik di vertex | Di dalam |
| Poligon cekung, titik di lekuk | Di luar |
| Confidence di bawah ambang | Tidak dihitung sebagai orang |
| Satu orang di dua meja | Hanya meja dengan center box terdekat yang `occupied` |
| Frame kosong 3x berturut-turut | Status tetap `occupied` sampai debounce habis |
| Frame kosong melebihi debounce | Transisi ke `available`, `since` diperbarui |
| Stream mati | Semua meja `unknown`, bukan `available` |

### 3.5 core-ffi

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/lib.rs` | `tests/ffi_lifecycle.rs` | start sekali, start ganda ditolak, stop sebelum start aman, start-stop-start |
| `src/api.rs` | `tests/api_contract.rs` | `TableConfig`, `TableStatus`, `EngineEvent` serialisasi stabil |
| `src/callback.rs` | `tests/event_dispatch.rs` | event hanya saat berubah, urutan terjaga, listener lambat tidak memblokir engine |
| `src/panic_guard.rs` | `tests/panic_boundary.rs` | panic di worker tidak melintasi FFI, dikembalikan sebagai error |
| `src/runtime.rs` | `tests/runtime_shutdown.rs` | Tokio runtime berhenti bersih, tidak ada thread menggantung |

Tambahan kontrak frame dan status:

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/api.rs` | `tests/frame_contract.rs` | `push_frame` menolak panjang buffer salah; frame drop dihitung; tidak ada panic untuk ukuran nol |
| `src/types.rs` | `tests/stream_status_crosswalk.rs` | semua nilai `StreamStatus` punya padanan wire dan UI; `Closed`/`Error` membuat meja `unknown` |
| `src/state.rs` | `tests/frame_ownership.rs` | buffer tidak dipakai setelah dikembalikan; event tetap berurutan saat frame datang cepat |

Catatan:

- `uniffi` binding Kotlin digenerate; **jangan** edit manual. Test memverifikasi file
  hasil generate ada dan signature-nya cocok, bukan isi implementasinya.
- Semua test FFI dijalankan dua kali: host (`cargo test`) dan instrumented Android
  (`connectedAndroidTest`) untuk memastikan `.so` termuat.

Kontrak frame yang harus diputuskan sebelum K5:

- Apakah `Vec<u8>` dipindahkan ke Rust (ownership transfer) atau disalin?
- Berapa ukuran maksimum frame dan apa yang terjadi bila melebihi?
- Apakah frame yang datang saat inferensi berjalan di-drop, diantrekan, atau diganti
  dengan frame terbaru?
- Apakah preview dan inferensi boleh memakai decoder yang sama? Jika ya, siapa pemilik
  Surface dan kapan boleh dilepas?

Tanpa jawaban ini, test hanya membuktikan bahwa byte bisa masuk, bukan bahwa pipeline
aman untuk service berjam-jam.

## 4. Peta file test Android

Acuan alur layar, wireframe, dan urutan fase Kotlin ada di
[`android-app-plan.md`](android-app-plan.md). Bagian ini hanya memetakan file test; bila
keduanya berbeda, `android-app-plan.md` yang berlaku untuk UI/UX.

Status scaffold: modul JVM `core:model` berisi source dan 29 test yang lulus;
`feature:tables` berisi domain/UI/data dan 47 test yang lulus (termasuk 10 test Compose); `core:database` berisi
Room v1 untuk revisi konfigurasi meja; `app` berisi manifest, `MainActivity`, dan theme.
Login, Home, Scan, foreground service, outbox, dan sinkronisasi masih target:

```
android/app/src/
├── main/kotlin/<pkg>/
│   ├── ui/            # Compose screen, komponen, theme
│   ├── domain/        # ViewModel, state, mapper
│   ├── service/       # foreground service, notification, permission
│   └── data/          # Room, outbox, sync worker, settings
├── test/kotlin/<pkg>/        # JVM unit test (cepat, tanpa emulator)
└── androidTest/kotlin/<pkg>/ # instrumented (emulator/perangkat)
```

### 4.1 Domain (`test/kotlin`)

| File produksi | File test | Perilaku |
| --- | --- | --- |
| `domain/MonitoringViewModel.kt` | `MonitoringViewModelTest.kt` | event → state; tidak ada update saat status sama; `unknown` saat stream down |
| `domain/TableMapper.kt` | `TableMapperTest.kt` | `TableStatus` → warna/label; id tidak dikenal diabaikan |
| `domain/PolygonEditor.kt` | `PolygonEditorTest.kt` | tambah/hapus/geser titik, tolak < 3 titik, tolak self-intersect, klamp ke 0..1, tolak luas < 0.0005 |
| `domain/MappingTransform.kt` | `MappingTransformTest.kt` | layar ke frame (zoom/pan/letterbox), frame ke layar, round-trip idempoten, titik di luar viewport di-klamp |
| `domain/ZoomState.kt` | `ZoomStateTest.kt` | batas zoom 1..8x, pan dibatasi ke tepi gambar, reset ke fit saat buka layar |
| `domain/HealthMapper.kt` | `HealthMapperTest.kt` | metrik → badge normal/warning/critical (ambang dari `monitoring.md`) |
| `domain/StreamHealthMapper.kt` | `StreamHealthMapperTest.kt` | `(StreamErrorKind, NetworkState)` → `NoNetwork`, `WrongNetwork`, `CameraOffline`, `AuthFailed`, `Connected`; pesan tanpa IP/kredensial |
| `data/OutboxRepository.kt` | `OutboxRepositoryTest.kt` | tulis sebelum kirim, hapus hanya setelah ACK, retensi 7 hari |
| `data/SyncScheduler.kt` | `SyncSchedulerTest.kt` | backoff 30s/1m/5m/15m, reset setelah sukses, gap `409` memicu kirim ulang |
| `data/HeartbeatBuilder.kt` | `HeartbeatBuilderTest.kt` | payload cocok skema, tidak memuat URL RTSP/token |
| `data/SettingsRepository.kt` | `SettingsRepositoryTest.kt` | device_id stabil, config_version naik saat poligon berubah |

Dependensi test: `kotlinx-coroutines-test` + `turbine`, `junit5`/`junit4`, `truth`/`assertk`,
Room in-memory, dan fake `Clock`. Tidak ada `Thread.sleep`.

### 4.2 UI (`test/kotlin` + `androidTest/kotlin`)

| File produksi | File test | Jenis | Perilaku |
| --- | --- | --- | --- |
| `ui/SetupScreen.kt` | `SetupScreenTest.kt` | Compose UI (instrumented) | input URL RTSP, tes koneksi 3 lapis (jaringan/TCP/RTSP), error terlihat, password tidak ditampilkan |
| `ui/MappingScreen.kt` | `MappingScreenTest.kt` | Compose UI | gambar poligon, undo, simpan, preview overlay, pinch-zoom 2x lalu titik baru mendarat di meja yang sama |
| `ui/MonitoringScreen.kt` | `MonitoringScreenTest.kt` | Compose UI | warna meja berubah sesuai event, banner stream down, indikator FPS |
| `ui/HealthScreen.kt` | `HealthScreenTest.kt` | Compose UI | menampilkan suhu/RAM/antrean, badge warning |
| `ui/theme/*` | tidak ada test khusus | - | diverifikasi lewat screenshot |
| `service/MonitoringService.kt` | `MonitoringServiceTest.kt` | instrumented | notifikasi permanen muncul, start/stop idempoten, restart setelah proses mati |
| `service/BootReceiver.kt` | `BootReceiverTest.kt` | instrumented | service hidup lagi setelah reboot bila setting aktif |
| `service/PermissionFlow.kt` | `PermissionFlowTest.kt` | instrumented | notifikasi & kamera ditolak → UI memberi panduan |
| `service/NetworkMonitor.kt` | `NetworkMonitorTest.kt` | instrumented | Wi-Fi kembali → retry segera; tidak ada retry saat transport `none` |

Aturan UI test:

- Selector memakai `testTag` semantik, bukan teks yang bisa berubah.
- Screenshot test hanya untuk layar utama (3 layar), bukan semua komponen.
- Jangan menguji warna hex langsung; uji **state** (`TableUiState.Occupied`) dan biarkan
  theme test terpisah.
- UI test tidak boleh butuh kamera/RTSP nyata; engine diganti fake yang mengirim event.

## 5. Peta file test server

| File produksi | File test | Perilaku |
| --- | --- | --- |
| `src/routes/sync.rs` | `tests/sync_api.rs` | batch valid, ACK `acked_seq`, duplikat diabaikan, gap → `409` |
| `src/routes/heartbeat.rs` | `tests/heartbeat_api.rs` | validasi skema, tolak field terlarang (URL RTSP/gambar), simpan metrik |
| `src/routes/snapshot.rs` | `tests/snapshot_api.rs` | rekonsiliasi status, tidak menghapus riwayat |
| `src/db/events.rs` | `tests/event_repo.rs` | unique `(device_id, seq)`, transaksi atomik, partisi bulanan |
| `src/db/sessions.rs` | `tests/session_duration.rs` | durasi dari transisi status, clock skew ditandai, turnover |
| `src/auth.rs` | `tests/auth.rs` | token per perangkat, token dicabut → 401, tidak ada token di log |
| `src/health.rs` | `tests/health.rs` | `GET /health` mengembalikan versi + status DB |
| `src/ws.rs` | `tests/ws_broadcast.rs` | event baru sampai ke klien, klien lambat diputus |

Strategi: PostgreSQL nyata via `testcontainers` atau database sementara; **jangan** pakai
SQLite untuk test karena constraint/partisi berbeda. Setiap test memakai transaksi yang
di-rollback atau schema terpisah.

## 6. Kontrak dan fixture

Status: **ada dan hijau** (2026-11-26). Lima schema draft 2020-12 dan crate validasi
`contracts/` sudah dibuat; `cd contracts; cargo test` menjalankan 26 test (5 unit + 21
integrasi). Crate ini **standalone** (punya `[workspace]` sendiri) supaya bisa dipakai
server dan Kotlin tanpa menarik dependensi Android. Perintah gate-nya terpisah dari
`cargo test --workspace` di `rust-core/`.

Validasi memakai `jsonschema` 0.63 dengan registry offline: semua `$ref` diselesaikan
dari lima dokumen di `schemas/`, tanpa akses file atau jaringan. `format: date-time`
diaktifkan agar timestamp non-UTC ditolak.

```
contracts/
├── schemas/
│   ├── table-config.schema.json
│   ├── table-status.schema.json
│   ├── sync-request.schema.json
│   ├── sync-ack.schema.json
│   └── heartbeat.schema.json
├── fixtures/
│   ├── valid/
│   └── invalid/
└── tests/
    └── schema_test.rs      # validasi semua fixture terhadap schema
```

Aturan:

- Setiap perubahan skema wajib menambah fixture `valid/` dan `invalid/` pada commit yang
  sama.
- Rust dan Kotlin memakai schema yang sama; tidak ada salinan manual. Bukti Kotlin:
  `android/core/model/src/test/kotlin/id/warung/monitor/core/model/ContractSchemaTest.kt`
  memvalidasi semua fixture `valid/` dan `invalid/` dari `contracts/` dan menolak
  ketidakcocokan direktori/`$id`. Test ini JVM-only, tidak ikut APK.
- `invalid/` minimal mencakup: poligon 2 titik, `status` tak dikenal, `seq` negatif,
  heartbeat berisi field gambar, versi skema lebih tinggi dari yang didukung.

## 7. Urutan eksekusi per fase

Setiap baris adalah satu putaran TDD lengkap: tulis test → jalankan sampai merah →
implementasi sampai hijau → refactor.

| Fase | Test yang ditulis lebih dulu | Gate |
| --- | --- | --- |
| 0 | `tests/workspace_build.rs` (smoke) | `cargo test --workspace` jalan; schema/CI belum menjadi gate |
| 1 | `ring_buffer_integration`, `frame_sampler`, `reconnect_policy`, `rtsp_client_mock`, `error_classification` | Ring buffer & reconnect hijau; kamera tak terjangkau menjadi `unknown` |
| 2 | `preprocess_golden`, `nms`, `postprocess` | Golden frame hijau dengan model dummy |
| 3 | `polygon_cases`, `debounce`, `state_machine`, `normalized_coords`, `config_loader` | Semua kasus tabel keputusan hijau; poligon 0..1 konsisten |
| 4 | `ffi_lifecycle`, `event_dispatch`, `panic_boundary`, `frame_contract`, `stream_status_crosswalk` | Start/stop aman; kontrak frame dan status teruji di host + perangkat |
| 5 | `DeviceCapabilityProviderTest`, `OnboardingViewModelTest`, `MonitoringViewModelTest`, `MappingScreenTest`, `OutboxRepositoryTest`, `StreamHealthMapperTest`, `NetworkMonitorTest`, `MappingTransformTest`, `ZoomStateTest`, `PolygonEditorTest` | Diagnostik perangkat, UI mapping termasuk zoom/pan, dan event fake engine; pesan beda jaringan benar |
| 6 | `sync_api`, `event_repo`, `session_duration` | Offline 10 menit tersinkron tanpa duplikat |
| 7 | `heartbeat_api`, `HeartbeatBuilderTest`, `HealthMapperTest` | Heartbeat valid & alert terpicu |
| 8 | soak test + screenshot test layar penuh | 7 hari stabil |

Catatan: nomor fase di tabel ini adalah urutan internal TDD, bukan nomor Fase
roadmap. Fase 5 di sini mencakup K3-K7 Kotlin, sedangkan Fase 8 adalah uji lapangan.

## 8. Perintah verifikasi

| Lingkup | Perintah |
| --- | --- |
| Rust (cepat) | `cargo test --workspace` |
| Rust (lint) | `cargo clippy --workspace --all-targets -- -D warnings` |
| Rust (format) | `cargo fmt --all -- --check` |
| Rust (Android target) | `cargo build --target aarch64-linux-android` |
| Kotlin JVM (domain) | `./gradlew :core:model:test` |
| Kotlin JVM (fitur) | `./gradlew :feature:tables:testDebugUnitTest` |
| Kotlin instrumented | `./gradlew :app:connectedDebugAndroidTest` |
| Server | `cargo test -p server` (butuh DB test) |
| Kontrak | `cd contracts; cargo test` (crate standalone, 26 test) |
| Semua | `powershell -ExecutionPolicy Bypass -File tools/ci/verify.ps1` (opsional `-SkipNdk`, `-SkipAndroid`, `-SkipAudit`) |

`tools/ci/verify.ps1` menjalankan fmt, clippy, test workspace, gate RTSP, gate
`core-ffi --features cli`, `cargo audit`/`cargo deny` (kontrak + workspace), tiga target
NDK, lalu seluruh task unit test, `lintDebug`, dan `assembleDebug` Android. Script
berhenti dengan exit 1 dan daftar tahap gagal bila ada yang merah. Script ini untuk
manusia/CI; sesi agen tetap menjalankan Gradle lewat wrapper `gradle-run` (AGENTS.md 2)
dan memakai `-SkipAndroid`. Job terpisah (nightly): instrumented test, RTSP live, soak.
Workflow GitHub Actions belum ada.

Bukti 2026-11-26 (satu sesi, lewat wrapper `gradle-run`): `:core:model:test` 44 test,
`:core:database:testDebugUnitTest` 11, `:feature:cameras:testDebugUnitTest` 23,
`:feature:tables:testDebugUnitTest` 53, `:feature:auth:testDebugUnitTest` 17,
`:feature:home:testDebugUnitTest` 20, dan `:app:testDebugUnitTest` 16 - total 184 test,
0 gagal. `:app:assembleDebug` menghasilkan APK debug (184.6 MB, termasuk
`libonnxruntime.so` untuk empat ABI) dan `:app:lintDebug` 0 error (warning tersisa hanya
versi dependency yang disengaja). APK belum dijalankan di perangkat, dan instrumented
test belum dijalankan. Crate `contracts` juga hijau: 26 test, `cargo audit` dan
`cargo deny check` bersih.

## 9. Definisi selesai untuk sebuah commit

- Test baru gagal sebelum implementasi (bukti red ada di log atau commit sebelumnya).
- Semua test lama tetap hijau.
- Tidak ada `#[ignore]` baru tanpa catatan alasan.
- Tidak ada `unwrap()`/`expect()` di jalur produksi yang bisa menerima input pengguna.
- Fixture/skema diperbarui bila payload berubah.
- Tidak ada perubahan pada test yang sudah hijau tanpa alasan (hindari test yang
  “dilonggarkan” agar lulus).

## 10. Contoh test yang menentukan desain

### 10.1 Debounce (Rust)

```rust
#[test]
fn stays_occupied_until_debounce_expires() {
    let mut engine = SpatialEngine::new(config(), fixed_clock());
    engine.update(&[person_in("meja-01")], t0());
    assert_eq!(engine.status("meja-01"), TableStatus::Occupied);

    for offset in [200, 400, 600] {
        engine.update(&[], t0() + ms(offset));
        assert_eq!(engine.status("meja-01"), TableStatus::Occupied);
    }

    engine.update(&[], t0() + ms(1000));
    assert_eq!(engine.status("meja-01"), TableStatus::Available);
}
```

Test ini memaksa desain: waktu harus diinjeksi, bukan `Instant::now()` di dalam logika.

### 10.2 Gap `seq` (server)

```rust
#[tokio::test]
async fn rejects_gap_in_sequence() {
    let app = test_app().await;
    app.post_sync(device("a"), seq(1)).await.assert_ok();
    let res = app.post_sync(device("a"), seq(3)).await;
    res.assert_status(409);
    res.assert_json_path("expected_seq", 2);
}
```

Test ini memaksa desain: server menyimpan `last_seq` dan mengembalikan `expected_seq`
yang bisa dipakai klien untuk retry.

### 10.3 UI tidak menghitung status (Kotlin)

```kotlin
@Test
fun table_color_follows_engine_event() {
    val vm = MonitoringViewModel(FakeEngine(status = TableStatus.Available))
    vm.state.test {
        assertEquals(TableUiState.Available, awaitItem().tables["meja-01"])
        vm.onEvent(EngineEvent.TableChanged("meja-01", TableStatus.Occupied))
        assertEquals(TableUiState.Occupied, awaitItem().tables["meja-01"])
    }
}
```

Test ini memaksa desain: ViewModel hanya meneruskan event; tidak ada deteksi ulang di
Kotlin.

### 10.4 Kamera tak terjangkau bukan "meja kosong" (Rust)

```rust
#[test]
fn unreachable_camera_marks_tables_unknown() {
    let mut engine = SpatialEngine::new(config(), fixed_clock());
    engine.update(&[person_in("meja-01")], t0());
    assert_eq!(engine.status("meja-01"), TableStatus::Occupied);

    engine.on_source_down(SourceError::Unreachable, t0() + ms(500));
    assert_eq!(engine.status("meja-01"), TableStatus::Unknown);

    engine.on_source_down(SourceError::Unreachable, t0() + ms(30_000));
    assert_eq!(engine.status("meja-01"), TableStatus::Unknown);
}
```

Test ini memaksa desain: kegagalan koneksi masuk ke state machine sebagai kejadian,
bukan dibiarkan menunggu frame yang tidak pernah datang. Status `unknown` juga yang
dikirim ke outbox, sehingga dashboard tidak menampilkan meja kosong palsu.

### 10.5 Zoom tidak menggeser poligon (Kotlin)

```kotlin
@Test
fun zoom_does_not_move_polygon() {
    val center = NormalizedPoint(0.5f, 0.5f)
    // viewport 1080x608, frame 1280x720 -> base 0.84375, content (0, 0.25).
    // Pan di bawah menahan titik di tengah viewport, jadi tidak tertutup clamp.
    val zooms = listOf(
        1f to Offset(0f, 0f),
        3f to Offset(-1080f, -607.5f),
        8f to Offset(-3780f, -2126.25f),
    )

    zooms.forEach { (scale, pan) ->
        val t = MappingTransform(1280f, 720f, 1080f, 608f, scale, pan.x, pan.y)
        val screen = t.toScreen(center)
        val back = t.toNormalized(screen.x, screen.y)

        // 1e-4 pada lebar 1080 px kira-kira 0.1 px, di bawah batas 0.5 px.
        assertEquals(center.x, back.x, 1e-4f)
        assertEquals(center.y, back.y, 1e-4f)
        assertTrue(back.x in 0f..1f && back.y in 0f..1f)
    }
}

@Test
fun touch_in_letterbox_is_clamped() {
    val t = MappingTransform(1280f, 720f, 1080f, 608f, scale = 2f, panX = 0f, panY = 0f)
    val outside = t.toNormalized(-50f, 2_000f)

    assertEquals(0f, outside.x, 0f)
    assertEquals(1f, outside.y, 0f)
}
```

Test ini memaksa desain: transform harus murni dan deterministik, tidak membaca ukuran
layar dari global, dan hasilnya sudah di-clamp ke 0..1. Zoom hanya mengubah tampilan;
poligon tidak pernah ikut digeser atau diskalakan saat gesture berlangsung. Pan di tiap
baris dipilih supaya titik tetap di tengah viewport, jadi nilainya legal dan tidak
tertutup oleh clamp. Test yang sama dijalankan untuk beberapa level zoom supaya bug
"benar di 1x, bergeser di 3x" langsung merah.

### 10.6 Poligon 0..1 tetap valid saat resolusi berubah (Rust)

```rust
const CONFIG: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

#[test]
fn polygon_survives_resolution_change_when_aspect_matches() {
    let config = TableConfig::from_json(CONFIG).unwrap();
    let engine_540p = SpatialEngine::with_frame(config.clone(), FrameSize::new(960, 540));
    let engine_720p = SpatialEngine::with_frame(config, FrameSize::new(1280, 720));

    let at_center = Point::new(0.5, 0.5);
    assert_eq!(engine_540p.table_at(at_center), Some("meja-mock"));
    assert_eq!(engine_720p.table_at(at_center), Some("meja-mock"));
    assert!(!engine_720p.needs_recalibration());
}

#[test]
fn aspect_change_requires_recalibration() {
    let config = TableConfig::from_json(CONFIG).unwrap();
    let engine = SpatialEngine::with_frame(config, FrameSize::new(1024, 768));

    assert!(engine.needs_recalibration());
    // Poligon tidak diubah diam-diam; hanya ditandai.
    assert_eq!(engine.table_count(), 1);
}
```

Test ini memaksa desain: ruang koordinat tidak bergantung pada resolusi, sementara rasio
aspek diperiksa eksplisit, bukan diasumsikan. Kalau rasio berubah (misalnya kamera diganti
atau di-crop), sistem menandai kalibrasi ulang alih-alih menebak.

Catatan: `960x540` dipakai, bukan `854x480`, karena 854x480 adalah 16:9 **anamorphic**
(piksel tidak persegi). Stream seperti itu butuh SAR/DAR dari container; selama belum
ditangani, engine memperlakukannya sebagai rasio berbeda dan meminta kalibrasi ulang —
lebih aman daripada memakai poligon yang posisinya meleset beberapa persen.
