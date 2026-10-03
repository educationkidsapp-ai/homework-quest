package quest.server.grading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.api.dto.StopScoring;

/**
 * B3 (D2): the server's own answer key. An exam answer is graded here from the stop the server stored and the answer
 * the child gave — the `correct` and `stars` the app reports are never read for it.
 *
 * <p><strong>The answer format is the player's</strong> (`shared-ui` stops in exam mode): a single-answer stop sends
 * the option id it was given (`"b"`, `"true"`), a word-tile sentence the word, multi-select and select-all the picked
 * ids joined by `,`, an order stop the placed ids in order joined by `,`, a match stop `left=right` pairs joined by
 * `,` and a read-page tap task the tapped hotspot ids. The rule is the player's exam rule: one answer, all of it right
 * or the question is wrong — three stars or none ({@link StopScoring}'s first-try values).
 *
 * <p><strong>No key, no grade.</strong> A retell, an open answer, free writing, a tracing and an exit ticket's wrapper
 * carry nothing a machine can check (a tracing's coverage is measured on the tablet), so they are {@link Kind#UNKEYED}:
 * stored with no stars and wrong, and {@link Scoring} treats them on a fixed paper as waiting for the teacher's mark —
 * never what the device claimed. An information stop is not a question and is {@link Kind#INFO}.
 */
public final class AnswerKey {
    private AnswerKey() {}

    public enum Kind { KEYED, INFO, UNKEYED }

    /** One answer as the server grades it. `correct` and `stars` are meaningful only for {@link Kind#KEYED}. */
    public record Graded(Kind kind, boolean correct, int stars, int mistakes) {}

    private static final int FULL = StopScoring.INSTANCE.singleAnswer(1);

    /** Whether the server can grade this stop's answer by itself. */
    public static boolean keyed(Stop stop) { return kind(stop) == Kind.KEYED; }

    public static Kind kind(Stop stop) {
        if (stop instanceof Stop.SingleAnswer || stop instanceof Stop.MultiSelect || stop instanceof Stop.SelectAll
                || stop instanceof Stop.Match || stop instanceof Stop.Order) return Kind.KEYED;
        if (stop instanceof Stop.WriteSentence w) return !w.getFree() && w.getOptions() != null ? Kind.KEYED : Kind.UNKEYED;
        if (stop instanceof Stop.ReadPage p) return p.getTapTask() != null ? Kind.KEYED : Kind.INFO;
        return stop.getCategory() == StopCategory.INFO ? Kind.INFO : Kind.UNKEYED;
    }

    public static Graded grade(Stop stop, String answer) { return grade(stop, answer, null); }

    /**
     * The same for an answer that may name the opaque ids of a sealed paper (B3): `seal` maps them back, and an id it
     * does not know — one from an unsealed copy, downloaded after the release — is taken as it is. A match answer
     * given on a sealed copy is graded against the right-hand tiles that copy showed ({@link PaperSeal#rightOwners}).
     */
    public static Graded grade(Stop stop, String answer, PaperSeal seal) {
        var kind = kind(stop);
        if (kind == Kind.INFO) return new Graded(kind, true, StopScoring.INFO, 0);
        if (kind == Kind.UNKEYED) return new Graded(kind, false, 0, 0);
        boolean right = right(stop, answer == null ? "" : answer.trim(), seal);
        return new Graded(kind, right, right ? FULL : 0, right ? 0 : 1);
    }

    /** B3: what an exit-ticket question holds when the app sent the ticket without its answers — the teacher marks it. */
    public static final String PENDING = "{\"pending\":\"no answer was sent for this question\"}";

    /** Whether the answer is the key, for a {@link Kind#KEYED} stop. */
    static boolean right(Stop stop, String answer) { return right(stop, answer, null); }

    static boolean right(Stop stop, String answer, PaperSeal seal) {
        String id = stop.getId();
        if (stop instanceof Stop.SingleAnswer s) return !s.getCorrectId().isEmpty() && s.getCorrectId().equals(back(seal, id, s.getOptionIds(), answer));
        if (stop instanceof Stop.WriteSentence w) return w.getAnswer().trim().equals(answer);
        if (stop instanceof Stop.MultiSelect m) return same(back(seal, id, tileIds(m.getOptions()), list(answer)), m.getCorrectIds());
        if (stop instanceof Stop.SelectAll s) return same(back(seal, id, tileIds(s.getOptions()), list(answer)), s.getCorrectIds());
        if (stop instanceof Stop.Order o) {
            var ids = o.getItems().stream().map(quest.api.dto.OrderItem::getId).toList();
            return !o.getCorrectOrder().isEmpty() && back(seal, id, ids, list(answer)).equals(o.getCorrectOrder());
        }
        if (stop instanceof Stop.ReadPage p) {
            var ids = p.getTapTask().getHotspots().stream().map(quest.api.dto.Hotspot::getId).toList();
            return same(back(seal, id, ids, list(answer)), p.getTapTask().getCorrectIds());
        }
        if (stop instanceof Stop.Match m) {
            var ids = m.getPairs().stream().map(quest.api.dto.MatchPair::getId).toList();
            Map<String, String> paired = new HashMap<>();
            boolean sealedCopy = seal != null;
            for (String pair : list(answer)) {
                int eq = pair.indexOf('=');
                if (eq <= 0) return false;
                String left = pair.substring(0, eq), right = pair.substring(eq + 1);
                String l = seal == null ? null : seal.original(id, ids, left), r = seal == null ? null : seal.original(id, ids, right);
                sealedCopy &= l != null && r != null;
                if (paired.put(l == null ? left : l, r == null ? right : r) != null) return false;
            }
            if (paired.size() != ids.size()) return false;
            var owners = sealedCopy ? seal.rightOwners(id, ids) : null;
            for (String pairId : ids) {
                String shownAt = paired.get(pairId);
                if (shownAt == null || !pairId.equals(owners == null ? shownAt : owners.get(shownAt))) return false;
            }
            return true;
        }
        return false;
    }

    private static String back(PaperSeal seal, String stopId, List<String> ids, String token) { return seal == null ? token : seal.back(stopId, ids, token); }
    private static List<String> back(PaperSeal seal, String stopId, List<String> ids, List<String> tokens) { return seal == null ? tokens : seal.back(stopId, ids, tokens); }
    private static List<String> tileIds(List<quest.api.dto.Tile> tiles) { return tiles.stream().map(quest.api.dto.Tile::getId).toList(); }

    /**
     * Every stop of some plays by id, an exit ticket's questions included — an attempt names the question it
     * answered, which on a ticket is one of the questions rather than the wrapper.
     */
    public static Map<String, Stop> index(List<Stop> stops) {
        Map<String, Stop> out = new HashMap<>();
        for (Stop s : stops) {
            out.put(s.getId(), s);
            if (s instanceof Stop.ExitTicket t) for (Stop q : t.getQuestions()) out.put(q.getId(), q);
        }
        return out;
    }

    private static List<String> list(String answer) {
        var out = new ArrayList<String>();
        for (String part : answer.split(",")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    /** A key with nothing in it is never matched — not even by an empty answer. */
    private static boolean same(List<String> answer, List<String> key) { return !key.isEmpty() && Set.copyOf(answer).equals(Set.copyOf(key)); }
}
