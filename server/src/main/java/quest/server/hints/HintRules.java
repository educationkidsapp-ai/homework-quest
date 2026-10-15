package quest.server.hints;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.api.dto.Tile;

/**
 * Feature 30: what a hint may be, and what it may never say.
 *
 * <p>Two rules, and they are the whole feature's safety. A hint is <strong>at most {@value #MAX_CHARS}
 * characters</strong> — Prompt B's own limit for the single `hint` a stop already carries, so an approved hint fits
 * wherever that one did — and a hint <strong>never contains the stop's answer</strong>. The second one is the rule
 * the owner asked for: a step-by-step hint that hands the child the correct option's words is not help, it is the
 * answer with extra steps, and the model writes one often enough that it has to be checked rather than requested.
 *
 * <p>{@link #answerWords} is also sent <em>to</em> the model, as {@code doNotSay} in {@link
 * quest.server.analysis.Prompts#userSmartHints} — telling it first is what makes the first attempt usually right,
 * and checking it afterwards is what makes the rule true.
 */
public final class HintRules {
    private HintRules() {}

    /** Prompt B's limit for a stop's `hint`, and therefore for each of these. */
    public static final int MAX_CHARS = 90;
    /** A nudge, then a stronger pointer. Never one, never three. */
    public static final int COUNT = 2;

    /** True for a stop a child answers, and therefore a stop that can be hinted at: no pages, no open stops. */
    public static boolean answerable(Stop stop) {
        return stop.getCategory() == StopCategory.SINGLE || stop.getCategory() == StopCategory.MULTI;
    }

    /** Every answerable stop of a play, the three inside an exitTicket included, in the order a child meets them. */
    public static List<Stop> answerableStops(quest.api.dto.Play play) {
        var out = new ArrayList<Stop>();
        for (Stop s : play.getStops()) {
            if (answerable(s)) out.add(s);
            if (s instanceof Stop.ExitTicket et) for (Stop q : et.getQuestions()) if (answerable(q)) out.add(q);
        }
        return out;
    }

    /**
     * The words this stop's hints may not contain: the correct option's label, the number that is the answer, the
     * spoken word, `true`/`false`, the comparison sign.
     *
     * <p>A {@code trace} stop has none — the letter it asks for is drawn on the screen, so naming it is the task
     * rather than the answer.
     */
    public static List<String> answerWords(Stop stop) {
        Set<String> out = new LinkedHashSet<>();
        if (stop instanceof Stop.SingleAnswer single) out.addAll(optionLabels(stop, List.of(single.getCorrectId())));
        switch (stop) {
            case Stop.TrueFalse tf -> out.add(Boolean.toString(tf.getAnswer()));
            case Stop.Count count -> out.add(Integer.toString(count.getTotal()));
            case Stop.Word word -> out.add(word.getSpokenWord());
            case Stop.ReadTap readTap -> out.add(readTap.getWord());
            case Stop.MultiSelect ms -> out.addAll(optionLabels(stop, ms.getCorrectIds()));
            case Stop.SelectAll sa -> out.addAll(optionLabels(stop, sa.getCorrectIds()));
            case Stop.WriteSentence ws -> out.add(ws.getAnswer());
            default -> { }
        }
        return out.stream().filter(w -> w != null && !w.isBlank()).toList();
    }

    /** What the teacher is shown beside the hints, and what the model is given as the question: never the answer. */
    public static String question(Stop stop) {
        return switch (stop) {
            case Stop.Choice choice -> choice.getQuestion();
            case Stop.TrueFalse tf -> tf.getStatement();
            case Stop.MultiSelect ms -> ms.getPrompt();
            case Stop.SelectAll sa -> sa.getPrompt();
            case Stop.Match match -> match.getPrompt();
            case Stop.Order order -> order.getPrompt();
            case Stop.WriteSentence ws -> ws.getFrame();
            default -> stop.getSpeak();
        };
    }

    /**
     * What is wrong with one stop's hints, in the words the next attempt is told — empty when they are good.
     *
     * <p>Every error names the stop, because one call writes the hints of a whole lesson and the retry turn has to
     * say which of thirty questions to write again.
     */
    public static List<String> errors(String stopId, List<String> hints, List<String> answerWords) {
        var errors = new ArrayList<String>();
        if (hints == null || hints.size() != COUNT) { errors.add(stopId + ": write exactly " + COUNT + " hints"); return errors; }
        for (int i = 0; i < hints.size(); i++) {
            String hint = hints.get(i);
            String which = stopId + " hint " + (i + 1);
            if (hint == null || hint.isBlank()) { errors.add(which + ": is empty"); continue; }
            if (hint.length() > MAX_CHARS) errors.add(which + ": is " + hint.length() + " characters, at most " + MAX_CHARS + " allowed");
            String leaked = leak(hint, answerWords);
            if (leaked != null) errors.add(which + ": gives the answer away (\"" + leaked + "\") — point at the method instead");
        }
        return errors;
    }

    /** The answer this hint says out loud, or null. Public because it is the rule itself, and tests assert on it. */
    public static String leak(String hint, List<String> answerWords) {
        String lower = hint.toLowerCase(Locale.ROOT);
        var words = new LinkedHashSet<String>(List.of(lower.split("[^\\p{L}\\p{N}]+")));
        for (String answer : answerWords) {
            String needle = answer.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) continue;
            // A sign ("<", ">", "=") survives no tokenising, so it is looked for in the sentence as written; a word or
            // a number is matched whole, so "ten" does not flag "often" and "4" does not flag "14".
            boolean symbolOnly = needle.chars().noneMatch(Character::isLetterOrDigit);
            if (symbolOnly ? lower.contains(needle)
                    : needle.contains(" ") ? lower.contains(needle) : words.contains(needle)) return answer.trim();
        }
        return null;
    }

    /** The labels of the named options of a stop that has options, whatever shape they take. */
    private static List<String> optionLabels(Stop stop, List<String> ids) {
        var out = new ArrayList<String>();
        switch (stop) {
            case Stop.Choice choice -> { for (Tile t : choice.getOptions()) if (ids.contains(t.getId()) && t.getLabel() != null) out.add(t.getLabel()); }
            case Stop.MultiSelect ms -> { for (Tile t : ms.getOptions()) if (ids.contains(t.getId()) && t.getLabel() != null) out.add(t.getLabel()); }
            case Stop.SelectAll sa -> { for (Tile t : sa.getOptions()) if (ids.contains(t.getId()) && t.getLabel() != null) out.add(t.getLabel()); }
            case Stop.Sequence sequence -> { for (var o : sequence.getOptions()) if (ids.contains(o.getId())) out.add(o.getLabel()); }
            case Stop.Count count -> { for (var o : count.getOptions()) if (ids.contains(o.getId())) out.add(o.getLabel()); }
            case Stop.Compare compare -> { for (var o : compare.getOptions()) if (ids.contains(o.getId())) out.add(o.getLabel()); }
            case Stop.Sound sound -> { for (var o : sound.getOptions()) if (ids.contains(o.getId())) out.add(o.getLabel()); }
            case Stop.Word word -> { for (var o : word.getOptions()) if (ids.contains(o.getId())) out.add(o.getLabel()); }
            default -> { }
        }
        return out;
    }
}
