package quest.server.children;

import java.util.List;
import quest.api.progress.Band;

/**
 * Feature 28: <strong>everything the evening card is allowed to be made of.</strong> It is stored as the card's
 * {@code facts_json} and it is the only thing the model is ever shown, so what is not in this record cannot reach the
 * provider and cannot reach the card.
 *
 * <p>Two kinds of fact and no third: the <strong>teacher's own lesson text</strong> (the title, the parent panel's
 * objectives, the `parentTip` of the one stop worth practising) and the <strong>child's own results in words</strong>
 * (whether she finished, and the band). <strong>No number</strong> — no stars, no mistakes, no score, no percentage —
 * and <strong>nothing that identifies the child</strong>: no name, no age, no class, not even the date, which is why
 * {@link #day} is kept beside the lessons rather than inside the block the model reads.
 *
 * <p>The band is stored as the band and turned into words here ({@link #word}), because the words are what the model
 * and the template are both given and one wording for both is the point: §5's "words, never a percentage".
 */
public record EveningCardFacts(String day, List<Lesson> lessons) {
    public EveningCardFacts { lessons = List.copyOf(lessons); }

    /**
     * One homework of today. {@code band} is null when she has answered nothing yet, which is itself a fact the note
     * may use, and {@code practiseEn} / {@code practiseAr} are the teacher's own tip for the stop with the most
     * mistakes (the fewest stars where they tie) — null when she played nothing or the panel has no tip for it.
     */
    public record Lesson(String lessonId, String title, List<String> objectivesEn, List<String> objectivesAr,
                         boolean finished, Band band, String practiseEn, String practiseAr) {
        public Lesson {
            objectivesEn = List.copyOf(objectivesEn); objectivesAr = List.copyOf(objectivesAr);
        }

        /** The block the model reads. Labelled lines, in a fixed order, so the fake provider can answer it too. */
        public String block() {
            var out = new StringBuilder("LESSON: ").append(title);
            if (!objectivesEn.isEmpty()) out.append("\nWHAT IT TEACHES: ").append(String.join("; ", objectivesEn));
            if (!objectivesAr.isEmpty()) out.append("\nWHAT IT TEACHES, IN ARABIC: ").append(String.join("; ", objectivesAr));
            out.append("\nFINISHED: ").append(finished ? "yes" : "not yet");
            out.append("\nHOW IT WENT: ").append(band == null ? "nothing answered yet / لم يُجب عنه بعد"
                    : word(band, false) + " / " + word(band, true));
            if (practiseEn != null) out.append("\nWORTH PRACTISING: ").append(practiseEn);
            if (practiseAr != null) out.append("\nWORTH PRACTISING, IN ARABIC: ").append(practiseAr);
            return out.toString();
        }
    }

    /** The lessons the card is about, for {@code EveningCard.lessonIds}. */
    public List<String> lessonIds() { return lessons.stream().map(Lesson::lessonId).toList(); }

    public List<String> blocks() { return lessons.stream().map(Lesson::block).toList(); }

    /**
     * Everything the <strong>teacher</strong> wrote in these facts — the titles, the objectives and the tips. It is
     * the one place a number in a card may be quoted from: `Counting by 2s` is her lesson's name, and the validator
     * allows exactly the digit runs that appear here and no others ({@code EveningCardService.problems}).
     */
    public String teacherText() {
        var out = new StringBuilder();
        for (Lesson l : lessons) {
            out.append(l.title()).append('\n');
            out.append(String.join("\n", l.objectivesEn())).append('\n').append(String.join("\n", l.objectivesAr())).append('\n');
            if (l.practiseEn() != null) out.append(l.practiseEn()).append('\n');
            if (l.practiseAr() != null) out.append(l.practiseAr()).append('\n');
        }
        return out.toString();
    }

    /** §5's bands in words, never as a number — {@link quest.api.progress.ProgressBands} decides the band itself. */
    public static String word(Band band, boolean arabic) {
        return switch (band) {
            case GOING_WELL -> arabic ? "سار على ما يرام" : "went well";
            case GETTING_THERE -> arabic ? "في الطريق الصحيح" : "getting there";
            case NEEDS_ANOTHER_LOOK -> arabic ? "يستحق مراجعة أخرى" : "worth another look";
        };
    }

    /** The same band as a whole sentence, which is what the template needs and the model writes for itself. */
    public static String sentence(Band band, boolean arabic) {
        return switch (band) {
            case GOING_WELL -> arabic ? " وقد سار على ما يرام." : " It went well.";
            case GETTING_THERE -> arabic ? " والطريق صحيح حتى الآن." : " It is getting there.";
            case NEEDS_ANOTHER_LOOK -> arabic ? " وهناك جزء يستحق مراجعة أخرى." : " One part is worth another look.";
        };
    }
}
