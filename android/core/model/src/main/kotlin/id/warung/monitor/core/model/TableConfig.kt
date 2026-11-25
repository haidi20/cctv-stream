package id.warung.monitor.core.model

/** Stable identifier shared with the server and the Rust engine. */
@JvmInline
value class TableId(val value: String) {
    init {
        require(value.isNotBlank()) { "Table id must not be blank" }
        require(!value.contains(',')) { "Table id must not contain a comma" }
    }

    override fun toString(): String = value
}

/** Human readable table name. The id stays unchanged when this is edited. */
@JvmInline
value class TableLabel(val value: String) {
    init {
        require(value.isNotBlank()) { "Table label must not be blank" }
        require(value.length <= MAX_LENGTH) { "Table label is too long" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 40

        /** Falls back to the id when the stored label is blank, matching Rust. */
        fun orFallback(label: String?, id: TableId): TableLabel {
            val candidate = label?.trim().orEmpty()
            if (candidate.isEmpty()) return TableLabel(id.value)
            return TableLabel(candidate.take(MAX_LENGTH))
        }
    }
}

/**
 * A validated table footprint. Instances are only created by [PolygonValidator],
 * so an invalid polygon cannot reach the JSON encoder or the engine.
 */
class TablePolygon private constructor(val points: List<NormalizedPoint>) {

    val area: Double = PolygonValidator.signedArea(points).let { kotlin.math.abs(it) }

    val centroid: NormalizedPoint = PolygonValidator.centroid(points)

    fun contains(point: NormalizedPoint): Boolean = PolygonValidator.contains(points, point)

    fun quantized(): TablePolygon = ofValidated(points.map { it.quantized() })

    override fun equals(other: Any?): Boolean =
        this === other || (other is TablePolygon && other.points == points)

    override fun hashCode(): Int = points.hashCode()

    override fun toString(): String = "TablePolygon(points=$points)"

    companion object {
        /** Builds a polygon, returning `null` when it would be rejected. */
        fun orNull(points: List<NormalizedPoint>): TablePolygon? =
            when (val result = PolygonValidator.validate(points)) {
                is PolygonValidation.Valid -> result.polygon
                is PolygonValidation.Invalid -> null
            }

        /**
         * Internal constructor for inputs already validated by [PolygonValidator].
         * Callers in this module only; kept private to the file contract by convention.
         */
        internal fun ofValidated(points: List<NormalizedPoint>): TablePolygon =
            TablePolygon(points.toList())
    }
}

/** One table definition in a configuration. */
data class TableConfig(
    val id: TableId,
    val label: TableLabel,
    val polygon: TablePolygon,
) {
    fun quantized(): TableConfig = copy(polygon = polygon.quantized())
}

/** A complete configuration at one point in time. */
data class TableConfigSet(
    val frame: MappingFrame,
    val tables: List<TableConfig>,
) {
    init {
        require(tables.map { it.id.value }.toSet().size == tables.size) {
            "Table ids must be unique"
        }
    }

    fun table(id: TableId): TableConfig? = tables.firstOrNull { it.id == id }

    /** Returns a copy with every coordinate rounded to four decimals. */
    fun quantized(): TableConfigSet = copy(tables = tables.map { it.quantized() })

    companion object {
        val EMPTY: TableConfigSet = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = emptyList(),
        )
    }
}