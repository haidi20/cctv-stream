# Masukan yang dibutuhkan dari pemilik produk

Status: 2026-11-28. Dokumen kerja, bukan ADR. Isinya daftar hal yang **tidak bisa
diselesaikan agen sendirian**: keputusan yang hanya boleh diambil pemilik produk, bahan
yang harus disediakan (rekaman, label, model), atau akses perangkat/kamera.

Dasarnya audit kode dan toolchain pada 2026-11-28, setelah commit `de2d5b7`
(`[Rust] Rotasi log berbasis ukuran 5 MB x 3 file`). Selama butir di bawah belum dijawab,
semua klaim terkait tetap ditulis **belum diverifikasi** di [`status.md`](status.md),
bukan dikarang.

## 1. Kondisi yang diverifikasi 2026-11-28

| Pemeriksaan | Hasil | Artinya |
| --- | --- | --- |
| `adb devices` | kosong | HP SM-A176B tidak tersambung; instrumented test, soak perangkat, dan angka lapangan tidak bisa dijalankan |
| `Get-ChildItem -Recurse -Filter *.onnx` | tidak ada | APK tidak membawa model; `modelReady=true` hanya tercapai bila model di-push manual (`tools/device-e2e.ps1 -Model <path.onnx>`), sehingga inferensi nyata masih bergantung pada bahan M-5 |
| `python -c "import torch"` | tidak ada (`numpy`, `PIL`, `onnx` ada) | `tools/model-export/python_reference.py` (Ultralytics) belum bisa jalan; hanya `opencv_reference.py` (`cv2.dnn`) yang siap |
| `testdata/videos/` | 4 klip stok landscape berwatermark, 1-2 MB | cukup untuk uji pipeline, tidak untuk akurasi (`testdata.md` §1.1.1) maupun portrait/1440p |
| `testdata/expected/` | kosong | belum ada ground truth yang di-commit |
| `docs/adr/0009-video-pipeline.md` | `Status: Diusulkan`, bagian akhir "Belum diputuskan" | pemilihan decoder/preview tidak boleh diputuskan agen (aturan §4 butir 9 `AGENTS.md`) |
| `TODO`/`FIXME`/`unimplemented!()` di `rust-core` | tidak ada | sisa pekerjaan Rust bukan kode kosong, melainkan butir M-1..M-6 di bawah |

## 2. Daftar masukan

| ID | Yang dibutuhkan | Bentuk konkret | Biaya | Membuka |
| --- | --- | --- | --- | --- |
| M-1 | **Keputusan ADR 0009** (jalur pipeline video) | satu kalimat: `A` (Media3 penuh), `B` (Rust penuh), `C` (hybrid, debug-only), atau `tunda` | ~1 menit | R-P1-2: decoder H.264/H.265 + `ANativeWindow`, atau kontrak frame untuk opsi A (Rust masih memegang validasi `width * height * 3` dan kebijakan frame terbaru) |
| M-2 | **Keputusan Q-04 anchor orang** | `tetap Center`, `BottomCenter`, atau `FootWithFallback` + siapa yang mengubah call site Kotlin | ~1 menit | `EngineConfig` Rust; menambah field mengubah data class UniFFI sehingga semua pemanggil Kotlin wajib diisi. Default baru tetap butuh tabel 10 menit (`acceptance.md` §4) |
| M-3 | **Frame berlabel untuk Q-03** | 20 frame dari kamera pengguna, `frames/frame-<n>-<w>x<h>.rgb` + `labels.json` (lihat §3.1) | ~30-45 menit anotasi | tabel precision/recall di `status.md`; tanpa itu tuning Q-05/Q-06/Q-07/Q-09 hanya menukar satu jenis salah dengan jenis salah lain |
| M-4 | **Rekaman + timeline status meja untuk A1-A12** | klip minimal 10 menit atau 20 transisi per skenario + `timeline.csv` (lihat §3.2) + peta poligon klip itu | menyediakan rekaman; anotasi 10-20 menit per klip | gate akurasi `acceptance.md` §2 (precision `occupied` >= 0,90, recall >= 0,85, transisi <= 5 dtk) |
| M-5 | **Model `.onnx`** dan izin lokasinya | `yolo11n.onnx` (atau varian 320) di luar git, mis. `%TEMP%`; atau minta agen menulis spesifikasi export | berkas saja, atau 1 kalimat "tulis spesifikasi export" | `CCTV_ONNX_TEST_MODEL`, `CCTV_PARITY_MODEL`, `CCTV_QUALITY_MODEL` bisa diisi; R-P0-4 (model 320 untuk HP 3 GB) dan inferensi nyata perangkat |
| M-6 | **Akses perangkat dan kamera** | (a) colok SM-A176B via USB, (b) HP 3 GB bila ada, (c) host + user + password RTSP EZVIZ **lewat environment variable**, bukan chat | colok kabel / siapkan variabel | soak 8 jam layar mati, reconnect R-P1-3, klaim RAM di perangkat 4 GB, verifikasi `unknown` saat stream putus (A11/A12) |

Butir yang **tidak** ada di tabel ini sengaja tidak dimasukkan: paritas portrait dan
resolusi 1440p sudah punya harness (`ai-engine/tests/parity_with_python.rs`), jadi cukup
mengirim klip tambahan sebagai bagian M-4 (sebut resolusinya di nama berkas).

## 3. Format bahan

### 3.1 Frame berlabel untuk Q-03 (`CCTV_QUALITY_DIR`)

Alat ukurnya sudah ada (`rust-core/ai-engine/src/quality.rs` +
`ai-engine/tests/detection_quality.rs`), yang belum bahan. Kontraknya:

```text
%TEMP%\cctv-quality\
    frames\frame-0001-640x480.rgb      # RGB888 mentah, tanpa header
    frames\frame-0002-640x480.rgb
    labels.json
```

`labels.json` adalah kotak **person** yang Anda gambar sendiri, koordinat ternormalisasi
`0..1`, dengan IoU pencocokan >= 0,5:

```json
[{ "frame": "frame-0001-640x480.rgb",
   "boxes": [{ "x": 0.10, "y": 0.20, "w": 0.10, "h": 0.30 }] }]
```

Cara mendapatkan `frames/` dari rekaman (butuh model, lihat M-5):

```powershell
python tools/model-export/opencv_reference.py `
    --model "$env:TEMP\yolo11n.onnx" --video "<klip>.mp4" --count 20 `
    --output "$env:TEMP\cctv-quality" --conf 0.35 --iou 0.5
```

Perlu diperhatikan: skrip itu menulis `python.json` berisi **prediksi** model, bukan
kebenaran. `labels.json` tetap harus Anda anotasi manual; kalau `python.json` dipakai
sebagai label, angka precision/recall akan selalu 1,0 dan tidak mengukur apa pun.

Menjalankan pengukuran setelah label siap:

```powershell
cmd /c "set CCTV_QUALITY_MODEL=%TEMP%\yolo11n.onnx&& set CCTV_QUALITY_DIR=%TEMP%\cctv-quality&& cd rust-core && cargo test -p ai-engine --test detection_quality -- --nocapture"
```

### 3.2 Rekaman + timeline status meja untuk A1-A12

Satu klip + satu CSV per skenario. `status` hanya boleh `available`, `occupied`, atau
`unknown`; interval harus menutup seluruh durasi klip; waktu dalam detik dari awal klip.

```csv
clip,scenario,t_start_s,t_end_s,table_id,status
a2-satu-orang.mp4,A2,0,25,meja-1,available
a2-satu-orang.mp4,A2,25,181,meja-1,occupied
a2-satu-orang.mp4,A2,181,240,meja-1,available
```

Peta poligon untuk klip yang sama memakai format `version: 2`
([`data-contracts.md`](data-contracts.md) §1), karena pembandingan status meja mustahil
tanpa poligon yang identik dengan yang dipakai engine. Satu berkas JSON per klip.

### 3.3 Model dan variabel lingkungan

| Variabel | Dipakai oleh | Isi |
| --- | --- | --- |
| `CCTV_ONNX_TEST_MODEL` / `CCTV_ONNX_TEST_MODEL_640` | `ai-engine --test onnx_inference`, `core-ffi --test onnx_engine` | path `.onnx` |
| `CCTV_PARITY_MODEL` + `CCTV_PARITY_DIR` + `CCTV_PARITY_THRESHOLD` | `ai-engine --test parity_with_python` | path model, folder berisi `frames/` + `python.json` |
| `CCTV_QUALITY_MODEL` + `CCTV_QUALITY_DIR` | `ai-engine --test detection_quality` | path model, folder §3.1 |
| `CCTV_LOG_DIR` | `core-ffi` (sudah dipakai aplikasi) | folder log JSON berputar 5 MB x 3 |

Skrip perangkat memakai parameter, bukan variabel lingkungan:

```powershell
powershell -ExecutionPolicy Bypass -File tools/device-e2e.ps1 -Model "$env:TEMP\yolo11n.onnx"
```

### 3.4 Kredensial kamera

Jangan menempel URL RTSP, user, atau password di chat, issue, atau berkas repo. Nama
variabel di bawah ini usulan (belum ada kode yang membacanya); yang penting nilainya
hanya hidup di sesi shell:

```powershell
$env:CCTV_RTSP_HOST = "192.168.1.10"        # atau hostname kamera
$env:CCTV_RTSP_USER = "<user kamera>"
$env:CCTV_RTSP_PASSWORD = "<password kamera>"
```

Redaksi terpusat di `core-ffi` sudah menyembunyikan URL RTSP, password, PIN, dan token,
tetapi kredensial tetap tidak boleh masuk repo (`.gitignore` sudah menjaga; jangan
dilemahkan).

## 4. Jalur minimum

Kalau hanya dua hal yang bisa dijawab hari ini, jawab **M-1** dan **M-2**: keduanya berupa
keputusan, bukan bahan, dan langsung membuka pekerjaan Rust terbesar yang tersisa.

| Jawaban | Langkah agen berikutnya |
| --- | --- |
| M-1 = `A` | tulis kontrak frame Rust untuk jalur Media3 (validasi `width * height * 3`, kebijakan frame terbaru, urutan start/attach/push/detach/stop) + test host dengan dekoder palsu |
| M-1 = `B` | rancang decoder + `ANativeWindow` di `stream-engine`, mulai dari trait `FrameDecoder` yang sudah ada, lalu daftar tugas Kotlin untuk `Surface` lifecycle |
| M-1 = `C` | batasi jalur hybrid sebagai build debug saja, dan catat larangan rilis di `docs/status.md` |
| M-2 = `tetap Center` | tutup Q-04 di `docs/detection-quality-todo.md` dengan alasan, tanpa perubahan kode |
| M-2 = titik lain | sisi Rust + spec perubahan kontrak; penggabungan call site Kotlin diserahkan ke agen Kotlin |

## 5. Yang tetap bisa dikerjakan tanpa bahan

Supaya tidak ada waktu tunggu yang kosong, butir berikut tidak bergantung pada M-1..M-6:

- skrip skor A1-A12 yang membaca `timeline.csv` + keluaran status, sehingga angka
  `acceptance.md` §2 bisa dihitung begitu rekaman datang (M-4);
- pemeriksaan ulang dokumentasi: bagian yang masih menyebut perilaku lama (mis. rotasi log
  berbasis waktu sebelum `de2d5b7`);
- pembersihan butir `docs/kotlin-rust-gaps.md` yang sudah ditutup;
- audit ulang klaim `status.md` terhadap artefak `TEST-*.xml` yang ada.

## 6. Aturan penyerahan bahan

1. Jangan commit `*.mp4`, `*.onnx`, `*.rgb`, `jniLibs/`, `local.properties`, atau
   kredensial; semuanya sudah ada di `.gitignore` dan tidak boleh dilemahkan.
2. Rekaman berwatermark hanya untuk uji pipeline, **bukan** untuk angka akurasi
   (`testdata.md` §1.1.1); watermark bisa dianggap pola objek oleh model.
3. Sebut resolusi dan orientasi klip (mis. `portrait-1080x1920`, `1440p`) di nama berkas,
   supaya paritas portrait/1440p bisa dijalankan tanpa menebak.
4. Kalau bahan tidak bisa disediakan, cukup nyatakan "tunda": butir ditulis sebagai
   belum diverifikasi beserta alasannya, dan tidak ada angka yang dikarang.

