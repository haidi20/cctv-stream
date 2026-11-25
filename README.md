# cctv-stream

Sistem **table occupancy monitoring** untuk warung/restoran: kamera CCTV EZVIZ (RTSP) diproses
langsung di perangkat Android (edge), bukan di VPS. HP/tablet di lokasi menjadi *smart edge
gateway*; VPS hanya menerima metadata status meja.

## Ringkasan

- **Input**: stream RTSP kamera EZVIZ di jaringan Wi-Fi lokal (sub-stream 360p/480p).
- **Core engine**: Rust (`cdylib`) berjalan sebagai foreground service Android.
- **AI**: YOLOv11n ONNX (deteksi `person`), inferensi 2-5 FPS.
- **Spasial**: poligon meja hasil mapping di UI Kotlin; status ditentukan oleh
  *point-in-polygon* + debounce waktu.
- **Output**: JSON ringan (`table_id`, `status`, `timestamp`) ke UI Kotlin dan VPS.
- **Prinsip**: offline-first. Internet putus tidak menghentikan deteksi; sinkronisasi
  menyusul saat koneksi kembali.

## Alur data

```
[Kamera EZVIZ RTSP]
        |
        v
[stream-engine]  --(zero-copy ring buffer)-->  [ai-engine: YOLOv11n ONNX]
                                                        |
                                                        v
[UI Kotlin]  <--(UniFFI)-->  [core-ffi]  <--  [spatial-engine: poligon meja]
        |
        v
[server: metadata JSON, dashboard, riwayat]
```

## Dokumen

| Dokumen | Isi |
| --- | --- |
| [architecture.md](architecture.md) | Komponen, tanggung jawab, alur, dan batasan desain |
| [roadmap.md](roadmap.md) | Fase kerja, kriteria selesai, dan urutan eksekusi |
| [data-contracts.md](data-contracts.md) | Skema JSON poligon meja, status, dan payload sinkronisasi |
| [structure.md](structure.md) | Struktur folder dan fungsi tiap direktori |
| [adr/](adr/) | Catatan keputusan arsitektur (ADR) |

## Batasan yang disepakati

- Video **tidak** dikirim keluar jaringan lokal.
- VPS tidak menjalankan inferensi video; hanya menyimpan dan menyajikan metadata.
- Model yang dipakai adalah YOLOv11n pra-latih (COCO, kelas `person`). Tidak ada
  pelatihan ulang kecuali kebutuhan objek baru muncul.
- Target perangkat awal: Android `arm64-v8a`.

## Status

Tahap **perencanaan**: struktur folder dan dokumen sudah ada, belum ada kode.

## url gemini
https://share.gemini.google/TSS8ugXd9LBP
