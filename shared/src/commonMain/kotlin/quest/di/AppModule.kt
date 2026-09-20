package quest.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
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
import quest.feature.children.domain.AddChildUseCase
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.presentation.AddChildViewModel
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
import quest.feature.rewards.presentation.RewardsViewModel
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
        if (key.isBlank()) FakeAuth(get()) else FirebaseAuth(key, get(), get())
    }
    single<SessionRestorer> { get<AuthProvider>() as SessionRestorer }
    // One Ktor client for the whole app: each `HttpClient()` starts an engine and its own thread pool, and the two
    // implementations plus the logo loader all want the same one. `RemoteContentApi` still derives its own config.
    single { HttpClient() }
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
    factory { AddChildUseCase(get()) }
    viewModel { SignInViewModel(get()) }
    viewModel { (editingId: String?) -> AddChildViewModel(editingId, get(), get(), get()) }
    viewModel { MapViewModel(get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> JourneyViewModel(lessonId, level, variant, get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int, index: Int) -> StopPlayerViewModel(lessonId, level, variant, index, get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> LessonCompleteViewModel(lessonId, level, variant, get(), get(), get(), get(), get()) }
}

val rewardsModule = module {
    single<RewardsRepository> { RewardsRepositoryImpl(get()) { get<ChildrenRepository>().currentChild.value?.id ?: "none" } }
    factory { AwardStickerUseCase(get(), StickerKeys.all) }
    factory { UpdateStreakUseCase(get()) }
    viewModel { RewardsViewModel(get()) }
}

val parentModule = module {
    single<ParentRepository> { ParentRepositoryImpl(get()) }
    factory { VerifyPinUseCase(get()) }
    factory { SetPinUseCase(get()) }
    factory { ProgressReportUseCase(get(), get()) }
    factory { CalendarUseCase(get()) }
    factory { ReleasedResultsUseCase(get()) }
    viewModel { (changePin: Boolean) -> PinViewModel(get(), get(), get(), changePin) }
    viewModel { ParentHomeViewModel(get(), get(), get()) }
    viewModel { CalendarViewModel(get(), get()) }
    viewModel { ProgressViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}

fun appModules(config: ApiConfig): List<Module> = listOf(platformModule(), apiModule(config), coreModule, schoolModule, contentModule, rewardsModule, parentModule)
