# API server metadata

Status: **target**, belum diimplementasikan. Folder `server/` masih kosong dan belum ada
crate server. Dokumen ini menetapkan kontrak yang harus dibuat pada Fase 6.

## 1. Prinsip

- Server hanya menerima metadata, tidak menerima video, gambar, atau credential RTSP.
- Semua endpoint memakai HTTPS; WebSocket memakai WSS.
- `device_id` stabil dan token perangkat disimpan di Android Keystore.
- Request idempoten untuk batch event; event tidak boleh hilang setelah ACK.
- Semua timestamp wire memakai RFC 3339 UTC. Timestamp internal perangkat yang
  monotonik harus dikonversi sebelum dikirim.
- Respons error tidak memuat URL RTSP, IP kamera, password, atau stack trace.

## 2. Endpoint

| Method | Path | Tujuan | Status |
| --- | --- | --- | --- |
| `POST` | `/v1/sync` | Kirim batch event status meja dan terima ACK | Target |
| `POST` | `/v1/heartbeat` | Kirim kesehatan perangkat | Target |
| `POST` | `/v1/snapshot` | Rekonsiliasi status terkini | Target |
| `GET` | `/v1/ws` | WebSocket update status untuk dashboard | Target |
| `GET` | `/health` | Health check proses + database | Target |

## 3. Autentikasi

```http
Authorization: Bearer <device-token>
```

- Token dibuat saat enrollment, bukan password bersama.
- Token dicabut/dirotasi dari dashboard; request berikutnya mendapat `401`.
- `GET /health` boleh tanpa auth dan hanya mengembalikan status agregat.
- Jangan menerima token lewat query string karena akan muncul di access log.

## 4. POST /v1/sync

Request mengikuti `data-contracts.md` §3 dan maksimal 100 event atau 64 KB.

```json
{
  "device_id": "android-warung-01",
  "seq": 42,
  "sent_at": "2026-11-25T05:31:05Z",
  "events": [
    {
      "table_id": "meja-01",
      "status": "occupied",
      "since": "2026-11-25T05:31:00Z"
    }
  ]
}
```

Respons sukses:

```json
{
  "device_id": "android-warung-01",
  "acked_seq": 42,
  "server_time": "2026-11-25T05:31:06Z",
  "duplicates": 0
}
```

Aturan:

- Unique constraint `(device_id, seq)`; retry tidak membuat event duplikat.
- Gap menghasilkan `409` dengan `expected_seq`.
- Batch yang melebihi batas menghasilkan `413`; client harus memecah batch, bukan
  mengulang payload yang sama.
- Event dengan `since` lebih tua dari retensi tetap diterima, tetapi ditandai
  `clock_skew` bila selisihnya lebih dari 2 menit.
- Respons hanya dikirim setelah transaksi database commit.

## 5. POST /v1/heartbeat

Payload mengikuti `data-contracts.md` §5. Server memvalidasi bahwa payload tidak memuat
field terlarang seperti `frame`, `image`, `rtsp_url`, atau `password`.

| Field | Wajib | Catatan |
| --- | --- | --- |
| `device_id` | Ya | Harus cocok dengan token |
| `session_id` | Ya | Berubah setiap start service |
| `sent_at` | Ya | RFC 3339 UTC |
| `stream.status` | Ya | Enum pada `data-contracts.md` §5 |
| `device.profile` | Ya | `full`/`balanced`/`lite`/`blocked` |
| `runtime.model_ready` | Ya | Boolean |

Heartbeat yang telat disimpan, tetapi dashboard menandai perangkat offline berdasarkan
`sent_at` dan `received_at`.

## 6. POST /v1/snapshot

Dipakai ketika antrean lokal tidak lengkap atau perangkat baru dipulihkan.

```json
{
  "device_id": "android-warung-01",
  "sent_at": "2026-11-25T05:35:00Z",
  "tables": [
    { "table_id": "meja-01", "status": "occupied", "since": "2026-11-25T05:31:00Z" }
  ]
}
```

Respons:

```json
{
  "accepted": true,
  "server_time": "2026-11-25T05:35:01Z",
  "tables": [
    { "table_id": "meja-01", "status": "occupied", "source": "device" }
  ]
}
```

Snapshot tidak menghapus riwayat. Snapshot hanya menandai status terkini dan sumbernya.
Jika snapshot bertentangan dengan event berurutan, server menyimpan konflik untuk audit,
bukan diam-diam memilih yang terbaru.

## 7. GET /v1/ws

- WebSocket mengirim event baru dan perubahan status perangkat.
- Setiap pesan memiliki `type`, `event_id`, dan `server_time`.
- Client dashboard mengirim `resume_from` (event id terakhir) saat reconnect.
- Server mempertahankan buffer pesan terbatas; jika `resume_from` sudah tidak tersedia,
  client harus melakukan full refresh dari REST.
- Urutan pesan per perangkat harus monoton; pesan duplikat boleh diabaikan oleh client.

Contoh pesan:

```json
{
  "type": "table_status",
  "event_id": 1042,
  "device_id": "android-warung-01",
  "table_id": "meja-01",
  "status": "occupied",
  "server_time": "2026-11-25T05:31:06Z"
}
```

## 8. GET /health

```json
{
  "status": "ok",
  "version": "0.1.0",
  "database": "ok",
  "uptime_s": 86400
}
```

`status` hanya `ok` atau `degraded`. Jangan mengembalikan detail koneksi database,
credential, atau path filesystem.

## 9. Error envelope

```json
{
  "error": {
    "code": "seq_gap",
    "message": "expected seq 43",
    "request_id": "req_01J..."
  },
  "expected_seq": 43
}
```

Kode minimum:

| HTTP | Code | Arti |
| --- | --- | --- |
| 400 | `invalid_payload` | Schema tidak valid |
| 401 | `unauthorized` | Token hilang/dicabut |
| 403 | `forbidden` | Token tidak berhak atas device |
| 409 | `seq_gap` | Ada event yang hilang |
| 413 | `payload_too_large` | Batch > 64 KB atau > 100 event |
| 429 | `rate_limited` | Terlalu banyak request; sertakan `Retry-After` |
| 500 | `internal` | Kesalahan server; jangan bocorkan detail |

Setiap respons membawa `X-Request-Id`; log server memakai id yang sama.

## 10. Rate limit dan kuota

Nilai awal, bukan janji kapasitas:

| Endpoint | Limit per perangkat | Catatan |
| --- | --- | --- |
| `/v1/sync` | 60 request/menit | Backoff normal lebih jarang |
| `/v1/heartbeat` | 4 request/menit | Interval 30-60 detik |
| `/v1/snapshot` | 2 request/menit | Hanya saat rekonsiliasi |
| `/v1/ws` | 1 koneksi aktif | Koneksi lama diputus |

Server mengembalikan `429` + `Retry-After`; client tidak boleh mengabaikan header itu.
Kuota storage dan retensi mengikuti `monitoring.md` §8.1.

## 11. Pagination dan dashboard

- Endpoint riwayat wajib memakai cursor (`before`/`after` + `limit`), bukan offset.
- `limit` maksimum 200; default 50.
- Urutan default terbaru lebih dulu; cursor harus stabil saat ada event baru.
- Dashboard tidak boleh memuat seluruh riwayat ke memori browser.

## 12. Deployment, backup, dan rollback

- Satu binary Rust (Axum) + PostgreSQL + Caddy pada VPS 2C2G.
- Migrasi dijalankan satu arah; rollback aplikasi tidak otomatis rollback schema.
- Sebelum migrasi: backup, ukur ukuran tabel, dan siapkan prosedur restore.
- Deploy memakai health check `/health` dan drain koneksi WebSocket.
- Backup harian dan restore drill mengikuti `monitoring.md` §8.2.
- Jangan menyimpan video atau thumbnail di VPS; thumbnail default nonaktif.

## 13. Test minimum

- Sync batch valid, duplikat, gap `seq`, payload terlalu besar, dan unauthorized.
- Heartbeat valid, field terlarang ditolak, dan clock skew tercatat.
- Snapshot tidak menghapus riwayat.
- WebSocket reconnect dengan `resume_from` dan fallback full refresh.
- Rate limit mengembalikan `Retry-After`.
- Log tidak memuat token, URL RTSP, IP kamera, atau password.
