# Kontrak data

Semua koordinat memakai piksel relatif terhadap resolusi video sumber (`width` x `height`).
Nilai disimpan sebagai bilangan bulat; engine melakukan normalisasi bila resolusi berubah.

## 1. Konfigurasi meja

Satu berkas JSON berisi daftar meja beserta poligonnya.

```json
{
  "version": 1,
  "video": { "width": 1280, "height": 720 },
  "tables": [
    {
      "id": "meja-01",
      "label": "Meja 01",
      "polygon": [
        { "x": 100, "y": 200 },
        { "x": 300, "y": 200 },
        { "x": 300, "y": 400 },
        { "x": 100, "y": 400 }
      ]
    }
  ]
}
```

Aturan:

- `id` unik dan stabil (dipakai di server).
- `polygon` minimal 3 titik, urutan bebas, tidak boleh self-intersect.
- `version` naik bila skema berubah; engine menolak versi yang tidak dikenal.

## 2. Status meja (event dari engine)

```json
{
  "table_id": "meja-01",
  "status": "occupied",
  "since": "2026-11-25T05:31:00Z",
  "confidence": 0.87,
  "persons_in_region": 1
}
```

- `status`: `available` | `occupied` | `unknown`.
- `unknown` dipakai saat stream mati atau model tidak siap.
- Event hanya dikirim saat status berubah (kecuali heartbeat berkala).

## 3. Payload sinkronisasi ke server

```json
{
  "device_id": "android-warung-01",
  "seq": 42,
  "sent_at": "2026-11-25T05:31:05Z",
  "events": [
    { "table_id": "meja-01", "status": "occupied", "since": "2026-11-25T05:31:00Z" }
  ]
}
```

Aturan:

- `seq` monoton per perangkat untuk deteksi duplikat/hilang.
- Server idempoten terhadap `(device_id, seq)`.
- Antrean lokal menyimpan event sampai menerima ACK.

## 4. Kesehatan engine

```json
{
  "stream": "connected",
  "fps_in": 25,
  "fps_infer": 3,
  "inference_ms": 41,
  "tables": 12,
  "uptime_s": 3600
}
```

Dipakai UI untuk indikator "stream down", "model siap", dan diagnosis lapangan.
Untuk data yang dikirim ke server, pakai heartbeat (bagian 5) yang mencakup subset
metrik ini plus kesehatan perangkat.

## 5. Heartbeat perangkat

Dikirim tiap 30 detik (60 detik saat idle) ke endpoint terpisah dari event status.

```json
{
  "device_id": "android-warung-01",
  "session_id": "3f0c9a1e",
  "sent_at": "2026-11-25T05:31:05Z",
  "app_version": "0.1.0",
  "config_version": 4,
  "stream": {
    "status": "connected",
    "fps_in": 25,
    "last_frame_age_s": 0.4,
    "reconnects_1h": 1
  },
  "inference": { "fps": 2.8, "p50_ms": 38, "p95_ms": 61 },
  "device": {
    "ram_free_mb": 720,
    "battery_temp_c": 39.5,
    "thermal_status": "LIGHT",
    "storage_free_mb": 5320
  },
  "queue": { "pending": 12, "oldest_age_s": 95 },
  "tables": { "occupied": 5, "available": 6, "unknown": 1 }
}
```

Aturan:

- Tanpa gambar, video, URL RTSP, koordinat, atau data pribadi.
- `unknown` dipakai saat stream mati atau model belum siap.
- Heartbeat yang hilang bukan bukti event hilang; status event tetap ditentukan `seq`.

## 6. Balasan sinkronisasi (ACK)

```json
{
  "device_id": "android-warung-01",
  "acked_seq": 42,
  "server_time": "2026-11-25T05:31:06Z",
  "duplicates": 1
}
```

Aturan:

- Perangkat menghapus event dengan `seq <= acked_seq`.
- `409` dengan `expected_seq` berarti ada gap; kirim ulang mulai dari `seq` tersebut.
- Server memakai unique constraint `(device_id, seq)`; retry tidak membuat duplikat.
- `POST /v1/snapshot` dipakai untuk rekonsiliasi bila antrean lokal sudah tidak lengkap.

## 7. Versi dan kompatibilitas

| Versi | Perubahan |
| --- | --- |
| 1 | Skema awal: konfigurasi meja, status, sinkronisasi |
| 2 | Tambah heartbeat perangkat dan balasan ACK; aturan idempotensi `(device_id, seq)` |

Perubahan yang memutus kompatibilitas wajib menaikkan `version` dan dicatat di sini.
