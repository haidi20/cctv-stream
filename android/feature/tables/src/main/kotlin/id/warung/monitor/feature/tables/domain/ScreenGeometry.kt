package id.warung.monitor.feature.tables.domain

/** A point in viewport pixels. Kept free of Compose types so domain tests are JVM-only. */
data class ScreenPoint(val x: Float, val y: Float)

/** Viewport size in pixels. */
data class ScreenSize(val width: Float, val height: Float)

/** Pan offset in viewport pixels, never in frame pixels. */
data class ScreenPan(val x: Float, val y: Float) {
    companion object {
        val None: ScreenPan = ScreenPan(0f, 0f)
    }
}