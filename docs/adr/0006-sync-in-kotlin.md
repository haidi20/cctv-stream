# ADR 0006 - Sinkronisasi di Kotlin, bukan di Rust

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Rust secara teknis mampu melakukan HTTP request dan mengelola antrean SQLite. Muncul
pertanyaan apakah sync ke VPS sebaiknya dipindahkan ke Rust agar Kotlin lebih tipis.
Padanan infrastrukturnya: WorkManager, Room, DataStore, dan Crashlytics semuanya
Android-native.

## Keputusan

Semua sinkronisasi jaringan dan antrean lokal dikerjakan di Kotlin:

- **Outbox** di Room (`outbox` table).
- **Penjadwalan** di WorkManager dengan constraint `NetworkType.CONNECTED`.
- **HTTP** memakai Ktor Client.
- **Preferensi** (retensi, `device_id`) di DataStore.
- **Crash reporting** di Firebase Crashlytics NDK.

Rust hanya menyerahkan event status lewat UniFFI; tidak tahu ada internet, HTTP, atau
database.

## Konsekuensi

- Rust tetap bebas `Context`, sehingga bisa diuji di host dan dipakai ulang di Linux.
- Doze/App Standby ditangani WorkManager, bukan kode sendiri.
- Migrasi Room memakai `MigrationTestHelper`, bukan SQL manual di Rust.
- Kotlin menjadi tempat bug sync; perlu test yang kuat (`tdd-plan.md` §4).
- Satu lapisan ekstra: event Rust → Kotlin, dengan latensi < 1 ms.

## Alternatif yang ditolak

- **Sync di Rust dengan `reqwest` + `rusqlite`**: menduplikasi WorkManager, butuh
  penanganan Doze manual, dan membuat Rust bergantung pada path database Android.
- **Sync di Rust tapi DB di Kotlin**: paling buruk — dua bahasa berbagi satu file SQLite,
  rawan lock dan inkonsistensi.
- **PeriodicWorkRequest 30 detik**: Android membulatkan minimum ke 15 menit; heartbeat
  memakai rantai `OneTimeWorkRequest`.
- **`fallbackToDestructiveMigration`**: data pengguna hilang saat skema berubah.