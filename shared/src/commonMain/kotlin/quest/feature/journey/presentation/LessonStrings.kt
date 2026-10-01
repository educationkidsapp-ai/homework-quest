package quest.feature.journey.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import quest.feature.parent.domain.ParentRepository
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.AcademicTheme
import quest.ui.journey.JourneyLabels
import quest.ui.journey.LocalJourneyLabels
import quest.ui.stops.LocalStopLabels
import quest.ui.stops.StopLabels

/**
 * The copy of the lesson and exam screens — school wording, in the language the parent picked. It is its own table
 * rather than more fields on the parent `Strings` because the view models speak some of it aloud and need it without a
 * composition. `{n}`, `{done}`, `{total}`, `{earned}` and `{name}` are replaced by the caller.
 */
data class LessonStrings(
    val back: String = "Back",
    val readAloud: String = "Read aloud",
    val loadingLesson: String = "Loading the lesson…",
    val savingWork: String = "Saving your work…",
    val lessonUnavailable: String = "This lesson is not on this device yet. Try again when you are online.",
    val lessonMissing: String = "This lesson could not be found.",
    val noStudent: String = "No student is selected.",
    val genericError: String = "Something went wrong.",
    val backToHome: String = "Back to home",
    // overview
    val startLesson: String = "Start lesson",
    val continueLesson: String = "Continue",
    val finishLesson: String = "Finish lesson",
    val stepsCompleted: String = "{done} of {total} steps completed",
    val stepOf: String = "Step {n} of {total}",
    // exam (§8): one sitting, no hints, no level chooser
    val exam: String = "Exam",
    val examRules: String = "One sitting. Hints are not available during an exam.",
    val startExam: String = "Start exam",
    val continueExam: String = "Continue exam",
    val submitExam: String = "Submit exam",
    val questionOf: String = "Question {n} of {total}",
    val examSubmittedPill: String = "Submitted",
    val examAlreadySubmitted: String = "This exam has already been submitted.",
    val examNote: String = "Exam — one sitting",
    // player
    val hint: String = "Hint",
    val tryAgain: String = "Try again",
    val praises: List<String> = listOf("Correct", "Well done", "That is right"),
    val answerSaved: String = "Answer saved",
    val stepComplete: String = "Step complete",
    // result
    val lessonComplete: String = "Lesson complete",
    val lessonCompleteBody: String = "You have finished every step of this lesson.",
    val examSubmitted: String = "Exam submitted",
    val examSubmittedBody: String = "Your answers were sent to your teacher. The result will be shared with your parent.",
    val starsEarned: String = "{earned} of {total} stars",
    val repeatLesson: String = "Repeat lesson",
    val nextLevel: String = "Next level",
    // spoken by the read-aloud button
    val speakStart: String = "Select the first step to begin the lesson.",
    val speakProgress: String = "{n} steps completed. Select the next step.",
    val speakAllDone: String = "All steps are complete. Select Finish lesson.",
    val speakLessonComplete: String = "Well done {name}. You have completed the lesson.",
    val speakLocked: String = "This lesson is not available yet.",
    val speakHomeEmpty: String = "No lessons today. Check again tomorrow.",
    val speakHomeToday: String = "Select today's lesson to begin.",
    val speakHome: String = "Select a lesson to open it.",
    val stops: StopLabels = StopLabels(),
    val journey: JourneyLabels = JourneyLabels(),
) {
    companion object {
        val en = LessonStrings()
        val ar = LessonStrings(
            back = "رجوع",
            readAloud = "قراءة بصوت مسموع",
            loadingLesson = "جارٍ تحميل الدرس…",
            savingWork = "جارٍ حفظ عملك…",
            lessonUnavailable = "هذا الدرس غير متوفر على الجهاز بعد. حاول مرة أخرى عند توفر الإنترنت.",
            lessonMissing = "تعذّر العثور على هذا الدرس.",
            noStudent = "لم يتم اختيار طالب.",
            genericError = "حدث خطأ ما.",
            backToHome = "العودة إلى الرئيسية",
            startLesson = "ابدأ الدرس",
            continueLesson = "متابعة",
            finishLesson = "إنهاء الدرس",
            stepsCompleted = "اكتمل {done} من {total} خطوات",
            stepOf = "الخطوة {n} من {total}",
            exam = "اختبار",
            examRules = "جلسة واحدة. التلميحات غير متاحة أثناء الاختبار.",
            startExam = "ابدأ الاختبار",
            continueExam = "متابعة الاختبار",
            submitExam = "تسليم الاختبار",
            questionOf = "السؤال {n} من {total}",
            examSubmittedPill = "تم التسليم",
            examAlreadySubmitted = "تم تسليم هذا الاختبار من قبل.",
            examNote = "اختبار — جلسة واحدة",
            hint = "تلميح",
            tryAgain = "حاول مرة أخرى",
            praises = listOf("إجابة صحيحة", "أحسنت", "هذا صحيح"),
            answerSaved = "تم حفظ الإجابة",
            stepComplete = "اكتملت الخطوة",
            lessonComplete = "اكتمل الدرس",
            lessonCompleteBody = "لقد أنهيت جميع خطوات هذا الدرس.",
            examSubmitted = "تم تسليم الاختبار",
            examSubmittedBody = "أُرسلت إجاباتك إلى معلّمك. ستُشارك النتيجة مع وليّ أمرك.",
            starsEarned = "{earned} من {total} نجوم",
            repeatLesson = "إعادة الدرس",
            nextLevel = "المستوى التالي",
            speakStart = "اختر الخطوة الأولى لبدء الدرس.",
            speakProgress = "اكتملت {n} خطوات. اختر الخطوة التالية.",
            speakAllDone = "اكتملت جميع الخطوات. اختر إنهاء الدرس.",
            speakLessonComplete = "أحسنت يا {name}. لقد أكملت الدرس.",
            speakLocked = "هذا الدرس غير متاح بعد.",
            speakHomeEmpty = "لا توجد دروس اليوم. عُد غداً.",
            speakHomeToday = "اختر درس اليوم للبدء.",
            speakHome = "اختر درساً لفتحه.",
            stops = StopLabels(
                check = "تحقّق", done = "تم", continueLabel = "متابعة", finished = "انتهيت", next = "التالي", listen = "استمع", sayIt = "انطقها",
                readToMe = "اقرأ لي", reading = "جارٍ القراءة…", showAnother = "اعرض مثالاً آخر", clear = "مسح", record = "تسجيل",
                stopRecording = "إيقاف", recordAgain = "تسجيل جديد", play = "تشغيل", sayAnswer = "قل إجابتك بصوت مسموع.",
                trueLabel = "صواب", falseLabel = "خطأ", moreToSelect = "بقي {n} للاختيار", notThatOne = "ليست هذه.",
                someRight = "بعضها صحيح. واصل.", notThose = "ليست هذه. حاول مرة أخرى.", notAMatch = "غير متطابقين. حاول مرة أخرى.",
                orderPartlyRight = "أول {n} صحيحة. أعد ترتيب الباقي.", readSentenceAgain = "اقرأ الجملة مرة أخرى. أي كلمة تناسب المعنى؟",
                alreadyTried = "تمت تجربتها",
            ),
            journey = JourneyLabels(
                step = "الخطوة {n}", level = "المستوى {n}", levelNames = mapOf(1 to "أساسي", 2 to "موسّع", 3 to "متقدّم"),
                locked = "مقفل", completed = "مكتمل", current = "التالي",
                certificate = "شهادة إتمام", certificateFor = "تُمنح إلى", certificateLesson = "لإتمام", studentFallback = "الطالب",
                kindRead = "قراءة", kindStory = "عناصر القصة", kindWords = "مفردات", kindActivity = "نشاط", kindExplain = "شرح",
                kindQuestion = "سؤال", kindSelect = "اختيار متعدد", kindMatch = "توصيل", kindOrder = "ترتيب", kindTrace = "خط",
                kindRetell = "إعادة سرد", kindOpen = "إجابة مفتوحة", kindSentence = "إكمال الجملة", kindReview = "أسئلة مراجعة",
            ),
        )

        fun forLanguage(code: String) = if (code == "ar") ar else en
    }
}

/**
 * The lesson copy in the parent's language, for the view models that speak it. Same rule as the student home: a school
 * without `parentPanel.arabic` has no Arabic at all, so it stays English rather than half-translated.
 */
class LessonCopy(private val parent: ParentRepository, private val flags: FlagStore) {
    fun strings(): LessonStrings =
        if (flags.isEnabled(Flags.PARENT_PANEL_ARABIC)) LessonStrings.forLanguage(parent.language.value) else LessonStrings.en
}

val LocalLessonStrings = staticCompositionLocalOf { LessonStrings.en }

/** Whether the lesson chrome reads right to left — the subject names follow it, as they do on the student home. */
val LocalLessonRtl = staticCompositionLocalOf { false }

/**
 * Wraps a lesson, exam or result screen: the student theme of the home page, the parent's language (and its layout
 * direction), and the labels the shared stop composables draw.
 */
@Composable
fun LessonTheme(content: @Composable () -> Unit) {
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val arabic = featureEnabled(Flags.PARENT_PANEL_ARABIC) && language == "ar"
    LessonTheme(if (arabic) LessonStrings.ar else LessonStrings.en, rtl = arabic, content = content)
}

@Composable
fun LessonTheme(strings: LessonStrings, rtl: Boolean, content: @Composable () -> Unit) {
    AcademicTheme(rtl = rtl) {
        CompositionLocalProvider(
            LocalLessonStrings provides strings,
            LocalLessonRtl provides rtl,
            LocalStopLabels provides strings.stops,
            LocalJourneyLabels provides strings.journey,
            content = content,
        )
    }
}
