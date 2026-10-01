package quest.feature.children.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.feature.children.domain.ChildrenRepository

class ChildrenRepositoryImpl(private val api: ContentApi, private val db: Db, private val settings: SettingsStore, private val auth: AuthProvider) : ChildrenRepository {
    private val _current = MutableStateFlow<Child?>(null)
    override val currentChild: StateFlow<Child?> = _current

    private fun uid() = (auth.state.value as? AuthState.SignedIn)?.uid ?: ""

    override suspend fun refresh(): List<Child> {
        val local = children()
        val remote = runCatching { api.listChildren() }.getOrNull()
        // The server is the truth about who is linked: a child the admin unlinked leaves the device too. Only when it
        // cannot be reached does the cached list stand in, so an offline launch still opens on the child's home.
        val merged = if (remote == null) local else {
            local.filter { old -> remote.none { it.id == old.id } }.forEach { forget(it.id) }
            remote.onEach { remember(it) }
        }
        val currentId = settings.currentChildId.value
        _current.value = merged.firstOrNull { it.id == currentId } ?: merged.firstOrNull()?.also { settings.setCurrentChild(it.id) }
        return merged
    }

    override suspend fun children(): List<Child> = db.read { selectChildren(uid()).executeAsList() }.map { it.toDomain(schoolOf(it.id)) }

    override suspend fun select(id: String) {
        _current.value = db.read { selectChild(id).executeAsOneOrNull() }?.toDomain(schoolOf(id))
        settings.setCurrentChild(id)
    }

    override suspend fun clear() { _current.value = null }

    /**
     * Writes the row and, beside it, the child's school (§2). The `Child` table predates tenancy and has no
     * `schoolId` column; a settings entry per child avoids a schema change that existing installs have no migration
     * for, and it is read exactly once per child. Without it an offline launch would theme the app as the default
     * school until `listChildren()` came back.
     */
    private suspend fun remember(c: Child) {
        db.write { save(c) }
        settings.set(schoolKey(c.id), c.schoolId.takeIf { it.isNotBlank() })
    }

    private suspend fun forget(childId: String) {
        db.write { deleteChild(childId) }
        settings.set(schoolKey(childId), null)
    }

    private suspend fun schoolOf(childId: String): String = settings.get(schoolKey(childId))?.takeIf { it.isNotBlank() } ?: "default"

    private fun quest.core.db.QuestQueries.save(c: Child) = upsertChild(c.id, uid(), c.name, c.avatarColor, c.curriculum.name.lowercase(), c.grade.toLong(), c.languages.joinToString(","))
    private fun quest.core.db.Child.toDomain(schoolId: String) = Child(id, name, avatarColor, Curriculum.valueOf(curriculum.uppercase()), grade.toInt(), languages.split(',').filter { it.isNotBlank() }, schoolId)

    private companion object {
        fun schoolKey(childId: String) = "school.child.$childId"
    }
}
