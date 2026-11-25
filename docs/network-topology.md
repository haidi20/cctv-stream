# Topologi jaringan

Dokumen ini membandingkan dua jalur CCTV → pemrosesan dan menetapkan pilihan untuk repo
ini. Ringkasannya sudah ada di ADR 0001; dokumen ini menyimpan analisis dan konsekuensi
operasionalnya.

Status: **keputusan arsitektur**. Jalur Opsi B sudah dipilih, tetapi aplikasi Android,
RTSP nyata, dan server metadata belum diuji. Checklist kamera dan perilaku pemulihan
ada di [`camera-setup.md`](camera-setup.md). Bukti implementasi ada di
[`status.md`](status.md).

## 1. Dua opsi

| Aspek | Opsi A: CCTV → VPS (tunneling) | Opsi B: CCTV → Android → VPS (dipilih) |
| --- | --- | --- |
| Yang menarik RTSP | VPS | HP/tablet di lokasi |
| Video keluar jaringan lokal | Ya | **Tidak** |
| Bandwidth upload | Tinggi (2-4 Mbps per kamera) | Hampir nol (hanya JSON) |
| Beban VPS | Decode + inferensi + DB | Hanya API + DB |
| RAM VPS minimum | 4-8 GB (realistis) | 2 GB cukup |
| Ketergantungan internet | Putus = monitoring mati | Putus = monitoring tetap jalan |
| Konfigurasi lokasi | Port forwarding / VPN router / DDNS | Cukup Wi-Fi lokal |
| Latensi status | Tergantung jaringan | Instan (di perangkat) |
| Risiko keamanan | Port RTSP terbuka / kredensial di jaringan | Tidak ada port terbuka |

## 2. Opsi A: kenapa ditolak

Kamera EZVIZ tidak bisa memasang klien VPN di dalamnya; tunneling harus di router.
Pilihan yang muncul:

- **Port forwarding + DDNS**: membuka port 554 ke internet. Rentan pemindaian otomatis,
  dan sering gagal pada ISP dengan CG-NAT (IndiHome/Biznet/XL).
- **VPN router (WireGuard/OpenVPN)**: lebih aman, tetapi menuntut router MikroTik/OpenWrt
  di setiap lokasi. Biaya dan kompleksitas operasional naik.

Selain itu, VPS 2 core / 2 GB tidak realistis untuk decode H.264/H.265 plus inferensi
ONNX terus-menerus; CPU akan penuh dan berisiko OOM.

## 3. Opsi B: alur yang dipilih

```text
[Kamera EZVIZ] --RTSP/Wi-Fi lokal--> [Android: Rust core] --JSON--> [VPS]
```

- RTSP ditarik lewat Wi-Fi warung; tidak melewati internet.
- Inferensi dan geometri di perangkat; VPS hanya menyimpan metadata.
- IP kamera tidak perlu diketahui dari luar; cukup satu subnet dengan HP.
- Bila internet putus, status meja tetap akurat dan antrean bertahan sampai tersambung
  (lihat `monitoring.md` §4).

Pipeline preview dan decode masih terbuka di
[`adr/0009-video-pipeline.md`](adr/0009-video-pipeline.md). Jalur Opsi B tidak otomatis
berarti satu koneksi RTSP; keputusan Media3, decoder Rust, atau hybrid belum diambil.

## 4. Konsekuensi yang harus diterima

| Konsekuensi | Mitigasi |
| --- | --- |
| Butuh perangkat Android menyala 24/7 | Charger permanen, foreground service, alert heartbeat > 5 menit |
| HP bisa panas | Sub-stream 360p/480p, 2-5 FPS, pantau suhu (lihat `monitoring.md`) |
| Kamera harus satu jaringan dengan HP | Dokumentasikan di panduan instalasi; dukung input IP manual |
| VPS tetap butuh internet | Antrean offline + retry; dashboard boleh terlambat |
| Keamanan kredensial RTSP | Simpan di Android Keystore, redact di log, jangan kirim ke VPS |

## 5. Bila kelak butuh opsi A

Opsi A baru masuk akal bila salah satu terpenuhi:

- Ada VPS dengan >= 8 GB RAM dan decode hardware.
- Lokasi tidak memungkinkan perangkat Android menyala terus.
- Butuh rekaman video terpusat (di luar cakupan saat ini).

Bila itu terjadi, `stream-engine`, `ai-engine`, dan `spatial-engine` dapat dipakai ulang
sebagai service Linux tanpa perubahan logika, karena semuanya tidak bergantung pada
Android. Yang berubah hanya `core-ffi` (diganti binary CLI/daemon) dan lapisan UI.

## 6. Jaringan lokal: hal yang perlu dicek saat instalasi

- HP dan kamera berada di subnet yang sama; cek `ping` ke IP kamera.
- Port RTSP kamera (default 554) tidak diblokir oleh isolasi klien Wi-Fi (AP isolation).
- IP kamera sebaiknya statis (DHCP reservation) agar URL tidak berubah.
- Kualitas Wi-Fi di titik kamera: RSSI cukup, tidak ada packet loss saat 30 menit uji.
- Kredensial RTSP dibuat khusus untuk aplikasi, bukan akun admin utama.
- Prosedur lengkap ada di [`camera-setup.md`](camera-setup.md) §4; catat model,
  firmware, codec, path, dan batas sesi sebelum menghubungkan aplikasi.

### 6.1 Pemulihan saat Wi-Fi atau kamera hilang

| Kondisi | Perilaku target | Bukti yang harus dikumpulkan |
| --- | --- | --- |
| Wi-Fi mati | `no_network`; tidak membuka RTSP; meja `unknown` | Test `ConnectivityManager` + UI |
| Hanya data seluler | `wrong_network`; tidak memindai subnet seluler | Test konektivitas |
| Wi-Fi hidup, kamera mati | `unreachable`; retry dengan backoff | Mock server dan kamera nyata |
| Kamera restart | `closed` lalu `connecting`; sesi baru | Uji 10 kali restart |
| Wi-Fi kembali | Satu percobaan langsung, lalu backoff normal bila gagal | Log transisi status |
| Kamera pulih | Status dihitung ulang dari frame baru; bukan melanjutkan status lama | Event `unknown -> available/occupied` |

Jangan mengubah status terakhir menjadi `available` saat koneksi hilang. Retry cepat
tanpa batas dan retry `auth_failed` berulang juga dilarang; lihat
[`data-contracts.md`](data-contracts.md) §5.1.

## 7. Kalau HP belum satu jaringan dengan CCTV

Ini kondisi paling sering saat instalasi, dan jawabannya: **tidak bisa** — dengan
pengecualian yang dijelaskan di bawah. RTSP adalah koneksi langsung dari HP ke kamera;
kalau tidak ada rute IP dari HP ke kamera, tidak ada frame yang bisa diproses.

### 7.1 Apa yang terjadi per skenario

| Skenario | Bisa? | Yang terlihat di app | Tindakan |
| --- | --- | --- | --- |
| HP di Wi-Fi warung, kamera di Wi-Fi yang sama | Ya | `connected` | - |
| HP di Wi-Fi tamu (guest network) | Tidak | `unreachable` | Pindahkan HP ke SSID utama |
| HP di Wi-Fi tetangga / hotspot berbeda | Tidak | `unreachable` | Sambungkan ke Wi-Fi lokasi |
| HP pakai data seluler saja | Tidak | `wrong_network` | Nyalakan Wi-Fi lokal |
| Subnet berbeda tetapi router punya rute (VLAN) | Ya, bila diizinkan firewall | `connected` | Pastikan rule antar-VLAN membuka TCP 554 |
| AP isolation / client isolation aktif | Tidak | `unreachable` | Matikan isolasi klien di AP |
| Kamera di belakang router lain (double NAT) | Tidak, kecuali port di-forward | `unreachable` | Satukan di satu router |
| Kamera EZVIZ diakses lewat cloud/P2P | Tidak relevan | - | RTSP lokal tetap butuh IP lokal |
| HP di luar lokasi lewat VPN ke router | Ya, bila VPN mengizinkan rute ke subnet kamera | `connected` | Di luar dukungan awal; lihat §7.4 |

Status yang dikirim ke UI/server hanya enum (`unreachable`, `timeout`, `auth_failed`,
`no_network`, `wrong_network`), bukan alamat IP. Pesan yang ditampilkan berbeda karena penyebabnya
berbeda: "Wi-Fi belum tersambung", "kamera tidak terjangkau di jaringan ini", atau
"password kamera salah".

### 7.2 Cara mendiagnosis berurutan

1. **Transport**: apakah perangkat punya Wi-Fi? Kalau tidak, `no_network`; jangan
   panggil Rust sama sekali.
2. **Subnet**: bandingkan IP HP dan IP kamera (misal `192.168.1.20` vs
   `192.168.1.64`). Beda tiga oktet pertama berarti kemungkinan besar tidak satu LAN.
3. **Reachability**: `ping` ke IP kamera; kalau gagal, jangan buang waktu menguji
   kredensial.
4. **Port**: cek TCP 554 terbuka (bukan diblokir AP isolation atau firewall).
5. **Kredensial**: baru diuji setelah tiga langkah di atas lolos.

Aplikasi melakukan urutan yang sama lewat tombol **Tes koneksi**, tetapi tiap langkah
punya timeout pendek agar pengguna tidak menunggu lama.

### 7.3 Yang tidak boleh dilakukan aplikasi

- Menebak "satu jaringan" dari prefix IP; NAT, VLAN, dan rute bisa membuat prefix sama
  tetapi tidak terhubung, atau prefix beda tetapi terhubung.
- Retry terus-menerus saat `auth_failed`; ini bisa mengunci akun kamera.
- Menampilkan URL RTSP lengkap di pesan error atau log.
- Menyarankan port forwarding ke internet kepada pemilik warung.

### 7.4 Kalau memang harus beda jaringan

Untuk kasus yang sah (kamera di gudang terpisah, HP di kantor), urutannya:

1. **VPN site-to-site** antara router lokasi kamera dan jaringan HP; buka hanya TCP 554
   ke IP kamera. Ini menambah dependensi infrastruktur dan tidak masuk Fase 1.
2. **Android di lokasi kamera** yang meneruskan metadata ke VPS; HP pemilik cukup
   membaca dashboard. Ini tetap sesuai arsitektur Opsi B dan lebih aman daripada
   mengekspos RTSP.
3. **Opsi A (tunneling ke VPS)** hanya bila VPS memadai dan rekaman terpusat memang
   dibutuhkan; lihat §2 dan §5.

Yang **tidak** disarankan: membuka port 554 ke internet publik. Itu mengundang pemindaian
otomatis dan biasanya tetap gagal di ISP dengan CG-NAT.

Port forwarding ke kamera juga bukan jalur dukungan resmi. Untuk kasus beda jaringan,
pilih VPN site-to-site atau tempatkan Android di lokasi kamera. Jangan meminta pemilik
warung mengekspos RTSP ke internet sebagai workaround.

### 7.5 Dampak ke rencana uji

Kasus-kasus di §7.1 menjadi **target test** eksplisit di
[`tdd-plan.md`](tdd-plan.md) §3.2.1 dan §4.1. Tidak ada test yang membutuhkan dua
jaringan fisik: simulator memakai alamat TEST-NET-1 untuk "tidak terjangkau", listener
lokal untuk "port tertutup", dan mock RTSP untuk `401`.

Cakupan saat ini baru dua test: `rtsp_client_mock.rs` dan
`error_classification.rs`. Simulasi TEST-NET-1 dan AP isolation belum ada.
