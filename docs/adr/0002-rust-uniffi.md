# ADR 0002 — Rust core dengan UniFFI untuk Android

- Status: diterima
- Tanggal: 2026-11-25

## Konteks

Pipeline video + AI harus berjalan lama di background tanpa lag akibat garbage collection
dan tanpa memori yang naik terus.

## Keputusan

Core engine ditulis di Rust sebagai `cdylib`, dijembatani ke Kotlin via UniFFI. Buffer
frame memakai ring buffer pra-alokasi; tidak ada alokasi di loop panas.

## Konsekuensi

- Butuh toolchain NDK dan proses build lintas arsitektur.
- Bindings Kotlin digenerate; perubahan API FFI harus lewat definisi UniFFI.
- Perlu disiplin `panic = abort`/penanganan error agar panic tidak menyeberang FFI.
