package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.DjEngine
import com.visorcraft.radioshed.domain.DjVoice
import com.visorcraft.radioshed.domain.EqBand
import com.visorcraft.radioshed.domain.EqOutputBinding
import com.visorcraft.radioshed.domain.EqPreset
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** EQ presets, per-output bindings, and DJ voices. */
class AudioSettingsRepository(private val db: RadioShedDatabase) {

    // ---- EQ presets ----

    suspend fun listEqPresets(): List<EqPreset> = withContext(IODispatcher) {
        db.audioSettingsQueries.selectAllEqPresets().executeAsList().map { row ->
            EqPreset(
                id = row.id,
                name = row.name,
                bands = decodeBands(row.bands_json),
                preAmpDb = row.pre_amp_db,
                isBuiltIn = row.is_built_in == 1L,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }
    }

    suspend fun getEqPreset(id: Long): EqPreset? = withContext(IODispatcher) {
        db.audioSettingsQueries.selectEqPresetById(id).executeAsOneOrNull()?.let { row ->
            EqPreset(
                id = row.id,
                name = row.name,
                bands = decodeBands(row.bands_json),
                preAmpDb = row.pre_amp_db,
                isBuiltIn = row.is_built_in == 1L,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }
    }

    suspend fun insertEqPreset(
        name: String,
        bands: List<EqBand>,
        preAmpDb: Double = 0.0,
        isBuiltIn: Boolean = false,
    ): Long = withContext(IODispatcher) {
        val now = nowMillis()
        db.audioSettingsQueries.insertEqPreset(
            name = name,
            bands_json = encodeBands(bands),
            is_built_in = if (isBuiltIn) 1L else 0L,
            pre_amp_db = preAmpDb,
            created_at = now,
            updated_at = now,
        ).executeAsOne()
    }

    suspend fun updateEqPreset(id: Long, name: String, bands: List<EqBand>, preAmpDb: Double) =
        withContext(IODispatcher) {
            db.audioSettingsQueries.updateEqPreset(name, encodeBands(bands), preAmpDb, nowMillis(), id)
        }

    suspend fun deleteEqPreset(id: Long) = withContext(IODispatcher) {
        db.audioSettingsQueries.deleteEqPreset(id)
    }

    // ---- Per-output EQ bindings ----

    suspend fun bindEqToOutput(outputKey: String, eqPresetId: Long?) = withContext(IODispatcher) {
        db.audioSettingsQueries.bindEqToOutput(outputKey, eqPresetId, nowMillis())
    }

    suspend fun getEqBinding(outputKey: String): EqOutputBinding? = withContext(IODispatcher) {
        db.audioSettingsQueries.getEqBindingForOutput(outputKey).executeAsOneOrNull()?.let { row ->
            EqOutputBinding(
                outputKey = row.output_key,
                eqPresetId = row.eq_preset_id,
                boundAt = row.bound_at,
            )
        }
    }

    suspend fun listEqBindings(): List<EqOutputBinding> = withContext(IODispatcher) {
        db.audioSettingsQueries.selectAllEqBindings().executeAsList().map { row ->
            EqOutputBinding(
                outputKey = row.output_key,
                eqPresetId = row.eq_preset_id,
                boundAt = row.bound_at,
            )
        }
    }

    suspend fun unbindEq(outputKey: String) = withContext(IODispatcher) {
        db.audioSettingsQueries.unbindEqOutput(outputKey)
    }

    // ---- DJ voices ----

    suspend fun listDjVoices(): List<DjVoice> = withContext(IODispatcher) {
        db.audioSettingsQueries.selectAllDjVoices().executeAsList().map { row ->
            DjVoice(
                id = row.id,
                name = row.name,
                engine = parseEngine(row.engine),
                voiceId = row.voice_id,
                rate = row.rate,
                pitch = row.pitch,
                isDefault = row.is_default == 1L,
                createdAt = row.created_at,
            )
        }
    }

    suspend fun defaultDjVoice(): DjVoice? = withContext(IODispatcher) {
        db.audioSettingsQueries.selectDefaultDjVoice().executeAsOneOrNull()?.let { row ->
            DjVoice(
                id = row.id,
                name = row.name,
                engine = parseEngine(row.engine),
                voiceId = row.voice_id,
                rate = row.rate,
                pitch = row.pitch,
                isDefault = row.is_default == 1L,
                createdAt = row.created_at,
            )
        }
    }

    suspend fun insertDjVoice(
        name: String,
        engine: DjEngine,
        voiceId: String,
        rate: Double = 1.0,
        pitch: Double = 1.0,
        isDefault: Boolean = false,
    ): Long = withContext(IODispatcher) {
        db.audioSettingsQueries.insertDjVoice(
            name = name,
            engine = engine.name,
            voice_id = voiceId,
            rate = rate,
            pitch = pitch,
            is_default = if (isDefault) 1L else 0L,
            created_at = nowMillis(),
        ).executeAsOne()
    }

    suspend fun setDefaultDjVoice(id: Long) = withContext(IODispatcher) {
        db.audioSettingsQueries.setDefaultDjVoice(id)
    }

    suspend fun deleteDjVoice(id: Long) = withContext(IODispatcher) {
        db.audioSettingsQueries.deleteDjVoice(id)
    }

    private fun parseEngine(name: String): DjEngine =
        runCatching { DjEngine.valueOf(name) }.getOrDefault(DjEngine.System)

    private fun encodeBands(bands: List<EqBand>): String =
        RadioShedJson.encodeToString(ListSerializer(EqBand.serializer()), bands)

    private fun decodeBands(json: String): List<EqBand> =
        if (json.isBlank()) emptyList()
        else RadioShedJson.decodeFromString(ListSerializer(EqBand.serializer()), json)
}
