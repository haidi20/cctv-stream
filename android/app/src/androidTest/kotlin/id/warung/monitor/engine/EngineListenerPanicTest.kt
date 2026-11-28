package id.warung.monitor.engine

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineEvent
import id.warung.monitor.core.EngineListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Kotlin listener that throws must not stop later listeners or corrupt the
 * engine. Rust catches the panic at the FFI boundary and logs it; this test
 * proves the observable half of that contract on the shipped `.so`.
 */
class EngineListenerPanicTest {

    @Test
    fun a_throwing_listener_does_not_break_the_engine_or_later_listeners() {
        val engine = Engine()
        val observed = mutableListOf<EngineEvent>()
        try {
            engine.subscribe(object : EngineListener {
                override fun onEvent(event: EngineEvent) {
                    throw IllegalStateException("listener exploded")
                }
            })
            engine.subscribe(object : EngineListener {
                override fun onEvent(event: EngineEvent) {
                    observed += event
                }
            })

            // Saving a map on a stopped engine is the first-run path; Rust
            // publishes a stream event, which reaches every listener.
            engine.setTables(FIXTURE)

            assertTrue("the next listener must still receive events", observed.isNotEmpty())
            assertEquals(1, engine.tables().size)
            engine.stop()
        } finally {
            engine.clearListeners()
            engine.close()
        }
    }

    private companion object {
        const val FIXTURE = """
            {
              "version": 2,
              "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
              "tables": [
                {
                  "id": "meja-mock",
                  "label": "Meja Mock",
                  "polygon": [
                    { "x": 0.2, "y": 0.2 },
                    { "x": 0.4, "y": 0.2 },
                    { "x": 0.4, "y": 0.4 },
                    { "x": 0.2, "y": 0.4 }
                  ]
                }
              ]
            }
        """
    }
}
