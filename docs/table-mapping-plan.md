# Rencana mapping meja manual (Kotlin)

Status: implementasi dan gate host lulus, 2026-11-26. `core:model`,
`feature:tables` (editor, repository Room, sink engine, dan UI mapping), manifest,
dan `MainActivity` sudah ada. `:app:assembleDebug` menghasilkan APK dan
`:app:lintDebug` nol error. Integrasi preview RTSP dan verifikasi perangkat belum
ada.

Dokumen ini adalah acuan untuk fitur **editor mapping meja manual** di aplikasi
Android. Fitur ini menghasilkan konfigurasi poligon meja untuk `core-ffi`; fitur ini
**tidak** menghitung status meja. Status `available`/`occupied`/`unknown` tetap
sepenuhnya milik Rust (`docs/tdd-plan.md` §2).

Dokumen terkait:

- `docs/adr/0008-normalized-coordinates.md` - koordinat 0..1.
- `docs/data-contracts.md` §1 - kontrak konfigurasi meja.
- `docs/android-app-plan.md` §6.1, §8, §13.6 - modul, layout adaptif, fase K6.
- `docs/tdd-plan.md` §3.2.2, §10.5 - rumus transform dan test zoom.
- `docs/android-sync.md` §4 - Room dan outbox.
- `docs/multi-camera-plan.md` - profil banyak kamera, satu sesi aktif, peta per `camera_id`.

## 1. Tujuan dan ruang lingkup

Pengguna harus bisa memetakan meja tanpa menyentuh JSON atau koordinat piksel:

1. Melihat bingkai kamera (atau bingkai demo saat kamera belum terpasang).
2. Menambah meja, menggambar poligon minimal tiga titik, menggeser titik, dan
   menghapus titik.
3. Memberi label yang mudah dibaca (mis. Meja 01).
4. Menyimpan konfigurasi secara lokal, lalu mengirimkannya ke engine Rust melalui
   `setTables`.
5. Mendapat peringatan bila rasio video berubah sehingga poligon lama tidak lagi
   mewakili posisi meja.

Di luar lingkup dokumen ini: login, home, scan CCTV, RTSP player, status meja,
outbox server, dan foreground service. Layar mapping tidak menampilkan total meja
kosong/terisi; angka itu milik Home dan berasal dari `EngineHealth`/event Rust.

## 2. Keputusan yang diwarisi

| Keputusan | Konsekuensi |
| --- | --- |
| ADR 0008: koordinat ternormalisasi 0..1 | Tidak ada penyimpanan piksel. Resolusi video boleh berubah selama rasio sama. |
| JSON `version: 2` | Versi 1 (piksel) ditolak dengan pesan migrasi manual. |
| Poligon divalidasi di Rust | Kotlin memvalidasi dengan aturan yang sama sebelum menyimpan dan sebelum memanggil `setTables`. |
| UI tidak menghitung status | Editor hanya menghasilkan `TableConfigSet` dan JSON. |
| Offline-first | Konfigurasi selalu disimpan ke Room lebih dulu; engine dan VPS opsional. |
| Tanpa bottom navigation/drawer | Mapping adalah layar penuh dengan app bar dan tombol kembali. |
| Layout berdasarkan lebar | Portrait dan landscape memakai aturan yang sama; orientasi tidak dibaca langsung. |
| `unknown` bukan `kosong` | Editor tidak boleh menampilkan meja yang belum pernah dilihat sebagai kosong. |

## 3. Alur pengguna

```text
Home -> menu Kelola meja -> Editor mapping
Editor:
  [Tambah Meja] -> ketuk bingkai (>= 3 titik) -> [Selesai]
  pilih meja -> ubah label -> seret titik -> [Simpan]
  [Simpan] -> validasi lokal -> Room -> setTables (Rust)
```

Perilaku penting:

- **Tambah meja** memilih `id` stabil berikutnya (`meja-01`, `meja-02`, ...) dan
  langsung masuk mode gambar. `id` tidak berubah saat label diedit.
- Ketuk bingkai menambah titik pada meja yang sedang digambar. Seret titik untuk
  memindahkan. Ketuk titik untuk memilih; long-press titik menghapusnya (bisa
  diurungkan).
- Cubit (pinch) untuk zoom 1..8x dan dua jari untuk pan. Zoom hanya mengubah
  tampilan; koordinat tersimpan tetap 0..1.
- Tombol **Selesai** memvalidasi meja yang sedang digambar. Jika belum valid,
  pesan menyebut masalahnya dan titik tidak dibuang.
- **Simpan** memvalidasi semua meja, menulis revisi baru ke Room, lalu memanggil
  engine. Kegagalan engine tidak menghapus konfigurasi lokal.
- **Urungkan/Ulangi** berlaku untuk tambah/hapus meja, tambah/geser/hapus titik,
  dan reset peta.

### 3.1 Wireframe portrait (lebar < 600dp)

```text
+--------------------------------------------------+
| <  Peta Meja                          [ Simpan ] |
|    2/3 meja siap                                  |
+--------------------------------------------------+
|            BINGKAI KAMERA / DEMO                 |
|        +------------------------------+          |
|        |      poligon meja            |          |
|        |   o------o                   |          |
|        |   |      |       [Fit] [2x]  |          |
|        |   o------o                   |          |
|        +------------------------------+          |
|  Ketuk bingkai untuk menambah titik              |
+--------------------------------------------------+
| [Tambah Meja] [Selesai] [Batal]                  |
| Meja 01  4 titik  siap        (dipilih)          |
|   Label: [Meja 01......................]         |
|   [Hapus Meja]                                   |
| Meja 02  2 titik  belum valid                    |
| [Urungkan] [Ulangi]                              |
+--------------------------------------------------+
```

### 3.2 Wireframe landscape / tablet (lebar >= 600dp)

```text
+----------------------------------------------------------------------+
| <  Peta Meja                                             [ Simpan ]  |
+-----------------------------------------+----------------------------+
|          BINGKAI KAMERA / DEMO          | [Tambah Meja] [Selesai]    |
|       o--------o                        | Meja 01  4 titik  siap     |
|       |  meja  |        [Fit] [2x]      |   Label: [Meja 01.......]  |
|       o--------o                        |   [Hapus Meja]             |
|                                         | Meja 02  2 titik  belum    |
|                                         | [Urungkan] [Ulangi]        |
+-----------------------------------------+----------------------------+
```

Panel kanan maksimum 420dp; preview mengambil sisa lebar. Di Compact, panel
diletakkan di bawah preview dan tetap bisa digulir. Tidak ada bottom bar.
## 4. Model data

Semua tipe di `core/model` bebas `android.*` dan bisa diuji sebagai JVM unit test.

```kotlin
data class NormalizedPoint(val x: Double, val y: Double) // finite, 0..1

data class MappingFrame(
    val width: Int,
    val height: Int,
    val aspectRatio: String, // 16:9
)

@JvmInline value class TableId(val value: String)      // stabil, unik
@JvmInline value class TableLabel(val value: String)   // 1..40 karakter

class TablePolygon private constructor(val points: List<NormalizedPoint>) {
    val area: Double
    val centroid: NormalizedPoint
}

data class TableConfig(
    val id: TableId,
    val label: TableLabel,
    val polygon: TablePolygon,
)

data class TableConfigSet(
    val frame: MappingFrame,
    val tables: List<TableConfig>,
)
```

`TablePolygon` hanya bisa dibuat lewat validator; draft yang belum valid disimpan
sebagai `TableDraft` (daftar titik + label) di layer fitur. Ini mencegah poligon
tidak valid masuk ke kontrak hanya karena UI belum selesai menggambar.

Aturan `TableId`:

- Dibuat otomatis dari angka terkecil yang belum dipakai (`meja-01`, `meja-02`).
- Tidak pernah berubah saat label diedit.
- Tidak dipakai ulang setelah meja dihapus dalam sesi yang sama, kecuali pengguna
  mengosongkan seluruh peta dan membuat ulang.

## 5. Kontrak JSON v2

Format yang dikirim ke `Engine.setTables` sama persis dengan
`contracts/fixtures/valid/meja-mock.json`:

```json
{
  "version": 2,
  "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
  "tables": [
    {
      "id": "meja-01",
      "label": "Meja 01",
      "polygon": [
        { "x": 0.1, "y": 0.1 },
        { "x": 0.3, "y": 0.1 },
        { "x": 0.3, "y": 0.3 },
        { "x": 0.1, "y": 0.3 }
      ]
    }
  ]
}
```

### 5.1 Validasi yang harus sama dengan Rust

| Aturan | Rust | Kotlin |
| --- | --- | --- |
| Minimal 3 titik | `ConfigError::TooFewPoints` | `PolygonProblem.TooFewPoints` |
| Koordinat finite dan 0..1 | `CoordinateOutOfRange` | decode menolak |
| Luas absolut >= 0.0005 | `MIN_POLYGON_AREA` | `PolygonValidator.MIN_POLYGON_AREA` |
| Tidak self-intersect | toleransi `1e-6` | port algoritma yang sama |
| `id` unik dan tidak kosong | `DuplicateId` / `EmptyId` | decode + `TableConfigSet` |
| Titik di tepi dianggap di dalam | `EDGE_TOLERANCE = 1e-6` | `PolygonValidator.contains` |
| Label opsional | fallback ke `id` | `TableLabel` fallback saat decode |
| Versi 1 ditolak | `LegacyPixelVersion` | pesan migrasi diperlukan |

Urutan validasi Rust dipertahankan: jumlah titik, koordinat, luas, lalu
self-intersection. Pesan Kotlin tidak harus identik kata per kata, tetapi
**keputusan valid/tidak valid harus identik**.

### 5.2 Pembulatan

- Koordinat dibulatkan ke maksimal 4 desimal **sebelum** validasi dan penyimpanan.
- Pembulatan memakai `BigDecimal.setScale(4, RoundingMode.HALF_UP)`.
- Alasannya: Rust memvalidasi angka yang sudah tertulis di JSON. Kalau Kotlin
  memvalidasi angka mentah lalu menulis 4 desimal, area bisa turun di bawah
  `0.0005` dan baru ditolak Rust.
- Zoom/pan tidak pernah mengubah koordinat sampai jari diangkat.
## 6. Transformasi layar <-> frame

`MappingTransform` adalah fungsi murni di `feature/tables/domain`. Rumus mengikuti
`docs/tdd-plan.md` §3.2.2:

```text
base      = min(viewport_w / frame_w, viewport_h / frame_h)
content_x = (viewport_w - frame_w * base) / 2
content_y = (viewport_h - frame_h * base) / 2

screen_x = content_x + pan_x + zoom * base * frame_w * norm_x
screen_y = content_y + pan_y + zoom * base * frame_h * norm_y

norm_x = ((screen_x - content_x - pan_x) / (zoom * base * frame_w)).coerceIn(0, 1)
norm_y = ((screen_y - content_y - pan_y) / (zoom * base * frame_h)).coerceIn(0, 1)
```

Aturan pan:

- `zoom` 1..8, di atas fit.
- `pan` dalam piksel layar, bukan piksel frame.
- Pada sumbu yang gambar masih lebih kecil dari viewport, gambar dipusatkan.
- Pada sumbu yang gambar lebih besar, pan dibatasi agar tidak ada area kosong di
  tepi: `pan in [viewport - content - scaled, -content]`.
- Di zoom 1, pan selalu 0.
- Titik di area letterbox di-clamp ke tepi frame, bukan dibuang.
- Zoom/pan tidak disimpan ke konfigurasi dan tidak memanggil Rust.

Contoh acuan (wajib lulus test):

| Kasus | Nilai |
| --- | --- |
| Frame | `1280x720`, rasio `16:9` |
| Viewport | `1080x608` -> `base = 0.84375`, `content = (0, 0.25)` |
| Titik tengah | `(0.5, 0.5)`, zoom 1 -> layar `(540, 304)` |
| Round-trip zoom | zoom 1/3/8 dengan pan yang menahan tengah, toleransi `1e-4` |
| Sentuhan letterbox | `(-50, 2000)` -> `(0.0, 1.0)` |
| Pan berlebih | `panBy` besar -> clamp ke tepi gambar |

Test yang wajib ada: `identity_mapping_at_zoom_1x`, `round_trip_keeps_point`,
`touch_in_letterbox_is_clamped`, `pan_is_clamped_to_image_bounds`,
`zoom_around_center_keeps_center`.

## 7. Gestur dan state editor

| Gestur | Mode pilih | Mode gambar |
| --- | --- | --- |
| Ketuk titik | Pilih titik | Pilih titik |
| Ketuk meja | Pilih meja | Pilih meja lain; ketuk meja sendiri = tambah titik |
| Ketuk area kosong | Hapus pilihan | Tambah titik ke meja aktif |
| Seret titik | Geser titik (target 48dp) | Geser titik |
| Seret area kosong | Pan tampilan | Tidak pan (hindari titik tak sengaja) |
| Cubit / dua jari | Zoom + pan | Zoom + pan |
| Long-press titik | Hapus titik | Hapus titik |

State yang dimiliki `TableMappingViewModel`:

- `frame` (bingkai live/demo), `configuredFrame` (bingkai saat terakhir disimpan).
- `tables: List<TableDraft>`, `selectedTableId`, `selectedVertex`,
  `drawingTableId`.
- `issues`, `isSaving`, `needsRecalibration`, `engineState`, `lastSavedRevision`.
- Riwayat undo/redo maksimum 50 snapshot. Snapshot berisi daftar meja, pilihan,
  dan meja yang sedang digambar; zoom/pan tidak termasuk.

Zoom/pan adalah state UI murni di composable, sehingga rotasi mengembalikan tampilan
ke fit tanpa mengubah data meja. Data meja tetap di ViewModel dan selamat dari
re-create activity.
## 8. Penyimpanan Room

Database `monitor.db` versi 1, dimiliki `core/database`:

```sql
CREATE TABLE table_config_revisions (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  camera_id     TEXT NOT NULL,
  revision      INTEGER NOT NULL,
  config_json   TEXT NOT NULL,
  frame_width   INTEGER NOT NULL,
  frame_height  INTEGER NOT NULL,
  aspect_ratio  TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_table_config_revisions_camera_id_revision
  ON table_config_revisions (camera_id, revision);
```

Aturan:

- Revisi baru selalu `max(revision) + 1` per kamera, di dalam satu transaksi.
- Revisi terbaru = konfigurasi aktif. Tidak ada flag `is_active` yang bisa
  menggantung.
- Riwayat disimpan maksimum 20 revisi per kamera; revisi lama dipangkas setelah
  insert.
- `config_json` adalah sumber kebenaran; kolom frame/rasio hanya untuk query dan
  diagnostik.
- Belum ada migrasi karena versi 1 adalah skema awal. Perubahan skema berikutnya
  wajib menaikkan versi dan menyediakan `Migration`; `fallbackToDestructiveMigration`
  tidak dipakai.

Repository:

```kotlin
interface TableConfigRepository {
    suspend fun active(cameraId: String): StoredTableConfig?
    fun observeActive(cameraId: String): Flow<StoredTableConfig?>
    suspend fun save(cameraId: String, config: TableConfigSet): StoredTableConfig
}
```

`InMemoryTableConfigRepository` dipakai di test dan preview.
`RoomTableConfigRepository` memakai transaksi Room dan DAO revisi.

## 9. Integrasi engine

Kontrak aplikasi:

```kotlin
fun interface TableConfigSink {
    suspend fun apply(configJson: String): TableApplyResult
}

sealed interface TableApplyResult {
    data object Applied : TableApplyResult
    data class Rejected(val reason: String) : TableApplyResult
    data class Unavailable(val reason: String) : TableApplyResult
}
```

Implementasi `RustTableConfigSink` memanggil binding UniFFI:

```text
Engine.setTables(tablesJson: String)
```

Detail yang harus dijaga:

1. **Simpan lokal dulu.** Room adalah sumber kebenaran offline. Engine yang mati
   atau belum punya model tidak boleh menghilangkan peta.
2. **Validasi lokal dulu.** JSON hanya dikirim setelah validator Kotlin lolos.
   Rust tetap memvalidasi ulang; penolakan Rust ditampilkan apa adanya (diredaksi).
3. **Jangan kirim kredensial.** JSON meja tidak berisi URL, password, atau token.
4. **Degradasi aman.** Bila `libcore_ffi.so`/binding belum ada, sink mengembalikan
   `Unavailable`, bukan crash. UI menyediakan Coba lagi dan tetap menampilkan peta
   yang tersimpan.
5. **Jangan hapus konfigurasi lama saat engine menolak.** Revisi lama tetap ada;
   revisi baru yang gagal diterapkan ditandai lewat `engineState`, bukan dihapus.
6. **Redaksi.** Pesan exception engine dibersihkan lewat `Redaction` sebelum
   ditampilkan; JSON dan kredensial tidak pernah masuk log.

`setTables` mengubah semua status meja menjadi `unknown` sampai frame berikutnya
memutuskan ulang. Karena itu editor tidak menampilkan angka kosong/terisi.

## 10. Rekalibrasi saat rasio berubah

- Konfigurasi menyimpan `video.aspect_ratio` dan dimensi acuan.
- Saat bingkai live diketahui, bandingkan rasio dengan cross-multiplication
  (`num * height == den * width`), bukan toleransi `Float`.
- Bila berbeda, `needsRecalibration = true` dan UI menampilkan banner:
  Peta disimpan untuk 16:9, video sekarang 4:3. Poligon tidak digeser otomatis.
- Aksi yang tersedia: **Mulai ulang peta** (hapus semua poligon, lalu gambar ulang)
  atau abaikan untuk sementara. Tidak ada konversi otomatis, karena crop/letterbox
  kamera tidak bisa ditebak dari rasio saja.
- Menyimpan peta baru memakai rasio live dan menghapus flag setelah sukses.
## 11. Layout adaptif dan aksesibilitas

- Lebar < 600dp: preview di atas, panel kontrol di bawah, keduanya dapat digulir.
- Lebar >= 600dp: preview kiri, panel kanan maksimum 420dp.
- Tidak memakai `Configuration.orientation`; `BoxWithConstraints` membaca lebar
  aktual sehingga split-screen dan foldable ikut benar.
- Edge-to-edge: `enableEdgeToEdge()`, `Scaffold` menangani inset, panel memakai
  `imePadding()` agar label tidak tertutup keyboard.
- Target sentuh minimal 48dp: tombol Material3 dan radius hit-test titik 24dp.
- Semua `IconButton` punya `contentDescription`; status valid memakai ikon + teks,
  bukan warna saja.
- Setiap meja juga bisa dipilih lewat daftar (bukan hanya kanvas), sehingga
  pengguna TalkBack tidak bergantung pada gestur.
- Teks error menyebut label meja, bukan hanya tidak valid.

## 12. Struktur file target dan yang sudah ada

```text
android/
  core/model/                         # kontrak + geometri + JSON, JVM murni
    NormalizedPoint.kt
    MappingFrame.kt
    TableConfig.kt
    PolygonValidator.kt
    TableConfigJson.kt
    Redaction.kt
  core/database/                      # Room v1 untuk revisi konfigurasi meja
    build.gradle.kts
  feature/tables/                     # source domain/UI/data + 47 test
    build.gradle.kts
    domain/MappingTransform.kt        # ada
    domain/TableDraft.kt              # ada
    domain/PolygonEditor.kt           # ada
    data/RoomTableConfigRepository.kt # ada
    data/InMemoryTableConfigRepository.kt # ada
    engine/TableConfigSink.kt         # ada; implementasi Rust ada di app
    ui/TableMappingScreen.kt          # ada
  app/
    build.gradle.kts                  # ada
    src/main/kotlin/MainActivity.kt   # ada
    src/main/AndroidManifest.xml       # ada
    src/main/jniLibs/...              # gitignored, hasil build Rust
    build/generated/uniffi/kotlin/    # gitignored, hasil uniffi-bindgen
```

Generated UniFFI binding tidak di-commit. Sink engine akan melaporkan `Unavailable`
sampai binding dan `.so` dihasilkan oleh `tools/android-build/build-rust.ps1`. Build
APK saat ini masih terblokir oleh binding Hilt, bukan oleh UniFFI.

## 13. Matriks test

| Level | File | Status | Perilaku yang dibuktikan |
| --- | --- | --- | --- |
| JVM | `PolygonValidatorTest` | Ada, lulus | <3 titik, area kecil, self-intersect, titik tepi, centroid |
| JVM | `TableConfigJsonTest` | Ada, lulus | decode fixture kanonik, round-trip 4 desimal, versi 1/3 ditolak, id duplikat, label fallback |
| JVM | `MappingFrameTest` | Ada, lulus | reduksi rasio, cross-multiplication, `needsRecalibration` |
| JVM | `NormalizedPointTest` | Ada, lulus | range 0..1, kuantisasi 4 desimal, clamp |
| JVM | `MappingTransformTest` | Ada, lulus (8 test) | rumus letterbox, round-trip zoom, clamp pan, clamp letterbox |
| JVM | `PolygonEditorTest` | Ada, lulus (9 test) | tambah/geser/hapus titik, kuantisasi, id berikutnya |
| JVM | `TableMappingViewModelTest` | Ada, lulus (13 test) | load, tambah meja, undo/redo, simpan lokal lalu engine, engine gagal, rekalkulasi |
| JVM | `InMemoryTableConfigRepositoryTest` | Ada, lulus (4 test) | revisi bertambah, 20 revisi terakhir, konfigurasi aktif |
| Compose | `TableMappingScreenTest` | Ada, lulus (10 test, Robolectric) | tombol Simpan memanggil event, banner recalibration, status valid/invalid, label, layout lebar |
| Perangkat | manual | Belum dijalankan | gambar di atas frame CCTV nyata, rotasi, pinch/pan, TalkBack |

Test JVM tidak boleh memakai `android.*`; `core:model` dan `feature:tables/domain`
diuji di host. Test Compose memakai state statis dan callback, sesuai
`compose-ui-testing-patterns`.

Status bukti 2026-11-26:

- Gate gabungan lewat wrapper `gradle-run` lulus: `:core:model:test`,
  `:core:database:testDebugUnitTest`, `:feature:tables:testDebugUnitTest`,
  `:app:testDebugUnitTest`, `:app:assembleDebug`, dan `:app:lintDebug`.
- 81 test hijau (0 gagal): `core:model` 29, `core:database` 5,
  `feature:tables` 47 (termasuk 10 test Compose `TableMappingScreenTest`).
- `:app:assembleDebug` menghasilkan `android/app/build/outputs/apk/debug/app-debug.apk`
  (32.3 MB).
- `:app:lintDebug` nol error; 21 warning sisa semuanya versi dependensi
  (`NewerVersionAvailable`, `GradleDependency`, `AndroidGradlePluginVersion`)
  yang sengaja dipin agar tetap kompatibel `compileSdk 36`/AGP 8.13.2.

## 14. Kriteria penerimaan

Fitur dianggap siap untuk digabung bila:

- [x] `:core:model:test` hijau dan fixture kanonik terbaca.
- [x] `:feature:tables:testDebugUnitTest` hijau untuk repository, transform, editor,
      draft, dan ViewModel (47 test, termasuk 10 test Compose).
- [x] `:app:assembleDebug` menghasilkan APK.
- [x] `:app:lintDebug` tidak menemukan error.
- [ ] Di perangkat: gambar 4 meja di frame 16:9, simpan, lalu engine menerima
      `setTables` dan status awal `unknown`.
- [ ] Di perangkat: rotasi portrait/landscape tidak kehilangan titik atau label.
- [ ] Di perangkat: kamera 4:3 memunculkan banner recalibration dan tidak
      menggeser poligon lama.
- [ ] TalkBack dapat memilih meja dari daftar dan mengedit label.

Item yang belum dicentang tidak boleh diklaim lulus. Bukti dan residual risk
diperbarui di `docs/status.md`.

## 15. Risiko dan batas

| Risiko | Mitigasi |
| --- | --- |
| Preview masih demo, bukan frame CCTV | API kanvas menerima `ImageBitmap`; integrasi frame dari player/service adalah langkah berikutnya. |
| Binding UniFFI/JNA gagal dimuat | Sink menangkap `Throwable`, status `Unavailable`, peta tetap tersimpan. |
| Model ONNX belum ada | `setTables` tetap bisa dipanggil saat engine dibuat; status meja `unknown` sampai model siap. |
| Zoom/pan reset saat rotasi | Disengaja; data meja tidak hilang. |
| Long-press menghapus titik | Selalu bisa diurungkan; target sentuh 48dp. |
| Dua kamera memakai DB yang sama | `camera_id` sudah ada di skema; pemilihan kamera aktif masih konstanta sampai profil kamera dibuat. |
| Rasio berubah karena crop | Tidak dikonversi otomatis; banner meminta kalibrasi ulang. |
| Database fitur bercampur dengan DB utama nanti | `core/database` adalah satu-satunya pemilik Room; modul lain tidak membuka DB sendiri. |

## 16. Skill yang dipakai

- `using-chrisbanes-skills` - router pekerjaan Kotlin/Compose.
- `compose-state-and-effects` - state ViewModel, `StateFlow`, `LaunchedEffect`, snackbar.
- `compose-component-design` - `modifier`, API stateless, slot panel.
- `compose-performance` - state zoom dibaca di fase draw; gesture tidak restart.
- `compose-ui-testing-patterns` - bentuk test UI state + callback (rencana).
- `kotlin-api-design` - value class, seam `TableConfigSink`, modul murni.
- `kotlin-control-flow` - `when` exhaustif atas sealed state.
- `kotlin-concurrency-and-flow` - `viewModelScope`, `Channel`, `StateFlow`, dispatcher.
- `adaptive` - layout berdasarkan lebar, bukan orientasi.
- `edge-to-edge` - inset Scaffold dan IME.
- `navigation-3` - acuan route `tables`; build ini belum memakai Nav3 (lihat 17).
- `testing-setup` - matrix unit test JVM.
- `gradle-run` - seluruh eksekusi Gradle lewat wrapper skill.
- `android-cli` - inspeksi perangkat/SDK (tidak ada perangkat saat implementasi).
- `android-permissions-security`, `android-intent-security` - manifest minimal, activity exported, tanpa permission berlebih.

## 17. Keputusan sementara yang perlu ditinjau

- **`MainActivity` sudah ada, Nav3 belum dipasang.** Mapping saat ini dibuka tanpa
  Nav3; saat route `tables` dipasang, gunakan `NavDisplay` dan scope ViewModel per
  entry, bukan per activity. Ini bukan keputusan arsitektur final.
- **minSdk 29**, bukan 26 seperti `android-app-plan.md` §11, mengikuti
  `docs/device-compatibility.md` untuk MVP. Perbedaan ini dicatat di sini agar
  dokumen keputusan tidak diubah diam-diam.
- **Hilt dipakai** untuk ViewModel, repository, dan sink. Tidak ada Service
  locator manual.
