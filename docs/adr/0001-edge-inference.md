# ADR 0001 — Inferensi di perangkat (edge), bukan VPS

- Status: diterima
- Tanggal: 2026-11-25

## Konteks

Kamera EZVIZ berada di jaringan lokal warung. Opsi yang dibandingkan: (a) inferensi di
Android, (b) kirim video ke VPS lalu inferensi di sana.

## Keputusan

Inferensi dijalankan di Android. VPS hanya menerima metadata JSON.

## Konsekuensi

- Butuh perangkat Android yang tersedia dan menyala 24/7 di lokasi.
- Tidak ada biaya bandwidth upload video dan tidak butuh port forwarding di router.
- Tetap berfungsi saat internet putus; sinkronisasi menyusul.
- Beban termal dan baterai menjadi risiko utama yang harus dimitigasi.
