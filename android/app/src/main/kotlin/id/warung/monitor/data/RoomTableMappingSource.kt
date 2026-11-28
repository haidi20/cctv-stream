package id.warung.monitor.data

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.feature.home.domain.SavedTableMap
import id.warung.monitor.feature.home.domain.TableMappingSource
import id.warung.monitor.feature.tables.data.TableConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Room-backed [TableMappingSource].
 *
 * Home needs the map's presence *and* its footprints: the presence gates the
 * analysis panel, and the footprints are drawn as the blue analysis scope so
 * the user can see which part of the frame Rust judges. Table *status* is still
 * never read here. A decode failure surfaces as "no map": the user can redraw
 * it, and a silent blank panel would look like "this warung has no tables".
 */
@Singleton
class RoomTableMappingSource @Inject constructor(
    private val repository: TableConfigRepository,
) : TableMappingSource {

    override fun observe(cameraId: CameraId): Flow<SavedTableMap?> =
        repository.observeActive(cameraId.value)
            .map { stored ->
                stored?.let {
                    SavedTableMap(
                        tables = it.config.tables.size,
                        revision = it.revision,
                        polygons = it.config.tables.map { table -> table.polygon.points },
                    )
                }
            }
            .catch { emit(null) }
}
