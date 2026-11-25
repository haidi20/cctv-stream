# Arsitektur

## 1. Posisi sistem

Sistem berjalan sebagai *on-device intelligence pipeline*. Seluruh beban berat (RTSP,
dekode, inferensi, geometri) dieksekusi di perangkat Android. VPS bersifat opsional dan
hanya menangani metadata.

```
Kamera EZVIZ  --RTSP/802.1X Wi-Fi lokal-->  Android (edge gateway)  --JSON-->  VPS
```

## 2. Komponen

### stream-engine

- Menarik stream RTSP dari kamera EZVIZ memakai runtime Tokio.
- Menyediakan dua sumber: **RTSP live** dan **file video lokal** untuk pengujian.
- Menulis frame ke *ring buffer* pra-alokasi (zero-copy) supaya tidak ada alokasi
  berulang di loop panas.
- Auto-reconnect dengan exponential backoff saat Wi-Fi putus atau kamera restart.
- Melakukan *frame skipping*: hanya 2-5 FPS yang diteruskan ke AI.

### ai-engine

- Menjalankan YOLOv11n dalam format ONNX lewat `ort` dengan dukungan Android NDK.
- Praproses (resize, normalisasi) memakai `image` + `ndarray`.
- Keluaran: daftar `BoundingBox` (koordinat, confidence, class id); hanya kelas
  `person` yang dipakai hilir.
- Sesi ONNX dibuat sekali dan dipakai ulang; tidak ada reload per frame.

### spatial-engine

- Menyimpan definisi meja sebagai poligon dalam ruang ternormalisasi 0..1 terhadap frame
  (lihat ADR 0008), sehingga zoom UI dan perubahan resolusi tidak membatalkan kalibrasi.
- Menghitung centroid bounding box lalu menguji *point-in-polygon* (`geo`).
- Menerapkan debounce berbasis waktu supaya status tidak berkedip karena noise piksel.
- Menghasilkan transisi status: `available` <-> `occupied`.

### core-ffi

- Menyatukan ketiga engine menjadi satu pustaka native.
- Mengekspos API ke Kotlin lewat UniFFI (bindings Kotlin digenerate, bukan ditulis tangan).
- Menghasilkan `.so` untuk `jniLibs/arm64-v8a` dan menyediakan tipe data bersama
  (`TableConfig`, `TableStatus`, `EngineEvent`).

### android (Kotlin)

- UI: preview video, overlay poligon meja, warna status (hijau/kosong, merah/terisi).
- Mapping: pengguna menggambar/mengatur poligon di atas preview yang bisa di-zoom dan
  di-pan; hasilnya dikonversi ke 0..1 sebelum dikirim ke Rust dan disimpan sebagai JSON.
- Foreground service + notifikasi permanen agar proses tidak dimatikan OS.
- Menerima event dari Rust dan memperbarui UI hanya saat status berubah (event-driven).

### server

- Menerima metadata JSON dari aplikasi (REST/WebSocket).
- Menyimpan riwayat status dan menyajikan dashboard/POS.
- Tidak menerima video dan tidak menjalankan model.
- Menerima heartbeat teknis (30-60 detik) dan menghitung metrik kesehatan perangkat.
- Menyimpan outbox ACK, mendeteksi gap `seq`, dan menghitung durasi/table turnover.
- Detail ada di [`monitoring.md`](monitoring.md).

## 3. Alur per frame

1. `stream-engine` membaca frame (RTSP atau file) dan melakukan sampling FPS.
2. Frame masuk ring buffer; `ai-engine` mengambil frame terbaru.
3. Deteksi `person` menghasilkan bounding box + confidence.
4. `spatial-engine` menghitung centroid, uji poligon, dan debounce.
5. Jika status berubah, `core-ffi` mengirim event ke Kotlin.
6. Kotlin memperbarui UI dan (opsional) mengantre metadata untuk dikirim ke server.

## 4. Keputusan desain penting

| Keputusan | Alasan |
| --- | --- |
| Inferensi di perangkat, bukan VPS | Hemat bandwidth, latensi rendah, tetap jalan saat internet putus |
| Rust + ONNX NDK | Footprint memori stabil, tanpa GC, cocok untuk long-running service |
| Ring buffer pra-alokasi | Menghindari fragmentasi memori pada service berjam-jam |
| Sampling 2-5 FPS | Status meja tidak butuh 25-30 FPS; menghemat CPU dan baterai |
| Event-driven ke UI | UI tetap ringan; tidak ada transfer matriks/video ke Kotlin |
| Sub-stream 360p/480p | Menurunkan beban dekode dan termal pada perangkat murah |

## 5. Risiko dan mitigasi

| Risiko | Mitigasi |
| --- | --- |
| Thermal throttling / baterai boros | Batasi FPS inferensi, pakai sub-stream, pantau suhu di uji lapangan |
| Memori tidak stabil pada service panjang | Zero-copy buffer, uji soak test minimal 8 jam |
| Cross-compile NDK gagal | Toolchain NDK terpasang eksplisit; build lewat skrip di `tools/android-build` |
| Koneksi RTSP putus | Auto-reconnect dengan backoff, status "stream down" terpisah dari status meja |
| HP beda jaringan dengan kamera | Klasifikasi error (ADR 0007) + tes koneksi 3 lapis; status `unknown`, bukan `available` |
| Akurasi poligon saat kamera bergeser | UI menyediakan kalibrasi ulang; simpan versi konfigurasi |
| Internet putus | Antrean lokal + sinkronisasi ulang (offline-first) |
| Perangkat mati tanpa terdeteksi | Heartbeat + alert Uptime Kuma; lihat `monitoring.md` |
| Crash native tidak terdiagnosis | Crashlytics + upload symbol NDK setiap rilis |
| Data monitoring membebani VPS 2 GB | Retensi/partisi heartbeat, agregasi harian |

## 6. Di luar cakupan

- Multi-kamera dalam satu perangkat pada iterasi pertama.
- Identifikasi orang (wajah/ID) dan analitik pelanggan.
- Rekaman video terpusat di VPS.
- Model kustom selain YOLOv11n.
- APM terdistribusi (Prometheus/Grafana) dan multi-tenant billing.
