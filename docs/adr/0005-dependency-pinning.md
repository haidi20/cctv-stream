# ADR 0005 - Pin versi dependensi native dan `ort` RC

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Draft perencanaan awal memakai versi crate yang sudah tidak berlaku (`ringbuf` 0.3,
`ort` "2.0" yang belum stabil, UniFFI dengan UDL dan `setup_scaffolding!("nama")`).
Beberapa API berubah secara breaking. `ort` masih RC, sedangkan `retina` menuntut MSRV
1.96 yang lebih tinggi dari crate lain.

## Keputusan

1. Pin `ort` secara exact (`=2.0.0-rc.15`) karena belum ada rilis stabil.
2. Crate stabil memakai caret, dengan MSRV workspace 1.96 mengikuti `retina`.
3. `Cargo.lock` di-commit karena ini aplikasi, bukan library.
4. Tidak memakai file UDL; UniFFI mode proc-macro saja (`setup_scaffolding!()`).
5. Gunakan `ringbuf::HeapRb` + `split()` + `try_push`/`push_overwrite`, bukan tipe
   `HeapProd`/`HeapCons` yang sudah tidak ada.
6. Panic boundary memakai `catch_unwind` (profil default), bukan `panic = "abort"`.

## Konsekuensi

- Upgrade `ort` adalah keputusan sadar; perubahan rc dapat mengubah API.
- MSRV tinggi membatasi toolchain lama; CI harus memakai Rust >= 1.96.
- `Cargo.lock` menambah noise di diff setiap kali dependensi berubah.
- Tanpa UDL, semua tipe FFI harus didokumentasikan lewat anotasi di kode.
- `catch_unwind` menambah sedikit overhead per entry point, tetapi monitoring tetap
  hidup saat satu frame bermasalah.

## Alternatif yang ditolak

- **Tunggu `ort` stabil**: menunda seluruh Fase 2 tanpa batas waktu yang jelas.
- **Pakai `tch`/libtorch**: ukuran binary jauh lebih besar dan cross-compile Android
  lebih rumit.
- **MSRV rendah + `retina` versi lama**: kehilangan perbaikan RTSP/H.265.
- **`panic = "abort"`**: proses mati total saat panic; tidak cocok untuk service 24/7.