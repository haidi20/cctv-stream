package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.PolygonProblem
import id.warung.monitor.core.model.PolygonValidation
import id.warung.monitor.core.model.PolygonValidator
import id.warung.monitor.core.model.TableId

/** Result of an edit that must keep the polygon usable. */
sealed interface PolygonEditResult {
    data class Success(val points: List<NormalizedPoint>) : PolygonEditResult
    data class Rejected(val problem: PolygonProblem, val message: String) : PolygonEditResult
}

/**
 * Pure polygon editing used by the ViewModel.
 *
 * Edits that would leave a complete polygon self-intersecting or below the
 * minimum area are rejected instead of being applied and flagged later, so the
 * canvas never shows a footprint the engine would refuse.
 */
object PolygonEditor {

    const val MAX_VERTICES: Int = 64
    const val MIN_VERTICES: Int = 3

    private val idPattern = Regex("^meja-(\\d+)$")

    /** Smallest unused `meja-NN`, starting at `meja-01`. */
    fun nextId(existing: Collection<TableId>): TableId {
        val used = existing.mapNotNull { idPattern.matchEntire(it.value)?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()
        var candidate = 1
        while (candidate in used) candidate++
        return TableId("meja-%02d".format(candidate))
    }

    fun addVertex(points: List<NormalizedPoint>, point: NormalizedPoint): PolygonEditResult {
        if (points.size >= MAX_VERTICES) {
            return PolygonEditResult.Rejected(
                PolygonProblem.TOO_FEW_POINTS,
                "Meja maksimal $MAX_VERTICES titik",
            )
        }
        val candidate = points + point.quantized()
        return validateCandidate(candidate)
    }

    fun moveVertex(
        points: List<NormalizedPoint>,
        index: Int,
        point: NormalizedPoint,
    ): PolygonEditResult {
        if (index !in points.indices) {
            return PolygonEditResult.Rejected(PolygonProblem.TOO_FEW_POINTS, "Titik tidak ditemukan")
        }
        val candidate = points.toMutableList().also { it[index] = point.quantized() }
        return validateCandidate(candidate)
    }

    fun removeVertex(points: List<NormalizedPoint>, index: Int): PolygonEditResult {
        if (index !in points.indices) {
            return PolygonEditResult.Rejected(PolygonProblem.TOO_FEW_POINTS, "Titik tidak ditemukan")
        }
        if (points.size - 1 < MIN_VERTICES) {
            return PolygonEditResult.Rejected(
                PolygonProblem.TOO_FEW_POINTS,
                "Poligon minimal $MIN_VERTICES titik",
            )
        }
        val candidate = points.toMutableList().also { it.removeAt(index) }
        return validateCandidate(candidate)
    }

    private fun validateCandidate(points: List<NormalizedPoint>): PolygonEditResult {
        if (points.size < MIN_VERTICES) return PolygonEditResult.Success(points)
        return when (val result = PolygonValidator.validate(points)) {
            is PolygonValidation.Valid -> PolygonEditResult.Success(points)
            is PolygonValidation.Invalid -> PolygonEditResult.Rejected(result.problem, result.detail)
        }
    }
}