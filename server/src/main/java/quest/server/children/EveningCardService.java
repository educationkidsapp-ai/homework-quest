package quest.server.children;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.api.dto.EveningCard;
import quest.api.dto.EveningCardSource;
import quest.api.dto.ParentPanel;
import quest.api.progress.Band;
import quest.server.ai.AiFailure;
import quest.server.ai.AiInputGuard;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.analysis.Prompts;
import quest.server.children.Entities.AttemptEntity;
import quest.server.children.Entities.ChildEntity;
import quest.server.children.Entities.EveningCardEntity;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.ParentPanelRepository;
import quest.server.exams.ExamPlays;
import quest.server.platform.SchoolCalendar;

/**
 * Feature 28: <strong>one card per child and day, from 16:00 school time.</strong>
 *
 * <p>The owner's two rules are the design. <strong>Only the teacher's lesson text and the child's own results</strong>
 * — today's homework, its title, the parent panel's objectives and the `parentTip` of the one stop worth practising,
 * plus whether she finished and one of three band words ({@link EveningCardFacts}). And <strong>no score and no
 * percentage</strong>, which is a validator here rather than a line in a prompt: an answer carrying a digit (Western
 * or Arabic-Indic), a `%` or a score word is rejected, and after the attempts run out the fixed bilingual
 * {@link #template} is stored instead, with {@code source = template} so the card says where its words came from.
 *
 * <p><strong>An exam is never in it.</strong> The card is homework only ({@link ExamPlays#isExam}), which is stricter
 * than {@link ExamPlays#sealed}: not even a released exam's result is phrased here, so nothing derived from an exam's
 * answers can reach a parent through this route however the release flag moves.
 *
 * <p><strong>The child is `{child}`.</strong> Her name never enters the model's input — the blocks above carry lesson
 * text and result words and nothing else — and the app fills the placeholder in. Her name is used in the evening
 * <em>push</em>, which is her own parent's phone and not the provider's.
 *
 * <p>E1's three phases, as {@link quest.server.broadcasts.VoiceDraftService} has them: read what the prompt needs,
 * let the transaction go, call the model, then take one short transaction to store the answer. The store is a claim —
 * V50's unique index, through {@link EveningCardRepository#insertOnce} — so two instances generating the same card at
 * the same moment write one row, and the loser reads the winner's words.
 */
@Service
public class EveningCardService {
    /** §28: nothing before the school afternoon, and the evening push from two hours after that. */
    public static final int FROM_HOUR = 16, PUSH_HOUR = 18;
    static final String MODEL = "model", TEMPLATE = "template";
    /** Three sentences of a parent's language. Longer than this is a model that started explaining itself. */
    static final int MAX_TEXT = 400;
    /** The words a card may never carry, whatever language it is in. Digits and `%` are refused on sight. */
    static final List<String> SCORE_WORDS = List.of("score", "scores", "percent", "درجة", "نسبة");
    /** At most this many objectives per lesson reach the prompt: a panel can hold a dozen, a note uses two. */
    private static final int MAX_OBJECTIVES = 3;

    private static final Logger log = LoggerFactory.getLogger(EveningCardService.class);

    private final SchoolLessons schoolLessons; private final AttemptRepository attempts;
    private final LessonCompletionRepository completions; private final ParentPanelRepository panels;
    private final ProgressService progress; private final EveningCardRepository cards;
    private final SchoolCalendar calendar; private final AiService ai; private final Json json;
    private final Clock clock; private final TransactionTemplate tx;

    public EveningCardService(SchoolLessons schoolLessons, AttemptRepository attempts, LessonCompletionRepository completions,
                              ParentPanelRepository panels, ProgressService progress, EveningCardRepository cards,
                              SchoolCalendar calendar, AiService ai, Json json, Clock clock,
                              PlatformTransactionManager transactions) {
        this.schoolLessons = schoolLessons; this.attempts = attempts; this.completions = completions;
        this.panels = panels; this.progress = progress; this.cards = cards; this.calendar = calendar;
        this.ai = ai; this.json = json; this.clock = clock; this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Today's card for one child, written on the first call that finds none — or null, which the endpoint answers as
     * 204: before {@link #FROM_HOUR} in the school's own zone, on a day the school does not teach, or when today
     * holds no homework of hers at all.
     */
    public EveningCardEntity today(ChildEntity child) { return today(child, clock.instant()); }

    /** The same, at an instant the caller already has — the evening job's own pass over a school. */
    public EveningCardEntity today(ChildEntity child, Instant now) {
        var week = calendar.of(child.getSchoolId());
        LocalDate day = LocalDate.ofInstant(now, week.zone());
        if (!week.isSchoolDay(day)) return null;
        if (now.isBefore(day.atTime(FROM_HOUR, 0).atZone(week.zone()).toInstant())) return null;
        var stored = cards.findOneOfChild(child.getSchoolId(), child.getId(), day);
        if (stored.isPresent()) return stored.get();

        var facts = facts(child, day, week.zone());
        if (facts.lessons().isEmpty()) return null;
        var written = write(facts, child.getSchoolId());                // the model call, outside every transaction

        var row = new EveningCardEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(child.getSchoolId()); row.setChildId(child.getId());
        row.setCardDay(day); row.setFactsJson(json.write(facts));
        row.setTextAr(written.textAr()); row.setTextEn(written.textEn()); row.setSource(written.source());
        row.setCreatedAt(now);
        if (tx.execute(status -> cards.insertOnce(row)) == 1) return row;
        return cards.findOneOfChild(child.getSchoolId(), child.getId(), day).orElse(row);
    }

    /** The stored row as the app reads it. The words still carry `{child}`; the app puts her name in. */
    public static EveningCard view(EveningCardEntity row, List<String> lessonIds) {
        return new EveningCard(row.getCardDay().toString(), row.getTextAr(), row.getTextEn(), lessonIds,
                TEMPLATE.equals(row.getSource()) ? EveningCardSource.TEMPLATE : EveningCardSource.MODEL);
    }

    /** The lessons a stored card is about, read back out of its own facts. */
    public List<String> lessonIdsOf(EveningCardEntity row) {
        try { return json.read(row.getFactsJson(), EveningCardFacts.class).lessonIds(); }
        catch (RuntimeException e) { return List.of(); }
    }

    // ---------------------------------------------------------------- the facts

    /**
     * Today's homework of this child: the lessons due today and the ones she played today, each with the teacher's
     * own text and her own result in words. <strong>Exams are left out whole</strong> — the card is homework only.
     *
     * <p>Six queries at most, whatever the number of lessons: her class's published lessons, her attempts, her lesson
     * completions, the bands ({@link ProgressService#skillBands} over today's lessons only), and one parent panel per
     * lesson of today — of which there are one or two.
     */
    EveningCardFacts facts(ChildEntity child, LocalDate day, ZoneId zone) {
        var published = schoolLessons.publishedFor(child);
        var mine = attempts.findByChildIdOrderByAnsweredAtDesc(child.getId());
        Instant from = day.atStartOfDay(zone).toInstant(), to = day.plusDays(1).atStartOfDay(zone).toInstant();
        Set<String> playedToday = new HashSet<>();
        for (var a : mine) if (!a.getAnsweredAt().isBefore(from) && a.getAnsweredAt().isBefore(to)) playedToday.add(a.getLessonId());

        List<LessonEntity> todays = new ArrayList<>();
        for (var lesson : published) {
            if (ExamPlays.isExam(lesson)) continue;                     // homework only, so `ExamPlays.sealed` can never reach a card
            if (day.equals(lesson.getDate()) || playedToday.contains(lesson.getId())) todays.add(lesson);
        }
        if (todays.isEmpty()) return new EveningCardFacts(day.toString(), List.of());

        Map<String, Band> bands = new HashMap<>();
        for (var b : progress.skillBands(child, todays)) if (b.band() != null) bands.putIfAbsent(b.lessonId(), b.band());
        Set<String> finished = new HashSet<>();
        for (var c : completions.findByChildId(child.getId())) finished.add(c.getLessonId());

        var lessons = new ArrayList<EveningCardFacts.Lesson>(todays.size());
        for (var lesson : todays) {
            var panel = panelOf(lesson.getId());
            var practise = practise(mine, lesson.getId(), panel);
            var band = bands.get(lesson.getId());
            lessons.add(new EveningCardFacts.Lesson(lesson.getId(),
                    lesson.getTitle() == null || lesson.getTitle().isBlank() ? "today's lesson" : lesson.getTitle().trim(),
                    objectives(panel, true), objectives(panel, false), finished.contains(lesson.getId()), band,
                    practise == null ? null : practise.en(), practise == null ? null : practise.ar()));
        }
        return new EveningCardFacts(day.toString(), List.copyOf(lessons));
    }

    private ParentPanel panelOf(String lessonId) {
        return panels.findById(lessonId)
                .map(p -> json.decodeShared(p.getPanelJson(), ParentPanel.Companion.serializer())).orElse(null);
    }

    private static List<String> objectives(ParentPanel panel, boolean english) {
        if (panel == null) return List.of();
        var all = english ? panel.getObjectives().getEn() : panel.getObjectives().getAr();
        return all.stream().limit(MAX_OBJECTIVES).toList();
    }

    /** The teacher's tip for the one stop to practise — the most mistakes, and the fewest stars where they tie. */
    private record Tip(String en, String ar) {}

    private static Tip practise(List<AttemptEntity> mine, String lessonId, ParentPanel panel) {
        if (panel == null) return null;
        // Only the stops the teacher's panel has a tip for can win. Since feature 30 a child's attempts include the
        // stops of a targeted practice set, which the panel knows nothing about: ranking those in would hand the
        // evening to a stop with no tip and leave the card with nothing to practise, exactly when she practised most.
        Map<String, Tip> tips = new LinkedHashMap<>();
        for (var tip : panel.getStopTips()) tips.putIfAbsent(tip.getStopId(), new Tip(tip.getEn(), tip.getAr()));
        if (tips.isEmpty()) return null;
        Map<String, int[]> perStop = new LinkedHashMap<>();              // stopId -> {mistakes, best stars}
        for (var a : mine) {
            if (!lessonId.equals(a.getLessonId()) || !tips.containsKey(a.getStopId())) continue;
            var seen = perStop.computeIfAbsent(a.getStopId(), k -> new int[] {0, 0});
            seen[0] += a.getMistakes(); seen[1] = Math.max(seen[1], a.getStars());
        }
        if (perStop.isEmpty()) return null;
        String worst = perStop.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -e.getValue()[0])
                        .thenComparingInt(e -> e.getValue()[1]).thenComparing(Map.Entry::getKey))
                .findFirst().map(Map.Entry::getKey).orElse(null);
        return worst == null ? null : tips.get(worst);
    }

    // ---------------------------------------------------------------- the words

    /** What was written and by whom: {@link #MODEL} or {@link #TEMPLATE}. */
    record Written(String textAr, String textEn, String source) {}

    /**
     * Two or three sentences in each language, from the model when it can follow the rules and from the template when
     * it cannot. Every failure ends in the template: a card is a kindness, and a parent reading "we could not write
     * your card" would be worse than a plainer sentence.
     */
    private Written write(EveningCardFacts facts, String schoolId) {
        try {
            var accepted = ai.run(AiTask.of("parentEveningCard", "EVENING-" + schoolId, schoolId,
                            Prompts.EVENING_CARD, Prompts.userEveningCard(facts.blocks()))
                    // The teacher's own prose is in the prompt; a telephone number she typed into a tip is not the model's business.
                    .guardedBy(AiInputGuard.numbersOnly())
                    .reading((raw, attempt) -> {
                        JsonNode candidate;
                        try { candidate = json.tree(raw); }
                        catch (RuntimeException e) { return AiVerdict.rejected("the answer was not JSON at all"); }
                        var wrong = problems(candidate, facts.teacherText());
                        return wrong.isEmpty() ? AiVerdict.ok(candidate) : AiVerdict.rejected(wrong);
                    })
                    .keepingRejects()).json();
            return new Written(accepted.path("textAr").asText(), accepted.path("textEn").asText(), MODEL);
        } catch (AiFailure f) {
            log.info("evening card: the template is used ({}): {}", f.kind(), f.errorsJoined(3));
            return template(facts);
        } catch (RuntimeException e) {
            log.warn("evening card: the template is used after {}", e.toString());
            return template(facts);
        }
    }

    /**
     * Every rule the owner gave, checked rather than requested. Empty means the answer may be stored.
     *
     * <p><strong>A number is allowed only when the teacher wrote it.</strong> `Counting by 2s` is her own title and
     * the note may quote it, so every run of digits in an answer is looked for, exactly as written, in
     * {@link EveningCardFacts#teacherText} — the titles, objectives and tips the facts carry. Any other digit is a
     * number the model made up, which is what the rule is about, and a `%` or a mark is refused however it is spelt.
     */
    static List<String> problems(JsonNode answer, String teacherText) {
        var wrong = new ArrayList<String>();
        for (String field : List.of("textAr", "textEn")) {
            String text = answer.path(field).asText(null);
            if (text == null || text.isBlank()) { wrong.add("\"" + field + "\" is missing"); continue; }
            if (!text.contains(EveningCard.CHILD))
                wrong.add("\"" + field + "\" must write the child as " + EveningCard.CHILD + " and nothing else");
            if (text.length() > MAX_TEXT) wrong.add("\"" + field + "\" is longer than two or three short sentences");
            for (String number : madeUpNumbers(text, teacherText))
                wrong.add("\"" + field + "\" contains the number \"" + number + "\", which the lesson text does not;"
                        + " how it went is said in words only");
            if (text.indexOf('%') >= 0) wrong.add("\"" + field + "\" contains a percentage");
            String lower = text.toLowerCase(Locale.ROOT);
            for (String word : SCORE_WORDS)
                if (lower.contains(word)) { wrong.add("\"" + field + "\" mentions a mark (\"" + word + "\")"); break; }
        }
        return wrong;
    }

    /**
     * The runs of digits in {@code text} that {@code teacherText} does not hold <strong>as a number of its own</strong>,
     * in reading order and without repeats. Whole runs on both sides, never a substring: "Unit 13" in a title does
     * not make "3 of them right" the teacher's own number, and "Count to 20" does not license "2 stars".
     */
    static List<String> madeUpNumbers(String text, String teacherText) {
        var allowed = digitRuns(teacherText);
        var out = new java.util.LinkedHashSet<String>();
        for (String run : digitRuns(text)) if (!allowed.contains(run)) out.add(run);
        return List.copyOf(out);
    }

    /** The whole runs of digits in a text, in reading order. Western and Arabic-Indic alike. */
    private static java.util.Set<String> digitRuns(String text) {
        var out = new java.util.LinkedHashSet<String>();
        if (text == null) return out;
        int i = 0;
        while (i < text.length()) {
            if (!Character.isDigit(text.charAt(i))) { i++; continue; }
            int from = i;
            while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
            out.add(text.substring(from, i));
        }
        return out;
    }

    /**
     * The fallback, built from the same facts in fixed words: whether today's homework is done, how it went, and one
     * thing to do this evening. Digit-free and gender-free by construction, and it never quotes a title — a lesson
     * called "Counting by 2s" would carry a digit into a card that may not hold one.
     */
    static Written template(EveningCardFacts facts) {
        var first = facts.lessons().getFirst();
        String en = first.finished() ? EveningCard.CHILD + " finished today's homework." : EveningCard.CHILD + " started today's homework.";
        String ar = first.finished() ? "واجب اليوم مكتمل مع " + EveningCard.CHILD + "." : "واجب اليوم مع " + EveningCard.CHILD + " لم يكتمل بعد.";
        if (first.band() != null) {
            en += EveningCardFacts.sentence(first.band(), false);
            ar += EveningCardFacts.sentence(first.band(), true);
        }
        en += " Ask about it this evening — a few minutes together are enough.";
        ar += " اسألوا عنه هذا المساء؛ دقائق قليلة معًا تكفي.";
        return new Written(ar, en, TEMPLATE);
    }
}
