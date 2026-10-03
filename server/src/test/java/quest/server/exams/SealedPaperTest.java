package quest.server.exams;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import quest.api.dto.PublishedLesson;
import quest.api.dto.Stop;
import quest.api.samples.Seeds;
import quest.api.validation.SchemaValidator;
import quest.server.config.Json;
import quest.server.grading.AnswerKey;
import quest.server.grading.PaperSeal;

/**
 * B3 contract test: a sealed paper of every stop type (the three sample lessons cover all 22) keeps the shape an
 * installed app decodes — `Play.schema.json` and the shared-api decoder — carries opaque ids only, and the answers a
 * player gives on it with those ids are graded right.
 */
class SealedPaperTest {
    private static final Pattern OPAQUE = Pattern.compile("x[0-9a-f]{16}");
    private final Json json = new Json(new ObjectMapper());
    private final SealedPaper sealer = new SealedPaper(json);
    private final PaperSeal seal = new PaperSeal("k".repeat(32).getBytes(), "parent-1", "lesson-1");
    private final kotlinx.serialization.json.Json codec = SchemaValidator.INSTANCE.getJson();

    @Test void every_stop_type_decodes_keeps_its_shape_and_grades_the_answers_given_on_it() {
        var types = new java.util.HashSet<String>();
        for (PublishedLesson lesson : Seeds.INSTANCE.getLessons()) {
            var sealedJson = sealer.seal(codec.encodeToString(PublishedLesson.Companion.serializer(), lesson), seal);
            var sealed = codec.decodeFromString(PublishedLesson.Companion.serializer(), sealedJson);
            var tree = json.tree(sealedJson);
            for (int p = 0; p < lesson.getPlays().size(); p++) {
                var playJson = tree.get("plays").get(p);
                assertThat(playJson.get("sealed").asBoolean()).isTrue();
                assertThat(SchemaValidator.INSTANCE.validatePlayShape(playJson.toString()).getErrors()).as("the shape an installed app decodes").isEmpty();
                var stops = lesson.getPlays().get(p).getStops();
                for (int i = 0; i < stops.size(); i++) check(stops.get(i), sealed.getPlays().get(p).getStops().get(i), types);
            }
        }
        assertThat(types).as("every stop type").hasSize(22);
    }

    private void check(Stop original, Stop sent, java.util.Set<String> types) {
        types.add(original.getType());
        assertThat(sent.getType()).isEqualTo(original.getType());
        if (original instanceof Stop.ExitTicket t) {
            var q = ((Stop.ExitTicket) sent).getQuestions();
            for (int i = 0; i < q.size(); i++) check(t.getQuestions().get(i), q.get(i), types);
            return;
        }
        ids(sent).forEach(id -> assertThat(id).as(original.getType() + " ids are opaque").matches(OPAQUE));
        String honest = honest(original, sent);
        if (honest != null) assertThat(AnswerKey.grade(original, honest, seal).correct()).as(original.getType() + " graded from the sealed ids").isTrue();
        if (original instanceof Stop.SingleAnswer s && !(original instanceof Stop.TrueFalse))
            for (String other : s.getOptionIds()) if (!other.equals(s.getCorrectId()))
                assertThat(AnswerKey.grade(original, seal.opaque(original.getId(), other), seal).correct()).isFalse();
    }

    /** What a player taps on the sealed stop when she knows the answer, in the ids it was sent. */
    private String honest(Stop o, Stop sent) {
        String id = o.getId();
        if (o instanceof Stop.TrueFalse t) return t.getCorrectId();
        if (o instanceof Stop.SingleAnswer s) return seal.opaque(id, s.getCorrectId());
        if (o instanceof Stop.WriteSentence w) return w.getOptions() != null && !w.getFree() ? w.getAnswer() : null;
        if (o instanceof Stop.MultiSelect m) return String.join(",", m.getCorrectIds().stream().map(c -> seal.opaque(id, c)).toList());
        if (o instanceof Stop.SelectAll m) return String.join(",", m.getCorrectIds().stream().map(c -> seal.opaque(id, c)).toList());
        if (o instanceof Stop.Order r) {
            assertThat(((Stop.Order) sent).getCorrectOrder()).hasSameSizeAs(r.getItems());
            return String.join(",", r.getCorrectOrder().stream().map(c -> seal.opaque(id, c)).toList());
        }
        if (o instanceof Stop.ReadPage p && p.getTapTask() != null) return String.join(",", p.getTapTask().getCorrectIds().stream().map(c -> seal.opaque(id, c)).toList());
        if (o instanceof Stop.Match m) {
            var out = new ArrayList<String>();
            for (var pair : m.getPairs()) {
                String rightTile = seal.opaque(id, "tile|" + pair.getRight().getId());
                var shownAt = ((Stop.Match) sent).getPairs().stream().filter(q -> q.getRight().getId().equals(rightTile)).findFirst().orElseThrow();
                out.add(seal.opaque(id, pair.getId()) + "=" + shownAt.getId());
            }
            return String.join(",", out);
        }
        return null;
    }

    private static List<String> ids(Stop s) {
        var out = new ArrayList<String>();
        if (s instanceof Stop.Choice c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Sequence c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Count c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Compare c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Sound c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Word c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.ReadTap c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.MultiSelect c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.SelectAll c) c.getOptions().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Order c) c.getItems().forEach(t -> out.add(t.getId()));
        if (s instanceof Stop.Match c) c.getPairs().forEach(p -> { out.add(p.getId()); out.add(p.getLeft().getId()); out.add(p.getRight().getId()); });
        if (s instanceof Stop.ReadPage c && c.getTapTask() != null) c.getTapTask().getHotspots().forEach(h -> out.add(h.getId()));
        return out;
    }
}
