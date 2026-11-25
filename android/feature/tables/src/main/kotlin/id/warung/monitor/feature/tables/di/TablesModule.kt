package id.warung.monitor.feature.tables.di

/**
 * The concrete repository and engine sink are provided by `:app`, because that
 * module owns the Room database and the generated UniFFI bindings.
 *
 * This file stays as the feature's extension point: add `@Binds` declarations
 * here only for interfaces whose implementation also lives in this module.
 */
internal object TablesModuleMarker