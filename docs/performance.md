# Performa & strategi FPS

Dokumen ini menjawab pertanyaan "bisa tidak 24 FPS di HP?" dan menetapkan target FPS
yang realistis. Semua angka di sini adalah **hipotesis yang harus diukur**, bukan janji.

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

Alat: `tracing` + heartbeat ke VPS (lihat `data-contracts.md` §5), plus benchmark lokal
di `tools/bench/` yang mencetak tabel CSV agar bisa dibandingkan antar perangkat.

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
| `PreprocessScratch` di `ai-engine` | Tensor `3*size*size` f32 dan tabel sampling per sumbu dipakai ulang; tidak ada lagi alokasi ~4.9 MB per frame 640x640, dan lookup sumber tidak dihitung ulang | `ai-engine` 33 unit test + 2 test integrasi ONNX hijau, termasuk uji bahwa hasilnya identik dengan jalur alokasi lama untuk 97x61@64, 640x360@320, 168x168@96 |
| Sesi ORT: `intra_threads` dibatasi 4, `inter_threads` 1, parallel execution mati, spinning intra/inter mati | Worker tidak berputar saat menganggur, sehingga aplikasi lain tidak kehilangan CPU ketika pengguna berpindah aplikasi | `cargo clippy`/`cargo test` hijau; default thread punya unit test batas 1..=4 |
| Input size mengikuti model (`[1,3,H,W]` statis, H kelipatan 32, 32..1280) | Model 416 tidak lagi dibuang ke tensor 640 (yang membuat ORT gagal atau lambat) | 2 unit test `input_size_from_shape` (9 skenario shape) + 1 test integrasi model 640 |
| `EngineFramePump` memakai satu thread khusus `THREAD_PRIORITY_BACKGROUND`, buffer `IntArray`/`ByteArray` dipakai ulang | Packing frame tidak lagi di `Dispatchers.Default` bersama UI; steady-state tidak lagi mengalokasikan heap tiap tick | `:app:testDebugUnitTest` 157 test hijau (9 untuk pump + 4 `Rgb888`) |
| `VideoCaptures.captureInto` memakai ulang `Bitmap` target dan `androidx.core.graphics.createBitmap` | Capture 2 FPS tidak lagi mengalokasikan bitmap baru tiap tick bila ukuran texture tidak berubah; bitmap recycled tidak pernah dipakai ulang | 4 test `VideoCaptures`/`Bitmap` di `EngineFramePumpTest` |
| Profil `lite` menurunkan laju inferensi ke 1.5 FPS lewat `EngineStartPlanner.withInferenceFps` yang dipilih `RustCameraSession` dari `OnboardingStore` | HP low-spec mendapat ~25% lebih sedikit inferensi; laju tetap di atas debounce 2 dtk sehingga meja tidak terasa lambat | 2 test `EngineStartPlannerTest` (cap `lite` dan batas band `docs/device-compatibility.md`) |
| `InferenceCadence` menurunkan interval pump ke 1 FPS saat `PowerManager` melapor `SEVERE`+ atau RAM bebas < 150 MB, dibaca tiap tick lewat `DeviceLoadSource` | Saat HP panas atau RAM sempit, inferensi otomatis memakai separuh CPU; ambangnya persis §5 dokumen ini dan `monitoring.md` §2.4, bukan angka baru | 10 test `InferenceCadenceTest` (termasuk `MODERATE` tetap 2 FPS dan batas RAM tepat 150 MB) |
| `InferenceBudget` meneruskan FPS sesi ke pump; batas keras core-ffi naik dari 10 ke 24 FPS | Pump tidak lagi terkunci 2 FPS, sehingga hard test 10-15/24 FPS di HP 4 GB bisa dijalankan; laju tetap diturunkan setengah saat panas atau RAM sempit | 6 test `InferenceBudgetTest` + 2 test `clamp_inference_fps` di `core-ffi` |

Verifikasi yang masih tertunda: p50/p95 `inference_ms`, FPS aktual, suhu, RSS, dan frame
drop pada perangkat nyata. `adb devices` masih kosong, jadi semua klaim latensi di atas
tetap hipotesis sampai §6 dijalankan.
## 7. Target perangkat

| Kelas | Contoh | Target |
| --- | --- | --- |
| Rendah (3 GB RAM) | HP lama | 2 FPS @416, suhu < 45 C |
| Menengah (4-6 GB) | HP umum 2023+ | 2-3 FPS @640, suhu < 45 C |
| Atas (8 GB+) | Flagship | 4-5 FPS @640; eksperimen 10-15 FPS |

Jika perangkat rendah tidak stabil pada 2 FPS, turunkan ke 1 FPS atau kecilkan resolusi;
**jangan** naikkan FPS. Perangkat di bawah 3 GB RAM masuk `blocked` sampai warm-up
membuktikan sebaliknya; lihat [`device-compatibility.md`](device-compatibility.md).

## 8. Kesimpulan

24 FPS adalah pertanyaan yang benar secara teknis, tetapi salah secara produk: biayanya
tinggi dan manfaatnya nol untuk status meja. Bangun pipeline yang mengukur, lalu pilih FPS
dari data — bukan dari target angka.
