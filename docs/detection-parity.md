# Paritas Rust vs Python YOLO

Status: 2026-11-28. Dokumen kerja, bukan ADR. Tujuannya satu: menjawab "apakah
angka error port Rust kecil" dengan angka, bukan dengan "kotak sudah terlihat sama".

## 1. Apa yang dibandingkan

Kedua sisi menjalankan **model ONNX yang sama** pada **frame yang sama**, lalu
kotak person dibandingkan satu per satu:

- Python menulis `python.json` (koordinat normal `0..1` + confidence).
- Rust menjalankan `ai-engine::Detector` pada frame `*.rgb` yang sama.
- `ai-engine::parity` memasangkan kotak dengan IoU ≥ 0.5 (greedy, confidence
  tertinggi dulu; satu kotak hanya boleh dipasangkan sekali) dan melaporkan:
  `matched`, `missed` (hanya Python), `extra` (hanya Rust), `mean_iou`, `min_iou`,
  `mean_edge`/`max_edge` (selisih tepi terbesar, satuan fraksi lebar frame),
  `mean_conf`/`max_conf` (selisih confidence).

Alur ini **tidak** mengukur akurasi model terhadap manusia (itu Q-03 di
`docs/detection-quality-todo.md`). Yang diukur adalah kesalahan implementasi Rust:
letterbox, decode, NMS, dan pemetaan balik ke piksel frame.

Ada dua pembangkit referensi di `tools/model-export/`:

- `python_reference.py` - Ultralytics asli (`YOLO(...).predict`). Referensi kanonik
  bila `ultralytics` + `torch` terpasang.
- `opencv_reference.py` - `cv2.dnn` + numpy, sekaligus bisa memotong frame dari
  klip. Dipakai di mesin ini karena `ultralytics`/`onnxruntime` tidak terpasang.

## 2. Cara menjalankan

```powershell
# 1. Referensi Python + frame (sekali jalan)
python tools/model-export/opencv_reference.py `
    --model "$env:TEMP\yolo11n.onnx" `
    --video testdata/videos/<klip>.mp4 --count 12 `
    --output "$env:TEMP\cctv-parity" --conf 0.25 --iou 0.5

# 2. Sisi Rust (dari rust-core/)
$env:CCTV_PARITY_MODEL = "$env:TEMP\yolo11n.onnx"
$env:CCTV_PARITY_DIR   = "$env:TEMP\cctv-parity"
$env:CCTV_PARITY_THRESHOLD = "0.25"
cargo test -p ai-engine --test parity_with_python -- --nocapture
```

`--conf`, `--iou`, dan `CCTV_PARITY_THRESHOLD` **harus sama** di kedua sisi;
kalau berbeda, selisih ambang akan terbaca sebagai kesalahan port. Default
skrip = default Rust (`0.35` / `0.5`). `CCTV_PARITY_MIN_AGREEMENT` (opsional)
membuat test gagal bila `agreement` di bawah nilai itu.

Model `yolo11n` di mesin ini: `E:\temp\cctv-yolo11n.onnx`. Frame, model, dan
`python.json` tidak masuk repo.

## 3. Hasil 2026-11-28 (host, CPU)

Model `cctv-yolo11n.onnx`, referensi `opencv_reference.py`, 768x432, 12 frame per
klip (`max_edge`/`mean_edge` dalam fraksi lebar frame; 0.0054 ≈ 4 px).

| Klip | conf | matched | missed | extra | agreement | mean_iou | min_iou | mean_edge | max_edge | mean_conf | max_conf |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 453990286 | 0.35 | 19 | 0 | 0 | 1.000 | 0.997 | 0.993 | 0.0001 | 0.0005 | 0.002 | 0.007 |
| 453990286 | 0.25 | 50 | 0 | 1 | 0.980 | 0.996 | 0.931 | 0.0002 | 0.0054 | 0.002 | 0.007 |
| 453990286 | 0.20 | 75 | 0 | 1 | 0.987 | 0.996 | 0.931 | 0.0002 | 0.0054 | 0.002 | 0.007 |
| 678973826 | 0.25 | 30 | 0 | 0 | 1.000 | 0.997 | 0.994 | 0.0002 | 0.0008 | 0.003 | 0.012 |
| 1067633772 | 0.35 | 0 | 0 | 0 | - | - | - | - | - | - | - |
| 2187553973 | 0.35 | 0 | 0 | 0 | - | - | - | - | - | - | - |

Kesimpulan yang boleh diambil:

- **Tidak ada orang yang terlewat** (`missed = 0` di semua baris). Selisih
  confidence antar mesin inferensi (ONNX Runtime vs `cv2.dnn`) ≤ 0.012.
- **Geser geometri ≤ 0.54 % lebar frame** (≈4 px pada 768 px), rata-rata
  0.02 % (≈0.15 px). Ini batas pembulatan, bukan pergeseran sistematis.
- Satu `extra` pada klip 453990286 (frame berbeda di tiap ambang) adalah kotak
  duplikat pada IoU tepat di ambang NMS 0.5: perbedaan float antar mesin
  inferensi membalik keputusan suppress. Bukan pola; tidak muncul di klip kedua.
- Dua klip tidak punya orang di atas ambang, jadi **bukan bukti** apa pun -
  ditulis sebagai baris kosong, bukan "agreement 1.000".

Belum diverifikasi: referensi Ultralytics asli (`ultralytics`/`torch` tidak
terpasang di mesin ini), klip CCTV warung nyata berisi orang duduk, dan frame
kamera setelah downscale ke 640x360 seperti di perangkat. Klip uji 768x432
masuk model sebagai 640x360 dengan `pad_y = 140` (`pad_x = 0`), jadi padding
vertikal ikut teruji; padding horizontal (frame portrait) baru teruji lewat test
unit `preprocess`, belum lewat angka paritas.
