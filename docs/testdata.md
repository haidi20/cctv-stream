# Data uji

Dokumen ini mengatur asal, lisensi, penamaan, dan penggunaan video/gambar uji. Prinsipnya:
**video uji bukan bahan training model**, melainkan bahan validasi pipeline
(`stream-engine` → `ai-engine` → `spatial-engine`).

## 1. Peran video uji

| Bukan untuk | Untuk |
| --- | --- |
| Melatih ulang YOLOv11n | Menguji ring buffer, pacing FPS, dan reconnect |
| Menambah kelas objek baru | Menguji akurasi poligon dan debounce |
| Menggantikan uji lapangan | Reproduksi bug tanpa kamera fisik |

Model tetap YOLOv11n pra-latih (COCO, kelas `person`). Video uji hanya mensimulasikan
feed CCTV; hasil deteksi dipakai memverifikasi logika, bukan memperbaiki bobot model.

## 2. Sumber footage

| Sumber | Kunci pencarian | Lisensi | Catatan |
| --- | --- | --- | --- |
| Pexels | `restaurant tables`, `empty restaurant`, `cafe interior`, `food court` | Pexels License (gratis, komersial) | HD-4K; cocok untuk uji resolusi |
| Pixabay Video | `restaurant`, `dining tables` | Pixabay Content License | Bebas royalti |
| Videvo | `dining room`, `commercial space` | Bervariasi per klip | **Cek lisensi tiap file** |
| Kaggle / GitHub dataset | `table occupancy`, `restaurant surveillance` | Bervariasi | Paling relevan untuk sudut CCTV |
| YouTube (rekaman publik) | `restaurant security camera footage`, `cafe cctv feed` | **Tidak bebas** | Hanya untuk eksperimen lokal, jangan di-commit/di-redistribusi |

Aturan lisensi:

- Catat sumber + lisensi + tanggal unduh di `testdata/SOURCES.md`.
- Jangan commit klip berlisensi ke repo publik; simpan di mesin lokal atau object
  storage privat.
- Klip dari YouTube tidak boleh dipakai untuk demo publik atau materi pemasaran.

## 3. Struktur folder

```text
testdata/
├── SOURCES.md              # daftar klip: nama, sumber, lisensi, tanggal
├── videos/                 # klip mentah (tidak di-commit)
├── frames/                 # frame hasil ekstraksi (tidak di-commit)
└── expected/               # hasil yang diharapkan (di-commit)
    ├── meja-01.json
    └── ...
```

`.gitignore` sudah menutup `testdata/videos/`, `testdata/frames/`, `*.mp4`, `*.mkv`.
Yang boleh masuk repo hanya `SOURCES.md` dan `expected/*.json` berukuran kecil.

## 4. Skenario minimum

| Skenario | Durasi | Tujuan |
| --- | --- | --- |
| Warung kosong | 30 detik | Pastikan tidak ada false positive |
| Satu orang duduk lalu pergi | 60 detik | Transisi `available → occupied → available` |
| Dua orang di meja berbeda | 60 detik | Assignment multi-meja |
| Orang lewat di depan meja (tidak duduk) | 30 detik | Uji debounce, hindari salah status |
| Pencahayaan redup | 30 detik | Uji ambang confidence |
| Sudut miring / top-down | 30 detik | Uji poligon non-persegi |

Setiap klip dipasangkan dengan `expected/<nama>.json`:

```json
{
  "clip": "warung-satu-orang",
  "fps": 25,
  "resolution": { "width": 1280, "height": 720 },
  "events": [
    { "at_s": 4.2, "table_id": "meja-01", "status": "occupied" },
    { "at_s": 38.7, "table_id": "meja-01", "status": "available" }
  ],
  "tolerance_s": 1.5
}
```

Toleransi waktu wajib ada; hasil tidak boleh diuji dengan exact timestamp.

## 5. Ekstraksi frame

Frame disimpan hanya untuk golden test AI (lihat `tdd-plan.md` §3.3):

```bash
ffmpeg -i testdata/videos/warung-satu-orang.mp4 \
  -vf "fps=1,scale=640:-1" \
  testdata/frames/warung-satu-orang-%03d.jpg
```

Aturan:

- Maksimal 10 frame per klip, resolusi <= 640 px, JPEG kualitas 80.
- Total `testdata/frames/` di mesin lokal < 50 MB.
- Nama file memuat detik asal agar mudah dilacak.

## 6. Cara memakai file lokal di engine

`stream-engine` menyediakan dua sumber dengan antarmuka sama (ADR 0003). Konfigurasi:

```json
{
  "source": {
    "kind": "file",
    "path": "testdata/videos/warung-satu-orang.mp4",
    "fps": 25,
    "loop": true
  }
}
```

Ganti ke RTSP hanya dengan mengubah blok `source`; modul hilir tidak berubah. Pacing
FPS file lokal harus meniru FPS asli, kalau tidak hasil debounce tidak realistis.

## 7. Kapan pindah ke kamera asli

Pindah ke RTSP EZVIZ setelah:

1. Skenario §4 hijau dengan file lokal.
2. Soak test 30 menit file lokal tanpa kenaikan RSS.
3. Poligon hasil mapping diuji ulang pada frame kamera nyata (posisi bisa berbeda).

Video uji tidak membuktikan akurasi di lokasi nyata; itu tetap tugas Fase 8
(`roadmap.md`).