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

    /**
     * The section a child sits in, as the parent's device knows it (D16 slice 3). It is written when the child is
     * added with a class join code and read by the child list, so a placed child reads "1A British" rather than only
     * "British · Grade 1".
     *
     * It is a device-side note, not the truth: `Child` carries no `classId`, so a child the *teacher* later places —
     * or moves — is not reflected here until the server answers with the section. See the M1 report.
     */
    suspend fun sectionName(childId: String): String?
    suspend fun rememberSection(childId: String, name: String?)
}

class AddChildUseCase(private val repo: ChildrenRepository) {
    suspend operator fun invoke(request: CreateChildRequest): Child {
        require(request.name.isNotBlank()) { "Please enter a name." }
        require(request.grade in 1..3) { "Grade must be 1, 2 or 3." }
        return repo.create(request).also { repo.select(it.id) }
    }
}
