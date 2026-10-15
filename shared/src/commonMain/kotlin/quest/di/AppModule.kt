package quest.di

import quest.core.platform.elapsedRealtimeMillis
import quest.feature.parent.domain.UndeliveredExamAnswersUseCase
import quest.feature.today.domain.TodaySnapshotStore
import quest.feature.today.domain.PublishTodayUseCase
import quest.feature.today.domain.ExamWindows
import quest.feature.today.domain.ExamSittingPresenter
import quest.core.platform.Today
import quest.core.platform.platformBiometricAuthenticator
import quest.feature.lock.data.BiometricPreferencesImpl
import quest.feature.lock.domain.BiometricPreferences
import quest.feature.lock.domain.AppLock
import quest.feature.children.domain.SignOutUseCase
import quest.core.platform.DocumentViewer
import quest.feature.broadcasts.domain.AttachmentDocuments
import quest.feature.broadcasts.data.AttachmentDocumentStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import quest.feature.chat.data.ChatRepositoryImpl
import quest.feature.chat.data.ChatSocketClient
import quest.api.UploadFile
import quest.feature.chat.domain.AttachmentUploader
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.data.FileUploadStaging
import quest.feature.chat.data.readBytes
import quest.feature.chat.data.source
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.presentation.ChatConversationViewModel
import quest.feature.chat.presentation.conversationState
import quest.feature.chat.presentation.ChatThreadsViewModel
import quest.feature.broadcasts.data.AttachmentImageStore
import quest.feature.broadcasts.data.BroadcastsRepositoryImpl
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.presentation.BroadcastsViewModel
import quest.feature.broadcasts.presentation.WeeklyPlanViewModel
import quest.feature.chat.presentation.CoordinatorPickerViewModel
import quest.feature.complaints.data.ComplaintsRepositoryImpl
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.presentation.ComplaintViewModel
import quest.feature.complaints.presentation.ComplaintsViewModel
import quest.feature.complaints.presentation.NewComplaintViewModel
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.platformModule
import quest.core.platform.connectivityModule
import quest.core.platform.ServerClock
import quest.feature.content.domain.PendingAnswersSync
import quest.feature.content.domain.ChildResultsUseCase
import quest.feature.notifications.data.ParentUnreadSource
import quest.feature.notifications.domain.ParentBadges
import quest.feature.notifications.domain.UnreadSource
import quest.feature.notifications.domain.NotificationsRepository
import quest.feature.notifications.data.NotificationsRepositoryImpl
import quest.feature.school.domain.Flags
import quest.feature.push.data.ApiPushRegistrar
import quest.feature.push.data.PushPreferencesImpl
import quest.feature.push.domain.NotificationRouter
import quest.feature.push.domain.ParentGate
import quest.core.runCancellable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import quest.feature.push.domain.PushPreferences
import quest.feature.push.domain.PushPrompts
import quest.feature.push.domain.PushRegistrar
import quest.feature.push.domain.PushRegistration
import quest.feature.push.presentation.PushNavigator
import quest.api.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import quest.feature.auth.data.FakeAuth
import quest.feature.auth.data.FirebaseAuth
import quest.feature.auth.data.SessionRestorer
import quest.feature.auth.presentation.SignInViewModel
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.presentation.ChildrenViewModel
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.data.LessonRepositoryImpl
import quest.feature.content.data.MapRepositoryImpl
import quest.feature.content.data.RemoteContentApi
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.MapRepository
import quest.feature.content.domain.SchoolApi
import quest.feature.journey.presentation.JourneyViewModel
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonCompleteViewModel
import quest.feature.journey.presentation.ExamResultViewModel
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.map.presentation.MapViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.translate.domain.TranslationStore
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ProgressReportUseCase
import quest.feature.parent.domain.ReleasedResultsUseCase
import quest.feature.parent.domain.SetPinUseCase
import quest.feature.parent.domain.VerifyPinUseCase
import quest.feature.parent.presentation.CalendarViewModel
import quest.feature.parent.presentation.ParentHomeViewModel
import quest.feature.parent.presentation.PinViewModel
import quest.feature.parent.presentation.ProgressViewModel
import quest.feature.parent.presentation.SettingsViewModel
import quest.feature.rewards.data.RewardsRepositoryImpl
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.UpdateStreakUseCase
import quest.feature.school.data.HttpSchoolLogos
import quest.feature.school.data.SchoolSessionImpl
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.SchoolLogoLoader
import quest.feature.school.domain.SchoolSession
import quest.feature.auth.domain.SessionRoles
import quest.feature.chat.data.ParentConversationSource
import quest.feature.staff.data.FakeStaffApi
import quest.feature.staff.data.RemoteStaffApi
import quest.feature.staff.data.SessionRolesImpl
import quest.feature.staff.data.SessionAuth
import quest.feature.staff.data.StaffAuth
import quest.feature.staff.data.StaffConversationSource
import quest.feature.staff.data.StaffPushRegistrar
import quest.feature.staff.data.StaffSession
import quest.feature.staff.data.StaffThreadsImpl
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.VoiceAnnouncementGateway
import quest.feature.staff.domain.StaffSessionView
import quest.feature.staff.domain.STAFF_CONVERSATION
import quest.feature.staff.domain.StaffThreadKey
import quest.feature.staff.domain.StaffThreads
import quest.feature.staff.data.SettingsCheckInPrefs
import quest.feature.staff.domain.CheckInPrefs
import quest.feature.staff.presentation.AttendanceRequestsViewModel
import quest.feature.staff.presentation.VoiceAnnouncementViewModel
import quest.feature.staff.presentation.CheckInViewModel
import quest.feature.staff.presentation.MyRecordViewModel
import quest.feature.staff.presentation.StaffAccountViewModel
import quest.feature.staff.presentation.StaffMessagesViewModel
import quest.feature.staff.presentation.StaffNotificationsViewModel
import quest.feature.staff.presentation.StaffPasswordViewModel
import quest.feature.staff.presentation.conversationState as staffConversationState
import org.koin.core.qualifier.named
import quest.ui.design.StickerKeys

/** Which [ContentApi] sits behind the interface. Screens never know. */
sealed interface ApiConfig {
    data object Fake : ApiConfig
    /** [firebaseApiKey] is the Firebase Web API key of the environment's project; blank keeps [FakeAuth] (server must run FAKE_AUTH). */
    data class Server(val baseUrl: String, val firebaseApiKey: String = "") : ApiConfig
}

fun apiModule(config: ApiConfig): Module = module {
    single<ApiConfig> { config }
    // Real Firebase Authentication (REST, shared by every platform) when the environment has a key; FakeAuth otherwise.
    single<AuthProvider> {
        val key = (config as? ApiConfig.Server)?.firebaseApiKey.orEmpty()
        // Resolved when the session expires, not here: the document store itself depends on the auth provider.
        if (key.isBlank()) FakeAuth(get()) else FirebaseAuth(key, get(), get(), onSessionExpired = { get<PushRegistration>().sessionExpired(); get<AttachmentDocuments>().clear(); get<BiometricPreferences>().signedOut(); get<TodaySnapshotStore>().write(null); get<ExamSittingPresenter>().end() })
    }
    single<SessionRestorer> { get<AuthProvider>() as SessionRestorer }
    // One Ktor client for the whole app: each `HttpClient()` starts an engine and its own thread pool, and the two
    // implementations plus the logo loader all want the same one. `RemoteContentApi` still derives its own config.
    single {
        HttpClient {
            install(WebSockets)
        }
    }
    // Bound by concrete type first, then aliased: `ContentApi` and `SchoolApi` are two views of the same object, and
    // going through the concrete class makes that a compile error to get wrong rather than a DI-time ClassCastException.
    when (config) {
        ApiConfig.Fake -> {
            single {
                val db = get<Db>()
                FakeContentApi(get(), persisted = { childId -> db.read { selectAllAttempts(childId).executeAsList().map { quest.api.dto.AttemptUpload(it.id, it.stopId, it.lessonId, it.level.toInt(), it.answerJson, it.correct == 1L, it.attemptNumber.toInt(), it.mistakes.toInt(), it.stars.toInt(), it.answeredAt) } } })
            }
            single<ContentApi> { get<FakeContentApi>() }
            single<SchoolApi> { get<FakeContentApi>() }
            single {
                AttachmentUploader { childId, file, onProgress ->
                    get<FakeContentApi>().uploadChatAttachment(childId, UploadFile(file.name, file.contentType, file.readBytes())).also { onProgress(1f) }
                }
            }
        }
        is ApiConfig.Server -> {
            single { RemoteContentApi(config.baseUrl, get(), get()) }
            single<ContentApi> { get<RemoteContentApi>() }
            single<SchoolApi> { get<RemoteContentApi>() }
            // M7: the server's upload reports progress; the fake's (below) answers in one step.
            single {
                AttachmentUploader { childId, file, onProgress ->
                    get<RemoteContentApi>().uploadChatAttachment(childId, file.name, file.contentType, file.size, { file.source() }, onProgress)
                }
            }
        }
    }

    val chatBaseUrl = when (config) {
        ApiConfig.Fake -> "http://localhost:8080"
        is ApiConfig.Server -> config.baseUrl
    }
    single { ChatSocketClient(chatBaseUrl, get(), get()) }
    // MH3: `attachment.url` is root-relative and authenticated, so the loader needs the same base and the same bearer.
    // Feature 13: the bearer is the **active side's** (`SessionAuth`) — a staff session's photo and PDF are fetched
    // with her token (and her `X-School-Id`), a parent's with the Firebase one, exactly as before.
    single<AttachmentImages> { AttachmentImageStore(chatBaseUrl, get<SessionAuth>(), get(), schoolId = { get<SessionAuth>().schoolId }) }
    // M1: a PDF is streamed into the document cache (10 MB cap), the directory the system viewer may read.
    single<AttachmentDocuments> {
        AttachmentDocumentStore(
            chatBaseUrl, get<SessionAuth>(), get(),
            directory = { DocumentViewer.directory() },
            schoolId = { get<SessionAuth>().schoolId },
        )
    }
    single<ChatRepository> { ChatRepositoryImpl(get(), get(), get()) }
    // M7 (review): picked files wait in the document cache — which sign-out empties — not in memory.
    single<UploadStaging> { FileUploadStaging(directory = { DocumentViewer.directory() }) }
}

val coreModule = module {
    single { Db(get()) }
    single { SettingsStore(get()) }
    single { quest.feature.journey.data.LessonImages(get()) }
    // M5: once the session is restored, the push token is (re-)registered — and again whenever the app language changes.
    single {
        AppInitializer(
            get(), get(), get(), get(),
            // Feature 13: the staff half of the session comes back beside the parent's, and the sides are worked out
            // before the first frame — the first screen is `RoleRouter`'s answer to them.
            sessions = { get<StaffAuth>().restore(); get<SessionRolesImpl>().restored() },
            onRestored = { scope -> get<PushRegistration>().start(scope, get<ParentRepository>().language) },
        )
    }
}

/**
 * §2–§4: the school a child belongs to, its theme and its feature flags. [SchoolApi]'s three public routes are served
 * by whichever [ContentApi] is bound — `FakeContentApi` and `RemoteContentApi` both implement it — so the join flow
 * works with and without a backend.
 */
val schoolModule = module {
    single<SchoolLogoLoader> { HttpSchoolLogos(get()) }
    single<SchoolSession> { SchoolSessionImpl(get(), get(), get()) }
    single<FlagStore> { get<SchoolSession>() }
}

val contentModule = module {
    single<ChildrenRepository> { ChildrenRepositoryImpl(get(), get(), get(), get()) }
    single<LessonRepository> { LessonRepositoryImpl(get(), get()) }
    single<JourneyRepository> { JourneyRepositoryImpl(get(), get()) }
    single<MapRepository> { MapRepositoryImpl(get(), get(), get()) }
    // M4 (D3): one for the app — started by `AppInitializer`, nudged when the app comes to the front.
    single { PendingAnswersSync(get(), get(), get()) }
    viewModel { SignInViewModel(get()) }
    // Signing out also turns the biometric lock off (M2), empties the home-screen widget and any exam activity (M3), and
    // withdraws the push token while the session can still say whose it is (M5).
    factory { SignOutUseCase(get(), get(), get(), alsoForget = { get<PushRegistration>().signingOut(); get<BiometricPreferences>().signedOut(); get<TodaySnapshotStore>().write(null); get<ExamSittingPresenter>().end() }) }
    single { ExamWindows() }
    factory { PublishTodayUseCase(get(), get(), get()) }
    // M2: the biometric lock. One instance — it remembers when the app went to the background.
    single<BiometricPreferences> { BiometricPreferencesImpl(get()) }
    single { platformBiometricAuthenticator() }
    single { AppLock(get(), get(), get(), signOut = { get<SignOutUseCase>()() }, elapsed = ::elapsedRealtimeMillis) }
    viewModel { ChildrenViewModel(get(), get()) }
    factory { LessonCopy(get(), get()) }
    viewModel { MapViewModel(get(), get(), get(), get(), get(), get(), now = ServerClock::now, publishToday = get(), childResults = get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> JourneyViewModel(lessonId, level, variant, get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int, index: Int) -> StopPlayerViewModel(lessonId, level, variant, index, get(), get(), get(), get(), get(), sitting = get(), windows = get(), now = ServerClock::now, sync = get()) }
    factory { ChildResultsUseCase(get()) }
    viewModel { (lessonId: String) -> ExamResultViewModel(lessonId, get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> LessonCompleteViewModel(lessonId, level, variant, get(), get(), get(), get(), get(), get()) }
}

val rewardsModule = module {
    single<RewardsRepository> { RewardsRepositoryImpl(get()) { get<ChildrenRepository>().currentChild.value?.id ?: "none" } }
    factory { AwardStickerUseCase(get(), StickerKeys.all) }
    factory { UpdateStreakUseCase(get()) }
}

val parentModule = module {
    single<ParentRepository> { ParentRepositoryImpl(get()) }
    factory { VerifyPinUseCase(get()) }
    factory { SetPinUseCase(get()) }
    factory { ProgressReportUseCase(get(), get()) }
    factory { CalendarUseCase(get()) }
    factory { ReleasedResultsUseCase(get()) }
    viewModel { (changePin: Boolean) -> PinViewModel(get(), get(), get(), changePin) }
    viewModel { ParentHomeViewModel(get(), get(), get(), get(), get(), UndeliveredExamAnswersUseCase(get(), get())) }
    viewModel { CalendarViewModel(get(), get(), get()) }
    viewModel { ProgressViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    // Feature 29: one store for the whole session — her reading language, and every translation she has asked for,
    // cached per item and language so asking twice costs nothing.
    single { TranslationStore(get()) }
}

val chatModule = module {
    viewModel { ChatThreadsViewModel(get(), get()) }
    viewModel { CoordinatorPickerViewModel(get(), get()) }
    // Feature 13: the parent's end of a conversation — the same view model the staff end uses, with her source.
    viewModel { (peer: ChatPeer) -> ChatConversationViewModel(ParentConversationSource(peer, get()), peer.conversationState(), get()) }
}

/** M8: B6's complaints — their own conversations and their own tab, apart from Messages. */
val complaintsModule = module {
    single<ComplaintsRepository> { ComplaintsRepositoryImpl(get()) }
    viewModel { ComplaintsViewModel(get(), get(), get()) }
    // M8: complaint files go through M7's pipeline — the same staging and uploader as Messages.
    viewModel { NewComplaintViewModel(get(), get(), get(), get()) }
    viewModel { (childId: String, complaintId: String) -> ComplaintViewModel(childId, complaintId, get(), get(), get(), get()) }
}

/** RM4: the parent's broadcasts feed. Its own module — the feed is not chat, and it is read behind its own flag. */
val broadcastsModule = module {
    single<BroadcastsRepository> { BroadcastsRepositoryImpl(get(), get()) }
    // M4 (D5): the bottom bar's badges, one for the app, moved live by `/ws/chat`.
    single<UnreadSource> {
        val flags = get<FlagStore>()
        ParentUnreadSource(get(), get(), get(), get(), announcementsOn = { flags.isEnabled(Flags.ANNOUNCEMENTS) }, chatOn = { flags.isEnabled(Flags.CHAT) })
    }
    single { ParentBadges(get(), get()).also { it.start(CoroutineScope(SupervisorJob() + Dispatchers.Default), get<ChatRepository>().incomingFrames) } }
    single<NotificationsRepository> { NotificationsRepositoryImpl(get()) }
    viewModel { BroadcastsViewModel(get(), get(), get<ChatRepository>().incomingFrames, get(), get(), feedOn = { get<FlagStore>().isEnabled(Flags.ANNOUNCEMENTS) }) }
    viewModel { WeeklyPlanViewModel(get(), get()) }
}

/**
 * M5: push for parents. The platform half ([quest.feature.push.domain.PushTokens]) is bound by `platformModule()` —
 * FCM on Android, nothing on iOS and the desktop. The registration sends the app's language as `locale`: the language
 * the parent reads the app in is the one the server writes the push in.
 */
val pushModule = module {
    single<PushPreferences> { PushPreferencesImpl(get()) }
    single<PushRegistrar> { ApiPushRegistrar(get()) }
    single {
        PushRegistration(
            get(), get(), get(), get(), locale = { get<ParentRepository>().language.value },
            // Feature 13: a signed-in staff session is the side this phone belongs to, so the token goes to
            // `staff_devices` through her own bearer and leaves the same way.
            staff = {
                val staff = get<StaffAuth>()
                if (staff.signedIn) staff to StaffPushRegistrar(get()) else null
            },
        )
    }
    factory { PushPrompts(get(), get()) }
    // One gate memory for the app: the PIN screen opens it, the child's side and a minute away close it.
    single { ParentGate(elapsed = ::elapsedRealtimeMillis, graceMillis = AppLock.BACKGROUND_LIMIT_MILLIS) }
    factory {
        val maps = get<MapRepository>()
        NotificationRouter(get(), get(), get(), get(), get(), staffThreads = {
            if (!get<StaffAuth>().signedIn) null else runCancellable { get<StaffThreads>().list() }.getOrNull()
        }, staffSignedIn = { get<StaffAuth>().signedIn }, lessonsOnMap = { child ->
            val today = Today.date()
            runCancellable { maps.map(child, today.minus(30, DateTimeUnit.DAY), today.plus(7, DateTimeUnit.DAY), today).islands.mapNotNull { it.lessonId }.toSet() }.getOrNull()
        })
    }
    factory {
        PushNavigator(
            get(), get(),
            // Feature 13: either side being signed in is a session, so a staff member's tap is followed too.
            signedIn = { get<AuthProvider>().state.value is AuthState.SignedIn || get<StaffAuth>().signedIn },
            // Her conversation opens once the app lock is past; there is no parent gate on the staff side.
            awaitUnlocked = { get<AppLock>().awaitUnlocked() },
        )
    }
}

/**
 * Feature 13: staff mode. Everything here is one object per app — the staff session, her gateway and the socket she
 * listens on — because they are the one session, and the view models are per screen as the parent's are.
 *
 * The socket is a **second** `ChatSocketClient`, named, with her bearer on it: `/ws/chat` is one connection per
 * person and the two sides of a dual-role account are two different people as far as the server is concerned.
 */
fun staffModule(config: ApiConfig): Module = module {
    val baseUrl = when (config) {
        ApiConfig.Fake -> "http://localhost:8080"
        is ApiConfig.Server -> config.baseUrl
    }
    single { StaffAuth(baseUrl, get(), get(), onSessionExpired = { get<StaffSession>().clear() }) }
    // The bearer of whichever side is signed in, for everything that fetches bytes rather than JSON.
    single { SessionAuth(get<AuthProvider>(), get<StaffAuth>()) }
    when (config) {
        // Parity with `FakeContentApi`: a build with no server still signs a teacher in and draws her tabs.
        ApiConfig.Fake -> {
            single { FakeStaffApi() }
            single<StaffGateway> { get<FakeStaffApi>() }
            // Feature 22's three `/management` routes are their own port, so no other feature's fake has to grow.
            single<VoiceAnnouncementGateway> { get<FakeStaffApi>() }
        }
        is ApiConfig.Server -> {
            single { RemoteStaffApi(baseUrl, get(), get()) }
            single<StaffGateway> { get<RemoteStaffApi>() }
            single<VoiceAnnouncementGateway> { get<RemoteStaffApi>() }
        }
    }
    single { StaffSession(get()) }
    single<StaffSessionView> { get<StaffSession>() }
    single(named(STAFF_SOCKET)) { ChatSocketClient(baseUrl, get<StaffAuth>(), get()) }
    single<StaffThreads> { StaffThreadsImpl(get(), get(), get(named(STAFF_SOCKET))) }
    // The counts listen to her own `/ws/chat` frames, so every staff screen — the home card included — moves with
    // a message that arrives while it is open, and not only when a tab is reopened.
    single {
        StaffBadges(get()) { get<StaffSession>().profile.value }
            .also { it.start(CoroutineScope(SupervisorJob() + Dispatchers.Default), get<StaffThreads>().frames) }
    }
    single {
        SessionRolesImpl(
            get(), get(), get(), get(),
            onSignedIn = { get<PushRegistration>().signedIn() },
            // The parent half of signing out is today's use case, which also withdraws the push token.
            parentSignOut = { get<SignOutUseCase>()() },
        )
    }
    single<SessionRoles> { get<SessionRolesImpl>() }

    viewModel { StaffMessagesViewModel(get(), get()) }
    viewModel { StaffNotificationsViewModel(get(), get()) }
    viewModel { StaffAccountViewModel(get(), get(), get(), get()) }
    viewModel { StaffPasswordViewModel(get()) }
    // Feature 15: the install id, the rationale bit and the language, on the device.
    single<CheckInPrefs> { SettingsCheckInPrefs(get()) }
    // Feature 15: the check-in card and her own month of records.
    viewModel { CheckInViewModel(get(), get(), get(), get()) }
    viewModel { MyRecordViewModel(get()) }
    // Feature 18: her remote-day and correction requests, with M7's staging under the one file a correction carries.
    viewModel { AttendanceRequestsViewModel(get(), get()) }
    // Feature 22: her voice note, the prepared draft and Send. The role gate reads her own `GET /staff/me`.
    viewModel { VoiceAnnouncementViewModel(get(), get<StaffSession>().profile) }
    // Her end of a conversation: the parent's view model, with the `/staff/chat` source under it.
    viewModel(named(STAFF_CONVERSATION)) { (key: StaffThreadKey) ->
        ChatConversationViewModel(StaffConversationSource(key, get(), get(named(STAFF_SOCKET))), key.staffConversationState(), get())
    }
}

/** The staff member's own `/ws/chat` connection, told apart from the parent's by name. */
private const val STAFF_SOCKET = "staffChatSocket"

fun appModules(config: ApiConfig): List<Module> = listOf(platformModule(), connectivityModule(), apiModule(config), coreModule, schoolModule, contentModule, rewardsModule, parentModule, chatModule, complaintsModule, broadcastsModule, pushModule, staffModule(config))
