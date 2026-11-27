# Data uji

Dokumen ini mengatur asal, lisensi, penamaan, dan penggunaan video/gambar uji. Prinsipnya:
**video uji bukan bahan training model**, melainkan bahan validasi pipeline
(`stream-engine` → `ai-engine` → `spatial-engine`).

Status dokumen: **rencana dan aturan penggunaan data**, bukan bukti bahwa semua
scenario sudah dapat dijalankan. `testdata/expected/` masih hanya berisi `.gitkeep`,
dan belum ada footage sintetis maupun hasil anotasi yang di-commit.

## 1. Peran video uji

| Bukan untuk | Untuk |
| --- | --- |
| Melatih ulang YOLOv11n | Menguji ring buffer, pacing FPS, dan reconnect |
| Menambah kelas objek baru | Menguji akurasi poligon dan debounce |
| Menggantikan uji lapangan | Reproduksi bug tanpa kamera fisik |

Model tetap YOLOv11n pra-latih (COCO, kelas `person`). Video uji hanya mensimulasikan
feed CCTV; hasil deteksi dipakai memverifikasi logika, bukan memperbaiki bobot model.

## 1.1 Di mana "training" sebenarnya terjadi

Kata "training" sering dipakai untuk dua hal yang berbeda. Di proyek ini hanya satu yang
benar-benar melatih bobot model, dan itu **tidak** terjadi di Kotlin maupun Rust.

| Yang biasa disebut "training" | Di proyek ini | Butuh video? |
| --- | --- | --- |
| Melatih bobot YOLOv11n (deteksi orang) | **Tidak dilakukan.** Model COCO pra-latih sudah punya kelas `person` | Tidak |
| Melatih ulang model untuk warung tertentu | Pekerjaan terpisah, di luar repo (Python + dataset berlabel) | Ya, banyak |
| "Melatih" pipeline agar kalibrasi benar | Bukan training; ini **validasi** di Rust + test di Kotlin | Ya, klip pendek |
| Demo tanpa kamera | Video sebagai pengganti RTSP | Ya, klip pendek |

Kotlin dan Rust tidak punya optimizer, gradient, atau bobot yang berubah saat aplikasi
berjalan. Yang "belajar" hanya konfigurasi meja (poligon 0..1) yang diisi pengguna sekali.
Karena itu klip stok ini fungsinya **input uji**, bukan dataset training.

Kalau nanti memang perlu melatih ulang model, aturannya berbeda: butuh dataset berlabel
(bounding box per orang), split train/val, dan hasilnya file `.onnx` baru. Itu alur
terpisah di `tools/model-export/`, bukan di `stream-engine` atau `spatial-engine`.

### 1.1.1 Kalau tetap ingin klip ini dipakai untuk melatih model

Klip stok 10-25 detik tidak cukup untuk training yang berguna, tetapi bisa jadi bahan
awal anotasi. Syarat minimumnya:

- Label bounding box `person` per frame kunci (mis. setiap 5 frame), bukan hanya poligon
  meja. Poligon meja tidak dipakai model; model hanya mengenal kotak objek.
- Split `train`/`val` berdasarkan **klip**, bukan frame. Kalau frame dari klip yang sama
  masuk keduanya, skor validasi akan terlalu optimistis.
- Video berwatermark jangan dipakai untuk training: watermark bisa dianggap pola objek.
- Untuk fine-tuning, YOLOv11n butuh ribuan gambar berlabel; 4 klip ini realistis untuk
  smoke test alur anotasi, bukan untuk menaikkan akurasi.
- Hasil model baru tetap diekspor ke ONNX dan diverifikasi ulang dengan `expected/*.json`,
  jadi langkah validasi di §4 tetap wajib.

## 1.2 Video sebagai pengganti kamera untuk uji performa

Kamera EZVIZ belum tersedia, tetapi uji **performa dan resource** (baterai, RAM, ROM, CPU,
suhu) tetap bisa dijalankan sekarang dengan klip di `testdata/videos/`. Alasannya: biaya
utama ada di decode + inferensi, dan keduanya berjalan sama saat frame berasal dari klip
maupun dari RTSP.

| Yang tercakup oleh klip | Yang tidak tercakup |
| --- | --- |
| Decode Media3, capture `TextureView`, `EngineFramePump`, `push_frame`, ORT, spatial | Radio Wi-Fi, reconnect RTSP, latensi jaringan kamera |
| Beban CPU/GPU dan panas dari inferensi 640 | Variasi bitrate/kualitas kamera nyata |
| Pertumbuhan PSS dan storage selama sesi panjang | Perilaku kamera saat mati mendadak |

Aturan pakai:

- Uji resource **tidak** menunggu `expected/*.json`. Fixture itu untuk akurasi; profil
  resource cukup memakai klip yang sama berulang dengan `loop: true`.
- Klip > 20 detik lebih baik untuk soak karena setiap pengulangan melatih jalur EOF/loop.
- Watermark iStock tidak menghalangi uji lokal; larangannya hanya untuk commit, APK debug
  yang dibagikan, dan materi pemasaran (§6.4).
- Hasil uji resource dilaporkan sebagai **batas bawah** beban lapangan. Jangan menulis
  "baterai aman di warung" dari data klip.

Protokol pengukuran ada di [`performance.md`](performance.md) §10; ambang gate dan urutan
prioritas ada di [`mvp-priority.md`](mvp-priority.md) §5.

## 2. Sumber footage

| Sumber | Kunci pencarian | Lisensi | Catatan |
| --- | --- | --- | --- |
| Pexels | `restaurant tables`, `empty restaurant`, `cafe interior`, `food court`, `indoor restaurant overhead` | Pexels License (gratis, komersial) | HD-4K; cocok untuk uji resolusi |
| Pixabay Video | `restaurant`, `dining tables`, `empty restaurant table` | Pixabay Content License | Bebas royalti |
| Mixkit | `restaurant interior`, `people in cafe` | Mixkit License | Klip pendek, mudah dipotong |
| Videvo | `dining room`, `commercial space` | Bervariasi per klip | **Cek lisensi tiap file** |
| Kaggle / GitHub dataset | `table occupancy`, `restaurant surveillance` | Bervariasi | Paling relevan untuk sudut CCTV |
| YouTube (rekaman publik) | `restaurant security camera footage`, `cafe cctv feed` | **Tidak bebas** | Hanya untuk eksperimen lokal, jangan di-commit/di-redistribusi |

Aturan lisensi:

- Catat sumber + lisensi + tanggal unduh di `testdata/SOURCES.md`.
- Jangan commit klip berlisensi ke repo publik; simpan di mesin lokal atau object
  storage privat.
- Klip dari YouTube tidak boleh dipakai untuk demo publik atau materi pemasaran.
- Klip stok berwatermark (mis. Getty) hanya untuk eksperimen lokal; jangan di-commit,
  jangan dipakai screenshot untuk materi pemasaran.
- Jangan menghapus watermark untuk memakai ulang klip stok. Itu bukan jalur lisensi
  dan tidak mengubah status hak pakai klip.
- **Merekam sendiri lebih baik** bila memungkinkan: 10-15 detik, HP diletakkan tinggi
  menghadap meja, meniru sudut CCTV. Tidak ada masalah lisensi dan sudut pandangnya
  cocok dengan lokasi asli.

## 3. Struktur folder

```text
testdata/
├── SOURCES.md              # daftar klip: nama, sumber, lisensi, tanggal
├── videos/                 # klip mentah (tidak di-commit)
├── frames/                 # frame hasil ekstraksi (tidak di-commit)
├── expected/               # hasil yang diharapkan (di-commit)
│   ├── meja-01.json
│   └── ...
└── label/                  # anotasi bounding box bila melatih model (di-commit)
    └── person/
```

`.gitignore` sudah menutup `testdata/videos/`, `testdata/frames/`, `*.mp4`, `*.mkv`.
Yang boleh masuk repo hanya `SOURCES.md` dan `expected/*.json` berukuran kecil.

## 4. Skenario minimum

| Skenario | Durasi | Tujuan |
| --- | --- | --- |
| Warung kosong | 30 detik | Pastikan tidak ada false positive |
| Satu orang duduk lalu pergi | 60 detik | Transisi `available → occupied → available` |
| Dua orang di meja berbeda | 60 detik | Assignment multi-meja |
| Orang lewat di depan meja (tidak duduk) | 30 detik | Uji debounce, hindari salah status |
| Pencahayaan redup | 30 detik | Uji ambang confidence |
| Sudut miring / top-down | 30 detik | Uji poligon non-persegi |
| Zoom/pan mapping (klip statis) | 10 detik | Uji transform layar ke 0..1 pada meja kecil di pojok frame |

Setiap klip dipasangkan dengan `expected/<nama>.json`:

```json
{
  "clip": "warung-satu-orang",
  "fps": 25,
  "resolution": { "width": 1280, "height": 720 },
  "aspect_ratio": "16:9",
  "config_version": 2,
  "events": [
    { "at_s": 4.2, "table_id": "meja-01", "status": "occupied" },
    { "at_s": 38.7, "table_id": "meja-01", "status": "available" }
  ],
  "tolerance_s": 1.5
}
```

Toleransi waktu wajib ada; hasil tidak boleh diuji dengan exact timestamp. Field
`aspect_ratio` dan `config_version` wajib ada agar test menolak klip yang dipasangkan dengan
konfigurasi poligon dari rasio berbeda.

### 4.1 Footage sintetis `empty -> people -> empty`

Target uji adalah klip sintetis 30-60 detik dengan urutan meja kosong, orang masuk,
lalu meja kosong lagi. Klip ini harus deterministik, tanpa watermark, dan disimpan
bersama `expected/*.json` agar transisi bisa diuji ulang.

Status: **belum dibuat**. Belum ada generator, file video, atau expected fixture untuk
skenario ini. Jangan menganggap urutan sintetis sudah tercakup oleh empat klip stok.

### 4.2 Klip di build Android

Satu klip bernama `mock_restaurant_feed.mp4` boleh dibundel ke build **debug** untuk demo
tanpa CCTV. Aturannya:

- Sumber file tetap `testdata/videos/` (tidak di-commit). Build task menyalinnya ke
  `android/app/src/main/assets/` hanya pada varian debug; `.gitignore` menutup nama itu.
- Rust tidak bisa membaca `assets/` lewat `File::open`. Saat start, Kotlin menyalin aset
  ke `cacheDir/mock/mock_restaurant_feed.mp4` lalu mengirim **path filesystem** itu ke
  `file_source`. Kalau penyalinan gagal, engine melaporkan error, bukan diam-diam jatuh
  ke RTSP.
- Build `release` tidak menyertakan klip dan tidak punya cabang fallback. Tidak ada
  `if is_testing` di kode produksi.
- Klip yang dipakai harus dari sumber yang lisensinya jelas (lihat §2) atau rekaman
  sendiri. Video berwatermark (mis. Getty) tidak boleh masuk APK debug yang dibagikan.

## 5. Ekstraksi frame

Frame disimpan hanya untuk golden test AI (lihat `tdd-plan.md` §3.3):

```bash
ffmpeg -i testdata/videos/warung-satu-orang.mp4 \
  -vf "fps=1,scale=640:-1" \
  testdata/frames/warung-satu-orang-%03d.jpg
```

Aturan:

- Maksimal 10 frame per klip, resolusi <= 640 px, JPEG kualitas 80.
- Total `testdata/frames/` di mesin lokal < 50 MB.
- Nama file memuat detik asal agar mudah dilacak.

## 6. Cara memakai file lokal di engine

`stream-engine` merencanakan dua sumber dengan antarmuka sama (ADR 0003). Di test host,
path klip datang dari env var `TESTDATA_VIDEO` supaya CI bisa menaruhnya di mana saja.
Konfigurasi `source` di bawah adalah **bentuk target**; `EngineConfig` saat ini belum
memiliki field tersebut dan `core-ffi` masih menerima frame RGB lewat `push_frame`.
Test integrasi host yang membaca `TESTDATA_VIDEO` juga belum ada.

```bash
TESTDATA_VIDEO=/path/ke/testdata/videos/warung-satu-orang.mp4 cargo test -p stream-engine
```

Konfigurasi sumber:

```json
{
  "source": {
    "kind": "file",
    "path": "testdata/videos/warung-satu-orang.mp4",
    "fps": 25,
    "loop": true
  }
}
```

Pada bentuk target, ganti blok `source` dari file ke RTSP; modul hilir tidak berubah.
Saat ini `stream-engine` sudah memiliki enum `Source` (`Rtsp`/`File`), tetapi
`EngineConfig` FFI belum memiliki field `source` dan belum ada wiring ke engine.
Pacing FPS file lokal harus meniru FPS asli, kalau tidak hasil debounce tidak realistis.

### 6.1 Alur di Rust (stream-engine → ai-engine → spatial-engine)

Klip dipakai sebagai pengganti RTSP. Jalur di bawah adalah **target integrasi**;
decoder konkret dan sambungan ke `ai-engine` belum ada di workspace:

```
mp4 --file_source--> Frame --> frame_sampler --> ai-engine (YOLOv11n)
                                              --> spatial-engine (poligon 0..1, debounce)
                                              --> EngineEvent
```

Konkretnya:

- `FileSource` menerima implementasi `FrameDecoder`. Saat ini ia hanya mengatur pacing,
  loop, dan timestamp di atas trait tersebut; belum ada decoder media konkret
  (misalnya ffmpeg atau decoder Android). Jangan menulis bahwa `ffmpeg-next` sudah
  terpasang atau bahwa file MP4 sudah bisa didekode oleh crate ini.
- Pacing dan normalisasi PTS sudah diuji dengan fake decoder. Tanpa pacing, debounce
  berbasis waktu akan mengukur kecepatan disk, bukan kecepatan kamera.
- `loop: true` dipakai untuk soak test; `loop: false` untuk test yang butuh `EOF`.
- Frame tetap melewati `frame_sampler` 2-5 FPS. Jangan menaikkan sampling hanya karena
  klipnya bagus; angka FPS di lapangan yang menentukan.
- Klip dengan sudut tinggi lebih baik untuk menguji poligon; klip eye-level tetap berguna
  untuk menguji false positive saat orang lewat.

Test yang tersedia dan yang masih target:

| Test / area | Status | Yang dibuktikan atau dibutuhkan |
| --- | --- | --- |
| `src/file_source.rs` unit test | Ada | Pacing, EOF, loop, dan normalisasi PTS memakai fake decoder |
| `tests/public_api.rs` | Ada | `Source`, `Frame`, sampler, ring buffer, dan error surface |
| `tests/rtsp_client_mock.rs` | Ada | Perilaku klien RTSP dengan mock, bukan kamera nyata |
| Integrasi `mp4 -> ai-engine -> spatial-engine` | Belum | Butuh decoder konkret, model, dan expected fixture |
| Test soak file 30 menit | Belum | Butuh klip berlisensi jelas dan harness RSS |

### 6.2 Alur di Kotlin (instrumented test)

Kotlin tidak mendekode video **untuk analisis**; analisis tetap di Rust. Preview pada
target sementara tetap direncanakan lewat Media3, yang melakukan decode untuk tampilan.
Pemakaian klip di Kotlin masih berupa rencana:

| Kebutuhan | Cara | Catatan |
| --- | --- | --- |
| Instrumented test end-to-end | aset di `androidTest/assets/`, disalin ke `cacheDir` | bukan `main/assets`, jadi tidak masuk APK rilis |
| Demo debug di perangkat | aset `main/assets` varian debug | build task menyalin dari `testdata/videos/` |
| Preview UI tanpa engine | `FakeEngine` mengirim event | tidak butuh video sama sekali |

Urutan di instrumented test:

1. `InstrumentationRegistry` membaca aset klip dari `androidTest/assets/`.
2. Test menyalin ke `context.cacheDir/mock/feed.mp4` dan mencatat path filesystem-nya.
3. Path itu diteruskan lewat API file source yang belum ada. `EngineConfig` saat ini
   tidak memiliki field `source`; jangan membuat test integrasi sebelum kontrak ini
   diputuskan.
4. Engine Rust berjalan seperti biasa; UI memakai fake/rekaman event, bukan kamera.
5. Assert warna/status meja terhadap `testdata/expected/*.json` dengan toleransi waktu.

Klip di `androidTest/assets/` ikut aturan `.gitignore` yang sama (`*.mp4`), jadi tetap
disalin manual atau lewat Gradle task `copyTestVideos` sebelum test.

### 6.3 Kalau ingin video ikut repo

Default proyek ini: **tidak**. `*.mp4` di-ignore karena klip CCTV berukuran besar dan
sebagian berlisensi terbatas. Kalau suatu saat perlu, pilihannya:

- **Git LFS** untuk klip milik sendiri yang memang harus ikut versi. Tambahkan
  `*.mp4 filter=lfs diff=lfs merge=lfs -text` dan hapus pengecualiannya di `.gitignore`.
- **Unduh saat CI** dari URL resmi berlisensi, dengan checksum di `testdata/SOURCES.md`.
  Ini pilihan paling aman untuk repo publik.
- **Simpan di object storage privat** dan sediakan skrip pengambil.

Yang tidak boleh: memaksa `git add -f` klip stok berwatermark ke repo publik.

### 6.4 File yang sekarang ada di mesin lokal

Empat klip stok ini ada di `testdata/videos/` dan tetap **tidak di-commit**. Metadata
lengkap, checksum, dan matriks pemakaian ada di [`testdata/SOURCES.md`](../testdata/SOURCES.md).

| Nama file | Durasi | FPS | Resolusi | Keyframe | Pakai untuk |
| --- | --- | --- | --- | --- | --- |
| `istockphoto-1067633772-640_adpp_is.mp4` | 19,24 s | 25,00 | 768x432 | 2 | beban banyak `person`, pacing |
| `istockphoto-2187553973-640_adpp_is.mp4` | 5,17 s | 29,97 | 768x432 | 1 | EOF/loop, pacing non-bulat |
| `istockphoto-453990286-640_adpp_is.mp4` | 23,52 s | 25,00 | 768x432 | 3 | poligon/center box, soak |
| `istockphoto-678973826-640_adpp_is.mp4` | 16,00 s | 25,00 | 768x432 | 2 | satu transisi `available → occupied` |

Angka di tabel berasal dari header MP4, bukan perkiraan. Kolom "pakai untuk" mengikuti
deskripsi awal dan **belum diverifikasi visual**; lihat batasan di
[`testdata/SOURCES.md`](../testdata/SOURCES.md) §5.

Semua klip 16:9, H.264, tanpa audio, dan punya edit list + B-frame. PTS mentah tidak
mulai dari 0; `file_source` menormalkannya per putaran dan menambahkan offset waktu
tempuh putaran sebelumnya, sehingga `captured_at` tetap monotonik saat `loop: true`.
Klip terpanjang hanya 23,5 detik, jadi tidak bisa menutup skenario 30-60 detik di §4.

Semua berwatermark iStock dan bukti lisensinya belum ada di repo, jadi hanya untuk
eksperimen lokal: jangan di-commit, jangan masuk APK debug yang dibagikan, jangan
dipakai sebagai golden frame atau materi pemasaran. `expected/*.json` belum boleh diisi
sebelum isi klip ditonton dan perannya diverifikasi.

## 7. Kapan pindah ke kamera asli

Pindah ke RTSP EZVIZ setelah:

1. Skenario §4 hijau dengan file lokal.
2. Soak test 30 menit file lokal tanpa kenaikan RSS.
3. Poligon hasil mapping diuji ulang pada frame kamera nyata (posisi bisa berbeda).

Video uji tidak membuktikan akurasi di lokasi nyata; itu tetap tugas Fase 8
(`roadmap.md`).
