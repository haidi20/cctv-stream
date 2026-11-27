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

## 1. Snapshot 2026-11-27

| Area | Status | Bukti | Yang belum |
| --- | --- | --- | --- |
| Workspace Rust | Ada; gate hijau 2026-11-27 | `rust-core/Cargo.toml`, 261 test workspace | Verifikasi perangkat dan akurasi model |
| `stream-engine` | Ada; test host terakhir tercatat lulus | RTSP client, file-source pacing, ring buffer, sampler | Decoder konkret, loop produksi, dan integrasi ke preview |
| `ai-engine` | Ada; test host terakhir tercatat lulus | `ort`, preprocess, postprocess, NMS | Akurasi model di kamera nyata |
| `spatial-engine` | Ada; test host terakhir tercatat lulus | polygon, geometry, debounce, multi-table, dan `view` (sidik jari luminans 32x18: kamera yang dipan/digeser memaksa semua meja `unknown`, ADR 0011); 50 test unit + 12 test integrasi `view_change` | Keputusan anchor dan acceptance lapangan; biaya guard di perangkat belum diukur |
| `core-ffi` | Ada; test host + gate hijau | binding UniFFI, listener, panic guard, `camera_id` pada config/health, `view_moved` pada `EngineHealth`; `cargo test --workspace` 261 test (termasuk empat test regresi generasi sesi, lima test kontrak frame, metrik resource R-P0-1, dan lima test guard kamera bergeser) dan `cargo ndk` tiga ABI lulus 2026-11-27 | Kontrak frame dan Surface |
| Android scaffold | Ada | Gradle root dan modul `app`, `core:model`, `core:database`, `core:network`, `core:sync`, `core:ui`, `feature:cameras`, `feature:tables`, `feature:auth`, `feature:home`, `feature:onboarding`, `feature:settings`; single-activity Compose (diagnostik perangkat -> login PIN -> Home -> Scan -> stream + total meja), Room v6 dengan outbox dan `table_logs`, binding, `jniLibs`, `EngineStartPlanner`, `EngineFramePump`, `TableEventRecorder`, dan `RustEngineEventSource` (`Engine.subscribe` -> sinyal coalesced); provider telemetri ONNX Runtime dihapus lewat manifest merge; foreground service mediaPlayback + notifikasi pemantauan ada; `SyncWorker` Hilt + `SyncScheduler` + `KtorSyncTransport` mengirim outbox ke `/v1/sync`, dan layar `feature:settings` menyimpan base URL/token | Verifikasi perangkat, server nyata, dan kamera |
| `core:ui` Kotlin | Unit-tested di host | `MonitorTheme` (Material 3, dynamic colour 12+) dan `TimeFormat` (`clock` per zona, `dateTime` tanggal+jam, `age` "12 detik lalu"/"1 menit lalu"/fallback jam); `:core:ui:test` lulus 7 test pada 2026-11-27 | Uji visual di perangkat |
| `core:model` Kotlin | Unit-tested di host | `android/core/model`; `:core:model:test` lulus 64 test pada 2026-11-27 (termasuk `CameraProfileTest`, `ContractSchemaTest`, dan `HeartbeatJsonTest`); encoder heartbeat divalidasi ke schema asli | Integrasi lapangan |
| `feature:tables` | Unit-tested di host | Source domain/UI/data ada; `:feature:tables:testDebugUnitTest` lulus 53 test pada 2026-11-26 (termasuk test Compose `TableMappingScreenTest` dan `TableMappingSnapshotTest`) dan terikat per `camera_id` | Verifikasi snapshot `TextureView` di perangkat |
| `core:network` Kotlin | Unit-tested di host | `android/core/network` (JVM murni): `SubnetScan`, `CameraPortScanner` (port 554/8554, concurrency 32, timeout 400 ms), parser `WsDiscovery`, `DiscoveryMerge`, dan `CameraDiscovery` (WS + probe paralel, cache 10 menit, tolak data seluler); `:core:network:test` lulus 43 test | Probe dan kamera di jaringan nyata |
| `feature:cameras` | Unit-tested di host | Repository, `CameraSession`, ViewModel, dan Compose list/editor; `:feature:cameras:testDebugUnitTest` lulus 49 test pada 2026-11-27 (termasuk `CameraScanViewModel`, tes koneksi empat lapis, dan password RTSP sekali simpan ke `CameraSecretStore`) | Verifikasi perangkat dan preview RTSP |
| `feature:auth` | Unit-tested di host | PIN PBKDF2 + lockout + reset lokal, ganti PIN tiga langkah (PIN lama -> PIN baru dua kali; entry point tombol "Ganti PIN" di samping reset `HAPUS`), haptic per digit, dan seam biometrik (`BiometricUnlocker`, `AndroidBiometricUnlock`); `:feature:auth:testDebugUnitTest` lulus **50 test** pada 2026-11-27 | Prompt biometrik di perangkat fisik dan verifikasi perangkat |
| `feature:onboarding` | Unit-tested di host | Empat baris diagnostik (platform, memori, mesin AI, penyimpanan) dan profil `full`/`balanced`/`lite`/`blocked`; warm-up engine nyata lewat `Engine.start` + `pushFrame` 64x64 bila model terpasang; `blocked` tidak pernah membuka Home; hasil disimpan per `versionCode` di DataStore; `:feature:onboarding:testDebugUnitTest` lulus 41 test pada 2026-11-26; profil yang tersimpan dipakai `AndroidHeartbeatFacts` untuk `device.profile` | Warm-up nyata di perangkat, model ONNX yang dibundel, dan permission notifikasi runtime |
| `feature:home` | Unit-tested di host | Home adaptif compact/wide, state stream, total kosong/terisi/unknown, scan sheet, catatan mode hemat `lite`, banner recalibration (rasio berubah **atau** kamera bergeser - `viewMoved` dari Rust), backoff reconnect 1-2-4-8-16 dtk, menu overflow "Sinkronisasi server", badge versi (`app-version`) dan label `devel` di kanan atas (debug), panel analisa per meja (`analysis-panel`, `tables`/`detections` dari Rust, fps target, usia inferensi, tanggal+jam frame (`analysis-frame-time`) dan delay frame->inferensi), overlay box deteksi (`detection-overlay`), serta indikator online/offline (`offline-banner` persisten saat seluler/offline, `back-online-banner` gaya iPhone: kartu hijau 0xFFB8F0C6 bershadow yang meluncur turun dari atas dan hilang sendiri setelah 2,5 dtk; jendela tampil dimiliki `HomeViewModel` sehingga recomposition tidak pernah mengulang animasi); `:feature:home:testDebugUnitTest` lulus **118 test** pada 2026-11-27 (termasuk 35 `HomeScreenTest`, 6 `HomeConnectivityTest`, 6 `HomeAnalysisTest` dengan tanggal+jam frame dan delay, 8 test badge/panel/overlay, dan test jeda/mulai analisa yang mempertahankan kamera serta stream yang sedang berjalan) | Model ONNX nyata di perangkat, akurasi hitungan, discovery nyata |
| `feature:settings` | Unit-tested di host | Dua layar tanpa bottom navigation: "Sinkronisasi server" (validasi `SyncUrl` HTTPS tanpa kredensial/query/fragment, maks 200 karakter; `SyncSettingsViewModel` dengan token yang tidak pernah masuk state; pintu masuk "Penyimpanan & Riwayat") dan "Penyimpanan & Riwayat" (`RetentionViewModel` + `RetentionScreen`: empat masa simpan 3/7/14/30 hari dengan bawaan 7 hari, kartu baris + ukuran basis data, peringatan retensi 3 hari, simpan non-destruktif); `:feature:settings:testDebugUnitTest` lulus 46 test pada 2026-11-28 | Verifikasi perangkat |
| `core:sync` Kotlin | Unit-tested di host | `SyncCoordinator` (batch 100, maks 20 batch, ACK prefix, replay `409` sekali, halving `413`), `SyncBackoff` (30 dtk/1 mnt/5 mnt/15 mnt), `SyncEndpoint` HTTPS-only, `RetentionWindow`/`RetentionPolicy` (3/7/14/30 hari, batas 10 000 baris), dan interface `SyncSettings`/`SyncTransport`/`RetentionSettings`/`LocalHistoryStore`; `:core:sync:test` lulus 18 test pada 2026-11-28 | Server nyata |
| Room v6 | Ada + unit-tested | `camera_profiles`, `outbox`, `table_logs`, DAO, `MIGRATION_1_2` (seed `camera-default`), `MIGRATION_2_3` (tabel `outbox` + index `(is_synced, seq)`), `MIGRATION_3_4` (`monitoring_enabled` default 1), `MIGRATION_4_5` (`device_id`), dan `MIGRATION_5_6` (tabel `table_logs` + index `index_table_logs_prune` pada `(sync_status, event_at)`), schema v1..v5 di `core/database/schemas`; `:core:database:testDebugUnitTest` lulus 32 test pada 2026-11-28 (termasuk `OutboxDaoTest`, `TableLogDaoTest` 6 test, pause/resume per kamera, dan migrasi 1->2 / 2->3 / 3->4 / 5->6 yang mempertahankan profil dan peta meja) | Migrasi pada database perangkat nyata |
| Outbox + recorder + sync + heartbeat | Ada + unit-tested | `OutboxRepository`, `DeviceIdStore` DataStore, `TableEventRecorder`, serta pipeline sync: `KtorSyncTransport`, `SyncRunner`, `SyncWorker` (`@HiltWorker`), `SyncScheduler`. Heartbeat: `HeartbeatBuilder` (EngineStatus -> wire enum, `unknown` tetap terpisah), `HeartbeatRunner` (30 dtk live / 60 dtk idle, stop saat token ditolak), `HeartbeatScheduler` (rantai `OneTimeWorkRequest`, clamp 10 dtk), `HeartbeatWorker`, `KtorHeartbeatTransport`, `AndroidHeartbeatFacts`, dan `HeartbeatSession`; chain dimulai/dihentikan oleh `MonitoringHub`; token VPS tidak lagi di DataStore melainkan di `KeystoreSyncTokenStore` (AES-256-GCM `AndroidKeyStore`, alias `cctv_sync_token`, file `filesDir/sync/token.bin`, migrasi sekali dari pref plaintext `sync_token`, ikut terhapus pada reset "Lupa PIN"); `:app:testDebugUnitTest` lulus 241 test pada 2026-11-28 (termasuk `RustEngineStatusSourceTest`, `KeystoreSyncTokenStoreTest` 6 test, `DataStoreSyncSettingsTest` 5 test, `RoomTableHistoryTest` 5 test, `HistoryPrunerTest` 3 test, `OutboxSyncAdapterTest` 4 test, dan `DataStoreRetentionSettingsTest` 3 test) | `/v1/snapshot` dan server nyata |
| Android build | Lulus di host, terpasang di perangkat (versi 0.2.0-dev) | `testDebugUnitTest :app:lintDebug :app:assembleDebug` lulus lewat wrapper `gradle-run` pada 2026-11-27 (termasuk rerun setelah indikator online/offline, alur ganti PIN, dan toggle monitoring global/per kamera, dan tombol sambung ulang per kamera, dan tombol jeda/mulai analisa yang menjeda pompa frame lalu menyambungkannya kembali ke stream yang sedang berjalan); APK `android/app/build/outputs/apk/debug/app-debug.apk` (149 MiB; `abiFilters` membatasi tiga ABI resmi `arm64-v8a`/`armeabi-v7a`/`x86_64`), lint 0 error / 29 warning (semua versi dependensi yang sengaja dipin, plus satu `ObsoleteSdkInt` di `AndroidHeartbeatFacts`); **705 test hijau dari 91 `TEST-*.xml` unit test debug** (`app` 207, `core:database` 24, `core:model` 64, `core:network` 43, `core:sync` 14, `core:ui` 7, `feature:auth` 57, `feature:cameras` 49, `feature:home` 118, `feature:onboarding` 41, `feature:settings` 28, `feature:tables` 53); dengan varian release `core:database` (+11) dan `core:ui` (+7) menjadi 723 test dari 95 artefak; APK debug + APK androidTest pernah dipasang di SM-A176B (2026-11-27); instalasi ulang belum diulang (adb kosong). Rerun 2026-11-28 setelah token pindah ke Keystore: `:app:testDebugUnitTest` 224 test / 0 gagal dari 33 `TEST-*.xml`, `:app:lintDebug` 0 error / 29 warning (kategori sama), `:app:assembleDebug` lulus. Rerun 2026-11-28 setelah `table_logs` + prune + layar retensi: `:core:sync:test` 18 test, `:core:database:testDebugUnitTest` 32 test, `:feature:settings:testDebugUnitTest` 46 test, `:app:testDebugUnitTest` 241 test / 0 gagal, `:app:lintDebug` 0 error / 29 warning (25 versi dependensi yang sengaja dipin + `ObsoleteSdkInt` di `AndroidHeartbeatFacts` + 3 versi dependensi lain yang sudah usang sebelum perubahan ini), `:app:assembleDebug` menghasilkan `app-debug.apk` 150 MiB | Soak, lint warning versi dependensi yang sengaja dipin |
| Server | Ada + teruji host | `server/` (Axum + SQLite): `/health`, `/v1/sync`, `/v1/heartbeat`, `/v1/snapshot`, `/v1/ws`; 17 test integrasi + 7 unit hijau 2026-11-26 | Dashboard web, deploy VPS, PostgreSQL produksi |
| Kontrak JSON | Ada + unit-tested | Enam schema draft 2020-12 di `contracts/schemas/`, crate `contracts` standalone dengan validasi registry offline; `cd contracts; cargo test` 28 test hijau 2026-11-26; Kotlin memvalidasi fixture yang sama lewat `ContractSchemaTest` | Pemakaian schema di server |
| CI | Direncanakan | command lokal di `AGENTS.md` | Workflow CI |
| Kamera nyata | Belum diverifikasi | belum ada unit | Model, path, codec, sesi |
| Perangkat fisik | Terpasang + benchmark, e2e, dan soak 30 menit lulus 2026-11-27 | Samsung SM-A176B (Android 16/API 36, RAM 7462 MB): APK debug + APK androidTest terpasang, `DeviceInferenceBenchmarkTest` lulus dengan model YOLO11n resmi (`cctv-benchmark.txt`), `DevVideoPipelineTest` lulus (`cctv-dev-e2e.txt`, 10/15/24 FPS diminta -> 4,5/3,8/3,8 capture & analysis FPS, `tables=2`, `modelReady=true`), soak 30 menit lulus (`cctv-soak.csv`/`.txt`: 30 sampel, PSS 158 122->246 160 kB, RSS 279 392->372 608 kB, baterai 100% (USB), suhu 33,7->34,9 C, thermal `NONE`, 3 561 frame masuk / 2 786 sampel / 774 drop, meja `AVAILABLE`). Setelah perbaikan jadwal sampler `core-ffi`, soak 10 menit pembanding mencatat 1 186/1 186 frame, **0 drop** | Soak 8 jam layar mati, kamera nyata, HP 4 GB |
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

### 1.1 Prioritas MVP dan audit 2026-11-27

Urutan kerja yang disepakati: **MVP dulu** — satu kamera (video lokal selama kamera belum
ada), status meja kosong/terisi/unknown, monitoring latar, dan bukti resource (baterai,
RAM, ROM, CPU, suhu). Rincian P0/P1/P2, ambang gate, dan yang sengaja ditunda ada di
[`mvp-priority.md`](mvp-priority.md); protokol ukurnya di
[`performance.md`](performance.md) §10 dan aturan klipnya di
[`testdata.md`](testdata.md) §1.2.

Hasil audit dokumentasi 2026-11-27 (membaca kode dan artefak build), lalu dihitung ulang
dari artefak `TEST-*.xml` setelah run broad `gradle-run` pada 2026-11-27 malam:

| Modul | Test (artefak) | Gagal |
| --- | --- | --- |
| `app` | 207 | 0 |
| `core:database` | 35 | 0 |
| `core:model` | 64 | 0 |
| `core:network` | 43 | 0 |
| `core:sync` | 14 | 0 |
| `core:ui` | 14 | 0 |
| `feature:auth` | 57 | 0 |
| `feature:cameras` | 49 | 0 |
| `feature:home` | 118 | 0 |
| `feature:onboarding` | 41 | 0 |
| `feature:settings` | 28 | 0 |
| `feature:tables` | 53 | 0 |
| **Total** | **723** | **0** |

Tabel audit di atas dihitung ulang dari artefak `TEST-*.xml` pada 2026-11-27 dan sudah sama
dengan §1: 705 test debug-only dari 91 XML, 723 dari 95 XML bila varian release
`core:database` dan `core:ui` ikut dihitung. Tidak ada lagi selisih angka antar bagian.

| Temuan | Bukti | Dampak |
| --- | --- | --- |
| `tracing` JSON + redaksi terpusat sudah dipakai di `core-ffi` (R-P1-1 selesai 2026-11-28) | `rust-core/core-ffi/src/observability.rs` memasang subscriber JSON berputar (3 file); `Engine::new` memakai `CCTV_LOG_DIR` yang diekspor `EngineLogDirectory` sebelum engine dibuat; `panic_guard`/watchdog memakai `tracing::error!`. Test: `core-ffi/tests/logging.rs`, `EngineLogDirectoryTest`, dan unit redaksi; gate Rust + `:app:testDebugUnitTest` hijau | Log siap dipakai untuk analisis resource; angka di perangkat masih butuh ADB |
| Rotasi log belum sesuai target ukuran `monitoring.md` §2.3 | Implementasi memakai `Rotation::MINUTELY` + 3 file (`tracing-appender` 0.2.5 tidak punya batas ukuran); target dokumen adalah 3 x 5 MB | Masih aman untuk storage (3 file), tetapi satu menit bisa menampung lebih dari 5 MB bila event sangat padat. Perlu writer khusus bila target 5 MB diwajibkan |
| `EngineHealth` sudah memuat `rss_kb`, `frames_dropped`, `inference_p50_ms`, `inference_p95_ms` (R-P0-1 host selesai 2026-11-27) | `rust-core/core-ffi/src/types.rs`, `api.rs`, `state.rs`; test lib `core-ffi` 61 hijau | RSS/p50/p95 nyata sudah masuk `cctv-soak.csv` (soak 10 menit); CPU% belum ada sebelum sample kedua |
| `tools/device-soak.ps1` + `DeviceSoakTest` terverifikasi 2026-11-27 | `tools/device-soak.ps1` menjalankan instrumented `DeviceSoakTest` dengan `-DurationMin`/`-IntervalSec`, menarik `cctv-soak.csv` dan `cctv-soak.txt`; unit test host untuk CSV/parser CPU 16 test hijau. Keyguard diatasi dengan `FLAG_SHOW_WHEN_LOCKED`. **Soak 30 menit lulus**: 30 sampel 60 dtk, PSS 158 122->246 160 kB, RSS 279 392->372 608 kB, baterai 100% (USB), suhu 33,7->34,9 C, thermal `NONE`, `framesIn=3561 sampled=2786 dropped=774`, status `{AVAILABLE=4}`, `tablesChanged=true`. Perbaikan jadwal sampler (toleransi 10% interval, maks 50 ms) menurunkan drop 10 menit dari 214 ke **0** (`framesIn=1186 sampled=1186`) | Soak 8 jam layar mati, HP 4 GB, frame drop RTSP nyata |
| `DevVideoPipelineTest` **lulus** 2026-11-27 di SM-A176B | API 36 arm64; `libcore_ffi.so` lama diperbarui lewat `build-rust.ps1`; `TextureView` tersedia setelah activity memakai `FLAG_SHOW_WHEN_LOCKED`, jadi keyguard tidak lagi menghalangi. Laporan: tekstur 1080x607, 10 FPS diminta -> 4,5 capture/s, 15/24 FPS -> 3,8 capture/s, `tables=2`, status `AVAILABLE,AVAILABLE`, `modelReady=true` | R-P0-2 terverifikasi di perangkat; angka laju ini sendiri bukan klaim 24 FPS |
| `table_logs` dan `device_settings` belum ada | Room v4 hanya `camera_profiles`, `outbox`, `monitoring_enabled` | retensi dan prune belum mungkin (K-P1-1) |
| `ai-engine/src/geometry.rs` dan `core-ffi/src/callback.rs` tidak ada | struktur crate | fungsi tidak hilang (melebur ke `postprocess.rs`/`api.rs`), tetapi peta file `rust-scaffold.md` §4 tidak dipatuhi |
| `ai-engine/testdata/` kosong, `rust-core/.cargo/config.toml` tidak ada | inspeksi folder | golden frame dan konfigurasi linker per target belum ada |
| Klip uji 5-23 detik berwatermark | `testdata/videos/` + `SOURCES.md` | cukup untuk uji resource lokal, tidak untuk akurasi atau distribusi |

Snapshot §1 tidak diubah oleh audit ini. Angka baru boleh masuk §1 setelah gate dijalankan
ulang dan hasilnya dicatat.

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

- Perbaikan load ORT Android 15+ (2026-11-27): `EngineAssetPaths` kini memakai `nativeLibraryDir` bila file
  terekstrak ada, lalu fallback memuat soname `libonnxruntime.so` lewat linker proses dan mengembalikan nama
  itu ke Rust. Sebelumnya `nativeLibraryDir` kosong di Android 16 (`targetSdk 36`), sehingga `onnxRuntimePath()`
  `null` dan warm-up selalu `ONNX_INIT`; 36 MB tidak disalin ke storage internal. Bukti: `EngineAssetPathsTest`
  3 test, `runtime.rs` 3 test (`is_library_soname`), dan `Engine.start` nyata di SM-A176B.
## 3. Artefak yang belum ada

| Artefak | Status | Dampak |
| --- | --- | --- |
| `contracts/schemas/*.schema.json` | Ada | Enam schema draft 2020-12 dengan `$id` absolut dan `additionalProperties: false` |
| `contracts/fixtures/invalid/` | Ada | 15 fixture negatif; setiap schema punya minimal satu |
| `contracts/tests/schema_test.rs` | Ada + lulus | 23 test integrasi, termasuk lima kasus wajib `tdd-plan.md` Â§6 |
| Pemakaian schema di Kotlin | Ada + unit-tested | `ContractSchemaTest` (`:core:model:test`) memvalidasi 6 fixture `valid/` dan 15 `invalid/` langsung dari `contracts/schemas` via `com.networknt:json-schema-validator` 1.5.6, plus output `TableConfigJson.encode`. Tidak ada salinan schema di Kotlin |
| Pemakaian schema di server | Ada + unit-tested | Handler `/v1/sync`/`/v1/snapshot` memvalidasi payload lewat crate `contracts`; payload dengan field `image` ditolak |
| `tools/ci/verify.ps1` | Ada + diverifikasi | Gate satu perintah untuk contracts, rust-core (workspace/rtsp/core-ffi cli), tiga ABI NDK, dan seluruh task test/lint/assemble Android. `-SkipNdk`/`-SkipAndroid`/`-SkipAudit` untuk mesin tanpa toolchain. Instrumented test dan kamera nyata tetap di luar script |
| `.github/workflows/` | Belum | CI remote belum aktif; `verify.ps1` sudah bisa dipanggil runner Windows |
| `server/` | Ada | Axum + SQLite (`sqlx` bundled), auth token `ctv_`, rate limit, WebSocket replay; dashboard belum ada. Lihat `docs/server-implementation.md` dan ADR 0010 |
| APK Android | Ada + terpasang | `:app:assembleDebug` lulus 2026-11-27; `app-debug.apk` 148 MiB (tiga ABI resmi). Terpasang dan menjalankan `DeviceInferenceBenchmarkTest` di SM-A176B (Android 16/API 36) |
| Implementasi Room | Sebagian | `MonitorDatabase` v4, entity, DAO, migrasi 1->2 (seed `camera-default`), 2->3 (`outbox`), dan 3->4 (`monitoring_enabled`), serta schema v1/v2/v3/v4 ada; `table_logs` dan `device_settings` belum ada |
| UI dan foreground service | Sebagian | Manifest, `MainActivity`, theme, login PIN (PBKDF2 + lockout), Home adaptif (compact/wide), scan sheet, preview Media3/demo, total meja, mapping poligon, layar pengaturan sinkronisasi, serta foreground service mediaPlayback (notifikasi pemantauan + aksi Hentikan) ada; token VPS di `KeystoreSyncTokenStore` (AES-256-GCM `AndroidKeyStore`, migrasi sekali dari pref DataStore lama) |

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
- `feature:cameras`; 49 test JVM/Compose lulus pada 2026-11-27
  (`:feature:cameras:testDebugUnitTest`), termasuk 8 test `CameraScanViewModel` dan tes tombol "Tes koneksi".
- Modul `feature:tables`; 53 test JVM/Compose lulus pada 2026-11-26
  (`:feature:tables:testDebugUnitTest`), termasuk test isolasi peta per `camera_id`.
- `feature:auth`; 50 test JVM/Compose lulus pada 2026-11-27 (`:feature:auth:testDebugUnitTest`): 32 test domain
  (lockout, hasher, ViewModel), 15 `LoginScreenTest` (keypad, lockout, biometrik opsional, reset `HAPUS`,
  dan alur ganti PIN), 6 `BiometricUnlockerTest` (satu prompt sekaligus, fallback PIN), dan
  3 `PinKeypadHapticTest`
  (`HapticFeedbackType.TextHandleMove` per digit; keypad nonaktif tidak memberi haptic).
- `feature:home`; 107 test JVM/Compose lulus pada 2026-11-27 (`:feature:home:testDebugUnitTest`),
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
- Retensi lokal 2026-11-28 (`android-sync.md` §4/§6/§7): `table_logs` di Room v6 ditulis
  `TableEventRecorder` **setelah** baris outbox durable (kegagalan log tidak menghilangkan
  event), ditandai `synced` saat ACK lewat `OutboxSyncAdapter` -> `markSyncedUpTo(created_at
  baris terakhir yang di-ACK)`, dipangkas `HistoryPruneWorker`/`HistoryPruneScheduler`
  (harian + sekali saat start) memakai `HistoryPruner` (jendela 3/7/14/30 hari, batas 10 000
  baris), dan dilaporkan layar "Penyimpanan & Riwayat" di `feature:settings`. Bukti:
  `TableLogDaoTest` 6 test, `MonitorDatabaseMigrationTest` 6 (termasuk 5->6),
  `RoomTableHistoryTest` 5, `HistoryPrunerTest` 3, `OutboxSyncAdapterTest` 4,
  `DataStoreRetentionSettingsTest` 3, `RetentionViewModelTest` 7, `RetentionScreenTest` 6,
  `StorageSizeTest` 4, `RetentionPolicyTest` 4, dan 2 test baru di `TableEventRecorderTest`
  (log menerima transisi; log gagal tidak membatalkan outbox). Belum ada: guard "storage
  < 50 MB berhenti menulis log" dan verifikasi prune di perangkat.
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
- Dev mode & analisa per meja (2026-11-27, host-only): `CameraSource.Video` + `DevSeed`
  (kamera `cctv-simulasi` dengan klip `testdata/videos/istockphoto-1067633772-640_adpp_is.mp4`
  yang di-push `tools/device-e2e.ps1` ke `files/videos`), badge versi `0.2.0-dev` + label
  `devel` kanan atas, panel analisa (`tables`, `detections`, fps target, usia inferensi), dan
  overlay box dari Rust (`Engine.tables()`/`Engine.lastDetections()`); `core-ffi` menambah
  `EngineHealth.inferenceFps` dan `lastInferenceAgeMs` (54 test lib hijau). Ambang RAM total
  `EngineStartPlanner.inferenceFpsFor` (0 FPS < 2560 MB, 1 FPS <= 3072 MB, selain itu laju
  profil) diuji 17 `EngineStartPlannerTest`. Instrumented `DevVideoPipelineTest` +
  `tools/device-e2e.ps1` memutar klip lewat Media3, memacu pump pada 10/15/24 FPS, dan
  menjalankan inferensi model nyata; `:app:assembleDebugAndroidTest` hijau dan
  **test instrumented lulus di SM-A176B 2026-11-27** (`cctv-dev-e2e.txt`: 10/15/24 FPS
  diminta -> 4,5/3,8/3,8 capture & analysis FPS, `tables=2`, `modelReady=true`).
  Harness sengaja tidak mengklaim capture 10/15/24 FPS: `push_frame` berjalan sinkron, jadi
  capture dibatasi biaya inferensi (~4 FPS pada model 640); yang diukur adalah laju nyata
  dan sisa anggaran, bukan angka yang diminta.
- Fixture poligon kanonik di `contracts/fixtures/valid/meja-mock.json`.
- Crate `contracts`: enam schema draft 2020-12, validasi registry offline tanpa akses
  jaringan, 28 test hijau 2026-11-26 (`cd contracts; cargo fmt --all -- --check`, `cargo clippy --all-targets -- -D warnings`, dan `cargo test`
  `cargo clippy --all-targets -- -D warnings`, `cargo test`, `cargo audit`,
  `cargo deny check`).
- Test ONNX sintetis di `tools/onnx-test/` untuk menguji kontrak runtime.
- Jalur model 320 diuji di host 2026-11-28 lewat `a_320_export_runs_the_whole_preprocess_and_decode_path`
  dengan fixture `CCTV_ONNX_TEST_MODEL_320` (preprocessing 640x360 -> input 320 -> decoding ->
  NMS -> koordinat 320,180). Ini hanya membuktikan jalur tensor, bukan akurasi atau ambang RAM;
  angka p50/p95 model 320 dan PSS perangkat 3 GB masih belum terukur (ADB kosong).
- Baseline preprocessing Rust murni 2026-11-28 lewat `cargo run --release -p ai-engine --example
  preprocess_bench` (2000 frame/host): 640x360 -> 640 p50 2,819 ms / p95 5,578 ms;
  640x360 -> 320 p50 0,679 ms / p95 1,221 ms; 1920x1080 -> 640 p50 2,988 ms. Ini adalah angka
  host, bukan perangkat. Karena inferensi 640 di perangkat mencapai p50 216 ms, preprocessing
  bukan bottleneck CPU; optimasi berikutnya harus diukur pada sesi ORT/thread, bukan resize.
- A/B `benchThreads` diulang di perangkat 2026-11-28: 0 (3 worker) 214/250 ms, 2 worker
  200/206 ms, 4 worker 235/289 ms, 1 thread inline 303/328 ms pada 640x360 (pola sama di
  640x640). Urutan 2 < 0 < 4 < 1 mengonfirmasi pin 2 thread di `EngineStartPlanner`.
  CPU steady-state soak dengan 2 thread masih belum diukur.
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

- Performa & pemakaian ulang buffer (2026-11-26, host-only): `ai-engine` memakai
  `PreprocessScratch` (tensor + tabel sampling dipakai ulang antar frame, hasil identik
  dengan jalur lama untuk 97x61@64, 640x360@320, 168x168@96), sesi ORT dibangun dengan
  `intra_threads` dibatasi 4, `inter_threads` 1, parallel execution mati, dan spinning
  intra/inter mati, input size dibaca dari model bila `[1,3,H,W]` statis dan kelipatan 32,
  `core-ffi` memakai `default_intra_threads()`, dan `EngineFramePump` memakai satu thread
  `THREAD_PRIORITY_BACKGROUND` dengan buffer `IntArray`/`ByteArray` yang dipakai ulang.
  `VideoCaptures.captureInto` memakai ulang `Bitmap` target selama ukuran texture tidak
  berubah, dan profil `lite` menurunkan laju inferensi ke 1.5 FPS lewat
  `EngineStartPlanner.withInferenceFps` yang dipilih `RustCameraSession` dari `OnboardingStore`.
  `InferenceCadence` menurunkan laju setengah saat `SEVERE`+ atau RAM < 150 MB, dan `InferenceBudget` meneruskan FPS sesi ke pump sehingga batas 24 FPS di core-ffi bisa dipakai.
  Pump memakai pacing tetap: `InferenceCadence.paceDelayMillis` mengurangi waktu kerja tick
  dari interval, jadi pacing tidak lagi menambah delay di atas interval. Namun `push_frame`
  tetap sinkron, sehingga throughput capture nyata dibatasi biaya inferensi (~4 FPS untuk
  model 640) - menaikkan FPS yang diminta tidak menaikkan jumlah frame yang dianalisis.
  Jalur yang tidak siap (engine/texture) tetap menunggu interval, bukan berputar.
  Sesi ORT juga memakai `BackgroundThreadManager`: worker Android di-spawn dengan nice 10
  (`Process.THREAD_PRIORITY_BACKGROUND`), sehingga CPU inference kalah prioritas dari aplikasi
  yang dibuka pengguna; runtime yang menolak custom thread manager tetap menjalankan inferensi.
  `cargo test --workspace` hijau (termasuk 36 unit test + 2 test integrasi ONNX di `ai-engine`), `cargo ndk` hijau untuk
  tiga ABI, `:app:testDebugUnitTest` 170 test hijau, `:app:lintDebug` 0 error,
  `:app:assembleDebug` menghasilkan APK. Latensi perangkat 2026-11-27: YOLO11n resmi
  (input 640) pada SM-A176B mencapai p50 216 ms (frame 640x360) dan 238 ms (640x640),
  ~4 FPS, worker ORT nice 10; rincian di `docs/performance.md` §6.3.1. Suhu dan RSS
  soak 10 menit terukur (33,7 C, RSS 288 580->366 032 kB); 30 menit sedang
  dijalankan, 8 jam belum diukur.
- Metrik resource Rust R-P0-1 (2026-11-27, host-only): `EngineHealth` memuat `rss_kb`
  (`/proc/self/status` -> `VmRSS`, `None`/0 bila tidak terbaca), `frames_dropped`
  (sampler melewati frame karena detektor masih sibuk), `inference_p50_ms`, dan
  `inference_p95_ms` (nearest-rank dari 120 sampel terakhir, reset tiap sesi).
  Binding UniFFI Kotlin diregenerasi dan memuat keempat field; `RustEngineStatusSource`
  memetakannya ke `EngineStatus`, dan `HeartbeatBuilder` mengirim `runtime.rss_kb` +
  `runtime.frames_dropped` serta `inference.p50_ms`/`p95_ms` bila sampel ada.
  Kontrak `heartbeat.schema.json` v6 menambah dua field aditif dengan fixture
  `heartbeat.resource-metrics.json`; `contracts` 28 test hijau. Test lib `core-ffi`
  61 hijau (termasuk percentile, RSS, dan `frames_dropped`), workspace Rust 261 test
  hijau. Angka RSS/p50/p95 pada perangkat sudah masuk laporan benchmark dan soak
  10 menit (`cctv-benchmark.txt`, `cctv-soak.csv`); yang belum dinilai adalah ambang
  30 menit/8 jam.Yang **belum** dibuktikan pada jalur sync:

- Belum pernah berbicara dengan `server/` nyata lewat jaringan (server baru diuji lewat router in-process); ACK, `409`, dan `413`
  hanya diuji lewat transport palsu/mock.
- `/v1/snapshot`, `table_logs`, dan prune riwayat belum ada.
- Jalur `AndroidKeyStore` nyata belum diuji di perangkat: `KeystoreSyncTokenStoreTest` dan
  `KeystoreCameraSecretStoreTest` memakai cipher AES-GCM JVM sebagai pengganti karena
  Robolectric tidak menyediakan provider `AndroidKeyStore`.
- `SyncSchedulerTest`/`HeartbeatSchedulerTest` menguji keputusan antrean murni; `WorkManager`
  nyata, Doze, dan rantai heartbeat di perangkat belum diuji.
- `AndroidHeartbeatFacts` membaca RAM/storage/suhu/termal di host tidak bisa diverifikasi;
  nilainya baru terbukti di perangkat.

Guard kamera bergeser (ADR 0011, 2026-11-27, host-only):

- `spatial-engine::view` membandingkan sidik jari luminans `32x18` dengan baseline peta
  dan memberi verdict `changed_fraction`/`moved`/`became_moved`/`recovered`; 12 test unit
  di `view.rs` dan 12 test integrasi `tests/view_change.rs` hijau (`cargo test -p
  spatial-engine` 50 unit + 12 integrasi).
- `core-ffi` menjalankan guard pada setiap frame yang dianalisis, sebelum debounce:
  meja menjadi `unknown`, `needs_recalibration` menyala, `EngineHealth.view_moved`
  terisi. Diuji oleh `a_panned_camera_never_reports_a_confident_table_status`,
  `a_person_walking_through_the_room_keeps_the_reading`,
  `the_map_works_again_once_the_camera_returns`,
  `installing_a_new_map_accepts_whatever_the_camera_shows_now`, dan
  `health_reports_a_camera_that_moved` (test lib `core-ffi` 70 hijau, workspace 261).
- Kotlin hanya memetakan: `RustEngineStatusSource` menyalakan
  `needsRecalibration = health.needsRecalibration || health.viewMoved`, banner Home
  menyebut kamera bergeser, dan `RustEngineStatusSourceTest` menambah
  `a_moved_camera_also_asks_for_a_new_map`.
- Belum diukur di perangkat: biaya sidik jari per frame (576 sel x 16 sampel + median)
  di dalam `pushFrame`; angka p50 209-239 ms yang tercatat **tidak** memuat guard ini.

Yang tidak boleh diklaim dari bukti itu:

- APK sudah berjalan (instrumented benchmark), tetapi alur UI login/Home/Scan belum diuji manual di perangkat.
- Login/Home/Scan sudah berjalan di perangkat fisik (belum diuji manual).
- Notifikasi foreground service, perilaku Doze, dan ketahanan `WAKE_LOCK` sudah teruji di perangkat; bukti yang ada hanya Robolectric/JVM.
- Total meja berasal dari model ONNX nyata: `Engine.start` + `pushFrame` **sudah** diuji dengan YOLO11n resmi di perangkat (benchmark), tetapi belum dengan kamera nyata atau frame berisi orang.
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
