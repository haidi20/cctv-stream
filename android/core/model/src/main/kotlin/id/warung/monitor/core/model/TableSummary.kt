package id.warung.monitor.core.model

/**
 * Aggregate table counts published by the Rust engine.
 *
 * `unknown` is a first-class bucket, never folded into [available]. A camera
 * that is offline or whose model is missing must not look like an empty
 * restaurant.
 */
data class TableSummary(
    val available: Int,
    val occupied: Int,
    val unknown: Int,
) {
    init {
        require(available >= 0) { "available must not be negative" }
        require(occupied >= 0) { "occupied must not be negative" }
        require(unknown >= 0) { "unknown must not be negative" }
    }

    /** Tables with a known state (available or occupied). */
    val known: Int get() = available + occupied

    /** Every configured table, including those that cannot be observed. */
    val total: Int get() = known + unknown
}