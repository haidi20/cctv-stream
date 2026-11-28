# Celah integrasi Kotlin-Rust: yang wajib ditambal

Status: 2026-11-28. Dokumen kerja, bukan ADR. Dasarnya audit kode langsung pada
2026-11-28 atas `android/app/src/main`, `android/feature/*`, `android/core/*`, dan
`rust-core/core-ffi/src`; setiap butir menyebut berkas dan baris yang bisa diperiksa
ulang. Angka yang belum diukur ditulis **belum diverifikasi**, bukan dikarang.

Ruang lingkup: sambungan Kotlin <-> Rust (FFI `core-ffi`), jalur frame dari preview ke
engine, dan jalur status dari engine ke layar. Bukan: server, dashboard, atau UI di luar
kebutuhan integrasi.

Kualitas kotak deteksi orang (geometri overlay, umur kotak, ambang, anchor meja) dibahas
terpisah di `docs/detection-quality-todo.md`; jangan mencampur kedua daftar.

Cara membaca tiap butir:

- **Bukti** - letak kode yang membuat celah ini nyata hari ini.
- **Gejala diam** - apa yang terlihat pengguna/perangkat kalau celah ini tidak ditambal.
  Semua gejala di sini tidak memunculkan crash; itu sebabnya tidak pernah muncul di gate.
- **Tambalan** - perubahan minimum, tanpa menambah dependensi baru.
- **Uji penjaga** - test yang harus ada supaya celah tidak kembali (red-green, nama test
  menyebut perilaku).
- **Bukti selesai** - artefak yang harus ada di repo atau di perangkat.

Aturan: butir tidak boleh ditutup tanpa uji penjaga dan bukti selesai. Item ber-tanda
**(P0)** menghalangi nilai produk; **(B)** celah diam yang murah ditambal; **(C)**
optimalisasi setelah P0/B hijau.

## 0. Ringkasan

Tiga hal menghalangi aplikasi ini berguna di HP warung, dan semuanya ada di batas
Kotlin-Rust:

1. **Model ONNX tidak pernah terkirim.** `android/app/src/main/assets/models/` hanya
   berisi `.gitkeep`; `EngineAssetPaths` mencari `yolov11n.onnx` di sana. Di perangkat
   pengguna `EngineStartPlanner` mengembalikan `Unavailable("Model ONNX belum tersedia")`
   dan total meja selamanya "—".
2. **Alasan "tidak menghitung" tidak pernah sampai ke layar.** Rantai
   `EngineStartPlan.Unavailable(reason)` -> `RustCameraSession.startIfReady` -> `return`
   membuang alasan itu (`RustCameraSession.kt:100-103`). `HomeState.modelReady` ada
   (`HomeState.kt:18`) tetapi tidak dipakai untuk memberi tahu pengguna. Pengguna melihat
   tanda "—" tanpa sebab, dan tidak ada cara membedakannya dari "meja belum dipetakan".
3. **Kamera CCTV nyata belum pernah diuji.** Semua uji RTSP berjalan terhadap server
   loopback; `tools/device-e2e.ps1` memakai klip, bukan RTSP EZVIZ. Jalur
   `report_stream_error`, watchdog 5 detik, dan gerbang `view_moved` (ADR 0011) belum
   bertemu kamera sungguhan.

Sisanya (bagian 2 dan 3) adalah celah kecil yang tidak memunculkan error apa pun tetapi
membuat diagnosis lapangan mustahil: error ditelan tanpa penghitung, klasifikasi error
lewat pencocokan string, tiga poller `health()` yang saling berebut lock, dan angka
`frames_dropped` yang artinya bukan "hilang".

## 1. Blocker produk (P0)

### G-01 (P0) Model ONNX tidak pernah terkirim ke perangkat

**Bukti**
- `android/app/src/main/assets/models/` berisi hanya `.gitkeep` (0 byte).
- `EngineAssetPaths.kt:28` menyalin `assets/models/yolov11n.onnx` ke `filesDir/models/`,
  `:56` menetapkan `MODEL_NAME = "yolov11n.onnx"`.
- `EngineStartPlanner.kt:44-45`: model kosong -> `EngineStartPlan.Unavailable("Model ONNX
  belum tersedia")`.
- `EngineFramePump.kt:103`: tanpa `health.modelReady` pompa frame tidak pernah mengirim
  apa pun ke Rust.

**Gejala diam** Semua meja `unknown`, kartu total kosong/terisi "—", `modelReady=false`,
dan tidak ada pesan apa pun. Aplikasi terlihat sehat. Slot model di `EngineConfig` tetap
diisi path yang tidak ada -> `Engine.start` gagal, juga tanpa jejak di layar.

**Tambalan** Pilih satu, jangan dua-duanya setengah:
- A. Task Gradle `verifyModel` yang mengambil model dari lokasi yang ditentukan builder,
  mencocokkan SHA-256 dengan nilai yang dipin di `docs/release.md`, lalu menaruhnya di
  `assets/models/` saat build. `.gitignore` tidak dilemahkan: model tetap tidak di-commit.
- B. Layar "Pasang model" di onboarding: pengguna memilih berkas, aplikasi memverifikasi
  checksum, menyalin ke `filesDir/models/`, dan menyimpan versi model.
- C. A untuk pilot + B untuk rilis toko.

Wajib ikut ditambal: `EngineAssetPaths.modelPath()` harus memeriksa ulang saat versi model
berubah (lihat G-12) supaya pembaruan model benar-benar berlaku.

**Uji penjaga**
- Host: `EngineStartPlanner` mengembalikan `Unavailable` yang menyebut model (sudah ada
  sebagian; pastikan pesannya dipakai UI, lihat G-02).
- Host: `EngineAssetPaths` menolak berkas 0 byte dan tidak membuat file pengganti.
- Instrumented (`EngineAbiTest`): `Engine().start(config dengan modelPath palsu)` gagal
  `EngineException` dan `health()` tetap `IDLE`, `tablesUnknown` tetap terisi.

**Bukti selesai** Setelah `adb install` bersih tanpa `adb push` model,
`tools/device-e2e.ps1` (mode klip) mencatat `modelReady=true` dan `tables>0` pada laporan
`cctv-*.txt` di perangkat.

### G-02 (P0) Alasan engine tidak menghitung tidak pernah sampai ke pengguna

**Bukti**
- `RustCameraSession.kt:100-103`: `EngineStartPlan.Unavailable` -> `return`, alasannya
  dibuang.
- `RustCameraSession.kt:107-116`: `engine.start(config)` yang gagal ditelan
  (`catch (_: Throwable)`), sesi tetap dilaporkan `Opened` (`:77`).
- `EngineFramePump.kt:103`: satu-satunya tempat `modelReady` diperiksa, hasilnya hanya
  `return buffer`.
- `HomeState.kt:18` punya `modelReady`, tetapi tidak ada layar yang menampilkan sebabnya
  (tidak ada rujukan `modelReady` di `HomeScreen`).

**Gejala diam** Pengguna melihat "—" dan menyimpulkan aplikasinya rusak, padahal sebabnya
spesifik: model belum ada, ONNX Runtime tidak termuat, RAM di bawah ambang, peta kosong,
atau kamera demo. Semua sebab itu sudah dibedakan di Rust/Kotlin, lalu dibuang.

**Tambalan** Tambah satu tipe hasil yang wajib dikonsumsi UI:
`CameraSessionResult.Opened(counting: CountingState)` dengan
`CountingState { Active | Inactive(reason: CountingReason) }`, dan
`CountingReason { ModelMissing, RuntimeMissing, RamTooLow, NoTables, Demo, StartFailed }`.
`EngineStartPlanner` sudah memproduksi semua sebab ini; tugasnya hanya meneruskannya.
Home menampilkan satu baris tetap di bawah panel total, mis. "Deteksi tidak aktif: model
belum terpasang" + tombol "Petakan meja".

**Uji penjaga**
- Host: untuk setiap `EngineStartSkip`/`Unavailable`, `RustCameraSession` mengembalikan
  `CountingState.Inactive` dengan reason yang tepat.
- Host: `HomeViewModel` memetakan setiap `CountingReason` ke teks yang berbeda (bukan satu
  teks generik).
- Host: `Engine.start` yang melempar -> `Inactive(StartFailed)`, bukan `Active`.

**Bukti selesai** Test UI/Compose menyatakan bahwa layar tanpa model menampilkan kalimat
sebab, bukan "—" telanjang; assertion semantics cukup, tidak perlu gambar.

### G-03 (P0) Kamera CCTV nyata belum pernah menggerakkan status

**Bukti** `core:network` menguji RTSP terhadap server loopback
(`SocketRtspSessionProbeTest`); `tools/device-e2e.ps1` memakai klip video yang di-*push*,
bukan RTSP EZVIZ. Belum ada laporan perangkat yang mencatat DESCRIBE, frame pertama, atau
kamera dimatikan saat monitoring.

**Gejala diam** Kalau RTSP asli gagal di lapangan, aplikasi tetap bisa menampilkan angka
dari jalur demo/polling terakhir. Itu persis kebohongan yang dilarang `AGENTS.md` §4.5.

**Tambalan** Protokol uji 30 menit dengan kamera EZVIZ: (1) tambah kamera lewat editor,
(2) jalankan monitoring, (3) catat `framesIn` naik dan `phase=Live`, (4) cabut kabel
jaringan kamera, (5) pastikan semua meja `unknown` dalam <=5 detik (watchdog) atau <=1
detik (`reportStreamError`), (6) sambungkan lagi, (7) pastikan status kembali tanpa
restart aplikasi. Skrip pencatat: perluas `tools/device-e2e.ps1` dengan mode
`-Rtsp <url>` yang menulis baris yang sama ke `cctv-live.txt`.

**Uji penjaga** Instrumented: `reportStreamError(UNREACHABLE)` saat engine berjalan ->
`health().stream` berubah dan `tablesUnknown == tables` (sisi Rust sudah ada; sisi Kotlin
baru jalur `NotRunning` yang diuji di `EngineAbiTest`).

**Bukti selesai** `cctv-live.txt` dari kamera nyata yang mencatat ketujuh langkah di atas,
disertai tanggal dan model kamera. Tanpa berkas itu, status kamera nyata tetap
**belum diverifikasi**.

## 2. Celah diam di batas FFI (B)

Semua butir di bawah ini tidak memunculkan crash, tidak menggagalkan gate, dan tidak
terlihat di layar. Itulah alasan bagian ini ditulis terpisah dari P0: nilainya baru
terasa saat ada masalah di lapangan.

Status 2026-11-28: **G-04, G-05, G-06, G-07, G-09, G-10, G-11, G-12, G-13, G-19 sudah
ditambal** dan dijaga test host
(`EngineErrorClassificationTest`, `EngineFramePumpFailureTest`, `EngineProviderRetryTest`,
`TableEventRecorderTest`, `InferenceCadenceTest`, `EngineFramePumpTest`,
`RustEngineStatusSourceTest`, `EngineAssetPathsTest`) atau test Rust (`api.rs` panic listener dan `set_tables`)
serta instrumented (`EngineAbiTest`). Catatan jujur per butir:

- G-05 menghitung kegagalan dan mengekspos `EngineFramePump.state`, tetapi banner Home
  untuk status `Failing` belum dipasang; angka `consecutiveFailures` baru bisa dibaca
  kode yang memegang pompa.
- G-06 menyimpan nama kelas error di `EngineProvider.lastLoadErrorForTest` dan menyediakan
  `resetForRetry()`, tetapi belum ada pemanggil produksi yang memicunya setelah update APK.
- G-07 menghitung `TableEventRecorder.lostEvents()` per proses; field heartbeat
  `runtime.lost_events` **belum** ada di schema (klaim "schema sudah punya slot" pada
  tabel di bawah ternyata tidak benar), jadi angkanya belum terkirim ke server.
- G-13 menulis `listener_panic` lewat `observability`, dan test Rust membuktikan listener
  berikutnya tetap menerima event; pembacaan berkas log belum diuji otomatis.
- G-09/G-19 memakai cache snapshot 900 ms di `RustEngineStatusSource` untuk dua pembaca
  UI (`read` + `readAnalysis`); `TableEventRecorder` sengaja membaca langsung karena
  `since_ms` butuh `uptime_ms` yang akurat, dan pompa frame tetap punya jalur sendiri.
  Test membuktikan empat pembacaan dalam satu detik sintetis = 1 panggilan `health()`.
- G-10 tidak mengganti nama field wire (`frames_dropped` tetap di schema dan heartbeat).
  Artinya sekarang didokumentasikan di `docs/performance.md` section 10.2, `EngineHealth`
  menjelaskan drop-by-backpressure, dan `EngineFramePumpState.throttled` menandai saat
  cadence sengaja diturunkan. Yang belum: `inference_fps` yang dikirim ke Rust belum ikut
  turun saat throttle, karena itu butuh restart sesi; laporan soak harus membaca
  `throttled` + `frames_sampled/uptime` untuk laju nyata.
- G-11 menyimpan status `Connected` saat peta disimpan di tengah sesi hidup: tidak ada
  event `Connecting`, dan tabel baru tetap `unknown` sampai frame berikutnya.
- G-12 menyimpan stamp `<versionCode>:<lastUpdateTime>` di `filesDir/models/yolov11n.onnx.stamp`.
  Jalur cepat (stamp sama) tidak membaca berkas sama sekali; hash hanya dihitung setelah update,
  dan bila byte aset sama hanya stamp yang ditulis. Bila `PackageManager` menolak memberi info
  paket, model yang sudah terpasang dipertahankan, bukan ditimpa. Belum diverifikasi di
  perangkat: belum ada update APK nyata dengan model berbeda untuk diuji.

| ID | Bukti | Gejala diam | Tambalan minimum | Uji penjaga |
| --- | --- | --- | --- | --- |
| **G-04** | `RustCameraSession.kt:195-196` mengklasifikasi error dengan `message.contains("tables")` / `"config"` | Pesan error Rust berubah sedikit -> peta yang ditolak dilaporkan sebagai `Unavailable`, atau sebaliknya; pengguna disuruh "coba lagi" padahal JSON-nya salah | Tangkap tipe: `catch (e: EngineException.Tables)` -> `Rejected`, `catch (e: EngineException.Config)` -> `Unavailable`; substring hanya sebagai fallback yang diberi komentar | Host: fake engine yang melempar `EngineException.Tables` (tanpa kata "tables" di pesan) tetap `Rejected` |
| **G-05** | `EngineFramePump.kt:115-121` membuang semua throwable dari `pushFrame`; `:105-111` membuang kegagalan `captureInto` | Format/size frame yang salah, atau engine yang selalu menolak, berhenti tanpa satu pun jejak; total tetap "—" selamanya | Penghitung `consecutivePushFailures` + `observability` (bukan `Log` dengan data frame) + sinyal ke Home setelah N kegagalan berturut | Host: fake `pushFrame` selalu melempar -> setelah 3 tick status pompa `Failing`, bukan `Idle` |
| **G-06** | `EngineProvider.kt`: `catch (error: Throwable) { failed = true; return null }` | `UnsatisfiedLinkError` (`.so` tidak cocok, ABI salah, update parsial) membuat aplikasi `Unavailable` sepanjang proses tanpa satu baris diagnosis | Satu log ter-redaksi (nama error + ABI) lewat jalur observability, dan izinkan satu percobaan ulang setelah `filesDir` berubah/versi berubah | Host: setelah `failed` di-set, `engineOrNull()` bisa mencoba lagi sekali bila `resetForRetry()` dipanggil |
| **G-07** | `TableEventRecorder.kt:143-146` menelan kegagalan tulis ("event is lost") | Outbox kosong dianggap "belum ada transisi", padahal transisi terjadi dan hilang; dashboard/`table_logs` jadi bolong tanpa alarm | Penghitung `lostEvents` per proses, dikirim di `runtime`/heartbeat (schema sudah punya slot opsional) | Host: outbox yang selalu gagal -> `lostEvents` bertambah, `awaitIdle()` tetap selesai |
| **G-08** | `EngineFramePump.kt:171` memanggil `engine.pushFrame(...)` dan membuang `List<TableEvent>` yang dikembalikan (kontrak Rust: `api.rs:213`) | Dua jalur kebenaran: listener (`RustEngineEventSource`, `TableEventRecorder`) dan nilai balik. Transisi yang terjadi sebelum `subscribe` berhasil hanya terlihat lewat polling; kalau suatu saat listener dihapus, tidak ada test yang gagal | Putuskan satu kanal: pakai nilai balik `pushFrame` sebagai sumber utama dan listener hanya untuk `Stream`, **atau** kunci keputusan sebaliknya di dokumentasi + test bahwa nilai balik memang diabaikan | Instrumented `EngineAbiTest`: event dari `pushFrame` (saat running) identik dengan event listener untuk transisi yang sama |
| **G-09** | `health()` dipanggil dari tiga tempat: `EngineFramePump.kt` (tiap tick), `RustEngineStatusSource.kt` (`read` dan `readAnalysis`), `TableEventRecorder.kt` (`toPayload`) | Empat pembaca lock yang sama; Rust memegang lock state saat menghitung sidik jari frame, jadi `health()` di UI bisa menunggu di belakang inferensi 200 ms | **Selesai**: `RustEngineStatusSource.snapshot` menyajikan satu snapshot 900 ms untuk `read` + `readAnalysis`; pompa frame tetap memakai jalurnya sendiri karena butuh latency minimal | Host: `RustEngineStatusSourceTest` membuktikan 4 pembacaan dalam 1 detik sintetis = 1 panggilan `health()`, dan TTL habis -> baca ulang |
| **G-10** | `api.rs`: frame yang dilewati sampler masuk `frames_dropped`; `InferenceCadence.delayMillis` menurunkan laju pompa saat panas/RAM rendah sementara `inference_fps` Rust tetap | Nama "dropped" dibaca sebagai "video hilang". Saat HP panas, angka naik dua kali (pompa melambat + Rust menolak frame) dan laporan soak terlihat seperti kebocoran | **Sebagian selesai**: arti angka didokumentasikan di `docs/performance.md` section 10.2 dan `EngineHealth`, `InferenceCadence.isThrottled` + `EngineFramePumpState.throttled` menandai throttle. Belum: `EngineConfig.inferenceFps` ikut turun saat throttle (butuh restart sesi) | Host: `InferenceCadenceTest` (SEVERE/RAM rendah/unknown) + `EngineFramePumpTest.a_hot_phone_is_reported_as_a_throttled_pump` |
| **G-11** | `api.rs`: `set_tables` saat running selalu mempublikasikan `Connecting` | Setiap kali pengguna menyimpan poligon, banner "Menghubungkan" berkedip walau stream tetap `Connected` | **Selesai**: status dibaca sebelum `install_tables`; bila `Connected`, status dipertahankan dan state machine baru di-unfreeze tanpa event | Rust: `saving_a_map_on_a_connected_stream_keeps_it_connected` + regresi `saving_a_map_before_the_stream_connects_still_announces_connecting` |
| **G-12** | `EngineAssetPaths` dulu memakai berkas `filesDir` bila `length > 0`, tanpa versi/checksum | Model baru di APK tidak pernah menggantikan model lama di perangkat yang sudah pernah instal; akurasi tidak berubah setelah update | **Selesai**: berkas stamp `<versionCode>:<lastUpdateTime>` di samping model, `planRefresh` memilih Reuse/Adopt/Copy, dan salinan hanya di-hash saat stamp berbeda. Aset hilang mempertahankan model lama | Host: `EngineAssetPathsTest` (update -> Copy, rerun -> Reuse tanpa hash, byte sama -> Adopt, stamp tak dikenal -> tidak menimpa) |
| **G-13** | `api.rs:819`: panic listener dilaporkan lewat `eprintln!` | Di perangkat, `stderr` tidak masuk berkas log; panic listener hilang tanpa jejak dan event yang sama terus gagal | Arahkan ke `observability` (fungsi baru `log_listener_panic`) | Rust: test `logging.rs` memverifikasi baris log muncul di berkas |
| **G-14** | Tidak ada tabel/kode `device_settings` (pencarian nol hasil di `core:database`); `EngineStartPlanner.kt:85` memaku `INTRA_THREADS = 2u` | Keputusan per-perangkat (FPS, jumlah thread, apakah model 320 atau 640) tidak tersimpan; setiap instal mengulang tebakan yang sama | Tabel `device_settings` (Room v4) + pemakaian `p95` dari onboarding (K-P0-5) untuk memilih FPS/threads | Host: migrasi Room v3->v4 teruji; planner memakai nilai tersimpan |

## 3. Optimalisasi (C) - setelah P0 dan B hijau

Butir di sini mengubah angka, bukan kebenaran. Jangan dikerjakan sebelum dua bagian di
atas, karena tanpa model terpasang (G-01) semua pengukuran tetap memakai klip dan adb
push, bukan keadaan pengguna.

| ID | Bukti / angka | Sasaran | Catatan pelaksanaan |
| --- | --- | --- | --- |
| **G-15** | `tools/device-bench.ps1`: p50 `pushFrame` 209-239 ms (640x360) dan 222-256 ms (640x640); CPU 166-188% pada soak 30 menit | CPU < 100% dan p50 < 150 ms | `VideoCaptures.captureInto` memakai `TextureView.getBitmap` (baca balik GPU + decode di CPU). Jalur lebih murah: `MediaCodec`/`ImageReader` langsung ke buffer, dan turunkan sisi masuk ke 416 atau 320. Bukti wajib: CSV sebelum/sesudah |
| **G-16** | `PackedFrameBuffers.pack` menyalin 640x360 -> 691 KB per tick lalu UniFFI menyalin lagi ke Rust | Satu salinan, bukan dua | Alternatif: kirim gray8 (1 byte/piksel) dan biarkan Rust melakukan resize, sehingga salinan turun ~3x. Menyentuh `RgbFrame::new` -> butuh test kontrak baru di kedua sisi |
| **G-17** | Target dokumen 24 FPS vs kenyataan 3,8-4,5 analysis FPS (`device-e2e.ps1` 2026-11-27) | Satu angka resmi: 2 FPS analisis, 24 FPS hanya untuk capture | Setelah G-15 diukur, selaraskan `docs/performance.md` §1 dan §7. Jangan ubah angka sebelum ada pengukuran baru |
| **G-18** | `EngineStartPlanner.kt:85` memaku 2 thread; model 640 memberi p50 216 ms | Model 320/416 dengan akurasi terukur, `benchThreads` diuji A/B | Bagian dari R-P0-4; hasilnya mengisi G-14 (pilihan disimpan per perangkat) |
| **G-19** | `RustEngineStatusSource.read` + `readAnalysis` + pompa + recorder membaca snapshot terpisah | Satu snapshot, satu akuisisi lock per detik | **Selesai bersama G-09**: dua pembaca UI berbagi snapshot 900 ms. Recorder sengaja tetap membaca langsung (butuh `uptime_ms` akurat untuk `since_ms`), pompa tetap punya jalur sendiri | Sama dengan G-09 |

## 4. Cakupan kontrak yang masih satu sisi

Perilaku yang hari ini hanya punya bukti di satu bahasa. Perhatikan: banyak di antaranya
**butuh model** (G-01) sebelum bisa diuji dari Kotlin; itulah alasan G-01 juga membuka
jalan untuk uji lain.

| Perilaku | Bukti Rust sekarang | Bukti Kotlin/perangkat | Catatan |
| --- | --- | --- | --- |
| Frame basi 5 detik -> `unknown` | test `core-ffi` watchdog | belum | Butuh engine berjalan -> menunggu G-01 |
| Debounce transisi status | test `spatial-engine` | belum | Bisa diuji Kotlin hanya setelah model ada |
| Kamera digeser -> `view_moved` (ADR 0011) | test `core-ffi` | belum | Sama |
| Ganti kamera: frame generasi lama ditolak | test `core-ffi` (`generation`) | `RustCameraSession` (fake engine) | Risiko nyata ada di FFI; layak ditambah di `EngineAbiTest` setelah G-01 |
| `report_stream_error` -> semua meja `unknown` | test `core-ffi` | hanya jalur `NotRunning` | Tambah jalur berjalan setelah G-01 |
| Frame dengan ukuran salah ditolak | `RgbFrame::new` (test `core-ffi`) | belum | Sekarang `push_frame` menolak karena `NotRunning` lebih dulu, jadi tidak bisa diuji tanpa model |
| Listener yang panic tidak merusak engine | `dispatch` + `catch_unwind` | belum | **Bisa diuji sekarang** di `EngineAbiTest` (listener Kotlin yang melempar) |
| Arti `frames_dropped` | test sampler `core-ffi` | dipakai di heartbeat tanpa penjelasan | Perbaiki bersama G-10 |
| Urutan `Connecting` sebelum `Connected` | test `core-ffi` | belum | Butuh model |
| p50/p95 reset per sesi | test `core-ffi` | dibaca `SoakDeviceSampler` | Tambah assertion di laporan soak |
| Penolakan peta JSON rusak | `kotlin_contract.rs` + `ContractSchemaTest` | `EngineAbiTest` | Sudah dua sisi |
| Peta tanpa frame = `unknown`, bukan `kosong` | `kotlin_contract.rs` | `EngineAbiTest` | Sudah dua sisi |

## 5. Urutan kerja yang disarankan

1. **G-01 + G-02 dalam satu paket.** Model tanpa jalur alasan tetap menghasilkan "—" tanpa
   penjelasan; alasan tanpa model hanya memindahkan kebingungan.
2. **G-04, G-05, G-06, G-07, G-13.** Semuanya kecil dan semuanya menambah kemampuan
   diagnosis di perangkat. Satu commit per butir; perkiraan total satu hari kerja.
3. **G-03**: uji kamera nyata 30 menit dengan protokol di atas. Ini yang mengubah klaim
   "belum diverifikasi" menjadi bukti, dan hanya bisa dilakukan dengan kamera di tangan.
4. **Soak 8 jam layar mati** (`tools/device-soak.ps1`) di perangkat yang tersambung;
   hasilnya mengisi matriks `docs/mvp-priority.md` §5, termasuk drain baterai yang sampai
   sekarang belum terukur karena USB powered.
5. **G-09, G-10, G-11**: konsolidasi poller dan semantik angka. Setelah itu barulah
   **G-15/G-16** (CPU) masuk akal diukur.
6. **G-08, G-12, G-14** dan perluasan matriks §4.

## 6. Perintah verifikasi per perubahan

Rust (dari `rust-core/`):

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo test -p core-ffi
```

Kotlin (dari `android/`, lewat wrapper `gradle-run`):

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :feature:home:testDebugUnitTest :feature:cameras:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

Bukti lintas bahasa (butuh perangkat/emulator; `EngineAbiTest` tidak ikut gate unit test):

```powershell
D:\app\sdk\platform-tools\adb.exe -s 127.0.0.1:5555 shell am instrument -w `
  -e class id.warung.monitor.engine.EngineAbiTest `
  id.warung.monitor.test/androidx.test.runner.AndroidJUnitRunner
```

## 7. Yang sengaja tidak diubah

- `AGENTS.md`, ADR, dan `docs/mvp-priority.md`: dokumen keputusan. Usulan pada §3/G-17 dan
  §4 butuh persetujuan pemilik repo lebih dulu.
- `docs/status.md`: sedang ada perubahan yang belum di-commit; dokumen ini tidak
  menyentuhnya. Setelah P0 hijau, satu baris ringkas layak ditambahkan ke sana.
- `HomeScreen.kt` dan `DevSeed.kt`: WIP pemilik repo; G-02 hanya menambah state dan test,
  penyentuhan layar dilakukan setelah WIP itu selesai.
- Dua suite lintas bahasa yang sudah dibuat
  (`rust-core/core-ffi/tests/kotlin_contract.rs`,
  `android/app/src/androidTest/kotlin/id/warung/monitor/engine/EngineAbiTest.kt`) belum
  di-commit saat dokumen ini ditulis; keduanya menjadi dasar perluasan matriks §4.

