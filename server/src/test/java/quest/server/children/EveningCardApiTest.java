package quest.server.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import quest.api.dto.Bilingual;
import quest.api.dto.BilingualList;
import quest.api.dto.EveningCard;
import quest.api.dto.ParentPanel;
import quest.api.dto.StopTip;
import quest.server.analysis.LlmClient;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingTestSupport;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * Feature 28 end to end: the facts, the validator, the 204s, the one card a day and the one evening push.
 *
 * <p>The model is a {@link MockitoBean} because this feature <em>is</em> its rules: every case below is a provider
 * that answers one particular way — well, with a digit, with an Arabic-Indic digit, with a percentage, with a mark,
 * without the placeholder — and the assertion is what the server does with that answer. It also makes the two
 * questions the owner cares about askable: what exactly was sent to the provider (the captured user turn), and how
 * many times (one card, one call).
 *
 * <p>{@code PER_CLASS}, so the parent of {@link #CHILD_NAME} is one parent for the whole class: {@link #PARENT} is a
 * fresh token per test <em>instance</em>, and a child created in {@code @BeforeAll} must stay hers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EveningCardApiTest extends GradingTestSupport {
    private static final String SCHOOL = "ev-school-a", OTHER_SCHOOL = "ev-school-b";
    private static final String TEACHER = "ev-teacher", SECTION = "ev-1a";
    private static final String HOMEWORK = "ev-lesson-today", YESTERDAY = "ev-lesson-yesterday", EXAM = "ev-exam-today";
    /** The one name that may never reach the provider. */
    private static final String CHILD_NAME = "Lamya Alqahtani";
    /** The teacher's own title, number and all: a card may quote it, and may invent no other number. */
    private static final String TITLE = "Counting by 2s";
    /** A Monday, which the default school week teaches, and the Friday after it, which it does not. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14), DAY_OFF = LocalDate.of(2026, 9, 18);
    /** 17:00 and 18:30 in Asia/Riyadh, the school's own zone: after the card, and after the push. */
    private static final Instant AFTERNOON = Instant.parse("2026-09-14T14:00:00Z"), EVENING = Instant.parse("2026-09-14T15:30:00Z");
    private static final String GOOD = "{\"textAr\":\"عمل {child} في الترتيب اليوم. اسألوا {child} عن الخطوات.\","
            + "\"textEn\":\"{child} practised putting the steps in order today. Ask {child} to tell you them back.\"}";

    @MockitoBean LlmClient llm;

    @Autowired EveningCardRepository cards;
    @Autowired EveningCardJob job;
    @Autowired PushSender pushes;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.push.ParentDeviceRepository parentDevices;

    private final String OTHER_PARENT = "Bearer fake-token-parent-" + UUID.randomUUID().toString().substring(0, 8);
    private ClassEntity section;
    private String childId, otherChildId;

    @Override public String prefix() { return "ev-"; }

    @BeforeAll void seedOneSchoolDay() throws Exception {
        school(SCHOOL, "Evening Primary", "EVSCHA"); school(OTHER_SCHOOL, "Distant Primary", "EVSCHB");
        teacher(TEACHER, SCHOOL, "Ms Reem");
        section = klass(SECTION, SCHOOL, TEACHER, "1A");
        setFlag(null, SCHOOL, FlagKeys.PARENT_EVENING_CARD, true);
        setFlag(null, OTHER_SCHOOL, FlagKeys.PARENT_EVENING_CARD, true);
        childId = child(CHILD_NAME, "EVSCHA", section);
        otherChildId = someoneElsesChild();

        // Today's homework, with the teacher's own panel: objectives, and a tip for each of the two single stops.
        // Her title carries a number, which is the one number a card may quote.
        var homework = lesson(HOMEWORK, SCHOOL, section, TODAY);
        homework.setTitle(TITLE); lessons.save(homework);
        store.savePanel(HOMEWORK, panel(
                new StopTip(stopId(HOMEWORK, 1), "Count them out loud together.", "عدّوا بصوت عالٍ معًا."),
                new StopTip(stopId(HOMEWORK, 2), "Lay the steps out with real spoons.", "رتّبوا الخطوات بملاعق حقيقية.")));
        playTheSample(childId, HOMEWORK);                         // one wrong first try on stop 2: that is the stop to practise

        // Yesterday's homework, played today: "played or due today" is two rules, and this is the other one.
        lesson(YESTERDAY, SCHOOL, section, TODAY.minusDays(1));
        answeredAt(childId, YESTERDAY, stopId(YESTERDAY, 1), AFTERNOON.minusSeconds(3600), true, 3);

        // An exam of the same day, never released: it must not reach the facts, the prompt or the card.
        var exam = lesson(EXAM, SCHOOL, section, TODAY);
        exam.setType("exam"); exam.setReleasedAt(null); lessons.save(exam);
        store.savePanel(EXAM, panel(new StopTip(stopId(EXAM, 1), "Secret exam tip.", "تلميح الاختبار.")));
        playTheSample(childId, EXAM);
    }

    @AfterAll void takeItBackOut() {
        cards.deleteAll(cards.findAll().stream().filter(c -> c.getSchoolId().startsWith("ev-")).toList());
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId().startsWith("ev-")).toList());
        parentDevices.findAll().stream().filter(d -> d.getToken().startsWith("ev-")).forEach(d -> parentDevices.deleteByTokenValue(d.getToken()));
        removeSeed();          // the section and the two schools stay, as `ChatTestSupport`'s do: a soft-deleted child still names her class
    }

    @BeforeEach void pinTheAfternoonAndTheModel() {
        clock.pinTo(AFTERNOON);
        answers(GOOD);
    }

    @AfterEach void release() {
        clock.release();
        Mockito.reset(llm);
        cards.deleteAll(cards.findAll().stream().filter(c -> c.getSchoolId().startsWith("ev-")).toList());
    }

    // ---------------------------------------------------------------- what the parent reads

    @Test void the_card_is_todays_lessons_in_words_and_the_child_is_a_placeholder() throws Exception {
        var card = parentGet("/children/" + childId + "/evening-card");

        assertThat(card.get("day").asText()).isEqualTo(TODAY.toString());
        assertThat(card.get("source").asText()).isEqualTo("model");
        assertThat(card.get("textEn").asText()).contains(EveningCard.CHILD).doesNotContain(CHILD_NAME);
        assertThat(card.get("textAr").asText()).contains(EveningCard.CHILD).doesNotContain(CHILD_NAME);
        assertThat(ids(card.get("lessonIds")))
                .as("the homework due today and the homework she played today, and never the exam")
                .containsExactlyInAnyOrder(HOMEWORK, YESTERDAY);
    }

    /** The owner's rule, from the other side: what was sent, which is the teacher's text and her own result. */
    @Test void the_prompt_carries_lesson_text_and_a_result_in_words_and_nothing_about_the_child() throws Exception {
        parentGet("/children/" + childId + "/evening-card");

        String sent = captured();
        assertThat(sent).as("the teacher's own words").contains("Lay the steps out with real spoons.")
                .contains("رتّبوا الخطوات بملاعق حقيقية.").contains("Practise together.");
        assertThat(sent).as("her result, in words").contains("worth another look").contains("يستحق مراجعة أخرى");
        assertThat(sent).as("and nothing of the child herself")
                .doesNotContain(CHILD_NAME).doesNotContain("Lamya").doesNotContain(childId)
                .doesNotContain("Secret exam tip.").doesNotContain(EXAM);
        assertThat(sent).as("no star, no mistake count, no mark").doesNotContain("%").doesNotContain("stars");
    }

    /**
     * Feature 30 put a child's practice-set stops in the same `attempts` table, and the parent panel has a tip only
     * for the <em>level</em> stops. So the evening she practises hardest — more mistakes on the practice set than on
     * the level stop it was built for — used to be the evening her card had nothing to practise in it: the ranking
     * picked a stop the panel knows nothing about and the tip came back null. Only stops the teacher wrote a tip for
     * are ranked.
     */
    @Test void the_practice_tip_survives_the_child_playing_a_practice_set() throws Exception {
        // She makes more mistakes on the targeted practice set than anywhere in the lesson's own levels.
        for (int number = 1; number <= 3; number++)
            attemptStop(childId, HOMEWORK, HOMEWORK + ":1:2:p1", 9, number, false, 0);

        parentGet("/children/" + childId + "/evening-card");
        assertThat(captured()).as("the teacher's tip for the level stop she struggled with, not nothing at all")
                .contains("Lay the steps out with real spoons.").contains("Practise together.");
    }

    @Test void a_sealed_exam_never_reaches_the_facts() throws Exception {
        var card = parentGet("/children/" + childId + "/evening-card");
        assertThat(ids(card.get("lessonIds"))).doesNotContain(EXAM);
        assertThat(captured()).doesNotContain("Secret exam tip.");

        var row = cards.findOneOfChild(SCHOOL, childId, TODAY).orElseThrow();
        assertThat(row.getFactsJson()).as("not even in the facts the card was written from").doesNotContain(EXAM);
    }

    /** One card a day, and one model call for it: the second request is the stored row, read back word for word. */
    @Test void the_card_is_written_once_a_day_and_read_back_without_a_second_model_call() throws Exception {
        var first = parentGet("/children/" + childId + "/evening-card");
        var again = parentGet("/children/" + childId + "/evening-card");

        assertThat(again.get("textEn").asText()).isEqualTo(first.get("textEn").asText());
        assertThat(again.get("textAr").asText()).isEqualTo(first.get("textAr").asText());
        verify(llm, times(1)).complete(any(), any(), any());
        assertThat(cards.findAll().stream().filter(c -> c.getChildId().equals(childId)).toList()).hasSize(1);
    }

    // ---------------------------------------------------------------- the rules, checked rather than requested

    /**
     * The owner's "no scores or percentages", and the placeholder the app needs. Each case is a model that answers
     * with exactly one broken note, twice (the loop's two attempts), and every one of them ends in the template.
     */
    @Test void a_digit_a_percentage_a_mark_or_a_missing_placeholder_costs_the_model_the_card() throws Exception {
        for (String broken : List.of(
                note("{child} got 3 stars today.", "حصل {child} على ٣ نجوم اليوم."),            // a Western digit
                note("{child} did well today.", "أجاب {child} عن ٩ أسئلة."),                    // an Arabic-Indic digit
                note("{child} is at 85% today.", "بلغ {child} ٨٥٪ اليوم."),                     // a percentage
                note("{child}'s score is good.", "درجة {child} جيدة."),                         // a mark, in both languages
                note("She did well today.", "أحسنت اليوم."),                                    // no placeholder
                note("{child} " + "did very well today. ".repeat(40), "عمل {child} جيدًا."))) {  // an essay, not a note
            cards.deleteAll(cards.findAll().stream().filter(c -> c.getChildId().equals(childId)).toList());
            answers(broken);

            var card = parentGet("/children/" + childId + "/evening-card");
            assertThat(card.get("source").asText()).as(broken).isEqualTo("template");
            for (String field : List.of("textEn", "textAr")) {
                String text = card.get(field).asText();
                assertThat(text).as(field + " of " + broken).contains(EveningCard.CHILD).doesNotContain("%");
                assertThat(text.chars().anyMatch(Character::isDigit)).as("a digit in " + field).isFalse();
                for (String word : List.of("score", "percent", "درجة", "نسبة"))
                    assertThat(text.toLowerCase()).doesNotContain(word);
            }
        }
    }

    /**
     * The owner's rule is about numbers the model makes up, not about the teacher's own words: her title is
     * `Counting by 2s`, so a note that names her lesson is kept, while a number she never wrote is still the
     * template — and a percentage is refused even beside a number the note was entitled to quote.
     */
    @Test void a_number_the_teacher_wrote_may_be_quoted_and_no_other_may() throws Exception {
        var quoted = answered(note("{child} practised " + TITLE + " today. Ask {child} to count on with you.",
                "تدرب {child} على " + TITLE + " اليوم. اعدّوا مع {child}."));
        assertThat(quoted.get("source").asText()).as("her own title, quoted word for word").isEqualTo("model");
        assertThat(quoted.get("textEn").asText()).contains(TITLE);

        var invented = answered(note("{child} practised " + TITLE + " and got 3 of them right.",
                "تدرب {child} على " + TITLE + "."));
        assertThat(invented.get("source").asText()).as("`3` is nowhere in the lesson text").isEqualTo("template");

        var percentage = answered(note("{child} practised " + TITLE + " — 2% of it.",
                "تدرب {child} على " + TITLE + "."));
        assertThat(percentage.get("source").asText()).as("a percentage, beside a number she may quote").isEqualTo("template");
        for (var card : List.of(invented, percentage))
            for (String field : List.of("textEn", "textAr")) assertThat(card.get(field).asText()).doesNotContain("%");
    }

    /**
     * The number rule, as a rule about <em>numbers</em> rather than about digit strings. "Unit 13" in a title and
     * "Count to 20" in an objective are the teacher's own words, and they used to license "3 of them right" and
     * "2 stars" — a substring search finds 3 inside 13 and 2 inside 20. Whole runs on both sides.
     */
    @Test void a_number_hiding_inside_a_longer_one_is_not_the_teachers_own() {
        assertThat(EveningCardService.madeUpNumbers("{child} got 3 of them right.", "Unit 13 — Count to 20"))
                .as("3 is only in 13").containsExactly("3");
        assertThat(EveningCardService.madeUpNumbers("{child} earned 2 stars today.", "Count to 20"))
                .as("2 is only in 20").containsExactly("2");
        assertThat(EveningCardService.madeUpNumbers("{child} practised Counting by 2s.", "Counting by 2s"))
                .as("her own number, word for word").isEmpty();
        assertThat(EveningCardService.madeUpNumbers("عمل {child} ١٣ تمرينًا.", "التمرين ١٣"))
                .as("Arabic-Indic digits are digits too").isEmpty();
    }

    @Test void a_provider_that_fails_still_leaves_her_a_card() throws Exception {
        Mockito.doThrow(new LlmClient.LlmException("busy", null, true)).when(llm).complete(any(), any(), any());

        var card = parentGet("/children/" + childId + "/evening-card");
        assertThat(card.get("source").asText()).isEqualTo("template");
        assertThat(card.get("textEn").asText()).contains(EveningCard.CHILD);
    }

    // ---------------------------------------------------------------- the three silences

    @Test void nothing_before_four_oclock_on_a_day_off_or_with_no_homework() throws Exception {
        clock.pinTo(TODAY);                                           // noon UTC is 15:00 in the school's own zone
        noContent();

        clock.pinTo(Instant.parse("2026-09-18T14:00:00Z"));           // 17:00 on a Friday: the school does not teach
        assertThat(DAY_OFF.getDayOfWeek().name()).isEqualTo("FRIDAY");
        noContent();

        clock.pinTo(Instant.parse("2026-09-15T14:00:00Z"));           // a school day with nothing of hers in it
        noContent();

        verify(llm, never()).complete(any(), any(), any());
    }

    // ---------------------------------------------------------------- the evening push

    @Test void the_job_tells_each_parent_once_a_day() throws Exception {
        String phone = "ev-phone-" + UUID.randomUUID().toString().replace("-", "");
        PushProbe.register(mvc, PARENT, phone, "en");
        clock.pinTo(EVENING);

        // Both children of 1A have a card — the other family's too — and each parent is told once; one phone is registered.
        assertThat(job.run(SCHOOL)).as("each parent of the section, once").isEqualTo(2);
        var sent = PushProbe.await(pushes, phone, 1);
        assertThat(sent).hasSize(1);
        var push = sent.getFirst().message();
        assertThat(push.getKind().name()).isEqualTo("EVENING_CARD");
        assertThat(push.getTitle()).as("her own child's name, on her own phone").contains(CHILD_NAME);
        assertThat(push.getLink()).isEqualTo("/children/" + childId + "/evening-card");
        assertThat(push.getChildId()).isEqualTo(childId);

        assertThat(job.run(SCHOOL)).as("a second pass tells nobody again").isZero();
        assertThat(PushProbe.await(pushes, phone, 1)).hasSize(1);
        assertThat(notificationRows.findAll().stream()
                .filter(n -> "evening.card".equals(n.getKind()) && childId.equals(n.getChildId())).toList()).hasSize(1);
    }

    @Test void the_job_does_nothing_before_six_or_on_a_day_off() {
        clock.pinTo(AFTERNOON);
        assertThat(job.run(SCHOOL)).as("17:00 is the card's hour, not the push's").isZero();
        clock.pinTo(Instant.parse("2026-09-18T16:00:00Z"));
        assertThat(job.run(SCHOOL)).as("and a day the school does not teach has no evening").isZero();
    }

    // ---------------------------------------------------------------- whose card it is

    @Test void another_familys_child_is_not_found_and_the_switch_hides_the_route() throws Exception {
        mvc.perform(get("/children/" + otherChildId + "/evening-card").header("Authorization", PARENT))
                .andExpect(status().isNotFound());
        mvc.perform(get("/children/" + childId + "/evening-card").header("Authorization", OTHER_PARENT))
                .andExpect(status().isNotFound());
        mvc.perform(get("/children/" + childId + "/evening-card")).andExpect(status().isUnauthorized());

        setFlag(null, SCHOOL, FlagKeys.PARENT_EVENING_CARD, false);
        try {
            mvc.perform(get("/children/" + childId + "/evening-card").header("Authorization", PARENT))
                    .andExpect(status().isNotFound());
            assertThat(cards.findOneOfChild(SCHOOL, childId, TODAY)).as("and nothing was written behind the switch").isEmpty();
        } finally {
            setFlag(null, SCHOOL, FlagKeys.PARENT_EVENING_CARD, true);
        }
        verify(llm, never()).complete(any(), any(), any());
    }

    // ---------------------------------------------------------------- helpers

    /** The card a model that answers with exactly this note leaves behind, on a day with no card yet. */
    private JsonNode answered(String json) throws Exception {
        cards.deleteAll(cards.findAll().stream().filter(c -> c.getChildId().equals(childId)).toList());
        answers(json);
        return parentGet("/children/" + childId + "/evening-card");
    }

    private void noContent() throws Exception {
        mvc.perform(get("/children/" + childId + "/evening-card").header("Authorization", PARENT))
                .andExpect(status().isNoContent());
    }

    /** The user turn the provider was given, which is the whole of what left the school. */
    private String captured() {
        var user = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), user.capture(), any());
        return user.getValue();
    }

    private void answers(String json) {
        Mockito.doReturn(new LlmClient.Result(json, 20, 10)).when(llm).complete(any(), any(), any());
        Mockito.doReturn("mock").when(llm).name();
    }

    private static String note(String en, String ar) {
        return "{\"textAr\":\"" + ar + "\",\"textEn\":\"" + en.replace("\"", "\\\"") + "\"}";
    }

    private static ParentPanel panel(StopTip... tips) {
        return new ParentPanel(new BilingualList(List.of("Practise together."), List.of("تدرّبوا معًا.")),
                List.of(new Bilingual("Count them out loud.", "عدّوا بصوت عالٍ.")),
                List.of(new Bilingual("Try the next ten.", "جرّبوا العشرة التالية.")),
                List.of(tips), List.of());
    }

    /** An attempt at an hour this test chose: "played today" is a question about the school's own day. */
    private void answeredAt(String child, String lessonId, String stop, Instant at, boolean correct, int stars) {
        var a = new Entities.AttemptEntity();
        a.setId(prefix() + UUID.randomUUID()); a.setChildId(child); a.setLessonId(lessonId); a.setStopId(stop);
        a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(correct); a.setAttemptNumber(1);
        a.setMistakes(correct ? 0 : 1); a.setStars(stars); a.setAnsweredAt(at);
        attempts.save(a);
    }

    private String someoneElsesChild() throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", OTHER_PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Noor Alharbi\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"EVSCHA\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(SECTION); childRows.save(c); });
        return id;
    }

    private static List<String> ids(JsonNode array) {
        var out = new java.util.ArrayList<String>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
