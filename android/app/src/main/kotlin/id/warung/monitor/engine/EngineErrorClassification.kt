package id.warung.monitor.engine

import id.warung.monitor.core.EngineException
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.feature.tables.engine.TableApplyResult

/** Classifies a native failure using typed FFI variants before message text. */
internal fun classifyTableApplyError(error: Throwable): TableApplyResult {
    if (error is EngineException.Tables) {
        return TableApplyResult.Rejected(Redaction.message(error.reason))
    }
    if (error is EngineException.Config) {
        return TableApplyResult.Unavailable(Redaction.message(error.reason))
    }
    val message = Redaction.message(error.message ?: error.javaClass.simpleName)
    val rejected = message.contains("tables", ignoreCase = true)
    return if (rejected) TableApplyResult.Rejected(message) else TableApplyResult.Unavailable(message)
}
