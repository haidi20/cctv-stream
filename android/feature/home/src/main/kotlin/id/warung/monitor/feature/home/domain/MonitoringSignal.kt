package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.TableSummary

/**
 * One sample of what the Home screen is currently showing.
 *
 * Detection stays in Rust; this only mirrors the rendered state so the app can
 * keep a notification honest while the screen is off.
 */
data class MonitoringSample(
    val stream: StreamUiState,
    val summary: TableSummary?,
)

/**
 * Hands the current stream state to the app shell, which owns the foreground
 * service. Home never starts a service itself, and a JVM test uses the no-op
 * default.
 */
fun interface MonitoringSignal {
    fun publish(sample: MonitoringSample)
}