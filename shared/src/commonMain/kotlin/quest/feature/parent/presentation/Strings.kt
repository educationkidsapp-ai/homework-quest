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
    // v3
    val signIn: String = "Sign in", val register: String = "Create account", val signInBody: String = "Parents sign in; children just play.",
    val email: String = "Email", val password: String = "Password", val createAccount: String = "Create account", val googleSignIn: String = "Continue with Google",
    val haveAccount: String = "I already have an account", val noAccount: String = "New here? Create an account",
    val addChild: String = "Add a child", val avatar: String = "Pip's colour", val american: String = "American", val british: String = "British",
    val languages: String = "Subject languages", val whoIsPlaying: String = "Who is playing?", val children: String = "Children", val signOut: String = "Sign out",
    val level: String = "Level", val unlockLevel: String = "Unlock", val lessonPanel: String = "Lesson panel", val objectives: String = "Learning objectives",
    val supported: String = "Supported", val challengeIdeas: String = "Challenge", val tipsPerStop: String = "Tips for each stop", val played: String = "Played", val notPlayed: String = "Not played yet", val playRecording: String = "Play the recording",
    val deleteChild: String = "Delete this child", val deleteChildBody: String = "Removes the child, their progress, recordings and drawings from this device and the server.", val deleteChildConfirm: String = "Yes, delete",
    val weakSkills: String = "Needs another look", val noWeakSkills: String = "Nothing to review right now — great!", val streak: String = "Day streak", val stickers: String = "Stickers",
    // §2 join school (P2.2)
    val schoolCode: String = "School code", val schoolCodeHint: String = "The 6-letter code from your child's school. Leave it empty if you do not have one.",
    val schoolCodePlaceholder: String = "ABC123", val schoolNotFound: String = "We couldn't find that school code.",
    val joinSchool: String = "Join this school", val joinedSchool: String = "Joined", val changeSchool: String = "Use a different code",
    val schoolCurriculumNote: String = "Your school sets the curriculum and grades below.",
    // D16 slice 2: the join belongs to the parent, so later children are told which school they are joining.
    val yourSchool: String = "Your school",
    // D16 slice 3: the class join code from the teacher's class card (§2).
    val classCode: String = "Class code",
    val classCodeHint: String = "The code on your child's class card, if the teacher sent one. Leave it empty if you do not have one.",
    val classNotFound: String = "We couldn't find that class code.",
    val changeClass: String = "Use a different class code",
    val noClassCodeNote: String = "Without a class code your child can still play. Until the teacher adds her to a class, the same lesson may appear once for each class in her year.",
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
            signIn = "تسجيل الدخول", register = "إنشاء حساب", signInBody = "الأهل يسجّلون الدخول؛ الأطفال يلعبون فقط.",
            email = "البريد الإلكتروني", password = "كلمة المرور", createAccount = "إنشاء حساب", googleSignIn = "المتابعة عبر Google",
            haveAccount = "لديّ حساب بالفعل", noAccount = "جديد هنا؟ أنشئ حسابًا",
            addChild = "إضافة طفل", avatar = "لون بيب", american = "أمريكي", british = "بريطاني",
            languages = "لغات المواد", whoIsPlaying = "من يلعب؟", children = "الأطفال", signOut = "تسجيل الخروج",
            level = "المستوى", unlockLevel = "فتح", lessonPanel = "لوحة الدرس", objectives = "أهداف التعلّم",
            supported = "دعم", challengeIdeas = "تحدٍّ", tipsPerStop = "نصائح لكل محطة", played = "لُعب", notPlayed = "لم يُلعب بعد", playRecording = "تشغيل التسجيل",
            deleteChild = "حذف هذا الطفل", deleteChildBody = "يزيل الطفل وتقدّمه وتسجيلاته ورسوماته من هذا الجهاز ومن الخادم.", deleteChildConfirm = "نعم، احذف",
            weakSkills = "يحتاج مراجعة", noWeakSkills = "لا شيء للمراجعة الآن — رائع!", streak = "أيام متتالية", stickers = "الملصقات",
            schoolCode = "رمز المدرسة", schoolCodeHint = "الرمز المكوّن من 6 أحرف من مدرسة طفلك. اتركه فارغًا إن لم يكن لديك رمز.",
            schoolCodePlaceholder = "ABC123", schoolNotFound = "لم نعثر على رمز المدرسة هذا.",
            joinSchool = "الانضمام إلى هذه المدرسة", joinedSchool = "تم الانضمام", changeSchool = "استخدم رمزًا آخر",
            schoolCurriculumNote = "مدرستك تحدّد المنهج والصفوف أدناه.", yourSchool = "مدرستك",
            classCode = "رمز الفصل", classCodeHint = "الرمز الموجود على بطاقة فصل طفلك، إن أرسلته المعلّمة. اتركه فارغًا إن لم يكن لديك رمز.",
            classNotFound = "لم نعثر على رمز الفصل هذا.", changeClass = "استخدم رمز فصل آخر",
            noClassCodeNote = "بدون رمز الفصل يستطيع طفلك اللعب. وإلى أن تضيفه المعلّمة إلى فصل، قد يظهر الدرس نفسه مرة لكل فصل في صفّه.",
            accuracyWords = mapOf("almost every time" to "في كل مرة تقريبًا", "most of the time" to "في معظم الأحيان", "more than half the time" to "أكثر من نصف المرات", "some of the time" to "أحيانًا", "not yet" to "ليس بعد"),
            weekdays = listOf("ن", "ث", "ر", "خ", "ج", "س", "ح"),
            months = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"),
        )

        fun forLanguage(code: String) = if (code == "ar") ar else en
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.en }
