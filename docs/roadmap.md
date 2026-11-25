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

**Selesai bila**: stream RTSP kamera EZVIZ stabil 30 menit tanpa kebocoran memori;
file video lokal terbaca dengan FPS terjadwal.

## Fase 2 — ai-engine

- Integrasi `ort` + YOLOv11n ONNX untuk `arm64-v8a`.
- Praproses frame dan pasca-proses (NMS) untuk kelas `person`.
- Benchmark latensi per frame di perangkat target.

**Selesai bila**: deteksi `person` benar pada video uji, latensi tercatat, RAM stabil
saat inferensi berjalan 30 menit.

## Fase 3 — spatial-engine

- Parser konfigurasi poligon meja (JSON).
- Point-in-polygon + centroid.
- Debounce berbasis waktu dan mesin status per meja.

**Selesai bila**: unit test menutup kasus titik di dalam/tepi/luar poligon, dan skenario
video uji menghasilkan transisi status yang benar.

## Fase 4 — core-ffi

- Definisikan antarmuka UniFFI (`start`, `stop`, `set_tables`, event listener).
- Generate bindings Kotlin dan bangun `.so` untuk `jniLibs`.
- Tangani lifecycle: start/stop aman, tanpa panic melintasi FFI.

**Selesai bila**: aplikasi contoh Kotlin bisa start/stop engine dan menerima event status.

## Fase 5 — Android UI & foreground service

- Preview video + overlay poligon yang bisa digambar pengguna.
- Foreground service + notifikasi permanen.
- Penyimpanan konfigurasi meja dan pemulihan setelah restart.

**Selesai bila**: pengguna bisa mapping meja, menyalakan monitoring, dan melihat warna
status berubah tanpa membuka aplikasi terus-menerus.

## Fase 6 — server metadata

- Endpoint penerima status (REST/WebSocket) + skema validasi.
- Penyimpanan riwayat dan dashboard sederhana.
- Antrean offline di perangkat + retry idempoten.

**Selesai bila**: status dari perangkat muncul di dashboard; putus internet 10 menit
lalu tersinkron tanpa duplikat.

## Fase 7 — Uji lapangan & operasional

- Uji di warung nyata: pencahayaan, sudut kamera, kepadatan pengunjung.
- Kalibrasi ulang poligon, tuning ambang debounce.
- Panduan instalasi untuk pemilik warung + checklist perawatan.

**Selesai bila**: berjalan 7 hari berturut-turut dengan status akurat dan tanpa intervensi manual.

## Prioritas berikutnya (belum dijadwalkan)

- Multi-kamera per perangkat.
- Deteksi kursi terpisah dari meja.
- Ekspor laporan okupansi harian.
