package id.warung.monitor.engine

import id.warung.monitor.core.EngineException
import id.warung.monitor.feature.tables.engine.TableApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rejected map must be identified by the FFI error type, not by its wording. */
class EngineErrorClassificationTest {

    @Test
    fun a_typed_tables_exception_is_rejected_even_without_a_keyword_in_its_reason() {
        val result = classifyTableApplyError(EngineException.Tables("polygon titiknya kurang"))

        assertTrue(result is TableApplyResult.Rejected)
        assertEquals("polygon titiknya kurang", (result as TableApplyResult.Rejected).reason)
    }

    @Test
    fun a_config_exception_stays_unavailable_even_when_its_reason_mentions_tables() {
        val result = classifyTableApplyError(EngineException.Config("tables tidak bisa dipakai"))

        assertTrue(result is TableApplyResult.Unavailable)
    }

    @Test
    fun an_untyped_error_still_uses_the_tables_keyword_fallback() {
        assertTrue(classifyTableApplyError(IllegalStateException("tables: polygon cacat")) is TableApplyResult.Rejected)
        assertTrue(classifyTableApplyError(IllegalStateException("config: session")).let {
            it is TableApplyResult.Unavailable && it.reason == "config: session"
        })
    }
}
