# ADR 0003 — Sumber video ganda: file lokal dan RTSP

- Status: diterima
- Tanggal: 2026-11-25

## Konteks

Mengembangkan langsung dengan kamera fisik menyulitkan reproduksi bug: jaringan putus,
kamera restart, dan posisi kamera berubah.

## Keputusan

`stream-engine` menyediakan dua implementasi sumber dengan antarmuka sama: pembaca file
video lokal (dengan pacing FPS) dan klien RTSP. Pengujian memakai file lokal; produksi
memakai RTSP.

## Konsekuensi

- Logika hilir (AI, spasial, FFI) tidak perlu diubah saat beralih ke kamera nyata.
- Dibutuhkan video uji yang mewakili sudut pandang CCTV warung.
- Pacing file lokal harus meniru FPS asli agar hasil uji relevan.
