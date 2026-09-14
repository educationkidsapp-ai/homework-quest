package quest.feature.content.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.UploadFile
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.SkillProgress
import quest.api.dto.Stop
import quest.api.dto.StopCategory
import quest.api.dto.UpdateChildRequest
import quest.api.map.MapAssembler
import quest.api.progress.Band
import quest.api.progress.ProgressBands
import quest.api.samples.Seeds
import quest.api.samples.summary
import quest.core.platform.Ids
import quest.core.platform.Today

/**
 * In-app stand-in for the Spring Boot API: serves the §6 seed lessons, keeps children and attempts in
 * memory, assembles the map with the shared [MapAssembler], and simulates network delays.
 */
class FakeContentApi(private val auth: AuthProvider, private val delayMillis: Long = 350) : ContentApi {
    private val mutex = Mutex()
    private val children = mutableMapOf<String, MutableList<Child>>()          // uid → children
    private val attempts = mutableMapOf<String, MutableList<AttemptUpload>>()  // childId → attempts

    private suspend fun uid(): String = (auth.state.value as? AuthState.SignedIn)?.uid ?: throw ApiException(ApiError(ApiError.UNAUTHORIZED, "Please sign in."))
    private suspend fun net() = delay(delayMillis)

    override suspend fun listChildren(): List<Child> { net(); return mutex.withLock { children[uid()].orEmpty().toList() } }

    override suspend fun createChild(request: CreateChildRequest): Child {
        net()
        val child = Child(Ids.random(), request.name, request.avatarColor, request.curriculum, request.grade, request.languages)
        mutex.withLock { children.getOrPut(uid()) { mutableListOf() } += child }
        return child
    }

    override suspend fun updateChild(id: String, request: UpdateChildRequest): Child {
        net()
        return mutex.withLock {
            val list = children.getOrPut(uid()) { mutableListOf() }
            val i = list.indexOfFirst { it.id == id }
            val old = if (i >= 0) list[i] else Child(id, request.name ?: "", request.avatarColor ?: "sky", request.curriculum ?: quest.api.dto.Curriculum.BRITISH, request.grade ?: 1)
            val updated = old.copy(name = request.name ?: old.name, avatarColor = request.avatarColor ?: old.avatarColor, curriculum = request.curriculum ?: old.curriculum, grade = request.grade ?: old.grade, languages = request.languages ?: old.languages)
            if (i >= 0) list[i] = updated else list += updated
            updated
        }
    }

    override suspend fun deleteChild(id: String) { net(); mutex.withLock { children[uid()]?.removeAll { it.id == id }; attempts.remove(id) } }

    override suspend fun map(childId: String, from: LocalDate, to: LocalDate): MapResponse {
        net()
        val child = mutex.withLock { children.values.flatten().firstOrNull { it.id == childId } } ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "child"))
        val completions = completions(childId)
        val weak = weakSkills(childId)
        val review = weak.mapNotNull { (skillId, name) ->
            val lesson = Seeds.lessons.firstOrNull { l -> l.skills.any { it.id == skillId } } ?: return@mapNotNull null
            if (completions.any { it.lessonId == lesson.id && it.level == 1 && it.variant == 1 }) return@mapNotNull null
            MapAssembler.ReviewCandidate(skillId, name, lesson.id, "${lesson.id}:1:1")
        }
        return MapAssembler.assemble(child, Seeds.summaries, completions.map { LessonCompletionInfo(it.lessonId, it.level, it.stars, it.total, it.mostTwo) }, review, emptyMap(), from, to, Today.date())
    }

    override suspend fun lesson(id: String, version: Int?): PublishedLesson {
        net()
        return Seeds.byId(id) ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No lesson $id"))
    }

    override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck {
        net()
        mutex.withLock { this.attempts.getOrPut(childId) { mutableListOf() }.addAll(attempts) }
        return AttemptAck(attempts.size)
    }

    override suspend fun uploadStopMedia(childId: String, stopId: String, media: UploadFile, kind: MediaKind): MediaRef {
        net(); return MediaRef(Ids.random(), "fake://media/$childId/$stopId", kind)
    }

    override suspend fun progress(childId: String): ProgressResponse {
        net()
        val bands = skillBands(childId)
        val skills = Seeds.lessons.flatMap { l -> l.skills.map { s -> Triple(l, s, bands[s.id]) } }.distinctBy { it.second.id }.map { (l, s, r) ->
            SkillProgress(s.id, s.name, s.subject, r?.first?.name, r?.second?.let(ProgressBands::accuracyWords), r?.third ?: 0, null)
        }
        return ProgressResponse(childId, skills, skills.filter { it.band == Band.NEEDS_ANOTHER_LOOK.name }.map { it.skillId }, 0, null, emptyList())
    }

    // ---- derived state ----
    private data class Completion(val lessonId: String, val level: Int, val variant: Int, val stars: Int, val total: Int, val mostTwo: Boolean)

    private suspend fun completions(childId: String): List<Completion> {
        val mine = mutex.withLock { attempts[childId].orEmpty().toList() }
        return Seeds.lessons.flatMap { lesson ->
            (lesson.plays + lesson.variant).mapNotNull { play ->
                val done = play.stops.map { stop -> mine.filter { it.stopId == stop.id && it.lessonId == lesson.id && it.level == play.level }.maxByOrNull { it.stars } }
                if (done.any { it == null }) null else {
                    val stars = done.sumOf { it!!.stars }
                    Completion(lesson.id, play.level, play.variant, stars, play.stops.size * 3, done.count { it!!.stars >= 2 } * 2 > done.size)
                }
            }
        }
    }

    /** band, accuracy, attempts per skill — from first tries on single-answer stops. */
    private suspend fun skillBands(childId: String): Map<String, Triple<Band, Double, Int>> {
        val mine = mutex.withLock { attempts[childId].orEmpty().toList() }
        return Seeds.lessons.flatMap { l -> l.skills.map { it.id to l } }.associate { (skillId, lesson) ->
            val singleStopIds = (lesson.plays + lesson.variant).flatMap { p -> p.stops.flatMap { s -> listOf(s) + ((s as? Stop.ExitTicket)?.questions ?: emptyList()) } }.filter { it.category == StopCategory.SINGLE }.map { it.id }.toSet()
            val firstTries = mine.filter { it.lessonId == lesson.id && it.stopId in singleStopIds && it.attemptNumber == 1 }.sortedByDescending { it.answeredAt }.map { it.correct }
            val acc = ProgressBands.accuracy(firstTries)
            skillId to (if (acc == null) null else Triple(ProgressBands.band(acc), acc, firstTries.size))
        }.filterValues { it != null }.mapValues { it.value!! }
    }

    private suspend fun weakSkills(childId: String): List<Pair<String, String>> = skillBands(childId).filter { it.value.first == Band.NEEDS_ANOTHER_LOOK }.keys.map { id ->
        id to (Seeds.lessons.flatMap { it.skills }.firstOrNull { it.id == id }?.name ?: id)
    }
}
