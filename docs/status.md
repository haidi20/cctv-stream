# Status implementasi dan bukti

Dokumen ini memisahkan rencana dari bukti. Gunakan istilah berikut secara
konsisten:

| Istilah | Arti |
| --- | --- |
| Direncanakan | Ada di dokumen, belum ada artefak |
| Ada | File atau kode ada |
| Unit-tested | Ada test otomatis yang lulus di host |
| Android build | Cross-compile atau Gradle build berhasil |
| Perangkat teruji | Berjalan di perangkat fisik target |
| Lapangan teruji | Lulus acceptance di lokasi nyata |

## 1. Snapshot 2026-11-26

| Area | Status | Bukti | Yang belum |
| --- | --- | --- | --- |
| Workspace Rust | Ada; gate hijau 2026-11-26 | `rust-core/Cargo.toml`, 214 test workspace | Verifikasi perangkat dan akurasi model |
| `stream-engine` | Ada; test host terakhir tercatat lulus | RTSP client, file-source pacing, ring buffer, sampler | Decoder konkret, loop produksi, dan integrasi ke preview |
| `ai-engine` | Ada; test host terakhir tercatat lulus | `ort`, preprocess, postprocess, NMS | Akurasi model di kamera nyata |
| `spatial-engine` | Ada; test host terakhir tercatat lulus | polygon, geometry, debounce, multi-table | Keputusan anchor dan acceptance lapangan |
| `core-ffi` | Ada; test host + gate hijau | binding UniFFI, listener, panic guard, `camera_id` pada config/health; `cargo test --workspace` 214 test (termasuk empat test regresi generasi sesi dan lima test kontrak frame) dan `cargo ndk` tiga ABI lulus 2026-11-26 | Kontrak frame dan Surface |
| Android scaffold | Ada | Gradle root dan modul `app`, `core:model`, `core:database`, `core:network`, `core:sync`, `core:ui`, `feature:cameras`, `feature:tables`, `feature:auth`, `feature:home`, `feature:onboarding`, `feature:settings`; single-activity Compose (diagnostik perangkat -> login PIN -> Home -> Scan -> stream + total meja), Room v3 dengan outbox, binding, `jniLibs`, `EngineStartPlanner`, `EngineFramePump`, `TableEventRecorder`, dan `RustEngineEventSource` (`Engine.subscribe` -> sinyal coalesced); provider telemetri ONNX Runtime dihapus lewat manifest merge; foreground service mediaPlayback + notifikasi pemantauan ada; `SyncWorker` Hilt + `SyncScheduler` + `KtorSyncTransport` mengirim outbox ke `/v1/sync`, dan layar `feature:settings` menyimpan base URL/token | Verifikasi perangkat, server nyata, dan kamera |
| `core:ui` Kotlin | Unit-tested di host | `MonitorTheme` (Material 3, dynamic colour 12+) dan `TimeFormat` (`clock` per zona, `age` "12 detik lalu"/"1 menit lalu"/fallback jam); `:core:ui:test` lulus 6 test pada 2026-11-26 | Uji visual di perangkat |
| `core:model` Kotlin | Unit-tested di host | `android/core/model`; `:core:model:test` lulus 63 test pada 2026-11-26 (termasuk `CameraProfileTest`, `ContractSchemaTest`, dan `HeartbeatJsonTest`); encoder heartbeat divalidasi ke schema asli | Integrasi lapangan |
| `feature:tables` | Unit-tested di host | Source domain/UI/data ada; `:feature:tables:testDebugUnitTest` lulus 53 test pada 2026-11-26 (termasuk test Compose `TableMappingScreenTest` dan `TableMappingSnapshotTest`) dan terikat per `camera_id` | Verifikasi snapshot `TextureView` di perangkat |
| `core:network` Kotlin | Unit-tested di host | `android/core/network` (JVM murni): `SubnetScan`, `CameraPortScanner` (port 554/8554, concurrency 32, timeout 400 ms), parser `WsDiscovery`, `DiscoveryMerge`, dan `CameraDiscovery` (WS + probe paralel, cache 10 menit, tolak data seluler); `:core:network:test` lulus 43 test | Probe dan kamera di jaringan nyata |
| `feature:cameras` | Unit-tested di host | Repository, `CameraSession`, ViewModel, dan Compose list/editor; `:feature:cameras:testDebugUnitTest` lulus 37 test pada 2026-11-26 (termasuk `CameraScanViewModel` dan tes koneksi empat lapis di editor) | Verifikasi perangkat dan preview RTSP |
| `feature:auth` | Unit-tested di host | PIN PBKDF2 + lockout + reset lokal, haptic per digit, dan seam biometrik (`BiometricUnlocker`, `AndroidBiometricUnlock`); `:feature:auth:testDebugUnitTest` lulus 36 test pada 2026-11-26 | Prompt biometrik di perangkat fisik dan verifikasi perangkat |
| `feature:onboarding` | Unit-tested di host | Empat baris diagnostik (platform, memori, mesin AI, penyimpanan) dan profil `full`/`balanced`/`lite`/`blocked`; warm-up engine nyata lewat `Engine.start` + `pushFrame` 64x64 bila model terpasang; `blocked` tidak pernah membuka Home; hasil disimpan per `versionCode` di DataStore; `:feature:onboarding:testDebugUnitTest` lulus 41 test pada 2026-11-26; profil yang tersimpan dipakai `AndroidHeartbeatFacts` untuk `device.profile` | Warm-up nyata di perangkat, model ONNX yang dibundel, dan permission notifikasi runtime |
| `feature:home` | Unit-tested di host | Home adaptif compact/wide, state stream, total kosong/terisi/unknown, scan sheet, catatan mode hemat `lite`, banner recalibration saat rasio video berubah dengan aksi "Buka peta meja", penyebab kegagalan spesifik dari Rust (`auth_failed` tidak tampil sebagai masalah Wi-Fi), backoff reconnect 1-2-4-8-16 dtk, dan menu overflow "Sinkronisasi server"; `:feature:home:testDebugUnitTest` lulus 66 test pada 2026-11-26 (termasuk 8 test `ScanSheetBody`, 3 test `power-saver-note`, `NetworkPolicyTest`, `ReconnectPolicyTest`/`HomeReconnectTest`, pelaporan kegagalan preview ke Rust, dua test `EngineChangeSignal`, tiga test recalibration, satu test penyebab kegagalan, dan satu test navigasi ke pengaturan) | Model ONNX nyata, akurasi hitungan, discovery nyata |
| `feature:settings` | Unit-tested di host | Layar "Sinkronisasi server" tanpa bottom navigation: validasi `SyncUrl` (HTTPS, tanpa kredensial/query/fragment, maks 200 karakter), `SyncSettingsViewModel` (token tidak pernah masuk state, simpan hanya sukses penuh), dan Compose screen dengan testTag stabil; `:feature:settings:testDebugUnitTest` lulus 28 test pada 2026-11-26 | Verifikasi perangkat, penyimpanan token di Keystore |
| `core:sync` Kotlin | Unit-tested di host | `SyncCoordinator` (batch 100, maks 20 batch, ACK prefix, replay `409` sekali, halving `413`), `SyncBackoff` (30 dtk/1 mnt/5 mnt/15 mnt), `SyncEndpoint` HTTPS-only, dan interface `SyncSettings`/`SyncTransport`; `:core:sync:test` lulus 14 test | Server nyata dan token di Keystore |
| Room v3 | Ada + unit-tested | `camera_profiles`, `outbox`, DAO, `MIGRATION_1_2` (seed `camera-default`), `MIGRATION_2_3` (tabel `outbox` + index `(is_synced, seq)`), schema v3; `:core:database:testDebugUnitTest` lulus 32 test (termasuk `OutboxDaoTest` dan migrasi 2->3 yang mempertahankan profil dan peta meja) | Migrasi pada database perangkat nyata |
| Outbox + recorder + sync + heartbeat | Ada + unit-tested | `OutboxRepository`, `DeviceIdStore` DataStore, `TableEventRecorder`, serta pipeline sync: `KtorSyncTransport`, `SyncRunner`, `SyncWorker` (`@HiltWorker`), `SyncScheduler`. Heartbeat: `HeartbeatBuilder` (EngineStatus -> wire enum, `unknown` tetap terpisah), `HeartbeatRunner` (30 dtk live / 60 dtk idle, stop saat token ditolak), `HeartbeatScheduler` (rantai `OneTimeWorkRequest`, clamp 10 dtk), `HeartbeatWorker`, `KtorHeartbeatTransport`, `AndroidHeartbeatFacts`, dan `HeartbeatSession`; chain dimulai/dihentikan oleh `MonitoringHub`; `:app:testDebugUnitTest` lulus 130 test pada 2026-11-26 | `/v1/snapshot`, server nyata, dan token di Keystore |
| Android build | Lulus di host | `testDebugUnitTest :app:lintDebug :app:assembleDebug` lulus lewat wrapper `gradle-run` pada 2026-11-26; APK `android/app/build/outputs/apk/debug/app-debug.apk` (143 MB; `abiFilters` membatasi tiga ABI resmi `arm64-v8a`/`armeabi-v7a`/`x86_64`), lint 0 error; 555 test hijau dari 83 `TEST-*.xml` (`app` 130, `core:database` 32, `core:model` 63, `core:network` 43, `core:sync` 14, `core:ui` 12, `feature:auth` 36, `feature:cameras` 37, `feature:home` 66, `feature:onboarding` 41, `feature:settings` 28, `feature:tables` 53) | Jalankan di perangkat; warning tersisa hanya versi dependensi yang sengaja dipin |
| Server | Ada + teruji host | `server/` (Axum + SQLite): `/health`, `/v1/sync`, `/v1/heartbeat`, `/v1/snapshot`, `/v1/ws`; 17 test integrasi + 7 unit hijau 2026-11-26 | Dashboard web, deploy VPS, PostgreSQL produksi |
| Kontrak JSON | Ada + unit-tested | Enam schema draft 2020-12 di `contracts/schemas/`, crate `contracts` standalone dengan validasi registry offline; `cd contracts; cargo test` 28 test hijau 2026-11-26; Kotlin memvalidasi fixture yang sama lewat `ContractSchemaTest` | Pemakaian schema di server |
| CI | Direncanakan | command lokal di `AGENTS.md` | Workflow CI |
| Kamera nyata | Belum diverifikasi | belum ada unit | Model, path, codec, sesi |
| Perangkat fisik | Belum diverifikasi | `adb devices` kosong saat audit | Instrumented test dan soak |
| Decoder video | Belum ada | hanya trait `FrameDecoder` dan fake decoder | H.264 decode, preview Surface, dan sambungan ke AI |

Snapshot ini tidak boleh dinaikkan tanpa bukti baru. Perbarui tanggal dan bukti
setiap kali fase selesai.

Kolom "test host terakhir tercatat lulus" merujuk laporan test sebelumnya, bukan
hasil test ulang pada audit dokumentasi 2026-11-25. Jalankan gate di `AGENTS.md`
sebelum mengubah status menjadi bukti baru.

Dokumen terkait: kontrak server di [`server-api.md`](server-api.md), rilis dan
distribusi di [`release.md`](release.md), kriteria lapangan di
[`acceptance.md`](acceptance.md), serta panduan kamera di
[`camera-setup.md`](camera-setup.md).

## 2. Kontradiksi yang harus diselesaikan

Dokumen arsitektur menargetkan Rust membuka RTSP dan melakukan decode. Implementasi FFI saat ini menerima frame RGB lewat push_frame, dan wiring sementara sudah ada: RustCameraSession memanggil Engine.start, EngineFramePump mengirim frame 2 FPS dari preview TextureView, dan total meja tetap - sampai model ONNX nyata siap. Jalur ini adalah opsi C (hybrid sementara) dan belum menggantikan decoder Rust yang direncanakan ADR 0009; jangan menyebutnya satu koneksi.

Tiga opsi yang mungkin:

| Opsi | Preview | Deteksi | Konsekuensi |
| --- | --- | --- | --- |
| A. Media3 penuh | Media3 | Frame RGB dari Kotlin | Dua koneksi atau satu koneksi dengan frame copy |
| B. Rust penuh | Surface dari Rust | Rust | Perlu decoder dan jembatan `ANativeWindow` |
| C. Hybrid sementara | Media3 | Rust | Hanya untuk internal, bukan rilis |

Keputusan dicatat sebagai ADR 0009, tetapi statusnya masih **Diusulkan**. Sampai ADR
itu diterima dan diimplementasikan, jangan menulis "single connection" sebagai fakta.

## 3. Artefak yang belum ada

| Artefak | Status | Dampak |
| --- | --- | --- |
| `contracts/schemas/*.schema.json` | Ada | Enam schema draft 2020-12 dengan `$id` absolut dan `additionalProperties: false` |
| `contracts/fixtures/invalid/` | Ada | 15 fixture negatif; setiap schema punya minimal satu |
| `contracts/tests/schema_test.rs` | Ada + lulus | 23 test integrasi, termasuk lima kasus wajib `tdd-plan.md` §6 |
| Pemakaian schema di Kotlin | Ada + unit-tested | `ContractSchemaTest` (`:core:model:test`) memvalidasi 6 fixture `valid/` dan 15 `invalid/` langsung dari `contracts/schemas` via `com.networknt:json-schema-validator` 1.5.6, plus output `TableConfigJson.encode`. Tidak ada salinan schema di Kotlin | Validasi server |
| Pemakaian schema di server | Ada + unit-tested | Handler `/v1/sync`/`/v1/snapshot` memvalidasi payload lewat crate `contracts`; payload dengan field `image` ditolak | Validasi di jaringan nyata |
| `tools/ci/verify.ps1` | Ada + diverifikasi | Gate satu perintah untuk contracts, rust-core (workspace/rtsp/core-ffi cli), tiga ABI NDK, dan seluruh task test/lint/assemble Android. `-SkipNdk`/`-SkipAndroid`/`-SkipAudit` untuk mesin tanpa toolchain. Instrumented test dan kamera nyata tetap di luar script |
| `.github/workflows/` | Belum | CI remote belum aktif; `verify.ps1` sudah bisa dipanggil runner Windows |
| `server/` | Ada | Axum + SQLite (`sqlx` bundled), auth token `ctv_`, rate limit, WebSocket replay; dashboard belum ada. Lihat `docs/server-implementation.md` dan ADR 0010 |
| APK Android | Ada (host build) | `:app:assembleDebug` lulus 2026-11-26; `app-debug.apk` 143 MB (tiga ABI resmi). Belum dipasang atau dijalankan di perangkat |
| Implementasi Room | Sebagian | `MonitorDatabase` v3, entity, DAO, migrasi 1->2 (seed `camera-default`) dan 2->3 (`outbox`), serta schema v1/v2/v3 ada; `table_logs` dan `device_settings` belum ada |
| UI dan foreground service | Sebagian | Manifest, `MainActivity`, theme, login PIN (PBKDF2 + lockout), Home adaptif (compact/wide), scan sheet, preview Media3/demo, total meja, mapping poligon, layar pengaturan sinkronisasi, serta foreground service mediaPlayback (notifikasi pemantauan + aksi Hentikan) ada; token masih di DataStore, belum Keystore |

## 4. Bukti yang sudah ada

- Binding Kotlin UniFFI di `android/app/build/generated/uniffi/kotlin`.
- `libcore_ffi.so` untuk `arm64-v8a`, `armeabi-v7a`, dan `x86_64` di
  `android/app/src/main/jniLibs`.
- Model domain Kotlin di `android/core/model`; 44 test JVM lulus pada 2026-11-26
  (`:core:model:test`), termasuk `ContractSchemaTest` terhadap schema bersama.
- `core:network`; 43 test JVM lulus pada 2026-11-26 (`:core:network:test`): subnet /24,
  probe port, parser WS-Discovery, ranking/dedupe, cache, penolakan di luar Wi-Fi, serta
  `ConnectionTester` empat lapis + `SocketRtspSessionProbe` (Basic/Digest, SDP, paket
  interleaved) yang diuji ke server RTSP loopback.
- `feature:cameras`; 37 test JVM/Compose lulus pada 2026-11-26
  (`:feature:cameras:testDebugUnitTest`), termasuk 8 test `CameraScanViewModel` dan tes tombol "Tes koneksi".
- Modul `feature:tables`; 53 test JVM/Compose lulus pada 2026-11-26
  (`:feature:tables:testDebugUnitTest`), termasuk test isolasi peta per `camera_id`.
- `feature:auth`; 36 test JVM/Compose lulus pada 2026-11-26 (`:feature:auth:testDebugUnitTest`): 17 test domain
  (lockout, hasher, ViewModel), 10 `LoginScreenTest` (keypad, lockout, biometrik opsional, reset `HAPUS`),
  6 `BiometricUnlockerTest` (satu prompt sekaligus, fallback PIN), dan 3 `PinKeypadHapticTest`
  (`HapticFeedbackType.TextHandleMove` per digit; keypad nonaktif tidak memberi haptic).
- `feature:home`; 66 test JVM/Compose lulus pada 2026-11-26 (`:feature:home:testDebugUnitTest`),
  termasuk test layout compact 411dp, dua panel 720dp, 8 test `ScanSheetBody`,
  `NetworkPolicyTest` (`no_network`/`wrong_network`, tanpa retry saat `auth_failed`), dan
  `ReconnectPolicyTest` + `HomeReconnectTest` (backoff 1-2-4-8-16 dtk, reset setelah live, berhenti di batas),
  dua test `EngineChangeSignal` (event Rust memicu satu baca status tanpa menunggu poll;
  event tanpa snapshot baru tidak menggeser jam "diperbarui"), tiga test recalibration
  (notice tampil, tombol "Buka peta meja" membuka mapping, notice hilang saat terkalibrasi),
  satu test sandi salah yang tidak dilaporkan sebagai masalah Wi-Fi, dan satu test
  overflow menu yang membuka layar "Sinkronisasi server".
- `feature:onboarding`; 41 test JVM/Compose lulus pada 2026-11-26
  (`:feature:onboarding:testDebugUnitTest`): 11 `DeviceAssessmentTest`, 10 `OnboardingViewModelTest`,
  4 `DeviceReportTest`, 3 `NativeLibraryLoadTest`, 3 `DataStoreOnboardingStoreTest`,
  7 `DeviceCheckScreenTest`, dan 3 `OnboardingUiStateTest`. Warm-up native hanya dipanggil
  bila API >= 29, ABI arm64, pustaka termuat, dan model terpasang; `blocked` tidak pernah
  menjadi `Approved`.
- Gate gabungan 2026-11-26 lewat wrapper `gradle-run` lulus: 555 test dari 83 `TEST-*.xml`
  hijau (`app` 130, `core:database` 32, `core:model` 63, `core:network` 43, `core:sync` 14,
  `core:ui` 12, `feature:auth` 36, `feature:cameras` 37, `feature:home` 66,
  `feature:onboarding` 41, `feature:settings` 28, `feature:tables` 53), `:app:assembleDebug`
  menghasilkan `app-debug.apk` (143 MB, tiga ABI resmi), dan `:app:lintDebug` 0 error. Dari 25 warning, semua soal
  versi dependensi yang sengaja dipin (`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`); tidak ada warning kode.
  Rincian outbox/sync: `OutboxDaoTest` 9 test (urutan `AUTOINCREMENT`, batch tidak melewati event tertua, ACK prefix
  idempoten, isolasi `device_id`, `attempts`/`last_error`, prune hanya baris tersinkron), migrasi 2->3 di
  `MonitorDatabaseMigrationTest`, `SyncContractsTest` 8 test yang juga memvalidasi encoder ke schema asli,
  `OutboxRepositoryTest` 7 test, dan `TableEventRecorderTest` 10 test (anchor `since_ms`, dedupe transisi,
  urutan penulisan, `health()` tidak dipanggil di thread callback, timestamp tidak pernah di masa depan).
  Jalur HTTP ditutup `KtorSyncTransportTest` (status 200/201/409/413/400/401/403/429/5xx tanpa membocorkan
  token/host), `SyncRunnerTest` (retry ladder persisten melintasi re-enqueue), dan `SyncSchedulerTest`
  (KEEP untuk debounce, APPEND_OR_REPLACE untuk follow-up, clamp 10 dtk, REPLACE untuk "sync now").
  Heartbeat ditutup `HeartbeatJsonTest` + validasi schema (`:core:model`), `HeartbeatBuilderTest`
  (status wire, `unknown` tidak dilebur, tidak ada RTSP/token), `HeartbeatRunnerTest`
  (live 30 dtk, idle 60 dtk, stop saat 401, tanpa endpoint tidak mengirim), `HeartbeatSchedulerTest`
  (rantai REPLACE/APPEND_OR_REPLACE + clamp), dan `KtorHeartbeatTransportTest`
  (path `/v1/heartbeat`, Bearer, 401/429/5xx, body metadata-only).
  `TableEventRecorder` membaca `health()` setelah callback dan menyerialkan penulisan; batas ini mengikuti
  komentar `EngineListener` di `core-ffi` yang melarang callback sinkron ke engine.
  Lima test `RustEngineEventSourceTest` menutup jalur event: library hilang, factory melempar, subscribe sekali
  per proses, baseline sebelum event pertama, dan burst 20 event menjadi tepat satu refresh tertunda.
  Lima test `RustEngineStatusSourceTest` mengunci pemetaan `health()`: `auth_failed` tetap `AuthFailed` (bukan
  masalah Wi-Fi), `timeout` dan `unreachable` tetap terpisah, stream live tanpa penyebab kegagalan,
  `needs_recalibration` diteruskan, dan library hilang menjadi `Unavailable`.
- Fixture poligon kanonik di `contracts/fixtures/valid/meja-mock.json`.
- Crate `contracts`: enam schema draft 2020-12, validasi registry offline tanpa akses
  jaringan, 28 test hijau 2026-11-26 (`cd contracts; cargo fmt --all -- --check`, `cargo clippy --all-targets -- -D warnings`, dan `cargo test`
  `cargo clippy --all-targets -- -D warnings`, `cargo test`, `cargo audit`,
  `cargo deny check`).
- Test ONNX sintetis di `tools/onnx-test/` untuk menguji kontrak runtime.
- `testdata/SOURCES.md` mencatat asal empat klip stok.
- `core:sync`: `SyncCoordinator` + `SyncBackoff` + `SyncEndpoint` + interface `SyncSettings`;
  `:core:sync:test` lulus 14 test. `HeartbeatTransport`/`HeartbeatResult` ditambahkan di modul
  yang sama tanpa dependensi Android.
- `feature:settings`: layar "Sinkronisasi server" dengan validasi HTTPS, state token yang tidak
  pernah disimpan di UI state, dan tombol simpan/hapus token; `:feature:settings:testDebugUnitTest`
  lulus 28 test.
- Jalur sync `app`: `KtorSyncTransport` (Bearer token, redaksi host/token), `OutboxSyncAdapter`,
  `SyncAttemptStore` DataStore, `SyncRunner`, `SyncScheduler`, `SyncWorker` (`@HiltWorker`), dan
  `SyncModule` Hilt; `:app:testDebugUnitTest` lulus 130 test.
- Heartbeat: `HeartbeatBuilder`, `HeartbeatRunner`, `HeartbeatScheduler`, `HeartbeatWorker`,
  `KtorHeartbeatTransport`, `AndroidHeartbeatFacts`, dan `HeartbeatSession`; encoder divalidasi
  ke `contracts/schemas/heartbeat.schema.json`. Chain dimulai saat monitoring aktif dan
  dihentikan saat stream ditutup.

Yang **belum** dibuktikan pada jalur sync:

- Belum pernah berbicara dengan `server/` nyata lewat jaringan (server baru diuji lewat router in-process); ACK, `409`, dan `413`
  hanya diuji lewat transport palsu/mock.
- `/v1/snapshot`, `table_logs`, dan prune riwayat belum ada.
- Token masih di DataStore, belum di Android Keystore.
- `SyncSchedulerTest`/`HeartbeatSchedulerTest` menguji keputusan antrean murni; `WorkManager`
  nyata, Doze, dan rantai heartbeat di perangkat belum diuji.
- `AndroidHeartbeatFacts` membaca RAM/storage/suhu/termal di host tidak bisa diverifikasi;
  nilainya baru terbukti di perangkat.

Yang tidak boleh diklaim dari bukti itu:

- APK sudah berjalan.
- Login/Home/Scan sudah berjalan di perangkat fisik.
- Notifikasi foreground service, perilaku Doze, dan ketahanan `WAKE_LOCK` sudah teruji di perangkat; bukti yang ada hanya Robolectric/JVM.
- Total meja berasal dari model ONNX nyata; `Engine.start` dan `pushFrame` sudah dipanggil, tetapi belum ada model/runtime yang diuji di perangkat.
- Kamera sudah terhubung.
- Akurasi sudah terukur.
- CI sudah hijau.
- Server sudah menerima event.

## 5. Aturan pembaruan status

1. Setiap klaim status harus menyebut artefak atau perintah yang membuktikannya.
2. Test host tidak menaikkan status menjadi "perangkat teruji".
3. Build Android tidak menaikkan status menjadi "lapangan teruji".
4. Dokumen rencana boleh memakai bentuk future tense; dokumen status memakai
   bentuk faktual.
5. Jika bukti hilang atau tidak bisa dijalankan ulang, turunkan status menjadi
   "belum diverifikasi".

## 6. Cakupan rencana Gemini

Share Gemini terbaru (25 November 2026) sudah diekstrak dan dibandingkan dengan
dokumen repo, bukan hanya dicatat sebagai tautan. Keputusan pentingnya sudah
terdistribusi sebagai berikut:

| Topik | Dokumen utama |
| --- | --- |
| Batas edge/VPS, alasan hybrid ditolak | `architecture.md`, `hybrid-mode.md`, ADR 0001 |
| Struktur workspace Rust dan FFI | `rust-scaffold.md`, ADR 0002 |
| RTSP, jaringan lokal, dan kamera | `network-topology.md`, `camera-setup.md` |
| Target FPS dan anggaran panas/CPU | `performance.md` |
| YOLOv11n dan kontrak frame | `architecture.md`, `tdd-plan.md` |
| Offline-first, Room, retensi 7 hari | `android-sync.md`, ADR 0006 |
| Diagnostik dan profil perangkat | `device-compatibility.md` |
| Alur UI dan preview | `android-app-plan.md` |
| Mapping meja dan koordinat 0..1 | `table-mapping-plan.md`, ADR 0008 |
| Sumber video uji dan fixture | `testdata.md`, `testdata/SOURCES.md` |
| Monitoring, heartbeat, dan alert | `monitoring.md`, ADR 0004 |
| Kontrak server metadata | `server-api.md`, `data-contracts.md` |
| Rilis, signing, dan model | `release.md` |
| Urutan kerja dan gate | `roadmap.md`, `acceptance.md` |

Yang tetap **belum** diputuskan atau belum dibuktikan:

- Pilihan decoder dan jembatan preview (ADR 0009 masih **Diusulkan**).
- Kontrak `push_frame` sudah diputuskan dan diuji (RGB888 copy, max width 640, caller
  `Dispatchers.Default`; lihat `rust-scaffold.md` 7.1 dan 5 test FFI), tetapi
  `DirectByteBuffer`/pointer tanpa copy belum dipilih dan belum boleh disebut zero-copy
  tanpa pengukuran alokasi.
- Kebijakan `WakeLock`/`WifiLock`, battery optimization OEM, dan perilaku Doze untuk
  service yang harus hidup berjam-jam.
- Dispatch callback Rust lintas thread: antrean background, penulisan Room, lalu
  `Dispatchers.Main` hanya untuk update UI.
- Decoder H.264/H.265 konkret dan integrasi RTSP -> decode -> AI -> UI.
- Model kamera, firmware, codec, path RTSP final, dan batas sesi kamera.
- Anchor deteksi: pusat kotak atau titik kaki.
- Implementasi server, JSON Schema, dan workflow CI.
- Build APK, lint, test Compose/instrumented, test perangkat fisik, dan akurasi lapangan.
