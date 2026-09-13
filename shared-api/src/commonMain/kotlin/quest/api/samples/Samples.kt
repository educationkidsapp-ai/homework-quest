package quest.api.samples

/**
 * Canonical sample model outputs. Used by schema tests, server tests (mocked Anthropic responses)
 * and the in-app `FakeLessonApi`. Content mirrors docs/design.md §8.
 */
object Samples {
    val skillExtractionMath = """
{
  "subject": "math",
  "skills": [
    {
      "id": "counting-by-2s",
      "name": "Counting by 2s",
      "subject": "math",
      "method": "number line jumps and pairs of objects",
      "examples": ["2, 4, 6, 8, 10", "12, 14, 16", "pairs of shoes"],
      "slideNumbers": [2, 3, 4],
      "confidence": 0.95
    },
    {
      "id": "number-bonds-to-10",
      "name": "Number bonds to 10",
      "subject": "math",
      "method": "two-part whole with ten frames",
      "examples": ["7 + 3 = 10", "6 + 4 = 10"],
      "slideNumbers": [6],
      "confidence": 0.55,
      "unsure": {
        "candidates": ["Number bonds to 10", "Adding two numbers"],
        "question": "Slide 6 shows 7 + 3 in a ten frame. Was this about making 10, or adding in general?"
      }
    }
  ]
}
""".trimIndent()

    val skillExtractionEnglish = """
{
  "subject": "english",
  "skills": [
    {
      "id": "sh-sound",
      "name": "The sh sound",
      "subject": "english",
      "method": "word family list with pictures",
      "examples": ["ship", "sheep", "shop", "shell"],
      "slideNumbers": [1, 2],
      "confidence": 0.92
    },
    {
      "id": "sight-words-week-3",
      "name": "Sight words: the, and, is",
      "subject": "english",
      "method": "look, say, cover, write, check",
      "examples": ["the", "and", "is"],
      "slideNumbers": [4],
      "confidence": 0.88
    }
  ]
}
""".trimIndent()

    val questionSetCountingBy2s = """
{
  "skillId": "counting-by-2s",
  "mode": "normal",
  "explanation": "Counting by 2s means we jump two each time!",
  "workedExamples": [
    { "prompt": "2, 4, 6, ?", "steps": ["Start at 6", "Jump 2 on the number line", "Land on 8"], "answer": "8" },
    { "prompt": "2 pairs of shoes", "steps": ["One pair is 2", "Two pairs: 2, 4"], "answer": "4 shoes" }
  ],
  "questions": [
    { "id": "c2-q1", "type": "sequence", "hint": "Start at 6 and jump 2.", "chips": [2, 4, 6, null],
      "options": [{"id":"a","label":"7"},{"id":"b","label":"8"},{"id":"c","label":"10"}], "correctOptionId": "b",
      "numberLine": {"from": 0, "to": 12, "step": 1, "highlight": [2, 4, 6, 8]} },
    { "id": "c2-q2", "type": "count", "hint": "Count the shoes two at a time: 2, 4, 6.", "objectKey": "shoe", "groupSizes": [2, 2, 2],
      "options": [{"id":"a","label":"3"},{"id":"b","label":"6"},{"id":"c","label":"5"}], "correctOptionId": "b",
      "numberLine": {"from": 0, "to": 10, "step": 1, "highlight": [2, 4, 6]} },
    { "id": "c2-q3", "type": "sequence", "hint": "What comes after 12 when we jump 2?", "chips": [10, 12, null, 16],
      "options": [{"id":"a","label":"13"},{"id":"b","label":"14"},{"id":"c","label":"15"}], "correctOptionId": "b",
      "numberLine": {"from": 8, "to": 18, "step": 1, "highlight": [10, 12, 14, 16]} },
    { "id": "c2-q4", "type": "compare", "hint": "Which number is further along the number line?", "left": 8, "right": 6,
      "options": [{"id":"a","label":"<"},{"id":"b","label":">"},{"id":"c","label":"="}], "correctOptionId": "b",
      "numberLine": {"from": 0, "to": 10, "step": 1, "highlight": [6, 8]} },
    { "id": "c2-q5", "type": "count", "hint": "Each pair is 2 socks. Jump 2 for every pair.", "objectKey": "sock", "groupSizes": [2, 2, 2, 2],
      "options": [{"id":"a","label":"4"},{"id":"b","label":"8"},{"id":"c","label":"6"}], "correctOptionId": "b",
      "numberLine": {"from": 0, "to": 10, "step": 1, "highlight": [2, 4, 6, 8]} },
    { "id": "c2-q6", "type": "sequence", "hint": "Start at 8 and jump 2.", "chips": [4, 6, 8, null],
      "options": [{"id":"a","label":"9"},{"id":"b","label":"10"},{"id":"c","label":"12"}], "correctOptionId": "b",
      "numberLine": {"from": 0, "to": 12, "step": 1, "highlight": [4, 6, 8, 10]} },
    { "id": "c2-q7", "type": "compare", "hint": "Find 12 and 14 on the number line. Which is bigger?", "left": 12, "right": 14,
      "options": [{"id":"a","label":"<"},{"id":"b","label":">"},{"id":"c","label":"="}], "correctOptionId": "a",
      "numberLine": {"from": 10, "to": 16, "step": 1, "highlight": [12, 14]} }
  ]
}
""".trimIndent()

    val questionSetShSound = """
{
  "skillId": "sh-sound",
  "mode": "normal",
  "explanation": "Sh says shhh, like a quiet ship!",
  "workedExamples": [
    { "prompt": "ship", "steps": ["Say sh", "Say ip", "Blend: ship"], "answer": "sh-ip" },
    { "prompt": "shop", "steps": ["Say sh", "Say op", "Blend: shop"], "answer": "sh-op" }
  ],
  "questions": [
    { "id": "sh-q1", "type": "sound", "hint": "Say the word slowly. Does it start with shhh?", "illustrationKey": "ship",
      "options": [{"id":"a","label":"sh"},{"id":"b","label":"ch"}], "correctOptionId": "a" },
    { "id": "sh-q2", "type": "word", "hint": "Listen for the shhh at the start.", "spokenWord": "shop",
      "options": [{"id":"a","label":"shop"},{"id":"b","label":"chop"},{"id":"c","label":"stop"}], "correctOptionId": "a" },
    { "id": "sh-q3", "type": "readTap", "hint": "Sh-eep. Which animal says baa?", "word": "sheep",
      "options": [{"id":"a","illustrationKey":"sheep"},{"id":"b","illustrationKey":"ship"},{"id":"c","illustrationKey":"fish"}], "correctOptionId": "a" },
    { "id": "sh-q4", "type": "sound", "hint": "Ch-air. Ch is like a train: ch ch ch.", "illustrationKey": "chair",
      "options": [{"id":"a","label":"sh"},{"id":"b","label":"ch"}], "correctOptionId": "b" },
    { "id": "sh-q5", "type": "trace", "hint": "Start at the top and curve like a snake.", "letter": "S" },
    { "id": "sh-q6", "type": "sound", "hint": "Sh-ell. Quiet sound at the start.", "illustrationKey": "shell",
      "options": [{"id":"a","label":"th"},{"id":"b","label":"sh"},{"id":"c","label":"ch"}], "correctOptionId": "b" },
    { "id": "sh-q7", "type": "readTap", "hint": "F-i-sh. It swims!", "word": "fish",
      "options": [{"id":"a","illustrationKey":"ship"},{"id":"b","illustrationKey":"fish"},{"id":"c","illustrationKey":"sheep"}], "correctOptionId": "b" }
  ]
}
""".trimIndent()

    val questionSetSightWords = """
{
  "skillId": "sight-words-week-3",
  "mode": "normal",
  "explanation": "Sight words are words we know just by looking!",
  "workedExamples": [
    { "prompt": "the", "steps": ["Look at the word", "Say it: the", "Cover it and say it again"], "answer": "the" },
    { "prompt": "and", "steps": ["Look at the word", "Say it: and", "Find it in a sentence"], "answer": "and" }
  ],
  "questions": [
    { "id": "sw-q1", "type": "word", "hint": "It starts with th.", "spokenWord": "the",
      "options": [{"id":"a","label":"the"},{"id":"b","label":"and"},{"id":"c","label":"is"}], "correctOptionId": "a" },
    { "id": "sw-q2", "type": "word", "hint": "It starts with a.", "spokenWord": "and",
      "options": [{"id":"a","label":"is"},{"id":"b","label":"and"},{"id":"c","label":"the"}], "correctOptionId": "b" },
    { "id": "sw-q3", "type": "trace", "hint": "A straight line down, then a dot on top.", "letter": "i" },
    { "id": "sw-q4", "type": "word", "hint": "It is a tiny word with an s.", "spokenWord": "is",
      "options": [{"id":"a","label":"in"},{"id":"b","label":"it"},{"id":"c","label":"is"}], "correctOptionId": "c" },
    { "id": "sw-q5", "type": "readTap", "hint": "S-u-n. It shines in the sky.", "word": "sun",
      "options": [{"id":"a","illustrationKey":"sun"},{"id":"b","illustrationKey":"moon"},{"id":"c","illustrationKey":"star"}], "correctOptionId": "a" },
    { "id": "sw-q6", "type": "trace", "hint": "Round like a ball, then a tail.", "letter": "a" },
    { "id": "sw-q7", "type": "word", "hint": "Listen for the t-h at the start.", "spokenWord": "the",
      "options": [{"id":"a","label":"she"},{"id":"b","label":"the"},{"id":"c","label":"he"}], "correctOptionId": "b" }
  ]
}
""".trimIndent()

    val allQuestionSets = listOf(questionSetCountingBy2s, questionSetShSound, questionSetSightWords)
}
