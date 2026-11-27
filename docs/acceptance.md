# Kriteria penerimaan dan validasi lapangan

Dokumen ini mengganti frasa "status akurat" dengan kriteria yang bisa diuji.
Angka target awal bersifat hipotesis sampai ada data lapangan; yang wajib adalah
cara mengumpulkan bukti dan ambang keputusan.

## 1. Definisi akurasi

Akurasi diukur per meja pada sampel waktu yang berlabel, bukan per frame dan bukan
dari kesan visual. Satu sampel adalah satu meja pada satu timestamp.

Ground truth:

- Dua penilai menonton rekaman dan menandai `available` atau `occupied`.
- Jika berbeda, keduanya membahas ulang; bila tetap berbeda, sampel dibuang dan
  dicatat sebagai ambigu.
- Staf yang melintas kurang dari 3 detik dianggap pass-through, bukan occupied.
- Orang yang berdiri di area meja lebih dari 3 detik dianggap occupied.
- Meja yang terhalang total dan tidak bisa dinilai diberi label `unknown`.

## 2. Ambang MVP

| Metrik | Target MVP | Cara ukur |
| --- | --- | --- |
| Precision `occupied` | >= 0,90 | TP / (TP + FP) |
| Recall `occupied` | >= 0,85 | TP / (TP + FN) |
| False `available` | <= 5% sampel occupied | Meja terlihat kosong padahal terisi |
| False `occupied` | <= 10% sampel available | Meja terlihat terisi padahal kosong |
| `unknown` saat stream hidup | <= 2% sampel | Bukan karena stream mati |
| Waktu transisi | <= 5 detik | Perubahan nyata sampai event |

False `available` lebih berbahaya karena membuat staf mengira meja siap dipakai.
Karena itu ambangnya lebih ketat daripada false `occupied`.

Angka ini harus dikunci setelah 3 sesi lapangan. Jika belum tercapai, jangan
menaikkan klaim "akurat"; turunkan FPS, perbaiki poligon, atau ubah model.

## 3. Skenario wajib

Setiap skenario membutuhkan minimal 10 menit rekaman atau 20 transisi:

| ID | Skenario | Yang diuji |
| --- | --- | --- |
| A1 | Warung kosong | Tidak ada false occupied |
| A2 | Satu orang duduk | Transisi available ke occupied |
| A3 | Dua orang di meja yang sama | Tetap satu meja occupied |
| A4 | Orang berdiri dekat meja | Tidak otomatis occupied |
| A5 | Staf melintas | Pass-through tidak memicu occupied |
| A6 | Orang keluar | Kembali available setelah debounce |
| A7 | Dua meja berdampingan | Assignment tidak tertukar |
| A8 | Occlusion sebagian | Status tidak berubah tanpa bukti |
| A9 | Kamera bergeser / tergeser | `needs_recalibration` atau `view_moved` muncul, semua meja `unknown` (ADR 0011) |
| A10 | Cahaya berubah | Tidak ada lonjakan false event |
| A11 | Stream putus | Semua meja menjadi `unknown` |
| A12 | Stream pulih | Status dihitung ulang, bukan melanjutkan status lama |

## 4. Titik acuan orang

Saat ini Rust memakai titik tengah bounding box. Untuk orang duduk dan kamera
miring, titik tengah bisa berada di luar poligon meja walaupun orangnya duduk di
meja tersebut.

Keputusan yang harus diambil sebelum Fase 8:

| Opsi | Kelebihan | Kekurangan |
| --- | --- | --- |
| Center box | Stabil saat deteksi penuh | Gagal untuk meja rendah dan sudut miring |
| Bottom center | Lebih dekat ke posisi kaki/kursi | Gagal saat kaki terhalang |
| Foot anchor dengan fallback | Paling akurat untuk orang duduk | Perlu aturan fallback dan test baru |

Jangan mengubah implementasi tanpa test yang membandingkan ketiga opsi pada
rekaman yang sama. Sampai keputusan dibuat, dokumen dan kode harus menyebut
**center box**, bukan "centroid" yang ambigu.

## 5. Prosedur kalibrasi

1. Pilih sub-stream yang akan dipakai permanen.
2. Pastikan rasio aspek dan resolusi sudah final.
3. Gambar poligon tiap meja dengan zoom 2-4x.
4. Simpan versi konfigurasi.
5. Uji satu orang di tiap meja selama 60 detik.
6. Uji meja kosong selama 60 detik.
7. Ulangi setelah kamera digeser atau lensa diubah.

`needs_recalibration` hanya menandai perubahan rasio aspek. Perubahan sudut kamera
dengan rasio sama tidak terdeteksi otomatis; operator harus memeriksa visual.

## 6. Definisi sesi dan turnover

- Sesi dimulai saat meja menjadi `occupied` dan berakhir saat `available`.
- Perpindahan orang tanpa meja sempat `available` tidak memulai sesi baru.
- Sesi lebih pendek dari 10 detik diabaikan sebagai noise, kecuali terjadi
  berulang.
- Turnover dihitung dari jumlah sesi valid per meja per jam.
- `unknown` menutup sesi sementara dan diberi tanda `data_gap`.

Aturan ini harus sama di server dan dashboard. Perangkat tidak menghitung turnover.

## 7. Bukti yang harus disimpan

Untuk setiap sesi uji:

- versi APK dan versi model;
- model perangkat, versi Android, dan ABI;
- resolusi serta codec sub-stream;
- konfigurasi poligon dan versi;
- rekaman atau klip yang dinilai;
- tabel ground truth;
- log event mentah;
- ringkasan precision, recall, dan confusion matrix.

Jangan menyimpan wajah atau video penuh di server. Rekaman lapangan disimpan
lokal dengan izin pemilik dan dihapus setelah evaluasi.

## 8. Gate Fase 8

Fase 8 hanya lulus jika:

- [ ] Semua skenario A1-A12 punya bukti.
- [ ] Ambang MVP tercapai atau deviasi disetujui tertulis.
- [ ] Titik acuan orang sudah diputuskan dan diuji.
- [ ] Tidak ada false `available` yang tidak dijelaskan.
- [ ] `unknown` tidak pernah ditampilkan sebagai kosong.
- [ ] Kalibrasi ulang terbukti bekerja setelah kamera digeser.
- [ ] Soak 7 hari selesai tanpa intervensi di luar runbook.

## 9. Hal yang tidak membuktikan akurasi

- Unit test Rust.
- Video stok berwatermark.
- Screenshot UI.
- Deteksi satu frame.
- Model sintetis untuk menguji kontrak runtime.
- `cargo test --workspace` yang hijau.

Semua itu berguna, tetapi bukan bukti akurasi lapangan.
