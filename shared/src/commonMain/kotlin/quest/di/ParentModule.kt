package quest.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import quest.api.dto.Subject
import quest.feature.lesson.presentation.AddLessonViewModel
import quest.feature.lesson.presentation.ConfirmSkillsViewModel
import quest.feature.lesson.presentation.ReadingViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ProgressReportUseCase
import quest.feature.parent.domain.RequeueWeakSkillsUseCase
import quest.feature.parent.domain.SetPinUseCase
import quest.feature.parent.domain.VerifyPinUseCase
import quest.feature.parent.presentation.CalendarViewModel
import quest.feature.parent.presentation.ParentHomeViewModel
import quest.feature.parent.presentation.PinViewModel
import quest.feature.parent.presentation.ProgressViewModel
import quest.feature.parent.presentation.SettingsViewModel

val parentModule = module {
    single<ParentRepository> { ParentRepositoryImpl(get(), get(), get(ChildIdQualifier)) }
    factory { VerifyPinUseCase(get()) }
    factory { SetPinUseCase(get()) }
    factory { ProgressReportUseCase(get(), get()) }
    factory { RequeueWeakSkillsUseCase(get(), get()) }
    factory { CalendarUseCase(get()) }

    viewModel { (changePin: Boolean) -> PinViewModel(get(), get(), get(), changePin) }
    viewModel { ParentHomeViewModel(get(), get(), get()) }
    viewModel { CalendarViewModel(get(), get()) }
    viewModel { ProgressViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }

    // lesson flow (parent-facing screens live in feature/lesson/presentation)
    viewModel { (subject: Subject) -> AddLessonViewModel(subject, get()) }
    viewModel { (lessonId: String) -> ReadingViewModel(lessonId, get(), get()) }
    viewModel { (lessonId: String) -> ConfirmSkillsViewModel(lessonId, get(), get()) }
}
