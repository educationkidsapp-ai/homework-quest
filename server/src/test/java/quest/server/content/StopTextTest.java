package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import quest.api.dto.Stop;
import quest.api.samples.Seeds;

/**
 * CR5: what a teacher reads instead of JSON. The sweep runs over the three seed lessons, which between them carry
 * one of every stop type (the test fails if a new type is added and the seeds are not), so a branch of
 * {@link StopText} that throws or says nothing cannot ship.
 */
class StopTextTest {

    /** Every stop of every seed play, exit-ticket questions included, keyed by the type it is the example of. */
    private static Map<String, Stop> byType() {
        Map<String, Stop> byType = new LinkedHashMap<>();
        for (var lesson : Seeds.INSTANCE.getLessons()) {
            List<Stop> all = new ArrayList<>();
            lesson.getPlays().forEach(p -> all.addAll(p.getStops()));
            all.addAll(lesson.getVariant().getStops());
            for (int i = 0; i < all.size(); i++) if (all.get(i) instanceof Stop.ExitTicket et) all.addAll(et.getQuestions());
            for (Stop s : all) byType.putIfAbsent(s.getType(), s);
        }
        return byType;
    }

    @Test void the_seeds_still_carry_one_of_every_stop_type() {
        List<String> concrete = new ArrayList<>();
        collect(Stop.class, concrete);
        assertThat(byType().keySet()).as("a stop type with no seed example is a type StopText is never tested on").containsAll(concrete);
    }

    @Test void every_type_reads_as_english_and_never_as_json() {
        byType().forEach((type, stop) -> {
            String text = StopText.describe(stop);
            assertThat(text).as(type + " says nothing").isNotBlank();
            assertThat(text.lines().findFirst().orElse("")).as(type + " does not open with its title").isEqualTo(stop.getTitle());
            assertThat(text).as(type + " loses the spoken prompt").contains("Pip says: " + stop.getSpeak());
            assertThat(text).as(type + " loses the parent tip").contains("Parent tip (English): " + stop.getParentTip().getEn())
                    .contains("Parent tip (Arabic): " + stop.getParentTip().getAr());
            assertThat(text).as(type + " still reads as JSON").doesNotContain("\":").doesNotContain("{\"");
            assertThat(text).as(type + " has no unfinished branch").doesNotContain("no readable description");
        });
    }

    /** The same stop described twice is the same string: the read side backfills on every request and must not churn. */
    @Test void describing_is_stable() {
        byType().forEach((type, stop) -> assertThat(StopText.describe(stop)).as(type).isEqualTo(StopText.describe(stop)));
    }

    @Test void the_right_answer_is_marked_and_the_wrong_ones_are_not() {
        var choice = (Stop.Choice) byType().get("choice");
        String text = StopText.describe(choice);
        for (var option : choice.getOptions()) {
            String shown = option.getLabel() != null ? option.getLabel()
                    : option.getIllustrationKey() != null ? "the " + option.getIllustrationKey() + " picture"
                    : "the page picture " + option.getPageImageId();
            assertThat(text).as("the tile is shown by whatever it carries").contains("- " + shown + (option.getId().equals(choice.getCorrectOptionId()) ? " (correct)" : "\n"));
        }
        assertThat(text).contains("Question: " + choice.getQuestion()).contains("Hint: " + choice.getHint());
        assertThat(text.split("\\(correct\\)", -1)).as("exactly one option is the right one").hasSize(2);
    }

    @Test void a_true_false_stop_says_which_it_is() {
        var tf = (Stop.TrueFalse) byType().get("trueFalse");
        assertThat(StopText.describe(tf)).contains("Statement: " + tf.getStatement())
                .contains("The statement is " + (tf.getAnswer() ? "true" : "false") + ".");
    }

    @Test void pairs_order_frames_cues_and_model_answers_are_spelled_out() {
        var match = (Stop.Match) byType().get("match");
        assertThat(StopText.describe(match)).contains(" goes with ");

        var order = (Stop.Order) byType().get("order");
        String orderText = StopText.describe(order);
        assertThat(orderText).contains("The right order:");
        int n = 1;
        for (String id : order.getCorrectOrder()) {
            String item = order.getItems().stream().filter(i -> i.getId().equals(id)).findFirst().orElseThrow().getText();
            assertThat(orderText).contains("  " + n++ + ". " + item);
        }

        var frame = (Stop.WriteSentence) byType().get("writeSentence");
        assertThat(StopText.describe(frame)).contains("Sentence frame: " + frame.getFrame()).contains("Answer: " + frame.getAnswer());

        var retell = (Stop.Retell) byType().get("retell");
        String retellText = StopText.describe(retell);
        assertThat(retellText).contains("Cues:").contains("Model answer: " + retell.getModelAnswer());
        for (var cue : retell.getCues()) assertThat(retellText).contains("- " + cue.getStage() + ": " + cue.getCue());

        var explain = (Stop.Explain) byType().get("explain");
        String explainText = StopText.describe(explain);
        assertThat(explainText).contains(explain.getExplanation());
        for (var w : explain.getWorkedExamples()) {
            assertThat(explainText).contains("Worked example: " + w.getPrompt()).contains("  Answer: " + w.getAnswer());
            int step = 1; for (String s : w.getSteps()) assertThat(explainText).contains("  " + step++ + ". " + s);
        }
    }

    /** The exit ticket's three questions are described the same way, one rung in, so nothing is hidden behind a count. */
    @Test void an_exit_ticket_describes_its_questions_recursively() {
        var ticket = (Stop.ExitTicket) byType().get("exitTicket");
        String text = StopText.describe(ticket);
        assertThat(text).contains("Exit ticket — " + ticket.getQuestions().size() + " questions:");
        for (var q : ticket.getQuestions()) {
            assertThat(text).contains("  " + q.getTitle()).contains("  Pip says: " + q.getSpeak());
            for (String line : StopText.describe(q).lines().toList()) if (!line.isBlank()) assertThat(text).contains("  " + line);
        }
    }

    private void collect(Class<?> root, List<String> out) {
        if (root.getPermittedSubclasses() == null) return;
        for (Class<?> sub : root.getPermittedSubclasses()) {
            if (!sub.isInterface() && !Modifier.isAbstract(sub.getModifiers())) out.add(typeOf(sub));
            collect(sub, out);
        }
    }

    /** The `type` discriminator a class serialises as: its `@SerialName`, which is the simple name lower-camelled. */
    private String typeOf(Class<?> type) {
        String n = type.getSimpleName();
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }
}
