# Monitoring & sinkronisasi

Dokumen ini merinci lapisan observability dan sinkronisasi data. Prinsipnya sama dengan
arsitektur utama: perangkat tetap bekerja saat internet putus, dan VPS hanya menerima
metadata.

## 1. Dua lapis monitoring

| Lapis | Sasaran | Sifat data | Tujuan |
| --- | --- | --- | --- |
| Teknis | Kesehatan app, engine, dan perangkat | Diagnostik | Deteksi crash, kamera putus, HP panas, antrean macet |
| Bisnis | Status dan riwayat meja | Operasional | Dashboard real-time, durasi kunjungan, laporan okupansi |

Keduanya dikirim lewat jalur terpisah agar masalah jaringan tidak menahan data bisnis,
dan sebaliknya. Data teknis tidak boleh memblokir antrean event meja.

## 2. Monitoring teknis

### 2.1 Crash & error reporting

- Cakupan: panic Rust yang melintasi JNI/UniFFI, segfault native, exception Kotlin, ANR.
- Rekomendasi: Firebase Crashlytics (gratis, menerima symbol NDK/native) atau Sentry.
  Untuk VPS 2 GB, Sentry self-host berisiko; pakai tier eksternal bila memilih Sentry.
- Wajib: unggah symbol build `arm64-v8a` setiap rilis. Tanpa symbol, stack trace native
  tidak terbaca.
- Crash tidak boleh mematikan foreground service secara diam-diam: proses harus restart
  otomatis dan mengirim event `engine_restarted` saat hidup kembali.
- Simpan `session_id` per start service agar crash berulang bisa dikelompokkan.

### 2.2 Heartbeat & device health

Perangkat mengirim heartbeat ringan ke VPS setiap 30 detik (60 detik saat idle).
Kontrak lengkap ada di `data-contracts.md` §5. Metrik minimum:

- status koneksi RTSP, jumlah reconnect dalam 1 jam, dan umur frame terakhir;
- FPS input dan FPS inferensi aktual;
- latensi inferensi p50/p95;
- RAM terpakai/bebas dan suhu baterai;
- status termal Android (`NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`);
- panjang antrean offline dan umur event tertua;
- jumlah meja per status (`occupied`, `available`, `unknown`);
- versi app dan versi konfigurasi poligon.

Heartbeat tidak memuat gambar, video, URL RTSP, atau data pribadi.

### 2.3 Structured logging

- Rust memakai `tracing` dengan output JSON baris-per-event; Kotlin memakai logger
  terstruktur yang meneruskan `device_id` dan `session_id`.
- Log disimpan sebagai rolling file lokal (3 x 5 MB) agar tidak memenuhi storage.
- Yang dicatat: perubahan status meja, error reconnect RTSP, kegagalan inferensi,
  kegagalan sync, dan perubahan versi konfigurasi/poligon.
- Log rutin **tidak** diunggah. Hanya bundel log saat error kritis atau saat diminta
  dari dashboard (opt-in, maksimal 5 MB, tanpa frame video).
- Kredensial RTSP wajib di-redact sebelum log ditulis (`rtsp://***:***@host`).

### 2.4 Metrik dan ambang alert

Ambang berikut adalah nilai awal; tuning dilakukan pada uji lapangan.

| Metrik | Normal | Warning | Critical | Tindakan otomatis |
| --- | --- | --- | --- | --- |
| Umur heartbeat | < 90 s | 90-180 s | > 180 s | Alert; UI menandai perangkat offline |
| Status RTSP | `connected` | reconnect > 3/jam | putus > 2 menit | Reconnect backoff; alert ke Telegram |
| FPS inferensi | >= 2.0 | 1.0-2.0 | < 1.0 selama 5 menit | Turunkan resolusi/frame; alert |
| Latensi inferensi p95 | < 100 ms | 100-200 ms | > 200 ms | Turunkan FPS; catat profil perangkat |
| Suhu baterai | < 42 C | 42-48 C | > 48 C | Turunkan ke 1 FPS, hentikan preview |
| RAM bebas | > 300 MB | 150-300 MB | < 150 MB | Restart engine secara graceful |
| Antrean offline | < 100 event | 100-1.000 | > 1.000 atau tertua > 15 menit | Alert; jangan hapus antrean |
| Storage bebas | > 500 MB | 200-500 MB | < 200 MB | Hapus log tertua; alert |
| Crash-free session | >= 99,5% / 7 hari | 98-99,5% | < 98% | Periksa rilis terakhir; rollback bila perlu |

Status `unknown` pada meja tidak dihitung sebagai error; hanya dipakai saat stream mati
atau model belum siap.

## 3. Sinkronisasi data bisnis

### 3.1 Data yang dikirim

- Perubahan status meja: `table_id`, `status`, `since`, `persons_in_region`, `confidence`.
- Heartbeat teknis lewat endpoint terpisah.
- Backup konfigurasi poligon (versi + resolusi video).
- Thumbnail opsional, default nonaktif.

### 3.2 Data yang tidak dikirim

- Video RTSP, frame penuh, audio, atau rekaman layar.
- Wajah, identitas, atau ciri fisik pelanggan.
- Log rutin dan data lokasi perangkat.

### 3.3 Thumbnail opsional

- Default **nonaktif**; diaktifkan per perangkat oleh pemilik.
- JPEG 320p kualitas 50%, target 15-25 KB.
- Dikirim hanya saat status berubah, maksimal 1 per menit, atau tiap 5-10 menit bila
  mode verifikasi visual dinyalakan.
- Crop ke area meja bila memungkinkan; hindari wajah. Retensi maksimal 7 hari.
- Kegagalan upload thumbnail tidak boleh menahan antrean event status.

### 3.4 Backup konfigurasi meja (2 arah)

- Setiap perubahan poligon di HP disimpan sebagai revisi baru di VPS.
- VPS menyimpan maksimal 10 revisi terakhir per perangkat.
- Restore bersifat manual dari UI, bukan otomatis, agar konfigurasi lama tidak menimpa
  kalibrasi baru.
- Bila resolusi video berubah, aplikasi menandai poligon perlu kalibrasi ulang.
- Konflik diselesaikan dengan `version` naik (last-write-wins) dan riwayat tetap disimpan.

## 4. Sinkronisasi offline-first

### 4.1 Antrean lokal (outbox)

- Tabel `outbox` di Room/SQLite: `seq`, `payload`, `created_at`, `attempts`, `is_synced`.
- Tulis ke outbox **sebelum** mengirim; update `is_synced` hanya setelah ACK.
- Retensi minimal 7 hari atau 50.000 event; hapus hanya event yang sudah di-ACK.
- `seq` monoton per perangkat dan persisten. Reset hanya saat factory reset, dengan
  `device_id` baru.

### 4.2 Realtime vs batch

| Jalur | Teknologi | Jaminan | Pemakaian |
| --- | --- | --- | --- |
| Realtime | WebSocket | Best-effort | Warna meja di dashboard dalam hitungan detik |
| Durabilitas | REST batch via WorkManager | At-least-once + ACK | Semua event, termasuk saat offline |

Event tetap masuk outbox meski realtime aktif. WebSocket hanya mempercepat tampilan;
penghapusan dari outbox tetap menunggu ACK batch.

### 4.3 Alur reconnect

1. WorkManager mendeteksi jaringan tersedia dan memulai `POST /v1/sync`.
2. Server memvalidasi skema, melakukan deduplikasi `(device_id, seq)`, menulis dalam satu
   transaksi, lalu membalas `acked_seq`.
3. Perangkat menghapus event dengan `seq <= acked_seq`.
4. Bila server membalas `409` dengan `expected_seq`, perangkat mengirim ulang mulai dari
   `seq` tersebut.
5. Bila data lokal sudah terhapus, perangkat mengirim snapshot status terkini lewat
   `POST /v1/snapshot` agar server bisa rekonsiliasi.

Batch dibatasi 100 event atau 64 KB. Backoff 30 detik, 1 menit, 5 menit, lalu maksimal
15 menit.

### 4.4 Idempotensi dan deteksi kehilangan data

- Server menyimpan `last_seq` per `device_id` dan unique constraint `(device_id, seq)`.
- Gap `seq` menghasilkan `409` dan tidak boleh diabaikan.
- Event dengan `seq` lebih kecil atau sama dengan `last_seq` dibalas sukses tanpa ditulis
  ulang (idempoten).
- Metrik `events_received`, `events_duplicate`, `events_gap` diekspos di dashboard.

### 4.5 Waktu dan durasi sesi

- Perangkat memakai jam lokal untuk `since`; jam bisa drift.
- Server menyimpan `received_at` dan memakai urutan terima sebagai acuan durasi.
- Durasi sesi dan table turnover dihitung **di server** dari transisi status, bukan dari
  payload sesi terpisah.
- Bila selisih `since` dan `received_at` lebih dari 2 menit, event ditandai `clock_skew`
  untuk audit, tetapi tetap diproses.

## 5. Stack VPS minimal (2 core / 2 GB)

| Komponen | Pilihan | Catatan |
| --- | --- | --- |
| API + WebSocket | Rust Axum (satu binary) | Selaras dengan repo Rust; alternatif Laravel/Bun |
| Database | PostgreSQL 16 | Event dan heartbeat dipartisi per bulan |
| Reverse proxy | Caddy | TLS otomatis |
| Uptime & alert | Uptime Kuma (Docker) | Notifikasi Telegram; fallback email |
| Crash reporting | Firebase Crashlytics | Tidak memakai resource VPS |
| Backup | `pg_dump` harian | Simpan 14 hari di object storage |
| Dashboard | Web sederhana (server-side) | Baca DB, tidak menjalankan inferensi |

Perkiraan beban: satu perangkat menghasilkan ~2.880 heartbeat/hari dan ratusan event.
Seratus perangkat masih di bawah 10 GB/bulan bila retensi heartbeat 90 hari dan event
diagregasi harian.

## 6. Alerting & runbook

Kanal utama Telegram, fallback email. WhatsApp opsional dan berbayar.

| Severity | Kondisi | Aksi pertama |
| --- | --- | --- |
| P1 | Heartbeat hilang > 5 menit atau kamera putus > 10 menit | Cek daya, Wi-Fi, dan notifikasi foreground; minta staf restart app |
| P2 | Suhu critical atau RAM kritis berulang | Turunkan FPS, hentikan preview, cek ventilasi perangkat |
| P3 | Antrean menumpuk > 15 menit | Cek internet dan kesehatan server; jangan hapus database |
| P3 | Crash spike setelah rilis | Cek symbol upload; pertimbangkan rollback |
| P4 | Storage/DB mendekati penuh | Prune log/heartbeat lama; tambah volume |

Runbook singkat:

- **Perangkat offline**: pastikan charger terpasang, Wi-Fi warung hidup, dan notifikasi
  Monitoring Meja Aktif masih ada. Bila app dimatikan OS, buka app sekali.
- **Kamera putus**: cek adaptor/PoE dan IP kamera; app akan reconnect otomatis. Ganti
  password RTSP hanya lewat UI agar tidak salah ketik.
- **Panas**: jauhkan HP dari sinar matahari langsung, lepas casing, turunkan FPS ke 1.
- **Antrean macet**: cek `GET /health` server dan koneksi internet; event tetap aman di
  perangkat sampai terkirim.
- **Crash berulang**: kumpulkan bundel log, cek versi app dan model, lalu rollback bila
  perlu.

## 7. SLO dan metrik keberhasilan

| SLO | Target |
| --- | --- |
| Kesegaran heartbeat p95 | < 90 detik |
| Latensi event ke dashboard p95 (online) | < 5 detik |
| Durabilitas event setelah ACK | 100%, tanpa duplikat |
| Crash-free sessions (7 hari) | >= 99,5% |
| Pengiriman alert | < 2 menit |
| Video/PII terkirim | 0 (audit bulanan) |
| Waktu pulih setelah internet kembali | < 5 menit untuk antrean normal |

## 8. Privasi & keamanan

- TLS 1.2+ untuk semua endpoint; token per perangkat, bukan password bersama.
- Token disimpan di Android Keystore dan bisa dicabut dari dashboard.
- Server tidak menyimpan video. Thumbnail default nonaktif dan dihapus otomatis.
- Log tidak boleh memuat kredensial RTSP, token, atau data pribadi.
- Dashboard memakai autentikasi; akses staf bersifat read-only.
- Sesuai semangat UU PDP: minimalkan data, beri papan informasi di lokasi, dan sediakan
  kebijakan retensi yang jelas.

## 9. Rencana implementasi

| Tahap | Deliverable | Kriteria selesai |
| --- | --- | --- |
| Fase 6 | Endpoint sync/heartbeat/ACK, outbox Room, WorkManager, dashboard status | Putus internet 10 menit, tersinkron tanpa duplikat; event tampil < 5 detik |
| Fase 7 | Crash reporting, `tracing`, dashboard kesehatan, Uptime Kuma, alert Telegram | Simulasi kamera putus, perangkat offline, dan panic memicu alert < 5 menit |
| Fase 8-9 | Soak test 7 hari + tuning ambang + runbook final + hardening | Tidak ada alert yang tidak tertangani; akurasi status terjaga |

## 10. Di luar cakupan

- APM terdistribusi, Prometheus/Grafana, dan tracing lintas service (berlebihan untuk 2 GB).
- Rekaman video terpusat, analitik wajah, dan identifikasi pelanggan.
- Multi-tenant billing dan portal pelanggan.

## Catatan sumber

Perencanaan ini diadaptasi dari diskusi arsitektur monitoring pada
<https://share.gemini.google/TSS8ugXd9LBP> dan diselaraskan dengan kontrak data serta
roadmap repo ini.
