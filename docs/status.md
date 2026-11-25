# Status implementasi dan bukti

Dokumen ini memisahkan rencana dari bukti. Gunakan istilah berikut secara
konsisten:

| Istilah | Arti |
| --- | --- |
| Direncanakan | Ada di dokumen, belum ada artefak |
| Ada | File atau kode ada |
| Unit-tested | Ada test otomatis yang lulus di host |
| Android build | Cross-compile atau Gradle build berhasil |
| Perangkat teruji | Berjalan di perangkat fisik target |
| Lapangan teruji | Lulus acceptance di lokasi nyata |

## 1. Snapshot 2026-11-25

| Area | Status | Bukti | Yang belum |
| --- | --- | --- | --- |
| Workspace Rust | Ada, unit-tested | `rust-core/Cargo.toml`, test workspace | Verifikasi ulang setelah perubahan lokal |
| `stream-engine` | Ada, unit-tested | RTSP client, file source, ring buffer, sampler | Integrasi decode ke preview |
| `ai-engine` | Ada, unit-tested | `ort`, preprocess, postprocess, NMS | Akurasi model di kamera nyata |
| `spatial-engine` | Ada, unit-tested | polygon, geometry, debounce, multi-table | Keputusan anchor dan acceptance lapangan |
| `core-ffi` | Ada, unit-tested | binding UniFFI, listener, panic guard | Kontrak frame dan Surface |
| Android app | Scaffold sebagian | folder `android/`, binding dan `jniLibs` | Gradle app, UI, service, Room |
| Server | Direncanakan | folder kosong | API, DB, dashboard |
| Kontrak JSON | Sebagian | `contracts/fixtures/valid/meja-mock.json` | Schema dan fixture invalid |
| CI | Direncanakan | command lokal di `AGENTS.md` | Workflow CI |
| Kamera nyata | Belum diverifikasi | belum ada unit | Model, path, codec, sesi |
| Perangkat fisik | Belum diverifikasi | `adb devices` kosong saat audit | Instrumented test dan soak |

Snapshot ini tidak boleh dinaikkan tanpa bukti baru. Perbarui tanggal dan bukti
setiap kali fase selesai.

## 2. Kontradiksi yang harus diselesaikan

Dokumen arsitektur menyebut Rust membuka RTSP dan melakukan decode. Implementasi
FFI saat ini menerima frame RGB dari Kotlin lewat `push_frame`. Keduanya belum
bisa benar bersamaan.

Tiga opsi yang mungkin:

| Opsi | Preview | Deteksi | Konsekuensi |
| --- | --- | --- | --- |
| A. Media3 penuh | Media3 | Frame RGB dari Kotlin | Dua koneksi atau satu koneksi dengan frame copy |
| B. Rust penuh | Surface dari Rust | Rust | Perlu decoder dan jembatan `ANativeWindow` |
| C. Hybrid sementara | Media3 | Rust | Hanya untuk internal, bukan rilis |

Keputusan harus masuk ke ADR baru sebelum K5. Sampai itu terjadi, jangan menulis
"single connection" sebagai fakta.

## 3. Artefak yang belum ada

| Artefak | Status | Dampak |
| --- | --- | --- |
| `contracts/schemas/table-config.schema.json` | Belum | Validasi lintas bahasa belum otomatis |
| `contracts/schemas/table-status.schema.json` | Belum | Event belum punya schema |
| `contracts/schemas/sync-request.schema.json` | Belum | Server belum punya kontrak formal |
| `contracts/schemas/sync-ack.schema.json` | Belum | ACK belum tervalidasi |
| `contracts/schemas/heartbeat.schema.json` | Belum | Heartbeat belum tervalidasi |
| `contracts/fixtures/invalid/` | Belum | Test negatif belum ada |
| `contracts/tests/schema_test.rs` | Belum | `cargo test -p contracts` belum bisa |
| `tools/ci/verify.ps1` | Belum | Gate semua bahasa belum satu perintah |
| `.github/workflows/` | Belum | CI belum aktif |
| `server/` | Kosong | Sync dan dashboard belum ada |
| `android/` app Gradle | Belum | APK belum bisa dibangun |

## 4. Bukti yang sudah ada

- Binding Kotlin UniFFI di `android/app/build/generated/uniffi/kotlin`.
- `libcore_ffi.so` untuk `arm64-v8a`, `armeabi-v7a`, dan `x86_64` di
  `android/app/src/main/jniLibs`.
- Fixture poligon kanonik di `contracts/fixtures/valid/meja-mock.json`.
- Test ONNX sintetis di `tools/onnx-test/` untuk menguji kontrak runtime.
- `testdata/SOURCES.md` mencatat asal empat klip stok.

Yang tidak boleh diklaim dari bukti itu:

- APK sudah berjalan.
- Kamera sudah terhubung.
- Akurasi sudah terukur.
- CI sudah hijau.
- Server sudah menerima event.

## 5. Aturan pembaruan status

1. Setiap klaim status harus menyebut artefak atau perintah yang membuktikannya.
2. Test host tidak menaikkan status menjadi "perangkat teruji".
3. Build Android tidak menaikkan status menjadi "lapangan teruji".
4. Dokumen rencana boleh memakai bentuk future tense; dokumen status memakai
   bentuk faktual.
5. Jika bukti hilang atau tidak bisa dijalankan ulang, turunkan status menjadi
   "belum diverifikasi".
