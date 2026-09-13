package quest.server.ai;

import java.util.List;
import quest.api.Illustrations;

/**
 * Prompt A (skill extraction) and Prompt B (question generation). Both demand JSON only; the server
 * validates the output against the shared schemas and retries once with the validation errors.
 */
public final class Prompts {
    private Prompts() {}

    public static final String SYSTEM_A = """
        You are a teaching assistant for a Grade 1 class at an international school. A parent has uploaded
        the teacher's class slides. Your job is to list the SKILLS THE TEACHER TAUGHT so a game can practise them.

        Rules
        - Identify only teaching content: a concept, method or skill being taught (for example "Counting by 2s",
          "The sh sound", "Number bonds to 10", "Sight words: the, and, is").
        - Ignore class rules, birthdays, timetables, homework reminders, transitions, greetings, and decoration.
        - Return between 1 and 5 skills. Merge slides that teach the same skill.
        - For each skill describe the METHOD the teacher used, in a few words, exactly as shown on the slides
          (for example "number line jumps", "pairs of objects", "ten frames", "word family list with pictures").
          The questions will be generated in that same method, so be specific.
        - Copy the teacher's EXAMPLES from the slides verbatim (numbers, words, sentences), up to 6 per skill.
        - Give a confidence between 0 and 1. If confidence is below 0.7, add an "unsure" object with exactly
          two candidate skill names and a one-line question a parent can answer.
        - Privacy: never include children's names, faces, photos, or anything that identifies a child. Never
          describe photographs of people. If the only content is about people, omit it.
        - If there is no teaching content at all, return {"subject": "<subject>", "skills": []}.

        Output
        Return ONLY a JSON object, no prose, no markdown fences, matching this schema:
        %s
        """;

    public static String userA(String subject, int grade, String curriculum, int slideCount, String typedTask) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(subject).append("\nGrade: ").append(grade).append("\nCurriculum style: ").append(curriculum).append('\n');
        if (slideCount > 0) sb.append("The ").append(slideCount).append(" attached document(s)/images are the slides, in order. Slide numbers start at 1.\n");
        if (typedTask != null && !typedTask.isBlank()) {
            sb.append("The parent typed this task instead of (or in addition to) slides. Treat it as slide 1:\n\"\"\"\n")
              .append(typedTask.strip()).append("\n\"\"\"\n");
        }
        sb.append("List the skills taught. JSON only.");
        return sb.toString();
    }

    public static final String SYSTEM_B = """
        You write practice questions for a 6-year-old in Grade 1, based on ONE skill the teacher taught today.
        The child plays on a phone: she taps big tiles, hears every instruction read aloud, and never sees a
        red X. Wrong answers get a hint and a second try.

        Rules
        - Mirror the teacher's METHOD and EXAMPLES exactly. If the teacher counted by 2s on a number line, use
          number lines and the same numbers. If the "sh" slide used ship/sheep/shop, use that word family.
        - explanation: ONE sentence, at most 12 words, that a 6-year-old can hear read aloud.
        - workedExamples: 2 or 3, in the teacher's method, each with 1-4 short steps.
        - questions: EXACTLY the number requested. Every question has a plain-language "hint" (one short
          sentence) and, for numeric types, a "numberLine" the hint screen can draw (from, to, step, highlight).
        - Question text and hints are one short sentence. No trick questions.
        - Mix the question types within a set: use at least two different types whenever the skill allows
          (for example counting skills mix sequence and count; sound skills mix sound, word, readTap and trace).
        - Distractors must be plausible for a 6-year-old: adjacent numbers, look-alike or sound-alike words.
        - Use only these question types, matching the game exactly:
            sequence  — number chips with one missing shown as null, options 2-4 numbers
            count     — groups of objects (objectKey from the illustration list) + groupSizes, options 2-4 numbers
            compare   — two numbers, options are exactly "<", ">", "=" (all three, in that order)
            sound     — a picture (illustrationKey) and 2-3 letter-sound options such as "sh", "ch", "th"
            word      — a spoken word and 3 written options; the spoken word must be one of the options
            trace     — a single letter to trace; no options
            readTap   — a written word and 3 pictures (illustrationKey); the correct picture is the word itself
        - Pictures come ONLY from this illustration list; never invent one: %s
        - Numeric answers must be arithmetically correct. For compare, "<" means left is smaller.
        - Mode: normal = as taught; again = new questions at the same level; harder = slightly harder
          (bigger numbers, one more option, contrast sounds); easier = smaller numbers, fewer options.
        - Never reuse a question id from the excluded list. Ids are short strings unique within the set.
        - Privacy: never include children's names.

        Output
        Return ONLY a JSON object, no prose, no markdown fences, matching this schema:
        %s
        """;

    public static String userB(String skillId, String name, String subject, String method, List<String> examples, int grade,
                               String mode, int length, List<String> excludedIds) {
        return "Skill id: " + skillId + "\nSkill: " + name + "\nSubject: " + subject + "\nGrade: " + grade +
               "\nTeacher's method: " + method + "\nTeacher's examples: " + String.join(" | ", examples) +
               "\nMode: " + mode + "\nNumber of questions: " + length +
               "\nAlready shown question ids (do not reuse): " + (excludedIds.isEmpty() ? "none" : String.join(", ", excludedIds)) +
               "\nUse skillId \"" + skillId + "\" and mode \"" + mode + "\" in the output. JSON only.";
    }

    public static String systemA(String skillSchema) { return SYSTEM_A.formatted(skillSchema); }

    public static String systemB(String questionSetSchema) {
        return SYSTEM_B.formatted(String.join(", ", Illustrations.INSTANCE.getKeys()), questionSetSchema);
    }

    /** Retry message appended after an invalid answer. */
    public static String retry(List<String> errors) {
        return "Your previous answer did not validate. Fix these problems and return the full corrected JSON only:\n- " + String.join("\n- ", errors);
    }
}
