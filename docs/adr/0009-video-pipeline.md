# ADR 0009 - Pipeline video: preview, decode, dan frame ownership

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Dokumen arsitektur menargetkan satu koneksi RTSP dan satu decode di Rust. Kondisi
implementasi saat ini berbeda:

- `stream-engine` membuka RTSP dan menghasilkan access unit Annex B terenkode.
- Belum ada decoder yang terhubung ke preview atau ke `ai-engine`.
- `core-ffi` menerima frame RGB dari Kotlin melalui `push_frame(width, height, Vec<u8>)`.
- Aplikasi Kotlin sudah memiliki layar mapping dan sink konfigurasi, tetapi belum
  memiliki pemanggil `Engine.start`/`push_frame` atau preview. Rencana preview
  memakai Media3 yang juga membuka RTSP.

Jika kedua jalur dijalankan bersamaan, kamera menerima dua sesi dan perangkat melakukan
dua decode. Beberapa kamera konsumen membatasi jumlah sesi. Sebaliknya, memindahkan
semua ke Rust membutuhkan decoder H.264/H.265 dan jembatan `Surface` yang belum ada.

## Keputusan yang diminta

Pilih satu jalur sebelum Fase K5 dan sebelum status "single connection" boleh diklaim.

| Opsi | Preview | Deteksi | Kelebihan | Konsekuensi |
| --- | --- | --- | --- | --- |
| A. Media3 penuh | Media3 membuka RTSP | Frame RGB dari Media3 ke Rust | Jalur tercepat untuk UI; satu koneksi bila Media3 menjadi satu-satunya sumber | Perlu ekstraksi frame, konversi RGB, dan copy; biaya CPU/RAM harus diukur |
| B. Rust penuh | Decoder Rust ke `Surface` | Frame yang sama di Rust | Satu koneksi, satu decode, tidak ada copy lintas bahasa | Perlu decoder, `ANativeWindow`, lifecycle Surface, dan penanganan codec |
| C. Hybrid sementara | Media3 | Rust membuka RTSP sendiri | UI cepat dan deteksi tetap Rust | Dua koneksi; hanya boleh untuk debug/internal, bukan rilis |

## Kriteria keputusan

Opsi dipilih berdasarkan bukti, bukan preferensi:

1. **Kamera**: berapa sesi RTSP yang diizinkan? Apakah H.264 sub-stream stabil?
2. **Biaya frame**: berapa ms dan MB untuk ekstraksi + konversi RGB + copy pada tablet
   target? Apakah frame drop terkendali?
3. **Stabilitas**: apakah 8 jam berjalan tanpa kebocoran dan tanpa disconnect?
4. **Termal**: apakah suhu tetap di bawah ambang `performance.md` §5?
5. **Kompleksitas rilis**: apakah decoder dan Surface bridge dapat diuji di perangkat
   target tanpa codec proprietary?

## Rekomendasi awal

Untuk MVP internal, Opsi A lebih kecil risikonya karena memakai decoder Media3 yang
sudah matang. Namun Opsi A **bukan** otomatis "single decode": frame tetap harus
diekspor, dikonversi, dan disalin ke Rust. Jika hasil pengukuran menunjukkan copy dan
CPU terlalu mahal, Opsi B menjadi target rilis.

Opsi C hanya boleh dipakai untuk membandingkan hasil deteksi di build debug. Opsi C
tidak boleh menjadi default rilis.

## Konsekuensi kontrak

Siapa pun yang dipilih, kontrak berikut wajib ada sebelum implementasi:

- format frame: RGB888, YUV, atau texture/Surface;
- ownership buffer: dipindahkan, dipinjam, atau disalin;
- ukuran maksimum dan validasi `width * height * 3`;
- kebijakan frame terbaru: drop, overwrite, atau antrean terbatas;
- thread yang memiliki decoder, surface, dan engine;
- urutan `start`, attach surface, push frame, detach, dan `stop`;
- perilaku saat rotasi, app background, dan layar mati.

## Alternatif yang ditolak

- **Dua koneksi sebagai default**: membebani kamera dan jaringan, serta menyulitkan
  diagnosis ketika kamera menolak sesi kedua.
- **Menyerahkan preview ke UI tanpa kontrak frame**: UI akan menjadi sumber kebenaran
  kedua dan sulit diuji.
- **Mengklaim zero-copy tanpa pengukuran**: `Vec<u8>` yang melintasi FFI tidak otomatis
  zero-copy.

## Status

Belum diputuskan. Perbarui ADR ini setelah pengukuran Opsi A dan/atau B tersedia.
