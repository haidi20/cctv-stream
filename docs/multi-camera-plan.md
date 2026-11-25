# Rencana multi-kamera dan mapping per CCTV

Status: rencana + implementasi bertahap, 2026-11-26. Dokumen ini menjadi acuan
untuk menghubungkan aplikasi ke **banyak profil CCTV**, dengan **satu stream
aktif pada satu waktu**, dan peta meja yang **terpisah per kamera**.

Dokumen terkait:

- `docs/table-mapping-plan.md` - editor poligon, JSON v2, dan Room revisi.
- `docs/camera-setup.md` - komponen RTSP, larangan menyimpan password di profil.
- `docs/android-app-plan.md` - alur login -> home -> scan CCTV.
- `docs/adr/0008-normalized-coordinates.md` - koordinat 0..1.
- `docs/adr/0009-video-pipeline.md` - pipeline preview/decode yang masih terbuka.
- `docs/tdd-plan.md` - batas UI vs core dan peta test.

## 1. Tujuan dan ruang lingkup

Pengguna harus bisa:

1. Menyimpan banyak profil kamera (nama, sumber demo/RTSP, host, port, path,
   username tanpa password).
2. Melihat daftar kamera dan membuka **satu** kamera untuk dipetakan atau
   dilihat stream-nya.
3. Memetakan meja untuk kamera yang sedang dibuka; peta kamera A tidak boleh
   muncul atau tersimpan ke kamera B.
4. Berpindah kamera: sesi lama ditutup lebih dulu, lalu sesi baru dibuka.
5. Tetap memakai aplikasi saat offline; profil dan peta tersimpan di Room.

Di luar lingkup tahap ini:

- Dua stream berjalan bersamaan. Secara desain **dilarang**.
- Preview RTSP nyata dan decode H.264. Keputusan pipeline masih di ADR 0009;
  layar mapping tetap memakai bingkai demo sampai pipeline itu diterima.
- Login, Home total meja kosong/terisi, foreground service, outbox, dan server.
- Menyimpan password RTSP di Room, JSON, `SavedStateHandle`, atau log.

## 2. Keputusan utama

| Keputusan | Konsekuensi |
| --- | --- |
| Profil kamera adalah entitas Room | Kamera bisa ditambah, diubah, dan dihapus tanpa mengubah kode. |
| `camera_id` stabil dan tidak berubah saat label diedit | Revisi peta dan event engine tetap terikat ke kamera yang benar. |
| Peta meja selalu per `camera_id` | Tabel revisi yang sudah ada dipertahankan; tidak ada peta global. |
| Hanya satu sesi aktif | Membuka kamera B menghentikan engine/preview kamera A lebih dulu. |
| Daftar kamera adalah layar akar | Tidak ada bottom navigation atau drawer. |
| Mapping dibuka dari kartu kamera | Pengguna melihat konteks kamera di judul dan status. |
| Password tidak masuk profil | Kredensial hanya boleh masuk Keystore pada tahap pipeline RTSP. |
| Engine menerima `camera_id` | Health dan log dapat membuktikan engine mana yang aktif. |
| `unknown` tetap bukan `kosong` | Perpindahan kamera tidak boleh menampilkan meja kosong. |

## 3. Kontrak profil kamera (Kotlin)

`core:model` memiliki tipe berikut. Semua validasi terjadi sebelum menyentuh
Room atau FFI.

```kotlin
@JvmInline
value class CameraId(val value: String)
// 1..64 karakter, hanya A-Z a-z 0-9 . _ -
// Tidak boleh memuat scheme, '@', '/', spasi, atau password.

@JvmInline
value class CameraLabel(val value: String)
// 1..40 karakter setelah trim.

sealed interface CameraSource {
    data object Demo : CameraSource
    data class Rtsp(
        val host: String,      // tanpa scheme, tanpa '/', tanpa '@'
        val port: Int,         // 1..65535
        val path: String,      // diawali '/', tanpa scheme/'@'
        val username: String?, // tanpa ':' atau '@'; password TIDAK di sini
    ) : CameraSource
}

data class CameraProfile(
    val id: CameraId,
    val label: CameraLabel,
    val source: CameraSource,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)
```

Aturan:

- `CameraId` dibuat sekali (UUID) dan tidak diedit.
- Label boleh diedit kapan saja; peta tetap memakai id yang sama.
- `CameraSource.Rtsp` tidak memiliki field password. Menambahkannya di masa
  depan wajib lewat referensi Keystore, bukan string biasa.
- Ringkasan untuk UI/log hanya memuat label dan `host:port`; username dan path
  tidak ditulis ke log.

## 4. Invarian mapping per kamera

1. `TableMappingState.cameraId` wajib terisi sebelum `load`, `save`, atau
   `setTables`.
2. `TableMappingViewModel` tidak lagi memiliki `DEFAULT_CAMERA_ID` dan tidak
   memuat peta pada `init`. Route memanggil `bindCamera(id, label)`.
3. Mengganti kamera membuang draft dan undo/redo kamera sebelumnya.
4. `save` menulis revisi ke `camera_id` yang sedang terikat, lalu memanggil
   `TableConfigSink.apply(cameraId, json)`.
5. Sink menolak penerapan bila kamera tersebut bukan sesi aktif, atau bila
   engine yang berjalan melaporkan `camera_id` berbeda.
6. Peta kamera A tidak boleh dihapus saat kamera B dihapus. Penghapusan kamera
   hanya menghapus revisi milik id itu.

## 5. Room v2 dan migrasi

Versi database naik dari 1 ke 2. Migrasi **non-destruktif**:

```sql
CREATE TABLE IF NOT EXISTS `camera_profiles` (
  `id` TEXT NOT NULL,
  `label` TEXT NOT NULL,
  `source_type` TEXT NOT NULL,
  `host` TEXT,
  `port` INTEGER,
  `stream_path` TEXT,
  `username` TEXT,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL,
  `last_opened_at` INTEGER,
  PRIMARY KEY(`id`)
);

INSERT OR IGNORE INTO `camera_profiles`
  (`id`, `label`, `source_type`, `host`, `port`, `stream_path`,
   `username`, `created_at`, `updated_at`, `last_opened_at`)
VALUES
  ('camera-default', 'Kamera Utama', 'demo', NULL, NULL, NULL,
   NULL, 0, 0, NULL);
```

Alasan seed `camera-default`:

- Revisi lama dari MVP sebelumnya sudah memakai `camera_id = 'camera-default'`.
- Pengguna tidak kehilangan peta setelah update aplikasi.
- Label dapat diganti menjadi nama CCTV sebenarnya.

DAO kamera menyediakan:

- `observeAll()` terurut label.
- `find(id)`, `upsert`, `markOpened(id, at)`.
- `delete(id)` dalam satu transaksi yang menghapus profil **dan** revisi
  `table_config_revisions` dengan `camera_id` yang sama.

`MonitorDatabase.create` menambahkan `MIGRATION_1_2`; `fallbackToDestructiveMigration`
tetap tidak dipakai. Schema v1 dan v2 diekspor ke `android/core/database/schemas`.

## 6. Siklus sesi kamera tunggal

Kontrak di `feature:cameras`:

```kotlin
interface CameraSession {
    val activeCameraId: StateFlow<CameraId?>
    suspend fun open(cameraId: CameraId): CameraSessionResult
    suspend fun close()
}

sealed interface CameraSessionResult {
    data object Opened : CameraSessionResult
    data class Failed(val reason: String) : CameraSessionResult
}
```

Implementasi `:app` (`RustCameraSession`):

1. `open(A)` saat belum ada sesi: set `activeCameraId = A`.
2. `open(B)` saat A aktif: `Engine.stop()` untuk A lebih dulu, baru set
   `activeCameraId = B`. Jika stop gagal, A tetap aktif dan B ditolak.
3. `open(A)` saat A aktif: idempoten, tidak menghentikan engine.
4. `close()`: `Engine.stop()` best-effort lalu `activeCameraId = null`.
5. Operasi engine berjalan di `Dispatchers.IO`; satu `Mutex` menutup
   `open`, `close`, dan `applyTables` sehingga peta tidak bisa ditulis saat
   sesi berganti kamera.
6. `applyTables(cameraId, json)` menolak `WrongCamera` bila id bukan sesi
   aktif, lalu memeriksa `EngineHealth.cameraId` sebelum `setTables`.
7. Shell `CameraShell` memakai `Mutex` yang sama untuk `open`/`close` dari UI, dan membatalkan efek open bila `camera_id` berubah saat menunggu lock. Dengan begitu tombol back tidak bisa menutup kamera yang baru saja dibuka, dan open yang dibatalkan tidak menelan `CancellationException`.
8. Belum ada `Engine.start` di sini: sumber frame dan model belum tersedia.
   Saat pipeline ADR 0009 diterima, `open` juga memulai engine dengan
   `EngineConfig.cameraId` milik kamera tersebut.

## 7. Perubahan kontrak Rust

`EngineConfig` dan `EngineHealth` mendapat `camera_id: String`.

- `EngineConfig.camera_id` divalidasi sebelum model dimuat:
  - tidak kosong setelah trim;
  - maksimal 64 karakter;
  - hanya `A-Z a-z 0-9 . _ -`.
- ID yang memuat `://`, `@`, `/`, spasi, atau karakter kontrol ditolak dengan
  `EngineError::Config` yang **tidak** menggemakan nilai mentah.
- `EngineHealth.camera_id` menggemakan ID sesi terakhir yang berhasil dimulai.
  Sebelum `start` atau setelah start gagal, nilainya string kosong.
- Sesi memiliki **generasi** internal (`AtomicU64`) yang naik pada `start` dan
  `stop`. Frame yang mulai di sesi lama tidak boleh menulis status, tabel,
  atau event ke sesi baru; `push_frame`, `set_tables`, dan
  `report_stream_error` memeriksa generasi di bawah lock state. Publikasi
  event diserialkan (`emit`) agar urutan `idle -> connected` mustahil.
- `EngineEvent` tidak berubah: satu engine hanya melayani satu kamera. Klien
  yang berpindah kamera wajib mengganti engine/sesi, bukan mencampur event.
- `set_tables` tetap hanya mengganti konfigurasi engine yang sedang dipegang;
  ia tidak menerima `camera_id` tambahan. Pemeriksaan kepemilikan dilakukan di
  Kotlin melalui sesi aktif dan `EngineHealth.camera_id`.

## 8. Alur UI

```text
Daftar Kamera
  [Tambah Kamera] -> dialog label + sumber (Demo/RTSP)
  kartu kamera:
    [Buka peta] -> CameraSession.open(id) -> Peta Meja (id, label)
    [Edit]      -> dialog
    [Hapus]     -> konfirmasi (peta ikut terhapus)

Peta Meja (satu kamera)
  <  Peta Meja
     Warung Depan · 2/3 meja siap
  bingkai kamera / demo
  daftar meja, simpan -> Room(camera_id) -> engine(camera_id)
  <  kembali -> CameraSession.close() -> Daftar Kamera
```

Wireframe portrait (lebar < 600dp):

```text
+--------------------------------------------------+
| Kamera                                  [+ Tambah]|
+--------------------------------------------------+
| +----------------------------------------------+ |
| | Warung Depan                    [Edit] [Hapus]| |
| | Demo                                          | |
| | [ Buka peta ]                                 | |
| +----------------------------------------------+ |
| +----------------------------------------------+ |
| | Warung Belakang                 [Edit] [Hapus]| |
| | RTSP - 192.168.1.64:554                       | |
| | [ Buka peta ]                                 | |
| +----------------------------------------------+ |
+--------------------------------------------------+
```

Wireframe landscape/tablet (lebar >= 600dp):

```text
+--------------------------------------------------------------------+
| Kamera                                                    [+ Tambah]|
+--------------------------------------------------------------------+
| +--------------------------+  +--------------------------+         |
| | Warung Depan             |  | Warung Belakang          |         |
| | Demo                     |  | RTSP - 192.168.1.64:554  |         |
| | [ Buka peta ]            |  | [ Buka peta ]            |         |
| +--------------------------+  +--------------------------+         |
+--------------------------------------------------------------------+
```

Aturan layout:

- Lebar, bukan orientasi, menentukan jumlah kolom
  (`LazyVerticalGrid` adaptif, minimum 320dp).
- Tidak ada bottom navigation/drawer.
- Dialog editor tetap bisa dipakai di portrait sempit; kontennya scrollable.
- Status `unknown`/tidak terpantau tidak boleh ditampilkan sebagai 0 meja kosong
  di layar kamera mana pun.

## 9. Keamanan

- Jangan menyimpan password, token, atau URL RTSP lengkap di Room.
- Jangan menulis host lengkap ke log; gunakan `label` dan `host:port` hanya di
  UI. Log error memakai `Redaction.message`.
- `CameraId` hanya menerima karakter aman; ini mencegah ID berisi
  `rtsp://user:pass@host`.
- Validasi Rust mengulang aturan tersebut karena binding dapat dipanggil dari
  luar UI.
- Permission `INTERNET` belum ditambahkan. Permission itu baru diperlukan saat
  preview RTSP nyata; saat itu manifest dan `android-permissions-security`
  ditinjau ulang.
- Hapus kamera juga menghapus revisi peta miliknya; tidak ada data yatim yang
  bisa muncul kembali.

## 10. Matriks test

| Lapisan | Test | Cakupan |
| --- | --- | --- |
| Rust | `core-ffi` unit | ID kosong/terlalu panjang/berbahaya ditolak; health menggemakan ID |
| Rust | `onnx_engine` | config nyata memuat `camera_id`; health cocok |
| Kotlin | `core:model` | validasi `CameraId`, `CameraLabel`, host/port/path/username |
| Kotlin | `core:database` | DAO CRUD, urutan, markOpened, hapus revisi terkait, migrasi 1->2 |
| Kotlin | `feature:cameras` | repository in-memory, ViewModel create/edit/delete/open, Compose list/dialog |
| Kotlin | `feature:tables` | bind per kamera, isolasi peta A/B, save memakai camera_id, sink menerima camera_id |
| Build | Gradle | unit test, `assembleDebug`, `lintDebug` |

Bukti wajib menyebut perintah dan jumlah test; test perangkat tetap terpisah.

## 11. Urutan implementasi

1. Dokumen ini dan tautan dari dokumen terkait.
2. Rust: `camera_id` pada config/health, validasi, test, binding ulang.
3. Kotlin `core:model`: `CameraId`, `CameraLabel`, `CameraSource`,
   `CameraProfile`, test validasi.
4. Kotlin `core:database`: entity/DAO v2, migrasi, test DAO + migrasi.
5. `feature:cameras`: repository, sesi, ViewModel, daftar kamera, dialog.
6. `feature:tables`: hapus kamera default, `bindCamera`, sink per kamera,
   perbarui test.
7. `:app`: wiring Hilt, `RustCameraSession`, `RustTableConfigSink`, shell
   daftar <-> mapping.
8. Gate Rust dan Gradle; perbarui `docs/status.md` dan tabel dokumen.

## 12. Kriteria penerimaan

- [x] Kamera dapat ditambah, diedit, dihapus, dan tetap ada setelah restart.
- [x] Dua kamera memiliki peta berbeda; menyimpan peta A tidak mengubah peta B
      (`maps_are_isolated_per_camera`).
- [x] Membuka kamera B menutup sesi A; tidak ada dua sesi aktif
      (`RustCameraSession.open` memanggil `stop()` lebih dulu).
- [x] Peta kamera lama (`camera-default`) tetap ada setelah migrasi v1 -> v2
      (`MonitorDatabaseMigrationTest`).
- [x] `EngineHealth.camera_id` cocok dengan kamera sesi aktif
      (`health_echoes_the_camera_id_of_the_active_session`, `onnx_engine`).
- [x] ID kamera berisi URL/kredensial ditolak di Kotlin dan Rust.
- [x] Semua test host, `assembleDebug`, dan `lintDebug` hijau (2026-11-26).
- [ ] Verifikasi perangkat dan preview RTSP nyata dicatat terpisah, bukan
      diklaim dari test host.

## 13. Risiko dan batas

| Risiko | Mitigasi |
| --- | --- |
| Preview RTSP belum ada | Profil dan pemilihan kamera tetap berguna; pipeline mengikuti ADR 0009. |
| Pengguna mengira dua kamera bisa live bersamaan | UI hanya punya satu tombol buka dan sesi menolak paralel. |
| Password bocor ke Room/log | Tidak ada field password; validasi ID dan redaksi terpusat. |
| Peta lama hilang saat migrasi | Migrasi non-destruktif + seed `camera-default` + test migrasi. |
| Engine berhenti gagal saat ganti kamera | `open` gagal dan sesi lama dipertahankan; UI menampilkan pesan. |
| Proses mati saat mapping | Peta tersimpan di Room; route dipulihkan dengan `camera_id` yang sama. |
| Kamera dihapus saat sesi aktif | UI menutup sesi lebih dulu, lalu menghapus profil dan revisinya. |

## 14. Skill yang dipakai

- `rust-skills`, `rust-best-practices`, `rust-async-patterns` - validasi FFI,
  error, state engine.
- `android-jni-security` - batas UniFFI, panic boundary, larangan kredensial.
- `using-chrisbanes-skills` - router Kotlin/Compose.
- `kotlin-api-design` - `CameraId`, `CameraLabel`, seam `CameraSession`.
- `kotlin-concurrency-and-flow` - `StateFlow`, `Mutex`, `viewModelScope`.
- `kotlin-control-flow` - `when` exhaustif atas sumber kamera dan hasil sesi.
- `compose-state-and-effects` - state daftar, dialog, snackbar, `LaunchedEffect`.
- `compose-component-design` - screen stateless + callback.
- `compose-ui-testing-patterns` - test daftar kamera dan dialog.
- `compose-performance` - daftar stabil, key per `camera_id`.
- `adaptive`, `edge-to-edge` - grid berdasarkan lebar, inset Scaffold.
- `navigation-3` - acuan shell; Nav3 tetap ditunda seperti catatan mapping.
- `testing-setup` - test JVM/Robolectric.
- `gradle-run`, `android-cli` - gate build dan inspeksi perangkat.

## 15. Verifikasi implementasi 2026-11-26

Yang sudah dijalankan dan lulus pada tanggal tersebut:

- Rust: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets
  -- -D warnings`, `cargo test --workspace` (209 test), clippy/test
  `stream-engine --features rtsp`, clippy `core-ffi --features cli`,
  `cargo ndk` untuk tiga ABI, `cargo audit`, dan `cargo deny check`.
- Gradle lewat wrapper `gradle-run`: `:core:model:test` (36),
  `:core:database:testDebugUnitTest` (11), `:feature:cameras:testDebugUnitTest`
  (23), `:feature:tables:testDebugUnitTest` (51), `:app:assembleDebug`,
  dan `:app:lintDebug` (0 error, 21 warning versi dependensi).
- Binding Kotlin UniFFI diregenerasi; `EngineConfig.cameraId` dan
  `EngineHealth.cameraId` tersedia untuk `:app`.

Belum diverifikasi: perangkat fisik, preview RTSP nyata, dan akurasi model.

## 16. Keputusan yang ditunda

- Nav3 belum dipasang; shell memakai `rememberSaveable` untuk `camera_id` dan
  label. Saat Nav3 masuk, scope ViewModel mengikuti `NavEntry` kamera.
- `Engine.start` sekarang dipanggil best-effort saat kamera RTSP dibuka dan punya
  peta + model/runtime. Decoder Rust penuh (ADR 0009) tetap belum ada; frame datang
  dari preview Media3 lewat `EngineFramePump`.
- Penyimpanan password RTSP menunggu pipeline nyata dan implementasi Keystore.
- Status meja per kamera di Home sudah membaca `EngineHealth` yang memuat
  `camera_id`; verifikasi dengan model ONNX nyata di perangkat masih tertunda.
