package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.PolygonValidation
import id.warung.monitor.core.model.PolygonValidator
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.core.model.TablePolygon

/**
 * An editable table that has not necessarily passed validation yet.
 *
 * Points are quantised before validation and before conversion to
 * [TableConfig], so the editor accepts exactly what Rust will accept.
 */
data class TableDraft(
    val id: TableId,
    val label: String,
    val points: List<NormalizedPoint>,
) {
    val displayLabel: String get() = label.ifBlank { id.value }

    val validation: PolygonValidation
        get() = PolygonValidator.validate(points.map { it.quantized() })

    val isValid: Boolean get() = validation is PolygonValidation.Valid

    val problemText: String?
        get() = when (val result = validation) {
            is PolygonValidation.Valid -> null
            is PolygonValidation.Invalid -> result.detail
        }

    /** Returns a validated config, or `null` when the draft is not saveable. */
    fun toConfig(): TableConfig? {
        val quantised = points.map { it.quantized() }
        val polygon: TablePolygon = when (val result = PolygonValidator.validate(quantised)) {
            is PolygonValidation.Valid -> result.polygon
            is PolygonValidation.Invalid -> return null
        }
        return TableConfig(
            id = id,
            label = TableLabel.orFallback(label, id),
            polygon = polygon,
        )
    }
}