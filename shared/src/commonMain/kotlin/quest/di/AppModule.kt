package quest.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import quest.api.LessonApi
import quest.core.db.Db
import quest.core.db.QuestionSetDao
import quest.core.db.SettingsStore
import quest.core.design.StickerKeys
import quest.core.platform.platformModule
import quest.feature.lesson.data.FakeLessonApi
import quest.feature.lesson.data.KtorLessonApi
import quest.feature.lesson.data.LessonRepositoryImpl
import quest.feature.lesson.domain.AddLessonUseCase
import quest.feature.lesson.domain.ConfirmSkillsUseCase
import quest.feature.lesson.domain.DeleteUploadedFilesUseCase
import quest.feature.lesson.domain.GenerateQuestionSetUseCase
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.ObserveLessonUseCase
import quest.feature.lesson.domain.TodaysSkillsUseCase
import quest.feature.map.domain.IslandsUseCase
import quest.feature.map.presentation.IntroViewModel
import quest.feature.map.presentation.MapViewModel
import quest.feature.practice.data.PracticeRepositoryImpl
import quest.feature.practice.domain.CompleteSetUseCase
import quest.feature.practice.domain.PracticeRepository
import quest.feature.practice.domain.RecordAttemptUseCase
import quest.feature.practice.domain.SkillProgressUseCase
import quest.feature.practice.presentation.PracticeViewModel
import quest.feature.rewards.data.RewardsRepositoryImpl
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.UpdateStreakUseCase
import quest.feature.rewards.presentation.RewardsViewModel

/** Which [LessonApi] sits behind the interface. Screens never know. */
sealed interface ApiConfig {
    /** Seeded, in-app. [seedDemo] adds today's sample lessons on a fresh install. */
    data class Fake(val seedDemo: Boolean = true) : ApiConfig
    data class Server(val baseUrl: String) : ApiConfig
}

fun apiModule(config: ApiConfig): Module = module {
    when (config) {
        is ApiConfig.Fake -> single<LessonApi> { FakeLessonApi() }
        is ApiConfig.Server -> single<LessonApi> { KtorLessonApi(config.baseUrl, HttpClient()) }
    }
}

val coreModule = module {
    single { Db(get()) }
    single { QuestionSetDao(get()) }
    single { SettingsStore(get()) }
    single { AppInitializer(get(), get()) }
    single { DemoSeeder(get()) }
    single<suspend () -> String>(qualifier = ChildIdQualifier) { { get<AppInitializer>().childId() } }
}

object ChildIdQualifier : org.koin.core.qualifier.Qualifier { override val value: String = "childId" }

val lessonModule = module {
    single<LessonRepository> { LessonRepositoryImpl(get(), get(), get(), get(), get(ChildIdQualifier)) }
    factory { AddLessonUseCase(get()) }
    factory { ObserveLessonUseCase(get()) }
    factory { ConfirmSkillsUseCase(get()) }
    factory { GenerateQuestionSetUseCase(get()) }
    factory { TodaysSkillsUseCase(get()) }
    factory { DeleteUploadedFilesUseCase(get()) }
}

val practiceModule = module {
    single<PracticeRepository> { PracticeRepositoryImpl(get(), get(), get(ChildIdQualifier)) }
    factory { RecordAttemptUseCase(get()) }
    factory { CompleteSetUseCase(get()) }
    factory { SkillProgressUseCase(get()) }
    viewModel { (setId: String) -> PracticeViewModel(setId, get(), get(), get(), get(), get(), get(), get()) }
}

val rewardsModule = module {
    single<RewardsRepository> { RewardsRepositoryImpl(get(), get(ChildIdQualifier)) }
    factory { AwardStickerUseCase(get(), StickerKeys.all) }
    factory { UpdateStreakUseCase(get()) }
    viewModel { RewardsViewModel(get()) }
}

val mapModule = module {
    factory { IslandsUseCase(get(), get()) }
    viewModel { MapViewModel(get(), get(), get()) }
    viewModel { (skillId: String) -> IntroViewModel(skillId, get(), get(), get()) }
}

fun appModules(config: ApiConfig): List<Module> = listOf(platformModule(), apiModule(config), module { single<ApiConfig> { config } }, coreModule, lessonModule, practiceModule, rewardsModule, mapModule, parentModule)
