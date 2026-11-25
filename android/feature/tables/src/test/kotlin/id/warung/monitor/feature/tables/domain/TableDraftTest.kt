package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDraftTest {

    @Test
    fun blank_label_falls_back_to_the_id() {
        val draft = TableDraft(TableId("meja-04"), "  ", emptyList())
        assertEquals("meja-04", draft.displayLabel)
    }

    @Test
    fun an_incomplete_draft_is_not_convertible() {
        val draft = TableDraft(
            TableId("meja-01"),
            "Meja 01",
            listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.2, 0.2)),
        )
        assertFalse(draft.isValid)
        assertNull(draft.toConfig())
    }

    @Test
    fun a_valid_square_converts_with_a_quantised_polygon() {
        val draft = TableDraft(
            TableId("meja-01"),
            "Meja 01",
            listOf(
                NormalizedPoint(0.20001, 0.2),
                NormalizedPoint(0.4, 0.2),
                NormalizedPoint(0.4, 0.4),
                NormalizedPoint(0.2, 0.4),
            ),
        )
        assertTrue(draft.isValid)
        val config = draft.toConfig()
        assertNotNull(config)
        requireNotNull(config)
        assertEquals(0.2, config.polygon.points.first().x, 0.0)
        assertEquals("Meja 01", config.label.value)
    }
}