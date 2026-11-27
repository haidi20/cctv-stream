# ADR 0011 - Kamera yang bergeser membatalkan peta meja

- Status: Diusulkan
- Tanggal: 2026-11-27

## Konteks

Poligon meja (`docs/table-mapping-plan.md`, ADR 0008) adalah koordinat 0..1 **relatif
terhadap apa yang dilihat kamera**. Selama kamera tidak bergerak, normalisasi membuat
poligon tahan ganti resolusi. Begitu kamera diputar, dimiringkan, atau tersenggol, isi
frame berubah tetapi geometrinya tidak: poligon `meja-01` kini menutupi kursi, dinding,
atau meja lain.

Mekanisme yang ada belum menangkap kasus ini:

- `needs_recalibration` hanya membandingkan **rasio aspek**. Kamera yang diputar pada
  rasio yang sama tetap 16:9.
- Watchdog dan `StreamFailureReporter` hanya menangani stream mati atau macet. Kamera
  yang diputar tetap mengirim frame sehat, deteksi tetap masuk akal, dan hasilnya angka
  meja yang **salah tetapi terlihat normal**.
- Model tidak punya kelas "kamera bergeser", dan menambahkannya butuh dataset baru.

Aturan repo melarang `unknown` dilebur menjadi `available`/`occupied` (`AGENTS.md` §4
butir 5). Selama peta tidak cocok dengan pandangan kamera, status meja tidak diketahui -
bukan "kosong".

## Keputusan

`spatial-engine::view` menyimpan **sidik jari luminans** frame yang dianalisis dan
membandingkannya dengan baseline; `core-ffi` menegakkannya sebelum status dihitung.

1. **Sidik jari.** Grid `32x18` (576 sel, 16 sampel per sel, ~9 200 pembacaan piksel per
   frame) berisi luminans rata-rata ternormalisasi `0.0..=1.0`. Biayanya dua orde
   besaran di bawah satu inferensi (~200 ms), jadi dijalankan pada setiap frame yang
   benar-benar dianalisis, bukan pada 24 FPS.
2. **Perbandingan tahan pencahayaan.** Setiap sel dibandingkan setelah **median grid
   dikurangkan** (median-centring). Lampu dinyalakan atau auto-exposure bergeser
   menggeser semua sel bersama-sama, sehingga selisih ternormalisasi tetap kecil; kamera
   yang bergeser memindahkan sebagian besar sel ke nilai berbeda.
3. **Ambang dengan histeresis.** Sel dianggap berubah bila selisih > `0.06`. Kamera
   dinyatakan bergeser bila fraksi sel berubah > `0.50`, dan dianggap kembali normal
   hanya setelah turun ke `<= 0.25`. Dua ambang mencegah status berkedip di sekitar batas.
4. **Baseline hanya dari peta yang dipasang.** Baseline adalah frame pertama yang
   dianalisis setelah `install_tables`/`set_tables` atau sesi baru. Baseline **tidak**
   diperbarui otomatis dari frame "stabil", karena kamera yang diputar lalu berhenti
   akan dianggap benar dan poligon lama diterima sebagai peta baru.
5. **Konsekuensi status.** Selama `moved`, `StateInner::observe_frame` tidak menjalankan
   debounce sama sekali: `mark_view_moved` mengosongkan state machine, menerbitkan
   `TableEvent { status: Unknown }` untuk setiap meja yang belum `unknown`, dan
   menyalakan `needs_recalibration`. Kotlin hanya menampilkan banner/notice yang sudah
   ada; UI tidak menghitung apa pun.
6. **Pulih.** Kembali ke pandangan baseline memulihkan state machine, tetapi event
   `available` yang dipancarkan saat pemulihan dibuang: frame pertama setelah pulih tetap
   harus lewat observasi nyata sebelum meja boleh disebut kosong.

## Konsekuensi

- Peta yang salah tidak lagi menghasilkan angka meja yang meyakinkan; meja menjadi
  `unknown` dan pengguna diminta menggambar ulang peta.
- Orang yang berjalan melewati kamera tidak memicu: tubuh dekat lensa mengubah sekitar
  seperempat frame, di bawah ambang `0.50`. Diuji dengan blok 10% dan 30% frame.
- Perpindahan mode siang/malam (IR cut) yang mengubah seluruh gambar bisa memicu
  peringatan palsu. Arah kesalahannya aman: pengguna diminta kalibrasi ulang, bukan
  disuguhi angka yang salah.
- Guard ini membandingkan piksel mentah, satu-satunya bagian `spatial-engine` yang tidak
  bekerja di ruang ternormalisasi; ini dinyatakan di dokumentasi crate.
- Biaya per frame: 576 sel x 16 sampel + median 576 nilai. Belum diukur di perangkat
  sebagai bagian `pushFrame`; pengukuran p50 209-239 ms (640x360) belum memuat guard ini.
  Ini risiko yang harus ditutup oleh benchmark perangkat berikutnya.

## Alternatif yang ditolak

- **Kelas model "camera moved"**: butuh dataset dan model baru; YOLOv11n saat ini tidak
  punya kelas itu.
- **Meminta kalibrasi setiap startup**: mengganggu warung yang memasang kamera permanen.
- **Menggeser poligon otomatis lewat homography/optical flow**: belum ada bukti akurasi;
  risiko memindahkan peta secara diam-diam ke posisi yang salah.
- **Membandingkan histogram global saja**: perubahan pencahayaan global langsung memicu
  false positive, sedangkan geseran kecil yang hanya mengubah separuh frame terlewat.
- **Memakai `needs_recalibration` lama (rasio aspek) saja**: kamera yang diputar pada
  rasio sama tetap dinyatakan terkalibrasi.
