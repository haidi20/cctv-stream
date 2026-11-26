# Rencana implementasi server metadata

Status: implementasi Fase 6 dimulai 2026-11-26. Kontrak endpoint ada di
[`server-api.md`](server-api.md); dokumen ini menetapkan **cara** memenuhinya.

## 1. Batas

- Server hanya menerima metadata. Tidak ada frame, gambar, URL RTSP, kredensial kamera,
  atau koordinat meja di payload mana pun.
- Satu binary Rust (Axum) + satu database. Dashboard menyusul; API dan WebSocket dulu.
- Kontrak tetap dimiliki `contracts/`; server tidak menyalin schema.

## 2. Peta modul

```
server/
  Cargo.toml            # crate standalone, workspace sendiri
  migrations/           # 0001_init.sql (sqlx migrate)
  src/
    main.rs             # entry point + subcommand enroll
    lib.rs              # build_app(), AppState, router
    config.rs           # env -> Config
    error.rs            # ApiError + envelope + X-Request-Id
    auth.rs             # Bearer -> device_id, hash token
    model.rs            # tipe wire (serde) + UtcTimestamp
    store.rs            # Db: SQLite via sqlx, semua SQL
    ratelimit.rs        # token bucket per (device, route)
    ws.rs               # broadcast + ring buffer resume
    routes/
      sync.rs heartbeat.rs snapshot.rs health.rs
  tests/                # integration test lewat router asli
```

Aturan dependensi: `routes` boleh memakai `store`, `model`, `error`; `store` tidak boleh
memakai `routes`; `model` tidak boleh memakai yang lain. Ini menjaga tipe wire tetap
murni dan bisa diuji tanpa database.

## 3. Skema database

```sql
devices(device_id TEXT PK, token_hash TEXT UNIQUE, acked_seq INTEGER, created_at TEXT)
sync_batches(device_id, seq, event_count, sent_at, received_at, payload_hash, PK(device_id,seq))
table_events(id INTEGER PK AUTOINCREMENT, device_id, seq, table_id, status, since,
             confidence REAL NULL, persons_in_region INTEGER NULL, received_at)
heartbeats(id INTEGER PK AUTOINCREMENT, device_id, session_id, sent_at, received_at,
           clock_skew_ms INTEGER, payload TEXT)
table_state(device_id, table_id, status, since, source, updated_at, PK(device_id,table_id))
snapshot_conflicts(id INTEGER PK AUTOINCREMENT, device_id, table_id, snapshot_status,
                   current_status, detected_at)
```

`table_events` adalah riwayat; `table_state` adalah status terkini untuk dashboard.
Keduanya ditulis dalam transaksi yang sama dengan `sync_batches`, sehingga ACK tidak
pernah mendahului commit.

## 4. Matematika `seq`

Klien mengirim `seq` = `seq` baris outbox **terakhir** dalam batch, plus `N` event
berurutan dari baris tertua. Outbox memakai `AUTOINCREMENT` dan hanya menghapus baris
yang sudah di-ACK, jadi himpunan baris pending selalu kontigu.

- `incoming_first = seq - (N - 1)`
- `expected_first = acked_seq + 1`
- `incoming_first > expected_first` -> `409` `seq_gap` dengan `expected_seq = expected_first`
- `incoming_first < expected_first` -> tumpang tindih; `duplicates = min(expected_first - incoming_first, N)`
- `incoming_first < 1` -> `409` `seq_gap` (klien mengirim `seq` yang mustahil)

Event disimpan dengan `seq = incoming_first + i`. ACK hanya setelah commit, dan
`acked_seq = max(acked_seq, seq)` sehingga retry batch yang sama idempoten.

## 5. Autentikasi dan enrollment

- Token 256-bit dibuat server: `ctv_<64 hex>`, hanya ditampilkan sekali oleh
  `cctv-server enroll --device-id <id>`.
- Yang disimpan hanya `sha256(token)`; lookup lewat indeks unik.
- `device_id` di body harus sama dengan pemilik token, kalau tidak `403 forbidden`.
- Token tidak pernah masuk query string, log, atau pesan error.

## 6. Batas payload dan rate limit

| Batas | Nilai | Perilaku |
| --- | --- | --- |
| Body | 64 KiB | `413 payload_too_large` |
| Event per batch | 100 | `413 payload_too_large` |
| `/v1/sync` | 60/menit/perangkat | `429` + `Retry-After` |
| `/v1/heartbeat` | 4/menit/perangkat | `429` + `Retry-After` |
| `/v1/snapshot` | 2/menit/perangkat | `429` + `Retry-After` |

Jendela tetap 60 detik disimpan di memori proses. Pembatasan ini melindungi satu
perangkat yang rusak, bukan penyerang terdistribusi; itu tugas reverse proxy.

## 7. WebSocket

- `GET /v1/ws` memerlukan `Authorization: Bearer` (bukan query string).
- Setiap pesan: `type`, `event_id`, `server_time`, plus field sesuai tipe.
- Ring buffer 256 pesan terakhir di memori. `resume_from` yang sudah terhapus
  menghasilkan pesan `{"type":"resync"}` agar klien melakukan full refresh REST.
- Urutan `event_id` monoton; klien boleh mengabaikan duplikat.

## 8. Matriks test

| Perilaku | Test |
| --- | --- |
| Batch valid -> ACK, event tersimpan | `sync_api::accepts_a_contiguous_batch` |
| Retry batch sama -> `duplicates = N` | `sync_api::replaying_a_batch_is_idempotent` |
| Gap -> `409` + `expected_seq` | `sync_api::reports_a_gap_with_expected_seq` |
| > 100 event / > 64 KiB -> `413` | `sync_api::rejects_oversized_batches` |
| Body bukan JSON / field asing -> `400` | `sync_api::rejects_invalid_payloads` |
| Token salah / device lain -> `401`/`403` | `auth_api::*` |
| Heartbeat valid tersimpan | `heartbeat_api::stores_a_valid_heartbeat` |
| Heartbeat dengan `image`/`frame` ditolak | `heartbeat_api::rejects_forbidden_fields` |
| Snapshot tidak menghapus riwayat | `snapshot_api::keeps_history_and_records_conflicts` |
| Rate limit + `Retry-After` | `rate_limit::*` |
| `X-Request-Id` ada di semua respons | `request_id::*` |
| Log tidak memuat token/URL RTSP | `redaction::*` |
| `/health` tanpa auth | `health::*` |
| WS resume dan fallback resync | `ws_api::*` |

## 9. Gate

```powershell
cd server
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo audit
cargo deny check
```

Test tidak memerlukan server database eksternal: setiap test memakai file SQLite
sementara. PostgreSQL, dashboard, dan deployment Caddy belum termasuk fase ini.