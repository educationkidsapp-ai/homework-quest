package quest.admin.feature.editor.presentation

import quest.api.dto.Bilingual
import quest.api.dto.Ingredient
import quest.api.dto.MatchPair
import quest.api.dto.MoveAction
import quest.api.dto.NumberLine
import quest.api.dto.Option
import quest.api.dto.OrderItem
import quest.api.dto.PictureOption
import quest.api.dto.RetellCue
import quest.api.dto.Stop
import quest.api.dto.StoryCard
import quest.api.dto.Tile
import quest.api.dto.WordCard
import quest.api.dto.WorkedExample

/** Every stop type from dev prompt §5 with a small, valid example the admin edits in place ("+ Add stop" menu). */
object StopTemplates {
    data class Entry(val type: String, val label: String, val group: String, val make: (id: String, math: Boolean) -> Stop)

    private val tip = Bilingual("Read the question aloud together first.", "اقرآ السؤال معًا بصوت عالٍ أولًا.")
    private fun ing(math: Boolean) = if (math) Ingredient("🥕", "carrot") else Ingredient("🍅", "tomato")

    val all: List<Entry> = listOf(
        Entry("readPage", "Read a page", "Information") { id, m -> Stop.ReadPage(id, "Read the page", "Let's read this page together.", ing(m), tip, 1, listOf("Write the first sentence here.", "And a second one.")) },
        Entry("storyPieces", "Story pieces", "Information") { id, m -> Stop.StoryPieces(id, "Meet the story pieces", "Tap each card to hear what it means.", ing(m), tip,
            listOf(StoryCard("title", "The name of the story", "Our story"), StoryCard("genre", "What kind of story", "Real"), StoryCard("characters", "Who is in it", "The children"), StoryCard("setting", "Where it happens", "At school"), StoryCard("plot", "What happens", "They learn something new"), StoryCard("problem", "The tricky part", "Something goes wrong"))) },
        Entry("wordCards", "New words", "Information") { id, m -> Stop.WordCards(id, "New words", "Tap a word to hear it.", ing(m), tip, listOf(WordCard("sun", "The bright star in the sky.", "The sun is hot.", "sun"), WordCard("tree", "A tall plant with leaves.", "The tree is green.", "tree"))) },
        Entry("move", "Move your body", "Information") { id, m -> Stop.Move(id, "Move your body", "Let's warm up!", ing(m), tip, listOf(MoveAction("🙌", "Reach up high!"), MoveAction("🐸", "Hop like a frog!"), MoveAction("🧘", "Sit down slowly."))) },
        Entry("explain", "Explain a skill", "Information") { id, m -> Stop.Explain(id, "How it works", "Watch how we do it.", ing(m), tip, "skill", "Say the rule here in one short sentence.", listOf(WorkedExample("2 + 2", listOf("Start at 2", "Count two more"), "4"))) },
        Entry("choice", "Multiple choice", "One answer") { id, m -> Stop.Choice(id, "Pick the answer", "Which one is right?", ing(m), tip, "Think about the page.", "Which one is right?", listOf(Tile("a", "First"), Tile("b", "Second"), Tile("c", "Third")), "a") },
        Entry("trueFalse", "True or false", "One answer") { id, m -> Stop.TrueFalse(id, "True or false", "Is this true?", ing(m), tip, "Look at the picture again.", "The sun is cold.", false) },
        Entry("sequence", "Number sequence", "One answer") { id, m -> Stop.Sequence(id, "What comes next?", "Fill the gap.", ing(m), tip, "Jump two each time.", listOf(2, 4, 6, null), listOf(Option("a", "8"), Option("b", "7"), Option("c", "9")), "a", NumberLine(0, 10, 1, listOf(2, 4, 6))) },
        Entry("count", "Count objects", "One answer") { id, m -> Stop.Count(id, "How many?", "Count them all.", ing(m), tip, "Count one group, then the next.", "apple", listOf(2, 2), listOf(Option("a", "4"), Option("b", "3"), Option("c", "5")), "a", NumberLine(0, 10, 1)) },
        Entry("compare", "Compare numbers", "One answer") { id, m -> Stop.Compare(id, "Which is bigger?", "Compare the two numbers.", ing(m), tip, "Find both on the line.", 8, 6, listOf(Option("a", "<"), Option("b", ">"), Option("c", "=")), "b", NumberLine(0, 10, 1, listOf(6, 8))) },
        Entry("sound", "Which sound?", "One answer") { id, m -> Stop.Sound(id, "Which sound?", "Which sound starts the word?", ing(m), tip, "Say it slowly.", "ship", listOf(Option("a", "sh"), Option("b", "ch")), "a") },
        Entry("word", "Hear the word", "One answer") { id, m -> Stop.Word(id, "Find the word", "Listen, then tap the word.", ing(m), tip, "Listen again.", "shop", listOf(Option("a", "shop"), Option("b", "ship"), Option("c", "chip")), "a") },
        Entry("readTap", "Read and tap", "One answer") { id, m -> Stop.ReadTap(id, "Read and tap", "Read the word, tap the picture.", ing(m), tip, "Sound it out.", "fish", listOf(PictureOption("a", "fish"), PictureOption("b", "ship"), PictureOption("c", "sun")), "a") },
        Entry("writeSentence", "Finish the sentence", "One answer") { id, m -> Stop.WriteSentence(id, "Finish the sentence", "Pick the missing word.", ing(m), tip, "The sun is ___.", "hot", listOf("hot", "wet", "blue")) },
        Entry("multiSelect", "Tap N of them", "Several answers") { id, m -> Stop.MultiSelect(id, "Tap two", "Tap two things from the page.", ing(m), tip, "Tap two things from the page.", listOf(Tile("a", "First"), Tile("b", "Second"), Tile("c", "Third"), Tile("d", "Fourth")), listOf("a", "b"), 2) },
        Entry("selectAll", "Select all that apply", "Several answers") { id, m -> Stop.SelectAll(id, "Find them all", "Tap every one that fits.", ing(m), tip, "Tap every one that fits.", listOf(Tile("a", "First"), Tile("b", "Second"), Tile("c", "Third")), listOf("a", "c")) },
        Entry("match", "Match pairs", "Several answers") { id, m -> Stop.Match(id, "Match the pairs", "Match each word to its picture.", ing(m), tip, "Match each word to its picture.", listOf(MatchPair("p1", Tile("l1", "sun"), Tile("r1", illustrationKey = "sun")), MatchPair("p2", Tile("l2", "tree"), Tile("r2", illustrationKey = "tree")), MatchPair("p3", Tile("l3", "fish"), Tile("r3", illustrationKey = "fish")))) },
        Entry("order", "Put in order", "Several answers") { id, m -> Stop.Order(id, "Put the story in order", "Put the story in order.", ing(m), tip, "Put the story in order.", listOf(OrderItem("a", "First this happened."), OrderItem("b", "Then this."), OrderItem("c", "In the end, this.")), listOf("a", "b", "c")) },
        Entry("trace", "Trace a letter", "Several answers") { id, m -> Stop.Trace(id, "Trace the letter", "Trace the letter with your finger.", ing(m), tip, "S", "Start at the top.") },
        Entry("retell", "Retell the story", "Open answer") { id, m -> Stop.Retell(id, "Tell the story", "Tell the story in your own words.", ing(m), tip, "Tell the story in your own words.", listOf(RetellCue("beginning", "First…"), RetellCue("middle", "Then…"), RetellCue("end", "In the end…")), "A good retelling names who, what happened and how it ended.") },
        Entry("openAnswer", "Open question", "Open answer") { id, m -> Stop.OpenAnswer(id, "Your idea", "What do you think?", ing(m), tip, "What would you do?", "speak", "Any answer with a reason is a good one.") },
        Entry("exitTicket", "Exit ticket (3 questions)", "Exit") { id, m -> Stop.ExitTicket(id, "Three last questions", "Three last questions!", ing(m), tip, listOf(
            Stop.Choice("$id-q1", "Question 1", "Who is in the story?", ing(m), tip, "Think about the page.", "Who is in the story?", listOf(Tile("a", "First"), Tile("b", "Second"), Tile("c", "Third")), "a"),
            Stop.MultiSelect("$id-q2", "Question 2", "Tap two.", ing(m), tip, "Tap two things from the page.", listOf(Tile("a", "First"), Tile("b", "Second"), Tile("c", "Third"), Tile("d", "Fourth")), listOf("a", "b"), 2),
            Stop.TrueFalse("$id-q3", "Question 3", "True or false?", ing(m), tip, "Look again.", "The story is about the sea.", false))) },
    )

    fun byType(type: String): Entry? = all.firstOrNull { it.type == type }
}
