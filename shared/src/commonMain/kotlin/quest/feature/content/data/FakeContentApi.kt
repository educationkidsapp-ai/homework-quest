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
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.BroadcastAttachment
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.Child
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
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
import kotlinx.datetime.minus
import quest.feature.broadcasts.domain.weekStartOf
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

    /**
     * The children the school linked to this parent. The app has no Add child (the admin adds them), so without a
     * backend the fake plays the admin: a parent it has not seen before is given [linkedChildren], with fixed ids so
     * the progress cached on the device still belongs to them after a restart.
     */
    override suspend fun listChildren(): List<Child> { net(); return mutex.withLock { children.getOrPut(uid()) { linkedChildren.toMutableList() }.toList() } }

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
    // One fake themed school; every other school id is the platform default (`DEFAULT_FLAGS` and the token theme).

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

    /** The topic the first message of a thread carried, so the fake's rows label a complaint the way the server does. */
    private val fakeTopics = mutableMapOf<String, ChatTopic>()

    private val fakeTeachers = listOf(
        Triple("t-sara", "Ms. Sara", "Math"),
        Triple("t-noor", "Ms. Noor", "English"),
    )

    /** R4: the coordinators of the subjects taught in the child's section — not teachers of hers, so listed apart. */
    private val fakeCoordinators = listOf(
        Triple("co-lina", "Ms. Lina", "Math"),
        Triple("co-omar", "Mr. Omar", "English, Science"),
    )

    /** RM2 (DR5): the manager of the department the child's section is in. She holds no subject — a department is not one. */
    private val fakeManagers = listOf("mg-nour" to "Ms. Nour")

    private fun fakeRow(childId: String, staffId: String, staffName: String, subject: String?, role: ChatStaffRole): ChatThread {
        val msgs = fakeMessages["$childId:$staffId"].orEmpty()
        return ChatThread(
            id = if (msgs.isEmpty()) null else "th-$childId-$staffId",
            childId = childId,
            childName = "Maya",
            teacherId = staffId,
            teacherName = staffName,
            className = "1A British",
            subject = subject,
            unread = msgs.count { it.sender == ChatSender.TEACHER && it.readAt == null },
            lastMessage = msgs.lastOrNull(),
            staffRole = role,
            topic = fakeTopics["$childId:$staffId"] ?: ChatTopic.QUESTION,
            status = ChatThreadStatus.OPEN,
        )
    }

    override suspend fun chatThreads(childId: String): List<ChatThread> {
        net()
        val teachers = fakeTeachers.map { (id, name, subj) -> fakeRow(childId, id, name, subj, ChatStaffRole.TEACHER) }
        // A coordinator appears in the parent's list only once a thread with her exists, as `ChatService` does it.
        val coordinators = fakeCoordinators
            .map { (id, name, subj) -> fakeRow(childId, id, name, subj, ChatStaffRole.COORDINATOR) }
            .filter { it.id != null }
        return teachers + coordinators
    }

    override suspend fun parentCoordinators(childId: String): List<ChatThread> {
        net()
        return fakeCoordinators.map { (id, name, subj) -> fakeRow(childId, id, name, subj, ChatStaffRole.COORDINATOR) }
    }

    override suspend fun childManagers(childId: String): List<ChatThread> {
        net()
        return fakeManagers.map { (id, name) -> fakeRow(childId, id, name, null, ChatStaffRole.MANAGERIAL) }
    }

    // ---- RM4: the parent's broadcasts feed, so the screen has something to draw without a server.
    private val fakeReads = mutableSetOf<String>()

    /**
     * MH1's shape: a plan is one grade's week as an image, so the fake carries an `attachments` reference with a type.
     * No `FakeAttachmentImages` serves the bytes — there is no image to invent — so the page draws its Try again state,
     * which is the honest offline answer and the one worth seeing without a server.
     */
    private fun fakePlan(week: String, grade: Int, read: Boolean, pdf: Boolean = false) = BroadcastView(
        id = "bc-plan-$week", kind = BroadcastKind.WEEKLY_PLAN, authorId = "mg-nour", authorName = "Ms. Nour",
        authorRole = ChatStaffRole.MANAGERIAL, title = "Weekly plan · Grade $grade · week of $week", weekStart = week,
        bodyEn = "Weekly plan · Grade $grade · week of $week",
        curriculum = Curriculum.BRITISH, grade = grade,
        // M1: a plan is an image or a PDF; the fake serves one of each kind so both cards exist without a backend.
        attachment = if (pdf) BroadcastAttachment("/media/attachments/att-$week", "Weekly plan grade $grade.pdf", "att-$week", "application/pdf")
        else BroadcastAttachment("/media/attachments/att-$week", "week-plan.png", "att-$week", "image/png"),
        createdAt = 1_758_500_000_000L, read = read,
    )

    /**
     * MH3 `GET /children/{id}/weekly-plans` — this week and the two before it, so the page has a pinned plan and an
     * archive to collapse without a server.
     */
    override suspend fun childWeeklyPlans(childId: String, from: String?, to: String?): quest.api.dto.WeeklyPlanArchive {
        net()
        val weeks = (0..2).map { back -> weekStartOf(today()).minus(back * 7, kotlinx.datetime.DateTimeUnit.DAY).toString() }
        val plans = weeks.mapIndexed { i, week -> fakePlan(week, grade = 1, read = "bc-plan-$week" in fakeReads, pdf = i == 1) }
        return quest.api.dto.WeeklyPlanArchive(
            from = weeks.last(), to = weeks.first(), unread = plans.count { !it.read },
            weeks = plans.map { quest.api.dto.WeeklyPlanWeek(it.weekStart!!, listOf(quest.api.dto.WeeklyPlanEntry(it))) },
        )
    }

    /** MH1 `GET|PATCH /parent/me` — one profile for the fake's single signed-in parent. */
    private var fakePhone: String? = "+971501234567"

    override suspend fun parentProfile(): quest.api.dashboard.ParentProfile {
        net()
        return quest.api.dashboard.ParentProfile("p-fake", "parent@example.com", fakePhone)
    }

    override suspend fun updateParentProfile(phone: String?): quest.api.dashboard.ParentProfile {
        net()
        fakePhone = phone?.takeIf { it.isNotBlank() }
        return quest.api.dashboard.ParentProfile("p-fake", "parent@example.com", fakePhone)
    }

    private fun fakeBroadcasts(): List<BroadcastView> {
        return listOf(
            BroadcastView(
                id = "bc-ann", kind = BroadcastKind.ANNOUNCEMENT, authorId = "co-lina", authorName = "Ms. Lina",
                authorRole = ChatStaffRole.COORDINATOR, title = "New number lines",
                bodyEn = "We have put number lines on every desk — practise counting back from 20 at home.",
                bodyAr = "وضعنا خطوط الأعداد على كل مقعد — تدرّبوا على العدّ التنازلي من 20 في البيت.",
                subject = "math", createdAt = 1_758_400_000_000L, read = "bc-ann" in fakeReads,
            ),
            BroadcastView(
                id = "bc-event", kind = BroadcastKind.EVENT, authorId = "mg-nour", authorName = "Ms. Nour",
                authorRole = ChatStaffRole.MANAGERIAL, title = "Sports day",
                bodyEn = "Sports day is on the last Thursday of the month. Parents are welcome.",
                curriculum = Curriculum.BRITISH, createdAt = 1_758_300_000_000L, read = "bc-event" in fakeReads,
            ),
        )
    }

    override suspend fun childBroadcasts(childId: String): BroadcastFeed {
        net()
        val items = fakeBroadcasts()
        return BroadcastFeed(unread = items.count { !it.read }, items = items)
    }

    override suspend fun markBroadcastRead(childId: String, broadcastId: String): BroadcastView {
        net()
        fakeReads += broadcastId
        val week = broadcastId.removePrefix("bc-plan-")
        if (week != broadcastId) return fakePlan(week, grade = 1, read = true)
        return fakeBroadcasts().firstOrNull { it.id == broadcastId }
            ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such broadcast."))
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
        // M1: `topic: complaint` is accepted on any message, to a teacher, a coordinator or a manager alike, and turns
        // the thread into a complaint from there; any other topic only labels the message that creates the thread.
        val topic = request.topic
        if (topic == ChatTopic.COMPLAINT || (list.isEmpty() && topic != null)) fakeTopics[key] = topic
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
        /** The school code `createChild` places a child in the themed school for. */
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

        /** Two sections of the fake school, which `createChild` places a child in by class join code (§2). */
        val sections: Map<String, ClassLookup> = listOf(
            ClassLookup(classId = "al-noor:british:1:1a", name = "1A British", grade = 1, curriculum = Curriculum.BRITISH, schoolName = "Al Noor School"),
            ClassLookup(classId = "al-noor:american:1:1a", name = "1A American", grade = 1, curriculum = Curriculum.AMERICAN, schoolName = "Al Noor School"),
        ).let { mapOf("CLASS1" to it[0], "CLASS2" to it[1]) }

        /** The two children the fake "admin" linked to every parent — two grades, so the picker and the switcher have something to switch. */
        val linkedChildren = listOf(
            Child("fake-child-1", "Maya", "sky", Curriculum.BRITISH, 1),
            Child("fake-child-2", "Omar", "mint", Curriculum.BRITISH, 4),
        )

        /** What `GET /platform-settings` answers without a backend. */
        val platformDefaults = PlatformSettings(name = "Homework Quest", shortName = "Quest")
    }
}
