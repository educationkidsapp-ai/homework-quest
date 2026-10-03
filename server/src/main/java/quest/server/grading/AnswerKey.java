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
     * The same, for an answer given on a <strong>sealed</strong> paper when `salt` is set (B3): a match stop was sent
     * with its right-hand tiles shuffled across the pairs by {@link #shuffled}, so `left=q` names the pair whose
     * <em>shown</em> right tile she chose, and it is right when that tile is the left's own.
     */
    public static Graded grade(Stop stop, String answer, String salt) {
        var kind = kind(stop);
        if (kind == Kind.INFO) return new Graded(kind, true, StopScoring.INFO, 0);
        if (kind == Kind.UNKEYED) return new Graded(kind, false, 0, 0);
        boolean right = right(stop, answer == null ? "" : answer.trim(), salt);
        return new Graded(kind, right, right ? FULL : 0, right ? 0 : 1);
    }

    /**
     * B3: the order a sealed paper shows a stop's ids in — by a hash of a server-only `salt`, the stop and the id, so
     * it is the same on every read and at grading time and says nothing about the key to someone without the salt.
     */
    public static List<String> shuffled(String salt, String stopId, List<String> ids) {
        return ids.stream().sorted(java.util.Comparator.comparing((String id) -> hash(salt + "|" + stopId + "|" + id)).thenComparing(id -> id)).toList();
    }

    /** The salt of one lesson's sealed paper: two server-side values a parent's download never carries. */
    public static String salt(quest.server.content.Entities.LessonEntity lesson) {
        return lesson.getId() + "|" + lesson.getCreatedAt() + "|" + lesson.getSourceHash();
    }

    private static String hash(String s) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Whether the answer is the key, for a {@link Kind#KEYED} stop. */
    static boolean right(Stop stop, String answer) { return right(stop, answer, null); }

    static boolean right(Stop stop, String answer, String salt) {
        if (stop instanceof Stop.SingleAnswer s) return !s.getCorrectId().isEmpty() && s.getCorrectId().equals(answer);
        if (stop instanceof Stop.WriteSentence w) return w.getAnswer().trim().equals(answer);
        if (stop instanceof Stop.MultiSelect m) return same(set(answer), m.getCorrectIds());
        if (stop instanceof Stop.SelectAll s) return same(set(answer), s.getCorrectIds());
        if (stop instanceof Stop.Order o) return !o.getCorrectOrder().isEmpty() && list(answer).equals(o.getCorrectOrder());
        if (stop instanceof Stop.ReadPage p) return same(set(answer), p.getTapTask().getCorrectIds());
        if (stop instanceof Stop.Match m) {
            Map<String, String> paired = new HashMap<>();
            for (String pair : list(answer)) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || paired.put(pair.substring(0, eq), pair.substring(eq + 1)) != null) return false;
            }
            if (paired.size() != m.getPairs().size()) return false;
            var ids = m.getPairs().stream().map(quest.api.dto.MatchPair::getId).toList();
            // on a sealed paper the right tile shown at pair i is pair `shown[i]`'s own
            var shown = salt == null ? ids : shuffled(salt, m.getId(), ids);
            for (var p : m.getPairs()) {
                int at = ids.indexOf(paired.get(p.getId()));
                if (at < 0 || !p.getId().equals(shown.get(at))) return false;
            }
            return true;
        }
        return false;
    }

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

    private static Set<String> set(String answer) { return Set.copyOf(list(answer)); }

    /** A key with nothing in it (a sealed copy) is never matched — not even by an empty answer. */
    private static boolean same(Set<String> answer, List<String> key) { return !key.isEmpty() && answer.equals(Set.copyOf(key)); }
}
