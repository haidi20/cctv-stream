# Prioritas MVP

Dokumen ini menetapkan **potongan rencana yang wajib selesai lebih dulu** di Kotlin dan
Rust, urutannya, dan bukti yang harus ada sebelum MVP disebut selesai. [`roadmap.md`](roadmap.md)
tetap menjadi urutan fase; dokumen ini memilih apa yang dikerjakan sekarang dan apa yang
sengaja ditunda.

Status dokumen: **rencana prioritas**, bukan bukti implementasi. Bukti per area tetap di
[`status.md`](status.md).

## 1. Definisi MVP

MVP adalah satu warung, satu perangkat Android, satu kamera:

1. Diagnostik perangkat, lalu login PIN.
2. Sumber frame: **video lokal** (`CameraSource.Video` + Media3) selama kamera belum ada;
   RTSP nanti menggantikan tanpa mengubah bagian hilir.
3. Mapping meja (poligon 0..1) dan status `available`/`occupied`/`unknown` beserta totalnya.
4. Monitoring tetap berjalan saat layar mati (foreground service + `WAKE_LOCK`).
5. Beban perangkat tetap wajar selama 8 jam: baterai, RAM, ROM, CPU, dan suhu.
6. Sync VPS opsional; kegagalan jaringan tidak boleh mengubah status meja.

Bukan bagian MVP (lihat §7): dashboard web, multi-kamera, QR, token di Keystore, decoder
Rust/K7, retensi `table_logs`, dan kuantisasi INT8.

## 2. Prinsip prioritas

| # | Prinsip | Konsekuensi praktis |
| --- | --- | --- |
| 1 | Kamera nyata belum ada, jadi **video lokal adalah sumber resmi MVP**, bukan demo | `testdata/videos/` menjadi bahan uji utama; menunggu kamera bukan alasan menunda uji |
| 2 | Uji resource adalah **gate rilis**, bukan pekerjaan akhir | item yang menghalangi pengukuran resource selalu P0, walaupun terlihat "bukan fitur" |
| 3 | P0 hanya untuk hal yang menghalangi pengukuran atau membuat status tidak jujur | fitur tampilan yang tidak memengaruhi angka resource turun ke P1/P2 |
| 4 | `unknown` tidak boleh menjadi `available` hanya karena data tidak ada | penghematan baterai tidak boleh memalsukan status meja |
| 5 | Offline-first | item yang menambah ketergantungan jaringan tidak boleh memblokir MVP |
| 6 | Setiap item P0 menghasilkan **file laporan di perangkat** | laporan `cctv-*.txt` di `/sdcard/Android/data/id.warung.monitor/files/`, bukan tangkapan layar |
| 7 | Angka resource tanpa alat ukur adalah tebakan | menambah metrik lebih dulu, baru menetapkan ambang |

Alasan prinsip 1–2: yang mahal di perangkat adalah decode + inferensi, dan keduanya
berjalan sama saat frame datang dari file maupun dari RTSP. Yang **tidak** tercakup oleh
video adalah perilaku jaringan (radio Wi-Fi, reconnect RTSP, latensi kamera), sehingga
angka dari video adalah batas bawah, bukan angka lapangan. Rincian protokolnya ada di
[`performance.md`](performance.md) §10.

## 3. Prioritas Rust

Urutan baca: kerjakan P0 dari atas; satu item belum selesai bila buktinya belum ada.

| ID | Item | Mengapa P0 | Bukti selesai |
| --- | --- | --- | --- |
| R-P0-1 | Metrik resource di `EngineHealth`: `rss_kb`, `frames_dropped`, `inference_p50_ms`, `inference_p95_ms` | Tanpa RSS dan latensi, soak tidak bisa memisahkan kebocoran Rust dari kebocoran UI | **Host selesai 2026-11-27**: unit test `/proc/self/status` (`VmRSS`), percentile p50/p95 (nearest-rank, 120 sampel, reset per sesi), `frames_dropped` dari sampler; field muncul di binding Kotlin dan terkirim di `runtime`/`inference` heartbeat (schema v6). Sisa: angka RSS/latensi pada perangkat dan ambang soak |
| R-P0-2 | Jalur video lokal menggerakkan status nyata di perangkat | **Lulus 2026-11-27** di SM-A176B (API 36, arm64): `tools/device-e2e.ps1` menghasilkan `cctv-dev-e2e.txt` dengan `texture=1080x607`, 10/15/24 FPS diminta -> 4,5/3,8/3,8 capture & analysis FPS, `tables=2`, status `AVAILABLE,AVAILABLE`, `modelReady=true`. Dua hambatan diperbaiki: `libcore_ffi.so` lama (`build-rust.ps1`) dan keyguard (`FLAG_SHOW_WHEN_LOCKED` di activity uji). Catatan: angka ini membuktikan jalur jalan, **bukan** kemampuan 24 FPS | Selesai; laporan perangkat di `cctv-dev-e2e.txt` |
| R-P0-3 | Harness soak 30 menit dan 8 jam berbasis video (`tools/device-soak.ps1` + `DeviceSoakTest`, tersedia 2026-11-27) | Fase 1/2 mensyaratkan 30 menit, DoD Android mensyaratkan 8 jam. **Soak 30 menit lulus 2026-11-27** di SM-A176B (30 sampel 60 dtk; PSS 158 122->246 160 kB, RSS 279 392->372 608 kB, suhu 34,9 C, thermal `NONE`, `framesIn=3561 sampled=2786 dropped=774`); keyguard diatasi dengan `FLAG_SHOW_WHEN_LOCKED`. Perbaikan jadwal sampler `core-ffi` (slack 10% interval, maks 50 ms) menurunkan drop 10 menit dari 214 ke **0** | CSV 8 jam: PSS, CPU, baterai, suhu, termal, `frames_in`, `frames_sampled`, `rss_kb`, `frames_dropped`, p50/p95 |
| R-P0-4 | Ukur model input kecil (320) **dan** sahkan ambang RAM minimum dari angka | Target 2 FPS @416 di `performance.md` §7 belum punya model; 640 memakan 216 ms p50 sehingga perangkat 4 GB berisiko. Ambang onboarding `< 3072` MB juga belum diuji pada HP 3 GB nyata (`device-compatibility.md` §1.1). Soak 30 menit mencatat CPU 166-188% (1,7-1,9 core), jadi biaya CPU juga perlu turun | Laporan `cctv-benchmark.txt` berisi p50/p95 untuk model 320 + `MemTotal` dan PSS HP 3 GB saat 1 FPS; A/B `benchThreads 2` vs default untuk memilih thread count dari angka; ambang final disahkan dari angka itu atau ditulis **belum diverifikasi** bila perangkat 3 GB tidak tersedia |
| R-P1-1 | `tracing` JSON + redaksi terpusat | Fase 7 dan `monitoring.md` §2; saat ini tidak ada `tracing` sama sekali, jadi log tidak bisa dipakai untuk analisis resource | Log JSON berputar + test tidak memuat URL/password/PIN |
| R-P1-2 | Decoder konkret dan jembatan frame | ADR 0009 masih **Diusulkan**; Fase 1/4 tidak boleh ditutup tanpanya | ADR diterima + decoder teruji pada klip lokal |
| R-P1-3 | RTSP live + reconnect soak | Butuh kamera nyata; tidak dapat dikerjakan sekarang | `tests/rtsp_live_ezviz.rs` hijau dengan `RTSP_URL` |
| R-P2-1 | Kuantisasi INT8/FP16, crash reporter native, soak 7 hari | Setelah angka dasar stabil; tanpa baseline, kuantisasi tidak bisa dinilai | Prosedur `performance.md` §6.2 terpenuhi |

## 4. Prioritas Kotlin

| ID | Item | Mengapa P0 | Bukti selesai |
| --- | --- | --- | --- |
| K-P0-1 | Jaga gate host tetap hijau | Angka resource hanya berguna kalau build-nya sendiri tidak rusak; artefak 2026-11-27 mencatat 705 test debug-only / 0 gagal dari 91 `TEST-*.xml` (723 dari 95 artefak bila varian release ikut) | `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` hijau pada run baru |
| K-P0-2 | Harness resource di perangkat berbasis video | `tools/device-soak.ps1` + `DeviceSoakTest` **terverifikasi 2026-11-27**: soak 30 menit, 30 sampel 60 dtk, CSV dan laporan di `$env:TEMP\cctv-soak-30min\`; PSS/RSS/CPU/baterai/suhu/termal/data/frame tercatat; soak 10 menit pembanding setelah perbaikan sampler mencatat 0 drop | Selesaikan soak 8 jam, lalu isi matriks §5 |
| K-P0-3 | Heartbeat mengirim resource nyata dan nilainya benar | `AndroidHeartbeatFacts` sudah membaca `ram_free_mb`, `battery_temp_c`, `thermal_status`, `storage_free_mb`, `profile`; kebenaran nilainya di perangkat belum diverifikasi (`adb` kosong) | Heartbeat diterima server dengan nilai yang sama dengan `dumpsys` pada saat yang sama |
| K-P0-4 | Monitoring 8 jam layar mati tanpa kebocoran | DoD `android-app-plan.md` butir 10 (RSS stabil ±10%); sekarang belum pernah diuji lebih dari hitungan menit | Laporan 8 jam: PSS stabil, tanpa ANR/crash, baterai terukur |
| K-P0-5 | Profil onboarding memakai latensi terukur, bukan hanya total RAM | Warm-up sekarang 64x64 dan hanya membuktikan model termuat; perangkat yang lolos bisa tetap 4 FPS @640 | `OnboardingStore` menyimpan p95 inference; `EngineStartPlanner` memilih laju dari angka itu |
| K-P1-1 | `table_logs` + prune + layar retensi | `android-sync.md` §4/§7; tanpa ini storage tumbuh tanpa batas pada pemakaian lama | Migrasi Room + DAO + test prune + layar penyimpanan |
| K-P1-2 | Token server di Keystore | Sekarang masih DataStore | Token tersimpan terenkripsi + test |
| K-P1-3 | K7 (Surface dari Rust) | Menunggu ADR 0009 | `SurfaceBridge`/`RustStreamEngine` + test instrumented |
| K-P2-1 | Multi-kamera, QR, dashboard, biometrik di perangkat | Tidak memblokir MVP satu kamera | Sesuai `multi-camera-plan.md` |

## 5. Matriks bukti resource (gate MVP)

Ambang di bawah adalah **target yang mengikat sebagai pertanyaan uji**, bukan hasil
pengukuran. Kolom "sumber" menunjukkan asal angka; yang bertanda "hipotesis" belum
disetujui siapa pun dan wajib disepakati sebelum dipakai menolak rilis.

| Metrik | Alat | Ambang target | Sumber ambang | Status 2026-11-27 |
| --- | --- | --- | --- | --- |
| Pertumbuhan PSS | `adb shell dumpsys meminfo id.warung.monitor` | ≤ +10% dari baseline setelah 30 menit, stabil 8 jam | `android-app-plan.md` butir 10 | Terukur 30 menit: 158 122->246 160 kB (+55,7%, layar nyala). Kenaikan terjadi di menit pertama (sampel 1 vs 60: 158->233 MB) lalu mendatar/fluktuatif 233-268 MB hingga menit 30; ambang +10% **tidak** terpenuhi bila baseline sampel pertama dipakai, dan 8 jam belum diukur |
| CPU aplikasi | `adb shell dumpsys cpuinfo` | ≤ 60% satu core saat 2 FPS, layar nyala | Hipotesis (216 ms per 500 ms ≈ 43% satu core untuk inferensi saja) | Terukur 30 menit: 166-188% (≈1,7-1,9 core) sepanjang run. Ambang hipotesis ≤60% satu core **terlampaui**; angka ini harus dipakai merevisi ambang atau menurunkan beban |
| Baterai | `adb shell dumpsys battery` | ≤ 10% per jam, layar mati, 2 FPS | Hipotesis; angka dasar belum ada | **Tidak valid**: perangkat USB powered (charging) selama soak; 100%->100% bukan bukti drain. Perlu run layar mati + baterai |
| Suhu baterai | `adb shell dumpsys battery` (satuan 0,1 C) | < 45 C; termal tidak mencapai `SEVERE` | `performance.md` §5 dan §7 | Hanya 30 detik benchmark |
| Storage | `adb shell run-as id.warung.monitor du -sk .` | Stabil saat idle; outbox ≤ 10.000 baris | `android-sync.md` | Terukur 30 menit: 13 462->13 462 kB (stabil) |
| Frame drop | `EngineHealth.frames_dropped` (fallback `frames_in - frames_sampled`) | Tidak naik saat layar mati; drop wajar saat 24 FPS diminta | `performance.md` §9 | Sebelum perbaikan, 30 menit pada 2 FPS: 3 561 masuk, 2 786 sampel, 774 drop (21,7%). Setelah perbaikan jadwal sampler, 10 menit: 1 186 masuk, 1 186 sampel, **0 drop** |
| ANR/crash | `adb logcat -d` + `dumpsys activity` | 0 selama 8 jam | — | Belum diukur |
| Latensi inferensi | `tools/device-bench.ps1` | p95 ≤ 300 ms pada 640; p50 tercatat per profil | `performance.md` §6.3.1 (p95 276 ms terukur) | Terukur, 30 detik |

Urutan pengambilan angka: baseline 5 menit dalam keadaan diam, lalu 30 menit beban
video, lalu 8 jam layar mati. Tanpa baseline, kenaikan PSS tidak bisa dibedakan dari
alokasi awal.

Ambang RAM belum diuji pada perangkat nyata: onboarding memblokir `< 3072` MB
(`DeviceAssessment.MIN_RAM_MB`) sementara pengaman sesi memberi 1 FPS untuk 2.560-3.072
MB (`EngineStartPlanner.inferenceFpsFor`). Artinya HP berlabel 3 GB bisa tertolak sebelum
diukur. Rinciannya di
[`device-compatibility.md`](device-compatibility.md) §1.1; memutuskan ambang final
adalah bagian dari langkah **R-P0-4** di §6.

## 6. Urutan pengerjaan

1. **K-P0-1** — pastikan gate hijau pada run baru; jangan menumpuk perubahan di atas build yang tidak jelas.
2. **R-P0-1 (selesai host 2026-11-27) + K-P0-2 (harness terverifikasi 30 menit 2026-11-27)** — metrik Rust dan harness PowerShell sudah berjalan di perangkat; sisa soak 8 jam.
3. **R-P0-2 + K-P0-3** — buktikan jalur video menggerakkan status dan heartbeat mengirim resource yang benar.
4. **R-P0-3 + K-P0-4** — soak 30 menit selesai; lanjutkan 8 jam layar mati; isi matriks §5.
5. **R-P0-4** — ukur model 320, lalu putuskan profil perangkat 4 GB.
6. Baru setelah itu P1 (log, `table_logs`, Keystore, decoder).

Langkah 2 harus mendahului langkah 4. Soak tanpa metrik hanya menghasilkan kenyamanan,
bukan bukti; memperbaiki kebocoran tanpa angka berarti menebak.

## 7. Yang sengaja ditunda

| Item | Ditunda sampai | Konsekuensi bila dipaksa sekarang |
| --- | --- | --- |
| RTSP kamera EZVIZ (bagian live Fase 1) | Ada kamera + `camera-setup.md` §1/§4 terisi | Uji hanya membuktikan mock, bukan kamera |
| Decoder Rust + Surface (K7) | ADR 0009 diterima | Dua jalur video bersaing tanpa dasar keputusan |
| Dashboard web | `/v1/sync` stabil dan data resource masuk | Dashboard menampilkan status tanpa bukti beban perangkat |
| Multi-kamera | MVP satu kamera lulus matriks §5 | Beban resource berlipat sebelum baseline diketahui |
| `table_logs`, retensi, arsip | Setelah soak 8 jam stabil | Retensi menyembunyikan kebocoran yang seharusnya terlihat |
| Kuantisasi INT8/FP16 | Baseline FP32 dan fixture akurasi ada | Latensi turun tanpa bukti akurasi |

## 8. Hubungan dengan dokumen lain

| Dokumen | Perannya terhadap MVP |
| --- | --- |
| [`roadmap.md`](roadmap.md) | Urutan fase dan kriteria "selesai bila" |
| [`performance.md`](performance.md) §10 | Protokol pengukuran resource berbasis video |
| [`testdata.md`](testdata.md) §4 | Skenario klip dan pasangan `expected/*.json` |
| [`status.md`](status.md) | Bukti yang sudah ada dan yang belum |
| [`android-app-plan.md`](android-app-plan.md) §15 | Checklist DoD Kotlin per fase |
| [`android-sync.md`](android-sync.md) | Retensi outbox dan `table_logs` |
| [`device-compatibility.md`](device-compatibility.md) | Profil perangkat dan warm-up |

MVP dinyatakan selesai bila matriks §5 terisi dengan angka dari perangkat, seluruh item
P0 punya bukti, dan tidak ada klaim "sudah jalan di kamera nyata" sebelum kamera diuji.
