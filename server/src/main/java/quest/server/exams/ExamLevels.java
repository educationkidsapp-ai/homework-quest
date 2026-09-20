package quest.server.exams;

import java.util.List;
import java.util.Locale;
import quest.server.config.ApiException;

/**
 * The two small vocabularies of §8, so a typo in a request body is a 400 with the options in it rather than a row
 * nothing will ever read. They mirror `quest.api.dashboard.ExamLevels` / `ExamRelease` in the contract.
 */
public final class ExamLevels {
    private ExamLevels() {}

    /** §8: "one level only — the teacher picks 1, 2 or 3, or *mixed*". */
    public static final String ONE = "1", TWO = "2", THREE = "3", MIXED = "mixed";
    public static final List<String> ALL = List.of(ONE, TWO, THREE, MIXED);

    /** §8: "results release: automatic on close or manual". */
    public static final String AUTO_ON_CLOSE = "auto_on_close", MANUAL = "manual";
    public static final List<String> RELEASE_MODES = List.of(AUTO_ON_CLOSE, MANUAL);

    /** The play level a given setting is sat over; `mixed` has none of its own and is assembled. */
    public static Integer levelOf(String level) {
        return switch (level) { case ONE -> 1; case TWO -> 2; case THREE -> 3; default -> null; };
    }

    public static String requireLevel(String raw) { return one(raw, ALL, "a level (1, 2, 3 or mixed)"); }

    public static String requireReleaseMode(String raw) { return one(raw, RELEASE_MODES, "a release mode (auto_on_close or manual)"); }

    private static String one(String raw, List<String> allowed, String what) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(value)) return value;
        throw ApiException.badRequest("`" + raw + "` is not " + what + ".");
    }
}
