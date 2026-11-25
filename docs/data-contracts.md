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

## 5. Versi dan kompatibilitas

| Versi | Perubahan |
| --- | --- |
| 1 | Skema awal: konfigurasi meja, status, sinkronisasi |

Perubahan yang memutus kompatibilitas wajib menaikkan `version` dan dicatat di sini.
