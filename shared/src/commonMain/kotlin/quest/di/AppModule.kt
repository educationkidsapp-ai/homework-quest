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
import quest.feature.journey.presentation.JourneyViewModel
import quest.feature.journey.presentation.LessonCompleteViewModel
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.map.presentation.MapViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ProgressReportUseCase
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
import quest.ui.design.StickerKeys

/** Which [ContentApi] sits behind the interface. Screens never know. */
sealed interface ApiConfig {
    data object Fake : ApiConfig
    data class Server(val baseUrl: String) : ApiConfig
}

fun apiModule(config: ApiConfig): Module = module {
    single<ApiConfig> { config }
    // Firebase arrives in Phase 4 (expect/actual); FakeAuth derives a stable uid from the email meanwhile.
    single { FakeAuth(get()) }
    single<AuthProvider> { get<FakeAuth>() }
    when (config) {
        ApiConfig.Fake -> single<ContentApi> { FakeContentApi(get()) }
        is ApiConfig.Server -> single<ContentApi> { RemoteContentApi(config.baseUrl, get(), HttpClient()) }
    }
}

val coreModule = module {
    single { Db(get()) }
    single { SettingsStore(get()) }
    single { AppInitializer(get(), get()) }
}

val contentModule = module {
    single<ChildrenRepository> { ChildrenRepositoryImpl(get(), get(), get(), get()) }
    single<LessonRepository> { LessonRepositoryImpl(get(), get()) }
    single<JourneyRepository> { JourneyRepositoryImpl(get(), get()) }
    single<MapRepository> { MapRepositoryImpl(get(), get(), get()) }
    factory { AddChildUseCase(get()) }
    viewModel { SignInViewModel(get()) }
    viewModel { (editingId: String?) -> AddChildViewModel(editingId, get(), get()) }
    viewModel { MapViewModel(get(), get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int) -> JourneyViewModel(lessonId, level, variant, get(), get(), get()) }
    viewModel { (lessonId: String, level: Int, variant: Int, index: Int) -> StopPlayerViewModel(lessonId, level, variant, index, get(), get(), get()) }
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
    viewModel { (changePin: Boolean) -> PinViewModel(get(), get(), get(), changePin) }
    viewModel { ParentHomeViewModel(get(), get(), get()) }
    viewModel { CalendarViewModel(get(), get()) }
    viewModel { ProgressViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}

fun appModules(config: ApiConfig): List<Module> = listOf(platformModule(), apiModule(config), coreModule, contentModule, rewardsModule, parentModule)
