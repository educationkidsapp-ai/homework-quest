package quest.api.samples

import kotlinx.datetime.LocalDate
import quest.api.dto.AnalysedPage
import quest.api.dto.Bilingual
import quest.api.dto.BilingualList
import quest.api.dto.Course
import quest.api.dto.Curriculum
import quest.api.dto.ExtractedSkill
import quest.api.dto.MoveAction
import quest.api.dto.NumberLine
import quest.api.dto.OrderItem
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.SkillRef
import quest.api.dto.SourceAnalysis
import quest.api.dto.SourceKind
import quest.api.dto.Stop
import quest.api.dto.StopTip
import quest.api.dto.Subject
import quest.api.dto.Theme
import quest.api.dto.WorkedExample

/** "Counting by 2s" — math, British/1, 14 Sep 2026 (the design doc's seed). Levels: fluency → reasoning → problem. */
object MathSeed {
    const val LESSON_ID = "lesson-counting-by-2s"
    val date = LocalDate(2026, 9, 14)
    val course = Course(Curriculum.BRITISH, 1)
    val theme = Theme("Number train", "The number train", "🚂", "Toot toot! The number train is full and ready to go!")
    private val skillId = "counting-by-2s"
    private val ings = listOf(ing("🚃", "Carriage"), ing("🛞", "Wheel"), ing("🔔", "Bell"), ing("💨", "Steam"), ing("🚦", "Signal"), ing("🎫", "Ticket"), ing("🧳", "Suitcase"), ing("🧑‍✈️", "Driver"))
    private fun line(from: Int, to: Int, vararg hl: Int) = NumberLine(from, to, 1, hl.toList())

    val analysis = SourceAnalysis(
        kind = SourceKind.MATH, title = "Counting by 2s",
        pages = listOf(
            AnalysedPage(2, listOf("We count by 2s.", "2, 4, 6, 8, 10.", "Jump two each time on the number line."), "a number line with jumps of 2", listOf("star")),
            AnalysedPage(3, listOf("Pairs of shoes.", "One pair is 2 shoes.", "Two pairs: 2, 4."), "pairs of shoes in a row", listOf("shoe")),
        ),
        skills = listOf(ExtractedSkill(skillId, "Counting by 2s", Subject.MATH, "number line jumps and pairs of objects", listOf("2, 4, 6, 8, 10", "12, 14, 16", "pairs of shoes"), listOf(2, 3, 4), 0.95)),
        objectives = BilingualList(
            en = listOf("Count forwards in 2s to 20.", "Find a missing number in a counting-by-2s pattern.", "Count pairs of objects in 2s."),
            ar = listOf("العدّ بالاثنينات حتى 20.", "إيجاد العدد الناقص في نمط العدّ بالاثنينات.", "عدّ أزواج الأشياء بالاثنينات."),
        ),
    )

    private fun explain(id: String, ingr: Int) = Stop.Explain(id, "Counting by 2s", "Counting by 2s means we jump two each time!", ings[ingr], tip("Point to each jump on the number line.", "أشر إلى كل قفزة على خط الأعداد."),
        skillId = skillId, explanation = "Counting by 2s means we jump two each time!",
        workedExamples = listOf(WorkedExample("2, 4, 6, ?", listOf("Start at 6", "Jump 2 on the number line", "Land on 8"), "8"), WorkedExample("2 pairs of shoes", listOf("One pair is 2", "Two pairs: 2, 4"), "4 shoes")))

    private fun seq(id: String, ingr: Int, chips: List<Int?>, answer: Int, vararg others: Int, hint: String): Stop.Sequence {
        val o = opts(*(listOf(answer) + others.toList()).map { it.toString() }.toTypedArray())
        val known = chips.filterNotNull()
        return Stop.Sequence(id, "What number is missing?", "What number is missing?", ings[ingr], tip("Say the numbers out loud together.", "قولا الأعداد معًا بصوت عالٍ."), hint, chips, o, idOf(o, answer.toString()), line(maxOf(0, known.min() - 2), maxOf(known.max(), answer) + 2, *(known + answer).sorted().toIntArray()))
    }
    private fun count(id: String, ingr: Int, key: String, groups: Int, hint: String): Stop.Count {
        val total = groups * 2
        val o = opts(total.toString(), (total + 1).toString(), (total - 2).toString())
        return Stop.Count(id, "How many?", "How many ${key}s are there?", ings[ingr], tip("Touch each pair and count 2, 4, 6…", "المس كل زوج وعدّ 2، 4، 6…"), hint, key, List(groups) { 2 }, o, o[0].id, line(0, total + 2, *(1..groups).map { it * 2 }.toIntArray()))
    }
    private fun compare(id: String, ingr: Int, l: Int, r: Int, hint: String): Stop.Compare {
        val o = opts("<", ">", "=")
        val correct = when { l < r -> "<"; l > r -> ">"; else -> "=" }
        return Stop.Compare(id, "Which sign?", "Which sign goes in the middle?", ings[ingr], tip("The open mouth eats the bigger number.", "الفم المفتوح يأكل العدد الأكبر."), hint, l, r, o, idOf(o, correct), line(minOf(l, r) - 1, maxOf(l, r) + 1, l, r))
    }
    private fun tf(id: String, ingr: Int, statement: String, answer: Boolean, hint: String) = Stop.TrueFalse(id, "True or false?", statement, ings[ingr], tip("Check it on the number line.", "تحقّق منها على خط الأعداد."), hint, statement, answer)

    val level1 = Play(1, 0, SourceKind.MATH, theme, listOf(
        explain("m1-explain", 0),
        seq("m1-s1", 1, listOf(2, 4, 6, null), 8, 7, 10, hint = "Start at 6 and jump 2."),
        count("m1-c1", 2, "shoe", 3, hint = "Count the shoes two at a time: 2, 4, 6."),
        seq("m1-s2", 3, listOf(10, 12, null, 16), 14, 13, 15, hint = "What comes after 12 when we jump 2?"),
        compare("m1-cmp", 4, 8, 6, hint = "Which number is further along the number line?"),
        count("m1-c2", 5, "sock", 4, hint = "Each pair is 2 socks. Jump 2 for every pair."),
        Stop.ExitTicket("m1-exit", "Exit ticket", "Three last questions!", ings[6], tip("Quick checks.", "أسئلة سريعة."), listOf(
            seq("m1-e1", 0, listOf(4, 6, 8, null), 10, 9, 12, hint = "Start at 8 and jump 2."),
            Stop.MultiSelect("m1-e2", "Tap two", "Tap two numbers we say when counting by 2s.", ing("✅", "Check"), tip("2, 4, 6, 8…", "2، 4، 6، 8…"), "Tap two numbers we say when counting by 2s.", listOf(t("n2", "2"), t("n5", "5"), t("n8", "8"), t("n7", "7")), listOf("n2", "n8"), 2),
            compare("m1-e3", 0, 12, 14, hint = "Find 12 and 14 on the number line. Which is bigger?"),
        )),
    ))

    val level1Variant = Play(1, 1, SourceKind.MATH, theme, listOf(
        explain("m1v-explain", 0),
        seq("m1v-s1", 1, listOf(6, 8, 10, null), 12, 11, 14, hint = "Start at 10 and jump 2."),
        count("m1v-c1", 2, "apple", 2, hint = "Count the apples two at a time."),
        seq("m1v-s2", 3, listOf(14, null, 18, 20), 16, 15, 17, hint = "What comes after 14 when we jump 2?"),
        compare("m1v-cmp", 4, 4, 10, hint = "Which number is further along the number line?"),
        count("m1v-c2", 5, "star", 5, hint = "Jump 2 for every pair of stars."),
        Stop.ExitTicket("m1v-exit", "Exit ticket", "Three last questions!", ings[6], tip("Quick checks.", "أسئلة سريعة."), listOf(
            seq("m1v-e1", 0, listOf(2, null, 6, 8), 4, 3, 5, hint = "Start at 2 and jump 2."),
            Stop.MultiSelect("m1v-e2", "Tap two", "Tap two numbers we say when counting by 2s.", ing("✅", "Check"), tip("2, 4, 6, 8…", "2، 4، 6، 8…"), "Tap two numbers we say when counting by 2s.", listOf(t("n1", "1"), t("n4", "4"), t("n6", "6"), t("n9", "9")), listOf("n4", "n6"), 2),
            compare("m1v-e3", 0, 16, 16, hint = "Are they the same?"),
        )),
    ))

    val level2 = Play(2, 0, SourceKind.MATH, theme, listOf(
        Stop.Move("m2-move", "Move your body", "Jump like the number line!", ings[0], tip("Two jumps forward each time.", "قفزتان إلى الأمام كل مرة."), listOf(MoveAction("🐸", "Jump 2 hops forward!"), MoveAction("👏", "Clap 2, 4, 6, 8!"), MoveAction("🦶", "Stamp your two feet!"))),
        seq("m2-first", 1, listOf(null, 4, 6, 8), 2, 3, 1, hint = "What comes before 4 when we jump back 2?"),
        Stop.MultiSelect("m2-two", "Which two?", "Tap the two numbers that make 10.", ings[2], tip("Not on the slide: the child must add pairs.", "غير موجود في الشريحة: على الطفل جمع الأزواج."), "Tap the two numbers that make 10.", listOf(t("a", "6"), t("b", "3"), t("c", "4"), t("d", "5")), listOf("a", "c"), 2),
        tf("m2-tf", 3, "4 + 2 = 6", true, hint = "Start at 4 and jump 2."),
        Stop.Order("m2-order", "Count up", "Put the numbers in counting order.", ings[4], tip("Smallest first.", "الأصغر أولًا."), "Put the numbers in counting-by-2s order.", listOf(OrderItem("o8", "8"), OrderItem("o2", "2"), OrderItem("o6", "6"), OrderItem("o4", "4")), listOf("o2", "o4", "o6", "o8")),
        Stop.Choice("m2-odd", "Odd one out", "Which number is NOT a counting-by-2s number?", ings[5], tip("Say the 2s pattern and listen for the missing one.", "قل نمط الاثنينات واستمع للعدد الغريب."), "Say 2, 4, 6, 8. Which one did you not say?", "Which number is not in the 2s pattern?", listOf(t("a", "4"), t("b", "7"), t("c", "8")), "b"),
        Stop.ExitTicket("m2-exit", "Exit ticket", "Three thinking questions!", ings[6], tip("Thinking questions.", "أسئلة تفكير."), listOf(
            tf("m2-e1", 0, "When we count by 2s we say 5.", false, hint = "2, 4, 6… is 5 there?"),
            Stop.MultiSelect("m2-e2", "Which two?", "Tap two numbers that come after 10 in the 2s pattern.", ing("✅", "Check"), tip("12 and 14.", "12 و14."), "Tap two numbers that come after 10 when counting by 2s.", listOf(t("a", "11"), t("b", "12"), t("c", "14"), t("d", "15")), listOf("b", "c"), 2),
            seq("m2-e3", 0, listOf(null, 12, 14, 16), 10, 11, 8, hint = "Jump back 2 from 12."),
        )),
    ))

    val level3 = Play(3, 0, SourceKind.MATH, theme, listOf(
        Stop.Move("m3-move", "Move your body", "Be a number detective!", ings[0], tip("Warm up first.", "الإحماء أولًا."), listOf(MoveAction("🔍", "Look for pairs around the room!"), MoveAction("👟", "Point to a pair of shoes!"), MoveAction("🙌", "Show two hands — that's a pair!"))),
        Stop.Choice("m3-story", "Story problem", "Sam has 3 pairs of socks. How many socks?", ings[1], tip("Ask your child to draw the pairs.", "اطلب من طفلك رسم الأزواج."), "3 pairs: 2, 4, 6.", "Sam has 3 pairs of socks. How many socks are there?", listOf(t("a", "6"), t("b", "3"), t("c", "5")), "a"),
        Stop.WriteSentence("m3-write", "Finish the sentence", "Tap the number that fits.", ings[2], tip("Read the sentence aloud together.", "اقرأا الجملة معًا."), "There are ___ shoes in 2 pairs.", "4", listOf("4", "2", "3")),
        Stop.OpenAnswer("m3-make", "Make your own", "Make your own counting-by-2s story.", ings[3], tip("A good story names something that comes in pairs — shoes, eyes, wheels — and counts them in 2s.", "القصة الجيدة تذكر شيئًا يأتي أزواجًا — أحذية، عيون، عجلات — وتعدّه بالاثنينات."), "Make your own counting-by-2s story.", "speak", "For example: I have 4 bikes. Each bike has 2 wheels: 2, 4, 6, 8 wheels."),
        count("m3-count", 4, "bee", 6, hint = "Jump 2 for every pair of bees — this one goes past 10!"),
        Stop.ExitTicket("m3-exit", "Exit ticket", "Three last questions!", ings[5], tip("Challenge checks.", "أسئلة التحدّي."), listOf(
            Stop.Choice("m3-e1", "Story problem", "Ali sees 4 bikes. How many wheels?", ing("✅", "Check"), tip("2 wheels per bike.", "عجلتان لكل دراجة."), "Each bike has 2 wheels: 2, 4, 6, 8.", "Ali sees 4 bikes. How many wheels are there?", listOf(t("a", "8"), t("b", "4"), t("c", "6")), "a"),
            Stop.MultiSelect("m3-e2", "Tap two", "Tap two things that come in pairs.", ing("✅", "Check"), tip("Shoes and socks come in pairs.", "الأحذية والجوارب تأتي أزواجًا."), "Tap two things that come in pairs.", listOf(t("a", pic = "shoe"), t("b", pic = "sun"), t("c", pic = "sock"), t("d", pic = "tree")), listOf("a", "c"), 2),
            seq("m3-e3", 0, listOf(16, 18, null, 22), 20, 19, 21, hint = "Jump 2 from 18."),
        )),
    ))

    val parentPanel = ParentPanel(
        objectives = analysis.objectives,
        supported = listOf(Bilingual("Use real pairs — shoes, socks, gloves — and count them together in 2s.", "استخدما أزواجًا حقيقية — أحذية، جوارب، قفازات — وعدّاها معًا بالاثنينات."), Bilingual("Draw a number line on paper and let your child hop a toy along it.", "ارسم خط أعداد على ورقة ودع طفلك يقفز بلعبة عليه.")),
        challenge = listOf(Bilingual("Count by 2s backwards from 20.", "عدّ بالاثنينات تنازليًا من 20."), Bilingual("Find things at home that come in pairs and count the total.", "ابحثا عن أشياء في البيت تأتي أزواجًا واحسبا المجموع.")),
        stopTips = (level1.stops + level2.stops + level3.stops).map { StopTip(it.id, it.parentTip.en, it.parentTip.ar) },
    )

    val lesson = PublishedLesson(LESSON_ID, 1, course, Subject.MATH, date, "Counting by 2s", SourceKind.MATH, theme,
        analysis.skills.map { SkillRef(it.id, it.name, it.subject, it.method) }, listOf(level1, level2, level3), level1Variant, parentPanel)
}
