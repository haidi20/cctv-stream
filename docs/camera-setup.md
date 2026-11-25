# Setup kamera EZVIZ

Dokumen ini menetapkan hal yang harus diketahui sebelum aplikasi menyentuh kamera
pertama. Isinya bukan pengganti panduan pabrik; ini checklist instalasi dan kontrak
yang harus cocok dengan aplikasi.

## 1. Status keputusan

Model kamera target **belum dipilih**. Jangan menulis path RTSP, codec, atau jumlah
sesi sebagai fakta sebelum model dan firmware diuji. Placeholder umum seperti
`/h264/ch1/sub/av_stream` dan `h264Preview_01_sub` adalah kandidat, bukan kontrak.

Yang harus diisi sebelum Fase K3:

| Item | Nilai | Bukti |
| --- | --- | --- |
| Merek dan model | Belum | Label kamera/foto unit |
| Firmware | Belum | Halaman status kamera |
| Sub-stream aktif | Belum | Menu kamera dan tes RTSP |
| Path RTSP final | Belum | `DESCRIBE` berhasil + frame pertama |
| Codec dan profil | Belum | `ffprobe` atau log `DESCRIBE` |
| Autentikasi | Belum | `WWW-Authenticate` pada respons 401 |
| Jumlah sesi RTSP | Belum | Tes dua klien bersamaan |
| Transport | Belum | Tes TCP dan UDP |
| IP statis/DHCP reservation | Belum | Daftar klien router |

## 2. Persyaratan minimum

- Kamera menyediakan RTSP di jaringan lokal.
- Sub-stream H.264 tersedia pada 360p atau 480p.
- Aplikasi dapat memakai akun RTSP terpisah dari akun admin kamera.
- Kamera dan tablet berada di subnet yang sama, atau ada rute/VPN yang disetujui.
- IP kamera memakai DHCP reservation agar path tidak berubah setelah reboot.
- Port default 554 atau 8554 tidak diblokir AP isolation.

Codec H.265 hanya boleh diaktifkan setelah `retina` dan decoder target terbukti
mendukung profil kamera tersebut. Untuk MVP, pilih H.264.

## 3. Format URL

Simpan komponen URL secara terpisah, bukan satu string yang memuat password:

```text
rtsp://<user>:<password>@<host>:<port>/<path>
```

Aturan:

- `host`, `port`, dan `path` boleh masuk `CameraProfile`.
- `password` hanya masuk Android Keystore.
- URL lengkap tidak boleh masuk log, crash report, screenshot, atau payload server.
- Path dengan karakter khusus harus di-encode sebelum dipakai klien RTSP.

Dokumentasikan path final per model, misalnya:

| Model | Sub-stream | Main stream | Catatan |
| --- | --- | --- | --- |
| Belum dipilih | Belum | Belum | Isi setelah tes |

## 4. Prosedur verifikasi kamera

Jalankan berurutan dan catat hasilnya:

1. Dari tablet, ping IP kamera.
2. Buka port TCP 554 atau 8554.
3. Kirim `OPTIONS`, lalu `DESCRIBE` dengan kredensial aplikasi.
4. Pastikan `DESCRIBE` mengembalikan video H.264 dan bukan hanya audio.
5. Buka sesi `SETUP` + `PLAY`, lalu minta satu keyframe.
6. Putuskan kabel jaringan 10 detik, sambungkan lagi, dan pastikan stream pulih.
7. Buka koneksi kedua dari klien lain untuk menguji batas sesi.
8. Jalankan 30 menit dan catat FPS, bitrate, packet loss, dan suhu tablet.

Hasil tes disimpan sebagai bukti instalasi, bukan hanya lulus di laptop developer.

## 5. Jaringan dan keamanan

- Jangan membuka port 554 ke internet publik.
- Pisahkan Wi-Fi tamu dan Wi-Fi perangkat operasional bila router mendukung.
- Matikan UPnP untuk kamera bila tidak diperlukan.
- Ganti password default sebelum kamera dipakai.
- Buat akun RTSP khusus aplikasi bila kamera mendukung.
- Catat prosedur reset pabrik dan pemulihan kredensial.
- Setelah reset, daftarkan ulang kamera dan hapus profil lama di aplikasi.

## 6. QR dan nama kamera

QR pada dus kamera belum tentu memuat RTSP. Banyak produsen memakai QR untuk
pairing aplikasi resmi. Karena itu aplikasi harus menerima dua jalur:

1. QR berisi payload aplikasi yang sudah divalidasi.
2. Input manual `host`, `port`, `path`, `username`, dan password.

Payload QR yang diusulkan:

```json
{
  "v": 1,
  "kind": "camera",
  "id": "cam-warung-depan",
  "label": "Warung Depan",
  "host": "192.168.1.64",
  "port": 554,
  "path": "/h264/ch1/sub/av_stream",
  "username": "viewer"
}
```

QR tidak boleh memuat password. Setelah scan, aplikasi meminta password atau
mengambil secret dari Keystore bila profil sudah ada.

Nama kamera adalah `label` yang bisa diubah pengguna, misalnya "Warung Depan".
`id` adalah UUID stabil dan tidak berubah saat label diedit. Nama kamera tidak
dikirim ke server kecuali pengguna secara eksplisit mengaktifkan sinkronisasi
metadata.

## 7. Checklist sebelum K3 selesai

- [ ] Model dan firmware tercatat.
- [ ] Path RTSP sub-stream terverifikasi.
- [ ] H.264 terdeteksi; H.265 tidak diaktifkan tanpa uji.
- [ ] Autentikasi dan akun aplikasi terdokumentasi.
- [ ] Batas sesi RTSP diuji.
- [ ] TCP dan UDP diuji; transport default dipilih.
- [ ] IP statis atau DHCP reservation terpasang.
- [ ] QR atau input manual dipilih sebagai jalur utama.
- [ ] Prosedur reset dan rotasi kredensial ditulis.
