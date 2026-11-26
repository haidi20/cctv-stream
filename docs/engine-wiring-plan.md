# Rencana wiring engine, preview CCTV, dan frame pump

Status: implementasi host, 2026-11-26. Dokumen ini mengikat urutan start engine,
pemilihan jalur model/runtime, dan cara frame CCTV sampai ke Rust. Verifikasi
perangkat belum ada; lihat `docs/status.md`.

Dokumen terkait:

- `docs/android-app-plan.md` - alur login -> Home -> Scan CCTV dan state layar.
- `docs/multi-camera-plan.md` - profil kamera, satu sesi aktif, peta per kamera.
- `docs/table-mapping-plan.md` - editor poligon dan kontrak `setTables`.
- `docs/adr/0009-video-pipeline.md` - keputusan pipeline preview yang masih terbuka.
- `docs/rust-scaffold.md` - kontrak FFI dan packaging `libonnxruntime.so`.
- `docs/data-contracts.md` - JSON meja dan status engine.

## 1. Masalah yang diselesaikan

Sebelum perubahan ini, aplikasi sudah bisa membuka preview Media3 dan menyimpan
peta meja, tetapi:

1. `RustCameraSession.open()` hanya menandai kamera aktif; `Engine.start` tidak
   pernah dipanggil, sehingga `health()` selalu kosong dan total meja `-`.
2. Layar mapping memakai bingkai demo; stream CCTV yang sedang dibuka tidak
   terlihat, sehingga poligon digambar tanpa acuan.
3. Tidak ada frame yang dikirim ke `pushFrame`, sehingga model ONNX tidak pernah
   dipakai walau file model tersedia.
4. Pemilihan player di `CameraPreviewSurface` memakai `remember` satu kali;
   bila komposisi pertama terjadi sebelum `preview.open()` selesai, layar
   selamanya hitam.

## 2. Keputusan

| Keputusan | Konsekuensi |
| --- | --- |
| Urutan: buka sesi -> start engine bila siap -> buka preview | `setTables` boleh dipanggil sebelum `start`; peta tidak hilang bila start gagal. |
| Demo tidak menyentuh Rust | `DemoEngineStatusSource` tetap satu-satunya sumber angka demo. |
| Model/runtime hilang bukan alasan menolak sesi | Mapping dan simpan lokal tetap jalan; total tetap `-` dan `modelReady=false`. |
| `Engine.start` hanya saat rencana `Ready` | Tidak ada panggilan dengan `model_path` kosong atau `tables` kosong yang pasti ditolak Rust. |
| Preview memakai `StateFlow<ExoPlayer?>` | Komposisi mengikuti player yang benar-benar siap; tidak ada layar hitam permanen. |
| Mapping memakai frame beku (snapshot) | Zoom/pan kanvas dan video memakai transform yang sama; poligon sejajar. |
| Frame pump 2 FPS memakai `TextureView.getBitmap()` | Rust tetap satu-satunya pemilik inferensi, poligon, dan debounce. |
| Kegagalan preview tidak memalsukan status | Tanpa frame, engine tetap `Connecting`; Home mengabaikan phase itu selama `framesIn == 0`. |
| Kegagalan preview dilaporkan ke Rust | `StreamFailureReporter` -> `reportStreamError`; tabel langsung `unknown`, tidak menunggu watchdog 5 dtk. `NoNetwork`/`WrongNetwork` tidak dilaporkan karena stream tidak pernah dibuka. |
| Reconnect memakai ladder eksplisit | `ReconnectPolicy` 1-2-4-8-16 dtk, maksimum 5 percobaan; `AuthFailed`/`Protocol` tidak pernah diulang; Wi-Fi putus membatalkan langkah yang tertunda. |

## 3. Urutan start

```text
Pengguna memilih kamera
  -> RustCameraSession.open(cameraId)
       -> stop engine lama (satu sesi)
       -> activeCameraId = cameraId
       -> baca CameraProfile dari Room
       -> Demo? -> selesai (tanpa Rust)
       -> baca StoredTableConfig + path model/runtime
       -> EngineStartPlanner
            Ready       -> Engine.start(config)
            NoTables    -> selesai (engine tetap stop)
            Unavailable -> selesai (alasan internal, tanpa path sensitif)
  -> Media3CameraPreview.open(profile)
  -> Home menampilkan preview + total dari health()
```

Aturan penting:

- Gagal `Engine.start` tidak mengubah hasil sesi menjadi `Failed`; sesi tetap
  `Opened` agar pengguna bisa memetakan meja dan menyimpan.
- Start berikutnya dicoba lagi saat kamera yang sama dibuka ulang setelah peta
  tersimpan; `open` yang sama tidak memulai engine ganda.
- `applyTables` tetap menolak kamera yang bukan sesi aktif dan tetap memanggil
  `setTables` walau engine berhenti.

## 4. Rencana start murni

`EngineStartPlanner` (JVM murni, diuji di host) menerima:

- `CameraProfile`
- `StoredTableConfig?`
- path model absolut atau `null`
- path `libonnxruntime.so` absolut atau `null`

Hasil:

| Kondisi | Hasil |
| --- | --- |
| `CameraSource.Demo` | `Skipped(Demo)` |
| Tidak ada peta atau peta kosong | `Skipped(NoTables)` |
| Model tidak ada | `Unavailable("Model ONNX belum tersedia")` |
| Runtime tidak ada | `Unavailable("ONNX Runtime belum tersedia")` |
| JSON gagal dibuat | `Unavailable("Konfigurasi meja tidak valid")` |
| Semua siap | `Ready(EngineConfig)` |

Nilai tetap: `inferenceFps = 2.0`, `debounceMs = 2000`. Keduanya berasal dari
`docs/performance.md` dan `rust-core/stream-engine/src/frame_sampler.rs`. Alasan
tidak ada path sensitif: pesan hanya menyebut jenis komponen yang hilang.

## 5. Sumber path model dan runtime

- Model: `assets/models/yolov11n.onnx`. Bila asset ada, disalin sekali ke
  `filesDir/models/yolov11n.onnx`; bila tidak ada, fungsi mengembalikan `null`
  tanpa membuat file kosong. Model tidak di-commit.
- Runtime: `File(applicationInfo.nativeLibraryDir, "libonnxruntime.so")`. File
  harus benar-benar ada; AAR `com.microsoft.onnxruntime:onnxruntime-android`
  (1.32.0) menyediakan library untuk `arm64-v8a`, `armeabi-v7a`, dan `x86_64`.
- `ai-engine::configure_library_path` hanya menerima satu path per proses; path
  `nativeLibraryDir` stabil selama proses hidup, dan proses baru dibuat setelah
  update APK.

## 6. Preview dan snapshot mapping

1. Route mapping menerima slot preview dari `app` (Media3 tetap di luar fitur).
2. Selama belum ada snapshot, slot menampilkan stream hidup dan tombol
   **Bekukan gambar**.
3. Tombol memakai `TextureView.getBitmap()` pada `PlayerView` (`preview_player.xml`,
   `surface_type="texture_view"`), lalu menyerahkan `ImageBitmap` ke
   `TableMappingViewModel.setSnapshot`. `TextureView` dipilih karena aplikasi
   memakai `FLAG_SECURE`, yang dapat menggagalkan `PixelCopy` pada `SurfaceView`.
4. Kanvas menggambar snapshot dengan `MappingTransform` yang sama dengan
   poligon; pinch/pan 1..8x bekerja pada gambar dan poligon sekaligus.
5. **Ambil ulang** membuang snapshot dan kembali ke stream hidup.
6. Ukuran video dari `Player.Listener.onVideoSizeChanged` dikirim ke
   `setLiveFrame(width, height, canonicalAspectRatio)`; banner recalibration
   memakai rasio ini.
7. Kamera demo tidak memakai slot preview; kanvas tetap memakai bingkai demo.

Snapshot adalah UI state sementara: tidak disimpan ke Room, tidak masuk JSON,
dan hilang saat layar ditutup.

## 7. Frame pump ke Rust

- Interval 500 ms (2 FPS) selama permukaan video terlihat.
- Sebelum menangkap, pump membaca `health()`; frame hanya dikirim bila
  `cameraId` cocok dan `modelReady=true`.
- Bitmap diskalakan ke lebar maksimum 640 px, lalu dikemas RGB888.
- `Engine.pushFrame(width, height, rgb)` dipanggil di dispatcher background;
  error `NotRunning`/`Frame` diabaikan (sesi mungkin baru ditutup).
- Pump berhenti saat komposisi preview dilepas, kamera ditutup, atau logout.
- Pump tidak menggantikan decoder Rust (ADR 0009); ia hanya menjembatani
  preview Media3 ke kontrak FFI yang sudah ada.

## 8. Status yang jujur

- Home tetap memakai `PreviewState` Media3 untuk video.
- Phase engine hanya menimpa status video bila `framesIn > 0`; phase
  `Connecting` tanpa frame tidak boleh menghapus label LIVE.
- Total meja hanya muncul bila health cocok dengan kamera aktif dan bukan
  `Failed`; sebelum ada frame, semua meja `unknown`.
- `unknown` tidak pernah dilipat menjadi kosong.
- Kegagalan model/runtime tampil sebagai `-` dan `modelReady=false`, bukan nol.

## 9. Test host

| Modul | Test | Cakupan |
| --- | --- | --- |
| `app` | `EngineStartPlannerTest` | demo, tanpa peta, model hilang, runtime hilang, siap, nilai config |
| `app` | `Rgb888Test` | packing ARGB ke RGB888 tanpa membawa alpha |
| `feature:home` | `HomeViewModelTest` | phase engine tanpa frame tidak menimpa LIVE; frame membuat engine berwenang |
| `feature:home` | `HomeReconnectTest` + `ReconnectPolicyTest` | backoff 1-2-4-8-16 dtk, reset setelah live, `AuthFailed` tidak pernah diulang, kegagalan dilaporkan ke engine dan total basi dibuang |
| `app` | `RustStreamFailureReporterTest` | `StreamErrorKind` -> `StreamStatus` lewat `Engine(NoHandle)`, transport tidak dilaporkan, `NotRunning`/library hilang ditelan |
| `feature:tables` | `TableMappingScreenTest` | slot preview tampil, snapshot menggantikan preview, ambil ulang |
| `feature:tables` | `TableMappingSnapshotTest` | snapshot disetel/dibersihkan dan reset saat ganti kamera |

Test perangkat wajib terpisah untuk: stream RTSP nyata, capture `TextureView`
pada perangkat, model ONNX di perangkat, dan akurasi hitungan.

## 10. Risiko dan batas

| Risiko | Mitigasi |
| --- | --- |
| Model tidak ada di repo | Planner menghasilkan `Unavailable`; UI jujur, tidak crash. |
| AAR ONNX Runtime belum terbukti teremas | `assembleDebug` memeriksa packaging; verifikasi `nativeLibraryDir` menunggu perangkat. |
| Capture `TextureView` gagal pada perangkat tertentu | Tombol menampilkan pesan dan tidak mengubah state; stream hidup tetap tampil. |
| Frame pump menambah beban CPU | 2 FPS, lebar maksimum 640 px, berhenti saat preview tidak terlihat. |
| Dua permukaan video aktif | Satu destinasi aktif pada satu waktu; pump membatalkan job sebelumnya. |
| Rotasi memicu capture ulang | Snapshot hilang saat layar dibuat ulang; pengguna membekukan gambar lagi. |
| Kredensial bocor lewat error | Pesan planner tidak memuat path atau URL; redaksi tetap dipakai. |

## 11. Kriteria penerimaan

- [x] Planner dan test JVM hijau.
- [x] `Engine.start` dipanggil dari `RustCameraSession` untuk kamera RTSP yang
      punya peta dan model/runtime.
- [x] Preview mapping menampilkan stream hidup dan bisa dibekukan menjadi
      backdrop kanvas.
- [x] `pushFrame` menerima frame 2 FPS saat model siap.
- [x] Total tidak berubah menjadi nol saat model belum ada.
- [x] Kegagalan preview dilaporkan ke Rust (`reportStreamError`) dan total basi tidak ditampilkan lagi.
- [x] Frame pump berhenti saat preview tidak lagi `Playing`, sehingga watchdog Rust tidak diberi makan frame beku.
- [x] `assembleDebug` dan `lintDebug` hijau.
- [ ] Stream RTSP nyata dan capture `TextureView` diverifikasi di perangkat.
- [ ] Akurasi model dan hitungan meja diverifikasi di lapangan.

## 12. Skill yang dipakai

`using-chrisbanes-skills`, `compose-state-and-effects`, `compose-component-design`,
`kotlin-api-design`, `kotlin-concurrency-and-flow`, `compose-performance`,
`compose-ui-testing-patterns`, `adaptive`, `edge-to-edge`, `gradle-run`,
`android-jni-security`.
