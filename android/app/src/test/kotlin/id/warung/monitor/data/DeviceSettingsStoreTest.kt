package id.warung.monitor.data

import id.warung.monitor.core.database.DeviceSettingsDao
import id.warung.monitor.core.database.DeviceSettingsEntity
import id.warung.monitor.engine.EngineStartPlanner
import id.warung.monitor.engine.EngineTuning
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store is the seam between Room and the pure tuning policy.
 *
 * The behaviour that matters is that a null p95 survives the round trip: a
 * device that never sampled a frame must not look like a device that measured
 * zero latency, because zero would be read as "infinitely fast".
 */
class DeviceSettingsStoreTest {

    private class RecordingDao(var row: DeviceSettingsEntity? = null) : DeviceSettingsDao {
        var saves = 0

        override suspend fun settings(): DeviceSettingsEntity? = row

        override suspend fun save(row: DeviceSettingsEntity) {
            saves += 1
            this.row = row
        }

        override suspend fun clear() {
            row = null
        }
    }

    @Test
    fun a_store_with_no_row_reports_no_measurement() = runTest {
        val store = RoomDeviceSettingsStore(RecordingDao(), clock = { 99L })

        assertNull(store.load())
    }

    @Test
    fun a_saved_tuning_round_trips_with_its_latency() = runTest {
        val dao = RecordingDao()
        val store = RoomDeviceSettingsStore(dao, clock = { 99L })

        store.save(
            StoredDeviceSettings(
                tuning = EngineTuning(inferenceFps = 1.5, intraThreads = 3u),
                p95InferenceMs = 450L,
            ),
        )

        val loaded = store.load()
        assertEquals(1.5, loaded?.tuning?.inferenceFps ?: 0.0, 0.0)
        assertEquals(3u, loaded?.tuning?.intraThreads)
        assertEquals(450L, loaded?.p95InferenceMs)
        assertEquals("the clock is injected so the row is reproducible", 99L, dao.row?.updatedAt)
    }

    @Test
    fun an_unmeasured_latency_is_saved_as_null_and_read_back_as_null() = runTest {
        val dao = RecordingDao()
        val store = RoomDeviceSettingsStore(dao, clock = { 1L })

        store.save(StoredDeviceSettings(tuning = EngineTuning.Default, p95InferenceMs = null))

        assertNull(dao.row?.p95InferenceMs)
        assertNull(store.load()?.p95InferenceMs)
    }

    @Test
    fun saving_twice_overwrites_the_single_row() = runTest {
        val dao = RecordingDao()
        val store = RoomDeviceSettingsStore(dao, clock = { 1L })

        store.save(StoredDeviceSettings(EngineTuning(2.0, 2u), 296L))
        store.save(StoredDeviceSettings(EngineTuning(1.0, 1u), 640L))

        assertEquals(2, dao.saves)
        assertEquals(1.0, store.load()?.tuning?.inferenceFps ?: 0.0, 0.0)
    }

    @Test
    fun a_stored_row_is_re_derived_against_the_current_profile_ceiling() = runTest {
        // A hot session once measured 640 ms, so the row says 1 FPS. On the
        // next start the lite ceiling is 1.5 FPS, and the measurement is still
        // slow, so the rate stays at the 1 FPS floor instead of jumping back up.
        val stored = StoredDeviceSettings(EngineTuning(1.0, 2u), p95InferenceMs = 640L)

        val tuned = EngineTuning.of(
            p95Ms = stored.p95InferenceMs,
            requestedFps = EngineStartPlanner.LITE_INFERENCE_FPS,
            requestedThreads = stored.tuning.intraThreads,
        )

        assertEquals(1.0, tuned.inferenceFps, 0.0)
    }
}
