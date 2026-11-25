# Mode hybrid (VPS + failover)

Dokumen ini mencatat analisis skema *hybrid edge-cloud failover* dan keputusannya untuk
repo ini. Ini melengkapi [`network-topology.md`](network-topology.md) dan ADR 0001.

## 1. Skema yang dibahas

Android bertindak sebagai router pintar:

| Mode | Yang memproses | Kondisi |
| --- | --- | --- |
| Online (default) | VPS (Python + YOLO) | Heartbeat ke VPS sehat |
| Failover | Rust di perangkat | VPS tidak merespons > 5 detik |
| Recovery | Kembali ke VPS | Internet pulih |

Di mode online, HP menarik RTSP lokal lalu mengirim frame/stream ke VPS; Rust idle.

## 2. Keputusan repo ini

**Tidak memakai mode hybrid.** Arsitektur tetap 100% on-device dengan VPS hanya menerima
metadata.

Alasan:

- VPS 2 core / 2 GB tidak mampu decode + inferensi berkelanjutan; risiko 100% CPU dan OOM.
- Mengirim frame/stream ke VPS melanggar batas privasi dan menghabiskan bandwidth warung.
- Dua basis kode AI (Python di VPS, Rust di HP) berarti perawatan ganda untuk logika
  poligon, debounce, dan kontrak.
- Failover menambah kompleksitas state: dua sumber kebenaran status meja bisa berbeda.

## 3. Biaya skema hybrid

| Komponen | Dampak |
| --- | --- |
| VPS dengan AI | Minimal 4 core / 4-8 GB, atau GPU (T4/RTX) — biaya berkali-kali lipat |
| Bandwidth warung | Upload 2-4 Mbps per kamera, 24 jam |
| Pengembangan | Dua implementasi AI + dua set test |
| Konsistensi | Status bisa bentrok saat transisi mode |
| Debugging | Bug bisa di HP, di jaringan, atau di VPS |

## 4. Kapan hybrid layak

Hanya bila **ketiga** kondisi ini terpenuhi:

1. Perangkat di lokasi sangat lemah (2-3 GB RAM) dan terbukti tidak stabil meski 1 FPS.
2. VPS dinaikkan ke minimal 4 core / 8 GB atau memakai GPU.
3. Upload internet warung stabil dan cukup (>= 5 Mbps tersedia terus-menerus).

Bila hanya satu yang terpenuhi, lebih murah mengganti perangkat Android.

## 5. Desain bila kelak dipakai

Agar tidak mengulang pekerjaan, jaga agar:

- `stream-engine`, `ai-engine`, `spatial-engine` tetap bebas Android. Ketiganya sudah
  dirancang bisa dikompilasi sebagai service Linux.
- `core-ffi` adalah satu-satunya lapisan yang Android-specific; ganti dengan CLI/daemon
  untuk VPS.
- Kontrak event (`data-contracts.md` §2) tetap sama di kedua mode, sehingga server dan
  dashboard tidak berubah.
- Status meja tetap punya **satu sumber kebenaran per waktu**: saat failover, HP menjadi
  sumber; saat recovery, server menerima snapshot dan menghitung ulang durasi.

## 6. Risiko yang harus dimitigasi bila hybrid diaktifkan

| Risiko | Mitigasi |
| --- | --- |
| Dua sumber status bentrok | Lease/epoch: hanya satu pemroses aktif; sertakan `producer_id` di event |
| Frame hilang saat transisi | Snapshot status saat failover, bukan replay frame |
| Beban VPS melonjak | Batasi FPS di VPS dan antre frame dengan drop-oldest |
| Biaya GPU | Ukur dulu; jangan sewa GPU sebelum ada data kebutuhan |
| Privasi | Bila frame keluar, wajib enkripsi, retensi jelas, dan persetujuan pemilik |

## 7. Kesimpulan

Untuk satu warung dengan satu kamera dan VPS 2 GB, hybrid hanya menambah biaya dan
kompleksitas. Keputusan: **on-device**, dengan jalur upgrade yang sudah disiapkan bila
kondisi lapangan memaksa.