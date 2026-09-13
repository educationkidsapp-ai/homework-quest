package quest.feature.parent.presentation

import androidx.compose.runtime.staticCompositionLocalOf

/** Parent-mode copy in English and Arabic. Child mode stays in English (dev prompt §7). */
data class Strings(
    val isRtl: Boolean,
    val grownUps: String, val enterPin: String, val createPin: String, val repeatPin: String, val pinMismatch: String, val wrongPin: String,
    val parentHome: String, val todaysLessons: String, val noLessonsToday: String, val addLesson: String, val calendar: String,
    val progress: String, val settings: String, val backToChild: String,
    val subject: String, val math: String, val english: String, val chooseSource: String, val pdf: String, val powerpoint: String,
    val camera: String, val gallery: String, val typeTask: String, val selectedFiles: String, val readSlides: String, val remove: String,
    val typedTaskTitle: String, val typedTaskHint: String, val readIt: String,
    val uploading: String, val reading: String, val generating: String, val ready: String, val readyBody: String, val somethingWrong: String,
    val tryAgain: String, val chooseAnother: String,
    val confirmTitle: String, val confirmBody: String, val unsureQuestion: String, val addSkill: String, val skillName: String,
    val makeQuest: String, val keepAtLeastOne: String, val slides: String,
    val goingWell: String, val gettingThere: String, val needsAnotherLook: String, val notPlayedYet: String, val firstTry: String,
    val lastPractised: String, val queuedFor: String, val attempts: String,
    val childProfile: String, val childName: String, val grade: String, val curriculum: String, val save: String,
    val practiceLength: String, val questions: String, val language: String, val changePin: String, val deleteFiles: String,
    val deleteFilesBody: String, val deleted: String, val privacy: String, val privacyBody: String, val version: String,
    val today: String, val noLessonsThatDay: String, val statusLabel: Map<String, String>,
    val weekdays: List<String>, val months: List<String>,
    val accuracyWords: Map<String, String> = emptyMap(),
) {
    fun accuracy(words: String) = accuracyWords[words] ?: words

    companion object {
        val en = Strings(
            isRtl = false,
            grownUps = "Grown-ups", enterPin = "Enter your PIN", createPin = "Create a 4-digit PIN", repeatPin = "Repeat the PIN",
            pinMismatch = "The PINs did not match. Try again.", wrongPin = "That is not the PIN.",
            parentHome = "Parent mode", todaysLessons = "Today's lessons", noLessonsToday = "No lesson added today yet.",
            addLesson = "Add lesson", calendar = "Calendar", progress = "Progress", settings = "Settings", backToChild = "Back to child mode",
            subject = "Subject", math = "Math", english = "English", chooseSource = "Add today's slides", pdf = "PDF", powerpoint = "PowerPoint",
            camera = "Camera", gallery = "Photos", typeTask = "Type a task", selectedFiles = "Selected", readSlides = "Read the slides", remove = "Remove",
            typedTaskTitle = "Type the task", typedTaskHint = "For example: Count by 2s to 20 using the number line", readIt = "Read it",
            uploading = "Uploading…", reading = "Reading the slides…", generating = "Making the questions…", ready = "The quest is ready!",
            readyBody = "Your child can play it from the map now. The uploaded files have been deleted.",
            somethingWrong = "Something went wrong", tryAgain = "Try again", chooseAnother = "Choose another file",
            confirmTitle = "What was taught?", confirmBody = "Untick anything that is not a skill from today.",
            unsureQuestion = "Not sure — which one?", addSkill = "Add a skill", skillName = "Skill name", makeQuest = "Make the quest",
            keepAtLeastOne = "Keep at least one skill.", slides = "slides",
            goingWell = "Going well", gettingThere = "Getting there", needsAnotherLook = "Needs another look", notPlayedYet = "Not played yet",
            firstTry = "Right on the first try", lastPractised = "Last practised", queuedFor = "Coming back on", attempts = "answers",
            childProfile = "Child profile", childName = "Child's name", grade = "Grade", curriculum = "Curriculum", save = "Save",
            practiceLength = "Practice length", questions = "questions", language = "Language", changePin = "Change PIN",
            deleteFiles = "Delete uploaded files", deleteFilesBody = "Removes every uploaded slide file from the server. Skills and questions stay.",
            deleted = "Files deleted", privacy = "Privacy", privacyBody = "Slides are read once and deleted. Only skills and questions are kept, on this device.",
            version = "Version", today = "Today", noLessonsThatDay = "No lesson on this day.",
            statusLabel = mapOf("uploading" to "Uploading", "reading" to "Reading", "needs_confirmation" to "Needs your OK", "generating" to "Making questions", "ready" to "Ready", "error" to "Problem"),
            weekdays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
            months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
        )

        val ar = Strings(
            isRtl = true,
            grownUps = "الأهل", enterPin = "أدخل الرقم السري", createPin = "أنشئ رقمًا سريًا من 4 أرقام", repeatPin = "أعد إدخال الرقم السري",
            pinMismatch = "الرقمان غير متطابقين. حاول مجددًا.", wrongPin = "الرقم السري غير صحيح.",
            parentHome = "وضع الأهل", todaysLessons = "دروس اليوم", noLessonsToday = "لم يُضف درس اليوم بعد.",
            addLesson = "إضافة درس", calendar = "التقويم", progress = "التقدّم", settings = "الإعدادات", backToChild = "العودة إلى وضع الطفل",
            subject = "المادة", math = "رياضيات", english = "إنجليزي", chooseSource = "أضف شرائح اليوم", pdf = "PDF", powerpoint = "PowerPoint",
            camera = "الكاميرا", gallery = "الصور", typeTask = "اكتب المهمة", selectedFiles = "المحدد", readSlides = "اقرأ الشرائح", remove = "إزالة",
            typedTaskTitle = "اكتب المهمة", typedTaskHint = "مثال: العدّ بالاثنينات حتى 20 على خط الأعداد", readIt = "اقرأها",
            uploading = "جارٍ الرفع…", reading = "جارٍ قراءة الشرائح…", generating = "جارٍ إعداد الأسئلة…", ready = "المهمة جاهزة!",
            readyBody = "يمكن لطفلك اللعب الآن من الخريطة. تم حذف الملفات المرفوعة.",
            somethingWrong = "حدث خطأ ما", tryAgain = "حاول مجددًا", chooseAnother = "اختر ملفًا آخر",
            confirmTitle = "ماذا تعلّم اليوم؟", confirmBody = "ألغِ تحديد أي شيء ليس مهارة من درس اليوم.",
            unsureQuestion = "غير متأكد — أيهما؟", addSkill = "أضف مهارة", skillName = "اسم المهارة", makeQuest = "أنشئ المهمة",
            keepAtLeastOne = "أبقِ مهارة واحدة على الأقل.", slides = "شرائح",
            goingWell = "ممتاز", gettingThere = "يتقدّم", needsAnotherLook = "يحتاج مراجعة", notPlayedYet = "لم يلعب بعد",
            firstTry = "صحيح من المحاولة الأولى", lastPractised = "آخر تدريب", queuedFor = "سيعود يوم", attempts = "إجابات",
            childProfile = "ملف الطفل", childName = "اسم الطفل", grade = "الصف", curriculum = "المنهج", save = "حفظ",
            practiceLength = "طول التدريب", questions = "أسئلة", language = "اللغة", changePin = "تغيير الرقم السري",
            deleteFiles = "حذف الملفات المرفوعة", deleteFilesBody = "يحذف كل ملفات الشرائح من الخادم. تبقى المهارات والأسئلة.",
            deleted = "تم حذف الملفات", privacy = "الخصوصية", privacyBody = "تُقرأ الشرائح مرة واحدة ثم تُحذف. تُحفظ المهارات والأسئلة فقط على هذا الجهاز.",
            version = "الإصدار", today = "اليوم", noLessonsThatDay = "لا يوجد درس في هذا اليوم.",
            statusLabel = mapOf("uploading" to "جارٍ الرفع", "reading" to "جارٍ القراءة", "needs_confirmation" to "بانتظار موافقتك", "generating" to "إعداد الأسئلة", "ready" to "جاهز", "error" to "مشكلة"),
            accuracyWords = mapOf("almost every time" to "في كل مرة تقريبًا", "most of the time" to "في معظم الأحيان", "more than half the time" to "أكثر من نصف المرات", "some of the time" to "أحيانًا", "not yet" to "ليس بعد"),
            weekdays = listOf("ن", "ث", "ر", "خ", "ج", "س", "ح"),
            months = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"),
        )

        fun forLanguage(code: String) = if (code == "ar") ar else en
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.en }
