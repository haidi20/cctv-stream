# Rencana 24 FPS di perangkat 4 GB

Dokumen ini adalah **simulasi perencanaan**, bukan hasil pengukuran. Pertanyaannya:
"kalau stream 24 FPS diminta di HP RAM 4 GB, apa yang sebenarnya terjadi, dan apa yang
harus dibuktikan?"

Status dokumen: **rencana + simulasi anggaran**. Satu-satunya angka terukur berasal dari
Samsung SM-A176B (8 GB) di [`performance.md`](performance.md) §6.3.1. HP 4 GB **belum
pernah diukur**; `adb devices` kosong sejak 2026-11-27, jadi `DevVideoPipelineTest` juga
belum pernah berjalan. Semua sel bertanda **[H]** adalah hipotesis dan tidak boleh dipakai
menolak rilis sebelum diukur.

## 1. Ringkasan jawaban

| Pertanyaan | Jawaban perencanaan |
| --- | --- |
| 24 FPS **dianalisis** di HP 4 GB? | **Tidak.** Batasnya biaya model (~216 ms/frame), bukan RAM. 24 FPS butuh <= 41 ms/frame; sekarang 5,2x terlalu lambat |
| 24 FPS **dikirim/diterima** engine? | Bisa, tanpa antrean dan tanpa pertumbuhan memori. Yang naik beban CPU, bukan RAM |
| 24 FPS **aman untuk HP 4 GB 8 jam**? | Belum diketahui. Duty cycle inferensi naik dari 43% ke ~100% satu core; suhu dan baterai belum diukur |
| Apakah 24 FPS pernah jadi laju produk? | Tidak. Profil HP 4 GB = `balanced` 2 FPS. 24 FPS hanya laju harness/benchmark |

Kesimpulan satu baris: **24 FPS di 4 GB bukan masalah RAM, melainkan masalah duty cycle dan
panas.** Menambah RAM tidak menaikkan FPS; memperkecil input model atau menurunkan laju
permintaan yang menaikkannya.

## 2. Definisi laju (supaya tidak tertukar)

Empat angka berbeda sering disebut "FPS". Dokumen ini selalu memakai nama berikut:

| Nama | Arti | Pemilik di kode |
| --- | --- | --- |
| `fps_diminta` | Laju yang diminta sesi/benchmark, 0,5-24 | `EngineConfig.inferenceFps`, `InferenceBudget`, `MAX_INFERENCE_FPS` di `core-ffi` |
| `fps_capture` | Berapa kali pump benar-benar menangkap frame | `EngineFramePump.run` + `InferenceCadence.paceDelayMillis` |
| `frames_in` | Frame yang sampai ke `push_frame` | `Counters.frames_in` |
| `frames_sampled` | Frame yang benar-benar melewati inferensi | `Counters.frames_sampled` |
| `fps_decode` | Laju Media3 men-decode stream/klip | Media3 (`media3-exoplayer`), **tidak** dikendalikan engine |

Titik yang mudah salah: **`fps_decode` tidak ikut turun saat `fps_diminta` diturunkan.**
Klip/stream tetap di-decode pada laju aslinya; yang turun hanya inferensinya.

## 3. Angka masukan

| Besaran | Nilai | Status | Sumber |
| --- | --- | --- | --- |
| Anggaran per frame pada 24 FPS | 41,7 ms (Kotlin memakai 41 ms) | terukur/aritmetik | `InferenceCadence.millisPerFrame` |
| p50 `pushFrame` 640x360 -> tensor 640 | 216 ms | **terukur** SM-A176B | `performance.md` §6.3.1 |
| p95 `pushFrame` | 276 ms | **terukur** | idem |
| max `pushFrame` | 325 ms | **terukur** | idem |
| p50 640x640 -> tensor 640 | 238 ms | **terukur** | idem |
| Thread ORT | 3, nice 10 | **terukur** | idem |
| Ukuran model `yolo11n.onnx` | 10,9 MB | terukur (file) | idem |
| `libonnxruntime.so` | 36 MB, dipetakan dari APK | tercatat | `performance.md` §6.3 |
| Tensor input f32 640x640 | 4,9 MB, dipakai ulang | tercatat | idem |
| Frame 640x360 RGB888 | 691 200 B (675 KiB) | aritmetik | 640x360x3 |
| Bitmap capture ARGB_8888 640x360 | 921 600 B | aritmetik | `EngineFramePump.MAX_FRAME_WIDTH` |
| Biaya decode H.264 per frame | 3-8 ms | **[H]** | `performance.md` §2 |
| Biaya capture + pack per tick | belum diukur | **[H]** | `EngineFramePump`, `PackedFrameBuffers` |
| PSS aplikasi di HP 4 GB | belum diukur | **[H]** | `mvp-priority.md` §5 |

## 4. Simulasi A - satu pump, `fps_diminta` = 24 (kondisi kode hari ini)

Alur per tick: `captureInto` (bitmap 640 lebar) -> `PackedFrameBuffers.pack` (ARGB ->
RGB888, buffer dipakai ulang) -> `engine.pushFrame` (UniFFI menyalin 691 KB, lalu
`push_frame_inner` memegang lock detektor selama inferensi) -> `paceDelayMillis`.

| Tahap | p50 | p95 | Catatan |
| --- | --- | --- | --- |
| Capture TextureView -> Bitmap | belum diukur **[H]** 1-3 ms | belum diukur | berjalan di Main |
| Pack ARGB -> RGB888 (230 400 px) | belum diukur **[H]** 2-5 ms | belum diukur | thread `cctv-inference` |
| `pushFrame` (preprocess + inferensi + NMS + spasial) | 216 ms | 276 ms | **terukur** |
| `paceDelayMillis(41, kerja)` | 0 ms | 0 ms | `41 - kerja` sudah negatif, jadi loop langsung lanjut |
| **Siklus** | **>= 220 ms** | **>= 285 ms** | |

Turunan:

| Metrik | p50 | p95 | Aritmetik |
| --- | --- | --- | --- |
| `fps_capture` | ~4,5 | ~3,5 | 1000 / siklus |
| `frames_sampled` per detik | ~4,5 | ~3,5 | hampir setiap tick `due` karena 216 ms > 41 ms |
| `frames_in - frames_sampled` | ~0 | ~0 | tidak ada frame yang di-drop, karena tidak ada frame yang lebih cepat dari inferensi |
| Antrean di Rust | 0 | 0 | lock detektor menahan pemanggil, bukan menyimpan frame |
| Memori tambahan karena 24 FPS | ~0 (steady state) | ~0 | satu salinan 691 KB per panggilan, dilepas setelah inferensi |

Pembacaan: pada jalur satu-pump, **24 FPS hanya mengubah cara loop berjalan (back-to-back),
bukan hasilnya.** Angka `4,5 FPS` itu identik dengan "inferensi berjalan terus-menerus".
Karena itu di jalur ini `frames_in` dan `frames_sampled` **sama**; pemisahan "masuk 24,
dianalisis 4" yang sering dikutip baru terjadi di jalur dengan produsen frame terpisah
(Simulasi B), belum di kode hari ini.

Yang perlu disadari: `InferenceCadence` menurunkan laju setengah saat termal `SEVERE` atau
RAM bebas < 150 MB. Pada `fps_diminta` 24, penurunan itu menghasilkan 12 FPS (interval
83 ms) yang **masih di bawah 216 ms**, jadi laju nyata tetap ~4,5 FPS. Artinya pengaman
termal tidak menolong saat inferensi sudah menjadi batas; yang menolong hanya memperkecil
model atau menghentikan sesi.

## 5. Simulasi B - produsen frame terpisah 24 FPS (jalur decoder ADR 0009)

Ini yang diasumsikan orang saat bertanya "24 FPS masuk, berapa yang dianalisis". Skenario:
thread decoder mendorong 24 frame/detik, `fps_diminta` = 24.

| Metrik | Nilai | Alasan |
| --- | --- | --- |
| Frame ditawarkan | 24/detik | produsen eksternal |
| `frames_sampled` | ~4,5/detik | satu inferensi 216 ms => 4,63 FPS |
| Frame ditolak cabang `!due` | ~19,5/detik | menaikkan `frames_in`, tidak menambah `frames_sampled` |
| Biaya frame yang ditolak | lock + cek `last_inference_at` + `promote_connected_if_model_ready` | mikro-detik, **[H]** belum diukur |
| Memori bila produsen **tidak** menunggu | 1 frame tertunda = 691 KB (terbatas) | buffer 1 frame, bukan antrean |
| Memori bila diantrekan | 691 KB x 24 = **16,6 MB/detik** | jangan dibuat; ini alasan desain menolak antrean |

Risiko di skenario ini: produsen frame **terblokir** lock detektor sampai ±216 ms. Kalau
decoder dipaksa menunggu, ia menahan buffer Media3 sehingga frame drop pindah ke hilir.
Kalau decoder diberi antrean agar tidak menunggu, memori tumbuh 16,6 MB/detik dan status
meja jadi basi. Pilihan yang benar adalah **buffer 1 frame (buang yang lama)**, sesuai
`performance.md` §3 butir 6.

## 6. Simulasi C - memperkecil model, bukan menaikkan FPS

Biaya model berskala kasar dengan luas input (perkiraan kuadratik; ada overhead tetap
sehingga hasil nyata lebih lambat dari hitungan ini):

| Input model | p50 hipotetis | FPS maksimum | Status |
| --- | --- | --- | --- |
| 640x640 | 216-238 ms (terukur) | 4,2-4,6 | **terukur** |
| 320x320 | ~54 ms **[H]** | ~18,5 **[H]** | belum diukur; R-P0-4 |
| Syarat 24 FPS | <= 41,7 ms | 24 | butuh ~19% biaya model 640 |

Pembacaan: bahkan model 320 (bila asumsi kuadratik benar) **belum** mencapai 24 FPS, dan
overhead tetap (preprocess, NMS, salin 691 KB, lock) membuat angka nyatanya lebih lambat.
Jadi 24 FPS analisis bukan target yang bisa dikejar dengan mengecilkan input satu tingkat;
itu target yang salah untuk produk (lihat §9).

## 7. Anggaran memori di perangkat 4 GB

4 GB berarti `MemTotal` sekitar 3 500-3 900 MB setelah kernel/GPU reserve **[H]**. Angka itu
**di atas** ambang onboarding 3 072 MB (`DeviceAssessment.MIN_RAM_MB`), jadi perangkat lolos
ke profil `balanced` (2 FPS) - dan profil inilah yang mengatur laju, bukan 24 FPS. Nilai
`MemTotal` tetap wajib dibaca di perangkat (langkah 0 §10), karena HP berlabel 4 GB bisa
melaporkan angka di bawah ambang.

| Komponen | Perkiraan | Status |
| --- | --- | --- |
| `libonnxruntime.so` | 36 MB, dipetakan dari APK (tidak disalin) | tercatat |
| `yolo11n.onnx` | 10,9 MB | terukur (file) |
| Tensor input + scratch `PreprocessScratch` | ~4,9 MB, dipakai ulang | tercatat |
| Arena internal ORT (activation/workspace) | belum diketahui | **[H]**; kemungkinan komponen native terbesar |
| Bitmap capture + `IntArray` + `ByteArray` pack | ~2,3 MB (640x360) | aritmetik |
| Salinan frame di Rust per `push_frame` | 691 KB sementara | aritmetik |
| Buffer decoder Media3 + permukaan texture | belum diukur | **[H]** |
| Compose, Room, Hilt, outbox | belum diukur | **[H]** |
| **PSS total saat sesi jalan** | belum diukur | **[H]** - wajib sebelum klaim apa pun |

Poin penting: **24 FPS tidak menambah satu baris pun di tabel ini** selama tidak ada
antrean. Beban 24 FPS adalah CPU dan panas, bukan memori. Karena itu argumen "HP 4 GB tidak
kuat 24 FPS karena RAM" salah alamat; yang membatasi adalah 216 ms/frame.

## 8. Beban CPU, termal, dan baterai (simulasi duty cycle)

Duty cycle thread inferensi = `p50 / interval`, dibatasi 100%:

| `fps_diminta` | Interval | Kerja per tick | Duty thread inferensi | Perkiraan CPU 1 core |
| --- | --- | --- | --- | --- |
| 0,5 | 2 000 ms | 216 ms | 11% | rendah |
| 1 | 1 000 ms | 216 ms | 22% | rendah |
| 2 (`balanced`) | 500 ms | 216 ms | **43%** | sesuai ambang hipotesis `mvp-priority.md` §5 |
| 12 (setelah thermal halving) | 83 ms | 216 ms | 100% (terbatas) | tinggi |
| 24 | 41 ms | 216 ms | **100% (terbatas)** | satu core penuh, terus |

Tambahan dari decode yang **tidak** ikut turun: 24 FPS x 3-8 ms = 72-192 ms/detik **[H]**,
yaitu 7-19% satu core lagi. Jadi permintaan 24 FPS berarti **satu core tersaturasi ditambah
~10-20% core kedua**, terus-menerus, dengan prioritas nice 10.

Konsekuensi yang harus diuji, bukan diklaim:

- **Termal**: `SEVERE` menghentikan uji 30 menit menurut `performance.md` §6.1. Pada 4 GB,
  dugaan **[H]** adalah `SEVERE` tercapai jauh sebelum 8 jam. Tidak ada angka.
- **Baterai**: `performance.md` §4 memperkirakan konsumsi 3-5x dibanding 2-3 FPS; **[H]**.
- **Tidak mengganggu aplikasi lain**: nice 10 membuat aplikasi foreground menang CPU, dan
  itu sudah terbukti di SM-A176B untuk beban 30 detik. Yang belum dibuktikan adalah apakah
  core yang tersaturasi 100% selama 8 jam masih menyisakan respons wajar untuk app lain;
  ini butuh `dumpsys gfxinfo` aplikasi lain saat uji, bukan asumsi.

## 9. Kriteria lulus/gagal

24 FPS analisis **tidak boleh** menjadi kriteria lulus; secara konstruksi hasilnya gagal dan
kegagalan itu tidak memberi informasi baru. Yang diuji adalah dampaknya.

| Klaim yang diuji | Ambang lulus | Alat |
| --- | --- | --- |
| Aplikasi tidak ANR/crash saat 24 FPS diminta | 0 ANR, 0 crash selama 30 menit | `adb logcat -d`, `dumpsys activity` |
| Memori tidak tumbuh karena FPS | PSS naik <= 10% dari baseline 5 menit | `dumpsys meminfo` tiap 60 detik |
| Analisis tetap berjalan | `frames_sampled`/detik >= 3 (bukan 24) | `EngineHealth` (butuh R-P0-1 untuk p50/p95) |
| Suhu terkendali | < 45 C dan bukan `SEVERE` | `dumpsys battery`, `dumpsys thermalservice` |
| Status meja tidak membeku | minimal satu transisi selain `unknown` selama uji | `tables` dari engine + log |
| Aplikasi lain tetap responsif | jank aplikasi lain tidak naik dibanding baseline | `dumpsys gfxinfo <paket lain>` |

Bila salah satu gagal, tindakannya **menurunkan beban** (model lebih kecil atau laju lebih
rendah), bukan menaikkan RAM atau mematikan pengaman termal.

## 10. Rencana eksekusi di HP 4 GB

Prasyarat: satu perangkat 4 GB fisik terpasang (`adb devices` terisi), model
`yolo11n.onnx` dan klip `testdata/videos/istockphoto-1067633772-640_adpp_is.mp4` tersedia.

| # | Langkah | Perintah | Bukti yang dihasilkan |
| --- | --- | --- | --- |
| 0 | Pastikan perangkat benar-benar 4 GB dan tidak `blocked` | `adb shell cat /proc/meminfo` (baris `MemTotal`) | `MemTotal` >= 3 072 MB, profil `balanced` |
| 1 | Latensi dasar di perangkat itu | `powershell -ExecutionPolicy Bypass -File tools/device-bench.ps1 -Model <path>` | `cctv-benchmark.txt`: p50/p95, FPS tercapai |
| 2 | Jalur video 10/15/24 FPS | `powershell -ExecutionPolicy Bypass -File tools/device-e2e.ps1 -Model <path>` | `cctv-dev-e2e.txt`: `capturedFps`, `analysisFps` per laju |
| 3 | Beban 30 menit dengan 24 FPS diminta | sampel manual `performance.md` §10.2 tiap 60 detik | CSV 30 baris: PSS, CPU, suhu, termal, `frames_in`, `frames_sampled` |
| 4 | Bandingkan dengan baseline 2 FPS | ulangi langkah 3 dengan `fps_diminta` 2 | dua CSV; selisihnya adalah harga 24 FPS |
| 5 | Isi tabel §11 | - | angka, bukan **[H]** |

`tools/device-soak.ps1` sudah ada sejak 2026-11-27 (R-P0-3), tetapi belum dijalankan karena keyguard perangkat terkunci; langkah 3-4 tetap manual sampai perangkat dibuka. R-P0-1 sudah
selesai di host 2026-11-27, jadi `rss_kb`, `frames_dropped`, dan p50/p95 sudah tersedia di
`EngineHealth` (nilainya di perangkat belum diukur).
### 10.1 Yang bisa dibuktikan tanpa perangkat fisik

Angka di §11 **tidak bisa** diisi dari emulator. Emulator berjalan di CPU host x86_64, jadi
latensi `pushFrame` mengukur host, `dumpsys thermalservice` melaporkan `NONE`, tidak ada
baterai, dan decode Media3 memakai jalur host. Empat hal berikut masih berguna:

| Diuji di emulator | Menjawab apa | Batas |
| --- | --- | --- |
| AVD `x86_64` RAM 4 096 MB | `MemTotal` yang dilaporkan, profil onboarding, `blocked` vs `balanced` | angka RAM emulator berbeda dari HP nyata |
| `tools/device-e2e.ps1` di AVD | harness 10/15/24 FPS berjalan; `capturedFps` dan `analysisFps` terisi; tidak ANR | `analysisFps` bukan prediksi HP |
| sesi 30 menit di AVD | tidak ada OOM atau kebocoran yang jelas; antrean FFI tetap 0 | kebocoran lambat di SoC nyata tidak muncul |
| `adb logcat -d` | tidak ada crash FFI saat `fps_diminta` 24 | - |

AVD yang ada di mesin ini tidak bisa dipakai apa adanya: `karta_ui_test` (1 536 MB, `x86_64`),
`karta_ui_test_30` (1 536 MB, `x86`), dan `karta_ui_test_small` (1 024 MB, `x86`).
`abiFilters` menolak `x86`, dan RAM 1 536 MB akan berakhir `blocked`. Untuk jalur emulator,
buat AVD **`x86_64`, android-34, `hw.ramSize=4096`**; `libcore_ffi.so` `x86_64` sudah ada di
`android/app/src/main/jniLibs/x86_64` (lokal, tidak di-commit).

Kesimpulan: emulator memvalidasi **jalur dan kegagalan keras** (crash, OOM, ANR); perangkat
fisik memvalidasi **angka dan panas**. Gate MVP di `mvp-priority.md` butuh yang kedua.

### 10.2 Peran k6: beban server, bukan beban perangkat

`k6` terpasang di mesin ini (`C:\Program Files\k6\k6.exe`) dan berguna untuk `server/`, bukan
untuk pertanyaan 4 GB. Batas yang diukur di §4-§8 terjadi di dalam proses Android
(`pushFrame` -> `ort` -> spatial). k6 hanya mengirim HTTP dan WebSocket, jadi tidak menyentuh
jalur itu.

| Pertanyaan | k6 menjawab? |
| --- | --- |
| Laju nyata, PSS, suhu, baterai di HP 4 GB | tidak |
| 24 frame/detik masuk ke jalur frame | tidak; k6 tidak punya modul RTSP |
| `/v1/sync` dan `/v1/heartbeat` menahan N perangkat | ya |
| `429` + `Retry-After` saat burst | ya; sekarang hanya diuji sebagai unit test `ratelimit.rs` |
| Fanout `/v1/ws` ke M dashboard | ya |

Beban ujinya harus realistis. 24 FPS **tidak** menaikkan laju HTTP: event meja di-debounce 30 dtk
(`SyncScheduler.DEBOUNCE_MILLIS`), heartbeat 30 dtk live dan 60 dtk idle, dan server membatasi 60
sync/menit serta 4 heartbeat/menit per perangkat (`server-api.md` §10). Satu perangkat
menghasilkan 2-3 request/menit, bukan 24 per detik; 50 warung berarti sekitar 2,5 request/detik
total. Menembak k6 pada 24 request/detik per perangkat menguji profil yang tidak akan pernah
ada, sama tidak bergunanya dengan memaksa analisis 24 FPS.

Konsekuensinya: untuk mengisi §11, k6 tidak memperpendek jalan, tetap butuh perangkat. Untuk
kapasitas VPS, k6 adalah alatnya dan perangkat tidak dibutuhkan; `cctv-server` bisa dijalankan
lokal (`cargo run -p cctv-server`) dengan SQLite sebelum ada VPS.

## 11. Hasil pengukuran (kosong)

Belum ada perangkat 4 GB yang terpasang. Tabel ini sengaja dibiarkan kosong; jangan diisi
dengan perkiraan.

| Metrik | 2 FPS (baseline) | 24 FPS diminta | Catatan |
| --- | --- | --- | --- |
| `MemTotal` perangkat | - | - | |
| `fps_capture` aktual | - | - | |
| `frames_sampled`/detik | - | - | |
| p50/p95 `pushFrame` | - | - | dari `EngineHealth` (R-P0-1) |
| PSS awal -> akhir 30 menit | - | - | |
| Suhu maksimum | - | - | |
| Termal tertinggi | - | - | |
| Baterai per jam | - | - | |
| ANR/crash | - | - | |
| Transisi status meja | - | - | |

## 12. Risiko dan keputusan tertunda

| Risiko | Dampak | Mitigasi sekarang |
| --- | --- | --- |
| Perangkat berlabel "4 GB" melaporkan `MemTotal` < 3 072 MB dan berakhir `blocked` | 24 FPS tidak bisa diuji sama sekali | Ukur `MemTotal` lebih dulu (langkah 0); keputusan ambang ada di `device-compatibility.md` §1.1 |
| `InferenceCadence` tidak menurunkan laju nyata saat inferensi-bound | Pengaman termal terlihat ada tetapi tidak bekerja pada 24 FPS | Dokumentasikan; mitigasi nyata = model lebih kecil atau hentikan sesi |
| Klip 5-23 detik di-loop | Beban lebih rata daripada CCTV nyata | Laporkan sebagai batas bawah (`performance.md` §10.6) |
| PSS baseline tidak diambil | Kenaikan PSS tidak bisa dipisahkan dari alokasi awal | Baseline 5 menit wajib (langkah 3) |
| Benchmark 24 FPS dipakai sebagai janji produk | Ekspektasi 24 FPS masuk ke rilis | Dokumen ini + `performance.md` §9 menyatakan 24 FPS hanya laju harness |

Keputusan yang belum diambil (bukan wewenang dokumen ini):

1. Apakah 24 FPS tetap dipertahankan sebagai laju harness, atau diturunkan ke 10 FPS agar
   uji lebih pendek dan tidak menyesatkan.
2. Apakah perlu antrean 1 frame di FFI (perubahan desain, butuh keputusan/ADR) atau tetap
   backpressure seperti sekarang.
3. Apakah ambang onboarding diturunkan ke 2 560 MB agar HP "3 GB" bisa diuji (R-P0-4).

### 12.1 Jawaban singkat dan rekomendasi

Tiga kalimat:

1. **Yang dibutuhkan angka adalah satu perangkat fisik ARM, bukan spesifik HP berlabel 4 GB.**
   Laju, suhu, baterai, dan PSS bisa diukur di perangkat apa pun yang lolos ambang `MemTotal`.
   Label 4 GB hanya wajib untuk membuktikan klaim memori dan perilaku `blocked`/`balanced`.
2. **Kemampuan analisis 24 FPS tidak perlu dibuktikan, karena sudah terbukti tidak bisa**
   (216 ms -> 4,6 FPS, §5). Yang perlu dibuktikan hanya bahwa memintanya tidak merusak: tanpa
   ANR, PSS datar, tabel tetap bertransisi (§9).
3. **Solusinya menurunkan laju, bukan menambah perangkat atau RAM.** Profil bawaan 2 FPS tetap;
   24 FPS tinggal sebagai laju harness untuk melihat batas.

Rekomendasi untuk tiga keputusan di atas:

| Keputusan | Rekomendasi | Alasan |
| --- | --- | --- |
| Laju harness | **10 FPS** | Uji 30 menit lebih pendek; 10 FPS sudah di atas laju nyata yang mungkin dicapai (4,6 FPS), jadi tidak menyembunyikan fakta |
| Antrean 1 frame di FFI | **tetap backpressure** | Antrean menambah 691 KB dan latensi tanpa menambah `frames_sampled`; 16,6 MB/detik hanya berpindah tempat |
| Ambang onboarding | **tetap 3 072 MB sampai R-P0-4 diukur** | Menurunkan ambang sebelum ada angka di HP 3 GB berarti mengubah gate berdasarkan asumsi; `lite` sudah ada untuk kasus marginal |

Urutan kerja yang bisa jalan **tanpa perangkat**, berurutan: ~~R-P0-1 (`rss_kb` dan p50/p95 di
`EngineHealth`)~~ (selesai host 2026-11-27), AVD `x86_64` 4 096 MB untuk jalur dan kegagalan keras (§10.1),
`tools/device-soak.ps1` (R-P0-3, harness tersedia), baru kemudian pengukuran di perangkat (§10). k6 hanya untuk
kapasitas server (§10.2) dan tidak memperpendek urutan ini. `performance.md` §9 tetap acuan laju
harness sampai keputusan 1 diambil.

## 13. Rujukan

- [`performance.md`](performance.md) - anggaran frame, pengukuran SM-A176B, protokol uji.
- [`mvp-priority.md`](mvp-priority.md) - R-P0-1..R-P0-4 dan matriks bukti resource §5.
- [`device-compatibility.md`](device-compatibility.md) - profil perangkat dan ambang RAM.
- [`android-sync.md`](android-sync.md) - outbox dan retensi yang ikut menambah PSS.
- [`testdata.md`](testdata.md) - klip uji yang dipakai harness.
