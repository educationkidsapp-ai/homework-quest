package quest.feature.content.data

import quest.api.dto.ExamWindow
import quest.api.dto.IslandState
import quest.api.dto.IslandKind
import quest.api.dto.Island
import quest.api.samples.MathSeed
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
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.UnreadCount

/**
 * In-app stand-in for the Spring Boot API: serves the §6 seed lessons, keeps children and attempts in
 * memory, assembles the map with the shared [MapAssembler], and simulates network delays.
 */
/**
 * @param persisted attempts the app already uploaded in earlier sessions (the fake is in-memory; a real server keeps them).
 */
class FakeContentApi(
    private val auth: AuthProvider, private val delayMillis: Long = 350, private val persisted: suspend (String) -> List<AttemptUpload> = { emptyList() }, private val today: () -> LocalDate = { Today.date() },
    /** The fake server's clock — the one an exam window is read against, as on the real server. */
    private val now: () -> Long = { Today.epochMillis() },
) : ContentApi, SchoolApi {
    // ---- §8: one exam, with the server's rules -----------------------------------------------------------------------
    // [FakeExam] is open from an hour before the fake started until two hours after, so a build without a server
    // always has an exam to sit. Tests move the window and re-open it the way a teacher would.
    var examOpensAt: Long = now() - 3_600_000L
    var examClosesAt: Long = now() + 2 * 3_600_000L
    private val examSubmitted = mutableSetOf<String>()
    private val examReopened = mutableSetOf<String>()

    /** `POST /teacher/exams/{id}/reopen/{childId}`: one more sitting for this child; her answers so far are kept. */
    fun reopenExam(childId: String) { examReopened += childId; examSubmitted -= childId }

    /** The answers the fake server holds for the exam, by stop. */
    suspend fun examAnswers(childId: String): Map<String, AttemptUpload> = allAttempts(childId).filter { it.lessonId == FakeExam.LESSON_ID }.associateBy { it.stopId }
    fun examIsSubmitted(childId: String): Boolean = childId in examSubmitted

    private fun examOpenFor(childId: String): Boolean = childId in examReopened || now() in examOpensAt until examClosesAt

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
        val assembled = MapAssembler.assemble(child, Seeds.summaries, completions.map { LessonCompletionInfo(it.lessonId, it.level, it.stars, it.total, it.mostTwo) }, review, emptyMap(), from, to, today())
        // As `MapService.withExamWindows`: the exam is on the map only while this child may sit it, and carries the
        // exam's own window (not a re-opening's).
        if (!examOpenFor(childId)) return assembled
        val exam = Island(
            "island-${FakeExam.LESSON_ID}", IslandKind.LESSON, today(), if (childId in examSubmitted) IslandState.DONE else IslandState.TODAY,
            FakeExam.lesson.title, FakeExam.lesson.subject, FakeExam.LESSON_ID, FakeExam.lesson.version, examWindow = ExamWindow(examOpensAt, examClosesAt, level = "1"),
        )
        return assembled.copy(islands = assembled.islands + exam)
    }

    override suspend fun lesson(id: String, version: Int?): PublishedLesson {
        net()
        // B3 parity: the exam is never released on the fake server, so its paper always arrives sealed.
        return Seeds.byId(id) ?: FakeExam.lesson.takeIf { it.id == id }?.sealedForChild() ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No lesson $id"))
    }

    override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck {
        net()
        // §8, as `ExamAttemptService.open`: checked before a single row is written, and a refusal refuses the batch.
        if (attempts.any { it.lessonId == FakeExam.LESSON_ID }) {
            if (!examOpenFor(childId)) throw ApiException(ApiError(ApiError.EXAM_CLOSED, "\"${FakeExam.lesson.title}\" is not open right now."))
            if (childId in examSubmitted) throw ApiException(ApiError(ApiError.EXAM_ALREADY_TAKEN, "\"${FakeExam.lesson.title}\" has already been handed in."))
        }
        mutex.withLock { this.attempts.getOrPut(childId) { mutableListOf() }.addAll(attempts) }
        // The paper is handed in when its last question is answered.
        if (attempts.any { it.lessonId == FakeExam.LESSON_ID } && examAnswers(childId).keys.containsAll(FakeExam.lesson.examPlay!!.stops.map { it.id })) examSubmitted += childId
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

    override suspend fun schoolFlags(schoolId: String): Map<String, Boolean> { net(); return DEFAULT_FLAGS + ("chat" to true) + ("announcements" to true) }

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

    // ---- M8 parity: B6's complaints, apart from Messages (`FakeComplaints`).
    // Its messages name M7's uploads (`fakeUploads`, below) by id, as the server's do.
    private val fakeComplaints = FakeComplaints(parentId = { uid() }, now = now, upload = { fakeUploads[it] })

    override suspend fun complaints(childId: String, status: String?): quest.api.dto.ComplaintList { net(); return fakeComplaints.list(childId, status) }
    override suspend fun complaintRecipients(childId: String): List<quest.api.dto.ComplaintRecipient> { net(); return fakeComplaints.recipients() }
    override suspend fun createComplaint(childId: String, request: quest.api.dto.CreateComplaintRequest): quest.api.dto.ComplaintDetail { net(); return fakeComplaints.create(childId, request) }
    override suspend fun complaint(childId: String, complaintId: String, before: String?, since: String?, limit: Int?): quest.api.dto.ComplaintDetail { net(); return fakeComplaints.detail(childId, complaintId, since) }
    override suspend fun sendComplaintMessage(childId: String, complaintId: String, request: SendChatMessageRequest): ChatMessage { net(); return fakeComplaints.reply(childId, complaintId, request) }
    override suspend fun markComplaintRead(childId: String, complaintId: String): ChatReadReceipt { net(); return fakeComplaints.markRead(childId, complaintId) }
    override suspend fun reopenComplaint(childId: String, complaintId: String): quest.api.dto.Complaint { net(); return fakeComplaints.reopen(childId, complaintId) }

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

    // ---- B3 parity: the parent's notification rows, so the Notifications tab runs without a server.
    private val fakeNotificationReads = mutableSetOf<String>()
    private fun fakeNotifications(): List<NotificationView> = listOf(
        NotificationView("nt-result", NotificationKind.EXAM_RELEASED, "Exam result released", "Autumn maths test", "/children/$MAYA/progress", lessonId = "exam-1", createdAt = 1_758_460_000_000L, childId = MAYA),
        // M8: B6's two complaint kinds a parent receives, linked to the complaint's own page.
        NotificationView("nt-cp-msg", NotificationKind.COMPLAINT_MESSAGE, "Ms. Nour replied to your complaint", "Thank you for telling us. I am checking the route with the transport team today.", "/children/$MAYA/complaints/cp-bus", lessonId = "cp-bus", createdAt = 1_758_465_000_000L, childId = MAYA),
        NotificationView("nt-cp-status", NotificationKind.COMPLAINT_STATUS, "Your complaint was resolved", "Homework is too long every night", "/children/$MAYA/complaints/cp-homework", lessonId = "cp-homework", createdAt = 1_758_455_000_000L, childId = MAYA),
        NotificationView("nt-msg", NotificationKind.CHAT_MESSAGE, "Message from Ms. Sara", "Maya did very well today.", "/children/$MAYA/chat/t-sara", createdAt = 1_758_450_000_000L, childId = MAYA),
        NotificationView("nt-hw", NotificationKind.HOMEWORK_PUBLISHED, "New homework", "Counting by 2s", "/children/$MAYA/map", lessonId = "l1", createdAt = 1_758_440_000_000L, childId = MAYA),
        // M5: one row of each newer kind B4 writes, so every tap target can be tried without a server.
        NotificationView("nt-plan", NotificationKind.BROADCAST_POSTED, "This week's plan", "Grade 1", "/children/$MAYA/broadcasts?open=bc-plan-${weekStartOf(today())}", lessonId = "bc-plan-${weekStartOf(today())}", createdAt = 1_758_435_000_000L, childId = MAYA),
        NotificationView("nt-ann", NotificationKind.BROADCAST_POSTED, "Announcement from Ms. Lina", "Library books are due on Thursday.", "/children/$MAYA/broadcasts?open=bc-ann", lessonId = "bc-ann", createdAt = 1_758_430_000_000L, childId = MAYA),
        NotificationView("nt-note", NotificationKind.ANNOUNCEMENT_POSTED, "Class note from Ms. Sara", "Please send a water bottle with your child every day this week — we are outside for PE.", "/children/$MAYA/announcements?open=an-1", lessonId = "an-1", createdAt = 1_758_425_000_000L, childId = MAYA),
        NotificationView("nt-exam", NotificationKind.EXAM_PUBLISHED, "New exam: Counting exam", "Open today 08:00–10:00", "/children/$MAYA/map", lessonId = FakeExam.LESSON_ID, createdAt = 1_758_420_000_000L, childId = MAYA),
        ).map { if (it.id in fakeNotificationReads) it.copy(readAt = 1_758_470_000_000L) else it }

    override suspend fun notifications(unread: Boolean?, limit: Int?): List<NotificationView> {
        net()
        return fakeNotifications().filter { unread != true || it.readAt == null }.take(limit ?: 20)
    }

    override suspend fun unreadNotificationCount(): UnreadCount { net(); return UnreadCount(fakeNotifications().count { it.readAt == null }) }

    override suspend fun markNotificationRead(id: String): NotificationView {
        net()
        fakeNotificationReads += id
        return fakeNotifications().firstOrNull { it.id == id } ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such notification."))
    }

    override suspend fun markAllNotificationsRead(): UnreadCount { net(); fakeNotificationReads += fakeNotifications().map { it.id }; return UnreadCount(0) }

    /** B4: the push tokens the app registered, by token — what `/me/devices` would hold for this parent. */
    val devices = mutableMapOf<String, quest.api.dto.RegisterDeviceRequest>()
    override suspend fun registerDevice(request: quest.api.dto.RegisterDeviceRequest) { net(); devices[request.token] = request }
    override suspend fun unregisterDevice(token: String) { net(); devices.remove(token) }

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
        // B6: a complaint is its own conversation now; Messages refuses the old way of opening one.
        if (request.topic == ChatTopic.COMPLAINT) throw ApiException(ApiError(ApiError.COMPLAINT_MOVED, "Complaints are opened with POST /children/{id}/complaints."))
        val list = fakeMessages.getOrPut(key) { mutableListOf() }
        val msg = ChatMessage(
            id = "m-${Ids.random()}",
            threadId = "th-$childId-$teacherId",
            sender = ChatSender.PARENT,
            senderId = uid(),
            body = request.body.trim(),
            createdAt = 1_758_451_000_000L,
            attachments = request.attachmentIds.orEmpty().mapNotNull { fakeUploads[it] },
        )
        list.add(msg)
        return msg
    }

    /** M7 (B5): uploads by id, so a send's `attachmentIds` come back on the message as the server's would. No bytes are kept. */
    private val fakeUploads = mutableMapOf<String, quest.api.dto.ChatAttachment>()

    override suspend fun uploadChatAttachment(childId: String, file: quest.api.UploadFile): quest.api.dto.AttachmentRef {
        net()
        val ref = quest.api.dto.AttachmentRef(id = "att-${Ids.random()}", name = file.fileName, type = file.mimeType, sizeBytes = file.bytes.size.toLong())
        fakeUploads[ref.id] = quest.api.dto.ChatAttachment(ref.id, ref.type, ref.name, ref.sizeBytes)
        return ref
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
        /** The first linked child, whom the fake's notification rows are about. */
        const val MAYA = "fake-child-1"

        /**
         * Al Noor's colours, chosen the way the server's validator demands: [SchoolTheme.primary] is a light brand
         * surface (dark ink reads on it), [SchoolTheme.accent] is dark enough to read on [SchoolTheme.ground], and
         * `mascotColor` clears 3:1 on the ground. Nothing here is validated on the device — this is only the shape a
         * themed school has, so the app can be exercised without a backend.
         */
        val alNoorTheme = SchoolTheme(
            logoUrl = null,
            appName = "Al Noor School",
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
            Child(MAYA, "Maya", "sky", Curriculum.BRITISH, 1),
            Child("fake-child-2", "Omar", "mint", Curriculum.BRITISH, 4),
        )

        /** What `GET /platform-settings` answers without a backend. */
        val platformDefaults = PlatformSettings(name = "MySchool", shortName = "MySchool")
    }
}

/**
 * §8: the exam the fake API serves — the counting lesson's first level sat as a paper, under its own id so its answers
 * never mix with the homework's.
 */
object FakeExam {
    const val LESSON_ID = "lesson-fake-exam"
    val lesson: PublishedLesson = MathSeed.lesson.copy(id = LESSON_ID, title = "Counting exam", type = "exam", hintsOff = true, numbersOff = true, examPlay = MathSeed.lesson.plays[0])
}
