# Rencana aplikasi Android (Kotlin) — Login → Scan CCTV → Live + ringkasan meja

- Status: rencana implementasi; scaffold Gradle, login PIN, Home adaptif, Scan CCTV,
  mapping poligon per `camera_id`, Room v2, dan APK debug host sudah ada. Detail bukti
  ada di [`status.md`](status.md); checklist di §15 menandai sisa pekerjaan
- Tanggal: 2026-11-25
- Lingkup: aplikasi Android (`android/`) untuk alur **login → halaman utama → tombol
  Scan CCTV → stream tampil → total meja kosong/terisi di bawahnya**
- Dokumen terkait: [`architecture.md`](architecture.md),
  [`android-sync.md`](android-sync.md), [`data-contracts.md`](data-contracts.md),
  [`tdd-plan.md`](tdd-plan.md), [`network-topology.md`](network-topology.md),
  [`performance.md`](performance.md), [`rust-scaffold.md`](rust-scaffold.md),
  [`camera-setup.md`](camera-setup.md), [`device-compatibility.md`](device-compatibility.md),
  [`acceptance.md`](acceptance.md), [`status.md`](status.md)

Dokumen ini adalah **satu-satunya acuan UI/UX dan urutan kerja Kotlin**. Bila ada
pertentangan dengan draft lama, ikuti dokumen ini dan perbarui draft lama.

Dokumen ini adalah **rencana**, bukan status implementasi. Jangan menandai checklist
selesai tanpa artefak yang bisa dijalankan; lihat [`status.md`](status.md).

---

## 0. Ringkasan keputusan

| ID | Keputusan | Alasan singkat |
| --- | --- | --- |
| D1 | Login **lokal**: PIN 6 digit + biometrik opsional | Warung harus bisa dipakai tanpa internet; VPS bersifat opsional |
| D2 | Akun VPS **opsional** (fase K8), bukan syarat masuk | Menjaga offline-first; token perangkat baru relevan saat sinkronisasi dipakai |
| D3 | "Scan CCTV" = pencarian kamera berlapis: WS-Discovery → probe subnet → QR → manual | Satu tombol, tapi tetap punya jalan keluar saat discovery diblokir router |
| D4 | **Tanpa bottom navigation** dan tanpa drawer | Permintaan produk; alur linear, satu tujuan utama per layar |
| D5 | Layout adaptif berdasarkan **lebar** (bukan orientasi) | Tablet portrait juga butuh dua panel; HP landscape tetap muat |
| D6 | Preview MVP diusulkan memakai **Media3 RTSP**; target akhir belum dipilih | Media3 membuka jalan UI lebih cepat; single-decode butuh FFI yang belum ada |
| D7 | Scan & Connect adalah **overlay state** di `HomeViewModel`, bukan route terpisah | Stream tetap hidup di belakang sheet; tidak ada re-connect saat buka/tutup sheet |
| D8 | Angka meja berasal dari **Rust**; Kotlin tidak menghitung status | Selaras `tdd-plan.md` §2; mencegah dua sumber kebenaran |
| D9 | `unknown` **selalu** ditampilkan, tidak dilebur ke "kosong" | Kamera mati tidak boleh terlihat seperti meja kosong |
| D10 | Kredensial kamera di Android Keystore; PIN di-hash PBKDF2 + lockout | Tidak ada password plaintext di disk/log |
| D11 | Foreground service `mediaPlayback` (+ `dataSync` untuk sinkronisasi) | Stream harus tetap jalan saat layar mati |
| D12 | **Demo mode** dengan `FakeStreamEngineGateway` | UI bisa dibangun, diuji, dan didemokan sebelum Rust FFI selesai |
| D13 | Diagnostik perangkat wajib sebelum PIN/Home | Perangkat yang tidak bisa memuat model lebih baik ditolak di awal daripada gagal saat operasional |
| D14 | Nama kamera = `label` yang bisa diedit; `id` tetap stabil | Label ramah pengguna tidak boleh memutus konfigurasi meja dan riwayat |
| D15 | OEM autostart, battery optimization, dan kiosk adalah keputusan perangkat operasional | Tablet warung harus pulih setelah reboot atau pemadaman listrik |

---

## 1. Definisi "ready to use"

Aplikasi dianggap siap pakai ketika **semua** poin berikut benar di perangkat fisik:

1. Instal APK, buka app, diagnostik perangkat lulus (atau pengguna menyetujui mode
   hemat), membuat PIN, lalu masuk. Setelah proses mati, app meminta PIN lagi (tidak
   ada sesi abadi).
2. Dari halaman utama, tombol **Scan CCTV** menemukan kamera di Wi-Fi yang sama **atau**
   pengguna bisa memasukkan alamat manual dan berhasil terhubung.
3. Stream tampil < 3 detik setelah kamera dipilih (LAN, sub-stream), dengan indikator
   status koneksi yang jujur.
4. Di bawah stream tampil **total meja kosong** dan **total meja terisi**, plus jumlah
   `unknown` bila ada, dan waktu pembaruan terakhir.
5. Rotasi portrait ↔ landscape **tidak** memutus stream dan tidak mengulang handshake.
6. Kamera dicabut dari jaringan → dalam ≤ 5 detik status berubah menjadi tidak terpantau
   dengan pesan yang bisa ditindaklanjuti, bukan angka "kosong" palsu.
7. Semua error yang mungkin muncul punya pesan Bahasa Indonesia + satu aksi
   ("Coba lagi", "Scan ulang", "Buka pengaturan jaringan").
8. Tidak ada kredensial (URL RTSP, password, token) di log, screenshot, atau crash report.
9. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` hijau.
10. Berjalan 8 jam dengan layar mati tanpa kebocoran memori yang terlihat (RSS stabil
    dalam ±10%) dan tanpa crash.
11. Setelah reboot atau pemadaman listrik, aplikasi kembali ke status yang jujur:
    meminta PIN atau menyalakan monitoring otomatis bila opsi itu dipilih.

Yang **bukan** syarat ready-to-use tahap ini: dashboard web, sinkronisasi VPS,
multi-kamera, dan deteksi kursi terpisah.

---

## 2. Alur produk

### 2.1 Diagram alur

```text
Buka aplikasi
   │
   ├─ belum pernah setup ──► Login: "Buat PIN" ──► tawarkan biometrik ──┐
   │                                                                    │
   ├─ sudah setup ─────────► Login: PIN / biometrik ──(5x salah)──► tunggu│
   │                                                                    │
   ▼                                                                    ▼
 Halaman utama (Home) ◄──────────────────────────────────────────────────┘
   │
   ├─ belum ada kamera tersimpan
   │     └─► [Scan CCTV] ─► ScanSheet ─► pilih kamera ─► ConnectSheet ─► tes 4 lapis
   │                                                                        │
   ├─ sudah ada kamera tersimpan                                            │
   │     └─► [Scan CCTV] ─► langsung connect (tombol "Ganti kamera" di menu)│
   │                                                                        │
   ▼                                                                        ▼
 Home · state Live: stream + total kosong/terisi/tidak diketahui ◄──────────┘
```

### 2.2 Layar dan route

| Route / overlay | Jenis | Isi | Punya app bar? |
| --- | --- | --- | --- |
| `login` | layar penuh | PIN / biometrik / buat PIN | tidak |
| `home` | layar penuh | stream, ringkasan meja, CTA Scan CCTV | tidak (immersive) |
| `tables` | layar penuh | kalibrasi poligon meja (sekali setup / ubah) | ya, dengan back |
| `settings` | layar penuh | kamera, jaringan, keamanan, tentang | ya, dengan back |
| `scan` | overlay (bottom sheet / dialog) | progres discovery + daftar kamera | tidak |
| `connect` | overlay | form alamat + kredensial + tes koneksi | tidak |

Alasan `scan` dan `connect` bukan route: keduanya hidup di atas `home`, berbagi
`HomeViewModel`, dan tidak boleh memicu re-create stream. Di layar lebar keduanya
dirender sebagai dialog di tengah, di layar sempit sebagai bottom sheet.

### 2.3 State mesin tingkat atas

```text
AppState = Boot
         | DeviceCheck        (diagnostik belum selesai)
         | Unsupported        (perangkat tidak bisa menjalankan engine)
         | NeedsSetup          (belum ada PIN)
         | Locked              (sudah ada PIN, menunggu unlock)
         | Unlocked            (sesi aktif)
         | LockedOut(until)     (terlalu banyak percobaan)
```

Aturan:

- `Unlocked` **tidak** dipersistensi. Proses mati atau app di-background > 5 menit →
  kembali ke `Locked`.
- `LockedOut` memakai waktu monotonik + `elapsedRealtime` agar tidak bisa diakali dengan
  mengubah jam perangkat.
- Auto-lock default 5 menit sejak app ke background; dapat diubah di Pengaturan
  (1/5/15 menit, atau "langsung").
- `DeviceCheck` berjalan sebelum layar PIN. Hasil `warning` tetap boleh lanjut dengan
  profil `lite`/`balanced`; `unsupported` tidak membuka Home, service, atau RTSP.
  Detail pemeriksaan ada di [`device-compatibility.md`](device-compatibility.md).

### 2.4 Wireframe portrait (HP, lebar < 600dp)

**Idle — belum ada kamera / kamera belum terhubung**

```text
┌──────────────────────────────────────┐
│                                  ⋮   │  overflow: Kelola meja · Pengaturan · Keluar
│                                      │
│              ┌────────┐              │
│              │ [cam]  │              │  ilustrasi vektor, 96dp
│              └────────┘              │
│                                      │
│        Belum ada kamera              │  headlineSmall
│   Sambungkan CCTV warung untuk       │  bodyMedium, muted
│   melihat status meja otomatis.      │
│                                      │
│  ┌────────────────────────────────┐  │
│  │        Scan CCTV               │  │  tombol utama, tinggi 56dp
│  └────────────────────────────────┘  │
│        Masukkan alamat manual        │  text button
│                                      │
│  ── panduan singkat ───────────────  │
│  • HP dan kamera di Wi-Fi yang sama  │
│  • Kamera memakai IP tetap           │
│                                      │
└──────────────────────────────────────┘
```

**Live — stream aktif**

```text
┌──────────────────────────────────────┐
│                                  ⋮   │
│  ┌────────────────────────────────┐  │
│  │ ● LIVE            25 fps   au  │  │  overlay chip di atas video
│  │                                │  │
│  │           (video 16:9)         │  │
│  │                                │  │
│  └────────────────────────────────┘  │  radius 16dp, 8dp margin
│  ● Terhubung · diperbarui 10:42:07   │  caption
│                                      │
│  ┌───────────────┐ ┌───────────────┐ │
│  │      12       │ │       3       │ │  angka 40sp, tabular
│  │    Kosong     │ │    Terisi     │ │  label bodyMedium
│  └───────────────┘ └───────────────┘ │
│  1 meja tidak terpantau · 10:42:07   │  hanya bila unknown > 0
│                                      │
│  ┌────────────────────────────────┐  │
│  │      Scan CCTV                 │  │  secondary (tonal)
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

**Error — kamera tidak terjangkau**

```text
┌──────────────────────────────────────┐
│                                  ⋮   │
│  ┌────────────────────────────────┐  │
│  │            [!]                 │  │
│  │  Kamera tidak terjangkau       │  │
│  │  Pastikan HP dan CCTV ada di   │  │
│  │  Wi-Fi yang sama.              │  │
│  │                                │  │
│  │  [ Coba lagi ]  [ Scan ulang ] │  │
│  └────────────────────────────────┘  │
│  Kosong  —      Terisi  —            │  angka jadi "—", bukan 0
│  Status meja tidak diketahui         │
└──────────────────────────────────────┘
```

### 2.5 Wireframe landscape (lebar ≥ 600dp)

```text
┌────────────────────────────────────────────────────────────────────────┐
│ ┌────────────────────────────────┐  │  ● LIVE            25 fps   au   │
│ │                                │  │  Kamera: Warung Depan            │
│ │                                │  │  ● Terhubung · 10:42:07          │
│ │         (video 16:9)           │  │                                  │
│ │                                │  │  ┌───────────┐ ┌───────────┐     │
│ │                                │  │  │    12     │ │     3     │     │
│ └────────────────────────────────┘  │  │  Kosong   │ │  Terisi   │     │
│                                     │  └───────────┘ └───────────┘     │
│                                     │  1 meja tidak terpantau          │
│                                     │                                  │
│                                     │  ┌────────────────────────────┐  │
│                                     │  │       Scan CCTV            │  │
│                                     │  └────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
      panel video (weight 1.6)              panel status (weight 1.0, maks 420dp)
```

Aturan panel:

- Panel status **selalu** bisa di-scroll; di tinggi < 400dp tombol Scan tetap terlihat
  karena berada di luar area scroll (sticky di bawah).
- Video memakai `ContentScale.Fit` di dalam kotak `aspectRatio(16f/9f)`; letterbox
  berwarna hitam, bukan putih, agar tidak menyilaukan.
- Tidak ada elemen yang bergantung pada orientasi secara langsung; hanya lebar.
---

## 3. Login

### 3.1 Keputusan: PIN lokal + biometrik opsional

Dashboard VPS belum ada dan sinkronisasi masih opsional (Fase 6). Karena itu login
**tidak** boleh bergantung pada jaringan. Yang dilindungi adalah akses ke aplikasi
(orang lain memegang HP tidak boleh melihat stream/kalibrasi), bukan akun cloud.

| Aspek | Keputusan |
| --- | --- |
| Faktor utama | PIN 6 digit |
| Faktor tambahan | Biometrik (`BiometricPrompt`, `BIOMETRIC_STRONG` atau `DEVICE_CREDENTIAL`) |
| Penyimpanan | Salt 16 byte + hash PBKDF2-HMAC-SHA256, 210.000 iterasi, disimpan di DataStore |
| Verifikasi | `MessageDigest.isEqual` (konstan waktu), bukan `==` pada `String` |
| Lockout | 5 gagal → 30 detik; 10 gagal → 5 menit; 15 gagal → 30 menit; reset saat sukses |
| Sesi | Tidak dipersistensi; auto-lock 5 menit di background |
| Ganti PIN | Wajib PIN lama, lalu PIN baru diketik dua kali |
| Lupa PIN | "Reset aplikasi" → hapus DataStore + Room + kredensial Keystore, konfirmasi ketik `HAPUS` |

Catatan keamanan:

- PIN 6 digit hanya melindungi dari akses fisik biasa, bukan dari perangkat yang di-root.
  Ini batas yang diterima; yang benar-benar rahasia (password kamera) tetap di Keystore.
- Jangan menyimpan PIN di `SavedStateHandle`, `rememberSaveable`, atau log.
- `FLAG_SECURE` aktif di semua layar agar screenshot/task-switcher tidak membocorkan
  stream atau PIN.

### 3.2 Wireframe login

```text
┌──────────────────────────────────────┐
│                                      │
│              ┌────────┐              │
│              │  CCTV  │              │  logo/ikon aplikasi
│              └────────┘              │
│            Warung Monitor            │  titleLarge
│                                      │
│          Masukkan PIN                │  bodyMedium, muted
│                                      │
│           ● ● ● ○ ○ ○                │  6 dot, 24dp, spacing 16dp
│                                      │
│        ┌────┐┌────┐┌────┐            │  keypad 3x4, tombol 72dp
│        │ 1  ││ 2  ││ 3  │            │
│        └────┘└────┘└────┘            │
│        ┌────┐┌────┐┌────┐            │
│        │ 4  ││ 5  ││ 6  │            │
│        └────┘└────┘└────┘            │
│        ┌────┐┌────┐┌────┐            │
│        │ 7  ││ 8  ││ 9  │            │
│        └────┘└────┘└────┘            │
│        ┌────┐┌────┐┌────┐            │
│        │ B  ││ 0  ││ ⌫  │            │  B = biometrik (bila aktif)
│        └────┘└────┘└────┘            │
│                                      │
│   PIN salah. Sisa 3 percobaan.       │  errorText, hanya setelah gagal
└──────────────────────────────────────┘
```

Aturan interaksi:

- Keypad adalah `Button` Compose biasa (bukan `TextField`), sehingga tidak memunculkan
  keyboard sistem dan tidak bisa di-autofill.
- Setiap digit memberi haptic `HapticFeedbackType.TextHandleMove`; tombol hapus juga, dan keypad nonaktif tidak memberi haptic (`PinKeypadHapticTest`).
- Setelah 6 digit, verifikasi otomatis (tanpa tombol "Masuk").
- Saat `LockedOut`, keypad disabled + hitung mundur `mm:ss` di atas keypad.
- Biometrik dicoba otomatis sekali per kemunculan layar login, setelah state `EnterPin` siap; gagal atau dibatalkan -> fallback PIN tanpa mengurangi percobaan.

### 3.3 State dan kontrak

```kotlin
sealed interface LoginUiState {
    data object EnterPin : LoginUiState
    data class Verifying(val filled: Int) : LoginUiState
    data class WrongPin(val attemptsLeft: Int, val message: String?) : LoginUiState
    data class LockedOut(val remainingMs: Long) : LoginUiState
    data class CreatePin(val step: CreateStep, val firstPin: String?) : LoginUiState
    data object BiometricPrompt : LoginUiState
}
```

Test wajib (JVM, tanpa emulator): `LoginViewModelTest`

| Nama test | Perilaku |
| --- | --- |
| `six_digits_trigger_verification` | verifikasi dipanggil tepat sekali setelah digit ke-6 |
| `wrong_pin_decrements_attempts` | `attemptsLeft` turun dan pesan muncul |
| `five_failures_lock_for_30_seconds` | state menjadi `LockedOut(30_000)` |
| `lockout_uses_elapsed_realtime` | memajukan jam dinding tidak membuka lockout |
| `successful_unlock_resets_attempts` | counter kembali 0 dan sesi `Unlocked` |
| `pin_never_appears_in_state_or_log` | semua `toString()` state bebas PIN |
| `create_pin_requires_matching_confirmation` | dua input beda → error, tidak tersimpan |
| `reset_requires_typed_confirmation` | teks selain `HAPUS` tidak menghapus apa pun |

---

## 4. Halaman utama (Home)

### 4.1 Anatomi

| Bagian | Portrait | Landscape |
| --- | --- | --- |
| App bar | Tidak ada; overflow `⋮` di kanan atas di atas video | Sama |
| Video | Kartu 16:9, full width - 16dp | Kolom kiri, mengisi tinggi |
| Status koneksi | Caption di bawah video | Di atas angka, kolom kanan |
| Kartu angka | 2 kartu berdampingan | 2 kartu bertumpuk vertikal |
| Catatan `unknown` | Di bawah kartu | Di bawah kartu |
| CTA Scan | Tombol lebar penuh | Tombol lebar penuh di bawah panel kanan |

### 4.2 Kartu angka

```text
┌───────────────────┐
│        12         │   displayMedium (40sp), tabular figures
│      Kosong       │   bodyMedium
│   dari 15 meja    │   bodySmall, muted (opsional)
└───────────────────┘
```

Aturan:

- Angka memakai `FontFeature("tnum")` agar tidak "bergetar" saat berubah.
- Animasi perubahan angka: crossfade 180 ms; tanpa animasi hitung naik/turun.
- Warna tidak boleh menjadi satu-satunya pembeda: kartu memakai ikon + label teks.
- Bila `unknown > 0`, kartu tetap menampilkan angka, tetapi ada baris peringatan
  "N meja tidak terpantau" dan warna outline berubah ke `warning`.
- Bila stream belum pernah terhubung, angka adalah `—` (bukan `0`).

### 4.3 State Home

```kotlin
data class HomeUiState(
    val camera: CameraSummary?,          // null = belum ada kamera tersimpan
    val stream: StreamUiState,           // Idle | Connecting | Live | Reconnecting | Failed
    val summary: TableSummary?,          // null bila belum ada data
    val lastUpdated: Instant?,
    val overlay: HomeOverlay?,           // ScanSheet | ConnectSheet | null
    val banner: BannerMessage?,          // pesan sekali tampil (mis. "kamera terputus")
    val mapping: TableMapState,          // Unknown | Missing | Mapped(tables, revision)
)

// Peta tersimpan dibaca dari Room, bukan dari status engine.
sealed interface TableMapState {
    data object Unknown : TableMapState   // belum ada sumber / belum terbaca: jangan blokir
    data object Missing : TableMapState   // kamera ini belum punya poligon di Room
    data class Mapped(val tables: Int, val revision: Long) : TableMapState
}

data class TableSummary(
    val available: Int,
    val occupied: Int,
    val unknown: Int,
) {
    val total: Int get() = available + occupied + unknown
}

sealed interface StreamUiState {
    data object Idle : StreamUiState
    data object Connecting : StreamUiState
    data class Live(val fps: Int, val since: Instant) : StreamUiState
    data class Reconnecting(val attempt: Int, val nextRetryInMs: Long) : StreamUiState
    data class Failed(val kind: StreamErrorKind, val retryable: Boolean) : StreamUiState
}
```

### 4.4 Aturan tampilan angka

| Kondisi stream | Kosong | Terisi | Catatan `unknown` |
| --- | --- | --- | --- |
| Belum pernah terhubung | `—` | `—` | "Status meja belum tersedia" |
| `Connecting` pertama kali | `—` | `—` | "Menghubungkan ke kamera…" |
| `Live`, semua meja diketahui | angka | angka | tidak tampil |
| `Live`, ada meja `unknown` | angka | angka | "N meja tidak terpantau" |
| `Reconnecting` dengan data terakhir | angka terakhir + label "data terakhir" | sama | "N meja tidak terpantau" |
| `Failed` | `—` atau angka terakhir + label | sama | pesan error + aksi |

Prinsip: **jangan pernah mengubah `unknown` menjadi `available`**. Lebih baik tampil `—`
daripada angka yang menyesatkan.

### 4.5 Analisa hanya setelah peta meja ada

Urutan di layar (Compact, lebar < 600dp): video → baris toggle analisa → panel analisa.
Analisa selalu berada langsung di bawah video karena panel itu menjelaskan frame yang
sedang dilihat pengguna.

| `mapping` | Baris toggle | Area analisa |
| --- | --- | --- |
| `Unknown` | normal (fail-open) | panel seperti biasa |
| `Mapped(tables > 0, revision)` | normal, bisa dinyalakan | panel tabel + kotak orang |
| `Missing` | nonaktif, label "Analisa menunggu peta meja" | kartu "Peta meja belum ada" + tombol **Gambar peta meja** ke kelola kamera/meja |

Aturan:

- Sumber `mapping` adalah `feature/tables` (Room), bukan `EngineStatus`. Engine yang
  berhenti tidak boleh menjawab "belum ada peta", dan stream yang hidup tanpa poligon
  tidak boleh terlihat seperti analisa kosong.
- Revisi tersimpan dengan 0 poligon dihitung `Missing`.
- Gagal baca / corrupt dilaporkan `Unknown`, bukan `Missing`: pengguna tidak boleh
  diminta menggambar ulang peta yang sebenarnya ada.
- `Missing` tidak menghapus kamera, tidak menutup stream, dan tidak mengubah
  `analysisEnabled`; toggle hanya dinonaktifkan sampai peta tersimpan.

---

## 5. Alur Scan CCTV

### 5.1 Empat lapis pencarian

```text
[Scan CCTV] ─► 1. WS-Discovery (ONVIF, multicast 239.255.255.250:3702)   ~3 s
            ─► 2. Probe subnet lokal (TCP 554 / 8554)                     ~4 s
            ─► 3. QR / barcode kamera (kartu di dus kamera)               manual
            ─► 4. Input manual (rtsp://user:pass@ip:554/path)             manual
```

Lapis 1 dan 2 berjalan **paralel**, hasil digabung dan dideduplikasi berdasarkan
`ip:port`. Total anggaran waktu scan: 8 detik; setelah itu daftar yang ada tetap
ditampilkan dengan tombol "Scan ulang".

Alasan tidak hanya ONVIF: banyak kamera konsumen (termasuk EZVIZ) mematikan WS-Discovery
atau memakai kredensial berbeda untuk ONVIF vs RTSP. Probe TCP 554 menemukan kamera yang
"ada" walau tidak mau menjawab discovery.

### 5.2 Yang dilakukan dan tidak dilakukan

| Dilakukan | Tidak dilakukan |
| --- | --- |
| Membaca SSID saat ini (butuh izin lokasi pada Android 10+) | Menyimpan SSID ke server |
| Mengambil prefix /24 dari `LinkProperties` | Memindai seluruh `10.0.0.0/8` |
| TCP connect ke port 554, 8554 (timeout 400 ms) | Mencoba password default (brute force) |
| WS-Discovery `Probe` | Port scanning lebih dari 512 host |
| Menampilkan IP + port + merek bila terdeteksi | Menampilkan URL lengkap dengan kredensial |

Batas keamanan: probe subnet hanya dijalankan bila transport aktif adalah Wi-Fi dan
alamatnya privat. Pada data seluler, scan langsung ditolak dengan pesan
"Scan hanya bisa saat tersambung ke Wi-Fi" (lihat `network-topology.md` §7).

### 5.3 Algoritma probe subnet

```text
hosts = /24 dari ipv4 lokal, minus network/broadcast/self       (maks 254)
ports = [554, 8554]
concurrency = 32 (Semaphore), timeout per host = 400 ms
hasil = hosts yang salah satu port-nya menerima TCP handshake
total waktu maksimum = ceil(254/32) * 400ms ≈ 3.2 s (paralel per host)
```

Detail implementasi:

- Pakai `kotlinx.coroutines` + `Dispatchers.IO`, **bukan** `Nmap`/binary eksternal.
- `Socket().connect(InetSocketAddress(host, port), 400)` di dalam `withTimeoutOrNull`.
- Batalkan seluruh scan saat sheet ditutup (`scanJob.cancel()`).
- Untuk kamera yang menjawab, lakukan satu `DESCRIBE` tanpa kredensial untuk membaca
  `Server`/`WWW-Authenticate` (menentukan merek dan skema auth). Bila 401, kamera tetap
  ditampilkan sebagai "Butuh login".
- Hasil di-cache 10 menit di memori (bukan disk) agar "Scan ulang" terasa instan.

### 5.4 Ranking hasil

Urutan tampil: (1) kamera yang pernah dipakai, (2) yang menjawab ONVIF, (3) yang
port 554 terbuka, (4) sisanya. Merek hanya ditampilkan bila dikenali dari header
`Server`; selain itu tampil "Kamera RTSP".

### 5.5 Wireframe ScanSheet

```text
┌──────────────────────────────────────┐
│  Scan CCTV                       ✕   │
│  Mencari kamera di Wi-Fi "Warung"…   │
│  ▓▓▓▓▓▓▓▓░░░░░░░░░░  3/4 detik       │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ [] 192.168.1.64 : 554      ●   │  │  ● = butuh login
│  │    EZVIZ · RTSP                │  │
│  └────────────────────────────────┘  │
│  ┌────────────────────────────────┐  │
│  │ [] 192.168.1.72 : 554          │  │
│  │    Kamera RTSP                 │  │
│  └────────────────────────────────┘  │
│                                      │
│  Tidak menemukan kamera?             │
│  [ Masukkan alamat manual ]  [ QR ]  │
└──────────────────────────────────────┘
```

Status implementasi (2026-11-26): lapis 1 dan 2 sudah berjalan.

- `core:network` (JVM murni, 43 test) berisi `SubnetScan`, `CameraPortScanner`,
  parser `WsDiscovery`, `DiscoveryMerge`, dan `CameraDiscovery`.
- Kedua lapis berjalan paralel dengan deadline sendiri (WS 3 s, probe 8 s), jadi lapis yang
  menggantung tidak membuang hasil lapis lain.
- Hasil di-cache 10 menit di memori; "Scan ulang" di dalam jendela itu menampilkan hasil
  cache dengan label "Hasil scan 10 menit terakhir".
- Scan ditolak sebelum probe bila transport aktif bukan Wi-Fi, atau bila alamatnya bukan
  privat.
- `CHANGE_WIFI_MULTICAST_STATE` diminta, dan `MulticastLock` hanya dipegang selama satu
  panggilan discovery lalu dilepas di `finally`.
- Baris hasil yang endpoint-nya sudah tersimpan membuka profil itu; endpoint baru diarahkan
  ke kelola kamera karena `CameraId` hanya dibuat di sana.

Aturan:

- Satu baris = satu kamera; klik langsung membuka `ConnectSheet` dengan IP terisi.
- Kamera tanpa nama ditampilkan dengan IP:port saja (tanpa tebakan merek).
- Bila nol hasil setelah 8 detik: pesan jelas + saran (cek kamera menyala di Wi-Fi yang
  sama, scan ulang, tambah manual), bukan spinner tanpa akhir.
- Bila tidak ada Wi-Fi: sheet tetap dibuka dan menampilkan panel "Sambungkan ke Wi-Fi dulu"
  dengan tombol "Coba lagi" dan "Tambah manual". Ini menyimpang dari rancangan awal (dialog
  terpisah) karena sheet sudah memegang konteks scan; tidak ada probe yang dijalankan.

### 5.6 ConnectSheet

```text
┌──────────────────────────────────────┐
│  Hubungkan kamera                ✕   │
│                                      │
│  Alamat                              │
│  ┌────────────────────────────────┐  │
│  │ rtsp://192.168.1.64:554/...    │  │  boleh tanpa kredensial
│  └────────────────────────────────┘  │
│                                      │
│  Nama pengguna        Kata sandi     │
│  ┌────────────────┐  ┌────────────┐  │
│  │ admin          │  │ ••••••••   │  │  👁 toggle
│  └────────────────┘  └────────────┘  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │      Tes & hubungkan           │  │
│  └────────────────────────────────┘  │
│                                      │
│  ● Wi-Fi lokal          ✓            │  4 baris hasil tes
│  ● Kamera terdeteksi    ✓            │
│  ● Kredensial           …            │
│  ● Stream H.264         …            │
└──────────────────────────────────────┘
```

### 5.7 Tes koneksi empat lapis

| Lapis | Aksi | Gagal berarti | Pesan UI |
| --- | --- | --- | --- |
| 1. Jaringan | `NetworkCapabilities` punya `TRANSPORT_WIFI` + alamat privat | `no_network` / `wrong_network` | "Sambungkan HP ke Wi-Fi warung" |
| 2. Reachability | TCP connect ke `host:port` (1.5 s) | `unreachable` | "Kamera tidak terjangkau di jaringan ini" |
| 3. Kredensial + DESCRIBE | RTSP `DESCRIBE` dengan auth (3 s) | `auth_failed` / `protocol` | "Kata sandi kamera salah" / "Format stream tidak didukung" |
| 4. Stream pertama | minta 1 keyframe (5 s) | `timeout` | "Kamera menjawab tetapi tidak mengirim gambar" |

Aturan:

- Lapis 1–2 dijalankan sebelum menyentuh Rust/Media3, sehingga pesan "beda jaringan"
  tidak pernah menyamar sebagai "password salah".
- `auth_failed` **tidak** di-retry otomatis (risiko akun terkunci, `network-topology.md`
  §7.3). Pengguna harus menekan tombol lagi.
- Tes memakai kredensial yang diketik, tetapi hasil yang disimpan tidak pernah berisi
  password di log.

### 5.8 Penyimpanan kamera

```kotlin
@Serializable
data class CameraProfile(
    val id: String,              // UUID, stabil
    val label: String,           // "Warung Depan"
    val host: String,            // tanpa kredensial
    val port: Int,
    val path: String,
    val username: String?,
    val credentialAlias: String, // kunci di Android Keystore
    val transport: RtspTransport,
    val subStream: Boolean,      // default true
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
```

- JSON profil disimpan di DataStore **tanpa** password.
- Password disimpan via `EncryptedSharedPreferences`/Keystore dengan alias
  `rtsp_cred_<cameraId>`; dihapus saat kamera dihapus.
- `label` bisa diubah; `id` tidak pernah berubah agar konfigurasi meja tetap terikat.
---

## 6. Arsitektur Kotlin

### 6.1 Modul Gradle

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # version catalog, satu sumber versi
├── gradle.properties
├── app/                               # application module (Compose, DI)
├── core/model/                        # data class murni, tanpa Android
├── core/network/                      # NetworkMonitor, subnet probe (JVM-testable)
├── core/ui/                           # theme, komponen bersama, format
└── feature/
    ├── auth/                          # Login
    ├── home/                          # Home + Scan + Connect
    ├── tables/                        # kalibrasi poligon
    └── settings/                      # pengaturan
```

Alasan modularisasi: `feature/*` tidak saling bergantung, sehingga layar bisa dikompilasi
dan diuji sendiri; `core/model` bebas `android.*` sehingga seluruh mapper diuji di JVM.

### 6.2 Lapisan

```text
ui/ (Compose)  ─►  domain/ (ViewModel, use case, mapper)  ─►  data/ (repo, Room, DataStore, Ktor)
                                        │
                                        └─► engine/ (FFI gateway atau Media3 player)
```

Aturan tegas (melanjutkan `tdd-plan.md` §2):

- `domain/` tidak boleh `import android.*`.
- ViewModel tidak menyentuh `Context`, `Intent`, atau `Media3`.
- Semua I/O dibungkus `Repository` + `Flow`, tidak ada callback yang bocor ke UI.
- `Dispatchers` diinjeksi lewat `AppDispatchers` agar test deterministik.

### 6.3 Pohon paket `app`

```text
app/src/main/kotlin/id/warung/monitor/
├── MonitorApp.kt                  # Application, DI root, Crashlytics init
├── MainActivity.kt                # single activity, edge-to-edge, FLAG_SECURE
├── navigation/
│   ├── AppNavHost.kt              # route: login, home, tables, settings
│   └── AppRoutes.kt               # konstanta route, argumen typed
├── engine/
│   ├── StreamEngineGateway.kt     # interface
│   ├── RustStreamEngine.kt        # impl UniFFI (Fase K7)
│   ├── Media3StreamPlayer.kt      # impl MVP (read-only preview)
│   └── FakeStreamEngineGateway.kt # demo & test
├── service/
│   ├── StreamService.kt           # foreground service
│   ├── StreamNotification.kt
│   └── BootReceiver.kt
└── di/
    ├── AppModule.kt
    ├── DataModule.kt
    └── EngineModule.kt            # memilih Rust vs Media3 vs Fake dari BuildConfig
```

### 6.4 Kontrak gateway (inti stabilitas)

```kotlin
interface StreamEngineGateway {
    val state: StateFlow<EngineState>       // Connecting | Live | Reconnecting | Failed
    val tables: StateFlow<TableSnapshot>    // ringkasan + per-meja
    val metrics: StateFlow<EngineMetrics>

    suspend fun connect(profile: CameraProfile, secret: String)
    suspend fun disconnect()
    suspend fun setTables(config: TableConfigSet)
    suspend fun skipRecalibration()
}

data class TableSnapshot(
    val tables: Map<String, TableStatus>,
    val updatedAt: Instant,
) {
    val available: Int get() = tables.values.count { it == TableStatus.Available }
    val occupied: Int get() = tables.values.count { it == TableStatus.Occupied }
    val unknown: Int get() = tables.values.count { it == TableStatus.Unknown }
}
```

Kenapa interface ini penting:

- `HomeViewModel` **tidak tahu** apakah stream berasal dari Rust atau Media3.
- Migrasi MVP → final (Fase K7) hanya mengganti binding DI, bukan ViewModel/UI.
- `FakeStreamEngineGateway` memungkinkan pembangunan seluruh UI sebelum `core-ffi` ada.

### 6.5 Skenario demo (FakeStreamEngineGateway)

Mode demo dipilih lewat `BuildConfig.ENGINE_MODE = demo|media3|rust`:

- Memutar pola warna + teks "DEMO" (bukan video iStock, agar tidak ada masalah lisensi).
- Mengacak status meja setiap 2–6 detik dengan 8 meja.
- Sesekali mensimulasikan `Reconnecting` 3 detik untuk menguji banner.
- Tidak butuh kamera, jaringan, atau `libcore_ffi.so`; berguna untuk screenshot test.

---

## 7. Sumber video: keputusan belum final

### 7.1 Masalah

`stream-engine` sudah mengembalikan access unit **terenkode** (Annex B) dan sengaja tidak
melakukan decode. `core-ffi` saat ini menerima frame RGB lewat `push_frame`, sehingga
belum ada jembatan dari `stream-engine` ke preview maupun inferensi. Untuk menampilkan
gambar di layar ada dua arah yang mungkin:

| Opsi | Cara | Kelebihan | Kekurangan |
| --- | --- | --- | --- |
| A. Media3 (`media3-exoplayer-rtsp`) | Media3 membuka RTSP sendiri | Cepat diimplementasikan, kontrol playback matang | **Dua koneksi RTSP** (Media3 + Rust) = dua kali decode di kamera dan jaringan |
| B. Surface dari Rust | Rust decode H.264 → `Surface` via `ANativeWindow` | Satu koneksi, satu decode; FFI tetap sumber kebenaran | Perlu `ai-engine`/decoder + jembatan Surface; belum ada |

### 7.2 Urutan target (belum diputuskan)

| Fase | Preview | Deteksi | Catatan |
| --- | --- | --- | --- |
| K4 (MVP UI) | Media3 | Fake / belum ada | Fokus membangun UI + scan + state |
| K5 (integrasi) | Media3 | Rust (`setTables` + event) | Dua koneksi; hanya untuk uji internal, **bukan** rilis |
| K7 (kandidat) | Surface Rust | Rust | Bisa satu koneksi, tetapi decoder dan bridge belum ada |

Timeline ini adalah **usulan**. Sebelum K5, ADR 0009 harus **diterima**, bukan sekadar
dicatat; saat ini statusnya masih Diusulkan. Alternatif yang sah:
Media3 tetap menjadi satu-satunya decoder, lalu frame RGB diteruskan ke Rust dengan
anggaran copy yang terukur.

Aturan pengamanan selama fase transisi:

- `BuildConfig.ALLOW_DUAL_RTSP = false` pada build `release`. Build debug boleh `true`
  dengan banner merah "Mode uji: dua koneksi RTSP".
- Jangan pernah mengaktifkan dua koneksi pada kamera yang sama di build yang dibagikan
  ke pemilik warung: beberapa kamera membatasi jumlah sesi dan akan menolak koneksi kedua.
- Bila kamera menolak koneksi kedua (`453 Not Enough Bandwidth` atau 401 berulang),
  UI harus memberi pesan "Kamera membatasi sesi; tutup aplikasi lain" — bukan retry.

### 7.3 Konfigurasi Media3 yang dipakai

```kotlin
val mediaSource = RtspMediaSource.Factory()
    .setForceUseRtpTcp(true)          // tembus AP isolation/NAT
    .setTimeoutMs(5_000)
    .createMediaSource(MediaItem.fromUri(redactedUri))

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(RtspMediaSource.Factory())
    .setLoadControl(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs = 1_000,
                maxBufferMs = 5_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 500,
            )
            .build()
    )
    .build()
```

Catatan penting:

- Media3 butuh `INTERNET`, dan untuk RTSP TCP tidak butuh permission lain.
- `setForceUseRtpTcp(true)` wajib: default UDP sering diblokir AP isolation.
- URL dengan kredensial **tidak** boleh masuk ke `MediaItem` yang di-log; gunakan
  `redactedUri` untuk log dan URI asli hanya di memori.
- Audio dimatikan (`player.volume = 0f`) karena CCTV warung tidak butuh audio dan
  menghemat bandwidth.

### 7.4 Batas yang harus dihormati

- Media3 **tidak** memberi frame ke Rust secara otomatis. Jika dipilih, frame harus
  diekstrak, dikonversi ke RGB888, dan disalin ke engine; biaya ini wajib diukur.
- Selama bridge belum ada, mode `rust` tidak boleh menampilkan layar hitam tanpa
  penjelasan.
- Jangan menyebut "single connection" sampai ada satu implementasi yang benar-benar
  membuka kamera sekali dan melayani preview + inferensi.

---

## 8. Adaptif: portrait & landscape

### 8.1 Prinsip

Layout ditentukan **lebar jendela** (`WindowSizeClass`), bukan `Configuration.orientation`.
Tablet portrait (800dp) mendapat layout dua panel; HP landscape (720×360dp) juga.

| Kelas lebar | Layout |
| --- | --- |
| `< 600dp` (Compact) | Satu kolom: video di atas, kartu angka di bawah |
| `600–840dp` (Medium) | Dua panel: video kiri, status kanan (maks 420dp) |
| `>= 840dp` (Expanded) | Dua panel dengan margin lebih besar; video maks 960dp |

### 8.2 Yang wajib bertahan saat rotasi

| Hal | Aturan |
| --- | --- |
| Sesi RTSP | Tetap; `player` dimiliki `ViewModel`/service, bukan composable |
| Posisi playback | Tetap (`player.currentPosition`) |
| Overlay scan | Tetap terbuka (`HomeUiState.overlay` di ViewModel) |
| Input form connect | Tetap (`SavedStateHandle` untuk teks non-rahasia saja) |
| Password | **Tidak** disimpan di `SavedStateHandle`; diketik ulang bila rotasi |
| Status meja | Tetap; berasal dari `StateFlow` engine |
| Dialog | Tidak tertutup otomatis |

Implementasi: `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`
**tidak** dipakai. Sebagai gantinya:

- `MainActivity` tetap re-create normal (agar resource & window metrics benar).
- Player + engine hidup di `ViewModel` ber-`SavedStateHandle`/`Service`, bukan di
  composable, sehingga re-create tidak memutus stream.
- `rememberSaveable` hanya untuk state UI ringan (posisi scroll, tab).

Deviasi sementara: `MainActivity` saat ini masih memakai `configChanges` (termasuk
`density`). Ini **belum** sesuai rencana dan harus dihapus saat player/service
lifecycle sudah stabil; jangan menandai checklist rotasi selesai hanya karena atribut
itu ada.

### 8.3 Edge-to-edge dan inset

- `enableEdgeToEdge()` di `MainActivity`.
- Video **tidak** masuk ke area cutout: `WindowInsets.displayCutout` dijadikan padding.
- Di landscape, navigasi bar gestural di kanan tidak menutupi tombol Scan: terapkan
  `WindowInsets.navigationBars` sebagai padding panel kanan.
- Status bar transparan dengan ikon terang di atas video.

### 8.4 Perilaku layar

| Kejadian | Perilaku |
| --- | --- |
| Layar mati | Service tetap hidup; preview berhenti (hemat daya); deteksi lanjut 2 FPS |
| App ke background | Setelah 5 menit → auto-lock |
| Rotasi | Re-create activity; stream tidak terputus |
| Multi-window / split screen | Layout mengikuti lebar aktual (Compact/Medium) |
| Foldable unfold | Layout berpindah ke dua panel tanpa restart stream |

---

## 9. Notifikasi dan foreground service

| Aspek | Keputusan |
| --- | --- |
| Jenis service | `foregroundServiceType="mediaPlayback"` (+ `dataSync` saat sinkronisasi aktif) |
| Notifikasi | Kanal `stream` (low), aksi "Hentikan" dan "Buka" |
| Isi notifikasi | "Memantau CCTV warung", jumlah meja kosong/terisi — **tanpa** nama file/frame |
| Izin | `POST_NOTIFICATIONS` (Android 13+) diminta saat pertama start, bukan saat login |
| Boot | `BootReceiver` hanya menyalakan service bila pengguna menyalakan "Mulai otomatis" |
| Doze | Foreground service saja belum cukup; kebijakan `WakeLock`/`WifiLock` dan battery optimization harus diuji di tablet target |

Aturan: notifikasi tidak boleh memuat URL kamera, IP, atau kredensial. `FLAG_SECURE`
pada activity mencegah thumbnail task-switcher menampilkan stream.

Implementasi 2026-11-26 (`app/src/main/kotlin/id/warung/monitor/service/`):

- `MonitoringService` - `mediaPlayback`, kanal `stream` (IMPORTANCE_LOW, tanpa badge),
  `START_STICKY`, `stopWithTask="true"`, `exported="false"`.
- Total dikirim sebagai empat primitif (`has_summary`, `available`, `occupied`, `unknown`),
  bukan objek, sehingga proses yang mati tidak dapat memulihkan angka lama.
- Snapshot `null` dirender "Menunggu hitungan meja", bukan "0 kosong".
- `MonitoringPolicy` memetakan `StreamUiState` ke Start/Update/Stop: `Failed` dan
  `Reconnecting` selalu mengosongkan total, `AuthFailed` tidak pernah menampilkan angka lama.
- Aksi "Hentikan" memakai `PendingIntent` immutable ke `StopMonitoringReceiver` (tidak
  exported). Receiver menutup preview + sesi sendiri lewat `goAsync`, karena saat layar
  mati belum tentu ada komposisi yang mengoleksi event.
- `WAKE_LOCK` + `ExoPlayer.setWakeMode(WAKE_MODE_NETWORK)` supaya RTSP tidak berhenti saat
  layar mati; tanpanya service hidup tetapi frame berhenti.
- Izin `POST_NOTIFICATIONS` diminta saat layar pertama kali `active`, tidak pernah di
  login. Ditolak pun monitoring tetap berjalan (notifikasi tidak tampil).
- Start kedua memakai `startService`, bukan `startForegroundService`, agar Android 12+
  tidak menolak promosi dari background saat layar mati.

---

## 10. Data lokal

| Data | Tempat | Alasan |
| --- | --- | --- |
| PIN hash + salt | DataStore | Kecil, sinkron, tidak butuh SQL |
| `deviceId` | DataStore | Stabil, dibuat sekali |
| Profil kamera (tanpa password) | DataStore (JSON) | Sedikit, jarang berubah |
| Password kamera | Android Keystore (`EncryptedSharedPreferences`) | Rahasia, tidak boleh plaintext |
| Konfigurasi meja | Room (`table_configs`) | Butuh riwayat revisi + query |
| Status meja terkini | Room (`table_status`) | Untuk pemulihan cepat setelah proses mati |
| Outbox event | Room (`outbox`) | Sinkronisasi Fase 6 |
| Preferensi (auto-lock, retensi) | DataStore | Sederhana |

Skema Room mengikuti `android-sync.md` §4; dokumen ini **tidak** menduplikasi DDL.

---

## 11. Baseline pustaka & toolchain

Versi di bawah adalah titik awal yang harus diverifikasi saat scaffold dibuat. Bila
versi stabil terbaru berbeda, pakai yang terbaru stabil dan catat perubahan di sini.

| Komponen | Versi target | Catatan |
| --- | --- | --- |
| Android Gradle Plugin | 8.13.x | Butuh Gradle 8.13+; Android Studio lokal 2023.3 belum mendukung — lihat §12.2 |
| Gradle | 8.14.x | Pakai wrapper, jangan Gradle sistem |
| Kotlin | 2.2.x | Compose compiler ikut versi Kotlin (plugin `org.jetbrains.kotlin.plugin.compose`) |
| JDK | 17 | Lokal: `D:\app\android-studio\jbr` (17.0.10); AGP 8.x mewajibkan 17 |
| compileSdk / targetSdk | 36 / 36 | Platform 36 sudah terpasang |
| minSdk | 29 (Android 10) | Sesuai baseline perangkat di `device-compatibility.md`; `foregroundServiceType` dan batas izin modern berlaku sejak API 29. Build aktual memakai `minSdk = 29` |
| Compose BOM | 2026.x | Satu BOM untuk seluruh artefak Compose |
| `androidx.media3` | 1.9.x | `media3-exoplayer`, `media3-exoplayer-rtsp`, `media3-ui-compose` |
| `androidx.lifecycle` | 2.11.x | `viewmodel-compose`, `runtime-compose` |
| `androidx.navigation` | 2.9.x | `navigation-compose` (typed routes) |
| Hilt | 2.5x | DI |
| `kotlinx.coroutines` | 1.10.x | `core`, `android`, `test` |
| `kotlinx.serialization` | 1.9.x | Profil kamera, DTO |
| Room | 2.8.x | KSP |
| DataStore | 1.1.x / 1.3.x | Preferences |
| `androidx.biometric` | 1.2.x | Login biometrik |
| Ktor client | 3.2.x | Sinkronisasi (Fase 6), bukan sekarang |
| `androidx.work` | 2.12.x | Outbox, heartbeat |
| `com.google.mlkit:barcode-scanning` | 17.3.x | QR kamera (opsional; bisa diganti ZXing) |
| Test | JUnit4, `kotlinx-coroutines-test`, Turbine, Robolectric, `compose-ui-test-junit4`, MockK | JVM-first |
| Lint | `lintDebug` + ktlint/detekt | Gate CI |
| `net.java.dev.jna:jna` | 5.17.x (`@aar`) | **Wajib** untuk binding UniFFI; generated Kotlin memakai `com.sun.jna.*` |

Versi yang benar-benar terpasang di scaffold saat audit 2026-11-26: AGP `8.13.2`,
Gradle `8.14.3`, Kotlin `2.3.21`, Compose BOM `2025.06.01`, Hilt `2.58`,
Room `2.8.5`, Coroutines `1.10.2`, dan JNA `5.19.1`. Baris lain yang belum masuk
version catalog tetap berupa target, bukan dependensi yang sudah dipakai.

Catatan versi:

- Compose + Kotlin 2.x memakai plugin compiler terpisah; **jangan** memakai
  `composeOptions.kotlinCompilerExtensionVersion` (usang).
- Media3 RTSP masih "work in progress" di upstream; uji di kamera nyata sebelum
  mengandalkannya untuk rilis. Ini alasan Fase K7 tidak boleh ditunda terlalu lama.
- Firebase Crashlytics **belum** dipakai di MVP; tambahkan di Fase K8 agar tidak ada
  dependensi Google Services saat pengembangan awal.

---

## 12. Struktur file & build

### 12.1 Struktur yang akan dibuat

```text
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/id/warung/monitor/...      # lihat §6.3
│       │   ├── res/values/{strings,themes,colors}.xml
│       │   ├── res/xml/{backup_rules,data_extraction_rules}.xml
│       │   ├── res/drawable/ic_launcher_*.xml
│       │   └── assets/models/.gitkeep
│       ├── test/kotlin/id/warung/monitor/...     # unit test JVM
│       └── androidTest/kotlin/id/warung/monitor/...# instrumented
├── core/{model,network,ui}/
└── feature/{auth,home,tables,settings}/
```

### 12.2 Prasyarat build di mesin ini

| Prasyarat | Status | Tindakan |
| --- | --- | --- |
| JDK 17 | ada (`D:\app\android-studio\jbr`) | set `org.gradle.java.home` di `gradle.properties` |
| Android SDK | ada (`D:\app\sdk`), platform 36, dan `android/local.properties` sudah berisi `sdk.dir` (gitignored) | pertahankan `local.properties` lokal; jangan di-commit |
| Gradle wrapper dan root build | ada (`gradlew`, `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`, version catalog) | pertahankan; modul `app`, `core:model`, `core:database`, dan `feature:tables` sudah terdaftar |
| Sumber aplikasi | sebagian | Gate 2026-11-26: 383 test host hijau (model 44, network 43, core:ui 6, database 11, cameras 37, tables 53, auth 36, home 56, onboarding 41, app 56); login/Home/Scan, tes koneksi empat lapis, backoff reconnect 1-2-4-8-16 dtk, mapping per `camera_id`, Room v2, dan preview Media3 sudah ada; service dan outbox masih target |
| Kompatibilitas dependency | lulus | dependency di-resolve dengan compileSdk 36/AGP 8.13.2; `:app:assembleDebug` dan `:app:lintDebug` lulus pada 2026-11-26 (lint 0 error) |
| Gradle cache | terisi sebagian | dependency berhasil diunduh; kegagalan saat ini ada di kompilasi Hilt/Dagger, bukan metadata AAR |
| Android Studio | 2023.3 (AGP ≤ 8.1) | **tidak** cocok untuk AGP 8.13; pakai CLI atau upgrade Studio |
| Emulator / perangkat | tidak terpasang | instrumented test ditunda; JVM test tetap jalan |

Keputusan: **build lewat CLI** (`gradlew`) sampai Android Studio di-upgrade. `local.properties`
berisi `sdk.dir=D\:\\app\\sdk` dan **tidak** di-commit (sudah ada di `.gitignore`).

### 12.3 Native library dan binding UniFFI

Jangan menyalin `.so` per ABI lewat task Gradle manual. Jalankan skrip Rust sekali;
skrip itu membangun tiga ABI, menyalin `.so`, dan membuat binding Kotlin:

```powershell
powershell -ExecutionPolicy Bypass -File tools/android-build/build-rust.ps1
```

Hasilnya (keduanya gitignored):

```text
android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libcore_ffi.so
android/app/build/generated/uniffi/kotlin/id/warung/monitor/core/core_ffi.kt
```

`app/build.gradle.kts` menambahkan folder generated itu sebagai source set Kotlin
(`srcDir` tetap harus menunjuk ke folder yang ada; buat lewat `.gitkeep` atau
jalankan skrip Rust sebelum build pertama):

```kotlin
android {
    sourceSets["main"].kotlin.srcDir("build/generated/uniffi/kotlin")
}
dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
```

Gradle **tidak** menjalankan Cargo. Saat binding atau `.so` belum ada, sink engine
melaporkan `Unavailable`; hanya jalur Rust yang membutuhkan artefak di atas. Build APK
saat ini tetap terblokir oleh binding Hilt yang disebut di §12.2, bukan oleh UniFFI.
`libonnxruntime.so` berasal dari AAR
`com.microsoft.onnxruntime:onnxruntime-android` dan path-nya dioper ke `Engine.start`
lewat `onnx_runtime_path`.

---

## 13. Rencana TDD Kotlin (file per file)

Setiap baris: tulis test dulu (merah), lalu implementasi (hijau). Nama test menyebut
perilaku.

### 13.1 Fase K1 — Fondasi

| File produksi | File test | Perilaku yang diuji |
| --- | --- | --- |
| `core/model/TableSummary.kt` | `TableSummaryTest` | hitung kosong/terisi/unknown; total; `unknown` tidak dianggap kosong |
| `core/model/CameraProfile.kt` | `CameraProfileTest` | serialisasi JSON tanpa password; `redactedUrl` menyembunyikan kredensial |
| `core/ui/format/TimeFormat.kt` | `TimeFormatTest` (6 test, hijau) | jam lokal `HH:mm:ss` per zona pemanggil; umur data ("12 detik lalu", "1 menit lalu", fallback jam setelah 1 jam, tahan clock skew) |
| `core/network/PrivateAddress.kt` | `PrivateAddressTest` | 10/8, 172.16/12, 192.168/16 dikenali; 100.64/10 dan publik ditolak |

### 13.2 Fase K2 — Login

| File | Test | Perilaku |
| --- | --- | --- |
| `feature/auth/PinHasher.kt` | `PinHasherTest` | hash stabil untuk salt sama; salt beda → hash beda; verifikasi konstan waktu |
| `feature/auth/LoginViewModel.kt` | `LoginViewModelTest` | 8 test di §3.3 |
| `feature/auth/LockoutPolicy.kt` | `LockoutPolicyTest` | ambang 5/10/15; `elapsedRealtime` tidak terpengaruh jam dinding |
| `feature/auth/CreatePinViewModel.kt` | `CreatePinViewModelTest` | konfirmasi harus sama; PIN lemah (123456, 000000) ditolak |

### 13.3 Fase K3 — Scan & Connect

| File | Test | Perilaku |
| --- | --- | --- |
| `core/network/SubnetPlanner.kt` | `SubnetPlannerTest` | /24 benar; buang network/broadcast/self; maks 254 host; batalkan bila seluler |
| `core/network/PortProbe.kt` | `PortProbeTest` | hosts terbuka terdeteksi; timeout 400 ms; concurrency ≤ 32 |
| `core/network/WsDiscovery.kt` | `WsDiscoveryTest` | parse `ProbeMatch`; dedup dengan hasil port probe |
| `feature/home/ScanViewModel.kt` | `ScanViewModelTest` | hasil gabungan; urutan ranking; batal saat sheet ditutup; 0 hasil → state `Empty` |
| `feature/home/ConnectViewModel.kt` | `ConnectViewModelTest` | 4 lapis berurutan; `auth_failed` tidak retry; error tanpa IP di pesan |
| `feature/home/CameraRepository.kt` | `CameraRepositoryTest` | simpan profil tanpa password; password masuk ke fake Keystore; hapus kamera menghapus secret |
| `feature/onboarding/DeviceCapabilityProvider.kt` | `DeviceCapabilityProviderTest` | API/ABI/RAM/storage dipetakan ke profil; `skipped` tidak dianggap lulus |
| `feature/onboarding/OnboardingViewModel.kt` | `OnboardingViewModelTest` | `pass`/`warning`/`unsupported`; tombol disabled selama cek; retry tidak menduplikasi warm-up |

### 13.4 Fase K4 — Home UI

| File | Test | Perilaku |
| --- | --- | --- |
| `feature/home/HomeViewModel.kt` | `HomeViewModelTest` | event → ringkasan; `unknown` dihitung; tidak ada update saat status sama; `Failed` → angka `—` |
| `feature/home/StreamUiMapper.kt` | `StreamUiMapperTest` | `(StreamErrorKind, NetworkState)` → pesan Indonesia; tanpa IP/kredensial |
| `feature/home/HomeScreen.kt` | `HomeScreenTest` (Robolectric/Compose) | dua kartu tampil; CTA Scan ada; `unknown>0` memunculkan warning; state `Idle` menampilkan `—` |
| `feature/home/HomeLayoutTest.kt` | `HomeLayoutTest` | lebar 411dp → Compact; 720dp → Medium dua panel |
| `feature/onboarding/DeviceCheckScreen.kt` | `DeviceCheckScreenTest` | status per baris; warning amber; unsupported tidak menampilkan tombol lanjut |

Test screenshot (opsional, Fase K4 akhir): portrait idle, portrait live, landscape live,
error — 4 gambar referensi.

### 13.5 Fase K5 — Integrasi engine (fake → Rust)

| File | Test | Perilaku |
| --- | --- | --- |
| `engine/FakeStreamEngineGateway.kt` | `FakeGatewayTest` | mengirim event berkala; bisa dipaksa `Failed`/`Reconnecting` |
| `engine/StreamEngineContractTest.kt` | dijalankan untuk **kedua** impl | kontrak yang sama: connect → Live → event → disconnect |
| `service/MonitoringService.kt` | `MonitoringServiceTest` (Robolectric) | notifikasi muncul, tanpa snapshot menampilkan "menunggu", aksi Hentikan, `exported=false`, API 29 |
| `service/MonitoringPolicy.kt` | `MonitoringPolicyTest` | idle tidak memulai, live kirim total, gagal/reconnect mengosongkan total, stop sekali |
| `service/MonitoringNotificationText.kt` | `MonitoringNotificationTextTest` | `unknown` tidak dilipat jadi kosong, tanpa alamat stream |
| `service/NotificationPermission.kt` | `NotificationPermissionTest` | tidak meminta sebelum API 33, tidak meminta ulang setelah grant |
| `service/NetworkMonitor.kt` | `NetworkMonitorTest` | Wi-Fi hilang → `no_network`; seluler saja → `wrong_network`; Wi-Fi kembali → retry sekali |
| `engine/EngineWarmup.kt` | `EngineWarmupTest` | model hilang/checksum salah/runtime gagal menghasilkan kode stabil; tidak membuka RTSP |
| `engine/FrameBridge.kt` | `FrameBridgeTest` | ukuran buffer tepat; frame drop dihitung; tidak ada copy berlebih yang tak terdokumentasi |

### 13.6 Fase K6 — Kalibrasi meja

| File | Test | Perilaku |
| --- | --- | --- |
| `feature/tables/PolygonEditor.kt` | `PolygonEditorTest` | tambah/geser/hapus titik; tolak < 3 titik; tolak self-intersect; klamp 0..1 |
| `feature/tables/MappingTransform.kt` | `MappingTransformTest` | round-trip layar ↔ 0..1; letterbox; zoom 1..8x (contoh di `tdd-plan.md` §10.5) |
| `feature/tables/TableConfigRepository.kt` | `TableConfigRepositoryTest` | simpan versi baru; `needs_recalibration` saat rasio berubah |

### 13.7 Fase K7 — Surface dari Rust

| File | Test | Perilaku |
| --- | --- | --- |
| `engine/RustStreamEngine.kt` | `RustStreamEngineTest` (instrumented) | `connect` mengembalikan `Live`; `disconnect` tidak panic; surface attach/detach aman |
| `engine/SurfaceBridge.kt` | `SurfaceBridgeTest` | attach surface sebelum/di tengah stream; detach tanpa crash |
| `service/StreamService.kt` | `StreamServiceSoakTest` (`@Ignore` default) | 8 jam, RSS stabil |

Catatan: K7 adalah **opsi pipeline**, belum keputusan. ADR 0009 harus selesai lebih
dulu. Selama keputusan belum dibuat, jangan menulis "single connection" atau
"Media3 dihapus" sebagai fakta.

### 13.8 Fase K8 — Sinkronisasi & monitoring (opsional)

Mengikuti `android-sync.md`, `server-api.md`, dan `monitoring.md`; tidak memblokir
"ready to use". Semua endpoint di `server-api.md` masih target dan belum dapat dipanggil.

### 13.9 Urutan eksekusi

```text
K1 fondasi (model, format, network util)          ~1 hari
K2 login                                           ~2 hari
K3 scan + connect                                  ~3 hari
K4 home UI (fake engine)                           ~3 hari
K5 integrasi engine + foreground service           ~3 hari
K6 kalibrasi meja                                  ~2 hari
K7 surface Rust (butuh ai-engine + core-ffi)       ~5 hari
K8 sinkronisasi + monitoring                       ~5 hari
```

"Ready to use" tercapai di akhir **K6** dengan `ENGINE_MODE = media3` + deteksi Rust
(read-only), atau akhir **K7** untuk single-connection penuh.

---

## 14. Kasus tepi dan perilaku yang harus ditulis di test

| Kasus | Perilaku |
| --- | --- |
| Kamera menjawab 401 | `auth_failed`; pesan "Kata sandi kamera salah"; tidak retry otomatis |
| Kamera tidak menjawab DESCRIBE | `timeout` setelah 3 detik; tawarkan "Scan ulang" |
| Stream putus di tengah | `Reconnecting(attempt=n)` + backoff 1-2-4-8-16 detik; angka terakhir diberi label |
| HP pindah ke data seluler | `wrong_network`; stream dihentikan; jaringan kembali → reconnect sekali |
| Scan di data seluler | Ditolak sebelum memindai; pesan "Sambungkan ke Wi-Fi" |
| Dua kamera di subnet | Keduanya tampil; pilih salah satu; kamera lama tetap tersimpan |
| Kamera tidak punya sub-stream | Fallback ke main stream + peringatan beban |
| Rotasi saat `Connecting` | Tidak memulai ulang handshake |
| Overlay dibuka saat stream putus | Overlay tetap; banner error tampil di belakang |
| Pengguna menghapus kamera | Service berhenti; secret di Keystore dihapus; konfigurasi meja ditawari hapus |
| PIN salah 5x | Lockout 30 detik, hitung mundur terlihat |
| Layar mati > 5 menit | App terkunci; stream tetap jalan di service |
| RAM perangkat rendah | Turunkan FPS inferensi (lihat `performance.md` §5), jangan hentikan stream |
| Model belum ada | Status meja `unknown`; pesan "Model deteksi belum siap" |

---

## 15. Checklist implementasi per fase (Definition of Done)

### K1 — Fondasi

- [x] `settings.gradle.kts` + version catalog + wrapper Gradle ada di worktree
- [x] `local.properties` ada dan berisi `sdk.dir` (tidak di-commit)
- [x] Modul `app`, `core:model`, `core:network`, `core:database`, `feature:cameras`,
      `feature:tables`, `feature:auth`, `feature:home`, dan `feature:onboarding`
      terdaftar di `settings.gradle.kts`
- [x] `core:network` dibuat (JVM murni; kini 43 test)
- [x] `core:ui` dibuat: `MonitorTheme` (dipindah dari `app`) + `TimeFormat` (`clock`, `age`) dengan 6 test JVM
- [x] CI lokal: `./gradlew :core:model:test` hijau (44 test, 2026-11-26)
- [x] Source `core:model` mencakup `MappingFrame`, `NormalizedPoint`, `PolygonValidator`, dan `TableConfigJson`
- [x] `TableSummaryTest` dan `CameraProfileTest` lulus; cakupan alamat privat ada di
      `SubnetScanTest` (`:core:network`)
- [x] `DeviceAssessmentTest` lulus dengan fake untuk perangkat 3 GB (`balanced`), 4 GB
      (`full`), ABI 32-bit (`blocked`), API 28, RAM 2 GB, model hilang, dan warm-up gagal
      (11 test, `:feature:onboarding:testDebugUnitTest` hijau 2026-11-26)

### K2 — Login

- [x] Layar login sesuai wireframe §3.2 (portrait & landscape; `wide` pada 600dp)
- [x] PIN 6 digit, keypad custom, auto-verifikasi, dan haptic `HapticFeedbackType.TextHandleMove` per digit (`PinKeypadHapticTest`, 3 test)
- [x] Biometrik opsional dengan fallback PIN (`BiometricUnlocker` + `AndroidBiometricUnlock`; `onBiometric = null` bila sensor tidak tersedia/terdaftar). Prompt platform API 28+ dipakai alih-alih `androidx.biometric` 1.1.0 karena versi itu butuh `FragmentActivity` + `appcompat`; konstruktor `Context` di 1.4.0-rc01 masih `@RestrictTo(LIBRARY)`
- [x] Lockout 5x/10x/15x dengan countdown (`LockoutPolicy`, 3 test)
- [x] Reset aplikasi butuh konfirmasi `HAPUS`
- [x] `FLAG_SECURE` aktif (`MainActivity`)
- [x] `LoginViewModelTest` lulus (10 test, bukan 8)
- [x] Diagnostik perangkat berjalan sebelum PIN; `blocked` tidak membuka Home
      (`DeviceCheckRoute` -> `OnboardingState.Approved` -> PIN). Warm-up native nyata
      belum dijalankan di perangkat; tanpa model terpasang hasilnya `skipped` -> `lite`

### K3 — Scan & Connect

- [x] Tombol Scan CCTV membuka sheet; progress terlihat (`CameraScanViewModel` + `ScanSheetBody`)
- [x] Probe /24 + port 554/8554, concurrency 32, timeout 400 ms (`core:network`, 43 test)
- [x] WS-Discovery paralel; dedup (`CameraDiscovery` + `DiscoveryMerge`)
- [x] Input manual (host/port/path/user) ada di editor kamera; QR belum
- [x] Tes 4 lapis dengan status per lapis (`ConnectionTester` + `ConnectionRows` di editor kamera; `SocketRtspSessionProbeTest` ke server loopback)
- [x] Scan ditolak di data seluler (`cellular_means_no_scan_and_no_probe_is_attempted`)
- [x] Nama kamera bisa diedit tanpa mengubah `id` (`editing_a_camera_keeps_its_id_and_changes_only_the_label`)
- [ ] Path, codec, dan batas sesi mengikuti checklist `camera-setup.md`

### K4 — Home UI

- [x] Video (Media3 atau fake) 16:9 di kartu (`aspectRatio(16f / 9f)`)
- [x] Dua kartu angka + `unknown` + waktu update (`CountCard`, `UnknownNote`)
- [x] CTA Scan; overflow menu (Kelola kamera & meja, Keluar)
- [x] Layout Compact/Medium/Expanded; rotasi tidak memutus
- [x] State Idle/Connecting/Live/Reconnecting/Failed semuanya punya tampilan
- [x] Perangkat `lite` menampilkan mode hemat (`HomePowerMode.Saver` dari shell, catatan
      `power-saver-note` di compact dan dua panel, 3 test); perangkat `blocked` tidak
      membuka stream

### K5 — Engine + service

- [x] Seam engine setara gateway: `CameraSession` (Rust) + `EngineStatusSource` (demo/Rust) + `CameraPreviewCoordinator` (Media3/demo) + `StreamFailureReporter`. `StreamEngineGateway` versi `android-app-plan.md` tidak dibuat terpisah karena tiga implementasinya sudah ada sebagai seam yang diuji; menambah pembungkus keempat hanya menambah lapisan tanpa perilaku baru.
- [x] Foreground service + notifikasi + `POST_NOTIFICATIONS` (`MonitoringService` `mediaPlayback`, kanal `stream`, aksi Hentikan, teks total dari Rust; izin diminta saat stream pertama, bukan saat login; ditolak pun monitoring tetap jalan)
- [x] `NetworkMonitor` memberi `no_network`/`wrong_network` (`NetworkPolicy` + `AndroidNetworkMonitor`; seluler/offline tidak membuka RTSP, `auth_failed` tidak pernah di-retry)
- [x] Reconnect backoff terlihat di UI (`ReconnectPolicy` 1-2-4-8-16 dtk + `Reconnecting(attempt, nextRetryInMs)`; reset setelah live; berhenti di 5 percobaan; retry sekali saat Wi-Fi kembali)
- [x] Kegagalan preview dilaporkan ke Rust (`StreamFailureReporter` + `RustStreamFailureReporter`; tabel langsung `unknown`)
- [x] Contract test dijalankan untuk fake & media3 (`PreviewCoordinatorContractTest`: satu suite, dua implementasi; Media3 diuji dengan sumber Demo di Robolectric, RTSP nyata tetap bukti perangkat)
- [x] Kontrak frame (owner, ukuran, drop, copy) tertulis dan diuji (`rust-scaffold.md` 7.1; 5 test FFI + test `pushFrameOffMain` di `:app`)
- [ ] OEM battery optimization dan autostart diuji di minimal satu tablet target

### K6 — Kalibrasi

- [x] Editor poligon di atas preview; pinch-zoom 1..8x; pan terbatas
      (`MappingTransform.MIN_ZOOM/MAX_ZOOM`, `clampPan`)
- [x] Simpan ke Room + kirim `setTables` ke engine (`persistAndApply`)
- [x] Peringatan `needs_recalibration` saat rasio berubah

### K7 — Surface Rust

- [ ] `libcore_ffi.so` termuat (arm64-v8a)
- [ ] Preview dari Surface + deteksi satu koneksi
- [ ] Media3 dihapus dari build rilis
- [ ] Soak 8 jam lulus
- [ ] ADR 0009 diterima sebelum implementasi dimulai (saat ini masih Diusulkan)
- [ ] Tidak ada dua koneksi RTSP di build rilis

---

## 16. Risiko dan mitigasi

| Risiko | Dampak | Mitigasi |
| --- | --- | --- |
| Media3 RTSP belum matang | Stream putus-putus di sebagian kamera | Uji di kamera target sejak K5; siapkan fallback ke Fase K7 lebih cepat |
| Dua koneksi RTSP (transisi) | Kamera menolak sesi kedua | `ALLOW_DUAL_RTSP=false` di release; sub-stream untuk deteksi |
| Discovery diblokir router | Scan tidak menemukan apa pun | Input manual + QR selalu tersedia; pesan tidak menyalahkan pengguna |
| Probe /24 di jaringan besar | Scan lambat | Batas 254 host, concurrency 32, batalkan saat sheet ditutup |
| Android membunuh service | Monitoring berhenti | Foreground service + `START_STICKY` + `BootReceiver` opsional |
| Kebocoran memori Media3 | Crash setelah berjam-jam | Soak 8 jam; pantau RSS di `EngineMetrics` |
| PIN lupa | Pengguna terkunci | Reset aplikasi (hapus data) dengan konfirmasi ketik |
| Kredensial di log | Kebocoran | `redact` terpusat + test `log_never_contains_credentials` |
| Toolchain AGP vs Studio lokal | Tidak bisa build via IDE | Build via CLI; upgrade Studio saat tersedia |
| Perangkat tidak kompatibel | Engine crash atau OOM saat operasional | Diagnostik awal, profil `blocked`, dan warm-up sebelum Home |
| OEM membunuh service | Monitoring berhenti | Panduan battery optimization + uji reboot/doze per merek |
| Pemadaman listrik | Tablet mati saat monitoring | `BootReceiver` opsional, status jujur setelah start, dan uji recovery |
| Nama kamera berubah | Riwayat terputus | Pisahkan `id` stabil dari `label` yang bisa diedit |

---

## 17. Perintah verifikasi

| Lingkup | Perintah |
| --- | --- |
| Build debug | `./gradlew :app:assembleDebug` |
| Unit test JVM | `./gradlew :app:testDebugUnitTest` |
| Lint | `./gradlew :app:lintDebug` |
| Instrumented (butuh perangkat) | `./gradlew :app:connectedDebugAndroidTest` |
| Format | `./gradlew ktlintCheck detekt` |
| APK ke perangkat | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Log stream | `adb logcat -s StreamService:* HomeViewModel:*` |

Gate sebelum merge: `assembleDebug` + `testDebugUnitTest` + `lintDebug` hijau, dan
tidak ada `TODO` baru di `feature/*/src/main`.

---

## 18. Yang sengaja tidak dikerjakan sekarang

- Bottom navigation, drawer, atau tab (permintaan produk).
- Dashboard web dan sinkronisasi VPS (Fase 6).
- Multi-kamera simultan.
- Login akun cloud / SSO.
- Rekaman video atau tangkapan layar otomatis.
- Dark mode otomatis berdasarkan sensor cahaya (ikut sistem saja).

---

## 19. Pertanyaan yang harus dijawab sebelum K3 dimulai

1. Kamera target: EZVIZ model apa, firmware apa, dan apakah sub-stream RTSP-nya aktif?
2. Apakah kamera mengizinkan lebih dari satu sesi RTSP bersamaan (menentukan urgensi
   Fase K7)?
3. Path RTSP final dan codec apa yang benar-benar dikirim kamera?
4. Apakah pengguna mau QR di dus kamera, atau cukup input manual?
5. Berapa jumlah meja realistis (mempengaruhi tinggi kartu dan layout landscape)?
6. Apakah perangkat operasional diizinkan memakai kiosk/dedicated device mode?
7. Apakah monitoring harus menyala otomatis setelah reboot, atau menunggu PIN?

Jawaban ini mempengaruhi default, bukan arsitektur; semua sudah punya jalan keluar.

Checklist lengkap ada di [`camera-setup.md`](camera-setup.md) dan
[`device-compatibility.md`](device-compatibility.md).
