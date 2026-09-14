package quest.feature.children.domain

import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.UpdateChildRequest

/** The parent's children on this device; one is "current" (whose map and PIN the app shows). */
interface ChildrenRepository {
    val currentChild: StateFlow<Child?>
    suspend fun refresh(): List<Child>
    suspend fun children(): List<Child>
    suspend fun create(request: CreateChildRequest): Child
    suspend fun update(id: String, request: UpdateChildRequest): Child
    suspend fun delete(id: String)
    suspend fun select(id: String)
    suspend fun clear()
}

class AddChildUseCase(private val repo: ChildrenRepository) {
    suspend operator fun invoke(request: CreateChildRequest): Child {
        require(request.name.isNotBlank()) { "Please enter a name." }
        require(request.grade in 1..3) { "Grade must be 1, 2 or 3." }
        return repo.create(request).also { repo.select(it.id) }
    }
}
