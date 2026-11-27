# Performa & strategi FPS

Dokumen ini menjawab pertanyaan "bisa tidak 24 FPS di HP?" dan menetapkan target FPS
yang realistis. Angka target di sini adalah **hipotesis yang harus diukur**, kecuali
pengukuran perangkat nyata di §6.3.1; keduanya bukan janji.

## 1. Ringkasan keputusan

| Pertanyaan | Jawaban |
| --- | --- |
| Mungkinkah 24 FPS inferensi di HP? | Secara teknis bisa, tetapi **tidak diperlukan** dan boros |
| Target operasional | **2-5 FPS** inferensi, 25-30 FPS input |
| Target eksperimen | 10-15 FPS pada perangkat kelas atas, sebagai benchmark |
| Yang menentukan biaya | Resolusi input model dan jumlah thread, bukan FPS stream |

Alasan 2-5 FPS cukup: status meja berubah dalam hitungan detik, bukan milidetik. Orang
butuh > 2 detik untuk duduk; debounce 2-3 detik sudah menutupi seluruh perubahan nyata.

## 2. Anggaran waktu per frame

Pada 2 FPS, satu frame punya 500 ms. Pada 24 FPS, hanya 41.7 ms. Perkiraan kasar untuk
perangkat menengah `arm64-v8a`:

| Tahap | 640x360 | 416x416 | Catatan |
| --- | --- | --- | --- |
| Decode H.264 | 3-8 ms | 3-8 ms | Tergantung resolusi stream |
| Resize + letterbox | 1-3 ms | 1-2 ms | Bisa digabung saat decode |
| Inferensi YOLOv11n (CPU) | 25-60 ms | 12-30 ms | 4 thread; NNAPI bisa lebih cepat/lebih lambat |
| NMS + filter | < 1 ms | < 1 ms | Jumlah kotak kecil |
| Spasial + debounce | < 1 ms | < 1 ms | Puluhan meja |
| **Total** | **30-72 ms** | **17-41 ms** | Belum termasuk thermal throttling |

Kesimpulan: pada resolusi 416x416, 24 FPS secara teori mungkin di perangkat kelas atas,
tetapi margin terhadap throttling sangat tipis. Pada 640x360, 24 FPS realistis hanya di
perangkat flagship dengan NNAPI/GPU.

## 3. Strategi mencapai FPS tinggi (jika benar-benar dibutuhkan)

1. **Kecilkan input model**, bukan stream. Preview tetap 720p; inferensi memakai
   416x416 atau 640x360.
2. **Turunkan presisi** bila EP mendukung (FP16/INT8), dengan uji akurasi ulang.
3. **Gunakan NNAPI/XNNPACK** lewat `ort`; ukur, karena NNAPI bisa lebih lambat untuk
   model kecil.
4. **Decode sekali, pakai banyak**: hindari decode ulang per frame.
5. **Batasi thread** agar tidak berebut core dengan UI.
6. **Frame terbaru saja**: bila inferensi lambat, lewati frame (drop), jangan antre.
7. **Matikan preview** saat layar mati untuk menghemat daya.

Yang **tidak** membantu: menaikkan FPS stream RTSP; itu hanya menambah beban decode.

## 4. Efek samping FPS tinggi

| Naik dari 3 ke 24 FPS | Dampak |
| --- | --- |
| CPU/GPU | ~8x beban; throttling lebih cepat |
| Suhu baterai | Naik signifikan; risiko > 48 C |
| Baterai | Bisa habis 3-5x lebih cepat |
| Akurasi status | **Tidak** naik signifikan karena debounce yang menentukan |
| Event ke UI | Sama saja (event-driven, hanya saat berubah) |
| Bandwidth | Tidak berubah (tidak ada video ke VPS) |

## 5. Desain adaptif (disarankan)

Engine menyesuaikan FPS berdasarkan kondisi, bukan angka tetap:

| Kondisi | FPS inferensi |
| --- | --- |
| Normal, layar mati | 2 |
| Normal, layar menyala | 3-4 |
| Ada perubahan status terdeteksi | 5 selama 10 detik |
| Suhu > 42 C atau termal `MODERATE` | 2 |
| Suhu > 48 C atau termal `SEVERE` | 1 |
| RAM bebas < 150 MB | 1 dan lepas buffer lama |

Ambang ini sinkron dengan [`monitoring.md`](monitoring.md) §2.4.

## 6. Cara mengukur

Yang harus dicatat, bukan dikira-kira:

- `inference_ms` p50/p95 per resolusi input (416, 640) dan per EP (CPU, NNAPI, XNNPACK).
- `fps_infer` aktual selama 30 menit, bukan 10 detik pertama.
- Suhu baterai dan `thermal_status` tiap 60 detik.
- RSS dan RAM bebas tiap 60 detik (deteksi kebocoran).
- Jumlah frame yang di-drop.

Alat: `tracing` + heartbeat ke VPS (lihat `data-contracts.md` §5), plus harness perangkat di
`tools/` yang mencetak tabel CSV agar bisa dibandingkan antar perangkat:
`tools/device-bench.ps1` (p50/p95 per resolusi, `cctv-benchmark.txt`),
`tools/device-e2e.ps1` (laju 10/15/24 FPS, `cctv-dev-e2e.txt`), dan
`tools/device-soak.ps1` (CSV 60 detik: PSS, RSS, CPU, suhu, termal, `cctv-soak.csv`).

### 6.1 Protokol uji termal

Uji termal tidak boleh disimpulkan dari 10 detik pertama. Urutan minimum:

1. Dinginkan perangkat sampai suhu baterai < 35 C.
2. Jalankan stream dan inferensi pada profil yang diuji selama 30 menit.
3. Catat `battery_temp_c`, `thermal_status`, FPS input/inferensi, p95 latensi, RSS,
   dan jumlah frame drop setiap 60 detik.
4. Ulangi dengan casing terpasang dan layar mati, karena kondisi warung berbeda dari
   meja developer.
5. Hentikan uji jika suhu > 48 C atau OS melaporkan `SEVERE`; turunkan FPS, jangan
   menaikkan fan atau mengabaikan peringatan.

Ambang FPS adaptif di §5 harus dipicu oleh data ini. Jangan menurunkan FPS hanya
berdasarkan suhu ruangan atau tebakan.

### 6.2 Kuantisasi model

FP16 dan INT8 adalah **eksperimen**, bukan default. Sebelum dipakai:

- ekspor model dengan input, preprocessing, dan label yang sama;
- verifikasi checksum model sumber dan hasil kuantisasi;
- jalankan set frame validasi yang sama pada FP32, FP16, dan INT8;
- bandingkan precision/recall `occupied` dan false `available` terhadap
  [`acceptance.md`](acceptance.md) §2;
- catat backend `ort`, ukuran model, waktu muat, RAM puncak, dan p95 latensi;
- rollback ke model sebelumnya bila salah satu ambang akurasi turun.

Penurunan latensi tanpa data akurasi bukan alasan untuk mengganti model. Model
kuantisasi tidak boleh didistribusikan sebelum ada checksum, lisensi, dan prosedur
rollback di [`release.md`](release.md).

### 6.3 Yang sudah diterapkan di kode (2026-11-26)

Tujuh hal berikut sudah ada di kode dan lulus gate host; **angka di perangkat belum diukur**.

| Perubahan | Efek yang diharapkan | Bukti |
| --- | --- | --- |
| `PreprocessScratch` di `ai-engine` | Tensor `3*size*size` f32 dan tabel sampling per sumbu dipakai ulang; tidak ada lagi alokasi ~4.9 MB per frame 640x640, dan lookup sumber tidak dihitung ulang | `ai-engine` 35 unit test + 2 test integrasi ONNX hijau, termasuk uji bahwa hasilnya identik dengan jalur alokasi lama untuk 97x61@64, 640x360@320, 168x168@96 |
| Sesi ORT: `intra_threads` dibatasi 4, `inter_threads` 1, parallel execution mati, spinning intra/inter mati, dan worker Android di-spawn oleh `BackgroundThreadManager` dengan nice 10 (`Process.THREAD_PRIORITY_BACKGROUND`) | Worker tidak berputar saat menganggur dan selalu kalah prioritas dari aplikasi yang dibuka pengguna, sehingga CPU tidak direbut ketika pengguna berpindah aplikasi | `cargo clippy`/`cargo test` hijau; default thread punya unit test batas 1..=4; 2 unit test `thread_manager`; cross-compile tiga ABI hijau |
| Input size mengikuti model (`[1,3,H,W]` statis, H kelipatan 32, 32..1280) | Model 416 tidak lagi dibuang ke tensor 640 (yang membuat ORT gagal atau lambat) | 2 unit test `input_size_from_shape` (9 skenario shape) + 1 test integrasi model 640 |
| `EngineFramePump` memakai satu thread khusus `THREAD_PRIORITY_BACKGROUND`, buffer `IntArray`/`ByteArray` dipakai ulang | Packing frame tidak lagi di `Dispatchers.Default` bersama UI; steady-state tidak lagi mengalokasikan heap tiap tick | `:app:testDebugUnitTest` 157 test hijau (9 untuk pump + 4 `Rgb888`) |
| `VideoCaptures.captureInto` memakai ulang `Bitmap` target dan `androidx.core.graphics.createBitmap` | Capture 2 FPS tidak lagi mengalokasikan bitmap baru tiap tick bila ukuran texture tidak berubah; bitmap recycled tidak pernah dipakai ulang | 4 test `VideoCaptures`/`Bitmap` di `EngineFramePumpTest` |
| Profil `lite` menurunkan laju inferensi ke 1.5 FPS lewat `EngineStartPlanner.withInferenceFps` yang dipilih `RustCameraSession` dari `OnboardingStore` | HP low-spec mendapat ~25% lebih sedikit inferensi; laju tetap di atas debounce 2 dtk sehingga meja tidak terasa lambat | 2 test `EngineStartPlannerTest` (cap `lite` dan batas band `docs/device-compatibility.md`) |
| `InferenceCadence` menurunkan interval pump ke 1 FPS saat `PowerManager` melapor `SEVERE`+ atau RAM bebas < 150 MB, dibaca tiap tick lewat `DeviceLoadSource` | Saat HP panas atau RAM sempit, inferensi otomatis memakai separuh CPU; ambangnya persis §5 dokumen ini dan `monitoring.md` §2.4, bukan angka baru | 10 test `InferenceCadenceTest` (termasuk `MODERATE` tetap 2 FPS dan batas RAM tepat 150 MB) |
| `InferenceCadence.paceDelayMillis` mengurangi waktu kerja tick dari interval (dan tidak pernah menambah delay saat kerja > interval) | Pacing tidak lagi menambah delay di atas interval; saat kerja > interval loop berjalan back-to-back tanpa antre. **Bukan** jaminan 24 FPS: `push_frame` sinkron dan inferensi ~216 ms membatasi throughput ke ~4 FPS | 5 unit test pacing (termasuk 41 ms - 25 ms = 16 ms) + 1 test anti-spin saat engine tidak siap |
| `InferenceBudget` meneruskan FPS sesi ke pump; batas keras core-ffi naik dari 10 ke 24 FPS | Pump tidak lagi terkunci 2 FPS, sehingga hard test 10-15/24 FPS bisa **diminta**; throughput nyata tetap dibatasi biaya inferensi (~4 FPS). Laju diturunkan setengah saat panas atau RAM sempit | 6 test `InferenceBudgetTest` + 2 test `clamp_inference_fps` di `core-ffi` |

| `EngineAssetPaths` menerima soname `libonnxruntime.so` bila `nativeLibraryDir` kosong | Android 15+ (`targetSdk 36`) memetakan native library langsung dari APK dan **tidak** mengekstrak `libonnxruntime.so`; jalur lama mengembalikan `null` sehingga warm-up selalu `ONNX_INIT`. Resolver memuat soname lewat linker proses, lalu Rust menerima nama itu dan `dlopen` memakai mapping yang sama - tanpa menyalin 36 MB ke storage internal | `EngineAssetPathsTest` 3 test (file terekstrak menang, direktori kosong -> soname, load gagal -> `null`) + `runtime.rs` 3 test (`is_library_soname` hanya menerima nama berkas polos) |

Bukti host (2026-11-27): dua test integrasi `ai-engine` (fixture 32 dan 640) dan satu test end-to-end `core-ffi` lulus dengan ONNX Runtime asli; fixture sintetis hanya membuktikan jalur runtime/tensor, bukan akurasi model.

#### 6.3.1 Pengukuran perangkat nyata (2026-11-27)

Perangkat: **Samsung SM-A176B (Galaxy A17 5G), Android 16 / API 36, RAM 7462 MB, thermal 0**.
Alat: `tools/device-bench.ps1` -> `DeviceInferenceBenchmarkTest`, model resmi `yolo11n.onnx`
(10.9 MB, input statis 640x640), 60 sampel per ukuran frame, loop dipacu 24 FPS.

| Frame masuk | Resolusi tensor | p50 | p95 | max | FPS tercapai | Worker ORT |
| --- | --- | --- | --- | --- | --- | --- |
| 640x360 (letterbox) | 640x640 | 216 ms | 276 ms | 325 ms | ~4 | 3 thread, nice 10 |
| 640x640 | 640x640 | 238 ms | 248 ms | 252 ms | ~4 | 3 thread, nice 10 |

Pembacaan:

- **~4 FPS adalah batas realistis model 640 di perangkat ini**, bukan 10-15 FPS. Target
  operasional 2-5 FPS di §1 tercapai; angka 24 FPS tidak.
- Latensi didominasi model: frame 640x360 dan 640x640 hampir sama karena keduanya masuk
  tensor 640x640 setelah letterbox. Menurunkan resolusi **frame** tidak menolong; yang
  menolong adalah model berinput lebih kecil (§3 butir 1).
- `cctv-ort-worker` terverifikasi berjalan pada **nice 10**
  (`Process.THREAD_PRIORITY_BACKGROUND`) selama sesi hidup. Saat pengguna membuka aplikasi
  lain, thread inferensi kalah prioritas CPU dan UI aplikasi itu menang - ini bukti
  langsung untuk klaim "tidak mengganggu aplikasi lain".
- Ini perangkat **8 GB kelas menengah**, bukan HP 4 GB. Angka 4 GB belum diukur; profil
  `lite` tetap menurunkan laju ke 1.5 FPS sebagai pengaman.
- Suhu/thermal sudah diuji 10 menit (§10): stabil 33,7 C, thermal `NONE`; angka di atas
  adalah 30 detik pertama. Soak 30 menit sudah lulus (suhu 34,9 C stabil, thermal `NONE`); 8 jam belum.

#### 6.3.2 A/B jumlah thread ORT (2026-11-27)

`EngineConfig.intraThreads` sekarang bisa dipilih per sesi, dan
`DeviceInferenceBenchmarkTest` menerima `-e benchThreads N`. Satu run menjalankan
kedua resolusi frame, 60 sampel masing-masing, pada perangkat yang sama
(SM-A176B, thermal 0, RAM 7462 MB).

| `benchThreads` | Worker ORT (`/proc` comm) | 640x360 p50 / p95 | 640x640 p50 / p95 | FPS |
| --- | --- | --- | --- | --- |
| 0 (default lama, ORT pilih sendiri) | 3, nice 10 | 211 / 277 ms | 219 / 255 ms | 4 |
| 4 | 3, nice 10 | 229 / 247 ms | 238 / 254 ms | 4 |
| 3 | 2, nice 10 | 208 / 221 ms | 215 / 224 ms | 4 |
| **2** | **1, nice 10** | **192-195 / 201-319 ms** | **199-204 / 205-225 ms** | **4-5** |
| 1 | 0 (inline di thread pemanggil) | 300 / 304 ms | 304 / 312 ms | 3 |

Pembacaan:

- ONNX Runtime menjalankan pemanggil di dalam pool intra-op, jadi `intraThreads = N`
  menghasilkan `N - 1` worker `cctv-ort-worker`; `1` berarti inferensi berjalan
  inline dan justru paling lambat (300 ms).
- **2 thread paling cepat** (p50 192-204 ms, sekitar 10% di bawah default lama) dan
  memakai satu worker lebih sedikit, sehingga menyisakan core untuk decode dan UI.
  Karena itu `EngineStartPlanner.INTRA_THREADS = 2` sekarang menjadi default sesi
  produksi (`EngineStartPlannerTest` mengunci nilainya).
- Perbedaan antar-run sekitar +-10 ms (run ulang `2` memberi 195/204 ms, run ulang `3`
  memberi 247/256 ms). Urutannya konsisten: 2 < 3 < 4 < 1.
- **Belum diukur**: CPU steady-state soak dengan 2 thread. Soak 30 menit sebelumnya
  memakai default 3 worker dan mencatat 166-188% CPU; pengukuran ulang menunggu
  perangkat terpasang kembali.

Angka p50/p95 di tabel berasal dari `DeviceInferenceBenchmarkTest` yang sudah berjalan di
SM-A176B, bukan hipotesis. `DevVideoPipelineTest` juga sudah lulus di perangkat: 10/15/24 FPS
diminta -> 4,5/3,8/3,8 capture & analysis FPS, `tables=2`, status `AVAILABLE,AVAILABLE`,
`modelReady=true`. Yang **masih** tertunda: soak 8 jam (tren RSS/baterai layar mati), frame
drop pada RTSP nyata, dan pengukuran HP 4 GB fisik.
## 7. Target perangkat

| Kelas | Contoh | Target |
| --- | --- | --- |
| Rendah (3 GB RAM) | HP lama | 2 FPS @416, suhu < 45 C |
| Menengah (4-6 GB) | HP umum 2023+ | 2-3 FPS @640, suhu < 45 C |
| Atas (8 GB+) | Flagship | 4-5 FPS @640; eksperimen 10-15 FPS |

Jika perangkat rendah tidak stabil pada 2 FPS, turunkan ke 1 FPS atau kecilkan resolusi;
**jangan** naikkan FPS. Perangkat di bawah 3 GB RAM masuk `blocked` sampai warm-up
membuktikan sebaliknya; lihat [`device-compatibility.md`](device-compatibility.md).

## 8. Ambang RAM total (simulasi 2 GB)

Pertanyaan "testing simulasi android ram 2, kasih treshold aja bisa berapa
fps untuk spek rendah" dijawab sebagai ambang, bukan tebakan. Angka ini dipakai
`EngineStartPlanner.inferenceFpsFor` dan `RustCameraSession` saat membuka
kamera; nilainya konstan dan diuji di `EngineStartPlannerTest`.

| RAM total | Laju inferensi | Alasan |
| --- | --- | --- |
| < 2 560 MB | **0 FPS** (engine tidak dijalankan) | Profil `blocked` di `device-compatibility.md`; ONNX Runtime tidak punya anggaran memori |
| 2 560 - 3 072 MB | **1 FPS** | Batas untuk perangkat yang baru lolos onboarding; satu frame per detik tetap di atas debounce 2 detik |
| > 3 072 MB | Laju profil (`full`/`balanced` 2 FPS, `lite` 1.5 FPS) | Pengaman profil, bukan batas perangkat |

Catatan penting:

- Ambang total RAM ini **bukan** pengganti onboarding. Perangkat 2 GB sudah
  `blocked` di `device-compatibility.md` §5 dan tidak pernah membuka Home; angka
  di sini adalah pengaman lapis kedua bila pembacaan RAM saat sesi lebih rendah
  dari record profil yang tersimpan.
- Pembacaan RAM gagal (`null`) tidak menurunkan laju: "tidak terukur" bukan
  "rendah". Perilaku ini diuji
  `a_missing_ram_reading_does_not_invent_a_throttle`.
- Laju 0 FPS membuat planner mengembalikan `Unavailable("RAM perangkat di bawah
  batas inferensi")`, sehingga Home tetap menampilkan tanda em dash alih-alih
  crash di dalam `Engine.start`.

## 9. Batas FPS inferensi yang terbukti

Pertanyaan "analisa bisa maksimalnya fps berapa?" dijawab dari pengukuran, bukan
target:

| Resolusi frame masuk | p50 inferensi | Batas realistis |
| --- | --- | --- |
| 640x360 (letterbox) | 216 ms (SM-A176B) | ~4 FPS |
| 640x640 | 238 ms (SM-A176B) | ~4 FPS |
| Input model 320x320 (eksperimen) | belum diukur | perkiraan 12-20 FPS; **wajib** diukur ulang dengan akurasi |

Kesimpulan:

- **Stream 10-15/24 FPS bisa *dikirim* ke engine, tetapi tidak bisa dianalisis
  secepat itu**: pump tidak lagi dikunci 2 FPS (batas core-ffi 24 FPS,
  `InferenceBudget`), dan `paceDelayMillis` mencegah pacing menambah delay.
  Hambatan sebenarnya adalah `push_frame` yang sinkron: satu panggilan memakan
  ~216 ms, jadi capture berikutnya menunggu inferensi selesai. **Bukti perangkat
  belum ada**: `DevVideoPipelineTest` mengukur `capturedFps`/`analysisFps` yang
  nyata pada 10/15/24 FPS dan memeriksa loop tidak melebihi anggaran, tetapi
  sudah dijalankan di SM-A176B: 10/15/24 FPS diminta menghasilkan 4,5/3,8/3,8
  capture & analysis FPS (`cctv-dev-e2e.txt`), jadi angka itu bukan hipotesis lagi.
- **Inferensi tidak bisa 10-15/24 FPS** dengan YOLO11n input 640 di perangkat
  kelas menengah: modelnya sendiri 216-238 ms per frame. Frame yang datang lebih
  cepat akan dihitung sebagai stream health tetapi di-drop dari sampling
  (`framesIn` naik, `framesSampled` tidak).
- "Tidak mengganggu aplikasi lain" dibuktikan lewat prioritas: worker ORT dan
  thread inference berjalan nice 10 (`Process.THREAD_PRIORITY_BACKGROUND`),
sehingga aplikasi di foreground menang CPU. Angka soak 30 menit (suhu 34,9 C, RSS
279 392->372 608 kB) sudah terukur; 8 jam masih menjadi risiko, bukan klaim.

**Tiga arti "24 FPS bisa di-handle".** Jawabannya berbeda untuk tiap arti, dan penentunya
bukan RAM:

| Arti | Hasil perencanaan | Alasan di kode |
| --- | --- | --- |
| 24 frame/detik diterima FFI tanpa pecah | Bisa | Frame yang datang lebih cepat dari `inference_interval` menaikkan `frames_in` lalu kembali tanpa inferensi begitu lock detektor bebas (`push_frame_inner`, cabang `!due`). Tidak ada antrean, jadi tidak ada alokasi yang tumbuh mengikuti FPS |
| 24 frame/detik dianalisis | Tidak, baik di 4 GB maupun 8 GB | Lock detektor dipegang selama inferensi (~216 ms di SM-A176B), sehingga batas kerasnya ~4,6 FPS. 24 FPS menuntut <= 41,7 ms/frame dan model 640 belum mendekatinya; input 320 pun baru perkiraan 12-20 FPS dan belum diukur |
| 24 frame/detik diantrekan di Rust | Tidak terjadi, dan jangan dibuat | 640x360 RGB = 691 KB/frame; 24 FPS berarti ~16,6 MB/detik bila diantrekan. Desain sekarang sengaja memilih menunggu (backpressure), bukan menumpuk |

Konsekuensi untuk perangkat 4 GB: profilnya tetap `balanced` 2 FPS (§7), jadi 24 FPS hanya
laju harness. Yang naik saat 24 FPS diminta bukan memori, melainkan CPU decode Media3
ditambah inferensi yang tidak pernah mengejar frame — dan suhu, PSS, serta frame drop pada
kondisi itu belum diukur. Perlu dicatat pula: dengan satu pump berurutan, setiap panggilan
`push_frame` hampir selalu `due`, sehingga `frames_in` dan `frames_sampled` sama-sama
sekitar 4 FPS; pemisahan "masuk 24, dianalisis 4" baru terlihat bila ada produsen frame
terpisah (mis. decoder RTSP di ADR 0009).

Rincian per komponen untuk perangkat 4 GB - anggaran waktu per tahap, anggaran memori,
duty cycle CPU, kriteria lulus, dan langkah pengukurannya - ada di
[`fps-24-4gb-plan.md`](fps-24-4gb-plan.md).

## 10. Uji resource berbasis video (baterai, RAM, ROM, CPU)

Kamera belum ada, dan itu tidak menghalangi uji resource. Yang mahal di perangkat adalah
decode + inferensi; keduanya berjalan sama saat frame datang dari klip lokal lewat Media3
dan `EngineFramePump` seperti saat datang dari RTSP. Yang belum tercakup: radio Wi-Fi,
reconnect RTSP, dan latensi jaringan kamera. Karena itu angka dari video adalah **batas
bawah** beban lapangan, bukan pengganti Fase 8.

Dasar prioritas dan ambang gate ada di [`mvp-priority.md`](mvp-priority.md) §2 dan §5.

### 10.1 Alat yang sudah ada dan yang belum

| Kebutuhan | Alat | Status |
| --- | --- | --- |
| Latensi inferensi per resolusi | `tools/device-bench.ps1` -> `DeviceInferenceBenchmarkTest` | Ada; laporan 30 detik |
| Capture/analysis rate dari klip | `tools/device-e2e.ps1` -> `DevVideoPipelineTest` | **Lulus di perangkat 2026-11-27** (SM-A176B): 10/15/24 FPS diminta -> 4,5/3,8/3,8 capture & analysis FPS; meja berstatus `AVAILABLE`; laporan `cctv-dev-e2e.txt` |
| Deret waktu PSS/CPU/baterai/suhu/storage | `tools/device-soak.ps1` -> `DeviceSoakTest` | **Soak 30 menit lulus 2026-11-27** di SM-A176B: 30 sampel 60 dtk (`cctv-soak.csv`/`.txt`), PSS 158 122->246 160 kB, RSS 279 392->372 608 kB, baterai 100%->100% (USB), suhu 33,7->34,9 C, thermal `NONE`, `framesIn=3561 sampled=2786 dropped=774`, meja `AVAILABLE`. Setelah perbaikan jadwal sampler, soak 10 menit pembanding mencatat `framesIn=1186 sampled=1186 dropped=0`. CPU 30 menit 166-188% (sekitar 1,7-1,9 core), di atas ambang hipotesis; `EngineConfig.intra_threads` + `-e benchThreads` sudah ditambahkan untuk A/B 2 vs 3 thread, **belum diukur** karena perangkat terputus dari `adb`. Soak 8 jam tertunda |
| RAM bebas, suhu, termal, storage bebas di aplikasi | `AndroidHeartbeatFacts` -> `/v1/heartbeat` | Ada; kebenaran nilai di perangkat belum diverifikasi |
| RSS proses dari sisi Rust | `EngineHealth` | Sudah ada (2026-11-27): `rss_kb` dari `/proc/self/status` -> `VmRSS`, plus `frames_dropped` dan `inference_p50_ms`/`p95_ms`; terukur 10 menit: 288 580->366 032 kB (belum dinilai terhadap ambang 8 jam) |

Perintah `adb` di bawah bisa dijalankan sekarang tanpa alat baru; harness hanya
mengotomatiskan pengambilan berulang supaya tidak ada sampel yang terlewat.

### 10.2 Perintah pengambilan sampel

| Metrik | Perintah | Catatan |
| --- | --- | --- |
| PSS total proses | `adb shell dumpsys meminfo id.warung.monitor` | Ambil baris `TOTAL PSS`; catat juga `Java Heap` dan `Native Heap` |
| CPU per proses | `adb shell dumpsys cpuinfo` | Persentase dihitung dari selisih statistik sistem, jadi ambil berulang dan bandingkan tren, bukan satu sampel |
| Level & suhu baterai | `adb shell dumpsys battery` | `level` dalam persen, `temperature` dalam 0,1 C (395 berarti 39,5 C) |
| Termal | `adb shell dumpsys thermalservice` | Baca `Thermal Status`; `MODERATE` sudah memicu turun FPS di §5 |
| Wake lock | `adb shell dumpsys power` | Cari bagian `Wake Locks`; wakelock liar adalah penyebab baterai habis |
| Ukuran data aplikasi | `adb shell run-as id.warung.monitor du -sk .` | Bandingkan antar jam; outbox dan `table_logs` harus stabil |
| Storage perangkat | `adb shell dumpsys diskstats` | Ukuran aplikasi kasar; jangan dipakai sebagai satu-satunya bukti |
| Jank/ANR | `adb shell dumpsys gfxinfo id.warung.monitor` | Kirim `reset` sebelum pengukuran |
| Log | `adb logcat -d -v time` | Log tidak boleh memuat URL RTSP, password, PIN, atau token |

Satu sampel ditulis sebagai satu baris CSV (header ini juga kontrak `SoakCsv.HEADER`):

```text
elapsed_s,pss_kb,java_heap_kb,native_heap_kb,rss_kb,cpu_pct,level_pct,battery_temp_c,thermal_status,data_kb,frames_in,frames_sampled,frames_dropped,inference_p50_ms,inference_p95_ms
```

`frames_in` dan `frames_sampled` dibaca dari `EngineHealth`; `frames_dropped` sekarang
dilaporkan langsung oleh sampler (bukan hanya selisih), bersama `rss_kb` dan
`inference_p50_ms`/`p95_ms`. Semua angka itu juga dikirim di `runtime`/`inference`
heartbeat, jadi laporan perangkat tidak bergantung pada pembacaan manual.



### 10.3 Protokol 30 menit

1. Isi baterai ≥ 80%, lepas charger, nyalakan mode pesawat kecuali Wi-Fi bila sync ikut diuji.
2. Dinginkan perangkat sampai suhu baterai < 35 C, sama seperti §6.1.
3. Salin model dan klip dengan `tools/device-e2e.ps1`, lalu jalankan `DevVideoPipelineTest`
   sekali untuk memastikan jalur video hidup sebelum mulai menghitung.
4. Mulai sesi monitoring pada profil yang diuji (mis. `balanced`, 2 FPS); layar menyala
   5 menit pertama, lalu matikan layar.
5. Ambil sampel tiap 60 detik selama 30 menit; satu baris CSV per sampel.
6. Ambil `dumpsys gfxinfo` di akhir untuk melihat jank.
7. Hentikan lebih awal bila suhu > 48 C, termal `SEVERE`, atau ada ANR.

Bukti minimum: 30 baris CSV, status meja pernah berubah dari `unknown` (bukan selalu
`unknown`), dan tidak ada ANR.

Harness: `powershell -ExecutionPolicy Bypass -File tools/device-soak.ps1 -Model "$env:TEMP\yolo11n.onnx"`
(default 30 menit / 60 dtk). Untuk 8 jam tambahkan `-DurationMin 480 -IntervalSec 300`.
Skrip dulu menolak instrumentasi bila layar/keyguard terkunci: jendela uji
tidak aktif, Media3 tidak pernah membuat `TextureView`, dan CSV hanya berisi
header. Activity uji kini memakai `FLAG_SHOW_WHEN_LOCKED` + `FLAG_TURN_SCREEN_ON`,
dan harness menahan layar tetap menyala (`FLAG_KEEP_SCREEN_ON`);
angka baterai dari soak adalah batas atas, bukan angka lapangan dengan layar
mati. Bila `EngineHealth.lift` melempar `BufferUnderflowException`, jalankan
`tools/android-build/build-rust.ps1` lalu rebuild dan pasang ulang APK serta
APK test - binding Kotlin dan `libcore_ffi.so` harus berasal dari build yang sama.

### 10.4 Protokol 8 jam

Sama seperti §10.3, dengan perbedaan:

- Sampel tiap 5 menit, bukan 60 detik; suhu dan baterai tetap ikut di setiap sampel.
- Layar mati sepanjang uji setelah 5 menit pemanasan.
- Klip wajib di-loop. Kalau klip berakhir tanpa loop, status harus menjadi `unknown`,
  bukan status terakhir yang dibekukan.
- Bandingkan PSS jam ke-1 dengan jam ke-8. Batas: kenaikan ≤ 10% (DoD
  `android-app-plan.md` butir 10).
- Baterai dihitung `(level_awal - level_akhir) / jam`. Angka itu yang mengisi matriks
  [`mvp-priority.md`](mvp-priority.md) §5.

### 10.5 Cara membaca hasil

| Gejala | Kemungkinan penyebab | Tindakan |
| --- | --- | --- |
| PSS naik monoton tanpa turun | Kebocoran bitmap/frame atau buffer ORT yang tidak dilepas | Bandingkan `Native Heap` dan `Java Heap`; bila native yang naik, periksa `PreprocessScratch` dan ring buffer |
| PSS naik lalu datar | Alokasi awal (tensor, sesi ORT) | Bukan kebocoran; baseline 5 menit wajib ada untuk membedakannya |
| Baterai turun > 10% per jam | Wakelock berlebih atau FPS tidak turun saat layar mati | Periksa `dumpsys power`; pastikan `InferenceCadence` menurunkan laju |
| Suhu naik cepat walau FPS 2 | Casing/ventilasi buruk atau decode tetap 25 FPS | Uji ulang tanpa casing; turunkan FPS, jangan menambah pendingin sebagai solusi |
| Data aplikasi tumbuh terus | Outbox tidak ter-ACK atau log tidak berputar | Periksa `/v1/sync`, lalu retensi di `android-sync.md` |
| `frames_in` naik, `frames_sampled` tidak | Inferensi lebih lambat dari interval | Normal pada 24 FPS dengan model 640; catat sebagai drop, bukan error |

### 10.6 Batasan yang wajib disebut saat melaporkan

- Angka baterai dari video tidak termasuk radio Wi-Fi dan reconnect RTSP; laporkan sebagai
  batas bawah, bukan angka lapangan.
- Klip uji berdurasi 5-23 detik dan di-loop; bebannya lebih rata daripada CCTV nyata yang
  punya malam, hujan, dan orang bolak-balik.
- Klip stok berwatermark hanya untuk eksperimen lokal (`testdata.md` §6.4); jangan masuk
  APK debug yang dibagikan.
- Uji ini tidak membuktikan akurasi deteksi; akurasi menunggu `expected/*.json` dan Fase 8.

## 11. Kesimpulan

24 FPS adalah pertanyaan yang benar secara teknis, tetapi salah secara produk: biayanya
tinggi dan manfaatnya nol untuk status meja. Bangun pipeline yang mengukur, lalu pilih FPS
dari data — bukan dari target angka.
