# TODO kualitas deteksi orang (CV): Kotlin + Rust

Status: 2026-11-28. Dokumen kerja, bukan ADR. Dasarnya audit kode langsung atas
`rust-core/ai-engine`, `rust-core/core-ffi`, `rust-core/spatial-engine`,
`android/app/src/main`, dan `android/feature/home` pada 2026-11-28, ditambah keluhan
lapangan: **kotak hijau deteksi orang masih "amburadul" dan tidak tepat**.

Setiap butir menyebut berkas dan baris yang bisa diperiksa ulang. Angka yang belum
diukur ditulis **belum diverifikasi**, bukan dikarang.

Cara membaca tiap butir:

- **Gejala** - apa yang terlihat di layar perangkat.
- **Bukti** - letak kode yang membuat gejala itu nyata hari ini.
- **Tambalan** - perubahan minimum, tanpa pustaka CV baru.
- **Uji penjaga** - test yang harus ada (nama test menyebut perilaku).
- **Bukti selesai** - angka/artefak yang harus ada sebelum butir ditutup.

Aturan: **(P0)** menghalangi keluhan langsung ("kotak salah tempat"), **(P1)** membuat
kotak berkedip/dobel/salah objek, **(P2)** kerapian visual dan dokumentasi. Butir tidak
boleh ditutup dengan alasan "kelihatan lebih baik"; harus ada angka dari Q-03.

## 0. Ringkasan

Keluhan "kotak hijau amburadul" bukan satu bug. Ada tiga lapisan yang bertumpuk, dan
lapisan pertama tidak berhubungan dengan model sama sekali:

1. **Kotak digambar dengan geometri yang salah (P0).** Kartu video dipaksa 16:9
   (`HomeScreen.kt:588`) sementara player memakai `resize_mode="fit"`
   (`res/layout/preview_player.xml:7`) dan overlay memetakan `0..1` ke seluruh kartu
   (`HomeScreen.kt:552-565`). Untuk stream 4:3 kotak bergeser + melebar 1,33x; untuk
   stream portrait melebar >3x. Home tidak pernah menerima `VideoSize` walaupun
   callback-nya sudah ada (`CameraPreviewSurface.kt:69`, `:107-110`); hanya layar
   mapping yang menyambungkannya (`MainActivity.kt:370`).
2. **Kotak selalu ketinggalan 1-1,3 detik (P0).** Frame dianalisis 2 FPS
   (`InferenceCadence.kt:81-87`, `NORMAL_DELAY_MS = 500`), inferensi berjalan sinkron
   di dalam `pushFrame` (`EngineFramePump.kt:139-159`, `:202-215`; p50 209-256 ms per
   `AGENTS.md`), dan Home baru membaca kotak tiap 1 000 ms (`HomeState.kt:397`). Video
   berjalan 25-30 FPS, jadi kotak orang berjalan tertinggal 1-2 meter. Ini terlihat
   sebagai "kotak tidak menempel".
3. **Status meja memakai titik tengah kotak (P1).** `geometry.rs:122-134` +
   `multi_table.rs:98-105`. Untuk orang duduk, titik tengah badan ada di atas poligon
   meja, jadi meja terbaca kosong padahal terisi. `docs/acceptance.md:57-73` sudah
   menyebut ini sebagai keputusan yang belum diambil.

Yang **sudah benar** dan tidak boleh "diperbaiki" tanpa bukti baru: letterbox dan
inversnya (`preprocess.rs:111-130`, `preprocess.rs:72-74`, `postprocess.rs:81-89`, test
di `postprocess.rs:225-262`), urutan kanal RGB (`Rgb888.kt` + `Rgb888Test`), normalisasi
`0..1` dengan clamp di Rust (`state.rs:504-518`), NMS greedy per confidence
(`nms.rs:46-61`), dan pemakaian padding 114 (`preprocess.rs:16`). Kotak yang salah
tempat **bukan** karena decoder YOLO salah hitung.

| ID | Gejala | Lapisan | Biaya |
| --- | --- | --- | --- |
| Q-01 | Kotak bergeser/melebar dibanding video | Kotlin (overlay) | 0,5 hari |
| Q-02 | Kotak tertinggal dari orang yang bergerak | Kotlin + Rust | 0,5 hari |
| Q-03 | Tidak ada angka akurasi sama sekali | Rust (harness) | 1 hari |
| Q-04 | Meja terisi terbaca kosong (titik acuan) | Rust (geometri) | 1 hari + rekaman |
| Q-05 | Ambang tidak bisa diatur dari perangkat | Rust + Kotlin | 0,5 hari |
| Q-06 | Kotak kecil/aspek aneh ikut digambar | Rust (decode) | 0,5 hari |
| Q-07 | Kotak berkedip 2 FPS, orang sama dihitung ulang | Rust (track) | 1 hari |
| Q-08 | Model selain 84-atribut gagal total | Rust (detector) | 1 hari |
| Q-09 | Semua meja `unknown` padahal orang terdeteksi | Rust (view guard) | 0,5 hari |
| Q-10 | Orang jauh hanya 20-30 px di input model | Kotlin + Rust | 2 hari (eksperimen) |
| Q-11 | Garis kotak tebal/kasar, tanpa tingkat keyakinan | Kotlin (Compose) | 0,25 hari |
| Q-12 | Kotak tidak membawa stempel waktu frame | Rust + Kotlin | 0,5 hari |
| Q-13 | Kotak hijau muncul di luar area yang dipetakan | Rust (region) + Kotlin | 0,5 hari |

## 1. P0 - kotak salah tempat dan kedaluwarsa

### Q-01 Overlay digambar dengan aspek 16:9 tetap, bukan aspek video

**Gejala.** Kotak bergeser ke samping dan melebar/tinggi dibanding orang di video.
Semakin jauh dari tengah gambar, semakin jauh geserannya. Pada stream portrait kotaknya
melebar sampai menutupi setengah layar.

**Bukti.**

- Kartu video dipaksa 16:9: `HomeScreen.kt:588` -> `Modifier.aspectRatio(16f / 9f)`.
- Player memakai `fit`: `android/app/src/main/res/layout/preview_player.xml:7`
  (`app:resize_mode="fit"`), sehingga video 4:3/portrait diberi bilah hitam di dalam
  kartu 16:9 dan **tidak** mengisi kartu.
- Overlay memetakan `0..1` ke seluruh kartu: `HomeScreen.kt:552-565`
  (`DetectionOverlay`), tanpa menyertakan bilah hitam.
- `HomeScreen` tidak pernah menerima ukuran video. `CameraPreviewSurface` sudah
  menyediakan `onVideoSize` (`CameraPreviewSurface.kt:69`, `:107-110`) tetapi pemanggil
  di `MainActivity.kt:301` tidak mengirimnya; hanya layar mapping yang menyambungkan
  (`MainActivity.kt:370`).

Perkiraan galat untuk kartu 16:9 (lebar kartu = 1, tinggi kartu = 0,5625):

| Aspek stream | Lebar video di kartu | Bilah per sisi | Galat x di tepi | Faktor lebar kotak |
| --- | --- | --- | --- | --- |
| 16:9 | 1,000 | 0 | 0 | 1,00x |
| 4:3 | 0,750 | 0,125 | +/-0,125 (12,5% lebar layar) | 1,33x |
| 9:16 (portrait) | 0,316 | 0,342 | +/-0,342 (34% lebar layar) | 3,16x |
| 21:9 | tinggi video 0,429 | 0,067 (vertikal) | +/-0,067 (vertikal) | 1,31x tinggi |

**Tambalan.** Jangan mengubah `resize_mode` menjadi `fill`/`zoom`: itu memotong bagian
bawah frame (kaki orang hilang) dan membuat `TextureView.getBitmap` di
`VideoCaptures.kt:47-95` menangkap piksel yang sudah dipotong, sehingga koordinat
normalisasi Rust tidak lagi cocok dengan piksel yang dianalisis. Yang benar:

1. Slot `preview` di `HomeRoute`/`HomeScreen` menerima ukuran frame:
   `@Composable (CameraProfile, (Int, Int) -> Unit) -> Unit`. `MainActivity` meneruskan
   `onVideoSize` milik `CameraPreviewSurface` ke slot itu, sehingga `feature:home` tetap
   tanpa Media3 dan seluruh test tetap JVM.
2. `VideoAspect` (`feature/home/domain/VideoAspect.kt`) mengubah `(width, height)` dari
   Media3 menjadi rasio. Sisi `<= 0` (Media3 melaporkan `0x0` sebelum frame pertama)
   menghasilkan `null`, bukan `Infinity`, dan `VideoAspect.Fallback` tetap 16:9.
3. `VideoCard` menyimpan `videoAspect` lokal (`remember(camera?.id)`) dan memakai
   `.aspectRatio(videoAspect ?: VideoAspect.Fallback)`: kartu **mengadopsi aspek video**,
   jadi tidak ada bilah hitam - kartu itu sendiri adalah rect video. `aspectRatio` juga
   di-clamp oleh tinggi yang tersedia, sehingga di jendela pendek kartu tetap persis
   sebanding frame walau lebih sempit dari kolomnya.
4. Overlay hanya digambar bila `videoAspect != null`, dengan
   `.fillMaxSize().clipToBounds()` supaya goresan kotak di tepi tidak melewati kartu.
   Aspek `null` berarti tidak ada kotak (lebih baik tidak ada kotak daripada kotak
   bohong).

Node `video-content` + `VideoContentRect` yang semula direncanakan **dibatalkan**:
fungsi itu menghitung bilah hitam, padahal setelah butir 3 bilah hitam tidak pernah ada.
Menyimpannya akan menjadi cabang mati yang harus diuji tanpa pernah terpakai.

**Uji penjaga.**

- `VideoAspectTest` (host, murni, 4 kasus): 1920x1080 -> `Fallback`; 640x480 -> 4:3;
  1080x1920 -> 9:16; `0x0`, `640x0`, `-640x480` -> `null`.
- `HomeScreenTest` (JVM, 3 kasus): (a) `the_video_card_takes_the_decoded_frame_aspect_ratio`
  - preview melaporkan 640x480, kartu `video-card` diukur lewat `getUnclippedBoundsInRoot()`
  dan rasionya 4:3 (+-0.02), yang otomatis membuktikan kotak `0..1` jatuh di rect video
  yang sama dengan piksel yang dianalisis; (b) `detection_boxes_stay_hidden_until_the_frame_size_is_known`
  - ada deteksi tetapi belum ada ukuran -> node `detection-overlay` tidak ada;
  (c) `detection_boxes_appear_once_the_frame_size_is_known` - setelah 640x480 dilaporkan,
  overlay ada.

**Bukti selesai.** Gate host 2026-11-28: `:feature:home:testDebugUnitTest` 131 test hijau
(termasuk 4 `VideoAspectTest` + 3 kasus baru di atas), `:app:testDebugUnitTest` hijau,
`:app:lintDebug` 0 error, `:app:assembleDebug` sukses. Bukti visual pada stream 4:3 dan
portrait di perangkat **belum diverifikasi** (model ONNX belum dibundel, APK belum
dipasang ulang), jadi Q-01 belum boleh ditutup di `docs/status.md`.

**Risiko.** Bila `VideoSize` datang terlambat (beberapa ratus ms), overlay sempat tidak
digambar; itu perilaku yang diinginkan. Jangan mengisi `videoAspect` dari
`CameraProfile` (resolusi yang diminta) karena kamera EZVIZ sering mengirim aspek lain
dari yang diminta. `remember(camera?.id)` mengosongkan aspek saat berganti kamera,
sehingga kamera berikutnya tidak sempat memakai aspek kamera sebelumnya; bila Media3
melaporkan ukuran baru di tengah sesi (mis. stream di-restart dengan resolusi lain),
callback yang sama memperbarui state dan kartu ikut berubah.

### Q-02 Kotak yang dilihat pengguna berumur sampai 1,5 detik

**Gejala.** Kotak tertinggal di belakang orang yang berjalan, atau "menempel" pada
posisi lama saat orang sudah lewat. Pada adegan ramai kotak tampak bertukar objek.
Status meja (angka kosong/terisi) tetap benar karena dihitung di Rust pada frame yang
sama; yang salah hanya gambar kotaknya - jadi jangan "memperbaiki" Rust untuk gejala
ini.

**Bukti.**

- Frame dianalisis paling cepat tiap 500 ms di perangkat normal
  (`InferenceCadence.kt:81-87`: `NORMAL_DELAY_MS = 500L`).
- Inferensi berjalan sinkron di dalam `pushFrame` (`EngineFramePump.kt:139-159` di
  `tick`, panggilan di `:202-215`); p50 `pushFrame` terukur 209-256 ms
  (`AGENTS.md` §6, `tools/device-bench.ps1`).
- Kotak baru dibaca Home tiap 1 000 ms (`HomeState.kt:397`).
- Total umur kotak = sampai 500 ms tunggu tick + 210-260 ms inferensi + sampai 1 000 ms
  tunggu poll = **hingga ~1,5 s**. Orang berjalan 1,2 m/s bergeser ~1,8 m dalam waktu
  itu; pada CCTV warung itu setara satu meja penuh.

**Tambalan (tanpa mengubah kontrak FFI).**

1. Pisahkan jadwal poll: `HomeViewModel` memakai 1 000 ms untuk status/meja dan 250 ms
   khusus untuk daftar kotak (`readAnalysis` sudah murah: tiga getter FFI). Bila CPU
   perangkat panas, naikkan ke 400 ms lewat `DeviceLoadSource` yang sudah ada.
2. `HomeUiState` menyimpan `detectionsAtMillis`; `DetectionOverlay` menyembunyikan
   seluruh kotak bila umur > 1 200 ms. Lebih baik kotak hilang sesaat daripada kotak di
   tempat yang salah.
3. Tampilkan chip "analisis N ms lalu" memakai `lastAnalysisMillis` yang sudah ada, agar
   pengguna tahu angka meja berasal dari frame lampau.

Jangan menambahkan prediksi gerak (kalman/optical flow). Menggambar posisi yang tidak
terdeteksi melanggar aturan repo: kotak harus berasal dari inferensi, bukan karangan.

**Uji penjaga.**

- `HomeViewModelTest`: saat status `Live`, `readAnalysis` dipanggil >= 3 kali dalam
  1 000 ms; saat `Idle`/`Error`, tetap 1 000 ms (tidak memboroskan CPU saat kamera mati).
- `HomeStateTest`: `detections` kosong saat `lastAnalysisAgeMillis > 1200`, dan tetap
  tampil saat 900 ms.
- `DetectionOverlayTest` (Compose): kotak tidak digambar saat state menandai basi.

**Bukti selesai.** `tools/device-e2e.ps1` mencetak `ageP50`/`ageP95` umur kotak selama
orang berjalan; target `ageP95 <= 600 ms`. Angka ini **belum diverifikasi**.

### Q-03 Belum ada alat ukur akurasi: semua penilaian masih "kelihatan lebih baik"

**Gejala.** Tidak ada cara membuktikan perbaikan Q-05/Q-06/Q-10 berhasil. Tanpa ini,
tuning ambang hanya menukar satu jenis salah dengan jenis salah lain.

**Bukti.** `docs/acceptance.md:21-36` sudah menetapkan precision/recall/IoU, tetapi tidak
ada kode yang menghitungnya; `tools/device-e2e.ps1` hanya mencetak FPS, jumlah tabel, dan
`modelReady`. `ai-engine/tests/` hanya menguji inferensi sintetis
(`onnx_inference`), bukan kualitas deteksi.

**Tambalan.** Test host-only `rust-core/ai-engine/tests/detection_quality.rs`, aktif
hanya bila env diisi (tanpa env: keluar bersih, bukan `panic`, supaya
`cargo test --workspace` tetap hijau):

- `CCTV_QUALITY_DIR` berisi `frames/frame-<n>-<w>x<h>.rgb` (RGB888 mentah, format sama
  dengan kontrak FFI) dan `labels.json`:
  `[{ "frame": "frame-0001-640x480.rgb", "boxes": [{ "x": 0.1, "y": 0.2, "w": 0.1, "h": 0.3 }] }]`
  dengan koordinat ternormalisasi `0..1`.
- `CCTV_QUALITY_MODEL` menunjuk berkas ONNX (tidak di-commit, lihat `.gitignore`).
- Keluaran: per frame GT vs deteksi, pencocokan IoU >= 0.5, lalu ringkasan
  precision/recall/F1 untuk ambang 0.25 / 0.35 / 0.50, histogram tinggi kotak dalam
  piksel, dan lima frame dengan FP terbanyak.
- Frame dan label disimpan di luar repo (`testdata/frames` atau `%TEMP%`); tidak ada
  biner yang masuk commit.

**Uji penjaga.** Unit test untuk matcher dan agregasi confusion matrix dengan kotak
buatan (tanpa model, tanpa frame): IoU 0.49 -> bukan TP; satu GT dua deteksi -> 1 TP +
1 FP; deteksi ganda setelah NMS tidak boleh lolos (regresi Q-06).

**Bukti selesai.** Tabel angka precision/recall untuk 20 frame berlabel dari kamera
pengguna dicatat di `docs/status.md` beserta tanggal dan model yang dipakai. Sampai
tabel itu ada, klaim "akurasi membaik" **belum diverifikasi**.

**Alat ukur selesai (host 2026-11-28).** Angka akurasi lapangan tetap belum ada.

- `ai-engine/src/quality.rs` (murni, tanpa ONNX dan tanpa berkas): `NormalizedBox`
  (+`from_pixels`), `normalized_iou`, `match_frame` (greedy per confidence, satu label
  hanya boleh diklaim satu deteksi), `ConfusionCounts` (precision/recall/F1, kasus
  "tidak memprediksi apa pun" = precision 1,0 dan recall 0,0), `summarize`,
  `worst_frames`, dan `height_histogram` dengan batas 16/32/64/128/256 px.
- `ai-engine/tests/detection_quality.rs`: aktif hanya bila `CCTV_QUALITY_MODEL` dan
  `CCTV_QUALITY_DIR` diisi; tanpa env mencetak baris skip dan lulus. Mencetak tabel
  precision/recall/F1 untuk ambang 0,25/0,35/0,50, histogram tinggi kotak, dan lima
  frame dengan FP terbanyak. Ukuran frame dibaca dari nama berkas
  `frame-<n>-<w>x<h>.rgb`.
- Uji penjaga ada semua: IoU 0,48 (di bawah 0,5) bukan TP; satu GT + dua deteksi = 1 TP
  + 1 FP; orang yang terlewat = 1 FN; agregasi lintas frame; peringkat frame terburuk;
  histogram; normalisasi kotak piksel.
- Bukti alat bekerja: fixture `%TEMP%\cctv-quality` (satu frame 64x64 + label yang
  cocok) mencetak `precision 1.000 recall 1.000 f1 1.000 tp=1`; label yang digeser
  mencetak `0.000 / 0.000 / 0.000 tp=0 fp=1 fn=1`. Keduanya lulus, jadi alat membedakan
  hit dan meleset, bukan selalu hijau.
- Gate: `cargo test --workspace` hijau (ai-engine 57 unit + 1 quality + 3 onnx),
  `clippy --workspace --all-targets -D warnings` bersih, `fmt` bersih, `cargo audit`
  dan `cargo deny check` hijau.
- **Belum diverifikasi:** tabel precision/recall 20 frame berlabel dari kamera pengguna.
  Belum ada rekaman berlabel di mesin ini, jadi klaim "akurasi membaik" tetap tidak
  boleh dibuat.

## 2. P1 - kotak benar tempat tetapi angka meja tetap salah

### Q-04 Titik acuan orang masih titik tengah kotak

**Gejala.** Kotak menutup orang dengan benar, tetapi meja yang jelas diduduki tetap
terbaca kosong. Paling kentara untuk orang yang duduk dekat kamera: tinggi kotak
melebihi tinggi meja, dan titik tengahnya jatuh **di atas** poligon meja.

**Bukti.**

- Titik acuan = pusat kotak: `spatial-engine/src/geometry.rs:122-134`
  (`PixelBox::center`, `centroid_normalized`).
- Poligon diuji terhadap titik itu: `spatial-engine/src/multi_table.rs:96-111`
  (`polygon.contains(detection.centroid)`).
- Keputusan ini sudah dicatat sebagai **belum diambil**:
  `docs/acceptance.md:57-73` (center vs bottom-center vs foot anchor, wajib
  dibandingkan pada rekaman yang sama sebelum Fase 8).

**Tambalan.** Jangan mengubah default lebih dulu. Tambahkan pilihan yang terukur:

1. `Anchor` di `spatial-engine` (`Center`, `BottomCenter`, `FootWithFallback`) dengan
   default tetap `Center`.
2. `EngineConfig` (`core-ffi/src/types.rs`) menambah `anchor: Option<String>`; nilai
   tidak dikenal -> `EngineError::Config`, bukan diam-diam `Center`. `tables_json` v2
   tidak berubah, jadi tidak ada migrasi Room/JSON.
3. `FootWithFallback` = `BottomCenter`, tetapi bila kotak terpotong tepi bawah frame
   (kaki tidak terlihat) pakai `Center` dan tandai `bbox_clipped` di snapshot supaya
   tidak berpura-pura yakin.
4. Harness Q-03 mendapat mode perbandingan: jalankan tiga anchor pada label yang sama,
   cetak selisih hitungan meja per anchor.

**Uji penjaga.** `seated_person_counts_only_when_anchor_is_bottom`,
`bottom_anchor_uses_center_when_box_is_cut_by_frame_edge`,
`unknown_anchor_name_is_a_config_error`.

**Bukti selesai.** Tabel perbandingan tiga anchor pada satu rekaman 10 menit
(tanggal, jumlah frame, hitungan per meja) di `docs/status.md`, baru default boleh
berubah. **Belum diverifikasi** sampai tabel itu ada.

### Q-05 Ambang confidence/IoU tidak bisa diatur dari perangkat

**Gejala.** Deteksi meleset (kursi/kursi susun dikira orang di 0.35-0.45) atau orang
jauh hilang sama sekali, dan tidak ada cara menyesuaikan tanpa build ulang APK.

**Bukti.**

- `core-ffi/src/api.rs:349-355` hanya menimpa `intra_threads`; `confidence_threshold`
  dan `iou_threshold` selalu `DetectorConfig::default()` = 0.35 / 0.5
  (`ai-engine/src/detector.rs:72-108`).
- Validasi sudah tersedia dan bisa dipakai ulang: `detector.rs:114-135`.
- Pola konfigurasi per sesi sudah ada: `EngineConfig` membawa `debounce_ms`,
  `inference_fps`, `intra_threads` (`api.rs:356-359`).

**Tambalan.**

1. `EngineConfig` menambah `confidence_threshold`, `iou_threshold`, `min_box_px`
   (semua `Option`), divalidasi lewat `DetectorConfig::validate`; nilai di luar rentang
   -> `EngineError::Config` dengan alasan, bukan clamp diam-diam.
2. Dua preset di `EngineStartPlanner` (Kotlin): `seimbang` (0.35) dan `presisi` (0.45),
   dikirim lewat `RustCameraSession.startIfReady`; disimpan di `feature:settings` per
   perangkat, bukan per kamera (satu telepon = satu kelas lensa).
3. Pisahkan tegas: ambang **tampilan** (kotak) dan ambang **hitung meja**
   (`multi_table.rs:92` `min_confidence`). Dokumen ini mengubah yang pertama; yang kedua
   hanya boleh berubah bersama Q-03.

**Uji penjaga.** `core-ffi`: `confidence_out_of_range_is_rejected_with_reason`,
`a_threshold_above_the_planted_score_drops_the_box` dan
`box_filter_bounds_reach_the_decoder` (bergantung fixture ONNX, lihat langkah 5 rencana
di bawah). Kotlin: `EngineStartPlannerTest` memetakan preset -> nilai, dan preset tak
dikenal jatuh ke `seimbang` dengan catatan.

**Rencana pelaksanaan sisi Rust (2026-11-28).** Kotlin hanya meneruskan angka; semua
keputusan tetap di Rust supaya kotak yang digambar dan kotak yang dihitung per meja
berasal dari himpunan yang sama.

1. `ai-engine` - `DetectorConfig` (`ai-engine/src/detector.rs`) menambah
   `filter: BoxFilter` dengan default `BoxFilter::default()`. Alasannya:
   `DecodeLayout::for_attributes` masih memaku filter default, jadi `min_box_px` hari
   ini tidak bisa disentuh dari luar crate. `Detector::detect` memakai
   `spec.decode.with_filter(self.config.filter)`.
2. `ai-engine` - `DetectorConfig::validate` menolak `min_side_px` negatif/NaN/di atas
   `MAX_MIN_SIDE_PX` (`postprocess.rs`, konstanta baru) dan `max_aspect < min_aspect`;
   alasan error menyebut nama field dan batasnya.
   Uji: `rejects_min_side_outside_the_supported_range`, `rejects_inverted_aspect_bounds`,
   `permissive_aspect_bounds_stay_valid`.
3. `core-ffi` - `EngineConfig` (`core-ffi/src/types.rs`) menambah
   `confidence_threshold: Option<f32>`, `iou_threshold: Option<f32>`,
   `min_box_px: Option<u32>`. `null` = pakai default detektor (0,35 / 0,5 / 12 px), jadi
   APK yang belum mengirim nilai berperilaku persis seperti sekarang.
4. `core-ffi` - helper murni `resolve_detector_config(&EngineConfig)` di `api.rs`,
   dipanggil `start_session` **sebelum** `configure_library_path`. Nilai di luar rentang
   menjadi `EngineError::Config` yang menyebut field (`confidence_threshold must be in
   0..=1, got 1.5`), bukan `Model`/`NotRunning` dan bukan pesan generik
   `AiError::user_message`. `Detector::from_file` menerima hasil helper, bukan
   `..DetectorConfig::default()`.
   Uji: `confidence_out_of_range_is_rejected_with_reason`,
   `iou_out_of_range_is_rejected_with_reason`,
   `min_box_px_out_of_range_is_rejected_with_reason`,
   `omitted_thresholds_keep_the_detector_defaults`,
   `thresholds_and_filter_reach_the_detector_config`,
   `the_ffi_floor_matches_the_decoder_bound`.
5. `core-ffi/tests/onnx_engine.rs` (bergerbang `CCTV_ONNX_TEST_MODEL_640`) -
   `box_filter_bounds_reach_the_decoder`: `min_box_px` besar mengosongkan
   `last_detections()` sementara default tetap menghasilkan satu kotak sintetis.
6. Kotlin (di luar lingkup Rust ini) - preset `seimbang` 0,35 / `presisi` 0,45 di
   `EngineStartPlanner` + penyimpanan di `feature:settings`. Sampai itu ada, kedua
   pemanggil `EngineConfig(...)` (`EngineStartPlanner.kt`, `RustEngineWarmUp.kt`)
   meneruskan `null` supaya perilaku tidak berubah.

Bukti selesai untuk langkah Rust: gate hijau + test di atas ada. **Efek** tuning tetap
**belum diverifikasi** sampai tabel precision/recall Q-03 dari rekaman berlabel ada;
ambang default tidak boleh diubah hanya karena "kelihatan lebih baik".

**Status 2026-11-28.** Langkah 1-5 selesai dan terverifikasi di host: `cargo fmt` bersih,
`clippy --workspace --all-targets -D warnings` bersih (juga dengan `--features rtsp` dan
`--features cli`), workspace 322 test hijau, plus fixture ONNX nyata:
`a_threshold_above_the_planted_score_drops_the_box` (model 32) dan
`box_filter_bounds_reach_the_decoder` (model 640) lulus - jadi ambang dan filter memang
sampai ke decoder, bukan hanya tersimpan di config. Langkah 6 (preset Kotlin) dan angka
akurasi lapangan belum. Nomor baris di bagian **Bukti** mengacu ke kondisi sebelum
tambalan.

### Q-06 Tidak ada saringan ukuran/aspek kotak

**Gejala.** Kotak kecil 8x16 px atau kotak mendatar aneh tetap digambar; di layar
tampak seperti "deteksi hantu" yang berkedip.

**Bukti.** `ai-engine` mengembalikan setiap kotak di atas ambang confidence
(`postprocess.rs` decode + `nms.rs:46-61`), tanpa filter ukuran minimum maupun aspek.
Konsumsi di `multi_table.rs:92` hanya menyaring confidence, dan overlay menggambar apa
adanya (`HomeScreen.kt:552-565`).

**Tambalan.** Filter di `postprocess`/`detector` (bukan di Kotlin, supaya status dan
tampilan memakai himpunan kotak yang sama):

- `min_box_px` (default 12 px pada ruang **frame sumber**, setelah invers letterbox;
  nilainya sudah tersedia di `postprocess.rs:81-89`). Kotak lebih kecil dibuang.
- Tolak aspek ekstrem: `h/w < 0.35` atau `h/w > 8.0`. Jangan memakai rentang sempit:
  orang duduk dekat kamera bisa `h/w ~= 0.6`, dan orang berdiri sangat dekat bisa
  `h/w > 4`.
- Sertakan alasan penolakan di `tracing::debug!` (tanpa frame, tanpa kredensial) agar
  tuning tidak buta.

**Uji penjaga.** `tiny_box_is_dropped_before_nms`, `seated_person_box_is_kept`,
`horizontal_smear_is_rejected`, `min_box_px_is_configurable`,
`strided_decode_applies_the_same_filter`, `rejection_reason_names_the_failing_bound`.

**Bukti selesai (host 2026-11-28).** `ai-engine` sekarang punya `BoxFilter`
(`DEFAULT_MIN_SIDE_PX = 12`, `MIN_ASPECT = 0.35`, `MAX_ASPECT = 8.0`) yang dijalankan
di `postprocess::finish` **sebelum** NMS, jadi kotak yang digambar dan kotak yang
dihitung per meja berasal dari himpunan yang sama. `decode` dan
`decode_attribute_major` tetap 6 argumen (memakai `BoxFilter::default()`), sedangkan
`decode_with_filter` / `decode_attribute_major_with_filter` menerima batas eksplisit
untuk tuning terhadap rekaman. Penolakan dicatat `tracing::debug!` dengan
`width`/`height`/`confidence`/`reason` (`too_small`, `extreme_aspect`, `degenerate`),
tanpa piksel dan tanpa kredensial.

Gate: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
`cargo test --workspace` (42 test `ai-engine` termasuk 6 kasus baru) hijau, dan
`cargo ndk` untuk `arm64-v8a`/`armv7-linux-androideabi`/`x86_64` sukses.
**Belum diverifikasi:** efek pada footage pengguna (butuh Q-03 + model ONNX terpasang);
angka default 12 px / 0.35 / 8.0 masih tebakan terukur-sebagian, bukan hasil tabel
precision/recall.

### Q-07 Kotak berkedip karena tidak ada asosiasi antar-frame

**Gejala.** Orang yang sama berkedip ada/hilang setiap ~0,5-1 s, dan posisinya meloncat
beberapa piksel setiap poll. Di layar tampak "deteksi tidak stabil".

**Bukti.**

- Inferensi tidak menyimpan keadaan: setiap frame menghasilkan himpunan kotak baru
  (`ai-engine/src/detector.rs`, `Detector::detect`), dan `core-ffi` dulu mengganti
  seluruh daftar deteksi pada setiap frame (`StateInner::observe_frame`).
- Yang punya peredam hanya **status meja** (`DebounceConfig`, `api.rs:356-359`); kotak
  yang digambar tidak punya peredam apa pun.
- Pada 2 FPS, satu frame yang gagal mendeteksi (orang menunduk, tangan menutup badan)
  langsung menghapus kotaknya di layar.

**Tambalan.** Trek tampilan, bukan prediksi. Selesai di Rust; peredupan di overlay
Kotlin belum.

1. `core-ffi/src/tracking.rs` menyimpan trek antar-frame (`advance`): kotak yang cocok
   (`MATCH_IOU = 0,3`) diperbarui dengan `misses = 0`, kotak yang tidak cocok
   `misses += 1` dan tetap dipublikasikan selama `misses <= MAX_MISSES` (2 frame),
   lalu dibuang pada miss ketiga.
2. `AnalysedBox` (internal) mendapat `misses`; `DetectionBox` FFI mendapat
   `stale: Boolean` - satu-satunya field baru yang dilihat Kotlin.
3. Trek yang tidak terdeteksi **tidak digeser** dan mempertahankan `frame_seq` serta
   `captured_at_ms` dari frame terakhir orang itu benar-benar terlihat, sehingga
   `age_ms` terus naik dan kotak `stale` tidak menyamar sebagai kotak baru (Q-12).
4. Posisi trek yang cocok dihaluskan EMA `alpha = 0,5` (`SMOOTHING_ALPHA`); keyakinan
   tetap diambil dari frame terbaru.
5. Hitungan meja tetap memakai kotak mentah (`multi_table::statuses` lewat
   `observe_frame`), sehingga angka tidak ikut dihaluskan dan debounce tetap satu-satunya
   peredam status.

**Uji penjaga.** Semua di `core-ffi`; `ai-engine` tidak berubah.

- `tracking::tests::a_re_detected_person_keeps_one_box_and_resets_its_misses`,
  `an_unmatched_track_never_moves_on_its_own`,
  `two_people_keep_their_own_boxes_when_they_pass_each_other`,
  `smoothing_keeps_the_centre_within_15_percent_of_the_true_one`,
  `a_box_without_area_never_matches_anything`,
  `a_track_is_dropped_after_three_missed_frames`
- `state::tests::box_survives_one_missed_detection`,
  `box_disappears_after_three_missed_frames`, `stale_track_is_flagged`,
  `table_counts_use_raw_boxes_not_smoothed_tracks`
- `onnx_engine.rs` (bergantung `CCTV_ONNX_TEST_MODEL_640`) tetap hijau: model sintetis
  yang menanam satu orang tetap menghasilkan satu kotak.

**Status 2026-11-28.** Rust selesai. `cargo test --workspace` 331 test hijau (0 gagal),
`cargo clippy --workspace --all-targets -- -D warnings` dan `cargo fmt --check` bersih,
uji ONNX nyata hijau (`ai-engine --test onnx_inference` 5 test,
`core-ffi --test onnx_engine` 3 test) dengan fixture dari
`tools/onnx-test/gen_synthetic_model.py`. Yang **belum**: overlay Kotlin belum membaca
`stale` untuk meredupkan kotak (bagian Q-02 di `feature:home`), dan `MATCH_IOU` /
`MAX_MISSES` belum diuji pada rekaman berlabel Q-03 - angka 0,3 dan 2 frame adalah
titik awal, bukan hasil pengukuran.

### Q-08 Model selain 84-atribut ditolak, termasuk fine-tune person-only

**Gejala.** Mengganti model (mis. hasil fine-tune satu kelas, atau ekspor dengan NMS
built-in) membuat engine `Error` total; pengguna hanya melihat "—".

**Bukti.**

- `postprocess.rs:18-20` mengunci `VALUES_PER_PREDICTION = 84`
  (4 koordinat + 80 kelas COCO).
- `detector.rs:35-55` hanya menerima `[1, 84, N]` dan `[1, N, 84]`; bentuk lain
  menghasilkan `AiError::Output`. Test yang ada justru menegaskan `[1, 85, N]`
  ditolak (`detector.rs:383-385`) dan bentuk `[1, 300, 6]` tidak didukung sama sekali.

**Tambalan.**

1. Turunkan `attributes` dari bentuk keluaran, bukan konstanta: `[1, A, N]` dengan
   `A >= 5` -> `A - 4` kelas. Kelas 0 tetap `person`.
2. Dukung `attributes = 5` (fine-tune person-only, tanpa skor kelas) dan
   `[1, 300, 6]` (x1, y1, x2, y2, score, class; NMS sudah dilakukan model).
3. Pesan error harus menyebut bentuk yang ditemukan dan bentuk yang didukung, mis.
   `"unsupported output shape [1, 85, 8400]: expected [1, 4+classes, N] or [1, N, 4+classes]"`,
   supaya pengguna tidak menebak.
4. `configure_model`/`start` mencetak (via `tracing`) `attributes` dan `predictions`
   hasil deteksi bentuk — bukan isi frame, bukan path lengkap.

**Uji penjaga.** `person_only_model_with_five_attributes_decodes`,
`torchvision_e2e_output_decodes_without_nms`, `unsupported_shape_error_names_the_shape`,
`attribute_major_and_prediction_major_agree` (sudah ada, jangan diregresikan).

**Bukti selesai (host 2026-11-28).**

- `postprocess.rs`: `BOX_ATTRIBUTES = 4`, `VALUES_PER_PREDICTION = 84` (tetap, hanya
  sebagai nilai default), `MAX_ATTRIBUTES = 1024`. Tipe baru `BoxEncoding`
  (`Center` / `Corners`) dan `DecodeLayout { attributes, encoding, class_index,
  person_class, filter }` dengan konstruktor `coco()`, `for_attributes(n)`,
  `nms_corners()`, `with_filter()`, dan `validate()`. Dekoder baru
  `decode_with_layout` (prediction-major, `chunks_exact`) dan
  `decode_attribute_major_with_layout` (strided, tanpa alokasi transpose).
  `decode`, `decode_with_filter`, `decode_attribute_major`,
  `decode_attribute_major_with_filter`, dan `transpose_attribute_major` tetap
  ada sebagai pembungkus layout COCO, jadi pemanggil lama tidak berubah.
- `detector.rs`: `output_spec(shape)` menurunkan `attributes` dari bentuk.
  Urutan klasifikasi: `[1, N, 6]` (NMS bawaan) lebih dulu, lalu sumbu yang
  nilainya `5..=1024` dianggap sumbu atribut; bila kedua sumbu masuk rentang,
  `[1, A, N]` menang karena itu ekspor Ultralytics kanonik. Bentuk lain
  menghasilkan `AiError::Output` yang menyebut bentuk ditemukan dan bentuk
  didukung. `from_file` memvalidasi bentuk statis sekali saat load (model yang
  tidak bisa didekode ditolak sebelum frame pertama), dan layout dicatat lewat
  `tracing::info!` berisi `attributes`, `predictions`, `layout`, `encoding` -
  tanpa isi frame dan tanpa path.
- Uji penjaga: `person_only_model_with_five_attributes_decodes`,
  `torchvision_e2e_output_decodes_without_nms`,
  `unsupported_shape_error_names_the_shape`, `extra_class_rows_are_accepted_now`
  (85 atribut kini positif), dan `attribute_major_and_prediction_major_agree`
  (nama baru untuk test kesetaraan strided vs transpose).
- Gate: `cargo test -p ai-engine` 46 unit + 3 integrasi hijau; `cargo test
  --workspace` hijau; `cargo clippy --workspace --all-targets -- -D warnings`
  bersih; `cargo ndk` untuk `arm64-v8a`, `armv7-linux-androideabi`, `x86_64`
  sukses. Fixture ONNX nyata: model 84-atribut dan model 5-atribut
  (`gen_synthetic_model.py --attributes 5`) sama-sama jalan lewat
  `--test onnx_inference`, dan `core-ffi --test onnx_engine` (640) hijau.
  `tools/onnx-test/gen_synthetic_model.py` mendapat opsi `--attributes`.
- Belum diverifikasi: model fine-tune nyata dari pengguna (belum ada berkas
  `.onnx`-nya) dan perilaku di perangkat.

### Q-09 Perubahan cahaya membuat semua meja `unknown` walau deteksi benar

**Gejala.** Pagi/sore, saat lampu dinyalakan, atau saat pintu depan dibuka, muncul banner
"kalibrasi ulang" dan seluruh meja jadi `unknown` - padahal kotak orang tetap tergambar
dan benar. Pengguna menyimpulkan "deteksi rusak" padahal yang menolak adalah penjaga
pandangan kamera.

**Bukti.**

- Ambang: `spatial-engine/src/view.rs:276-282` (`cell_threshold = 0.06` luminans,
  `enter_fraction = 0.50`, `exit_fraction = 0.25`).
- Baseline diambil dari frame ber-analisis **pertama** dan **tidak pernah diperbarui**
  (`view.rs:369-373`); `reset` hanya dipanggil saat peta meja baru dipasang
  (`view.rs:356-363`).
- Begitu `moved`, seluruh status dipaksa `unknown`: `core-ffi/src/state.rs:314-316`.

**Tambalan.**

1. Histeresis waktu: `moved` baru menyala setelah `>= 3` frame berurutan di atas
   `enter_fraction` (bidang `streak` baru di `ViewChangeGuard`), dan padam setelah
   `2` frame di bawah `exit_fraction`.
2. Baseline adaptif lambat hanya saat `!moved`: `baseline = 0.95 * baseline + 0.05 *
   current` per sel. Perubahan cahaya bertahap (matahari bergeser) tidak lagi menumpuk
   menjadi "kamera dipan"; perubahan mendadak (dipan) tetap melewati 50% sel dalam satu
   frame sehingga tetap terdeteksi.
3. `ViewVerdict` menambah `changed_fraction` ke `EngineHealth` agar banner bisa
   menjelaskan angkanya, bukan hanya "berubah".

**Uji penjaga.** `gradual_brightness_ramp_does_not_flag_moved`,
`instant_pan_beyond_half_the_grid_flags_moved`,
`short_spike_below_three_frames_does_not_flag_moved`,
`baseline_adapts_only_while_not_moved`, `adaptation_can_be_disabled`.

**Bukti selesai (host 2026-11-28).** `ViewChangeConfig` sekarang membawa
`enter_frames = 3`, `exit_frames = 2`, `baseline_adapt = 0.05`. `ViewGuard::observe`
menghitung streak di kedua arah: satu frame terang (lampu, pintu, lampu mobil) tidak
lagi membekukan peta, dan satu frame yang cocok tidak langsung melepasnya.
`ViewSignature::blend_towards` menggeser baseline 5% per frame **hanya** saat view masih
dipercaya dan frame itu sendiri di bawah `enter_fraction`; tanpa syarat kedua, frame pan
yang sedang membentuk streak ikut tercampur ke baseline dan view bisa terjebak di atas
`exit_fraction` selamanya. `baseline_adapt = 0` mematikan adaptasi dan mengembalikan
perilaku beku. `EngineHealth.view_changed_fraction` meneruskan angka
`ViewVerdict.changed_fraction`; banner Home menyebut persentasenya.

Gate: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
`cargo test --workspace` (43 `ai-engine` + 50+17 `spatial-engine` + 77 `core-ffi` hijau),
`cargo ndk` tiga ABI, `:app:testDebugUnitTest` + `:feature:home:testDebugUnitTest` 872
test hijau, `:app:lintDebug` 0 error.
**Belum diverifikasi:** ambang 3/2 frame dan bobot 0.05 belum diuji pada rekaman nyata
dengan matahari/pintu warung; kalau Q-03 menghasilkan tabel, angka ini yang pertama
dikoreksi.

**Catatan.** Ini bukan pekerjaan CV, tetapi gejalanya paling sering dilaporkan sebagai
"deteksi rusak". Kerjakan sebelum menyimpulkan model salah.

## 3. P2 - resolusi tangkapan, kerapian visual, kontrak waktu

### Q-10 Orang jauh hanya menempati 20-30 px di input model

**Gejala.** Orang di meja paling belakang tidak terdeteksi atau berkedip, sedangkan orang
dekat kamera selalu terdeteksi. Meja belakang selamanya "kosong".

**Bukti.**

- Tangkapan selalu 640 px lebar: `EngineFramePump.kt:140` memakai `MAX_FRAME_WIDTH`
  (`:234`), jadi stream 1080p diturunkan ke 640x360 sebelum masuk model 640x640.
  Orang yang hanya 40 px di tangkapan tetap 40 px di input model; orang 15 px di
  tangkapan tetap 15 px - di bawah ambang praktis YOLOv11n (~16-24 px).
- Batas berbasis **lebar** juga membuat stream portrait menghasilkan tangkapan
  640x1138 (2,2 MB per frame) tanpa menambah detail pada orang.

**Tambalan (eksperimen, bukan default).** Batasi sisi **panjang**, bukan lebar; lalu:

1. Naikkan batas ke 960 px hanya untuk perangkat `arm64` dengan `MemTotal >= 6 GB`
   (sumber: diagnostik onboarding / `DeviceLoadSource` yang sudah ada). Syarat diterima:
   p50 `pushFrame` <= 300 ms dan laju analisis >= 3 FPS.
2. Alternatif yang lebih murah dan lebih tepat: kirim crop RoI. Rust sudah memegang
   semua poligon (`tables_json`), jadi engine bisa meminta Kotlin menangkap crop
   gabungan bounding-box meja (mis. 60% frame) dan memetakan kotak kembali ke koordinat
   penuh. Ini menaikkan kepadatan piksel orang tanpa menaikkan biaya inferensi.
   Konsekuensinya kontrak frame berubah (rect + offset) -> butuh ADR baru, jangan
   dikerjakan diam-diam.
3. Jangan memakai `input_size = 960` tanpa mengukur; biaya naik ~2,25x dan bench
   2026-11-27 sudah menunjukkan 3-4 FPS pada 640.

**Uji penjaga.** `target_capture_size_caps_long_side`,
`capture_size_keeps_video_aspect`, dan test host untuk pemetaan crop -> koordinat penuh
(jika opsi 2 diambil).

**Bukti selesai.** Tabel pada dua perangkat: p50 inference, FPS analisis, dan
precision/recall dari Q-03 untuk 640 vs 960 vs RoI. **Belum diverifikasi.**

### Q-11 Overlay memakai garis 2 piksel dan tidak menunjukkan keyakinan

**Gejala.** Di layar 1080p garis kotak hampir tidak terlihat atau tebalnya tidak rata;
kotak 0,36 (ragu) dan 0,92 (yakin) terlihat sama, sehingga pengguna tidak bisa menilai
sendiri mana yang layak dipercaya.

**Bukti.** `HomeScreen.kt:552-565`: `Stroke(width = 2f)` (`:555`) - satuan piksel mentah,
bukan `dp`; tidak ada label confidence; tidak ada `clipRect`, sehingga kotak bisa
menggambar keluar area video.

**Tambalan.** `Stroke(width = with(LocalDensity.current) { 2.dp.toPx() })`;
`canvas.clipRect(Size(size.width, size.height))` sebelum menggambar; opasitas kotak
diturunkan dari confidence (mis. `alpha = 0.35 + 0.65 * confidence`); label teks
opsional yang bisa dimatikan (default mati, supaya layar warung tidak ramai). Jangan
memakai warna berbeda per kelas: hanya ada satu kelas (`person`).

**Uji penjaga.** `overlay_stroke_is_density_scaled`, `overlay_clips_boxes_to_video_area`,
`low_confidence_box_is_dimmer` (Compose test dengan state tetap).

### Q-12 Kotak tidak membawa stempel waktu frame

**Gejala.** Tidak ada cara mengetahui apakah kotak yang terlihat berasal dari frame
sekarang atau 1 detik lalu; Q-02 hanya bisa ditambal dengan menghitung umur dari waktu
poll, bukan dari waktu tangkap.

**Bukti.** `EngineHealth` punya `last_inference_age_ms`, tetapi tidak ada field yang
mengikat satu kotak ke frame tertentu. Sebelum tambalan, isi payload hanya
`x/y/width/height/confidence` (`core-ffi/src/state.rs`, `normalized_box`), sehingga
setiap pembacaan `lastDetections()` mengembalikan koordinat tanpa identitas frame.

**Tambalan (sisi Rust selesai 2026-11-28).**

1. `DetectionBox` (`core-ffi/src/types.rs`) sekarang membawa:
   - `frame_seq: u64` - nomor frame teranalisis dalam sesi, mulai `1`, naik satu per
     inferensi sukses. Untuk kotak terbaru nilainya sama dengan
     `EngineHealth.frames_sampled`; dua pembacaan dengan `frame_seq` sama berarti satu
     hasil inferensi, bukan dua.
   - `captured_at_ms: u64` - jam monotonik sesi saat frame itu sampai ke engine
     (`session_time()` di awal `push_frame`), **bukan** saat kotak dibaca.
   - `age_ms: u64` - `jam sesi sekarang - captured_at_ms`, dihitung Rust di
     `StateInner::detection_payload()` saat payload disajikan.
2. Engine menyimpan `AnalysedBox` (koordinat ternormalisasi + `frame_seq` +
   `captured_at: Duration`) dan `analysis_seq`; konversi ke milidetik hanya terjadi di
   `AnalysedBox::to_payload`. Pembacaan berulang tidak pernah men-stempel ulang kotak,
   dan umur tetap benar walau pemanggil memakai `EngineHealth` dari cache 900 ms
   (`RustEngineStatusSource.SNAPSHOT_TTL_MS`).
3. Kotak tetap dikosongkan saat stream mati (`mark_stream_down`) dan saat frame kosong
   benar-benar dianalisis; `analysis_seq` tetap naik, sehingga frame kosong tidak bisa
   menyamar sebagai frame lama yang masih berisi orang.

Deviasi dari rencana awal: dokumen ini semula meminta `captured_at_ms: i64` epoch dan
Kotlin menghitung `now - captured_at_ms`. Yang dipakai `u64` jam monotonik sesi karena
kontrak `types.rs` melarang jam dinding di dalam engine (jam perangkat bisa berubah saat
runtime) dan `EngineHealth.uptime_ms` sudah ada di snapshot yang sama. Field `age_ms`
dibuat agar Kotlin tidak perlu mencocokkan dua sumber waktu sama sekali: satu kotak
cukup membawa umurnya sendiri.

**Uji penjaga.**

- `core-ffi` unit (`state.rs`): `detections_carry_the_frame_sequence_they_came_from`,
  `box_age_is_measured_from_capture_not_from_poll`,
  `payload_age_is_derived_from_the_capture_stamp_at_read_time` (termasuk jam sesi yang
  mundur -> `age_ms = 0`, bukan angka `u64` hampir maksimum), dan
  `an_empty_frame_advances_the_sequence_without_leaving_boxes`.
- `core-ffi/tests/onnx_engine.rs` (butuh `CCTV_ONNX_TEST_MODEL_640`):
  `analysed_boxes_carry_the_frame_they_came_from` - `frame_seq == health.frames_sampled`,
  `captured_at_ms <= uptime_ms`, dan frame yang ditolak sampler tidak menaikkan
  `frame_seq` maupun mengubah `captured_at_ms`.
- Gate 2026-11-28: `cargo clippy -p core-ffi --all-targets -- -D warnings` hijau,
  `cargo test -p core-ffi` 81 unit + 7 integrasi hijau (dua test ONNX berhenti sebagai
  `skipped` karena fixture model tidak dipasang).

**Sisa pekerjaan (Kotlin, bagian Q-02).**

- `DetectionRect`/`HomeUiState.detections` memetakan `box.ageMs`, bukan menghitung umur
  dari `lastAnalysisAgeMillis`.
- `HomeViewModel` memakai jadwal 250 ms khusus kotak (status tetap 1 000 ms) dan
  `DetectionOverlay` menyembunyikan seluruh kotak saat `ageMs > 1200`.
- `tools/device-e2e.ps1` mencetak `ageP50`/`ageP95` dari `ageMs` selama orang berjalan.

**Bukti selesai.** Angka `ageP50`/`ageP95` untuk kamera nyata belum ada, jadi Q-12 baru
tertutup di sisi Rust dan **belum** di `docs/status.md`.

### Q-13 Kotak hijau muncul di luar area yang dipetakan

**Gejala.** Orang yang lewat di trotoar, tetangga di teras sebelah, atau bayangan di
kolom gang tetap digambar kotak hijau walaupun tidak ada meja di sana. Pengguna membaca
itu sebagai "deteksinya salah", padahal mesin hanya menghitung di dalam poligon yang
dipetakan. Tidak ada batas biru yang memberi tahu area mana yang benar-benar dianalisa.

**Bukti.** Sebelum tambalan, `StateInner` hanya menyimpan `config` dan `spatial`;
`DetectionBox` (`core-ffi/src/types.rs`) membawa `x/y/width/height/confidence/frame_seq/
captured_at_ms/age_ms/stale` tanpa penanda apakah orangnya di dalam lingkup. `spatial-engine`
juga tidak punya konsep lingkup: `TableConfigSet` menyimpan poligon per meja, dan hanya
`multi_table::totals_for` yang memakainya. Di Kotlin, garis biru dihitung ulang dari
salinan mapping di Room (`DetectionOverlayStyle`, commit `d2a5b2d`), sehingga angka yang
dihitung Rust dan garis yang digambar bisa berbeda sumber.

**Tambalan (sisi Rust selesai 2026-11-28).**

1. `spatial-engine/src/region.rs` baru: `AnalysisRegion { x, y, width, height }` dalam
   ruang ternormalisasi, `AnalysisRegion::from_points`, `TableConfigSet::analysis_region`,
   `SpatialEngine::analysis_region`. Lingkup **diturunkan dari poligon yang digambar
   pengguna**, bukan dari hasil detektor: tidak ada auto-detect, tidak ada ROI yang
   ditebak mesin.
2. `core-ffi` menyimpan lingkup sekali per `set_tables` (`StateInner::region`), lalu:
   - record `AnalysisRegion` FFI + `Engine::analysis_region()` untuk garis biru;
   - `DetectionBox.in_region` diisi sekali per frame oleh `analysed_box` memakai titik
     tengah kotak ternormalisasi (ruang yang sama dengan poligon), jadi "kotak hijau di
     dalam garis biru" dan "dihitung ke meja" memakai geometri yang sama;
   - `crate::tracking` meneruskan bendera: kotak yang dideteksi ulang mengambil jawaban
     frame terbaru, kotak yang hanya ditahan (`stale`) memakai jawaban frame saat orang
     itu benar-benar terlihat, sehingga satu frame meleset tidak memindahkan kotak masuk
     atau keluar lingkup.
3. Alasan memakai **persegi pembatas**, bukan gabungan poligon: gabungan beberapa
   footprint punya lubang dan lekuk yang di layar terbaca sebagai peta kedua, sementara
   persegi selalu superset - orang yang duduk di meja tidak mungkin jatuh di luar garis.
   Orang di dalam persegi tetapi di luar semua poligon tetap `in_region = true` (dia
   memang di area yang dipetakan) namun tetap tidak menempati meja mana pun, karena
   hitungan meja memakai `multi_table`, bukan persegi ini.
4. Tidak ada tabel yang dipetakan -> `analysis_region()` mengembalikan `None` dan Kotlin
   harus menggambar tanpa garis biru. Lingkup selebar frame tidak pernah dilaporkan
   sebagai "menonton semuanya" kalau memang belum ada mapping.

**Uji penjaga.**

- `spatial-engine` (`region.rs`): `the_region_covers_every_mapped_table`,
  `a_person_between_two_far_tables_is_inside_the_scope_but_at_no_table`,
  `a_person_outside_the_mapped_scope_is_not_inside_it`,
  `a_configuration_without_tables_has_no_scope`,
  `a_full_frame_mapping_reports_the_full_frame`; plus
  `tests::engine_publishes_the_scope_the_user_mapped` di `lib.rs`.
- `core-ffi` (`state.rs`): `the_engine_reports_the_scope_of_the_mapped_tables`,
  `only_people_inside_the_mapped_scope_are_flagged_in_region`.
- `core-ffi` (`tracking.rs`): `a_kept_track_keeps_the_scope_verdict_from_the_frame_it_was_seen_in`,
  `a_re_detected_track_takes_the_scope_verdict_of_the_newest_frame`.
- Gate 2026-11-28: `cargo test -p spatial-engine -p core-ffi` hijau (100 unit
  `core-ffi` + integrasi, termasuk seluruh test `region`).

**Sisa pekerjaan (Kotlin).**

- `HomeViewModel` mengambil `engine.analysisRegion()` dan `HomeState` membawa lingkup itu
  ke `DetectionOverlay`, menggantikan perhitungan di `DetectionOverlayStyle` yang membaca
  Room, supaya garis biru dan angka meja tidak bisa berbeda.
- `DetectionOverlay` tidak menggambar kotak dengan `inRegion == false` (atau
  menggambarnya abu-abu tipis), dan tetap memakai `box.stale` untuk meredupkan kotak yang
  ditahan Q-07.
- Uji Compose: satu kotak di luar lingkup tidak muncul di kanvas; lingkup `null` berarti
  tidak ada garis biru.

**Bukti selesai.** Screenshot/uji kanvas di perangkat yang menunjukkan hanya orang di
dalam lingkup yang digambar hijau, ditambah angka Q-03 sebelum/sesudah (FP dari pejalan
kaki di luar mapping harus turun tanpa menurunkan recall orang di meja).

## 4. Urutan kerja

| Urutan | Butir | Kenapa di sini |
| --- | --- | --- |
| 1 | Q-01 | Murah, tanpa model, langsung menghapus galat 12-34% lebar layar |
| 2 | Q-02 + Q-12 | Menghapus kotak "hantu" yang tertinggal; Q-12 membuat umurnya terukur |
| 3 | Q-03 | Tanpa angka, semua tuning berikutnya tidak bisa dinilai |
| 4 | Q-09 | Gejala "deteksi rusak" yang paling sering ternyata bukan soal deteksi |
| 5 | Q-05 + Q-06 | Ambang dan saringan; efeknya bisa diukur dengan Q-03 |
| 6 | Q-07 | Stabilitas tampilan |
| 7 | Q-04 | Butuh rekaman 10 menit; jangan ubah default tanpa tabel |
| 8 | Q-08 | Kesiapan mengganti model |
| 9 | Q-10 | Eksperimen resource; butuh perangkat dan angka |
| 10 | Q-11 | Kerapian visual |

Q-01 dan Q-02 tidak boleh digabung dalam satu commit: keduanya bisa diuji terpisah, dan
Q-01 tidak menyentuh Rust sama sekali.

Status 2026-11-28: **sisi Rust Q-12 sudah selesai** (`frame_seq` + `captured_at_ms` +
`age_ms` pada `DetectionBox`, lihat §3). Langkah 2 sekarang tinggal Q-02 di Kotlin yang
memakan `box.ageMs`; angka `ageP50`/`ageP95` baru bisa diisi setelah itu.

## 5. Cara membuktikan

```powershell
# Rust (dari rust-core/)
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo test -p spatial-engine
cargo test -p core-ffi

# Android (dari android/, lewat wrapper gradle-run)
./gradlew :feature:home:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug

# Perangkat
tools/device-e2e.ps1            # FPS + tables + modelReady + ageP50/ageP95 (setelah Q-12)
tools/device-soak.ps1           # regresi memori setelah Q-07/Q-10
```

Untuk Q-03, harness dijalankan di host dengan env yang diisi manual (frame + label hasil
`adb pull` dari rekaman sendiri), bukan bagian dari `cargo test --workspace` default.
Frame, label, dan model tidak di-commit.

## 6. Sengaja tidak dilakukan

- **Memprediksi posisi kotak** (kalman/optical flow) saat deteksi tidak ada. Kotak harus
  berasal dari inferensi; lebih baik hilang sebentar daripada menunjuk meja yang salah.
- **Mengganti `resize_mode` menjadi `fill`/`zoom`** supaya "kelihatan penuh". Itu
  memotong bagian frame dan merusak pemetaan piksel yang dianalisis
  (`TextureView.getBitmap` menangkap konten yang sudah dipotong).
- **Menambah dependensi CV baru** (OpenCV, tracker pihak ketiga, model kedua). Tidak ada
  anggaran APK/RAM untuk itu sebelum Q-10 terbukti perlu.
- **Menurunkan ambang confidence supaya "semua orang terdeteksi"**. Itu menukar FN
  dengan FP dan membuat meja terbaca terisi padahal kosong.
- **Mengubah ADR atau `docs/acceptance.md`.** Q-01 dan Q-04 layak menjadi ADR baru
  (geometri overlay + anchor orang), tetapi perubahan keputusan harus disampaikan ke
  pengguna lebih dulu.
- **Meng-commit frame, video, atau model uji.** Semua bukti visual disimpan di luar repo.




