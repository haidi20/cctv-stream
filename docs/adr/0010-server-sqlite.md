# ADR 0010 - SQLite sebagai database server tahap pertama

- Status: Diusulkan
- Tanggal: 2026-11-26

## Konteks

`server-api.md` dan `monitoring.md` menargetkan PostgreSQL 16 di VPS 2 core / 2 GB.
Mesin pengembangan saat ini tidak memiliki PostgreSQL, Docker, atau `psql`, sehingga
target itu tidak bisa diuji lokal. Yang dibutuhkan sekarang adalah server yang bisa
dibangun, diuji, dan dijalankan tanpa layanan eksternal, dengan bentuk skema yang siap
dipindah ke PostgreSQL.

## Keputusan

1. Fase pertama memakai **SQLite** lewat `sqlx` (backend `sqlite`, tanpa fitur `any`).
2. Skema ditulis dengan tipe yang portabel: `TEXT` untuk id dan timestamp RFC 3339,
   `INTEGER` untuk `seq` dan hitungan, `REAL` untuk confidence/suhu.
3. Semua akses database lewat satu modul `store`; tidak ada SQL di handler.
4. Migrasi memakai `sqlx::migrate!` dan dijalankan saat startup.
5. Database default `cctv.db`; path dapat diubah lewat `CCTV_DB_PATH`.
6. PostgreSQL tetap target produksi. Perpindahan dilakukan dengan mengganti backend
   `sqlx` dan menulis ulang DDL, bukan dengan mengubah bentuk payload.

## Konsekuensi

- Satu binary bisa dijalankan di laptop, CI, dan VPS kecil tanpa container.
- SQLite hanya cocok untuk satu proses penulis; ini cukup untuk satu instance API.
- `sqlx` tanpa fitur `any` berarti test memakai file sementara, bukan `:memory:`,
  agar semantik transaksi dan WAL sama dengan produksi.
- Query tidak diverifikasi saat compile (`query`, bukan `query!`), sehingga test
  integrasi wajib menutup setiap jalur SQL. Ini disengaja: CI tidak boleh butuh
  database hidup.
- Backup memakai `VACUUM INTO`/salinan file, bukan `pg_dump`; prosedur PostgreSQL
  baru berlaku setelah migrasi backend.

## Alternatif yang ditolak

- **PostgreSQL sekarang**: tidak bisa dijalankan atau diuji di mesin ini; akan
  menghasilkan kode yang tidak pernah dieksekusi.
- **`sqlx` fitur `any`**: menambah lapisan dinamis dan dependensi driver yang tidak
  dipakai, serta mempersulit pin versi.
- **ORM (SeaORM/Diesel)**: menambah generator dan makro; skema sederhana ini lebih
  jelas sebagai SQL biasa.
- **Penyimpanan file JSON**: tidak memberi transaksi, indeks unik, atau idempotensi.