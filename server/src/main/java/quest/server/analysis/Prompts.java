package quest.server.analysis;

import java.util.List;
import java.util.stream.Collectors;
import quest.api.Illustrations;

/**
 * Prompt A (slides → SourceAnalysis), Prompt B (analysis + confirmed skills → one Play per level), and
 * Prompt C (plays → ParentPanel). Versions live in {@code quest.api.CacheKeys}; bump them when the text changes.
 * The model only ever writes JSON; the server validates against the shared schemas and retries once with the errors.
 */
public final class Prompts {
    private Prompts() {}

    static final String ILLUSTRATIONS = String.join(", ", Illustrations.INSTANCE.getKeys());

    // ------------------------------------------------------------------ Prompt A
    public static final String SYSTEM_A = """
        You are a primary-school teacher's assistant reading a lesson's slides (grades 1–3, ages 5–8).
        You read carefully and write down only what the slides actually teach. You never invent content that is
        not on the slides, and you never add clip-art descriptions of things that are not there.
        You answer with one JSON object only — no prose, no markdown fences.
        """;

    public static String userA(String curriculum, int grade, String subject, String notes, String pageText, boolean imagesAttached) {
        return """
        Course: %s curriculum, grade %d, subject: %s.
        Admin notes (may be empty): %s

        %s

        Produce a JSON object with exactly these fields (limits in brackets are hard limits):
        {
          "kind": "story" | "informational" | "math" | "phonics" | "vocabulary" | "mixed",
          "title": "short lesson title as a child would say it [1–60 chars]",
          "pages": [ { "number": 1,
                       "childText": ["the exact words a child reads, one sentence per entry [1–7 entries, ≤ 90 chars each; a page without words gets one entry: \"(no words on this page)\"]"],
                       "pictureDescription": "what the picture shows, one sentence [1–120 chars; write \"No picture.\" if there is none]",
                       "illustrationKeys": ["keys from the fixed list that match objects in the picture (may be empty)"],
                       "keepImage": false } ],
          "skills": [ { "id": "sk1", "name": "skill as a teacher names it [3–40 chars]",
                        "subject": "math" | "english", "method": "the exact method the slides use, one sentence [3–120 chars]",
                        "examples": ["worked examples copied from the slides [1–6 entries, ≤ 120 chars]"], "slideNumbers": [1, 2],
                        "confidence": 0.0–1.0
                        /* add "unsure": { "candidates": ["exactly two alternative readings [3–40 chars each]"], "question": "one question for the admin [≤ 120 chars]" } ONLY when confidence < 0.7 — otherwise omit the key entirely; never write null */ } ],
          "vocabulary": [ { "word": "[≤ 16 chars]", "meaning": "child-friendly [≤ 80 chars]", "sentence": "from the slides or a simple one [≤ 120 chars]", "illustrationKey": "from the list" } ],   // 0–12 entries
          "storyPieces": { "title": "[≤ 60]", "genre": "real" | "fantasy", "characters": ["1–6, ≤ 30 chars each"], "setting": "[≤ 90]", "problem": "[≤ 120]", "resolution": "[≤ 120]" },   // stories only; omit the key otherwise
          "events": [ { "id": "e1", "text": "one story event in order [≤ 80 chars]", "pageNumber": 1 } ],   // 0–6 entries
          "facts": [ { "id": "f1", "trueStatement": "a true statement from the slides [≤ 90]", "falseTwin": "the same statement with one thing changed so it is false [≤ 90]", "pageNumber": 1 } ],   // 0–10 entries
          "objectives": { "en": ["what the child will be able to do [3–5 entries, ≤ 160 chars]"], "ar": ["the same objectives in Modern Standard Arabic, same count and order"] }
        }

        Rules:
        - Every slide becomes one entry in "pages", in order; keep the child's reading text verbatim (fix only obvious OCR breaks).
        - Set keepImage=true only when the picture itself is needed to answer (a diagram, a labelled picture, a number line drawn on the slide).
        - "illustrationKeys" and "illustrationKey" may only use: %s
        - Story slides: fill storyPieces, events (4–6, in order) and vocabulary (3–6 words). Math slides: 1–3 skills carrying the method and 3–6 examples each. Phonics slides: the sound and 4–8 example words in vocabulary.
        - Never write null for any field: omit optional keys instead. Skills: 1–5. Mark confidence < 0.7 as unsure with a question the admin can answer in one tap.
        - If the slides contain no teaching content at all (blank, logos, "thank you" only), answer {"error":"no_teaching_content"}.
        """.formatted(curriculum, grade, subject, notes == null || notes.isBlank() ? "(none)" : notes,
                imagesAttached ? "The slides are attached as page images in order. The extracted text (may be partial) follows:\n" + pageText : "The slides' text, page by page:\n" + pageText,
                ILLUSTRATIONS);
    }

    // ------------------------------------------------------------------ Prompt B
    public static final String SYSTEM_B = """
        You design Homework Quest lessons: short, joyful practice journeys for children aged 5–8 who play alone on a phone
        and cannot read instructions well. Everything a child sees is spoken aloud, so every string must be short, warm and
        readable by a first grader. You only teach what the source slides teach, using the same method the teacher used.
        Wrong answers are never punished: hints point to the method, not the answer.
        You answer with one JSON object only — no prose, no markdown fences.
        """;

    public static String userB(int level, int variant, String analysisJson, String confirmedSkillsJson, String notes, int practiceLength, List<String> excludedIds) {
        String levelBrief = switch (level) {
            case 1 -> "LEVEL 1 — \"Same as the book\": the exact examples, numbers, words and sentences from the slides. Read pages first (story) or explain first (math/phonics), then practise them.";
            case 2 -> "LEVEL 2 — \"Think\": the same skills one step deeper. Stories: the SAME story and characters, but questions the slides did not ask (why, how, feelings, what happened before/after, order of events) and new sentences using the story's words. Math: the same method with new numbers inside the slides' range. No page reading; one explain/wordCards stop at most.";
            default -> "LEVEL 3 — \"Challenge\": the same skills stretched one step (two-step problems, retelling the whole story in the child's own words, writing a sentence about it). Stories stay the SAME story. Include at least one open stop (retell / openAnswer / writeSentence free=true).";
        };
        String variantBrief = variant == 1 ? "\nThis is the AGAIN variant of Level 1 for a child who needs another look: same skills and same difficulty, but every question is different from the first pass. Do not reuse these stop ids: " + String.join(", ", excludedIds) : "";
        return """
        %s%s

        SOURCE ANALYSIS (Prompt A output):
        %s

        CONFIRMED SKILLS (teach exactly these, with these methods):
        %s

        Admin notes: %s

        Produce one Play as JSON (limits in brackets are hard limits):
        { "level": %d, "variant": %d, "kind": <same kind as the analysis>,
          "theme": { "potName": "e.g. Soup Pot [≤ 20]", "dishName": "e.g. Hot Soup [≤ 30]", "potEmoji": "one emoji", "servedText": "what the child hears when the dish is served [≤ 60]" },
          "stops": [ ...%d stops (6–9), the last one an exitTicket... ] }

        Every stop has: "type", "id" (short, unique in this play, e.g. "s1"), "title" [≤ 40], "speak" (what Pip says aloud) [≤ 90],
        "ingredient": {"emoji": "one food emoji", "name": "[≤ 20]"}, "parentTip": {"en": "one sentence for the parent [≤ 200]", "ar": "the same in Arabic [≤ 200]"}.
        Stop types and their extra fields:
        INFO (no answer):
          readPage      { pageNumber, sentences:[1–7 sentences ≤ 90 chars, the page's exact words], pictureDescription [≤ 120], illustrationKey?, pageImageId? ("page-N", keepImage pages only), tapTask?: {prompt [≤ 90], hotspots:[2–8 of {id,label [≤ 24],x,y,w,h in 0..1}], correctIds:[…]} }
          storyPieces   { cards:[exactly 6, one per piece in this order: title, genre, characters, setting, plot, problem (no resolution card) — each {piece, definition [≤ 90], answer [≤ 120]}] }
          wordCards     { words:[2–6 of {word [≤ 16], meaning [≤ 80], sentence [≤ 120], illustrationKey}] }
          move          { actions:[3–4 of {emoji, text [≤ 60]}] } (tied to the story or the skill)
          explain       { skillId, explanation [≤ 80 chars, the teacher's method], workedExamples:[2–3 of {prompt [≤ 60], steps:[1–4], answer [≤ 60]}] }
        SINGLE ANSWER (options 2–4, ids "a".."d", exactly one correct; "hint" [≤ 90] points to the method, never to the answer):
          choice        { hint, question [≤ 90], options:[{id,label [≤ 40]} or {id,illustrationKey}], correctOptionId }
          trueFalse     { hint, statement [≤ 90], answer: true|false }
          sequence      { hint, chips:[3–6 numbers with exactly one null gap], options:[{id,label [≤ 12]}], correctOptionId, numberLine:{from,to,step ≥ 1,highlight:[…]} }
          count         { hint, objectKey (illustration), groupSizes:[1–6 groups of 1–5], options:[{id,label}], correctOptionId, numberLine:{…} } — the correct label must equal the sum of groupSizes
          compare       { hint, left, right, options:[exactly 3: {"id":"a","label":"<"},{"id":"b","label":">"},{"id":"c","label":"="}], correctOptionId, numberLine:{…} }
          sound         { hint, illustrationKey, options:[{id,label: the sound e.g. "sh"}], correctOptionId }
          word          { hint, spokenWord [≤ 12], options:[{id,label}], correctOptionId }
          readTap       { hint, word [≤ 12], options:[{id, illustrationKey}], correctOptionId }
          writeSentence { frame ("Alan ___ soup." [≤ 90]), answer, options:[3 words ≤ 16 chars], free:false }
        MULTI ANSWER:
          multiSelect   { prompt, options:[3–8 tiles {id,label|illustrationKey}], correctIds:[2–3], pick: 2 or 3 (= number of correctIds) }
          selectAll     { prompt, options:[tiles], correctIds:[…] }
          match         { prompt, pairs:[2–5 of {id, left:{id,label|illustrationKey}, right:{id,label|illustrationKey}}] }
          order         { prompt, items:[3–6 of {id,text,illustrationKey?}], correctOrder:[the ids in the right order] }
          trace         { text: a letter, digraph or short word, hint }
        OPEN (no right answer, the parent sees the model answer):
          retell        { prompt, cues:[exactly 3: {stage:"beginning"|"middle"|"end", cue: a short question or hint [≤ 90], illustrationKey?}], modelAnswer [≤ 300], record:true }
          openAnswer    { prompt, mode: "speak" | "draw" | "both", modelAnswer }
          writeSentence { frame, answer, free:true }
        EXIT (always last): exitTicket { questions:[exactly 3 COMPLETE answerable stops — each question carries every common field (type, id, title, speak, ingredient, parentTip) plus its own fields, exactly like a top-level stop; allowed types: (choice / trueFalse / multiSelect / selectAll / order / match / sequence / count / compare / sound / word / readTap — never info or open stops), and exactly one of them a multiSelect or selectAll; each with its own unique id] }
        Semantic rules the validator enforces: choice/sound/word/readTap correctOptionId must be an option id; word: the spokenWord must be one of the option labels and the correct one;
        readTap: the correct option's illustrationKey equals the word; count: the correct label equals the sum of groupSizes; sequence: exactly one null chip and the correct label is the missing number;
        compare: correct is "<" when left < right, ">" when left > right, "=" when equal; multiSelect: pick == number of correctIds and at least one wrong option; order: correctOrder is a permutation of the item ids;
        storyPieces: the six distinct pieces; retell: beginning, middle and end cues; writeSentence: frame contains "___" and answer is one of the options.

        Rules:
        - Level 1 of a story: move → storyPieces → one readPage per page (2–5 pages) → 1–2 questions → retell → exitTicket. Math: explain → 4–6 practice stops with the slides' numbers → exitTicket. Phonics: sound → trace → readTap/word → exitTicket.
        - Use only these illustration keys, spelled exactly (never invent one — if nothing fits, leave illustrationKey out and use a label): %s. Tiles and options need a label or a known illustrationKey.
        - Every child-facing string is short and uses the words the slides use. No percentages, no "wrong", no "fail".
        - Numbers stay within the range the slides use (Level 3 may go one step further). Options must be plausible; exactly one correct for single-answer stops.
        - Stop ids must be unique across the play, including exitTicket questions (e.g. "s9q1"). Never write null for any field: omit optional keys instead.
        - Arabic parentTip is Modern Standard Arabic, written for a parent in the UAE.
        """.formatted(levelBrief, variantBrief, analysisJson, confirmedSkillsJson, notes == null || notes.isBlank() ? "(none)" : notes,
                level, variant, practiceLength, ILLUSTRATIONS);
    }

    /** Regenerate one stop in place: same type and position, fresh content. */
    public static String userStop(String playJson, String stopJson, List<String> excludedIds) {
        return """
        Here is a Play you wrote earlier:
        %s

        Rewrite ONLY this stop, keeping the same "type", the same position in the journey and the same skill, but with different content:
        %s

        Do not reuse any of these ids: %s
        Answer with the single replacement stop as a JSON object (not the whole play). Same field rules as before; illustration keys: %s
        """.formatted(playJson, stopJson, String.join(", ", excludedIds), ILLUSTRATIONS);
    }

    // ------------------------------------------------------------------ Prompt C
    public static final String SYSTEM_C = """
        You write the parent panel for a Homework Quest lesson: plain, kind, bilingual (English + Modern Standard Arabic) guidance
        for a parent in the UAE who may not know the teaching method. Never percentages, never jargon, never blame.
        You answer with one JSON object only — no prose, no markdown fences.
        """;

    public static String userC(String analysisJson, String playsJson) {
        return """
        SOURCE ANALYSIS:
        %s

        THE THREE LEVELS (plays) the child will play:
        %s

        Produce (limits in brackets are hard limits):
        { "objectives": { "en": ["what the child practises [3–5 entries, ≤ 160 chars]"], "ar": ["the same in Arabic, same count and order"] },
          "supported": [ { "en": "one thing a parent can do alongside Level 1 or 2 [≤ 200 chars]", "ar": "the same in Arabic" } ],   // 2–4 entries
          "challenge": [ { "en": "one stretch idea for Level 3 or after [≤ 200 chars]", "ar": "the same in Arabic" } ],               // 2–4 entries
          "stopTips": [ { "stopId": "L<level>:<stop id copied exactly from that play, e.g. L1:s6>", "en": "how to help at this stop if it is hard [≤ 200]", "ar": "the same in Arabic" } ],   // 4–8, the trickiest stops
          "modelAnswers": [ { "stopId": "L<level>:<id of each retell / openAnswer / free writeSentence stop>", "en": "what a good answer sounds like [≤ 300]" } ] }
        Stop ids are written as "L" + level + ":" + the stop's "id" field exactly as it appears in the play (ids repeat across levels, so the level prefix matters).
        Never write null for any field.
        """.formatted(analysisJson, playsJson);
    }

    static String join(List<String> xs) { return xs.stream().collect(Collectors.joining(", ")); }
}
