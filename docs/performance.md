# Performa & strategi FPS

Dokumen ini menjawab pertanyaan "bisa tidak 24 FPS di HP?" dan menetapkan target FPS
yang realistis. Semua angka di sini adalah **hipotesis yang harus diukur**, bukan janji.

## 1. Ringkasan keputusan

| Pertanyaan | Jawaban |
| --- | --- |
| Mungkinkah 24 FPS inferensi di HP? | Secara teknis bisa, tetapi **tidak diperlukan** dan boros |
| Target operasional | **2-5 FPS** inferensi, 25-30 FPS input |
| Target eksperimen | 10-15 FPS pada perangkat kelas atas, sebagai benchmark |
| Yang menentukan biaya | Resolusi input model dan jumlah thread, bukan FPS stream |

Alasan 2-5 FPS cukup: status meja berubah dalam hitungan detik, bukan milidetik. Orang
butuh > 2 detik untuk duduk; debounce 2-3 detik sudah menutupi seluruh perubahan nyata.

## 2. Anggaran waktu per frame

Pada 2 FPS, satu frame punya 500 ms. Pada 24 FPS, hanya 41.7 ms. Perkiraan kasar untuk
perangkat menengah `arm64-v8a`:

| Tahap | 640x360 | 416x416 | Catatan |
| --- | --- | --- | --- |
| Decode H.264 | 3-8 ms | 3-8 ms | Tergantung resolusi stream |
| Resize + letterbox | 1-3 ms | 1-2 ms | Bisa digabung saat decode |
| Inferensi YOLOv11n (CPU) | 25-60 ms | 12-30 ms | 4 thread; NNAPI bisa lebih cepat/lebih lambat |
| NMS + filter | < 1 ms | < 1 ms | Jumlah kotak kecil |
| Spasial + debounce | < 1 ms | < 1 ms | Puluhan meja |
| **Total** | **30-72 ms** | **17-41 ms** | Belum termasuk thermal throttling |

Kesimpulan: pada resolusi 416x416, 24 FPS secara teori mungkin di perangkat kelas atas,
tetapi margin terhadap throttling sangat tipis. Pada 640x360, 24 FPS realistis hanya di
perangkat flagship dengan NNAPI/GPU.

## 3. Strategi mencapai FPS tinggi (jika benar-benar dibutuhkan)

1. **Kecilkan input model**, bukan stream. Preview tetap 720p; inferensi memakai
   416x416 atau 640x360.
2. **Turunkan presisi** bila EP mendukung (FP16/INT8), dengan uji akurasi ulang.
3. **Gunakan NNAPI/XNNPACK** lewat `ort`; ukur, karena NNAPI bisa lebih lambat untuk
   model kecil.
4. **Decode sekali, pakai banyak**: hindari decode ulang per frame.
5. **Batasi thread** agar tidak berebut core dengan UI.
6. **Frame terbaru saja**: bila inferensi lambat, lewati frame (drop), jangan antre.
7. **Matikan preview** saat layar mati untuk menghemat daya.

Yang **tidak** membantu: menaikkan FPS stream RTSP; itu hanya menambah beban decode.

## 4. Efek samping FPS tinggi

| Naik dari 3 ke 24 FPS | Dampak |
| --- | --- |
| CPU/GPU | ~8x beban; throttling lebih cepat |
| Suhu baterai | Naik signifikan; risiko > 48 C |
| Baterai | Bisa habis 3-5x lebih cepat |
| Akurasi status | **Tidak** naik signifikan karena debounce yang menentukan |
| Event ke UI | Sama saja (event-driven, hanya saat berubah) |
| Bandwidth | Tidak berubah (tidak ada video ke VPS) |

## 5. Desain adaptif (disarankan)

Engine menyesuaikan FPS berdasarkan kondisi, bukan angka tetap:

| Kondisi | FPS inferensi |
| --- | --- |
| Normal, layar mati | 2 |
| Normal, layar menyala | 3-4 |
| Ada perubahan status terdeteksi | 5 selama 10 detik |
| Suhu > 42 C atau termal `MODERATE` | 2 |
| Suhu > 48 C atau termal `SEVERE` | 1 |
| RAM bebas < 150 MB | 1 dan lepas buffer lama |

Ambang ini sinkron dengan [`monitoring.md`](monitoring.md) §2.4.

## 6. Cara mengukur

Yang harus dicatat, bukan dikira-kira:

- `inference_ms` p50/p95 per resolusi input (416, 640) dan per EP (CPU, NNAPI, XNNPACK).
- `fps_infer` aktual selama 30 menit, bukan 10 detik pertama.
- Suhu baterai dan `thermal_status` tiap 60 detik.
- RSS dan RAM bebas tiap 60 detik (deteksi kebocoran).
- Jumlah frame yang di-drop.

Alat: `tracing` + heartbeat ke VPS (lihat `data-contracts.md` §5), plus benchmark lokal
di `tools/bench/` yang mencetak tabel CSV agar bisa dibandingkan antar perangkat.

## 7. Target perangkat

| Kelas | Contoh | Target |
| --- | --- | --- |
| Rendah (2-3 GB RAM) | HP lama | 2 FPS @416, suhu < 45 C |
| Menengah (4-6 GB) | HP umum 2023+ | 2-3 FPS @640, suhu < 45 C |
| Atas (8 GB+) | Flagship | 4-5 FPS @640; eksperimen 10-15 FPS |

Jika perangkat rendah tidak stabil pada 2 FPS, turunkan ke 1 FPS atau kecilkan resolusi;
**jangan** naikkan FPS.

## 8. Kesimpulan

24 FPS adalah pertanyaan yang benar secara teknis, tetapi salah secara produk: biayanya
tinggi dan manfaatnya nol untuk status meja. Bangun pipeline yang mengukur, lalu pilih FPS
dari data — bukan dari target angka.