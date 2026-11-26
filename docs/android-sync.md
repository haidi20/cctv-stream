# Arsitektur Android & sinkronisasi

Dokumen ini merinci lapisan Kotlin: pustaka yang dipakai, struktur database lokal,
pembersihan data, dan batas tegas dengan Rust. Ringkasan alur ada di
[`architecture.md`](architecture.md); aturan offline-first ada di
[`monitoring.md`](monitoring.md) §4.

## 1. Pembagian tanggung jawab

| Komponen | Tugas | Tidak boleh |
| --- | --- | --- |
| Rust core (target) | RTSP, decode, ring buffer, YOLO, poligon, debounce | HTTP, SQLite, WorkManager, tahu soal internet |
| Kotlin (target) | UI, Room, antrean, WorkManager, Ktor, Crashlytics | Menghitung status meja, inferensi. Preview boleh memakai decoder Media3 bila ADR 0009 memilih opsi itu. |

Status: scaffold Kotlin parsial, gate host 2026-11-26 lulus 555 test dari 83 `TEST-*.xml`.
`core:model` berisi domain/geometri/JSON + kontrak sync/heartbeat (63 test), `core:network` discovery CCTV + tes
koneksi empat lapis (43 test), `core:sync` coordinator/backoff/endpoint (14 test), `feature:tables`
mapping/editor/ViewModel (53 test, termasuk 10 test Compose), `feature:cameras` daftar/editor + scan +
tes koneksi (37 test), `feature:auth` login PIN (36 test), `feature:home` Home + scan sheet +
`NetworkPolicy` + backoff reconnect + menu pengaturan sync (66 test), `feature:onboarding` diagnostik
perangkat sebelum PIN (41 test), `feature:settings` layar "Sinkronisasi server" (28 test), plus `app`
wiring/engine warm-up, foreground service, outbox repository, recorder, pipeline sync HTTP, dan
heartbeat (130 test). Room v3 menyimpan revisi konfigurasi meja, profil kamera, dan outbox (migrasi 1->2->3);
`:app:assembleDebug` menghasilkan APK dan lint 0 error.

Yang sudah ada di jalur sync: `SyncWorker` (`@HiltWorker`, `OneTimeWorkRequest` + `NetworkType.CONNECTED`),
`SyncScheduler` (KEEP untuk debounce, APPEND_OR_REPLACE untuk follow-up, REPLACE untuk "sync now"),
`KtorSyncTransport`, `OutboxSyncAdapter`, `SyncRunner` dengan retry ladder persisten, dan layar
pengaturan untuk base URL + token Bearer. Heartbeat memakai rantai `OneTimeWorkRequest` 30 dtk
(live) / 60 dtk (idle) lewat `HeartbeatScheduler`, `HeartbeatRunner`, dan `KtorHeartbeatTransport`;
chain dimulai/dihentikan oleh `MonitoringHub`, bukan oleh worker periodik 15 menit. Yang belum ada:
`/v1/snapshot`, workspace `table_logs`, penyimpanan token di Keystore, server nyata, dan decoder Rust.
Pembagian di atas adalah batas tanggung jawab, bukan deskripsi fitur lengkap.

Alasannya: WorkManager dan Room adalah API platform Android yang butuh `Context` dan
siklus hidup aplikasi. Memaksakan itu ke Rust berarti mengakses SQLite lewat FFI dan
mendengarkan broadcast sistem secara manual — lebih rapuh dan lebih sulit dirawat.
Rust tetap "pekerja murni" yang menyerahkan event ke Kotlin.

## 2. Baseline pustaka Android

Dicek ke Google Maven pada 2026-11-25:

| Pustaka | Versi | Kegunaan |
| --- | --- | --- |
| `io.ktor:ktor-client-*` | 3.2.0 | HTTP ke VPS (JSON kecil, coroutines) |
| `androidx.room` | 2.8.5 | Outbox, log status, konfigurasi poligon |
| `androidx.work` | 2.12.0 | Batch sync, prune, heartbeat |
| `androidx.datastore:datastore-preferences` | 1.3.0 | Preferensi (retensi, device_id) |
| `com.google.firebase:firebase-crashlytics-ndk` | 21.1.0 | Native crash Rust |
| `androidx.compose:compose-bom` | 2026.11.01 | UI |
| `androidx.lifecycle` | 2.11.1 | ViewModel, state |

Catatan:

- Retensi dan `device_id` disimpan di **DataStore**, bukan `SharedPreferences`; DataStore
  aman untuk akses async dan tidak memblokir main thread.
- Ktor dipilih dibanding Retrofit karena payload kecil dan kita butuh kontrol manual atas
  retry/backoff (tidak ingin interceptor tersembunyi).
- Tidak memakai Firebase Analytics; hanya Crashlytics agar tidak menambah beban.

## 3. Struktur paket

```text
android/app/src/main/kotlin/<pkg>/
├── ui/            # Compose screen, komponen, theme
├── domain/        # ViewModel, UI state, mapper (tanpa import Android)
├── service/       # Foreground service, notifikasi, boot receiver
└── data/
    ├── local/     # Room entity, DAO, database, migration
    ├── remote/    # Ktor client, DTO, endpoint
    ├── sync/      # WorkManager worker, scheduler, backoff
    └── settings/  # DataStore
```

`domain/` harus bebas `android.*` agar bisa diuji sebagai JVM unit test tanpa emulator.

## 4. Skema Room

Empat tabel, semuanya kecil kecuali `table_logs`:

```sql
-- 1. Konfigurasi meja (kecil, permanen)
CREATE TABLE table_config (
  table_id      TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  polygon_json  TEXT NOT NULL,
  config_version INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

-- 2. Outbox: event yang belum di-ACK server
CREATE TABLE outbox (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id   TEXT NOT NULL,
  payload     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  last_error  TEXT,
  is_synced   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_pending ON outbox (is_synced, seq);

-- 3. Log status lokal (riwayat + audit)
CREATE TABLE table_logs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  table_id    TEXT NOT NULL,
  status      TEXT NOT NULL,
  event_at    INTEGER NOT NULL,
  sync_status INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_logs_prune ON table_logs (sync_status, event_at);

-- 4. Preferensi perangkat
CREATE TABLE device_settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
```

Aturan:

- `seq` memakai `AUTOINCREMENT` agar tidak dipakai ulang setelah baris dihapus; harus
  monoton dan cocok dengan `data-contracts.md` §3.
- `outbox` dan `table_logs` sengaja dipisah: outbox adalah antrean pengiriman, log adalah
  riwayat lokal yang bisa dipangkas. Menghapus log tidak boleh menghapus event yang belum
  terkirim.
- Semua waktu disimpan sebagai epoch milidetik UTC (`INTEGER`), bukan `TEXT`.
- Migrasi wajib punya test (`MigrationTestHelper`), bukan hanya `fallbackToDestructiveMigration`.

## 5. Alur event

```text
Rust event (UniFFI callback)
  -> Kotlin tulis ke outbox (is_synced = 0)     [atomik, satu transaksi]
  -> Kotlin update UI (hanya jika layar aktif)
  -> WorkManager: baca batch PENDING
  -> POST /v1/sync (Ktor)
  -> 200 + acked_seq: tandai is_synced = 1, hapus
  -> 409 gap: kirim ulang dari expected_seq
  -> gagal: attempts++, backoff
```

Penting: outbox ditulis **sebelum** UI diperbarui. Kalau proses mati setelah UI berubah
tetapi sebelum outbox tersimpan, event hilang tanpa jejak.

## 6. Kebijakan retensi dan pruning

Data dikelompokkan menjadi tiga:

| Kategori | Contoh | Kebijakan |
| --- | --- | --- |
| Master | poligon, `device_id`, pengaturan | Simpan sampai diubah pengguna |
| Outbox | event belum di-ACK | Simpan sampai ACK; **jangan** dipangkas karena umur |
| Histori | `table_logs` yang sudah `SYNCED` | Pangkas berdasarkan retensi + batas baris |

Ambang default:

- Retensi histori: **7 hari** (default), pilihan pengguna 3 / 7 / 14 / 30 hari.
- Batas baris: 10.000 baris terbaru; kelebihan dihapus FIFO.
- Ukuran DB target: < 50 MB; alarm bila > 100 MB.

Query prune (dijalankan WorkManager setelah sync sukses dan saat app start):

```sql
DELETE FROM table_logs
WHERE sync_status = 1
  AND event_at < :cutoffMillis;

DELETE FROM table_logs
WHERE id NOT IN (
  SELECT id FROM table_logs ORDER BY id DESC LIMIT 10000
);
```

Catatan teknis:

- `VACUUM` tidak bisa dijalankan di dalam transaksi; jalankan mingguan saat idle, bukan
  setiap siklus sync.
- Prune **tidak pernah** menyentuh `outbox` yang belum `is_synced = 1`.
- Jika storage < 50 MB: hentikan penulisan `table_logs`, pertahankan outbox dan engine
  tetap berjalan; UI menampilkan peringatan.

## 7. Pengaturan retensi di UI

- Lokasi: `Settings > Penyimpanan & Riwayat`.
- Opsi: 3 / 7 (default) / 14 / 30 hari, plus info estimasi ukuran DB saat ini.
- Perubahan disimpan ke DataStore, lalu WorkManager prune dijadwalkan ulang.
- Perubahan retensi **tidak** langsung menghapus data; prune berjalan pada siklus
  berikutnya agar tidak memblokir UI.
- Tampilkan peringatan bila pengguna memilih 3 hari: riwayat lokal lebih cepat hilang,
  tetapi data di VPS tetap tersimpan.

## 8. Heartbeat dan sinkronisasi

Heartbeat dikirim tiap 30-60 detik lewat WorkManager periodik (minimum interval Android
15 menit untuk worker periodik; karena itu heartbeat memakai `OneTimeWorkRequest`
berantai atau alarm ringan, bukan `PeriodicWorkRequest` 30 detik). Jangan memaksa
`PeriodicWorkRequest` di bawah 15 menit — Android akan mengabaikannya.

Karena itu:

- Heartbeat: `OneTimeWorkRequest` yang menjadwalkan dirinya sendiri 30-60 detik kemudian
  selama service aktif.
- Batch sync: `OneTimeWorkRequest` dengan constraint `NetworkType.CONNECTED`, dipicu
  setiap ada event baru (debounce 30 detik) dan saat app start.
- Prune: `PeriodicWorkRequest` harian.

## 9. Anti-pattern yang dihindari

| Anti-pattern | Alasan |
| --- | --- |
| Sync HTTP di dalam Rust | Harus akses SQLite via FFI, menduplikasi WorkManager, rawan di Doze |
| `PeriodicWorkRequest` 30 detik | Android membulatkan ke 15 menit minimum |
| Hapus baris setelah `POST` dikirim (tanpa ACK) | Request bisa gagal setelah terkirim |
| `fallbackToDestructiveMigration` di produksi | Data pengguna hilang saat skema berubah |
| Menyimpan `device_id` di `SharedPreferences` | Rawan race; DataStore lebih aman |
| Mengirim frame/gambar ke VPS | Melanggar batas privasi dan bandwidth |
| Timestamp `TEXT` lokal | Perbandingan dan pruning jadi lambat/ambigu |

## 10. Rujukan

- Kontrak payload: [`data-contracts.md`](data-contracts.md) §3, §5, §6.
- Ambang alert: [`monitoring.md`](monitoring.md) §2.4.
- Peta test: [`tdd-plan.md`](tdd-plan.md) §4.
