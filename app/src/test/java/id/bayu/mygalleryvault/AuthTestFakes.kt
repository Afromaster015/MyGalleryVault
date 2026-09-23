package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.data.local.SecurityEventEntity
import id.bayu.mygalleryvault.data.local.SettingEntity
import id.bayu.mygalleryvault.data.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared in-memory fakes for auth-related unit tests. */
internal class FakeSettingsDao : id.bayu.mygalleryvault.data.local.SettingsDao {
    val map = HashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override fun observeAll(): Flow<List<SettingEntity>> =
        MutableStateFlow(map.map { SettingEntity(it.key, it.value) })
    override suspend fun put(setting: SettingEntity) { map[setting.key] = setting.value }
    override suspend fun delete(key: String) { map.remove(key) }
}

internal class FakeSecurityEventDao : id.bayu.mygalleryvault.data.local.SecurityEventDao {
    val rows = mutableListOf<SecurityEventEntity>()
    var nextId = 1L
    override fun recent(limit: Int): Flow<List<SecurityEventEntity>> =
        MutableStateFlow(rows.take(limit))
    override suspend fun recentOnce(limit: Int): List<SecurityEventEntity> = rows.take(limit)
    override suspend fun unacknowledgedBreakIns(): List<SecurityEventEntity> =
        rows.filter { it.eventType == AuthRepository.EVENT_BREAKIN_ALERT && !it.acknowledged }
    override suspend fun insert(event: SecurityEventEntity): Long {
        rows.add(event.copy(id = nextId)); return nextId++
    }
    override suspend fun acknowledge(ids: List<Long>) {
        for (i in rows.indices) if (rows[i].id in ids) rows[i] = rows[i].copy(acknowledged = true)
    }
    override suspend fun clear() = rows.clear()
}
