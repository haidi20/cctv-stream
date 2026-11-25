package id.warung.monitor.feature.cameras.session

import id.warung.monitor.core.model.CameraId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryCameraSessionTest {

    private val a = CameraId("cam-a")
    private val b = CameraId("cam-b")

    @Test
    fun opening_a_camera_makes_it_active() = runTest {
        val session = InMemoryCameraSession()
        assertEquals(CameraSessionResult.Opened, session.open(a))
        assertEquals(a, session.activeCameraId.value)
    }

    @Test
    fun reopening_the_same_camera_does_not_close_it() = runTest {
        val session = InMemoryCameraSession()
        session.open(a)
        session.open(a)

        assertEquals(0, session.closeCount)
        assertEquals(a, session.activeCameraId.value)
    }

    @Test
    fun opening_another_camera_closes_the_previous_session_first() = runTest {
        val session = InMemoryCameraSession()
        session.open(a)
        session.open(b)

        assertEquals(1, session.closeCount)
        assertEquals(b, session.activeCameraId.value)
    }

    @Test
    fun closing_the_session_clears_the_active_camera() = runTest {
        val session = InMemoryCameraSession()
        session.open(a)
        session.close()

        assertNull(session.activeCameraId.value)
    }

    @Test
    fun closing_twice_is_safe() = runTest {
        val session = InMemoryCameraSession()
        session.open(a)
        session.close()
        session.close()

        assertNull(session.activeCameraId.value)
    }
}