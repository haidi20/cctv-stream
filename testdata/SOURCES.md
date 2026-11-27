# Sumber video uji

Dokumen ini mencatat provenance dan metadata klip di `testdata/videos/`.
Klipnya sendiri tidak di-commit (lihat `.gitignore`); dokumen ini yang di-commit.

Semua angka di bawah dibaca langsung dari header MP4 pada 2026-11-25. Tidak ada frame
atau klip turunan yang diekstrak. Isi visual **belum diverifikasi**; kolom peran mengikuti
deskripsi awal, bukan hasil menonton.

## 1. Status lisensi

Klip berasal dari iStock (terlihat dari nama file `istockphoto-*`). Bukti lisensi
(URL lisensi + akun pemilik + tanggal unduh) **belum ada di repo**.

Selama status itu belum berubah:

- Gunakan hanya untuk eksperimen lokal.
- Jangan `git add -f`, jangan commit, jangan unggah ke repo publik.
- Jangan masukkan ke APK debug yang dibagikan.
- Jangan pakai frame/hasil decode sebagai materi pemasaran.
- Jangan pakai sebagai golden frame `ai-engine`.

Kalau klip ini nanti dipakai bersama di CI, ganti dengan rekaman sendiri atau klip
berlisensi jelas (Pexels/Pixabay/Mixkit) yang boleh didistribusikan.

## 2. Metadata terukur

| File | Durasi | FPS | Frame | Resolusi | Codec | Keyframe | Bitrate file | Ukuran |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `istockphoto-1067633772-640_adpp_is.mp4` | 19,240 s | 25,000 | 481 | 768x432 | H.264 (`avc1`) | 2 (0 s, 10 s) | ~874 kbps | 2.102.983 B |
| `istockphoto-2187553973-640_adpp_is.mp4` | 5,172 s | 29,970 | 155 | 768x432 | H.264 (`avc1`) | 1 (0 s) | ~2.021 kbps | 1.306.210 B |
| `istockphoto-453990286-640_adpp_is.mp4` | 23,520 s | 25,000 | 588 | 768x432 | H.264 (`avc1`) | 3 (0, 10, 20 s) | ~695 kbps | 2.042.659 B |
| `istockphoto-678973826-640_adpp_is.mp4` | 16,000 s | 25,000 | 400 | 768x432 | H.264 (`avc1`) | 2 (0 s, 10 s) | ~527 kbps | 1.054.397 B |

Checksum (SHA-256 penuh, untuk verifikasi file lokal):

```text
66d5ea1af589feee8d2633a8ebb6999b566016ceda32ab14171989a316af2cf6  istockphoto-1067633772-640_adpp_is.mp4
89b4e27935947b9e54ae7d0ccd764c631171d3b93dc248b560eb39d656d5802d  istockphoto-2187553973-640_adpp_is.mp4
456e73599aec03bfc0e6dbb6310d06aa554ef1e980276db88331d7c1cf4815d2  istockphoto-453990286-640_adpp_is.mp4
e66ba387ad9ab67230fc519b30a8467b55483d583cec8a699eb6ec999ced6b21  istockphoto-678973826-640_adpp_is.mp4
```

Fakta kontainer yang berlaku untuk keempat file:

- `ftyp` -> `moov` -> `mdat`: **faststart**, aman untuk streaming file.
- Hanya satu track video, **tanpa audio**.
- Ada B-frame (`ctts`) dan edit list dengan `media_time` 1024/12800 tick (~80 ms) atau
  2002/30000 tick (~67 ms). PTS mentah tidak mulai dari 0.
- Semua **16:9 persegi** (768x432); tidak ada SAR anamorphic.
- Tanggal file di disk: 2026-11-25. Ini waktu file diterima, bukan bukti tanggal unduh.

## 3. Peran tiap klip

Prinsip: klip ini **Tier 1 (fixture pipeline)**, bukan Tier 2 (acceptance lapangan).

| File | Peran | Alasan |
| --- | --- | --- |
| `1067633772` | Beban banyak `person`, pacing 25 fps | Klip terpanjang dengan 481 frame; cukup untuk uji NMS dan sampler |
| `2187553973` | EOF/loop, pacing non-bulat | Hanya 1 keyframe dan 29,97 fps; kasus terburuk restart decoder |
| `453990286` | Poligon/center box, soak | 23,5 s, 3 keyframe, sudut tinggi (sesuai deskripsi awal) |
| `678973826` | Satu transisi `available -> occupied` | 16 s cukup untuk duduk + debounce 2-3 s; sekaligus bahan latih CV lokal (`docs/testdata.md` §1.1.2) |

## 4. Matriks klip -> test

### 4.1 Rust (`rust-core`)

| Kebutuhan | Klip | Test yang dituju | `loop` |
| --- | --- | --- | --- |
| Decode sampai EOF | `2187553973` | `tests/file_source.rs` | `false` |
| Pacing 25 fps | `1067633772` | `tests/file_source.rs` | `false` |
| Pacing 29,97 fps (durasi frame non-bulat) | `2187553973` | `tests/file_source.rs` | `false` |
| Timestamp pertama dinormalkan ke 0 | semua | `tests/file_source.rs` | `false` |
| Restart loop tanpa stall | `2187553973` | `tests/file_source.rs` | `true` |
| File terpotong -> error, bukan hang | salinan terpotong di temp dir | `tests/file_source.rs` | `n/a` |
| End-to-end spasial 1 transisi | `678973826` | target `tests/file_source_integration.rs` (belum ada) | `false` |
| Poligon non-persegi / sudut tinggi | `453990286` | target `tests/file_source_integration.rs` (belum ada) | `false` |
| Soak 30 menit, RSS stabil | `453990286` | target `tests/soak_file_source.rs` (`#[ignore]`, belum ada) | `true` |
| Golden frame AI | **tidak ada** | - | - |

Aturan pacing: `file_source` memakai jam monotonik (`Instant`), bukan PTS media, untuk
jadwal frame. PTS hanya dipakai untuk urutan dan deteksi lompatan. Kalau loop, PTS reset
setiap 5-24 detik; debounce tidak boleh ikut reset.

### 4.2 Kotlin (`android`)

| Kebutuhan | Klip | Test | Catatan |
| --- | --- | --- | --- |
| `MappingTransform` / `ZoomState` | tidak ada | `test/kotlin/.../MappingTransformTest.kt` | Tetap pakai frame kanonik `1280x720`; tidak menyentuh video |
| `MonitoringViewModel` | tidak ada | `MonitoringViewModelTest.kt` | `FakeEngine`, tanpa video |
| End-to-end instrumented | `678973826` | target `androidTest/...` | Salin ke `cacheDir/mock/feed.mp4`, kirim path ke Rust; ukuran frame `768x432`; app dan API source belum ada |
| Demo debug manual | `453990286` | - | Hanya build lokal; cek lisensi sebelum dibagikan |

Kotlin tidak melakukan decode **untuk analisis** dan tidak melakukan inferensi.
Preview direncanakan lewat Media3. Video uji untuk analisis masuk lewat `file_source`
di Rust, yang belum terhubung ke decoder konkret maupun engine.

## 5. Batasan yang tidak ditutup klip ini

- Skenario 30-60 detik di `docs/testdata.md` §4 (duduk, pergi, lewat, redup).
- Perubahan rasio aspek 16:9 -> 4:3 (`needs_recalibration`).
- Resolusi 720p/1080p dan stream anamorphic seperti 854x480.
- Kegagalan RTSP: timeout, auth, kamera unreachable, Wi-Fi beda jaringan.
- Akurasi di lokasi nyata dan sudut kamera warung.
- Training model: tidak ada label bounding box dan klip terlalu pendek.

Untuk semua di atas: rekam sendiri 30-60 detik per skenario, atau pakai klip berlisensi
jelas, lalu isi `expected/*.json` setelah isinya ditonton.

## 6. Cara memakai setelah test dibuat

Test integrasi di bawah adalah **target**. Perintah ini belum bisa dijalankan
karena `tests/file_source_integration.rs` dan API source di `EngineConfig` belum
ada. Jangan menganggap perintah ini sudah terverifikasi.

```bash
TESTDATA_VIDEO=/path/ke/testdata/videos/istockphoto-678973826-640_adpp_is.mp4 \
  cargo test -p stream-engine --test file_source_integration
```

Aturan:

- `loop: false` untuk test yang butuh EOF; `loop: true` hanya untuk soak.
- Jangan menaikkan FPS sampling hanya karena klipnya 25/29,97 fps; engine tetap 2-5 FPS.
- Jangan memakai PTS mentah untuk debounce.
- Assert terhadap `expected/*.json` dengan toleransi waktu, bukan timestamp exact.

## 7. Checklist sebelum dipakai lebih jauh

- [ ] Isi bukti lisensi (URL + akun + tanggal unduh).
- [ ] Tonton klip dan verifikasi kolom peran di §3.
- [ ] Isi `expected/*.json` dengan event nyata dan `tolerance_s`.
- [ ] Pastikan test video memakai ukuran frame asli `768x432`, sedangkan test matematika Kotlin tetap `1280x720`.
- [ ] Rekam klip 30-60 detik sendiri untuk acceptance dan soak.
- [ ] Hapus klip ini dari mesin kalau lisensi tidak bisa dibuktikan.

## 8. Riwayat

- 2026-11-25: metadata dan checksum dicatat dari header MP4. Belum ada verifikasi visual,
  belum ada bukti lisensi, belum ada `expected/*.json`.
