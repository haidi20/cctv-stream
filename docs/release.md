# Rilis APK, model, dan perangkat operasional

Status: **rencana**. Belum ada APK, workflow rilis, atau model produksi di repo.

## 1. Artefak rilis

| Artefak | Lokasi | Dikelola |
| --- | --- | --- |
| APK/AAB | CI artifact + distribusi terpilih | Gradle + signing key |
| `libcore_ffi.so` | `jniLibs/<abi>` di APK | `tools/android-build/build-rust.ps1` |
| Binding Kotlin UniFFI | `app/build/generated/uniffi/kotlin` | Build script; jangan edit tangan |
| Model ONNX | Asset APK atau unduhan terpisah | Checksum + lisensi |
| Symbol native | Crash reporting | Upload tiap rilis |
| Log build | CI artifact | Retensi 30 hari |

## 2. Signing

- Debug APK hanya untuk internal; jangan dibagikan ke warung.
- Release key berada di CI secret/Keystore, tidak di repo dan tidak di mesin developer
  biasa.
- Simpan salinan recovery key di tempat aman di luar repo; kehilangan key berarti
  tidak bisa memperbarui APK yang sudah terpasang.
- Aktifkan Play App Signing jika distribusi lewat Play Store.
- Jangan memakai debug key untuk build yang menyimpan kredensial kamera nyata.

## 3. Strategi update

| Jalur | Kelebihan | Kekurangan | Kapan dipakai |
| --- | --- | --- | --- |
| Play Store | Update otomatis, rollback staged | Butuh akun, review, ukuran | Distribusi umum |
| Sideload terkelola | Kontrol penuh, cepat | Update manual, risiko versi bercampur | Pilot warung |
| MDM/kiosk | Kebijakan perangkat | Biaya dan kompleksitas | Banyak lokasi |

Aturan:

- `versionCode` selalu naik; `versionName` mengikuti semver.
- Schema database dan model harus kompatibel dengan satu versi sebelumnya.
- Update tidak boleh menghapus PIN, kamera, atau konfigurasi meja.
- Rollback aplikasi tidak boleh menurunkan schema tanpa migrasi teruji.

## 4. Matriks ABI dan perangkat

| ABI | Paket | Engine AI | Catatan |
| --- | --- | --- | --- |
| `arm64-v8a` | Ya | Ya | Target utama; wajib diuji fisik |
| `armeabi-v7a` | Kompatibilitas paket | Tidak | Jangan janjikan deteksi |
| `x86_64` | Emulator | Terbatas | Hanya untuk test UI/runtime |

Perangkat minimum mengikuti [`device-compatibility.md`](device-compatibility.md):
Android 10, RAM 3 GB, storage 1 GB, dan warm-up model lulus. Untuk operasional,
disarankan tablet 4 GB+ dengan charger permanen dan ventilasi.

## 5. R8/ProGuard dan JNI

- UniFFI/JNA membutuhkan keep rule untuk kelas binding dan nama method native.
- Jangan mengandalkan `-keep class *`; tambahkan rule spesifik dan jalankan
  `assembleRelease` di CI.
- Verifikasi `System.loadLibrary("core_ffi")` pada build release, bukan hanya debug.
- Simpan mapping file dan native symbol agar crash report bisa dibaca.
- Uji release build di perangkat fisik sebelum distribusi.

## 6. Model ONNX

Model yang dipakai adalah YOLOv11n pra-latih (COCO, kelas `person`). Model bukan
hasil pelatihan ulang kecuali ada keputusan produk baru.

Persyaratan distribusi:

- checksum SHA-256 dicatat di manifest rilis;
- lisensi model dan ONNX Runtime disertakan;
- ukuran model dan kebutuhan RAM dicatat;
- model diverifikasi saat start (checksum) dan gagal ke `ERR_MODEL_CHECKSUM` bila beda;
- update model memicu warm-up ulang dan test akurasi;
- rollback model tersedia tanpa perlu downgrade APK.

FP16/INT8 hanya boleh dikirim setelah prosedur di [`performance.md`](performance.md)
§6.2 lulus. Model sintetis untuk test runtime tidak boleh masuk APK produksi.

## 7. Perangkat operasional

- Dokumentasikan langkah battery optimization/autostart untuk merek tablet yang dipakai.
  Jangan mengandalkan satu merek sebagai representasi semua OEM.
- Gunakan charger permanen dan kabel yang aman; pantau suhu baterai.
- Kiosk/dedicated device adalah pilihan, bukan default; pastikan cara keluar darurat
  tersedia untuk pemilik.
- Setelah reboot atau pemadaman listrik, service hanya menyala otomatis bila opsi itu
  dipilih. Jika tidak, aplikasi harus meminta PIN dan menampilkan status jujur.
- Uji minimal: reboot, doze, layar mati 8 jam, Wi-Fi putus, kamera restart, dan storage
  hampir penuh.

## 8. Checklist rilis

- [ ] `versionCode` naik dan changelog ditulis.
- [ ] Unit test, lint, dan build release hijau.
- [ ] APK release diuji di perangkat fisik `arm64-v8a`.
- [ ] `libcore_ffi.so` untuk semua ABI yang dikirim termuat.
- [ ] Model + checksum + lisensi tersedia.
- [ ] Symbol native dan mapping file diunggah.
- [ ] Backup key dan prosedur rollback diuji.
- [ ] Tidak ada kredensial, token, atau URL RTSP di artefak/log.
- [ ] Diagnostik perangkat dan warning/unsupported tampil benar.
