# ADR 0004 - Monitoring dua lapis dan sinkronisasi offline-first

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Perangkat Android berjalan sebagai foreground service di lokasi warung tanpa pengawasan
teknis. Kegagalan yang perlu cepat diketahui: kamera putus, perangkat offline, HP panas,
app crash, dan antrean data menumpuk. VPS yang tersedia hanya 2 core / 2 GB dan tidak
boleh menerima video.

## Keputusan

1. Pisahkan **heartbeat teknis** dari **event bisnis**; kegagalan salah satu tidak menahan
   yang lain.
2. Tidak pernah mengirim video, frame penuh, atau audio. Thumbnail opsional dan default
   nonaktif.
3. Gunakan **outbox lokal + WorkManager** dengan ACK per batch; event hanya dihapus
   setelah server mengonfirmasi.
4. WebSocket untuk realtime best-effort, REST batch untuk durabilitas.
5. Crash reporting memakai Crashlytics; uptime memakai Uptime Kuma dengan alert Telegram.
6. Durasi sesi dan turnover dihitung di server dari transisi status, bukan payload sesi
   terpisah.
7. Idempotensi memakai `(device_id, seq)` dan server mendeteksi gap dengan `409`.

## Konsekuensi

- Perangkat membutuhkan storage kecil untuk outbox dan log rolling; aman untuk HP murah.
- Dashboard bisa terlambat beberapa detik saat realtime gagal, tetapi data tidak hilang.
- Perlu disiplin unggah symbol native setiap rilis agar crash Rust terbaca.
- Bertambahnya endpoint dan tabel heartbeat menuntut retensi/partisi agar DB 2 GB tetap
  ringan.
- Alert yang terlalu sensitif bisa menjadi noise; ambang di `monitoring.md` harus
  dituning pada uji lapangan.

## Alternatif yang ditolak

- **Kirim video ke VPS untuk inferensi**: bandwidth dan CPU VPS tidak memadai, serta
  melanggar prinsip video tidak keluar jaringan lokal.
- **MQTT untuk semua jalur**: andal, tetapi menambah broker dan operasional; WebSocket +
  REST cukup untuk skala awal.
- **Sentry self-host di VPS 2 GB**: berebut RAM dengan API dan database.
- **Hitung durasi di perangkat**: rawan drift jam dan duplikasi saat retry.
