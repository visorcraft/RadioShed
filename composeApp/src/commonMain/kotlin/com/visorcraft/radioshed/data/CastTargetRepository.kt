package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.CastTarget
import com.visorcraft.radioshed.domain.CastTargetType
import kotlinx.coroutines.withContext
import com.visorcraft.radioshed.db.Cast_target as CastTargetRow

class CastTargetRepository(private val db: RadioShedDatabase) {
    suspend fun list(): List<CastTarget> =
        withContext(IODispatcher) {
            db.castQueries.selectAllCastTargets().executeAsList().map(::toDomain)
        }

    suspend fun listPaired(): List<CastTarget> =
        withContext(IODispatcher) {
            db.castQueries.selectPairedCastTargets().executeAsList().map(::toDomain)
        }

    suspend fun listAutoCast(): List<CastTarget> =
        withContext(IODispatcher) {
            db.castQueries.selectAutoCastTargets().executeAsList().map(::toDomain)
        }

    suspend fun getById(id: Long): CastTarget? =
        withContext(IODispatcher) {
            db.castQueries.selectCastTargetById(id).executeAsOneOrNull()?.let(::toDomain)
        }

    suspend fun getByAddress(address: String): CastTarget? =
        withContext(IODispatcher) {
            db.castQueries.selectCastTargetByAddress(address).executeAsOneOrNull()?.let(::toDomain)
        }

    suspend fun upsert(
        type: CastTargetType,
        name: String,
        address: String,
        capabilitiesJson: String = "{}",
        isPaired: Boolean = false,
        autoCast: Boolean = false,
        transcodingBitrateKbps: Int? = null,
    ) = withContext(IODispatcher) {
        val now = nowMillis()
        db.castQueries.upsertCastTarget(
            target_type = type.name,
            name = name,
            address = address,
            capabilities_json = capabilitiesJson,
            last_seen_at = now,
            is_paired = if (isPaired) 1L else 0L,
            auto_cast = if (autoCast) 1L else 0L,
            transcoding_bitrate_kbps = transcodingBitrateKbps?.toLong(),
            created_at = now,
            updated_at = now,
        )
    }

    suspend fun setPaired(
        id: Long,
        paired: Boolean,
    ) = withContext(IODispatcher) {
        db.castQueries.setCastTargetPaired(if (paired) 1L else 0L, nowMillis(), id)
    }

    suspend fun setAutoCast(
        id: Long,
        autoCast: Boolean,
    ) = withContext(IODispatcher) {
        db.castQueries.setCastTargetAutoCast(if (autoCast) 1L else 0L, nowMillis(), id)
    }

    suspend fun setTranscodingBitrate(
        id: Long,
        bitrateKbps: Int?,
    ) = withContext(IODispatcher) {
        db.castQueries.setCastTargetTranscodingBitrate(bitrateKbps?.toLong(), nowMillis(), id)
    }

    suspend fun delete(id: Long) =
        withContext(IODispatcher) {
            db.castQueries.deleteCastTarget(id)
        }

    private fun toDomain(row: CastTargetRow): CastTarget =
        CastTarget(
            id = row.id,
            type = parseType(row.target_type),
            name = row.name,
            address = row.address,
            capabilitiesJson = row.capabilities_json,
            lastSeenAt = row.last_seen_at,
            isPaired = row.is_paired == 1L,
            autoCast = row.auto_cast == 1L,
            transcodingBitrateKbps = row.transcoding_bitrate_kbps?.toInt(),
            createdAt = row.created_at,
            updatedAt = row.updated_at,
        )

    private fun parseType(name: String): CastTargetType =
        runCatching { CastTargetType.valueOf(name) }.getOrDefault(CastTargetType.Other)
}
