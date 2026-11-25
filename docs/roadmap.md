# Roadmap

Urutan fase mengikuti ketergantungan teknis: toolchain dulu, lalu stream, AI, spasial,
baru jembatan FFI dan UI. Setiap fase harus lulus kriterianya sebelum fase berikutnya.

## Fase 0 — Fondasi & toolchain

- Siapkan workspace Cargo (multi-crate) dan target `aarch64-linux-android`.
- Pasang Android NDK + LLVM/Clang; verifikasi build `cdylib` kosong.
- Siapkan proyek Android Studio minimal dan pipeline UniFFI.

**Selesai bila**: `cargo build --target aarch64-linux-android` sukses dan `.so` termuat di perangkat.

## Fase 1 — stream-engine

- Implementasi sumber RTSP (retina/Tokio) dan sumber file lokal.
- Ring buffer pra-alokasi + frame skipping.
- Auto-reconnect dengan exponential backoff dan metrik koneksi.
- Klasifikasi error koneksi di Rust (`unreachable`, `timeout`, `auth_failed`, `protocol`)
  tanpa membocorkan URL atau kredensial; Kotlin menambahkan `no_network`/`wrong_network`.

**Selesai bila**: stream RTSP kamera EZVIZ stabil 30 menit tanpa kebocoran memori;
file video lokal terbaca dengan FPS terjadwal; kamera yang tidak terjangkau (beda
jaringan) ditandai `unknown` dalam <= 5 detik, bukan `available` atau crash.

## Fase 2 — ai-engine

- Integrasi `ort` + YOLOv11n ONNX untuk `arm64-v8a`.
- Praproses frame dan pasca-proses (NMS) untuk kelas `person`.
- Benchmark latensi per frame di perangkat target.

**Selesai bila**: deteksi `person` benar pada video uji, latensi tercatat, RAM stabil
saat inferensi berjalan 30 menit.

## Fase 3 — spatial-engine

- Parser konfigurasi poligon meja (JSON) dengan koordinat ternormalisasi 0..1, termasuk
  penolakan `version: 1` dan koordinat di luar rentang.
- Point-in-polygon + centroid, dengan konversi piksel frame ke 0..1.
- Penjaga kalibrasi: rasio aspek stream berbeda dari konfigurasi menandai
  `needs_recalibration` tanpa mengubah poligon.
- Debounce berbasis waktu dan mesin status per meja.

**Selesai bila**: unit test menutup kasus titik di dalam/tepi/luar poligon, poligon yang
sama tetap valid di 540p dan 720p (rasio sama), dan skenario video uji menghasilkan
transisi status yang benar.

## Fase 4 — core-ffi

- Definisikan antarmuka UniFFI (`start`, `stop`, `set_tables`, event listener).
- Generate bindings Kotlin dan bangun `.so` untuk `jniLibs`.
- Tangani lifecycle: start/stop aman, tanpa panic melintasi FFI.

**Selesai bila**: aplikasi contoh Kotlin bisa start/stop engine dan menerima event status.

## Fase 5 — Android UI & foreground service

- Preview video dengan pinch-zoom 1..8x dan pan terbatas; konversi layar ke 0..1 sebelum
  dikirim, termasuk koreksi letterbox dan `base` fit.
- `MappingTransform` dan `ZoomState` diuji sebagai fungsi murni sebelum layar mapping
  dibuat.
- Foreground service + notifikasi permanen.
- Penyimpanan konfigurasi meja dan pemulihan setelah restart.

**Selesai bila**: pengguna bisa mapping meja, menyalakan monitoring, dan melihat warna
status berubah tanpa membuka aplikasi terus-menerus.

## Fase 6 — server metadata

Detail teknis: [`monitoring.md`](monitoring.md) §3-§5.

- Endpoint `POST /v1/sync` (batch + ACK `acked_seq`), `POST /v1/heartbeat`,
  `POST /v1/snapshot`, dan `GET /health`.
- Skema validasi payload, unique constraint `(device_id, seq)`, deteksi gap `409`.
- Penyimpanan riwayat, agregasi durasi sesi/table turnover, dan dashboard status.
- Outbox Room + WorkManager di perangkat; hapus event hanya setelah ACK.
- WebSocket best-effort untuk warna meja real-time.

**Selesai bila**: status dari perangkat muncul di dashboard; putus internet 10 menit
lalu tersinkron tanpa duplikat; event online tampil < 5 detik; gap `seq` terdeteksi
dan tidak ada event hilang.

## Fase 7 — observability perangkat

Detail teknis: [`monitoring.md`](monitoring.md) §2 dan §6.

- Heartbeat 30-60 detik: status RTSP, FPS, latensi p50/p95, RAM, suhu, termal, storage,
  panjang antrean, versi app/konfigurasi.
- Structured logging `tracing` (JSON) + rolling file; redaksi kredensial RTSP.
- Crashlytics (native + Kotlin) dengan upload symbol `arm64-v8a`; event `engine_restarted`.
- Uptime Kuma + alert Telegram; ambang awal mengikuti tabel di `monitoring.md`.

**Selesai bila**: simulasi kamera putus, perangkat offline, dan panic memicu alert
< 5 menit; tidak ada kredensial/PII di log.

## Fase 8 — Uji lapangan & operasional

- Uji di warung nyata: pencahayaan, sudut kamera, kepadatan pengunjung.
- Kalibrasi ulang poligon, tuning ambang debounce.
- Tuning ambang alert (suhu, FPS, antrean) berdasarkan data heartbeat nyata.
- Verifikasi runbook: perangkat offline, kamera putus, HP panas, antrean macet, crash.
- Soak test 7 hari + audit privasi (tidak ada video/PII keluar perangkat).
- Panduan instalasi untuk pemilik warung + checklist perawatan.

**Selesai bila**: berjalan 7 hari berturut-turut dengan status akurat, tidak ada alert
yang tidak tertangani, dan tidak ada intervensi manual selain yang ada di runbook.

## Fase 9 — Hardening & operasional lanjutan

- Retensi/partisi heartbeat dan event; backup `pg_dump` harian (simpan 14 hari).
- Audit SLO bulanan (heartbeat p95, latensi event, crash-free, durabilitas).
- Kebijakan privasi & retensi thumbnail (default nonaktif, maksimal 7 hari).
- Review kapasitas: 100 perangkat pada VPS 2 core / 2 GB.

**Selesai bila**: DB tetap < 70% kapasitas setelah 30 hari, backup teruji restore,
dan laporan SLO tersedia.

## Prioritas berikutnya (belum dijadwalkan)

- Multi-kamera per perangkat.
- Deteksi kursi terpisah dari meja.
- Ekspor laporan okupansi harian.
- MQTT sebagai alternatif WebSocket bila jumlah perangkat bertambah besar.
- APM ringan (metrik agregat) setelah kapasitas VPS dievaluasi.
