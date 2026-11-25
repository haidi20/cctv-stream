# ADR 0007 - Deteksi "beda jaringan" tanpa menebak dari IP

- Status: Diusulkan
- Tanggal: 2026-11-25

## Konteks

Kasus paling sering saat instalasi adalah HP yang belum berada di jaringan yang sama
dengan kamera CCTV. Kebutuhan: beri pesan yang benar ke pengguna dan jangan tampilkan
status meja yang menyesatkan. Pilihan yang dipertimbangkan:

1. Menebak dari prefix IP (misal `192.168.1.x`) atau SSID Wi-Fi.
2. Menyerahkan semuanya ke Rust dan hanya menampilkan "gagal connect".
3. Membagi diagnosis: Kotlin memeriksa transport jaringan, Rust mengklasifikasikan
   kegagalan koneksi, lalu UI memetakan kombinasi keduanya.

## Keputusan

Opsi 3. Kotlin memeriksa `ConnectivityManager` (transport Wi-Fi/seluler/tidak ada) dan
memanggil Rust hanya bila ada jaringan. Rust melakukan satu percobaan koneksi dengan
timeout 5 detik dan mengembalikan enum `StreamErrorKind`:
`Unreachable`, `Timeout`, `AuthFailed`, `Protocol`.

UI memetakan `(StreamErrorKind, NetworkState)` menjadi pesan:

| Kondisi | Status UI | Pesan |
| --- | --- | --- |
| `transport = none` | `NoNetwork` | "Wi-Fi belum tersambung" |
| `transport = cellular` saja | `WrongNetwork` | "Sambungkan ke Wi-Fi lokasi" |
| `unreachable` + Wi-Fi aktif | `CameraOffline` | "Kamera tidak terjangkau di jaringan ini" |
| `timeout` | `CameraOffline` | "Kamera tidak mengirim data" |
| `auth_failed` | `AuthFailed` | "Password kamera salah" |
| `protocol` | `CameraOffline` | "Format stream tidak didukung" |
| tidak ada error | `Connected` | - |

## Konsekuensi

- Tidak ada pembacaan SSID atau penebakan subnet; diagnosis berbasis bukti koneksi.
- Rust tetap bebas dari API Android; Kotlin tetap tidak menyentuh RTSP. `NoNetwork` dan
  `WrongNetwork` hanya bisa dihasilkan Kotlin; `unreachable`/`timeout` hanya dari Rust.
- `auth_failed` tidak di-retry cepat; backoff minimal 60 detik agar akun tidak terkunci.
- Biaya: satu percobaan koneksi tambahan saat pengguna menekan **Tes koneksi**.
- Test tidak butuh dua jaringan fisik: TEST-NET-1 untuk unreachable, listener lokal
  untuk refused, mock RTSP untuk `401`.

## Alternatif yang ditolak

- **Tebak dari prefix IP**: salah pada NAT, VLAN, dan rute antar-subnet.
- **Baca SSID**: butuh permission lokasi, tidak membuktikan rute ke kamera, dan tidak
  membedakan AP isolation.
- **Serahkan semua ke Rust**: Rust tidak tahu apakah perangkat punya Wi-Fi; pesan yang
  dihasilkan akan generik dan tidak membantu pengguna.