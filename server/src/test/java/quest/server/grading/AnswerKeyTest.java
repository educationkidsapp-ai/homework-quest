package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import quest.api.dto.Bilingual;
import quest.api.dto.Ingredient;
import quest.api.dto.MatchPair;
import quest.api.dto.OrderItem;
import quest.api.dto.Stop;
import quest.api.dto.TapTask;
import quest.api.dto.Tile;

/** B3 (D2): every keyed stop type is graded from its stored key in the player's own answer format. */
class AnswerKeyTest {
    private static final Ingredient I = new Ingredient("C", "carrot");
    private static final Bilingual TIP = new Bilingual("tip", "tip");
    private static final List<Tile> TILES = List.of(tile("a"), tile("b"), tile("c"));

    @Test void single_answer_stops_take_the_option_id() {
        var choice = new Stop.Choice("s", "t", "s", I, TIP, "h", "q?", TILES, "b", null, null);
        assertRight(choice, "b"); assertWrong(choice, "a"); assertWrong(choice, "{\"optionId\":\"b\"}");
        var tf = new Stop.TrueFalse("s", "t", "s", I, TIP, "h", "Two is more than one.", true, null, null);
        assertRight(tf, "true"); assertWrong(tf, "false");
    }

    @Test void a_word_tile_sentence_takes_the_word_and_free_writing_waits_for_the_teacher() {
        var tiles = new Stop.WriteSentence("s", "t", "s", I, TIP, "The ___ ran.", "dog", List.of("dog", "cat"), false, null, null);
        assertRight(tiles, "dog"); assertWrong(tiles, "cat");
        var free = new Stop.WriteSentence("s", "t", "s", I, TIP, "The ___ ran.", "dog", null, true, null, null);
        assertThat(AnswerKey.kind(free)).isEqualTo(AnswerKey.Kind.UNKEYED);
        var traced = new Stop.WriteSentence("s", "t", "s", I, TIP, "The ___ ran.", "dog", null, false, null, null);
        assertThat(AnswerKey.kind(traced)).as("tracing the answer has nothing to check").isEqualTo(AnswerKey.Kind.UNKEYED);
    }

    @Test void multi_select_and_select_all_need_exactly_the_right_set() {
        var multi = new Stop.MultiSelect("s", "t", "s", I, TIP, "Pick two", TILES, List.of("a", "c"), 2, null, null);
        assertRight(multi, "c,a"); assertWrong(multi, "a"); assertWrong(multi, "a,b,c");
        var all = new Stop.SelectAll("s", "t", "s", I, TIP, "Pick all", TILES, List.of("b"), null, null);
        assertRight(all, "b"); assertWrong(all, "b,c"); assertWrong(all, "");
    }

    @Test void match_needs_every_pair_and_order_the_exact_order() {
        var match = new Stop.Match("s", "t", "s", I, TIP, "Match", List.of(new MatchPair("p1", tile("x"), tile("y")), new MatchPair("p2", tile("u"), tile("v"))), null, null);
        assertRight(match, "p1=p1,p2=p2"); assertWrong(match, "p1=p2,p2=p1"); assertWrong(match, "p1=p1");
        var order = new Stop.Order("s", "t", "s", I, TIP, "Order", List.of(new OrderItem("1", "one", null), new OrderItem("2", "two", null)), List.of("1", "2"), null, null);
        assertRight(order, "1,2"); assertWrong(order, "2,1");
    }

    @Test void a_read_page_tap_task_is_keyed_and_a_plain_page_is_information() {
        var task = new Stop.ReadPage("s", "t", "s", I, TIP, 1, List.of("A cat."), null, null, null, new TapTask("Tap the cat", List.of(), List.of("h1")), null, null);
        assertRight(task, "h1"); assertWrong(task, "h2");
        var page = new Stop.ReadPage("s", "t", "s", I, TIP, 1, List.of("A cat."), null, null, null, null, null, null);
        assertThat(AnswerKey.grade(page, "").kind()).isEqualTo(AnswerKey.Kind.INFO);
    }

    @Test void open_stops_and_a_tracing_are_never_client_scored() {
        var retell = new Stop.Retell("s", "t", "s", I, TIP, "Tell it", List.of(), "model", true, null, null);
        var open = new Stop.OpenAnswer("s", "t", "s", I, TIP, "Why?", "speak", "model", null, null);
        var trace = new Stop.Trace("s", "t", "s", I, TIP, "abc", "h", null, null);
        for (Stop s : List.of(retell, open, trace)) {
            var g = AnswerKey.grade(s, "coverage=1.0");
            assertThat(g.kind()).isEqualTo(AnswerKey.Kind.UNKEYED);
            assertThat(g.stars()).isZero(); assertThat(g.correct()).isFalse();
            assertThat(Scoring.isOpen(s, true)).as("waits for the teacher on a paper").isTrue();
        }
        assertThat(Scoring.isOpen(trace, false)).as("a homework tracing keeps its stars rule").isFalse();
    }

    /** B3: a sealed paper shuffles a match stop's right tiles; the child's pairing is graded against what she saw. */
    @Test void a_match_answered_on_the_sealed_paper_is_graded_against_the_tiles_it_showed() {
        var pairs = List.of(new MatchPair("p1", tile("l1"), tile("r1")), new MatchPair("p2", tile("l2"), tile("r2")),
                new MatchPair("p3", tile("l3"), tile("r3")), new MatchPair("p4", tile("l4"), tile("r4")));
        var match = new Stop.Match("m1", "t", "s", I, TIP, "Match", pairs, null, null);
        String salt = "lesson|2026-10-01T00:00:00Z|hash";
        assertThat(AnswerKey.shuffled(salt, "m1", List.of("p1", "p2", "p3", "p4"))).as("this salt really shuffles").isNotEqualTo(List.of("p1", "p2", "p3", "p4"));

        var codec = quest.api.validation.SchemaValidator.INSTANCE.getJson();
        String paper = "{\"examPlay\":{\"stops\":[" + codec.encodeToString(Stop.Companion.serializer(), match) + "]}}";
        var json = new quest.server.config.Json(new com.fasterxml.jackson.databind.ObjectMapper());
        var sealedJson = json.tree(new quest.server.exams.SealedPaper(json).seal(paper, salt)).get("examPlay").get("stops").get(0).toString();
        var sealed = (Stop.Match) codec.decodeFromString(Stop.Companion.serializer(), sealedJson);

        var honest = new StringBuilder();       // she pairs each left with the pair whose shown right tile is its own
        for (var p : pairs) {
            var shownAt = sealed.getPairs().stream().filter(q -> q.getRight().getId().equals(p.getRight().getId())).findFirst().orElseThrow();
            honest.append(honest.isEmpty() ? "" : ",").append(p.getId()).append('=').append(shownAt.getId());
        }
        assertThat(AnswerKey.grade(match, honest.toString(), salt).correct()).isTrue();
        assertThat(AnswerKey.grade(match, "p1=p1,p2=p2,p3=p3,p4=p4", salt).correct()).as("the pairing the paper no longer shows").isFalse();
        assertThat(AnswerKey.grade(match, "p1=p1,p2=p2,p3=p3,p4=p4").correct()).as("unsealed, ids pair as stored").isTrue();
    }

    private static void assertRight(Stop stop, String answer) {
        var g = AnswerKey.grade(stop, answer);
        assertThat(g.correct()).as(answer).isTrue(); assertThat(g.stars()).isEqualTo(3); assertThat(g.mistakes()).isZero();
    }

    private static void assertWrong(Stop stop, String answer) {
        var g = AnswerKey.grade(stop, answer);
        assertThat(g.correct()).as(answer).isFalse(); assertThat(g.stars()).isZero();
    }

    private static Tile tile(String id) { return new Tile(id, id, null, null); }
}
