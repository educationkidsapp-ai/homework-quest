package quest.feature.content.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
import quest.api.UploadFile
import quest.api.dashboard.ClassLookup
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatThread
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.PlatformSettings
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.SchoolTheme
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.SkillProgress
import quest.api.dto.Stop
import quest.api.dto.StopCategory
import quest.api.dto.UpdateChildRequest
import quest.api.dto.WorldPalette
import quest.feature.content.domain.SchoolApi
import quest.feature.content.domain.ThemeFetch
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
/**
 * @param persisted attempts the app already uploaded in earlier sessions (the fake is in-memory; a real server keeps them).
 */
class FakeContentApi(private val auth: AuthProvider, private val delayMillis: Long = 350, private val persisted: suspend (String) -> List<AttemptUpload> = { emptyList() }, private val today: () -> LocalDate = { Today.date() }) : ContentApi, SchoolApi {
    private val mutex = Mutex()
    private val children = mutableMapOf<String, MutableList<Child>>()          // uid → children
    private val attempts = mutableMapOf<String, MutableList<AttemptUpload>>()  // childId → attempts

    private suspend fun uid(): String = (auth.state.value as? AuthState.SignedIn)?.uid ?: throw ApiException(ApiError(ApiError.UNAUTHORIZED, "Please sign in."))
    private suspend fun net() = delay(delayMillis)

    override suspend fun listChildren(): List<Child> { net(); return mutex.withLock { children[uid()].orEmpty().toList() } }

    override suspend fun createChild(request: CreateChildRequest): Child {
        net()
        // A class join code is the more specific answer: the school, curriculum and grade all come from the section
        // and whatever the form said is ignored, exactly as `ChildService.create` does it.
        val section = request.joinCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { code ->
            sections[code] ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No class with code $code"))
        }
        val school = when {
            section != null -> AL_NOOR_ID
            request.schoolCode?.trim()?.uppercase() == AL_NOOR_CODE -> AL_NOOR_ID
            else -> "default"
        }
        val child = Child(
            Ids.random(), request.name, request.avatarColor,
            section?.curriculum ?: request.curriculum, section?.grade ?: request.grade, request.languages, schoolId = school,
        )
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
        // One review island per weak lesson (its Level-1 variant); attempts are recorded per stop, so bands are per lesson skill set.
        val review = weak.mapNotNull { (skillId, name) ->
            val lesson = Seeds.lessons.firstOrNull { l -> l.skills.any { it.id == skillId } } ?: return@mapNotNull null
            if (completions.any { it.lessonId == lesson.id && it.level == 1 && it.variant == 1 }) return@mapNotNull null
            MapAssembler.ReviewCandidate(skillId, name, lesson.id, "${lesson.id}:1:1")
        }.distinctBy { it.lessonId }
        return MapAssembler.assemble(child, Seeds.summaries, completions.map { LessonCompletionInfo(it.lessonId, it.level, it.stars, it.total, it.mostTwo) }, review, emptyMap(), from, to, today())
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

    // ---- §2 join school, §3 theme, §4 flags, §A platform settings -------------------------------------------------
    // One fake school so the join flow is playable without a backend; every other code is a 404, exactly as the server
    // answers it, and every other school id is the platform default (`DEFAULT_FLAGS` and the token theme).

    override suspend fun schoolByCode(code: String): JoinSchoolInfo {
        net()
        return when (code.trim().uppercase()) {
            AL_NOOR_CODE -> alNoor
            DEFAULT_CODE -> defaultSchool
            else -> throw ApiException(ApiError(ApiError.NOT_FOUND, "No school with code $code"))
        }
    }

    override suspend fun classByJoinCode(code: String): ClassLookup {
        net()
        return sections[code.trim().uppercase()] ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No class with code $code"))
    }

    override suspend fun schoolFlags(schoolId: String): Map<String, Boolean> { net(); return DEFAULT_FLAGS + ("chat" to true) }

    override suspend fun schoolTheme(schoolId: String): SchoolTheme { net(); return if (schoolId == AL_NOOR_ID) alNoorTheme else SchoolTheme() }

    override suspend fun schoolTheme(schoolId: String, ifNoneMatch: String?): ThemeFetch {
        val etag = "\"fake-$schoolId\""
        if (ifNoneMatch == etag) { net(); return ThemeFetch(theme = null, etag = etag, notModified = true) }
        return ThemeFetch(schoolTheme(schoolId), etag)
    }

    override suspend fun platformSettings(): PlatformSettings { net(); return platformDefaults }

    private suspend fun allAttempts(childId: String): List<AttemptUpload> {
        val mem = mutex.withLock { attempts[childId].orEmpty().toList() }
        val ids = mem.map { it.id }.toSet()
        return mem + persisted(childId).filter { it.id !in ids }
    }

    // ---- derived state ----
    private data class Completion(val lessonId: String, val level: Int, val variant: Int, val stars: Int, val total: Int, val mostTwo: Boolean)

    private suspend fun completions(childId: String): List<Completion> {
        val mine = allAttempts(childId)
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
        val mine = allAttempts(childId)
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

    private val fakeMessages = mutableMapOf<String, MutableList<ChatMessage>>()

    override suspend fun chatThreads(childId: String): List<ChatThread> {
        net()
        val defaultTeachers = listOf(
            Triple("t-sara", "Ms. Sara", "Math"),
            Triple("t-noor", "Ms. Noor", "English"),
        )
        return defaultTeachers.map { (tId, tName, subj) ->
            val key = "$childId:$tId"
            val msgs = fakeMessages[key].orEmpty()
            val last = msgs.lastOrNull()
            val unreadCount = msgs.count { it.sender == ChatSender.TEACHER && it.readAt == null }
            ChatThread(
                id = if (msgs.isEmpty()) null else "th-$childId-$tId",
                childId = childId,
                childName = "Maya",
                teacherId = tId,
                teacherName = tName,
                className = "1A British",
                subject = subj,
                unread = unreadCount,
                lastMessage = last,
            )
        }
    }

    override suspend fun chatMessages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> {
        net()
        val key = "$childId:$teacherId"
        val list = fakeMessages.getOrPut(key) {
            mutableListOf(
                ChatMessage(
                    id = "m-seed-1",
                    threadId = "th-$childId-$teacherId",
                    sender = ChatSender.TEACHER,
                    senderId = teacherId,
                    body = "Hello! Let me know if you have any questions about today's lesson.",
                    createdAt = 1_758_450_000_000L,
                    readAt = 1_758_450_500_000L,
                )
            )
        }
        var filtered = list.toList()
        if (since != null) {
            val idx = filtered.indexOfFirst { it.id == since }
            if (idx >= 0) filtered = filtered.drop(idx + 1)
        }
        if (before != null) {
            val idx = filtered.indexOfFirst { it.id == before }
            if (idx >= 0) filtered = filtered.take(idx)
        }
        val lim = limit ?: 50
        return if (filtered.size > lim) filtered.takeLast(lim) else filtered
    }

    override suspend fun sendChatMessage(childId: String, teacherId: String, request: SendChatMessageRequest): ChatMessage {
        net()
        val key = "$childId:$teacherId"
        val list = fakeMessages.getOrPut(key) { mutableListOf() }
        val msg = ChatMessage(
            id = "m-${Ids.random()}",
            threadId = "th-$childId-$teacherId",
            sender = ChatSender.PARENT,
            senderId = uid(),
            body = request.body.trim(),
            createdAt = 1_758_451_000_000L,
        )
        list.add(msg)
        return msg
    }

    override suspend fun markChatRead(childId: String, teacherId: String): ChatReadReceipt {
        net()
        val key = "$childId:$teacherId"
        val list = fakeMessages[key].orEmpty()
        val now = 1_758_452_000_000L
        list.filter { it.sender == ChatSender.TEACHER && it.readAt == null }.forEach {
            val idx = list.indexOf(it)
            if (idx >= 0) (list as MutableList)[idx] = it.copy(readAt = now)
        }
        return ChatReadReceipt("th-$childId-$teacherId", ChatSender.PARENT, now)
    }

    override suspend fun childAttendance(childId: String, from: String?, to: String?): quest.api.dto.ChildAttendanceResponse {
        net()
        val todayStr = today().toString()
        val record = quest.api.dto.ChildAttendanceRecord(date = todayStr, status = "PRESENT", notes = "Great participation in class today!")
        return quest.api.dto.ChildAttendanceResponse(
            records = listOf(record),
            summary = quest.api.dto.ChildAttendanceSummary(totalDays = 1, presentDays = 1, absentDays = 0, lateDays = 0, excusedDays = 0, attendanceRate = 100.0)
        )
    }

    override suspend fun todayAttendance(childId: String): quest.api.dto.ChildAttendanceRecord? {
        net()
        return quest.api.dto.ChildAttendanceRecord(date = today().toString(), status = "PRESENT", notes = "Great participation in class today!")
    }

    companion object {
        /** The one join code the fake answers; anything else is a 404, like the server. */
        const val AL_NOOR_CODE = "ALNOOR"
        const val AL_NOOR_ID = "al-noor"

        /**
         * Al Noor's colours, chosen the way the server's validator demands: [SchoolTheme.primary] is a light brand
         * surface (dark ink reads on it), [SchoolTheme.accent] is dark enough to read on [SchoolTheme.ground], and
         * `mascotColor` clears 3:1 on the ground. Nothing here is validated on the device — this is only the shape a
         * themed school has, so the app can be exercised without a backend.
         */
        val alNoorTheme = SchoolTheme(
            logoUrl = null,
            appName = "Al Noor Quest",
            primary = "#E7F2EC",
            primaryInk = "#13301F",
            accent = "#1F6B4A",
            ground = "#F4F7F4",
            softBorder = "#C9DCD1",
            mascotColor = "#2E7D57",
            worldPalettes = mapOf(
                "math" to WorldPalette(primary = "#7FD1B9", deep = "#2E9E80", soft = "#E6F6F1", ink = "#12261F"),
                "english" to WorldPalette(primary = "#F2C75C", deep = "#C08F1C", soft = "#FDF4DE", ink = "#2A2310"),
            ),
        )

        val alNoor = JoinSchoolInfo(
            name = "Al Noor School",
            logoUrl = null,
            curriculumOptions = listOf(Curriculum.BRITISH, Curriculum.AMERICAN),
            gradeOptions = listOf(1, 2, 3, 4, 5, 6),
            theme = alNoorTheme,
        )

        /**
         * The **default** school also has a code, exactly as QA's acceptance school does (`HQ0001`). It is a school a
         * parent joins with a code and which nonetheless has no theme, and keeping it in the fake is what stops the
         * app from being written as if "joined" and "themed" were the same thing.
         */
        const val DEFAULT_CODE = "HQ0001"

        val defaultSchool = JoinSchoolInfo(
            name = "Default school",
            logoUrl = null,
            curriculumOptions = listOf(Curriculum.BRITISH, Curriculum.AMERICAN),
            gradeOptions = listOf(1, 2, 3, 4, 5, 6),
            theme = null,
        )

        /** Two sections of the fake school, so a class join card is playable without a backend (§2). */
        val sections: Map<String, ClassLookup> = listOf(
            ClassLookup(classId = "al-noor:british:1:1a", name = "1A British", grade = 1, curriculum = Curriculum.BRITISH, schoolName = "Al Noor School"),
            ClassLookup(classId = "al-noor:american:1:1a", name = "1A American", grade = 1, curriculum = Curriculum.AMERICAN, schoolName = "Al Noor School"),
        ).let { mapOf("CLASS1" to it[0], "CLASS2" to it[1]) }

        /** What `GET /platform-settings` answers without a backend. */
        val platformDefaults = PlatformSettings(name = "Homework Quest", shortName = "Quest")
    }
}
