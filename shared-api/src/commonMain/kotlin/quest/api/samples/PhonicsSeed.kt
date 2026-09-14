package quest.api.samples

import kotlinx.datetime.LocalDate
import quest.api.dto.AnalysedPage
import quest.api.dto.Bilingual
import quest.api.dto.BilingualList
import quest.api.dto.Course
import quest.api.dto.Curriculum
import quest.api.dto.ExtractedSkill
import quest.api.dto.MatchPair
import quest.api.dto.MoveAction
import quest.api.dto.OrderItem
import quest.api.dto.ParentPanel
import quest.api.dto.PictureOption
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.SkillRef
import quest.api.dto.SourceAnalysis
import quest.api.dto.SourceKind
import quest.api.dto.Stop
import quest.api.dto.StopTip
import quest.api.dto.Subject
import quest.api.dto.Theme
import quest.api.dto.VocabularyItem
import quest.api.dto.WordCard

/** "The 'sh' sound + sight words" — English phonics, British/1, 11 Sep 2026. Levels: recognise → build and sort → read and write. */
object PhonicsSeed {
    const val LESSON_ID = "lesson-sh-sound"
    val date = LocalDate(2026, 9, 11)
    val course = Course(Curriculum.BRITISH, 1)
    val theme = Theme("Treasure chest", "A chest of sh words", "🧰", "The chest is full of shiny sh words!")
    private val ings = listOf(ing("🐚", "Shell"), ing("⭐", "Star"), ing("💎", "Gem"), ing("🪙", "Coin"), ing("🔑", "Key"), ing("👑", "Crown"), ing("📜", "Map"), ing("🎀", "Ribbon"))

    val analysis = SourceAnalysis(
        kind = SourceKind.PHONICS, title = "The sh sound",
        pages = listOf(AnalysedPage(1, listOf("sh says shhh.", "ship, sheep, shop, shell."), "four pictures: ship, sheep, shop, shell", listOf("ship", "sheep", "shop", "shell")), AnalysedPage(4, listOf("Sight words: the, and, is."), "three word cards", emptyList())),
        skills = listOf(
            ExtractedSkill("sh-sound", "The sh sound", Subject.ENGLISH, "word family list with pictures", listOf("ship", "sheep", "shop", "shell"), listOf(1, 2), 0.92),
            ExtractedSkill("sight-words-week-3", "Sight words: the, and, is", Subject.ENGLISH, "look, say, cover, write, check", listOf("the", "and", "is"), listOf(4), 0.88),
        ),
        vocabulary = listOf(VocabularyItem("ship", "A big boat that sails on the sea.", "The ship sails.", "ship"), VocabularyItem("sheep", "A farm animal with woolly fur.", "The sheep says baa.", "sheep"), VocabularyItem("shop", "A place where we buy things.", "We go to the shop.", "shop"), VocabularyItem("shell", "A hard cover from the beach.", "I found a shell.", "shell")),
        objectives = BilingualList(listOf("Hear and say the sh sound at the start of words.", "Read ship, sheep, shop and shell.", "Read the sight words the, and, is."), listOf("سماع ونطق صوت sh في بداية الكلمات.", "قراءة ship وsheep وshop وshell.", "قراءة الكلمات البصرية the وand وis.")),
    )

    private fun sound(id: String, ingr: Int, key: String, correct: String, other: String, hint: String): Stop.Sound {
        val o = opts(correct, other)
        return Stop.Sound(id, "What sound?", "What sound does this start with?", ings[ingr], tip("Say the word slowly and stretch the first sound.", "انطق الكلمة ببطء ومدّ الصوت الأول."), hint, key, o, idOf(o, correct))
    }
    private fun word(id: String, ingr: Int, spoken: String, vararg others: String, hint: String): Stop.Word {
        val o = opts(spoken, *others)
        return Stop.Word(id, "Tap the word", "Tap the word you hear.", ings[ingr], tip("Press Listen as many times as needed.", "اضغط «استمع» كلما احتجت."), hint, spoken, o, idOf(o, spoken))
    }
    private fun readTap(id: String, ingr: Int, w: String, vararg others: String, hint: String): Stop.ReadTap {
        val o = (listOf(w) + others).mapIndexed { i, k -> PictureOption("abc"[i].toString(), k) }
        return Stop.ReadTap(id, "Read and tap", "Tap the picture for $w.", ings[ingr], tip("Sound it out: s-h-…", "انطقها صوتًا صوتًا: s-h-…"), hint, w, o, o[0].id)
    }

    val level1 = Play(1, 0, SourceKind.PHONICS, theme, listOf(
        Stop.WordCards("p1-words", "sh words", "Tap a word to hear it.", ings[0], tip("Make the shhh sound with a finger on your lips.", "اصنع صوت shhh واضعًا إصبعك على شفتيك."), analysis.vocabulary.map { WordCard(it.word, it.meaning, it.sentence, it.illustrationKey) }),
        sound("p1-s1", 1, "ship", "sh", "ch", hint = "Say the word slowly. Does it start with shhh?"),
        word("p1-w1", 2, "shop", "chop", "stop", hint = "Listen for the shhh at the start."),
        readTap("p1-r1", 3, "sheep", "ship", "fish", hint = "Sh-eep. Which animal says baa?"),
        sound("p1-s2", 4, "chair", "ch", "sh", hint = "Ch-air. Ch is like a train: ch ch ch."),
        Stop.Trace("p1-trace", "Trace it", "Trace the letter S.", ings[5], tip("Start at the top and curve like a snake.", "ابدأ من الأعلى وانحنِ مثل الأفعى."), "S", "Start at the top and curve like a snake."),
        Stop.ExitTicket("p1-exit", "Exit ticket", "Three last questions!", ings[6], tip("Quick checks.", "أسئلة سريعة."), listOf(
            sound("p1-e1", 0, "shell", "sh", "th", hint = "Sh-ell. Quiet sound at the start."),
            Stop.MultiSelect("p1-e2", "Tap two", "Tap two words that start with sh.", ing("✅", "Check"), tip("ship and shop.", "ship وshop."), "Tap two words that start with sh.", listOf(t("a", "ship", "ship"), t("b", "cat", "cat"), t("c", "shop", "shop"), t("d", "dog", "dog")), listOf("a", "c"), 2),
            readTap("p1-e3", 0, "fish", "ship", "sheep", hint = "F-i-sh. It swims!"),
        )),
    ))

    val level1Variant = Play(1, 1, SourceKind.PHONICS, theme, listOf(
        Stop.WordCards("p1v-words", "sh words", "Tap a word to hear it.", ings[0], tip("Make the shhh sound.", "اصنع صوت shhh."), listOf(WordCard("shoe", "Something we wear on our foot.", "I put on my shoe.", "shoe"), WordCard("fish", "An animal that swims.", "The fish swims.", "fish"), WordCard("shell", "A hard cover from the beach.", "I found a shell.", "shell"))),
        sound("p1v-s1", 1, "shoe", "sh", "th", hint = "Sh-oe. Shhh at the start."),
        word("p1v-w1", 2, "ship", "chip", "shop", hint = "Listen for the whole word: sh-ip."),
        readTap("p1v-r1", 3, "shop", "ship", "shell", hint = "Sh-op. Where do we buy things?"),
        sound("p1v-s2", 4, "thumb", "th", "sh", hint = "Th-umb. Tongue between your teeth."),
        Stop.Trace("p1v-trace", "Trace it", "Trace the letter h.", ings[5], tip("A tall line, then a bump.", "خط طويل ثم نتوء."), "h", "A tall line, then a bump."),
        Stop.ExitTicket("p1v-exit", "Exit ticket", "Three last questions!", ings[6], tip("Quick checks.", "أسئلة سريعة."), listOf(
            sound("p1v-e1", 0, "sheep", "sh", "ch", hint = "Sh-eep."),
            Stop.MultiSelect("p1v-e2", "Tap two", "Tap two words that start with sh.", ing("✅", "Check"), tip("shell and shoe.", "shell وshoe."), "Tap two words that start with sh.", listOf(t("a", "shell", "shell"), t("b", "sun", "sun"), t("c", "shoe", "shoe"), t("d", "bee", "bee")), listOf("a", "c"), 2),
            readTap("p1v-e3", 0, "ship", "shop", "chair", hint = "Sh-ip. It sails!"),
        )),
    ))

    val level2 = Play(2, 0, SourceKind.PHONICS, theme, listOf(
        Stop.Move("p2-move", "Move your body", "Shhh like the wind!", ings[0], tip("Actions first.", "الحركة أولًا."), listOf(MoveAction("🤫", "Finger on lips: shhh!"), MoveAction("🚢", "Rock like a ship on the sea!"), MoveAction("🐑", "Wiggle like a woolly sheep!"))),
        Stop.MultiSelect("p2-two", "Which two?", "Tap the two words that start with sh.", ings[1], tip("Say each word first.", "انطق كل كلمة أولًا."), "Tap the two words that start with sh.", listOf(t("a", pic = "ship"), t("b", pic = "chair"), t("c", pic = "shop"), t("d", pic = "thumb")), listOf("a", "c"), 2),
        Stop.Choice("p2-odd", "Odd one out", "Which one does NOT start with sh?", ings[2], tip("Three start with sh, one does not.", "ثلاث كلمات تبدأ بـ sh وواحدة لا."), "Say each word: which first sound is different?", "Which one does not start with sh?", listOf(t("a", pic = "sheep"), t("b", pic = "cheese"), t("c", pic = "shell")), "b"),
        Stop.Match("p2-match", "Match up", "Match each word to its picture.", ings[3], tip("Read the word before choosing.", "اقرأ الكلمة قبل الاختيار."), "Match the word to the picture.", listOf(MatchPair("m1", t("l1", "ship"), t("r1", pic = "ship")), MatchPair("m2", t("l2", "shell"), t("r2", pic = "shell")), MatchPair("m3", t("l3", "sheep"), t("r3", pic = "sheep")))),
        Stop.Order("p2-spell", "Build the word", "Put the letters in order to spell ship.", ings[4], tip("Say each sound as you place it: s-h-i-p.", "انطق كل صوت أثناء وضعه: s-h-i-p."), "Put the letters in order to spell ship.", listOf(OrderItem("i", "i"), OrderItem("p", "p"), OrderItem("s", "s"), OrderItem("h", "h")), listOf("s", "h", "i", "p")),
        Stop.SelectAll("p2-all", "Tap all the sh words", "Tap every word that starts with sh.", ings[5], tip("Keep looking until all are found.", "استمر في البحث حتى تجدها كلها."), "Tap every word that starts with sh.", listOf(t("a", "shop"), t("b", "cat"), t("c", "shell"), t("d", "ship"), t("e", "sun")), listOf("a", "c", "d")),
        Stop.ExitTicket("p2-exit", "Exit ticket", "Three thinking questions!", ings[6], tip("Thinking questions.", "أسئلة تفكير."), listOf(
            Stop.TrueFalse("p2-e1", "True or false?", "Chair starts with sh.", ing("✅", "Check"), tip("It starts with ch.", "تبدأ بـ ch."), "Ch-air. Is that sh or ch?", "Chair starts with sh.", false),
            Stop.MultiSelect("p2-e2", "Which two?", "Tap two words that rhyme with shop.", ing("✅", "Check"), tip("hop and top.", "hop وtop."), "Tap two words that rhyme with shop.", listOf(t("a", "hop"), t("b", "ship"), t("c", "top"), t("d", "sheep")), listOf("a", "c"), 2),
            word("p2-e3", 0, "the", "she", "he", hint = "Listen for the t-h at the start."),
        )),
    ))

    val level3 = Play(3, 0, SourceKind.PHONICS, theme, listOf(
        Stop.Move("p3-move", "Move your body", "Write sh in the air!", ings[0], tip("Big arm movements.", "حركات كبيرة بالذراع."), listOf(MoveAction("✍️", "Write a giant s in the air!"), MoveAction("🙆", "Now a tall h!"), MoveAction("🤫", "Say shhh together!"))),
        Stop.Trace("p3-trace", "Write the word", "Trace the word ship.", ings[1], tip("Say s-h-i-p while tracing.", "انطق s-h-i-p أثناء الكتابة."), "ship", "Say each sound as you trace."),
        Stop.WriteSentence("p3-write", "Finish the sentence", "Trace the missing word.", ings[2], tip("Read the sentence aloud after writing.", "اقرأ الجملة بصوت عالٍ بعد الكتابة."), "The ___ sails on the sea.", "ship", free = true),
        Stop.Choice("p3-read", "Read the sentence", "The sheep is in the shop. Which picture matches?", ings[3], tip("Read the whole sentence together first.", "اقرأا الجملة كاملة معًا أولًا."), "Which animal? Where is it?", "The sheep is in the shop. Tap the picture that matches.", listOf(t("a", "sheep in a shop", "sheep"), t("b", "a ship at sea", "ship"), t("c", "a fish in a bath", "fish")), "a"),
        Stop.OpenAnswer("p3-say", "Your sentence", "Say a sentence with a sh word.", ings[4], tip("Any sentence with ship, shop, sheep or shell counts.", "أي جملة فيها ship أو shop أو sheep أو shell تُقبل."), "Say a sentence with a sh word.", "speak", "For example: I see a sheep at the shop."),
        Stop.ExitTicket("p3-exit", "Exit ticket", "Three last questions!", ings[5], tip("Challenge checks.", "أسئلة التحدّي."), listOf(
            Stop.SelectAll("p3-e1", "Tap the sh words", "Tap every picture that starts with sh.", ing("✅", "Check"), tip("ship, shell, shoe.", "ship وshell وshoe."), "Tap every picture that starts with sh.", listOf(t("a", pic = "ship"), t("b", pic = "cat"), t("c", pic = "shell"), t("d", pic = "shoe"), t("e", pic = "chair")), listOf("a", "c", "d")),
            word("p3-e2", 0, "and", "is", "the", hint = "It starts with a."),
            Stop.Choice("p3-e3", "Which word?", "Which word finishes: I found a ___ on the beach?", ing("✅", "Check"), tip("shell.", "shell."), "What do we find on the beach?", "I found a ___ on the beach.", listOf(t("a", "shell"), t("b", "shop"), t("c", "ship")), "a"),
        )),
    ))

    val parentPanel = ParentPanel(
        objectives = analysis.objectives,
        supported = listOf(Bilingual("Hunt for sh things around the house: shoes, shirt, shampoo.", "ابحثا عن أشياء بصوت sh في البيت: حذاء، قميص، شامبو."), Bilingual("Say the sound, not the letter names: 'shhh', not 'ess-aitch'.", "انطق الصوت لا أسماء الحروف: «شhhh» لا «إس-إتش».")),
        challenge = listOf(Bilingual("Write three sh words and draw them.", "اكتب ثلاث كلمات بصوت sh وارسمها."), Bilingual("Make up a silly sentence using ship, sheep and shop.", "ألّف جملة مضحكة فيها ship وsheep وshop.")),
        stopTips = (level1.stops + level2.stops + level3.stops).map { StopTip(it.id, it.parentTip.en, it.parentTip.ar) },
    )

    val lesson = PublishedLesson(LESSON_ID, 1, course, Subject.ENGLISH, date, "The sh sound + sight words", SourceKind.PHONICS, theme,
        analysis.skills.map { SkillRef(it.id, it.name, it.subject, it.method) }, listOf(level1, level2, level3), level1Variant, parentPanel)
}
