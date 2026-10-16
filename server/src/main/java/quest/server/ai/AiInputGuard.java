package quest.server.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the provider is not told. Opt-in per {@link AiTask}, because a feature that sends only its own leader's words
 * has nothing to mask and masking it would change what she reads back.
 *
 * <p>It replaces e-mail addresses with {@code [email]}, numbers long enough to be a telephone number with
 * {@code [phone]}, and every name the caller passes in — a child's, a parent's, an employee's — with {@code [name]}.
 * Names are matched case-insensitively and longest first, so "Nada Alqahtani" is one {@code [name]} rather than two
 * halves of one; Arabic needs no special case, since the match is on the literal text, and Arabic-Indic digits count
 * as digits.
 *
 * <p>Nine digits is the phone threshold, which is deliberately above the eight an ISO date carries: a feature that
 * tells the model today's date (every one of them does) must not have it masked, and a Saudi number is ten. A date
 * is also not allowed to <em>join</em> what follows it: "20/10/2026 10:30" is ten digits in one run and would have
 * been masked whole, so a run is trimmed of a leading or trailing date before its digits are counted
 * ({@link #DATE}) — a translated message keeps the day of the trip and the hour of it.
 *
 * <p>It is a guard, not a promise about free text: a transcript can still describe somebody without naming her. What
 * it does promise is that the <em>records</em> a feature reads out of the school's own tables — the names, addresses
 * and numbers it looked up — do not travel to the provider.
 */
public record AiInputGuard(List<String> names) {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /** A run of digits and the separators a number is written with; whether it is long enough is counted, not matched. */
    private static final Pattern NUMBERISH = Pattern.compile("\\+?[\\d\\u0660-\\u0669][\\d\\u0660-\\u0669 .()\\u00a0/-]{5,}[\\d\\u0660-\\u0669]");
    private static final int PHONE_DIGITS = 9;
    /** A written date, in either order and with either separator: `2026-10-20`, `20/10/2026`, `20-10-2026`. */
    private static final Pattern DATE = Pattern.compile("\\d{1,4}([/-])\\d{1,2}\\1\\d{1,4}");
    public static final String EMAIL_MASK = "[email]", PHONE_MASK = "[phone]", NAME_MASK = "[name]";

    public AiInputGuard(List<String> names) {
        var cleaned = new ArrayList<String>();
        for (String n : names == null ? List.<String>of() : names) if (n != null && n.strip().length() > 1) cleaned.add(n.strip());
        cleaned.sort(Comparator.comparingInt(String::length).reversed());
        this.names = List.copyOf(cleaned);
    }

    /** Masks e-mail addresses and telephone numbers and nothing else. */
    public static AiInputGuard numbersOnly() { return new AiInputGuard(List.of()); }
    public static AiInputGuard masking(String... names) { return new AiInputGuard(List.of(names)); }
    public static AiInputGuard masking(List<String> names) { return new AiInputGuard(names); }

    /** The text as the provider may see it. */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = maskPhones(EMAIL.matcher(text).replaceAll(EMAIL_MASK));
        for (String name : names) out = Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out).replaceAll(NAME_MASK);
        return out;
    }

    private static String maskPhones(String text) {
        Matcher m = NUMBERISH.matcher(text);
        var out = new StringBuilder(text.length());
        int from = 0;
        while (m.find()) {
            int start = m.start(), end = m.end();
            // A date is not part of a telephone number, however the run ran into it: a leading or trailing date
            // (and the separators between it and the rest) leaves the run before its digits are counted.
            var dates = new ArrayList<int[]>();
            var date = DATE.matcher(text.substring(start, end));
            while (date.find()) dates.add(new int[] {start + date.start(), start + date.end()});
            for (int[] d : dates) if (d[0] == start) { start = d[1]; while (start < end && !Character.isDigit(text.charAt(start))) start++; }
            for (int[] d : dates) if (d[1] == end && d[0] >= start) { end = d[0]; while (end > start && !Character.isDigit(text.charAt(end - 1))) end--; }
            if (start >= end || digits(text.substring(start, end)) < PHONE_DIGITS) continue;
            out.append(text, from, start).append(PHONE_MASK);
            from = end;
        }
        return from == 0 ? text : out.append(text, from, text.length()).toString();
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) n++;
        return n;
    }
}
