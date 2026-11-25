# Arsitektur

Status implementasi ada di [`status.md`](status.md). Dokumen ini menjelaskan
**target arsitektur**; bagian yang belum dibuktikan ditandai secara eksplisit dan
tidak boleh dibaca sebagai fitur yang sudah berjalan.

## 1. Posisi sistem

Sistem **ditujukan** sebagai *on-device intelligence pipeline*. Seluruh beban berat
(RTSP, dekode, inferensi, geometri) akan dieksekusi di perangkat Android. VPS bersifat
opsional dan hanya menangani metadata.

```
Kamera EZVIZ  --RTSP/802.1X Wi-Fi lokal-->  Android (edge gateway)  --JSON-->  VPS
```

## 2. Komponen

### stream-engine

- Menyediakan klien RTSP (`retina` di fitur `rtsp`) yang menghasilkan access unit.
  Belum ada loop produksi yang menghubungkan klien ini ke ring buffer dan sampler.
- Menyediakan tipe sumber **RTSP live** dan **file video lokal**. Sumber file masih
  memakai abstraksi decoder; belum ada decoder video konkret di workspace.
- Menghasilkan **access unit terenkode** (Annex B), bukan frame RGB. Decode dan
  kepemilikan preview masih menjadi keputusan terbuka; lihat ADR 0009.
- Menyediakan *ring buffer* pra-alokasi dan sampler untuk konsumen frame.
- Menyediakan kebijakan exponential backoff. Kebijakan itu belum dipasang ke loop
  reconnect produksi.
- Menyediakan sampler 2-5 FPS. Jalur decode ke sampler dan AI belum tersambung;
  lihat ADR 0009.

### ai-engine

- Menjalankan YOLOv11n dalam format ONNX lewat `ort` dengan dukungan Android NDK.
- Praproses (resize, normalisasi) memakai `image` + `ndarray`.
- Keluaran: daftar `BoundingBox` (koordinat, confidence, class id); hanya kelas
  `person` yang dipakai hilir.
- Sesi ONNX dibuat sekali dan dipakai ulang; tidak ada reload per frame.

### spatial-engine

- Menyimpan definisi meja sebagai poligon dalam ruang ternormalisasi 0..1 terhadap frame
  (lihat ADR 0008), sehingga zoom UI dan perubahan resolusi tidak membatalkan kalibrasi.
- Menghitung **titik tengah (center box)** bounding box lalu menguji
  *point-in-polygon*. Implementasi saat ini belum memakai foot anchor; perbandingan
  opsi ada di [`acceptance.md`](acceptance.md) §4.
- Menerapkan debounce berbasis waktu supaya status tidak berkedip karena noise piksel.
- Menghasilkan transisi status: `available` <-> `occupied`.

### core-ffi

- Mengekspos `ai-engine` dan `spatial-engine` melalui satu pustaka native untuk
  Kotlin. `stream-engine` belum tersambung ke pipeline FFI; dependensinya baru
  dipakai untuk tipe error dan redaksi endpoint.
- Mengekspos API ke Kotlin lewat UniFFI (bindings Kotlin digenerate, bukan ditulis tangan).
- Menghasilkan `.so` untuk `arm64-v8a`, `armeabi-v7a`, dan `x86_64`; AI hanya
  didukung pada `arm64-v8a`. Menyediakan tipe data bersama
  (`TableConfig`, `TableStatus`, `EngineEvent`).
- **Kondisi saat ini:** API tidak membuka RTSP. Kontrak yang diekspos adalah
  `Engine.start(...)` lalu `push_frame(width, height, rgb)`. Belum ada pemanggil
  Kotlin untuk `Engine.start`/`push_frame`; yang sudah ada adalah sink konfigurasi
  meja. `EngineConfig` belum memuat URL kamera, decoder, atau konfigurasi Surface.
- Jalur `stream-engine` -> decode -> `ai-engine` belum tersambung ke preview maupun
  `push_frame`. Klaim "satu koneksi, satu decode" adalah target, bukan fakta.

### android (Kotlin)

- **Status:** scaffold parsial. Gradle root dan modul `app`, `core:model`,
  `core:database`, serta `feature:tables` sudah ada. `core:model` berisi model
  domain, geometri, dan codec JSON (29 test lulus); `feature:tables` berisi layar
  mapping, editor, repository, dan ViewModel (47 test lulus, termasuk 10 test
  Compose); Room v1 menyimpan revisi konfigurasi meja; manifest dan `MainActivity`
  ada; `:app:assembleDebug` menghasilkan APK dan lint 0 error. Login, Home, Scan,
  foreground service, outbox, dan preview belum ada.
- Rencana UI: preview video, overlay poligon meja, warna status
  (hijau/kosong, merah/terisi).
- Preview direncanakan lewat Media3. Jika deteksi Rust membuka RTSP sendiri,
  rancangan itu menghasilkan dua koneksi sampai ADR 0009 diputuskan.
- Mapping: pengguna menggambar/mengatur poligon di atas preview yang bisa di-zoom dan
  di-pan; hasilnya dikonversi ke 0..1 sebelum dikirim ke Rust dan disimpan sebagai JSON.
- Foreground service + notifikasi permanen agar proses tidak dimatikan OS.
- Menerima event dari Rust dan memperbarui UI hanya saat status berubah (event-driven).

### server

- **Status:** folder `server/` masih kosong; seluruh kontrak di bawah adalah target.
- Menerima metadata JSON dari aplikasi (REST/WebSocket).
- Menyimpan riwayat status dan menyajikan dashboard/POS.
- Tidak menerima video dan tidak menjalankan model.
- Menerima heartbeat teknis (30-60 detik) dan menghitung metrik kesehatan perangkat.
- Menyimpan outbox ACK, mendeteksi gap `seq`, dan menghitung durasi/table turnover.
- Detail ada di [`monitoring.md`](monitoring.md).

## 3. Alur per frame

### 3.1 Target akhir (belum dipilih)

Target akhir yang diinginkan adalah satu koneksi kamera dan satu decode:

1. `stream-engine` membuka RTSP dan mendekode stream.
2. Frame masuk ring buffer; `ai-engine` mengambil frame terbaru.
3. Deteksi `person` menghasilkan bounding box + confidence.
4. `spatial-engine` menghitung titik tengah, uji poligon, dan debounce.
5. Jika status berubah, `core-ffi` mengirim event ke Kotlin.
6. Kotlin memperbarui UI dan (opsional) mengantre metadata untuk dikirim ke server.

Pilihan decoder dan jembatan preview (Media3 penuh vs Surface dari Rust vs hybrid
sementara) belum diputuskan; lihat [`adr/0009-video-pipeline.md`](adr/0009-video-pipeline.md).

### 3.2 Kontrak FFI saat ini

API Rust berikut sudah ada dan diuji di host. Belum ada aplikasi Kotlin lengkap di
repo yang memanggilnya, jadi urutan ini adalah kontrak integrasi, bukan alur
aplikasi yang sudah berjalan:

1. Pemanggil membuka preview dan memegang lifecycle-nya. Sumber preview masih
   keputusan terbuka ADR 0009.
2. Pemanggil mengubah frame menjadi RGB888 dan memanggil `push_frame`.
3. `core-ffi` menjalankan sampling, ONNX, geometri, dan debounce.
4. Event dikirim ke listener; status meja tidak dihitung di UI.
5. Jika koneksi preview gagal, pemanggil memanggil `report_stream_error`; semua
   meja menjadi `unknown`.

Konsekuensi: biaya copy frame dan kepemilikan buffer belum ditetapkan. Jangan
menambahkan klaim zero-copy untuk jalur `push_frame` sampai ada test alokasi/copy.

## 4. Keputusan desain penting

| Keputusan | Alasan |
| --- | --- |
| Inferensi di perangkat, bukan VPS | Hemat bandwidth, latensi rendah, tetap jalan saat internet putus |
| Rust + ONNX NDK | Footprint memori stabil, tanpa GC, cocok untuk long-running service |
| Ring buffer pra-alokasi | Menghindari fragmentasi memori pada service berjam-jam |
| Sampling 2-5 FPS | Status meja tidak butuh 25-30 FPS; menghemat CPU dan baterai |
| Event-driven ke UI | Event status ringan dan tidak membawa frame video |
| Sub-stream 360p/480p | Menurunkan beban dekode dan termal pada perangkat murah |

Catatan: desain target memisahkan event status dari frame video, tetapi kontrak FFI
saat ini masih menerima byte RGB melalui `push_frame`. Jalur itu bukan zero-copy
sampai biaya salinan dan ownership buffer diukur.

## 5. Risiko dan mitigasi

| Risiko | Mitigasi |
| --- | --- |
| Thermal throttling / baterai boros | Batasi FPS inferensi, pakai sub-stream, pantau suhu di uji lapangan |
| Memori tidak stabil pada service panjang | Tetapkan ownership/copy frame, batasi ukuran frame, dan uji soak minimal 8 jam |
| Cross-compile NDK gagal | Toolchain NDK terpasang eksplisit; build lewat skrip di `tools/android-build` |
| Koneksi RTSP putus | Auto-reconnect dengan backoff, status "stream down" terpisah dari status meja |
| HP beda jaringan dengan kamera | Klasifikasi error (ADR 0007) + tes koneksi 3 lapis; status `unknown`, bukan `available` |
| Akurasi poligon saat kamera bergeser | UI menyediakan kalibrasi ulang; simpan versi konfigurasi |
| Internet putus | Antrean lokal + sinkronisasi ulang (offline-first) |
| Perangkat mati tanpa terdeteksi | Heartbeat + alert Uptime Kuma; lihat `monitoring.md` |
| Crash native tidak terdiagnosis | Crashlytics + upload symbol NDK setiap rilis |
| Data monitoring membebani VPS 2 GB | Retensi/partisi heartbeat, agregasi harian |
| Klaim dokumen lebih maju daripada kode | Setiap status harus merujuk artefak; lihat `status.md` |
| Dua koneksi RTSP saat transisi | Batasi ke build debug; selesaikan ADR 0009 sebelum rilis |
| Device tidak mampu menjalankan model | Diagnostik onboarding + profil `lite`/`blocked`; lihat `device-compatibility.md` |

## 6. Di luar cakupan

- Multi-kamera dalam satu perangkat pada iterasi pertama.
- Identifikasi orang (wajah/ID) dan analitik pelanggan.
- Rekaman video terpusat di VPS.
- Model kustom selain YOLOv11n.
- APM terdistribusi (Prometheus/Grafana) dan multi-tenant billing.
