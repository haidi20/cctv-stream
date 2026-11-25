# cctv-stream

Sistem **table occupancy monitoring** untuk warung/restoran: kamera CCTV EZVIZ (RTSP) diproses
langsung di perangkat Android (edge), bukan di VPS. HP/tablet di lokasi menjadi *smart edge
gateway*; VPS hanya menerima metadata status meja.

## Ringkasan

- **Status implementasi**: lihat [docs/status.md](docs/status.md). Workspace Rust dan
  model domain Kotlin (`core:model`) sudah memiliki test host; aplikasi Android yang
  bisa dibangun, server, kamera fisik, dan akurasi lapangan belum terverifikasi.
- **Input**: stream RTSP kamera EZVIZ di jaringan Wi-Fi lokal (sub-stream 360p/480p).
- **Target core engine**: Rust (`cdylib`) berjalan sebagai foreground service Android.
- **AI**: YOLOv11n ONNX (deteksi `person`), inferensi 2-5 FPS.
- **Spasial**: poligon meja hasil mapping di UI Kotlin; status ditentukan oleh
  *point-in-polygon* + debounce waktu.
- **Output**: JSON ringan (`table_id`, `status`, `timestamp`) ke UI Kotlin dan VPS.
- **Prinsip**: offline-first. Internet putus tidak menghentikan deteksi; sinkronisasi
  menyusul saat koneksi kembali.

## Alur data (target)

```
[Kamera EZVIZ RTSP]
        |
        v
[stream-engine]  --(ring buffer pra-alokasi)-->  [ai-engine: YOLOv11n ONNX]
                                                        |
                                                        v
[UI Kotlin]  <--(UniFFI)-->  [core-ffi]  <--  [spatial-engine: poligon meja]
        |
        v
[server: metadata JSON, dashboard, riwayat, monitoring]
```

## Dokumen

| Dokumen | Isi |
| --- | --- |
| [status.md](docs/status.md) | Status implementasi, bukti, dan artefak yang belum ada |
| [architecture.md](docs/architecture.md) | Komponen, tanggung jawab, alur, dan batasan desain |
| [roadmap.md](docs/roadmap.md) | Fase kerja, kriteria selesai, dan urutan eksekusi |
| [acceptance.md](docs/acceptance.md) | Definisi akurasi, skenario A1-A12, dan gate uji lapangan |
| [data-contracts.md](docs/data-contracts.md) | Skema JSON poligon meja, status, heartbeat, dan ACK sinkronisasi |
| [monitoring.md](docs/monitoring.md) | Monitoring perangkat, antrean offline, alert, runbook, dan SLO |
| [server-api.md](docs/server-api.md) | Kontrak endpoint server metadata dan aturan sinkronisasi |
| [tdd-plan.md](docs/tdd-plan.md) | Pembagian UI vs core, peta file test, urutan TDD per fase |
| [rust-scaffold.md](docs/rust-scaffold.md) | File produksi Rust, baseline versi crate, koreksi API usang |
| [testdata.md](docs/testdata.md) | Sumber video uji, lisensi, skenario, dan cara memakainya |
| [network-topology.md](docs/network-topology.md) | Perbandingan CCTV→VPS vs CCTV→Android, kasus beda jaringan, dan konsekuensinya |
| [camera-setup.md](docs/camera-setup.md) | Checklist kamera EZVIZ, path RTSP, auth, QR, dan pemulihan jaringan |
| [device-compatibility.md](docs/device-compatibility.md) | Diagnostik perangkat, profil full/balanced/lite/blocked, dan onboarding |
| [android-sync.md](docs/android-sync.md) | Pustaka Android, skema Room, outbox, retensi, dan pruning |
| [android-app-plan.md](docs/android-app-plan.md) | Rencana aplikasi Kotlin: login → Scan CCTV → live + total meja kosong/terisi, portrait & landscape |
| [table-mapping-plan.md](docs/table-mapping-plan.md) | Rencana dan implementasi editor mapping meja per kamera (poligon, Room revisi, JSON v2) |
| [multi-camera-plan.md](docs/multi-camera-plan.md) | Profil banyak CCTV, satu stream aktif, peta terpisah per kamera, migrasi Room v2 |
| [performance.md](docs/performance.md) | Target FPS, anggaran waktu per frame, dan desain adaptif |
| [hybrid-mode.md](docs/hybrid-mode.md) | Analisis mode hybrid VPS + failover dan alasan ditolak |
| [release.md](docs/release.md) | Signing, update, ABI, model ONNX, dan operasional perangkat |
| [structure.md](docs/structure.md) | Struktur folder dan fungsi tiap direktori |
| [adr/](docs/adr/) | Catatan keputusan arsitektur (ADR) |

## Batasan yang disepakati

- Video **tidak** dikirim keluar jaringan lokal.
- VPS tidak menjalankan inferensi video; hanya menyimpan dan menyajikan metadata.
- Model yang dipakai adalah YOLOv11n pra-latih (COCO, kelas `person`). Tidak ada
  pelatihan ulang kecuali kebutuhan objek baru muncul.
- Target perangkat awal: Android `arm64-v8a`.

## Status

Workspace `rust-core/` berisi `stream-engine` (RTSP + ring buffer),
`spatial-engine` (poligon + debounce), `ai-engine` (preprocess/postprocess/NMS +
ONNX Runtime), dan `core-ffi` (UniFFI). Android sudah punya Gradle root, modul `app`,
`core:model`, `core:database`, dan `feature:tables`, ditambah manifest, `MainActivity`,
tema, layar mapping poligon, dan Room v1 untuk revisi konfigurasi meja. `core:model`
lulus 29 test JVM dan `feature:tables` lulus 47 test (37 JVM + 10 Compose). Namun
status "ada" bukan berarti aplikasi siap: login, Home, Scan, foreground service,
outbox, preview RTSP, dan alur end-to-end belum ada. `:app:assembleDebug` menghasilkan
APK dan `:app:lintDebug` 0 error pada 2026-11-26; APK belum dijalankan di perangkat.
Server masih kosong; kamera, akurasi, serta acceptance lapangan belum diverifikasi. Keputusan pipeline preview/decode masih
terbuka di `docs/adr/0009-video-pipeline.md`.

## Referensi Gemini

- Perencanaan awal: https://share.gemini.google/TSS8ugXd9LBP
- Perencanaan terbaru (25 November 2026): https://share.gemini.google/7cn3ExIJ4ujr
