# Kompatibilitas perangkat dan onboarding

Dokumen ini menetapkan cara aplikasi memeriksa apakah tablet atau HP layak
menjalankan engine Rust, apa yang terjadi saat perangkat hanya memenuhi sebagian
syarat, dan bagaimana UI menjelaskan hasilnya. Pemeriksaan berjalan sekali saat
onboarding dan bisa diulang dari Pengaturan.

## 1. Batas keputusan

Target minimum untuk MVP:

| Komponen | Minimum | Rekomendasi |
| --- | --- | --- |
| Android | API 29 (Android 10) | API 31+ (Android 12+) |
| ABI | `arm64-v8a` | `arm64-v8a` |
| RAM total | 3 GB | 4 GB atau lebih |
| RAM bebas saat start | 600 MB | 1 GB atau lebih |
| Storage bebas | 1 GB | 2 GB atau lebih |
| Jaringan | Wi-Fi lokal | Wi-Fi 5 GHz stabil |
| Layar | 360 dp | 600 dp untuk dua panel |

Angka RAM adalah hipotesis yang harus divalidasi dengan `Debug.MemoryInfo` dan
`ActivityManager.MemoryInfo`. Jangan mengunci pengguna hanya dari angka total RAM
sebelum warm-up engine diuji.

Build `armeabi-v7a` boleh tetap tersedia untuk kompatibilitas paket, tetapi
perangkat 32-bit masuk kategori **tidak didukung** untuk engine AI. Jangan
menjanjikan inferensi pada ABI itu.

## 2. Urutan pemeriksaan

Pemeriksaan berjalan berurutan dan berhenti pada kegagalan fatal:

1. **Android dan ABI.** Baca `Build.VERSION.SDK_INT` dan `Build.SUPPORTED_ABIS`.
2. **ABI native.** Pastikan `arm64-v8a` ada dan `System.loadLibrary("core_ffi")`
   berhasil.
3. **RAM.** Baca total dan available memory dari `ActivityManager`.
4. **Storage.** Baca `StatFs` pada direktori data.
5. **Model dan runtime.** Minta Rust memuat model ONNX dan menjalankan warm-up
   satu frame sintetis.
6. **Izin dan jaringan.** Periksa `POST_NOTIFICATIONS`, status Wi-Fi, dan apakah
   perangkat berada di jaringan lokal.

Hasil setiap langkah: `pass`, `warning`, `unsupported`, atau `skipped`.
`skipped` tidak boleh diperlakukan sebagai `pass`.

## 3. Pembagian Kotlin dan Rust

| Pemeriksaan | Pemilik | Alasan |
| --- | --- | --- |
| Versi Android, ABI, RAM, storage, izin | Kotlin | API platform ada di Android SDK |
| `System.loadLibrary` dan simbol native | Kotlin | Kegagalan library harus ditangkap sebelum FFI |
| Muat ONNX dan warm-up | Rust | Runtime dan model adalah milik engine |
| Waktu warm-up dan memori native | Rust, dibaca Kotlin | Pengukuran harus dekat dengan alokasi |
| Keputusan UI | Kotlin | Rust tidak tahu layar atau bahasa |

`System.loadLibrary` harus dibungkus `try/catch` untuk `UnsatisfiedLinkError`.
Jangan memanggil fungsi UniFFI sebelum library berhasil dimuat.

## 4. Warm-up engine

Warm-up minimal:

1. Muat model dari path yang sudah diverifikasi checksum.
2. Buat sesi ONNX dengan konfigurasi thread yang dipilih.
3. Jalankan satu inferensi pada frame sintetis berukuran input model.
4. Lepas sesi dan buffer sementara.
5. Kembalikan `model_ready`, `warmup_ms`, dan `peak_memory_mb`.

Warm-up tidak boleh membuka RTSP, menyalakan foreground service, atau mengirim
data ke server. Kegagalan warm-up adalah `unsupported`, bukan warning, karena
engine tidak akan bisa memproses frame.

Kode error native yang stabil:

| Kode | Arti | Tindakan UI |
| --- | --- | --- |
| `ERR_ABI_UNSUPPORTED` | ABI bukan `arm64-v8a` | Blokir, sarankan perangkat lain |
| `ERR_ANDROID_TOO_OLD` | API di bawah 29 | Blokir |
| `ERR_LIBRARY_LOAD` | `libcore_ffi.so` gagal dimuat | Blokir, salin laporan |
| `ERR_MODEL_MISSING` | Model tidak ditemukan | Perbaiki instalasi |
| `ERR_MODEL_CHECKSUM` | Checksum model tidak cocok | Perbaiki instalasi |
| `ERR_ONNX_INIT` | Runtime gagal membuat sesi | Blokir, salin laporan |
| `ERR_WARMUP_FAILED` | Inferensi warm-up gagal | Blokir, salin laporan |
| `ERR_RAM_LOW` | RAM di bawah batas fatal | Blokir atau mode hemat |
| `ERR_STORAGE_LOW` | Storage di bawah batas fatal | Blokir, minta ruang |

## 5. Profil perangkat

Setelah pemeriksaan, pilih profil:

| Profil | Syarat | Perilaku |
| --- | --- | --- |
| `full` | ARM64, RAM >= 4 GB, warm-up lulus | 3-5 FPS, buffer normal |
| `balanced` | ARM64, RAM 3-4 GB, warm-up lulus | 2-3 FPS, buffer lebih kecil |
| `lite` | ARM64, RAM 3-4 GB, warm-up lulus | 1-2 FPS, preview boleh dimatikan |
| `blocked` | 32-bit, API < 29, library/model gagal | Tidak menjalankan engine |

Profil disimpan di DataStore agar hasil tidak berubah setiap app dibuka. Perubahan
RAM atau storage tidak otomatis menaikkan profil; jalankan ulang pemeriksaan.

## 6. Alur UI

### 6.1 Layar diagnostik

Layar memakai satu kolom dan status per baris:

```text
Pemeriksaan perangkat

Android dan prosesor        Lulus
Memori                     Peringatan
Mesin AI lokal              Lulus
Penyimpanan dan izin       Lulus

[ Jalankan ulang ]
```

Aturan:

- Jangan memakai bahasa internal seperti `arm64-v8a` di teks utama.
- Tampilkan detail teknis di bagian yang bisa dibuka.
- Animasi status wajib menghormati `prefers-reduced-motion`.
- Tombol yang memulai pemeriksaan memakai loading state dan tidak bisa ditekan
  dua kali.

### 6.2 Warning

Warning berarti aplikasi tetap boleh masuk dengan profil yang lebih rendah.

Contoh pesan:

```text
Perangkat ini memiliki RAM 3 GB. Pemantauan tetap bisa berjalan dengan mode
hemat: deteksi 2 FPS dan riwayat lokal lebih pendek.
```

Aksi:

- Tombol utama: **Lanjutkan dengan mode hemat**.
- Tombol sekunder: **Periksa ulang**.
- Simpan pilihan agar peringatan tidak muncul setiap app dibuka.
- Jangan memakai warna merah; warning memakai amber.

### 6.3 Unsupported

Unsupported berarti engine tidak dijalankan. Aplikasi memblokir Home, tidak
menyalakan foreground service, dan tidak membuka RTSP.

Isi layar:

- Alasan dalam bahasa sehari-hari.
- Perbandingan singkat: terdeteksi versus minimum.
- Tombol **Salin laporan perangkat**.
- Tombol **Tutup aplikasi**.
- Di build debug, tampilkan kode error dan versi native.

Laporan yang disalin tidak boleh memuat URL RTSP, password, token, atau SSID.

### 6.4 Pass

Jika semua lulus, lanjut ke pembuatan PIN atau Home tanpa layar tambahan. Jangan
menampilkan diagnostik lagi kecuali dipanggil dari Pengaturan.

## 7. Kapan pemeriksaan diulang

- Setelah instalasi atau update APK.
- Setelah update model ONNX.
- Setelah pengguna memilih **Periksa ulang**.
- Setelah crash native berulang, maksimal sekali per 24 jam.

Pemeriksaan tidak dijalankan setiap app dibuka karena warm-up memakan waktu dan
daya.

## 8. Test yang wajib ada

| Test | Harapan |
| --- | --- |
| `DeviceCapabilityTest` | API, ABI, RAM, dan storage dipetakan ke profil yang benar |
| `NativeLibraryLoadTest` | `UnsatisfiedLinkError` menjadi `ERR_LIBRARY_LOAD`, bukan crash |
| `WarmupContractTest` | Model hilang, checksum salah, dan runtime gagal menghasilkan kode stabil |
| `OnboardingViewModelTest` | Pass, warning, unsupported, dan retry menghasilkan state yang benar |
| `OnboardingScreenTest` | Tombol disabled saat memeriksa; warning tidak memakai warna merah |
| `DeviceReportTest` | Laporan tidak memuat kredensial, token, atau SSID |

Test unit memakai fake capability provider. Test native dijalankan di perangkat
fisik `arm64-v8a`; emulator hanya membuktikan UI.

## 9. Yang belum diputuskan

- Batas RAM final setelah pengukuran warm-up di perangkat nyata.
- Apakah `armeabi-v7a` tetap dikirim di APK atau dihapus.
- Ukuran model dan metode distribusi (asset APK, unduhan, atau Play Asset Delivery).
- Apakah profil `lite` mengizinkan preview video.
