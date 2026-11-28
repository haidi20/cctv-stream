package id.warung.monitor.feature.home.domain

import id.warung.monitor.feature.cameras.session.CountingReason

/**
 * One user-facing sentence per reason the engine is not counting.
 *
 * Pure JVM and exhaustive: every [CountingReason] has its own line, so the
 * screen can never collapse "no model" and "no map" into one vague message.
 * The wording lives here rather than in a composable so a host test can assert
 * that all reasons differ (`docs/kotlin-rust-gaps.md` G-02).
 */
object CountingNotice {

    /** Short headline, always prefixed with the same phrase for scannability. */
    fun message(reason: CountingReason): String = when (reason) {
        CountingReason.ModelMissing ->
            "Deteksi tidak aktif: model ONNX belum terpasang."
        CountingReason.RuntimeMissing ->
            "Deteksi tidak aktif: ONNX Runtime tidak termuat di perangkat ini."
        CountingReason.RamTooLow ->
            "Deteksi tidak aktif: RAM perangkat di bawah batas inferensi."
        CountingReason.NoTables ->
            "Deteksi tidak aktif: peta meja belum ada."
        CountingReason.Demo ->
            "Deteksi tidak aktif: kamera demo tidak dianalisa."
        CountingReason.ConfigInvalid ->
            "Deteksi tidak aktif: konfigurasi meja tidak valid."
        CountingReason.StartFailed ->
            "Deteksi tidak aktif: engine gagal dimulai."
    }

    /**
     * Second line that says what the user can do. Only the map case has an
     * action the user can complete on this screen, so the rest are advisory.
     */
    fun hint(reason: CountingReason): String = when (reason) {
        CountingReason.ModelMissing ->
            "Pasang berkas model di aset aplikasi lalu buka kamera ini lagi."
        CountingReason.RuntimeMissing ->
            "Perangkat ini tidak memuat pustaka ONNX; analisa tidak bisa jalan."
        CountingReason.RamTooLow ->
            "Tutup aplikasi lain atau pakai perangkat dengan RAM lebih besar."
        CountingReason.NoTables ->
            "Gambar poligon tiap meja di atas video supaya sistem bisa menilai."
        CountingReason.Demo ->
            "Pilih kamera CCTV asli untuk melihat hitungan meja."
        CountingReason.ConfigInvalid ->
            "Gambar ulang peta meja, lalu simpan lagi."
        CountingReason.StartFailed ->
            "Coba buka ulang kamera; bila tetap gagal, periksa berkas model."
    }

    /** Only a missing map is fixed by drawing polygons, not by installing. */
    fun needsMapping(reason: CountingReason): Boolean = reason == CountingReason.NoTables
}
