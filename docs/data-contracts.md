# Kontrak data

Semua koordinat poligon memakai ruang **ternormalisasi 0..1** terhadap frame video
(`x / width`, `y / height`), bukan piksel layar dan bukan piksel absolut. Ruang ini
tidak berubah saat pengguna zoom/pan di UI, dan tidak berubah saat resolusi stream
berganti selama rasio aspeknya sama. Keputusan dan konsekuensinya ada di
[`adr/0008-normalized-coordinates.md`](adr/0008-normalized-coordinates.md).

Dokumen ini adalah **kontrak target**, ditegakkan oleh JSON Schema draft 2020-12 di
`contracts/schemas/` (`table-config`, `table-status`, `sync-request`, `sync-ack`,
`heartbeat`). Crate `contracts/` memvalidasi payload terhadap registry offline tanpa
akses jaringan: `cd contracts; cargo test`. Tipe native dan status stream yang
diekspor melalui UniFFI dicatat di bagian 2 dan 5.

## 1. Konfigurasi meja

Satu berkas JSON berisi daftar meja beserta poligonnya.

```json
{
  "version": 2,
  "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
  "tables": [
    {
      "id": "meja-01",
      "label": "Meja 01",
      "polygon": [
        { "x": 0.0781, "y": 0.2778 },
        { "x": 0.2344, "y": 0.2778 },
        { "x": 0.2344, "y": 0.5556 },
        { "x": 0.0781, "y": 0.5556 }
      ]
    }
  ]
}
```

Aturan:

- `id` unik dan stabil (dipakai di server).
- `label` adalah nama yang bisa diedit pengguna. Mengubah label tidak boleh mengubah
  `id`, riwayat status, atau ikatan kamera. Semantik kamera ada di
  [`camera-setup.md`](camera-setup.md) §6.
- `polygon` minimal 3 titik, urutan bebas, tidak boleh self-intersect.
- `version` naik bila skema berubah; engine menolak versi yang tidak dikenal.
  `version: 2` berarti koordinat ternormalisasi; `version: 1` (piksel) ditolak dengan
  pesan "migrasi diperlukan" dan tidak dikonversi otomatis.

Aturan koordinat:

- `x` dan `y` berada di `[0, 1]`, ditulis maksimal 4 desimal. `NaN`, `Infinity`, dan
  nilai di luar rentang ditolak oleh loader.
- `video.width`/`height` hanya referensi tampilan preview; matematika memakai ruang
  ternormalisasi, jadi tidak ada rescale saat resolusi berubah.
- `video.aspect_ratio` (contoh `"16:9"`) adalah penjaga kalibrasi. Bila rasio stream
  aktual berbeda, UI menandai `needs_recalibration`; poligon tidak diubah otomatis.
- Luas poligon minimal `0.0005` (0,05% frame). Poligon yang mengecil jadi garis akibat
  zoom atau salah geser ditolak saat simpan.
- Titik tepat di tepi poligon dianggap **di dalam** dengan toleransi `1e-6`.

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

Representasi native `TableEvent` memakai `since_ms` monotonik sejak engine start,
bukan epoch Unix. Saat event masuk outbox, Kotlin harus mengonversinya menjadi
timestamp RFC 3339 memakai anchor waktu sesi. Jangan pernah memperlakukan `since_ms`
sebagai waktu dinding; selisih dapat membuat durasi sesi dan turnover salah.

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
    "reconnects_1h": 1,
    "last_error": "unreachable",
    "last_error_age_s": 12
  },
  "network": { "transport": "wifi", "same_lan_as_camera": true },
  "inference": { "fps": 2.8, "p50_ms": 38, "p95_ms": 61 },
  "device": {
    "profile": "balanced",
    "ram_free_mb": 720,
    "battery_temp_c": 39.5,
    "thermal_status": "LIGHT",
    "storage_free_mb": 5320
  },
  "runtime": { "model_ready": true, "warmup_ms": 1840, "model_checksum_ok": true },
  "queue": { "pending": 12, "oldest_age_s": 95 },
  "tables": { "occupied": 5, "available": 6, "unknown": 1 }
}
```

Aturan:

- Tanpa gambar, video, URL RTSP, koordinat, atau data pribadi.
- `unknown` dipakai saat stream mati atau model belum siap.
- Heartbeat yang hilang bukan bukti event hilang; status event tetap ditentukan `seq`.
- `device.profile` adalah `full` | `balanced` | `lite` | `blocked` dan mengikuti
  [`device-compatibility.md`](device-compatibility.md) §5.
- `model_checksum_ok` menyatakan hasil verifikasi checksum model saat start, bukan
  jaminan bahwa model cocok untuk setiap perangkat.

Nilai `stream.status`:

| Nilai | Arti |
| --- | --- |
| `connected` | Frame terakhir < 5 detik |
| `connecting` | Sedang membuka koneksi atau reconnect |
| `unreachable` | TCP/RTSP tidak terjangkau (IP salah, beda jaringan, AP isolation) |
| `timeout` | Koneksi terbuka tetapi frame tidak datang |
| `auth_failed` | Kredensial RTSP ditolak |
| `protocol` | Format stream tidak didukung (codec/transport) |
| `closed` | Kamera menutup stream yang sudah terbuka |
| `error` | Kegagalan lain; detail hanya di log native |
| `no_network` | Perangkat tidak punya transport jaringan (hanya dari Kotlin) |
| `wrong_network` | Hanya ada data seluler (hanya dari Kotlin); RTSP lokal tidak mungkin dijangkau |

`last_error` memakai enum yang sama dan hanya diisi bila `status != connected`.
`network.same_lan_as_camera` diisi hasil percobaan terakhir, **bukan** tebakan prefix IP;
field ini `null` bila belum pernah dicoba. `network` tidak memuat SSID, IP, atau MAC.

### 5.1 Crosswalk status stream dan retry

| Native Rust | Kotlin-only | Wire `stream.status` | Status UI | Retry |
| --- | --- | --- | --- | --- |
| `Idle` | - | `idle` | Belum berjalan | Tidak; tunggu pengguna |
| `Connecting` | - | `connecting` | Menghubungkan | Satu percobaan aktif |
| `Connected` | - | `connected` | Live | Tidak |
| `Unreachable` | - | `unreachable` | Kamera tidak terjangkau | Backoff; cek Wi-Fi dulu |
| `Timeout` | - | `timeout` | Tidak ada frame | Backoff; putuskan sesi |
| `AuthFailed` | - | `auth_failed` | Kredensial salah | Tidak cepat; minimal 60 detik |
| `Protocol` | - | `protocol` | Format tidak didukung | Tidak; minta ganti codec/sub-stream |
| `Closed` | - | `closed` | Kamera menutup stream | Backoff normal |
| `Error` | - | `error` | Gangguan teknis | Backoff; kirim laporan |
| - | `NoNetwork` | `no_network` | Wi-Fi mati | Tidak memanggil Rust |
| - | `WrongNetwork` | `wrong_network` | Hanya seluler | Tidak memanggil Rust |

Aturan:

- `Idle` bukan error dan tidak boleh dikirim sebagai `last_error`.
- `Closed` dan `Error` adalah kegagalan; keduanya harus membuat meja `unknown`.
- Hanya `auth_failed` yang memakai retry lambat untuk mencegah akun terkunci.
- `no_network` dan `wrong_network` berasal dari `ConnectivityManager`, bukan dari
  percobaan RTSP.
- Wire status tidak boleh memakai nilai di luar tabel ini. Penambahan nilai berarti
  perubahan kontrak dan harus menaikkan versi.

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
| 3 | Tambah `stream.last_error`, `stream.last_error_age_s`, dan blok `network` (aditif) |
| 4 | Poligon meja pindah ke koordinat ternormalisasi 0..1 dan `video.aspect_ratio`; konfigurasi `version: 2` |
| 5 | Tambah `device.profile`, `runtime.model_ready`, `runtime.warmup_ms`, dan `runtime.model_checksum_ok` (aditif) |

Perubahan yang memutus kompatibilitas wajib menaikkan `version` dan dicatat di sini.
