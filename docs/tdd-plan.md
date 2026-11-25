# Rencana TDD

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
7. **UI tidak menguji matematika.** Poligon, debounce, dan status meja diuji di Rust.
   UI hanya menguji pemetaan event ke tampilan dan alur pengguna.
8. **Tidak mengejar coverage angka.** Target awal: logika inti (spatial, ring buffer,
   NMS, outbox, idempotensi) >= 90%; UI >= 60%.

## 2. Batas UI dan core

| Tanggung jawab | Lokasi | Diuji dengan |
| --- | --- | --- |
| Buka RTSP/file, decode, ring buffer, frame sampling | `rust-core/stream-engine` | Unit + integration Rust |
| Klasifikasi error koneksi (timeout/refused/auth) | `rust-core/stream-engine` | Unit Rust + mock TCP |
| Inferensi YOLOv11n, pra/pasca-proses, NMS | `rust-core/ai-engine` | Unit + golden frame |
| Poligon, centroid, point-in-polygon, debounce, state machine | `rust-core/spatial-engine` | Unit Rust murni |
| Tipe FFI, lifecycle start/stop, event callback, panic boundary | `rust-core/core-ffi` | Integration Rust + instrumented Android |
| Gambar poligon, preview, warna status, form, navigasi | `android/.../ui` | Compose UI test + screenshot |
| ViewModel, pemetaan event ke state, validasi input | `android/.../ui` + `.../domain` | JVM unit test + fake |
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
- Kotlin **tidak** melakukan decode/ONNX. Preview video memakai surface milik engine
  atau Media3, tetapi frame yang dianalisis tetap milik Rust.
- Validasi poligon di UI hanya untuk UX (misal minimal 3 titik); validasi otoritatif
  tetap di `spatial-engine`.

## 3. Peta file test Rust

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

File implementasi dan pasangan test-nya:

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `src/ring_buffer.rs` | unit di file + `tests/ring_buffer_integration.rs` | push/pop FIFO, overwrite saat penuh, tidak ada alokasi ulang, panic saat kapasitas 0 |
| `src/frame_sampler.rs` | `tests/frame_sampler.rs` | sampling 2-5 FPS, drop frame lama, timestamp monoton |
| `src/reconnect.rs` | `tests/reconnect_policy.rs` | backoff 1-2-4-8-16 detik, reset setelah sukses, batas maksimum, jitter deterministik |
| `src/file_source.rs` | `tests/file_source.rs` | baca video uji, EOF, file rusak, FPS terjadwal |
| `src/rtsp_client.rs` | `tests/rtsp_client_mock.rs`, `tests/unreachable_camera.rs`, `tests/auth_failure_no_retry_storm.rs` | connect, timeout, putus di tengah, kamera tak terjangkau, credential redaction |
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
tests/unreachable_camera.rs           # IP TEST-NET-1 + timeout
tests/error_classification.rs         # io::Error -> StreamErrorKind
tests/auth_failure_no_retry_storm.rs  # 401 tidak memicu loop cepat
```

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
| `src/loader.rs` | `tests/config_loader.rs` | JSON valid, versi tidak dikenal, poligon < 3 titik, self-intersect, id duplikat |
| `src/centroid.rs` | `tests/centroid.rs` | centroid kotak, kotak terpotong tepi frame, kotak nol |
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
| Satu orang di dua meja | Hanya meja dengan centroid terdekat yang `occupied` |
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

Catatan:

- `uniffi` binding Kotlin digenerate; **jangan** edit manual. Test memverifikasi file
  hasil generate ada dan signature-nya cocok, bukan isi implementasinya.
- Semua test FFI dijalankan dua kali: host (`cargo test`) dan instrumented Android
  (`connectedAndroidTest`) untuk memastikan `.so` termuat.

## 4. Peta file test Android

Struktur yang dituju:

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
| `domain/PolygonEditor.kt` | `PolygonEditorTest.kt` | tambah/hapus/geser titik, tolak < 3 titik, tolak self-intersect sebelum dikirim |
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
| `ui/MappingScreen.kt` | `MappingScreenTest.kt` | Compose UI | gambar poligon, undo, simpan, preview overlay |
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
- Rust dan Kotlin memakai schema yang sama; tidak ada salinan manual.
- `invalid/` minimal mencakup: poligon 2 titik, `status` tak dikenal, `seq` negatif,
  heartbeat berisi field gambar, versi skema lebih tinggi dari yang didukung.

## 7. Urutan eksekusi per fase

Setiap baris adalah satu putaran TDD lengkap: tulis test → jalankan sampai merah →
implementasi sampai hijau → refactor.

| Fase | Test yang ditulis lebih dulu | Gate |
| --- | --- | --- |
| 0 | `tests/workspace_build.rs` (smoke), schema test kosong gagal | `cargo test --workspace` jalan |
| 1 | `ring_buffer_integration`, `frame_sampler`, `reconnect_policy`, `unreachable_camera`, `error_classification` | Ring buffer & reconnect hijau; kamera tak terjangkau menjadi `unknown` |
| 2 | `preprocess_golden`, `nms`, `postprocess` | Golden frame hijau dengan model dummy |
| 3 | `polygon_cases`, `debounce`, `state_machine` | Semua kasus tabel keputusan hijau |
| 4 | `ffi_lifecycle`, `event_dispatch`, `panic_boundary` | Start/stop aman di host + perangkat |
| 5 | `MonitoringViewModelTest`, `MappingScreenTest`, `OutboxRepositoryTest`, `StreamHealthMapperTest`, `NetworkMonitorTest` | UI mapping + event jalan dengan fake engine; pesan beda jaringan benar |
| 6 | `sync_api`, `event_repo`, `session_duration` | Offline 10 menit tersinkron tanpa duplikat |
| 7 | `heartbeat_api`, `HeartbeatBuilderTest`, `HealthMapperTest` | Heartbeat valid & alert terpicu |
| 8 | soak test + screenshot test layar penuh | 7 hari stabil |

## 8. Perintah verifikasi

| Lingkup | Perintah |
| --- | --- |
| Rust (cepat) | `cargo test --workspace` |
| Rust (lint) | `cargo clippy --workspace --all-targets -- -D warnings` |
| Rust (format) | `cargo fmt --all -- --check` |
| Rust (Android target) | `cargo build --target aarch64-linux-android` |
| Kotlin JVM | `./gradlew :app:testDebugUnitTest` |
| Kotlin instrumented | `./gradlew :app:connectedDebugAndroidTest` |
| Server | `cargo test -p server` (butuh DB test) |
| Kontrak | `cargo test -p contracts` |
| Semua | `./tools/ci/verify.ps1` (dibuat di Fase 0) |

CI wajib menjalankan: fmt, clippy, `cargo test --workspace`, `testDebugUnitTest`,
schema test. Job terpisah (nightly): instrumented test, RTSP live, soak.

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
