package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.withContext

class WidgetConfigRepository(private val db: RadioShedDatabase) {

    suspend fun upsert(widgetKey: String, widgetType: String, configJson: String = "{}") =
        withContext(IODispatcher) {
            val now = nowMillis()
            db.widgetsQueries.upsertWidgetConfig(widgetKey, widgetType, configJson, now, now)
        }

    suspend fun get(widgetKey: String): WidgetConfigEntry? = withContext(IODispatcher) {
        db.widgetsQueries.selectWidgetConfig(widgetKey).executeAsOneOrNull()?.let { row ->
            WidgetConfigEntry(
                widgetKey = row.widget_key,
                widgetType = row.widget_type,
                configJson = row.config_json,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }
    }

    suspend fun list(): List<WidgetConfigEntry> = withContext(IODispatcher) {
        db.widgetsQueries.selectAllWidgetConfigs().executeAsList().map { row ->
            WidgetConfigEntry(
                widgetKey = row.widget_key,
                widgetType = row.widget_type,
                configJson = row.config_json,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }
    }

    suspend fun delete(widgetKey: String) = withContext(IODispatcher) {
        db.widgetsQueries.deleteWidgetConfig(widgetKey)
    }
}

data class WidgetConfigEntry(
    val widgetKey: String,
    val widgetType: String,
    val configJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
