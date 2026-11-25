# ADR 0008 - Koordinat poligon ternormalisasi 0..1

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Poligon meja digambar pengguna di atas preview video. Preview bisa di-zoom, di-pan,
diberi letterbox, atau ditampilkan di layar dengan rasio berbeda. Stream juga bisa
berpindah antara sub-stream 480p dan main stream 720p/1080p.

Tiga ruang koordinat yang mungkin dipakai:

1. **Piksel layar**: posisi sentuhan apa adanya. Rusak saat zoom, rotasi, atau ganti HP.
2. **Piksel absolut frame**: koordinat terhadap resolusi video tertentu. Benar, tetapi
   harus diskalakan ulang setiap resolusi berubah dan mudah salah saat campur sumber.
3. **Ternormalisasi 0..1**: `x / width` dan `y / height` terhadap frame.

## Keputusan

Opsi 3. Kontrak menyimpan `x` dan `y` sebagai pecahan 0..1 dengan presisi 4 desimal.
Kotlin mengonversi sentuhan layar ke ruang ini sebelum menyimpan atau mengirim ke Rust.
Rust mengonversi pusat bounding box ke ruang yang sama sebelum uji poligon.

## Konsekuensi

- Zoom, pan, rotasi layar, dan ukuran layar tidak lagi memengaruhi data tersimpan.
- Ganti resolusi dengan **rasio aspek sama** (540p `960x540` ke 720p `1280x720`) tidak
  membatalkan kalibrasi. Resolusi anamorphic seperti `854x480` tidak dianggap 16:9 karena
  pikselnya tidak persegi; tanpa penanganan SAR, poligon bisa meleset beberapa persen.
- Ganti rasio aspek (16:9 ke 4:3) **membatalkan** kalibrasi, karena normalisasi per sumbu
  meregangkan posisi. Konfigurasi menyimpan `video.aspect_ratio`; bila berbeda dari
  stream saat ini, UI menampilkan `needs_recalibration` dan tidak diam-diam memakai
  poligon lama.
- Pembulatan 4 desimal berarti resolusi spasial ~0,01% frame; pada 1280 px itu ~0,13 px,
  jauh di bawah noise deteksi. Pembulatan hanya dilakukan saat serialisasi JSON.
- Perbandingan titik memakai toleransi `1e-6`; titik tepat di tepi dianggap di dalam
  (aturan yang sudah ada, kini dinyatakan dalam ruang ternormalisasi).
- File konfigurasi lama (`version: 1`, piksel) ditolak dengan pesan migrasi; tidak ada
  konversi otomatis karena resolusi referensi tidak selalu benar.

## Alternatif yang ditolak

- **Piksel absolut + resolusi referensi**: menambah satu field yang mudah salah dan
  memaksa rescale di dua bahasa (Kotlin dan Rust).
- **Simpan keduanya (piksel dan normalisasi)**: dua sumber kebenaran; bug sinkronisasi.
- **Normalisasi terhadap sisi terpanjang saja**: menyederhanakan skala tetapi merusak
  posisi pada video non-persegi; UI dan engine tetap butuh x dan y terpisah.
