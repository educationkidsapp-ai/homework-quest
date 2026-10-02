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
 * The copy of the lesson screens — school wording, in the language the parent picked. It is its own table
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
    // player
    val hint: String = "Hint",
    val tryAgain: String = "Try again",
    val praises: List<String> = listOf("Correct", "Well done", "That is right"),
    val stepComplete: String = "Step complete",
    // result
    val lessonComplete: String = "Lesson complete",
    val lessonCompleteBody: String = "You have finished every step of this lesson.",
    val starsEarned: String = "{earned} of {total} stars",
    val repeatLesson: String = "Repeat lesson",
    val nextLevel: String = "Next level",
    // exam (§8): one sitting, every answer acknowledged in the same words, no result until the teacher releases it
    val exam: String = "Exam",
    val examRules: String = "Each question can be answered once. An answered question cannot be opened again. Your teacher will share the result after marking.",
    val startExam: String = "Start exam",
    val continueExam: String = "Continue exam",
    val questionsAnswered: String = "{done} of {total} questions answered",
    val questionOf: String = "Question {n} of {total}",
    val answerSaved: String = "Answer saved",
    val examSubmitted: String = "Exam submitted",
    val examSubmittedBody: String = "Your answers have been handed in. Your teacher will share the result after marking.",
    val examAlreadyTaken: String = "This exam has already been submitted. An exam can be taken once.",
    val examClosed: String = "This exam is closed. Answers can no longer be handed in.",
    val examNotOpenYet: String = "This exam has not opened yet.",
    val examSubmittedShort: String = "Submitted",
    val examClosedShort: String = "Closed",
    val examNotOpenShort: String = "Not open yet",
    val examSubmittedNote: String = "Submitted. Your teacher will share the result after marking.",
    val examNeedsConnection: String = "Connect to the internet to open this exam.",
    val examReopened: String = "Your teacher has re-opened this exam for you.",
    val examReopenedShort: String = "Re-opened",
    val examOpenUntil: String = "Open until {time}",
    val examOpensAt: String = "Opens {time}",
    val examHoursLeft: String = "About {n} hours left",
    val examOneHourLeft: String = "About one hour left",
    val examMinutesLeft: String = "{n} minutes left",
    val examLastMinutes: String = "Closing soon",
    val speakExamStart: String = "This is an exam. Each question can be answered once. Select Start exam to begin.",
    val speakExamProgress: String = "{n} questions answered. Select Continue exam.",
    val speakExamSubmitted: String = "Your exam has been submitted.",
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
            hint = "تلميح",
            tryAgain = "حاول مرة أخرى",
            praises = listOf("إجابة صحيحة", "أحسنت", "هذا صحيح"),
            stepComplete = "اكتملت الخطوة",
            lessonComplete = "اكتمل الدرس",
            lessonCompleteBody = "لقد أنهيت جميع خطوات هذا الدرس.",
            starsEarned = "{earned} من {total} نجوم",
            repeatLesson = "إعادة الدرس",
            nextLevel = "المستوى التالي",
            exam = "اختبار",
            examRules = "يمكن الإجابة عن كل سؤال مرة واحدة. لا يمكن فتح السؤال بعد الإجابة عنه. سيشارك معلمك النتيجة بعد التصحيح.",
            startExam = "ابدأ الاختبار",
            continueExam = "متابعة الاختبار",
            questionsAnswered = "تمت الإجابة عن {done} من {total} أسئلة",
            questionOf = "السؤال {n} من {total}",
            answerSaved = "تم حفظ الإجابة",
            examSubmitted = "تم تسليم الاختبار",
            examSubmittedBody = "تم تسليم إجاباتك. سيشارك معلمك النتيجة بعد التصحيح.",
            examAlreadyTaken = "تم تسليم هذا الاختبار من قبل. يُؤدّى الاختبار مرة واحدة.",
            examClosed = "هذا الاختبار مغلق. لم يعد تسليم الإجابات ممكناً.",
            examNotOpenYet = "لم يُفتح هذا الاختبار بعد.",
            examSubmittedShort = "تم التسليم",
            examClosedShort = "مغلق",
            examNotOpenShort = "لم يُفتح بعد",
            examSubmittedNote = "تم التسليم. سيشارك معلمك النتيجة بعد التصحيح.",
            examNeedsConnection = "اتصل بالإنترنت لفتح هذا الاختبار.",
            examReopened = "أعاد معلمك فتح هذا الاختبار لك.",
            examReopenedShort = "أُعيد فتحه",
            examOpenUntil = "متاح حتى {time}",
            examOpensAt = "يُفتح {time}",
            examHoursLeft = "بقي نحو {n} ساعات",
            examOneHourLeft = "بقي نحو ساعة واحدة",
            examMinutesLeft = "بقي {n} دقيقة",
            examLastMinutes = "يُغلق قريباً",
            speakExamStart = "هذا اختبار. يمكن الإجابة عن كل سؤال مرة واحدة. اختر ابدأ الاختبار.",
            speakExamProgress = "تمت الإجابة عن {n} أسئلة. اختر متابعة الاختبار.",
            speakExamSubmitted = "تم تسليم اختبارك.",
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
                drawingStrokes = "رسم من {n} خطوط", drawingPad = "لوحة الرسم، {n} خطوط", groupOf = "مجموعة من {n}", colour = "اللون {n}",
                selected = "محدّد", numberWord = "العدد {n}", missingNumber = "العدد الناقص",
            ),
            journey = JourneyLabels(
                step = "الخطوة {n}", level = "المستوى {n}", levelNames = mapOf(1 to "أساسي", 2 to "موسّع", 3 to "متقدّم"),
                locked = "مقفل", completed = "مكتمل", answered = "تمت الإجابة", current = "التالي",
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
