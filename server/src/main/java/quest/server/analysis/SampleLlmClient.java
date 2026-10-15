package quest.server.analysis;

import java.util.List;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.SourceAnalysis;
import quest.api.samples.HotSoupSeed;
import quest.api.samples.MathSeed;
import quest.api.samples.PhonicsSeed;
import quest.api.validation.SchemaValidator;

/** No network: answers with the seeded Hot Soup / math / phonics content. Used with FAKE_LLM=true and in tests. */
public class SampleLlmClient implements LlmClient {
    @Override public String name() { return "sample"; }

    @Override public Result complete(String system, String user, List<Attachment> attachments) {
        var json = SchemaValidator.INSTANCE.getJson();
        String header = user.substring(0, Math.min(400, user.length())).toLowerCase();   // "Course: … subject: math." + notes, or the analysis kind
        boolean math = header.contains("subject: math") || user.contains("\"kind\":\"math\"");
        boolean phonics = !math && (user.contains("\"kind\":\"phonics\"") || header.contains("phonics") || header.contains("sound"));
        if (system.equals(Prompts.SYSTEM_A)) {
            SourceAnalysis a = math ? MathSeed.INSTANCE.getAnalysis() : phonics ? PhonicsSeed.INSTANCE.getAnalysis() : HotSoupSeed.INSTANCE.getAnalysis();
            return new Result(json.encodeToString(SourceAnalysis.Companion.serializer(), a), 1200, 800);
        }
        if (system.equals(Prompts.SYSTEM_C)) {
            ParentPanel p = math ? MathSeed.INSTANCE.getParentPanel() : phonics ? PhonicsSeed.INSTANCE.getParentPanel() : HotSoupSeed.INSTANCE.getParentPanel();
            return new Result(json.encodeToString(ParentPanel.Companion.serializer(), p), 3000, 600);
        }
        if (system.startsWith(PROMPT_D)) return stopFromText(user);
        if (system.equals(Prompts.SYSTEM_E)) return announcementFromTranscript(user);
        if (system.equals(Prompts.SYSTEM_F)) return briefFromFacts(user);
        if (system.equals(Prompts.DOCUMENT_READER)) return summaryFromDocument(user);
        if (system.equals(Prompts.TRANSLATE)) return markedTranslation(user);
        if (user.startsWith("Here is a Play you wrote earlier")) {
            int i = user.indexOf("different content:\n") + "different content:\n".length();
            String stop = user.substring(i, user.indexOf("\n\nDo not reuse", i)).trim();
            return new Result(stop.replaceFirst("\"id\":\"([^\"]+)\"", "\"id\":\"$1x\""), 2000, 400);
        }
        var lesson = math ? MathSeed.INSTANCE.getLesson() : phonics ? PhonicsSeed.INSTANCE.getLesson() : HotSoupSeed.INSTANCE.getLesson();
        int level = user.contains("LEVEL 2") ? 2 : user.contains("LEVEL 3") ? 3 : 1;
        boolean variant = user.contains("AGAIN variant");
        Play play = lesson.play(level, variant ? 1 : 0);
        return new Result(json.encodeToString(Play.Companion.serializer(), play), 4000, 2500);
    }

    /** The first line of {@link Prompts#SYSTEM_D}, which carries a schema and so cannot be matched whole. */
    private static final String PROMPT_D = "You turn a teacher's description of one practice stop";

    /**
     * CR5's text → JSON turn without a model: the stop exactly as it was stored, with the heading line the teacher
     * wrote as its `title` and her "Pip says:" line as `speak`. Deterministic, which is what lets the e2e press Save
     * and assert on the result; every other field survives, which is the behaviour the real Prompt D is asked for.
     */
    private Result stopFromText(String user) {
        String current = between(user, "did not ask you to change):\n", "\n\nWHAT THE TEACHER WROTE");
        String text = between(user, "follow the teacher):\n", "\n\nRules:");
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(current);
            if (!lines.isEmpty()) node.put("title", cut(lines.get(0), 40));
            if (lines.size() > 1) node.put("speak", cut(lines.get(1).replaceFirst("^Pip says:\\s*", ""), 90));
            return new Result(node.toString(), 900, 300);
        } catch (Exception e) { return new Result(current, 900, 300); }
    }

    /**
     * Feature 22's turn without a model: the first line of the transcript becomes the title, the whole transcript
     * becomes both bodies, the <em>first</em> audience line offered is copied into `audience`, and an ISO date written
     * anywhere in the transcript becomes `expiresOn`. Deterministic, which is what lets the API test and the local H2
     * run press the button and assert on what comes back — and it exercises the real checks either way, because the
     * server still has to recognise that first cell as one of the caller's own.
     */
    private Result announcementFromTranscript(String user) {
        String cells = between(user, "out for all of them):\n", "\n\nWHAT SHE SAID");
        String transcript = between(user, "punctuation may be missing):\n", "\n\nYour previous answer").strip();
        List<String> lines = transcript.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("title", cut(lines.isEmpty() ? "Announcement" : lines.get(0), 120));
        out.put("bodyAr", cut(transcript, 1000));
        out.put("bodyEn", cut(transcript, 1000));
        var cell = CELL.matcher(cells);
        if (cell.find()) out.putObject("audience").put("curriculum", cell.group(1)).put("grade", Integer.parseInt(cell.group(2)));
        var day = ISO_DAY.matcher(transcript);
        if (day.find()) out.put("expiresOn", day.group());
        return new Result(out.toString(), 700, 400);
    }

    /**
     * Feature 25's turn without a model: the first three to five fact lines the server offered become the brief,
     * word for word, in both lists. Deterministic, and it is deliberately the <em>dullest</em> possible answer —
     * every number in it came from the facts, so the local H2 run and the API test exercise the accepting path of
     * the validator, while a test that wants the rejecting path mocks {@code LlmClient} and invents a number.
     *
     * <p>The Arabic list is the same lines with the school's own label in front, because the sample client has no
     * translator and a brief whose two lists disagreed in <em>length</em> would be rejected rather than merely
     * untranslated — which would make the fallback, not the accepting path, what the local run exercises.
     */
    private Result briefFromFacts(String user) {
        String block = between(user, "only numbers you may write):\n", "\n\nYour previous answer");
        List<String> facts = block.lines().map(String::strip).filter(l -> l.startsWith("- ")).map(l -> l.substring(2).strip())
                .filter(l -> !l.isEmpty()).limit(5).toList();
        if (facts.isEmpty()) facts = List.of("nothing needs her today");
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var ar = out.putArray("linesAr");
        var en = out.putArray("linesEn");
        for (String fact : facts) { en.add(cut(fact, 110)); ar.add(cut("الموجز: " + fact, 110)); }
        while (en.size() < 3) { en.add("Nothing else needs you now."); ar.add("لا شيء آخر يحتاجك الآن"); }
        return new Result(out.toString(), 600, 300);
    }

    /**
     * Feature 26's turn without a model: the first line of the extracted text becomes the title, the whole text
     * becomes both summaries, every ISO date written in it becomes a key date, and every line that begins with a `-`
     * becomes an action. Deterministic, which is what lets the API test and the local H2 run upload a circular and
     * assert on what comes back — and it exercises the real checks either way, because the server still has to find
     * those dates parseable and the whole answer inside its limits.
     *
     * <p>An announcement is suggested only when the text says "parents", which is as close as a client with no model
     * can come to the prompt's own rule and is enough for the dashboard's "Draft announcement" to have something to
     * open with.
     */
    private Result summaryFromDocument(String user) {
        String fileName = between(user, "\nTHE FILE: ", "\n");
        String body = between(user, "headings are ours):\n", "\n\nYour previous answer").strip();
        List<String> lines = body.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("## Page")).toList();
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("title", cut(lines.isEmpty() ? fileName : lines.get(0), 120));
        out.put("summaryAr", cut(body, 1200));
        out.put("summaryEn", cut(body, 1200));
        var dates = out.putArray("keyDates");
        var day = ISO_DAY.matcher(body);
        while (day.find() && dates.size() < 10)
            dates.addObject().put("date", day.group()).put("labelAr", "موعد").put("labelEn", "A date in " + fileName);
        var ar = out.putArray("actionsAr"); var en = out.putArray("actionsEn");
        for (String line : lines) {
            if (!line.startsWith("-") || ar.size() >= 10) continue;
            String action = cut(line.substring(1).strip(), 200);
            ar.add(action); en.add(action);
        }
        if (body.toLowerCase().contains("parent") || body.contains("أولياء"))
            out.putObject("announcement").put("title", out.get("title").asText())
                    .put("bodyAr", cut(body, 1000)).put("bodyEn", cut(body, 1000));
        return new Result(out.toString(), 1100, 500);
    }

    /**
     * Feature 29's turn without a model: the source text with the target language's code in front of it, e.g.
     * `[ur] Please send her reading book tomorrow.`. A marker rather than a real translation, and deliberately so —
     * it is deterministic, it carries the source through so a test can see that nothing was lost, it adds no link,
     * and it stays inside the validator's length bound for any source. What the API test then proves is the
     * machinery: the cache, the hash, who may ask, and that the original is untouched.
     */
    private Result markedTranslation(String user) {
        var code = LANG.matcher(between(user, "TRANSLATE INTO: ", "\n"));
        String text = between(user, "nothing but the translation):\n", "\n\nYour previous answer");
        return new Result("[" + (code.find() ? code.group(1) : "xx") + "] " + text, 300, 200);
    }

    /** The code in `Urdu (ur)`, which {@link Prompts#userTranslate} is given. */
    private static final java.util.regex.Pattern LANG = java.util.regex.Pattern.compile("\\(([a-z]{2,3})\\)");

    /** One audience line of {@link Prompts#userE}: `curriculum "british", grade 1`. */
    private static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("curriculum \"([a-z]+)\", grade (\\d+)");
    private static final java.util.regex.Pattern ISO_DAY = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max).strip(); }

    private static String between(String whole, String after, String before) {
        int from = whole.indexOf(after); if (from < 0) return "{}";
        from += after.length();
        int to = whole.indexOf(before, from);
        return (to < 0 ? whole.substring(from) : whole.substring(from, to)).strip();
    }
}
