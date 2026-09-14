package quest.api.samples

import kotlinx.datetime.LocalDate
import quest.api.dto.AnalysedEvent
import quest.api.dto.AnalysedPage
import quest.api.dto.Bilingual
import quest.api.dto.BilingualList
import quest.api.dto.Course
import quest.api.dto.Curriculum
import quest.api.dto.ExtractedSkill
import quest.api.dto.Fact
import quest.api.dto.Hotspot
import quest.api.dto.MatchPair
import quest.api.dto.ModelAnswer
import quest.api.dto.MoveAction
import quest.api.dto.OrderItem
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.RetellCue
import quest.api.dto.SkillRef
import quest.api.dto.SourceAnalysis
import quest.api.dto.SourceKind
import quest.api.dto.Stop
import quest.api.dto.StopTip
import quest.api.dto.StoryCard
import quest.api.dto.StoryPiecesInfo
import quest.api.dto.Subject
import quest.api.dto.TapTask
import quest.api.dto.Theme
import quest.api.dto.VocabularyItem
import quest.api.dto.WordCard

/**
 * "Hot Soup for Mummy · Part 1" — Grade 1 reading, British/1, 14 Sep 2026.
 *
 * PLACEHOLDER: `docs/example-play.html` was not available when this seed was written, so the stops follow
 * the description in the dev prompt (move, story pieces, read pages with a fridge tap task, true or false,
 * new words, match up, order, exit ticket with "Tap two vegetables from the soup"). Replace stop for stop
 * once the file is in the repo.
 */
object HotSoupSeed {
    const val LESSON_ID = "lesson-hot-soup-1"
    val date = LocalDate(2026, 9, 14)
    val course = Course(Curriculum.BRITISH, 1)
    val theme = Theme(potName = "Soup pot", dishName = "Hot soup for Mummy", potEmoji = "🍲", servedText = "Mummy sips the hot soup and smiles!")

    private val pages = listOf(
        AnalysedPage(1, listOf("Mummy is in bed.", "She has a cold.", "Alan wants to help."), "Mummy in bed with a blanket, Alan at the door", listOf("mummy", "bed", "boy")),
        AnalysedPage(2, listOf("Alan and Daddy look in the fridge.", "They find a carrot, a potato and an onion.", "They find peas too!"), "an open fridge full of vegetables", listOf("fridge", "carrot", "potato", "onion", "pea")),
        AnalysedPage(3, listOf("Daddy chops the vegetables.", "Alan puts them in the pot.", "The pot goes on the fire."), "Daddy chopping, Alan with a big pot", listOf("pot", "fire", "daddy")),
        AnalysedPage(4, listOf("Alan stirs and stirs.", "The soup gets hot.", "It smells so good!"), "Alan stirring a steaming pot with a spoon", listOf("spoon", "soup")),
        AnalysedPage(5, listOf("Alan carries the bowl up the stairs.", "He walks slowly, like on a hike.", "Mummy sips the soup and smiles."), "Alan carrying a bowl of soup to Mummy", listOf("bowl", "mummy", "hike")),
    )

    val analysis = SourceAnalysis(
        kind = SourceKind.STORY,
        title = "Hot Soup for Mummy",
        pages = pages,
        skills = listOf(
            ExtractedSkill("story-retell", "Retelling a story", Subject.ENGLISH, "story pieces: characters, setting, problem, resolution", listOf("Mummy has a cold", "Alan and Daddy make soup"), listOf(1, 2, 3, 4, 5), 0.93),
            ExtractedSkill("vocabulary-soup", "New words: fridge, pot, stir, sip", Subject.ENGLISH, "words with pictures from the story", listOf("fridge", "pot", "stir", "sip"), listOf(2, 3, 4, 5), 0.9),
        ),
        vocabulary = listOf(
            VocabularyItem("fridge", "A cold box that keeps food fresh.", "Alan and Daddy look in the fridge.", "fridge"),
            VocabularyItem("pot", "A big pan for cooking soup.", "Alan puts them in the pot.", "pot"),
            VocabularyItem("stir", "To move the spoon round and round.", "Alan stirs and stirs.", "spoon"),
            VocabularyItem("sip", "To drink a tiny bit at a time.", "Mummy sips the soup and smiles.", "bowl"),
        ),
        storyPieces = StoryPiecesInfo("Hot Soup for Mummy", "real", listOf("Alan", "Daddy", "Mummy"), "At home, in the kitchen and Mummy's bedroom", "Mummy has a cold and cannot get up.", "Alan and Daddy make hot soup and Mummy feels better."),
        events = listOf(
            AnalysedEvent("e1", "Mummy is in bed with a cold.", 1),
            AnalysedEvent("e2", "Alan and Daddy find vegetables in the fridge.", 2),
            AnalysedEvent("e3", "They chop and cook the vegetables in a pot.", 3),
            AnalysedEvent("e4", "Alan stirs the hot soup.", 4),
            AnalysedEvent("e5", "Alan carries the soup to Mummy.", 5),
        ),
        facts = listOf(
            Fact("f1", "Mummy has a cold.", "Mummy has a broken leg.", 1),
            Fact("f2", "They find a carrot in the fridge.", "They find a fish in the fridge.", 2),
            Fact("f3", "Daddy chops the vegetables.", "Mummy chops the vegetables.", 3),
            Fact("f4", "Alan stirs the soup.", "Daddy eats the soup.", 4),
            Fact("f5", "Alan carries the bowl up the stairs.", "Alan throws the bowl away.", 5),
        ),
        objectives = BilingualList(
            en = listOf("Retell the story in order: beginning, middle, end.", "Name the characters, the setting and the problem.", "Use the new words fridge, pot, stir and sip.", "Say true or false about what happened."),
            ar = listOf("إعادة سرد القصة بالترتيب: البداية، الوسط، النهاية.", "تسمية الشخصيات والمكان والمشكلة.", "استخدام الكلمات الجديدة: ثلاجة، قدر، يحرّك، يرتشف.", "تحديد صواب أو خطأ ما حدث في القصة."),
        ),
    )

    private val ingredients = listOf(ing("🥕", "Carrot"), ing("🥔", "Potato"), ing("🧅", "Onion"), ing("🫛", "Peas"), ing("🍅", "Tomato"), ing("🌽", "Corn"), ing("🧂", "Salt"), ing("💧", "Water"), ing("🌿", "Herbs"))

    val level1 = Play(level = 1, variant = 0, kind = SourceKind.STORY, theme = theme, stops = listOf(
        Stop.Move("hs1-move", "Move your body", "Let's warm up like Alan!", ingredients[0], tip("Do the actions together; it wakes the body up before reading.", "قوما بالحركات معًا؛ فهذا ينشّط الجسم قبل القراءة."),
            actions = listOf(MoveAction("🥾", "Stomp like you're on a hike!"), MoveAction("🥄", "Stir a big pot of soup!"), MoveAction("👃", "Sniff the yummy soup!"), MoveAction("🥣", "Carry a bowl very carefully!"))),
        Stop.StoryPieces("hs1-pieces", "Meet the story pieces", "Tap each card to hear what it means.", ingredients[1], tip("Ask: who is in the story? Where does it happen?", "اسأل: من في القصة؟ أين تحدث؟"),
            cards = listOf(
                StoryCard("title", "The name of the story.", "Hot Soup for Mummy"),
                StoryCard("genre", "Real or fantasy? Could it really happen?", "Real. A family really could make soup."),
                StoryCard("characters", "The people in the story.", "Alan, Daddy and Mummy"),
                StoryCard("setting", "Where and when the story happens.", "At home: the kitchen and Mummy's bedroom"),
                StoryCard("plot", "What happens in the story.", "Alan and Daddy make hot soup for Mummy."),
                StoryCard("problem", "The trouble that needs fixing.", "Mummy has a cold and cannot get up."),
            )),
        Stop.ReadPage("hs1-page1", "Read: Mummy is in bed", "Listen and follow the words.", ingredients[2], tip("Let your child tap the speaker and repeat each sentence.", "دع طفلك يضغط على السماعة ويكرر كل جملة."),
            pageNumber = 1, sentences = pages[0].childText, pictureDescription = pages[0].pictureDescription, illustrationKey = "bed"),
        Stop.ReadPage("hs1-page2", "Read: The fridge", "Read the page, then tap the vegetables in the fridge.", ingredients[3], tip("Name each vegetable in the fridge out loud.", "سمِّ كل خضار في الثلاجة بصوت عالٍ."),
            pageNumber = 2, sentences = pages[1].childText, pictureDescription = pages[1].pictureDescription, illustrationKey = "fridge",
            tapTask = TapTask("Tap the vegetables in the fridge.", listOf(
                Hotspot("h-carrot", "carrot", 0.08f, 0.2f, 0.26f, 0.26f), Hotspot("h-potato", "potato", 0.38f, 0.2f, 0.26f, 0.26f), Hotspot("h-milk", "milk", 0.68f, 0.2f, 0.26f, 0.26f),
                Hotspot("h-onion", "onion", 0.08f, 0.56f, 0.26f, 0.26f), Hotspot("h-egg", "egg", 0.38f, 0.56f, 0.26f, 0.26f), Hotspot("h-peas", "peas", 0.68f, 0.56f, 0.26f, 0.26f)),
                correctIds = listOf("h-carrot", "h-potato", "h-onion", "h-peas"))),
        Stop.ReadPage("hs1-page3", "Read: Making the soup", "Listen and follow the words.", ingredients[4], tip("Ask: what does Daddy do? What does Alan do?", "اسأل: ماذا يفعل بابا؟ وماذا يفعل آلان؟"),
            pageNumber = 3, sentences = pages[2].childText + pages[3].childText.take(2), pictureDescription = pages[2].pictureDescription, illustrationKey = "pot"),
        Stop.WordCards("hs1-words", "New words", "Tap a word to hear it.", ingredients[5], tip("Use each new word in a sentence about your own kitchen.", "استخدم كل كلمة جديدة في جملة عن مطبخكم."),
            words = analysis.vocabulary.map { WordCard(it.word, it.meaning, it.sentence, it.illustrationKey) }),
        Stop.Match("hs1-match", "Match up", "Match each word to its picture.", ingredients[6], tip("If a match is wrong, read the word slowly together.", "إذا كان الربط خاطئًا، اقرأا الكلمة ببطء معًا."),
            prompt = "Match the word to the picture.", pairs = listOf(
                MatchPair("m1", t("m1l", label = "carrot"), t("m1r", pic = "carrot")), MatchPair("m2", t("m2l", label = "pot"), t("m2r", pic = "pot")),
                MatchPair("m3", t("m3l", label = "spoon"), t("m3r", pic = "spoon")), MatchPair("m4", t("m4l", label = "bowl"), t("m4r", pic = "bowl")))),
        Stop.Order("hs1-order", "What happened first?", "Put the story in order.", ingredients[7], tip("Say 'first, next, then, last' as you go.", "قل: أولًا، ثم، بعد ذلك، أخيرًا أثناء الترتيب."),
            prompt = "Put the story in order.", items = listOf(
                OrderItem("o1", "Mummy is in bed with a cold.", "bed"), OrderItem("o2", "Alan and Daddy find vegetables.", "fridge"),
                OrderItem("o3", "The soup cooks in the pot.", "pot"), OrderItem("o4", "Alan carries the soup to Mummy.", "bowl")),
            correctOrder = listOf("o1", "o2", "o3", "o4")),
        Stop.ExitTicket("hs1-exit", "Exit ticket", "Three last questions!", ingredients[8], tip("Three quick checks of what was read today.", "ثلاثة أسئلة سريعة عمّا قُرئ اليوم."), questions = listOf(
            Stop.Choice("hs1-exit-1", "Who has a cold?", "Who has a cold?", ing("✅", "Check"), tip("Look at page 1.", "انظر إلى الصفحة 1."), hint = "Who is in bed on page 1?", question = "Who has a cold?",
                options = listOf(t("a", "Mummy", "mummy"), t("b", "Daddy", "daddy"), t("c", "Alan", "boy")), correctOptionId = "a"),
            Stop.MultiSelect("hs1-exit-2", "Tap two vegetables", "Tap two vegetables from the soup.", ing("✅", "Check"), tip("The soup had a carrot, a potato, an onion and peas.", "الحساء فيه جزر وبطاطس وبصل وبازلاء."),
                prompt = "Tap two vegetables from the soup.", options = listOf(t("v1", pic = "carrot"), t("v2", pic = "fish"), t("v3", pic = "potato"), t("v4", pic = "cake")), correctIds = listOf("v1", "v3"), pick = 2),
            Stop.TrueFalse("hs1-exit-3", "True or false?", "Alan carries the bowl up the stairs.", ing("✅", "Check"), tip("Page 5 tells us.", "الصفحة 5 تخبرنا."), hint = "Remember the last page: Alan walks slowly with the bowl.", statement = "Alan carries the bowl up the stairs.", answer = true),
        )),
    ))

    /** "Again" — same level, different questions and distractors. */
    val level1Variant = Play(level = 1, variant = 1, kind = SourceKind.STORY, theme = theme, stops = listOf(
        Stop.Move("hs1v-move", "Move your body", "Warm up like Alan!", ingredients[0], tip("Do the actions together.", "قوما بالحركات معًا."),
            actions = listOf(MoveAction("🧊", "Open the cold fridge — brrr!"), MoveAction("🔪", "Chop, chop, chop the carrot!"), MoveAction("🔥", "Wiggle like the flames!"), MoveAction("😊", "Smile like Mummy!"))),
        Stop.ReadPage("hs1v-page4", "Read: Stir the soup", "Listen and follow the words.", ingredients[1], tip("Ask what the soup smells like.", "اسأل: ما رائحة الحساء؟"),
            pageNumber = 4, sentences = pages[3].childText, pictureDescription = pages[3].pictureDescription, illustrationKey = "spoon"),
        Stop.ReadPage("hs1v-page5", "Read: Up the stairs", "Read, then tap what Alan carries.", ingredients[2], tip("Ask why Alan walks slowly.", "اسأل: لماذا يمشي آلان ببطء؟"),
            pageNumber = 5, sentences = pages[4].childText, pictureDescription = pages[4].pictureDescription, illustrationKey = "bowl",
            tapTask = TapTask("Tap what Alan carries to Mummy.", listOf(Hotspot("h-bowl", "bowl", 0.1f, 0.3f, 0.35f, 0.4f), Hotspot("h-ball", "ball", 0.55f, 0.3f, 0.35f, 0.4f)), correctIds = listOf("h-bowl"))),
        Stop.TrueFalse("hs1v-tf1", "True or false?", "Daddy chops the vegetables.", ingredients[3], tip("Page 3.", "الصفحة 3."), hint = "Who holds the knife on page 3?", statement = "Daddy chops the vegetables.", answer = true),
        Stop.TrueFalse("hs1v-tf2", "True or false?", "They find a fish in the fridge.", ingredients[4], tip("Page 2.", "الصفحة 2."), hint = "Was there a fish, or vegetables?", statement = "They find a fish in the fridge.", answer = false),
        Stop.Match("hs1v-match", "Match up", "Match each word to its picture.", ingredients[5], tip("Read the word first, then find the picture.", "اقرأ الكلمة أولًا ثم ابحث عن الصورة."),
            prompt = "Match the word to the picture.", pairs = listOf(MatchPair("m1", t("m1l", label = "onion"), t("m1r", pic = "onion")), MatchPair("m2", t("m2l", label = "peas"), t("m2r", pic = "pea")), MatchPair("m3", t("m3l", label = "fridge"), t("m3r", pic = "fridge")))),
        Stop.Order("hs1v-order", "What happened first?", "Put the story in order.", ingredients[6], tip("Use the pictures as clues.", "استخدم الصور كدلائل."),
            prompt = "Put the story in order.", items = listOf(OrderItem("o1", "Alan stirs the soup.", "spoon"), OrderItem("o2", "Mummy sips the soup.", "mummy"), OrderItem("o3", "Daddy chops the vegetables.", "carrot")), correctOrder = listOf("o3", "o1", "o2")),
        Stop.ExitTicket("hs1v-exit", "Exit ticket", "Three last questions!", ingredients[7], tip("Quick checks.", "أسئلة سريعة."), questions = listOf(
            Stop.Choice("hs1v-exit-1", "Where is Mummy?", "Where is Mummy?", ing("✅", "Check"), tip("Page 1.", "الصفحة 1."), hint = "Page 1 says Mummy is in…", question = "Where is Mummy?", options = listOf(t("a", "In bed", "bed"), t("b", "In the car", "car"), t("c", "At school", "school")), correctOptionId = "a"),
            Stop.MultiSelect("hs1v-exit-2", "Tap two", "Tap two things Alan does.", ing("✅", "Check"), tip("Alan stirs and carries.", "آلان يحرّك ويحمل."), prompt = "Tap two things Alan does.", options = listOf(t("x1", "Stirs the soup", "spoon"), t("x2", "Drives a car", "car"), t("x3", "Carries the bowl", "bowl"), t("x4", "Flies a kite", "kite")), correctIds = listOf("x1", "x3"), pick = 2),
            Stop.TrueFalse("hs1v-exit-3", "True or false?", "The soup smells bad.", ing("✅", "Check"), tip("Page 4.", "الصفحة 4."), hint = "Page 4 says it smells so…", statement = "The soup smells bad.", answer = false),
        )),
    ))

    val level2 = Play(level = 2, variant = 0, kind = SourceKind.STORY, theme = theme, stops = listOf(
        Stop.Move("hs2-move", "Move your body", "Warm up like a helper!", ingredients[0], tip("Actions first, thinking next.", "الحركة أولًا ثم التفكير."),
            actions = listOf(MoveAction("🤒", "Shiver like you have a cold!"), MoveAction("🤔", "Think hard: how can we help?"), MoveAction("🥕", "Grab a carrot from the fridge!"))),
        Stop.Choice("hs2-why", "Why?", "Why do Alan and Daddy make soup?", ingredients[1], tip("The answer is not written; the child must connect the cold and the soup.", "الجواب ليس مكتوبًا؛ على الطفل أن يربط بين الزكام والحساء."),
            hint = "Mummy has a cold. What helps when you are ill?", question = "Why do Alan and Daddy make soup?",
            options = listOf(t("a", "To help Mummy feel better", "heart"), t("b", "Because they are bored", "ball"), t("c", "For a party", "balloon")), correctOptionId = "a"),
        Stop.MultiSelect("hs2-two", "Which two?", "Tap two things you need to make soup.", ingredients[2], tip("Think about what was in the story: a pot and vegetables.", "فكّر فيما ورد في القصة: قدر وخضار."),
            prompt = "Tap two things you need to make soup.", options = listOf(t("p1", pic = "pot"), t("p2", pic = "kite"), t("p3", pic = "carrot"), t("p4", pic = "drum")), correctIds = listOf("p1", "p3"), pick = 2),
        Stop.TrueFalse("hs2-infer", "Think: true or false?", "Alan walks slowly so the soup does not spill.", ingredients[3], tip("Not written on the page — your child must infer why he walks slowly.", "غير مكتوب في الصفحة — على طفلك أن يستنتج سبب مشيه ببطء."),
            hint = "What happens to hot soup if you run?", statement = "Alan walks slowly so the soup does not spill.", answer = true),
        Stop.Match("hs2-cause", "Cause and effect", "Match what happens with why.", ingredients[4], tip("Say 'because' when you match.", "قل «لأن» عند الربط."),
            prompt = "Match each thing to its reason.", pairs = listOf(
                MatchPair("c1", t("c1l", "Mummy stays in bed"), t("c1r", "because she has a cold")),
                MatchPair("c2", t("c2l", "They open the fridge"), t("c2r", "to find vegetables")),
                MatchPair("c3", t("c3l", "The soup gets hot"), t("c3r", "because the pot is on the fire")))),
        Stop.Order("hs2-order", "Steps to make soup", "Put the steps in order.", ingredients[5], tip("Cooking order, not story order.", "ترتيب الطبخ لا ترتيب القصة."),
            prompt = "Put the soup steps in order.", items = listOf(OrderItem("s1", "Find the vegetables", "fridge"), OrderItem("s2", "Chop them", "carrot"), OrderItem("s3", "Cook them in the pot", "pot"), OrderItem("s4", "Stir until hot", "spoon"), OrderItem("s5", "Carry the bowl to Mummy", "bowl")), correctOrder = listOf("s1", "s2", "s3", "s4", "s5")),
        Stop.WriteSentence("hs2-write", "Finish the sentence", "Tap the word that fits.", ingredients[6], tip("Read the whole sentence back together.", "اقرأا الجملة كاملة معًا."),
            frame = "Alan and Daddy make ___ for Mummy.", answer = "soup", options = listOf("soup", "cake", "tea")),
        Stop.ExitTicket("hs2-exit", "Exit ticket", "Three thinking questions!", ingredients[7], tip("Thinking questions.", "أسئلة تفكير."), questions = listOf(
            Stop.Choice("hs2-exit-1", "How does Mummy feel at the end?", "How does Mummy feel at the end?", ing("✅", "Check"), tip("She smiles.", "إنها تبتسم."), hint = "She sips the soup and smiles.", question = "How does Mummy feel at the end?", options = listOf(t("a", "Happy", "heart"), t("b", "Angry", "fire"), t("c", "Scared", "cloud")), correctOptionId = "a"),
            Stop.MultiSelect("hs2-exit-2", "Tap two helpers", "Tap the two people who help.", ing("✅", "Check"), tip("Alan and Daddy.", "آلان وبابا."), prompt = "Tap the two people who help.", options = listOf(t("h1", "Alan", "boy"), t("h2", "Mummy", "mummy"), t("h3", "Daddy", "daddy")), correctIds = listOf("h1", "h3"), pick = 2),
            Stop.TrueFalse("hs2-exit-3", "Think: true or false?", "The story could really happen.", ing("✅", "Check"), tip("It is a real story, not fantasy.", "قصة واقعية لا خيالية."), hint = "Can a family make soup at home?", statement = "The story could really happen.", answer = true),
        )),
    ))

    val level3 = Play(level = 3, variant = 0, kind = SourceKind.STORY, theme = theme, stops = listOf(
        Stop.Move("hs3-move", "Move your body", "Warm up like a chef!", ingredients[0], tip("Actions first.", "الحركة أولًا."),
            actions = listOf(MoveAction("👨‍🍳", "Put on your chef hat!"), MoveAction("🥄", "Taste the soup — yum!"), MoveAction("🛏️", "Tuck Mummy in gently!"))),
        Stop.Retell("hs3-retell", "Tell the story", "Tell the story in your own words.", ingredients[1], tip("Listen for beginning, middle and end. A good retell names Mummy's cold, making the soup, and Mummy smiling.", "استمع للبداية والوسط والنهاية. الإعادة الجيدة تذكر زكام ماما، وصنع الحساء، وابتسامة ماما."),
            prompt = "Tell the story in your own words.", cues = listOf(RetellCue("beginning", "Mummy is in bed…", "bed"), RetellCue("middle", "Alan and Daddy…", "pot"), RetellCue("end", "Then Mummy…", "mummy")),
            modelAnswer = "Mummy had a cold and stayed in bed. Alan and Daddy found vegetables in the fridge, chopped them and cooked hot soup. Alan carried the soup up to Mummy and she felt better."),
        Stop.OpenAnswer("hs3-apply", "Your idea", "Think of another way Alan could help Mummy.", ingredients[2], tip("Any caring idea counts: a blanket, a drink, reading to her. Ask 'why would that help?'", "أي فكرة تدل على الاهتمام تُقبل: بطانية، مشروب، القراءة لها. اسأل: لماذا سيساعد ذلك؟"),
            prompt = "Think of another way Alan could help Mummy.", mode = "both", modelAnswer = "Alan could bring Mummy a warm blanket, make her a cup of tea, or read her a story so she can rest."),
        Stop.WriteSentence("hs3-write", "Write it", "Trace the missing word.", ingredients[3], tip("Say the sounds s-ou-p as they trace.", "انطق الأصوات س-و-ب أثناء الكتابة."),
            frame = "Alan and Daddy make ___ for Mummy.", answer = "soup", free = true),
        Stop.Choice("hs3-feeling", "How would you feel?", "How would Alan feel when Mummy smiles?", ingredients[4], tip("Talk about a time your child helped someone.", "تحدثا عن مرة ساعد فيها طفلك أحدًا."),
            hint = "Helping someone makes us feel…", question = "How would Alan feel when Mummy smiles?", options = listOf(t("a", "Proud and happy", "heart"), t("b", "Sad", "rain"), t("c", "Sleepy", "moon")), correctOptionId = "a"),
        Stop.ExitTicket("hs3-exit", "Exit ticket", "Three last questions!", ingredients[5], tip("Challenge checks.", "أسئلة التحدّي."), questions = listOf(
            Stop.SelectAll("hs3-exit-1", "Tap all the vegetables", "Tap everything that is a vegetable.", ing("✅", "Check"), tip("Carrot, potato, onion, peas.", "جزر، بطاطس، بصل، بازلاء."), prompt = "Tap everything that is a vegetable.", options = listOf(t("a1", pic = "carrot"), t("a2", pic = "cake"), t("a3", pic = "onion"), t("a4", pic = "pea"), t("a5", pic = "ball")), correctIds = listOf("a1", "a3", "a4")),
            Stop.Order("hs3-exit-2", "Order", "Put the story in order.", ing("✅", "Check"), tip("Beginning, middle, end.", "بداية، وسط، نهاية."), prompt = "Put the story in order.", items = listOf(OrderItem("b1", "Mummy has a cold"), OrderItem("b2", "They make soup"), OrderItem("b3", "Mummy smiles")), correctOrder = listOf("b1", "b2", "b3")),
            Stop.Choice("hs3-exit-3", "Best title", "Which is another good title?", ing("✅", "Check"), tip("A title tells the big idea.", "العنوان يخبر بالفكرة الكبيرة."), hint = "The story is about helping Mummy.", question = "Which is another good title?", options = listOf(t("t1", "Helping Mummy", "heart"), t("t2", "The Fast Car", "car"), t("t3", "A Day at School", "school")), correctOptionId = "t1"),
        )),
    ))

    val parentPanel = ParentPanel(
        objectives = analysis.objectives,
        supported = listOf(
            Bilingual("Read each page aloud first, then let your child read it back with the speaker button.", "اقرأ كل صفحة بصوت عالٍ أولًا ثم دع طفلك يعيدها مستخدمًا زر السماعة."),
            Bilingual("For the order game, act out the steps with real spoons and pots.", "في لعبة الترتيب، مثّلا الخطوات بملاعق وقدور حقيقية."),
        ),
        challenge = listOf(
            Bilingual("Ask your child to tell the story to a toy, using 'first, next, last'.", "اطلب من طفلك أن يحكي القصة للعبة مستخدمًا: أولًا، ثم، أخيرًا."),
            Bilingual("Make a real soup together and name every ingredient in English.", "اصنعا حساءً حقيقيًا معًا وسمّيا كل مكوّن بالإنجليزية."),
        ),
        stopTips = (level1.stops + level2.stops + level3.stops).map { StopTip(it.id, it.parentTip.en, it.parentTip.ar) },
        modelAnswers = listOf(ModelAnswer("hs3-retell", (level3.stops[1] as Stop.Retell).modelAnswer), ModelAnswer("hs3-apply", (level3.stops[2] as Stop.OpenAnswer).modelAnswer)),
    )

    val lesson = PublishedLesson(
        id = LESSON_ID, version = 1, course = course, subject = Subject.ENGLISH, date = date, title = "Hot Soup for Mummy · Part 1", kind = SourceKind.STORY, theme = theme,
        skills = analysis.skills.map { SkillRef(it.id, it.name, it.subject, it.method) },
        plays = listOf(level1, level2, level3), variant = level1Variant, parentPanel = parentPanel,
    )
}
