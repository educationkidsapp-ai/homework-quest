package quest.di

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
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.presentation.ChatConversationViewModel
import quest.feature.chat.presentation.ChatThreadsViewModel
import quest.feature.broadcasts.data.AttachmentImageStore
import quest.feature.broadcasts.data.BroadcastsRepositoryImpl
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.presentation.BroadcastsViewModel
import quest.feature.broadcasts.presentation.WeeklyPlanViewModel
import quest.feature.chat.presentation.CoordinatorPickerViewModel
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.platformModule
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
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.map.presentation.MapViewModel
import quest.feature.parent.data.ParentRepositoryImpl
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
        if (key.isBlank()) FakeAuth(get()) else FirebaseAuth(key, get(), get(), onSessionExpired = { get<AttachmentDocuments>().clear() })
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
        }
        is ApiConfig.Server -> {
            single { RemoteContentApi(config.baseUrl, get(), get()) }
            single<ContentApi> { get<RemoteContentApi>() }
            single<SchoolApi> { get<RemoteContentApi>() }
        }
    }

    val chatBaseUrl = when (config) {
        ApiConfig.Fake -> "http://localhost:8080"
        is ApiConfig.Server -> config.baseUrl
    }
    single { ChatSocketClient(chatBaseUrl, get(), get()) }
    // MH3: `attachment.url` is root-relative and authenticated, so the loader needs the same base and the same bearer.
    single<AttachmentImages> { AttachmentImageStore(chatBaseUrl, get(), get()) }
    // M1: a PDF is streamed into the document cache (10 MB cap), the directory the system viewer may read.
    single<AttachmentDocuments> { AttachmentDocumentStore(chatBaseUrl, get(), get(), directory = { DocumentViewer.directory() }) }
    single<ChatRepository> { ChatRepositoryImpl(get(), get()) }
}

val coreModule = module {
    single { Db(get()) }
    single { SettingsStore(get()) }
    single { quest.feature.journey.data.LessonImages(get()) }
    single { AppInitializer(get(), get(), get()) }
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
    viewModel { SignInViewModel(get()) }
    factory { SignOutUseCase(get(), get(), get()) }
    viewModel { ChildrenViewModel(get(), get()) }
    factory { LessonCopy(get(), get()) }
    viewModel { MapViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> JourneyViewModel(lessonId, level, variant, get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int, index: Int) -> StopPlayerViewModel(lessonId, level, variant, index, get(), get(), get(), get(), get()) }
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
    viewModel { ParentHomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { CalendarViewModel(get(), get(), get()) }
    viewModel { ProgressViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}

val chatModule = module {
    viewModel { ChatThreadsViewModel(get(), get()) }
    viewModel { CoordinatorPickerViewModel(get(), get()) }
    viewModel { (peer: ChatPeer) -> ChatConversationViewModel(peer, get()) }
}

/** RM4: the parent's broadcasts feed. Its own module — the feed is not chat, and it is read behind its own flag. */
val broadcastsModule = module {
    single<BroadcastsRepository> { BroadcastsRepositoryImpl(get(), get()) }
    viewModel { BroadcastsViewModel(get(), get()) }
    viewModel { WeeklyPlanViewModel(get(), get()) }
}

fun appModules(config: ApiConfig): List<Module> = listOf(platformModule(), apiModule(config), coreModule, schoolModule, contentModule, rewardsModule, parentModule, chatModule, broadcastsModule)
