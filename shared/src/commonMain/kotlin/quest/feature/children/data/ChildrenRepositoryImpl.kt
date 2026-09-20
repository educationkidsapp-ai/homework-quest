package quest.feature.children.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.UpdateChildRequest
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
        val merged = when {
            remote == null -> local
            remote.isEmpty() && local.isNotEmpty() -> { local.forEach { c -> runCatching { api.updateChild(c.id, UpdateChildRequest(c.name, c.avatarColor, c.curriculum, c.grade, c.languages)) } }; local }  // fake API restarted: re-register
            else -> remote.also { list -> list.forEach { remember(it) } }
        }
        val currentId = settings.currentChildId.value
        _current.value = merged.firstOrNull { it.id == currentId } ?: merged.firstOrNull()?.also { settings.setCurrentChild(it.id) }
        return merged
    }

    override suspend fun children(): List<Child> = db.read { selectChildren(uid()).executeAsList() }.map { it.toDomain(schoolOf(it.id)) }

    override suspend fun create(request: CreateChildRequest): Child {
        val child = api.createChild(request)
        remember(child)
        return child
    }

    override suspend fun update(id: String, request: UpdateChildRequest): Child {
        val child = api.updateChild(id, request)
        remember(child)
        if (_current.value?.id == id) _current.value = child
        return child
    }

    override suspend fun delete(id: String) {
        runCatching { api.deleteChild(id) }
        db.write { deleteChild(id) }
        settings.set(schoolKey(id), null)
        settings.set(sectionKey(id), null)
        if (_current.value?.id == id) { _current.value = null; settings.setCurrentChild(null) }
    }

    override suspend fun select(id: String) {
        _current.value = db.read { selectChild(id).executeAsOneOrNull() }?.toDomain(schoolOf(id))
        settings.setCurrentChild(id)
    }

    override suspend fun clear() { _current.value = null }

    override suspend fun sectionName(childId: String): String? = settings.get(sectionKey(childId))?.takeIf { it.isNotBlank() }

    override suspend fun rememberSection(childId: String, name: String?) = settings.set(sectionKey(childId), name?.takeIf { it.isNotBlank() })

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

    private suspend fun schoolOf(childId: String): String = settings.get(schoolKey(childId))?.takeIf { it.isNotBlank() } ?: "default"

    private fun quest.core.db.QuestQueries.save(c: Child) = upsertChild(c.id, uid(), c.name, c.avatarColor, c.curriculum.name.lowercase(), c.grade.toLong(), c.languages.joinToString(","))
    private fun quest.core.db.Child.toDomain(schoolId: String) = Child(id, name, avatarColor, Curriculum.valueOf(curriculum.uppercase()), grade.toInt(), languages.split(',').filter { it.isNotBlank() }, schoolId)

    private companion object {
        fun schoolKey(childId: String) = "school.child.$childId"
        fun sectionKey(childId: String) = "section.child.$childId"
    }
}
